package cn.aclin.typebridge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.aclin.typebridge.network.AppConnState
import cn.aclin.typebridge.network.RescanReason
import cn.aclin.typebridge.scan.QrError
import cn.aclin.typebridge.scan.QrResult
import cn.aclin.typebridge.scan.parseQr
import cn.aclin.typebridge.scan.qrErrorMessage
import cn.aclin.typebridge.ui.MainViewModel
import cn.aclin.typebridge.ui.components.ActionPanel
import cn.aclin.typebridge.ui.components.InputBar
import cn.aclin.typebridge.ui.components.needRescanTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val currentDevice by vm.currentDevice.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val quickInputs by vm.quickInputs.collectAsStateWithLifecycle()
    val sendEnter by vm.sendEnter.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var text by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showQuickInputs by remember { mutableStateOf(false) }
    var showQuickSend by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var dismissedRescan by remember { mutableStateOf<RescanReason?>(null) }

    val serverName = (state as? AppConnState.Connected)?.serverName?.takeIf { it.isNotBlank() }
        ?: currentDevice?.serverName?.takeIf { it.isNotBlank() }
        ?: "电脑"

    val titleText = when (val current = state) {
        is AppConnState.Connected ->
            current.serverName.ifBlank { currentDevice?.displayName ?: "投字" }
        else -> currentDevice?.displayName ?: "投字"
    }
    val subtitleText = when (val current = state) {
        is AppConnState.Connected ->
            if (current.known) "已连接 · 已配对" else "已连接 · 首次配对"
        AppConnState.Connecting -> "正在连接…"
        is AppConnState.AutoReconnecting -> "重连中（第 ${current.attempt} 次）"
        AppConnState.Disconnected -> "未连接"
        is AppConnState.NeedRescan -> needRescanTitle(current.reason, serverName)
    }
    val canDisconnect = state is AppConnState.Connected ||
        state is AppConnState.Connecting ||
        state is AppConnState.AutoReconnecting
    val connectedDeviceId = if (state is AppConnState.Connected) {
        currentDevice?.deviceId
    } else {
        null
    }

    val copyDiagnostics: () -> Unit = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText("TypeBridge 诊断", vm.diagnosticsText())
        )
        scope.launch { snackbarHostState.showSnackbar("诊断信息已复制") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            titleText,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("已配对设备") },
                                leadingIcon = { Icon(Icons.Default.Devices, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showDevices = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("增加设备") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showScanner = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("快捷输入") },
                                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showQuickInputs = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("历史记录") },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showHistory = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showSettings = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("断开连接") },
                                leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
                                enabled = canDisconnect,
                                onClick = {
                                    showMenu = false
                                    showDisconnectConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (actions.isNotEmpty()) {
                ActionPanel(
                    actions = actions,
                    inputText = text,
                    sourceName = serverName,
                    onExec = { vm.execAction(it, text) }
                )
            }

            InputBar(
                modifier = Modifier.weight(1f),
                enabled = state is AppConnState.Connected,
                targetName = serverName,
                value = text,
                onValueChange = { text = it },
                sendEnter = sendEnter,
                onSendEnterChange = { vm.setSendEnter(it) },
                onQuickInput = { showQuickSend = true },
                onSend = {
                    if (text.isNotBlank()) {
                        vm.send(text)
                        text = ""
                    }
                }
            )
        }
    }

    if (showDevices) {
        DevicesSheet(
            devices = devices,
            current = currentDevice,
            connectedDeviceId = connectedDeviceId,
            onDismiss = { showDevices = false },
            onAdd = {
                showDevices = false
                showScanner = true
            },
            onSelect = { device ->
                showDevices = false
                vm.connectDevice(device)
            },
            onRemove = { vm.removeDevice(it) }
        )
    }

    if (showHistory) {
        HistorySheet(
            items = history,
            onDismiss = { showHistory = false },
            onResend = { vm.resend(it) },
            onEdit = { item ->
                text = if (item.text == "[回车]") "" else item.text
                showHistory = false
            },
            onDelete = { vm.deleteHistoryItem(it) },
            onClear = { vm.clearHistory() }
        )
    }

    if (showQuickInputs) {
        QuickInputSheet(
            items = quickInputs,
            canSend = state is AppConnState.Connected,
            onDismiss = { showQuickInputs = false },
            onSend = { vm.sendQuickInput(it) },
            onSave = { vm.saveQuickInput(it) },
            onDelete = { vm.deleteQuickInput(it) }
        )
    }

    if (showQuickSend) {
        QuickInputSheet(
            items = quickInputs,
            canSend = state is AppConnState.Connected,
            manage = false,
            onDismiss = { showQuickSend = false },
            onSend = { vm.sendQuickInput(it) },
            onSave = {},
            onDelete = {}
        )
    }

    if (showSettings) {
        SettingsSheet(
            currentName = vm.localDeviceName(),
            onDismiss = { showSettings = false },
            onSave = { name ->
                showSettings = false
                vm.updateDeviceName(name)
            }
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text("断开连接") },
            text = { Text("确定要断开与 $titleText 的连接吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    vm.manualDisconnect()
                }) {
                    Text("断开")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showScanner) {
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ScanScreen(
                onResult = { raw ->
                    when (val result = parseQr(raw)) {
                        is QrResult.Ok -> {
                            showScanner = false
                            dismissedRescan = null
                            vm.connectWithScannedDevice(result.payload)
                        }

                        is QrResult.Err -> {
                            // 非 TypeBridge 二维码：静默继续扫描
                            if (result.reason != QrError.NotTypeBridge) {
                                showScanner = false
                                scanError = qrErrorMessage(result.reason)
                            }
                        }
                    }
                },
                onCancel = { showScanner = false }
            )
        }
    }

    scanError?.let { message ->
        AlertDialog(
            onDismissRequest = { scanError = null },
            title = { Text("无法使用该二维码") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { scanError = null }) { Text("知道了") }
            }
        )
    }

    val needRescan = state as? AppConnState.NeedRescan
    if (needRescan != null && dismissedRescan != needRescan.reason) {
        NeedRescanDialog(
            reason = needRescan.reason,
            detail = needRescan.detail,
            serverName = serverName,
            onRescan = {
                dismissedRescan = needRescan.reason
                showScanner = true
            },
            onDismiss = { dismissedRescan = needRescan.reason },
            onCopyDiagnostics = copyDiagnostics
        )
    }
}
