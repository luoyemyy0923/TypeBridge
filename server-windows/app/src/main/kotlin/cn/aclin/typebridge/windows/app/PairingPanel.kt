package cn.aclin.typebridge.windows.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.aclin.typebridge.windows.TB
import cn.aclin.typebridge.windows.crypto.Crypto
import cn.aclin.typebridge.windows.model.LANInterface
import cn.aclin.typebridge.windows.model.QrPayload
import cn.aclin.typebridge.windows.net.InterfacePicker
import cn.aclin.typebridge.windows.net.NetworkMonitor
import cn.aclin.typebridge.windows.network.TypeBridgeWebSocketServer
import cn.aclin.typebridge.windows.scan.QrCodeGenerator
import cn.aclin.typebridge.windows.storage.PskStore
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage

private fun BufferedImage.toImageBitmap(): ImageBitmap {
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(this, "png", out)
    return SkiaImage.makeFromEncoded(out.toByteArray()).toComposeImageBitmap()
}

private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

@Composable
fun PairingPanel(server: TypeBridgeWebSocketServer) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var payload by remember { mutableStateOf<QrPayload?>(null) }
        var paired by remember { mutableStateOf(false) }
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        val interfaces = remember { InterfacePicker.candidates() }
        var selectedId by remember { mutableStateOf(interfaces.firstOrNull()?.id) }
        var interfaceMenuExpanded by remember { mutableStateOf(false) }

        fun selectedInterface(): LANInterface? =
            interfaces.firstOrNull { it.id == selectedId } ?: InterfacePicker.pickDefault()

        fun regenerate() {
            val iface = selectedInterface()
            if (iface == null) {
                payload = null
                paired = false
                return
            }
            val psk = Crypto.randomBytes(32)
            PskStore.set(psk)
            val fingerprint = Crypto.fingerprint(psk)
            payload = QrPayload(iface.ip, null, TB.port, psk, fingerprint, System.currentTimeMillis() + TB.qrTTLMillis)
            paired = false
        }

        server.onPaired = {
            paired = true
            payload = null
        }

        LaunchedEffect(Unit) { regenerate() }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                now = System.currentTimeMillis()
            }
        }

        DisposableEffect(Unit) {
            NetworkMonitor.onIPChanged = { regenerate() }
            NetworkMonitor.start()
            onDispose { NetworkMonitor.stop() }
        }

        val secondsRemaining: Long = payload?.let { ((it.expiresAt - now) / 1000).coerceAtLeast(0) } ?: 0L
        val expired = payload != null && secondsRemaining <= 0

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("用安卓 App 扫码配对", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "一次一码 · 5 分钟内有效 · 新码会让旧码立即失效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // 二维码 / 已连接 / 过期
            val qr = remember(payload) {
                payload?.takeIf { !expired }?.let { QrCodeGenerator.image(it.uriString(), 260)?.toImageBitmap() }
            }
            when {
                qr != null -> {
                    Box(
                        Modifier.size(288.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(bitmap = qr, contentDescription = "二维码", modifier = Modifier.size(260.dp))
                    }
                }

                paired -> {
                    Box(
                        Modifier.size(288.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E7D32).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("✓", color = Color(0xFF4CAF50), fontSize = 54.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("已连接", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "如需配对新的设备，请点击下方「重新生成二维码」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                }

                else -> {
                    Box(
                        Modifier.size(288.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "二维码已过期",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ip:port + 指纹 + 剩余秒数
            payload?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    "${it.ip}:${it.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "指纹 ${it.fingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "剩余 $secondsRemaining 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (secondsRemaining < 60) Color(0xFFE57373) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 网卡选择
            if (payload != null) {
                Spacer(Modifier.height(8.dp))
                if (interfaces.size > 1) {
                    Box {
                        TextButton(onClick = { interfaceMenuExpanded = true }) {
                            val iface = selectedInterface()
                            Text("网卡 ${iface?.name ?: "—"} · ${iface?.ip ?: "—"} ▾")
                        }
                        DropdownMenu(expanded = interfaceMenuExpanded, onDismissRequest = { interfaceMenuExpanded = false }) {
                            interfaces.forEach { iface ->
                                DropdownMenuItem(
                                    text = { Text("${iface.name} · ${iface.ip}") },
                                    onClick = {
                                        selectedId = iface.id
                                        interfaceMenuExpanded = false
                                        regenerate()
                                    }
                                )
                            }
                        }
                    }
                } else if (interfaces.isNotEmpty()) {
                    val iface = interfaces.first()
                    Text(
                        "网卡 ${iface.name} · ${iface.ip}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Button(onClick = { regenerate() }) { Text("重新生成二维码") }
                OutlinedButton(onClick = { payload?.let { copyToClipboard(it.uriString()) } }) { Text("复制连接串") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (paired) "二维码已被使用；需要配对新的设备时，点击「重新生成二维码」。"
                else "若 IP 变化，二维码会自动刷新；旧码同时作废。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
