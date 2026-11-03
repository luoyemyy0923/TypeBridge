package cn.aclin.typebridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.aclin.typebridge.model.Device
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeviceList(
    devices: List<Device>,
    current: Device?,
    connectedDeviceId: String?,
    onSelect: (Device) -> Unit,
    onRemove: (Device) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (devices.isEmpty()) {
            Text(
                "还没有配对设备，点右上角「+」扫码添加",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        devices.forEach { device ->
            val isConnected = device.deviceId == connectedDeviceId
            val isCurrent = device.deviceId == current?.deviceId

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected || isCurrent) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(device.displayName, style = MaterialTheme.typography.bodyMedium)
                            if (isConnected) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(Color(0xFF2E7D32), CircleShape)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "已连接",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                        Text(
                            "指纹 ${device.fingerprint ?: "—"} · 首次配对 ${formatDate(device.firstSeenAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!isConnected) {
                        TextButton(onClick = { onSelect(device) }) {
                            Text(if (isCurrent) "重连" else "连接")
                        }
                    }
                    IconButton(onClick = { onRemove(device) }) {
                        Icon(Icons.Default.Delete, contentDescription = "移除")
                    }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
