package cn.aclin.typebridge.windows.network

import cn.aclin.typebridge.windows.TB
import cn.aclin.typebridge.windows.crypto.Crypto
import cn.aclin.typebridge.windows.inject.KeyboardInjector
import cn.aclin.typebridge.windows.model.ActionBehavior
import cn.aclin.typebridge.windows.storage.ActionStore
import cn.aclin.typebridge.windows.storage.DeviceStore
import cn.aclin.typebridge.windows.storage.PskStore
import cn.aclin.typebridge.windows.WindowsIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.java_websocket.WebSocket

/**
 * 一条 WebSocket 连接：负责明文握手、会话恢复、加密帧路由与回执。
 * 与 macOS `Connection.swift` 对齐。
 */
class Connection(
    private val socket: WebSocket,
    val remoteIP: String
) {
    @Volatile private var session: Session? = null
    @Volatile private var deviceId: String? = null
    @Volatile private var closed = false

    /** 认证成功后回调（用于向其他设备广播状态）。 */
    var onAuthenticated: (() -> Unit)? = null

    /** 完整握手成功后回调（用于隐藏二维码、提示已连接）。 */
    var onPaired: (() -> Unit)? = null

    val deviceIdentifier: String? get() = deviceId

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close(1000, "server") }
    }

    fun rejectBusy() {
        sendRaw(MessageFrames.error(4005, "server device limit reached"))
        close()
    }

    fun onClose() {
        // 设备在线状态由 TypeBridgeWebSocketServer 统一判断。
        // 这里不能直接 markOffline：网络抖动重连时旧连接的 onClose 会延迟到
        // 新连接认证之后才触发，若在此直接置离线会把新连接刚置的 online=true 覆盖掉。
    }

    fun onMessage(text: String) {
        val obj = Message.parse(text) ?: return
        when (Message.typeOf(obj)) {
            "hello" -> handleHello(obj)
            "enc" -> handleEncrypted(obj)
        }
    }

    // MARK: - 握手

    private fun handleHello(dict: JsonObject) {
        if (Message.int(dict, "v") != TB.protocolVersion) {
            sendError(4002, "unsupported protocol version")
            return
        }

        // 路径一：会话恢复（sessionToken 未过期）
        val token = Message.str(dict, "sessionToken")
        if (token != null) {
            val record = SessionStore.lookup(token)
            if (record != null) {
                val requested = Message.str(dict, "deviceId")
                if (requested != null && requested != record.deviceId) {
                    sendError(4001, "session device mismatch")
                    return
                }
                val reportedName = Message.str(dict, "deviceName")
                val newToken = Crypto.b64(Crypto.randomBytes(32))
                SessionStore.prune(record.deviceId, setOf(record.token))
                SessionStore.put(record.copy(token = newToken, createdAt = System.currentTimeMillis()))

                session = Session(record.sessionId, record.key, newToken)
                val (_, firstSeen) = DeviceStore.touch(record.deviceId, remoteIP, reportedName)
                deviceId = record.deviceId

                sendHelloAck(record.fingerprint, known = true, firstSeen, serverNonce = null, token = newToken)
                sendActions()
                onAuthenticated?.invoke()
                return
            }
            // token 失效 → 落到完整 PSK 认证；客户端未带 authTag 时会收到 4001 后回退重试
        }

        // 路径二：完整 PSK 认证（先认证，再查设备）
        val deviceIdStr = Message.str(dict, "deviceId")?.takeIf { it.isNotEmpty() }
        val clientNonce = Message.str(dict, "clientNonce")?.let(Crypto::b64d)?.takeIf { it.size == 16 }
        val authTag = Message.str(dict, "authTag")?.let(Crypto::b64d)
        if (deviceIdStr == null || clientNonce == null || authTag == null) {
            sendError(4001, "malformed hello")
            return
        }

        val psk = PskStore.current
        if (psk == null) {
            sendError(4003, "pairing code expired")
            return
        }

        val expected = Crypto.authTag(psk, deviceIdStr, clientNonce)
        if (!Crypto.constantTimeEquals(expected, authTag)) {
            sendError(4001, "auth failed")
            return
        }

        val serverNonce = Crypto.randomBytes(16)
        val key = Crypto.deriveSessionKey(psk, clientNonce, serverNonce)
        val sessionId = Crypto.sessionId(clientNonce, serverNonce)
        val newToken = Crypto.b64(Crypto.randomBytes(32))
        val fingerprint = Crypto.fingerprint(psk)

        SessionStore.prune(deviceIdStr, emptySet())
        SessionStore.put(SessionRecord(newToken, sessionId, key, deviceIdStr, fingerprint, System.currentTimeMillis()))

        session = Session(sessionId, key, newToken)
        val (known, firstSeen) = DeviceStore.touch(deviceIdStr, remoteIP, Message.str(dict, "deviceName"))
        deviceId = deviceIdStr

        sendHelloAck(fingerprint, known, firstSeen, serverNonce, newToken)
        sendActions()
        onAuthenticated?.invoke()
        // 二维码已被成功使用：清除一次性 PSK
        PskStore.clear()
        onPaired?.invoke()
    }

    private fun sendHelloAck(fingerprint: String, known: Boolean, firstSeen: Long, serverNonce: ByteArray?, token: String?) {
        sendRaw(MessageFrames.helloAck(
            serverId = WindowsIdentity.id,
            serverName = WindowsIdentity.name,
            fingerprint = fingerprint,
            known = known,
            firstSeenAt = firstSeen,
            serverNonce = serverNonce,
            sessionToken = token
        ))
    }

    // MARK: - 加密帧

    private fun handleEncrypted(dict: JsonObject) {
        val current = session ?: run { sendError(4001, "not authenticated"); return }
        val raw = Message.str(dict, "data")?.let(Crypto::b64d) ?: return
        val plain = runCatching { current.decrypt(raw) }.getOrNull()
        val business = plain?.let { Message.parse(String(it, Charsets.UTF_8)) }
        if (business == null) {
            // 解密失败视为重放 / 篡改，直接断开
            close()
            return
        }
        handleBusiness(business)
    }

    private fun handleBusiness(dict: JsonObject) {
        when (Message.typeOf(dict)) {
            "text" -> {
                val text = Message.str(dict, "text") ?: ""
                val pressEnter = Message.bool(dict, "enter") ?: true
                val messageId = Message.str(dict, "id") ?: ""
                val secure = Message.bool(dict, "secure") ?: false
                val allowSecureClipboard = cn.aclin.typebridge.windows.storage.SettingsStore.allowSecureClipboard
                val ok = KeyboardInjector.inject(
                    text,
                    pressEnter,
                    allowClipboard = !secure || allowSecureClipboard
                )
                sendEncrypted(MessageFrames.ack(messageId, ok))
            }

            "action" -> {
                val actionId = Message.str(dict, "actionId") ?: ""
                val liveText = Message.str(dict, "liveText") ?: ""
                val messageId = Message.str(dict, "id") ?: ""
                val action = ActionStore.action(actionId)
                if (action == null) {
                    sendEncrypted(MessageFrames.error(4004, "action not found"))
                    return
                }
                val payload = if (action.text.isEmpty()) liveText else action.text
                if (payload.isNotEmpty()) {
                    KeyboardInjector.inject(payload, action.behavior == ActionBehavior.TEXT_ENTER)
                }
                sendEncrypted(MessageFrames.ack(messageId, true))
            }

            "ping" -> sendEncrypted(MessageFrames.pong())
        }
    }

    // MARK: - 发送

    fun sendEncrypted(frame: JsonObject) {
        val current = session ?: return
        val plain = frame.toString().toByteArray(Charsets.UTF_8)
        val ct = runCatching { current.encrypt(plain) }.getOrNull() ?: return
        sendRaw(buildJsonObject {
            put("type", "enc")
            put("data", Crypto.b64(ct))
        })
    }

    private fun sendRaw(frame: JsonObject) {
        if (closed) return
        runCatching { socket.send(frame.toString()) }
    }

    private fun sendError(code: Int, message: String) = sendRaw(MessageFrames.error(code, message))

    private fun sendActions() {
        val (version, actions) = ActionStore.snapshot()
        sendEncrypted(MessageFrames.actions(version, actions))
    }
}
