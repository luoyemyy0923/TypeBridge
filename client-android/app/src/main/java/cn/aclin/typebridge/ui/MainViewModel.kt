package cn.aclin.typebridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cn.aclin.typebridge.data.HistoryRepository
import cn.aclin.typebridge.data.QuickInputRepository
import cn.aclin.typebridge.discovery.MdnsResolver
import cn.aclin.typebridge.model.ActionItem
import cn.aclin.typebridge.model.Device
import cn.aclin.typebridge.model.HistoryItem
import cn.aclin.typebridge.model.QuickInput
import cn.aclin.typebridge.model.QuickInputGroup
import cn.aclin.typebridge.network.AppConnState
import cn.aclin.typebridge.network.Crypto
import cn.aclin.typebridge.network.DisconnectReason
import cn.aclin.typebridge.network.RescanReason
import cn.aclin.typebridge.network.WebSocketClient
import cn.aclin.typebridge.scan.QrPayload
import cn.aclin.typebridge.storage.ActionPrefs
import cn.aclin.typebridge.storage.DeviceIdentity
import cn.aclin.typebridge.storage.DeviceStore
import cn.aclin.typebridge.storage.QuickInputPrefs
import cn.aclin.typebridge.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext: Application
        get() = getApplication()

    private val _state = MutableStateFlow<AppConnState>(AppConnState.Disconnected)
    val state: StateFlow<AppConnState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _currentDevice = MutableStateFlow<Device?>(null)
    val currentDevice: StateFlow<Device?> = _currentDevice.asStateFlow()

    /** 最后一次连接成功的服务名称；设备记录被删除后仍用于界面展示。 */
    private val _lastServerName = MutableStateFlow("")
    val lastServerName: StateFlow<String> = _lastServerName.asStateFlow()

    private val _actions = MutableStateFlow<List<ActionItem>>(emptyList())
    val actions: StateFlow<List<ActionItem>> = _actions.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _quickInputs = MutableStateFlow<List<QuickInput>>(emptyList())
    val quickInputs: StateFlow<List<QuickInput>> = _quickInputs.asStateFlow()

    private val _quickGroups = MutableStateFlow<List<QuickInputGroup>>(emptyList())
    val quickGroups: StateFlow<List<QuickInputGroup>> = _quickGroups.asStateFlow()

    /** 已折叠的分组 id；随界面状态持久化。 */
    private val _collapsedQuickGroups = MutableStateFlow<Set<Long>>(emptySet())
    val collapsedQuickGroups: StateFlow<Set<Long>> = _collapsedQuickGroups.asStateFlow()

    private val historyRepository = HistoryRepository()
    private val quickInputRepository = QuickInputRepository()

    private val _sendEnter = MutableStateFlow(true)
    val sendEnter: StateFlow<Boolean> = _sendEnter.asStateFlow()

    private var activeClient: WebSocketClient? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var isInForeground = false
    private var userRequestedDisconnect = false
    private var triedMdnsFallback = false
    private var lastServerError = ""
    private var mdnsResolver: MdnsResolver? = null
    /** 重发时新消息 id → 原历史 id，用于回执只更新原记录而不新增历史 */
    private val pendingResendIds = mutableMapOf<String, String>()

    fun bootstrap() {
        _devices.value = DeviceStore.list(appContext)
        _actions.value = ActionPrefs.list(appContext)
        _sendEnter.value = DeviceStore.getSendEnter(appContext)
        _collapsedQuickGroups.value = QuickInputPrefs.collapsed(appContext)
        scope.launch {
            historyRepository.migrateLegacyIfNeeded(appContext)
            _history.value = historyRepository.list(appContext)
            loadQuickInputs()
        }
        val last = DeviceStore.lastConnected(appContext) ?: return
        _currentDevice.value = last
        // 有凭据时提前进入连接中状态，避免启动时先闪一下设备列表
        if (last.psk != null) {
            _state.value = AppConnState.Connecting
        }
        // 首次启动不立即连接，等 onStart 触发
    }

    fun onAppForegrounded() {
        isInForeground = true
        userRequestedDisconnect = false
        if (_state.value is AppConnState.NeedRescan) return
        val device = _currentDevice.value ?: return
        if (activeClient?.isOpen() == true) return
        if (device.psk == null) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.AuthFailed,
                detail = "本设备缺少 PSK，请重新扫码"
            )
            return
        }
        beginConnect(device, firstAttempt = true)
    }

    fun onAppBackgrounded() {
        isInForeground = false
        reconnectJob?.cancel()
        activeClient?.disconnect()
        activeClient = null
        _state.value = AppConnState.Disconnected
    }

    /** 扫码成功后调用。 */
    fun connectWithScannedDevice(payload: QrPayload) {
        val phoneIp = NetworkUtils.currentWifiIp(appContext)
        if (phoneIp == null) {
            _state.value = AppConnState.NeedRescan(RescanReason.NoWifi)
            return
        }
        if (!NetworkUtils.isLikelySameLan(phoneIp, payload.ip)) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.WrongSubnet,
                detail = "手机 $phoneIp / 电脑 ${payload.ip}"
            )
            return
        }

        reconnectJob?.cancel()
        activeClient?.disconnect()
        activeClient = null
        reconnectAttempt = 0
        userRequestedDisconnect = false
        triedMdnsFallback = false
        lastServerError = ""

        val existing = _devices.value.firstOrNull {
            it.fingerprint.equals(payload.fp, ignoreCase = true) && it.psk != null
        }
        val device = Device(
            deviceId = existing?.deviceId ?: "pending-${payload.fp}",
            ip = payload.ip,
            mdns = payload.mdns,
            port = payload.port,
            serverName = existing?.serverName.orEmpty(),
            firstSeenAt = existing?.firstSeenAt ?: 0L,
            lastSeenAt = System.currentTimeMillis(),
            psk = payload.psk,
            fingerprint = payload.fp,
            sessionToken = null,
            sessionKey = null,
            sessionId = null,
            actionVersion = 0
        )

        DeviceStore.save(appContext, device)
        _devices.value = DeviceStore.list(appContext)
        _currentDevice.value = device
        beginConnect(device, firstAttempt = true)
    }

    private fun beginConnect(device: Device, firstAttempt: Boolean) {
        val psk = device.psk
        if (psk == null) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.AuthFailed,
                detail = "本设备缺少 PSK，请重新扫码"
            )
            return
        }

        reconnectJob?.cancel()
        activeClient?.disconnect()
        activeClient = null
        pendingResendIds.clear()
        _state.value = AppConnState.Connecting

        val clientHolder = arrayOfNulls<WebSocketClient>(1)
        val listener = object : WebSocketClient.ClientListener {
            override fun onConnected(
                serverId: String?,
                known: Boolean,
                serverName: String,
                firstSeenAt: Long,
                sessionToken: String?
            ) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return

                reconnectAttempt = 0
                triedMdnsFallback = false
                lastServerError = ""
                if (serverName.isNotBlank()) {
                    _lastServerName.value = serverName
                }
                _state.value = AppConnState.Connected(serverName, known)

                val resolvedId = serverId ?: device.deviceId
                val existing = _devices.value.firstOrNull { it.deviceId == resolvedId }
                val merged = device.copy(
                    deviceId = resolvedId,
                    serverName = serverName,
                    firstSeenAt = when {
                        firstSeenAt > 0L -> firstSeenAt
                        existing != null -> existing.firstSeenAt
                        else -> device.lastSeenAt
                    },
                    lastSeenAt = System.currentTimeMillis(),
                    sessionToken = sessionToken ?: device.sessionToken,
                    sessionKey = client.sessionKeyBase64,
                    sessionId = client.sessionIdValue
                )

                if (device.deviceId != resolvedId) {
                    DeviceStore.remove(appContext, device.deviceId)
                }
                DeviceStore.save(appContext, merged)
                _currentDevice.value = merged
                _devices.value = DeviceStore.list(appContext)
            }

            override fun onDisconnected(reason: DisconnectReason) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return
                activeClient = null
                pendingResendIds.clear()

                if (!isInForeground || userRequestedDisconnect) {
                    _state.value = AppConnState.Disconnected
                    return
                }
                handleDisconnected(device, reason)
            }

            override fun onAck(id: String, ok: Boolean) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return
                val originalId = pendingResendIds.remove(id)
                updateHistoryStatus(originalId ?: id, if (ok) "已送达" else "失败")
            }

            override fun onPong() = Unit

            override fun onStatus(state: String, devices: Int) = Unit

            override fun onActions(version: Int, actions: JsonArray) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return
                val parsed = ActionPrefs.replaceAll(appContext, version, actions)
                _actions.value = parsed
                _currentDevice.value?.deviceId?.let {
                    DeviceStore.updateActionVersion(appContext, it, version)
                }
            }

            override fun onActionUpdate(version: Int, change: String, action: JsonObject) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return
                _actions.value = ActionPrefs.applyUpdate(appContext, version, change, action)
            }

            override fun onServerError(code: Int, msg: String) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return
                handleServerError(code, msg)
            }

            override fun onFingerprintMismatch(serverFingerprint: String) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return
                activeClient = null
                userRequestedDisconnect = true
                lastServerError = "服务器指纹 $serverFingerprint 与二维码不一致"
                _state.value = AppConnState.NeedRescan(
                    RescanReason.FingerprintMismatch,
                    lastServerError
                )
            }

            override fun onHandshakeError(msg: String) {
                val client = clientHolder[0] ?: return
                if (activeClient !== client) return
                if (!isInForeground || userRequestedDisconnect) {
                    _state.value = AppConnState.Disconnected
                    return
                }
                lastServerError = msg
                activeClient = null
                handleDisconnected(device, DisconnectReason.Network)
            }
        }

        val client = WebSocketClient(
            serverUrl = "ws://${device.ip}:${device.port}/typebridge",
            deviceId = DeviceIdentity.get(appContext),
            deviceName = DeviceIdentity.getDeviceName(appContext),
            psk = psk,
            expectedFingerprint = device.fingerprint.orEmpty(),
            cachedSessionToken = device.sessionToken,
            cachedSessionKey = device.sessionKey?.let(Crypto::b64d),
            cachedSessionId = device.sessionId,
            listener = listener
        )
        clientHolder[0] = client
        activeClient = client
        client.connect()
    }

    private fun handleDisconnected(device: Device, reason: DisconnectReason) {
        if (reason == DisconnectReason.FingerprintMismatch) {
            _state.value = AppConnState.NeedRescan(RescanReason.FingerprintMismatch)
            return
        }

        val mdnsName = device.mdns
        if (reason == DisconnectReason.UnknownHost && mdnsName != null && !triedMdnsFallback) {
            triedMdnsFallback = true
            _state.value = AppConnState.AutoReconnecting(1, reason)

            val resolver = mdnsResolver ?: MdnsResolver(appContext).also { mdnsResolver = it }
            resolver.resolve(mdnsName) { resolved ->
                scope.launch {
                    if (!isInForeground) return@launch
                    val current = _currentDevice.value ?: return@launch
                    if (resolved != null && resolved != current.ip) {
                        val updated = current.copy(ip = resolved)
                        DeviceStore.save(appContext, updated)
                        _currentDevice.value = updated
                        beginConnect(updated, firstAttempt = false)
                    } else {
                        startAutoReconnect(current, reason)
                    }
                }
            }
            return
        }

        startAutoReconnect(device, reason)
    }

    private fun startAutoReconnect(device: Device, reason: DisconnectReason) {
        if (reconnectAttempt >= MAX_ATTEMPTS) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.MacUnreachable,
                detail = reason.name
            )
            return
        }

        val phoneIp = NetworkUtils.currentWifiIp(appContext)
        if (phoneIp == null) {
            _state.value = AppConnState.NeedRescan(RescanReason.NoWifi)
            return
        }
        if (!NetworkUtils.isLikelySameLan(phoneIp, device.ip)) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.WrongSubnet,
                detail = "手机 $phoneIp / ${device.serverName.ifBlank { "电脑" }} ${device.ip}"
            )
            return
        }

        reconnectAttempt += 1
        val delayMs = when (reconnectAttempt) {
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            else -> 15_000L
        }
        _state.value = AppConnState.AutoReconnecting(reconnectAttempt, reason)

        reconnectJob = scope.launch {
            delay(delayMs)
            if (!isInForeground) return@launch
            val current = _currentDevice.value ?: return@launch
            beginConnect(current, firstAttempt = false)
        }
    }

    private fun handleServerError(code: Int, msg: String) {
        val displayName = _currentDevice.value?.serverName?.takeIf { it.isNotBlank() } ?: "电脑"
        lastServerError = when {
            code == 4006 -> "$displayName 已移除该设备"
            msg.isBlank() -> "错误码 $code"
            else -> "$code $msg"
        }

        if (code == 4004) {
            // 指令已被移除：保持连接，等待 Mac 下一次指令库推送
            return
        }

        userRequestedDisconnect = true
        activeClient?.disconnect()
        activeClient = null

        when (code) {
            4001 -> {
                _currentDevice.value?.let { device ->
                    DeviceStore.clearPsk(appContext, device.deviceId)
                    _currentDevice.value = device.copy(
                        psk = null,
                        sessionToken = null,
                        sessionKey = null,
                        sessionId = null
                    )
                    _devices.value = DeviceStore.list(appContext)
                }
                _state.value = AppConnState.NeedRescan(RescanReason.AuthFailed, msg)
            }

            4006 -> {
                // Mac 主动移除了该设备：本地也删除配对记录（已无法重连），提示重新扫码
                _currentDevice.value?.let { device ->
                    DeviceStore.remove(appContext, device.deviceId)
                }
                _currentDevice.value = null
                _devices.value = DeviceStore.list(appContext)
                _state.value = AppConnState.NeedRescan(RescanReason.DeviceRemoved)
            }

            4002 -> _state.value = AppConnState.NeedRescan(
                RescanReason.MacUnreachable,
                "协议版本不兼容，请升级 App 或电脑端"
            )

            4003 -> _state.value = AppConnState.NeedRescan(RescanReason.Expired, msg)

            4005 -> _state.value = AppConnState.NeedRescan(
                RescanReason.MacUnreachable,
                "$displayName 已达连接上限"
            )

            else -> _state.value = AppConnState.NeedRescan(
                RescanReason.MacUnreachable,
                lastServerError
            )
        }
    }

    fun manualRetry() {
        val device = _currentDevice.value ?: return
        if (device.psk == null) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.AuthFailed,
                detail = "本设备缺少 PSK，请重新扫码"
            )
            return
        }
        userRequestedDisconnect = false
        reconnectAttempt = 0
        beginConnect(device, firstAttempt = true)
    }

    fun connectDevice(device: Device) {
        _currentDevice.value = device
        DeviceStore.save(appContext, device)
        manualRetry()
    }

    fun manualDisconnect() {
        userRequestedDisconnect = true
        reconnectJob?.cancel()
        activeClient?.disconnect()
        activeClient = null
        _state.value = AppConnState.Disconnected
    }

    fun send(text: String) {
        if (text.isBlank()) return
        dispatchText(text, record = true, enter = _sendEnter.value)
    }

    /**
     * 发送一条快捷输入：
     * - 明文记录正常发送并写入历史
     * - 加密记录发送时按需从数据库解密，且不写入任何一端的历史；
     *   同时标记 secure=true，让 Mac 禁用剪贴板兜底
     * - 是否回车使用该条记录自己的开关，而不是全局开关
     */
    fun sendQuickInput(input: QuickInput) {
        if (_state.value !is AppConnState.Connected) return
        if (!input.encrypted) {
            dispatchText(input.text, record = true, enter = input.enter)
            return
        }
        scope.launch {
            val plain = quickInputRepository.textForSend(appContext, input.id) ?: return@launch
            dispatchText(plain, record = false, enter = input.enter, secure = true)
        }
    }

    private fun dispatchText(
        text: String,
        record: Boolean,
        enter: Boolean,
        secure: Boolean = false
    ) {
        if (text.isBlank()) return
        if (_state.value !is AppConnState.Connected) return
        val id = activeClient?.sendText(text, enter, record, secure) ?: return
        if (!record) return

        val item = HistoryItem(
            id = id,
            text = text,
            sentAt = System.currentTimeMillis(),
            status = "发送中",
            enter = enter,
            deviceName = _currentDevice.value?.serverName.orEmpty()
        )
        _history.value = listOf(item) + _history.value
        scope.launch { historyRepository.save(appContext, item) }
    }

    fun saveQuickInput(input: QuickInput) {
        scope.launch {
            quickInputRepository.save(appContext, input)
            loadQuickInputs()
        }
    }

    fun deleteQuickInput(input: QuickInput) {
        scope.launch {
            quickInputRepository.delete(appContext, input.id)
            loadQuickInputs()
        }
    }

    fun saveQuickGroup(group: QuickInputGroup) {
        scope.launch {
            quickInputRepository.saveGroup(appContext, group)
            loadQuickInputs()
        }
    }

    fun deleteQuickGroup(group: QuickInputGroup) {
        scope.launch {
            quickInputRepository.deleteGroup(appContext, group.id)
            // 分组已删除，清掉它的折叠状态
            val collapsed = _collapsedQuickGroups.value - group.id
            _collapsedQuickGroups.value = collapsed
            QuickInputPrefs.setCollapsed(appContext, collapsed)
            loadQuickInputs()
        }
    }

    /** 加密条目：可改描述 / 回车 / 分组，文本与加密标记不变。 */
    fun saveQuickInputMeta(input: QuickInput) {
        scope.launch {
            quickInputRepository.updateMeta(appContext, input)
            loadQuickInputs()
        }
    }

    fun toggleQuickGroupCollapsed(group: QuickInputGroup) {
        val current = _collapsedQuickGroups.value
        val next = if (group.id in current) current - group.id else current + group.id
        _collapsedQuickGroups.value = next
        QuickInputPrefs.setCollapsed(appContext, next)
    }

    fun moveQuickGroup(group: QuickInputGroup, up: Boolean) {
        scope.launch {
            quickInputRepository.moveGroup(appContext, group.id, up)
            loadQuickInputs()
        }
    }

    fun moveQuickInput(input: QuickInput, up: Boolean) {
        scope.launch {
            quickInputRepository.moveItem(appContext, input.id, up)
            loadQuickInputs()
        }
    }

    /** 整理数据：未分组 / 失效分组条目移入默认分组，回调移动条数。 */
    fun tidyQuickInputs(onDone: (Int) -> Unit = {}) {
        scope.launch {
            val moved = quickInputRepository.tidyUp(appContext)
            loadQuickInputs()
            onDone(moved)
        }
    }

    private suspend fun loadQuickInputs() {
        _quickInputs.value = quickInputRepository.list(appContext)
        _quickGroups.value = quickInputRepository.listGroups(appContext)
    }

    fun execAction(actionId: String, liveText: String) {
        val action = _actions.value.firstOrNull { it.id == actionId } ?: return
        // 敏感指令永远不把固定文本带到连接上
        val live = if (action.sensitive) "" else liveText
        activeClient?.sendAction(actionId, live)
    }

    fun setSendEnter(value: Boolean) {
        _sendEnter.value = value
        DeviceStore.setSendEnter(appContext, value)
    }

    fun resend(item: HistoryItem) {
        val id = activeClient?.sendText(item.text, item.enter) ?: return
        // 不新增历史记录；回执只更新原来那条的状态
        updateHistoryStatus(item.id, "发送中")
        pendingResendIds[id] = item.id
    }

    fun clearHistory() {
        _history.value = emptyList()
        scope.launch { historyRepository.clear(appContext) }
    }

    fun deleteHistoryItem(item: HistoryItem) {
        _history.value = _history.value.filterNot { it.id == item.id }
        scope.launch { historyRepository.delete(appContext, item.id) }
    }

    fun deleteHistoryItems(items: List<HistoryItem>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        _history.value = _history.value.filterNot { it.id in ids }
        scope.launch { historyRepository.deleteMany(appContext, ids.toList()) }
    }

    fun localDeviceName(): String = DeviceIdentity.getDeviceName(appContext)

    /** 保存本机名称；若已连接则立即重连一次，让 Mac 立刻看到新名字。 */
    fun updateDeviceName(name: String) {
        DeviceIdentity.setDeviceName(appContext, name)
        if (_state.value is AppConnState.Connected) {
            val device = _currentDevice.value ?: return
            userRequestedDisconnect = false
            reconnectAttempt = 0
            beginConnect(device, firstAttempt = true)
        }
    }

    fun removeDevice(device: Device) {
        DeviceStore.remove(appContext, device.deviceId)
        _devices.value = DeviceStore.list(appContext)
        if (_currentDevice.value?.deviceId == device.deviceId) {
            userRequestedDisconnect = true
            activeClient?.disconnect()
            activeClient = null
            _currentDevice.value = null
            _state.value = AppConnState.Disconnected
        }
    }

    fun diagnosticsText(): String {
        val device = _currentDevice.value
        val phoneIp = NetworkUtils.currentWifiIp(appContext) ?: "未连接 Wi-Fi"
        val deviceId = DeviceIdentity.get(appContext)
        val errorText = when (val current = _state.value) {
            is AppConnState.NeedRescan -> current.detail.ifBlank { lastServerError }
            else -> lastServerError
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("投字 v2 诊断")
            appendLine("手机 IP: $phoneIp")
            appendLine("服务: ${device?.serverName?.ifBlank { "未知" } ?: "未知"} · ${device?.ip ?: "-"}:${device?.port ?: 8765}")
            appendLine("deviceId: $deviceId")
            appendLine("指纹: ${device?.fingerprint ?: "-"}")
            appendLine("错误: ${errorText.ifBlank { "-" }}")
            append("时间: $time")
        }
    }

    private fun updateHistoryStatus(id: String, status: String) {
        _history.value = _history.value.map {
            if (it.id == id) it.copy(status = status) else it
        }
        scope.launch { historyRepository.updateStatus(appContext, id, status) }
    }

    override fun onCleared() {
        scope.cancel()
        activeClient?.disconnect()
        activeClient = null
        super.onCleared()
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
