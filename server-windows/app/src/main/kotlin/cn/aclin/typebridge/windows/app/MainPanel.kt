package cn.aclin.typebridge.windows.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.aclin.typebridge.windows.WindowsIdentity
import cn.aclin.typebridge.windows.inject.KeyboardInjector
import cn.aclin.typebridge.windows.launch.LaunchAtLogin
import cn.aclin.typebridge.windows.model.Device
import cn.aclin.typebridge.windows.network.TypeBridgeWebSocketServer
import cn.aclin.typebridge.windows.storage.DeviceStore
import cn.aclin.typebridge.windows.storage.SettingsStore

@Composable
fun MainPanel(
    server: TypeBridgeWebSocketServer,
    running: Boolean,
    connectionCount: Int,
    onToggleServer: () -> Unit,
    onShowPairing: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var devices by remember { mutableStateOf(DeviceStore.active) }
        var serverName by remember { mutableStateOf(WindowsIdentity.name) }
        var preferClipboard by remember { mutableStateOf(SettingsStore.preferClipboard) }
        var autoLaunch by remember { mutableStateOf(LaunchAtLogin.isEnabled()) }
        var editingName by remember { mutableStateOf(false) }
        var nameDraft by remember { mutableStateOf(serverName) }

        server.onDevicesChanged = { devices = DeviceStore.active }

        fun refreshDevices() {
            devices = DeviceStore.active
        }

        Surface(modifier = Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // 头部：状态 + 显示二维码 + 停止/启动
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) Color(0xFF4CAF50) else Color(0xFF888888)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (running) "服务在线 · $connectionCount 台设备" else "服务已停止",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onShowPairing) { Text("显示二维码配对") }
                    TextButton(onClick = onToggleServer) { Text(if (running) "停止" else "启动") }
                    TextButton(onClick = onClose) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // 电脑名称
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "电脑名称",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    if (editingName) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            WindowsIdentity.setName(nameDraft)
                            serverName = WindowsIdentity.name
                            editingName = false
                        }) { Text("保存") }
                        TextButton(onClick = { editingName = false; nameDraft = serverName }) { Text("取消") }
                    } else {
                        Text(
                            serverName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { editingName = true; nameDraft = serverName }) { Text("✎") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // 设备列表
                Text(
                    "已配对 (${devices.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                if (devices.isEmpty()) {
                    Text(
                        "暂无设备，点击上方「显示二维码配对」扫码连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    devices.forEach { device ->
                        DeviceRow(
                            device = device,
                            onRename = { newName ->
                                DeviceStore.rename(device.id, newName)
                                refreshDevices()
                            },
                            onRemove = {
                                server.disconnectDevice(device.id)
                                DeviceStore.remove(device.id)
                                refreshDevices()
                            }
                        )
                    }
                }
                Text(
                    "点铅笔可手动改名；未手动改名时会跟随手机端上报的名称",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // 设置
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoLaunch, onCheckedChange = { autoLaunch = it; LaunchAtLogin.setEnabled(it) })
                    Text("开机自启动", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = preferClipboard,
                        onCheckedChange = {
                            preferClipboard = it
                            SettingsStore.setPreferClipboard(it)
                            KeyboardInjector.preferClipboard = it
                        }
                    )
                    Text("优先使用剪贴板注入", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onExit) { Text("退出") }
                }
                Text(
                    "适用于 VS Code / 微信等 unicode 注入不生效的 App。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, onRename: (String) -> Unit, onRemove: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(device.name) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (device.online) Color(0xFF4CAF50) else Color(0xFF888888)))
        Spacer(Modifier.width(10.dp))
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onRename(draft); editing = false }) { Text("保存") }
        } else {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                device.ip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = { if (editing) editing = false else { editing = true; draft = device.name } }) { Text("✎") }
        TextButton(onClick = onRemove) { Text("⊖", color = MaterialTheme.colorScheme.error) }
    }
}
