package cn.aclin.typebridge.windows.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cn.aclin.typebridge.windows.network.TypeBridgeWebSocketServer
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import java.awt.GraphicsEnvironment

private class TrayIconPainter : Painter() {
    override val intrinsicSize = Size(16f, 16f)
    override fun DrawScope.onDraw() {
        drawCircle(color = Color(0xFF4A90D9), radius = 7f, center = Offset(8f, 8f))
        drawCircle(color = Color.White, radius = 2.2f, center = Offset(8f, 8f))
    }
}

private object AppIcons {
    /** 从资源加载「笔 + 键盘」品牌图标，失败返回 null（回退到占位图标）。 */
    fun load(): Painter? {
        val bytes = runCatching {
            AppIcons::class.java.classLoader.getResourceAsStream("app-icon.png")?.use { it.readBytes() }
        }.getOrNull() ?: return null
        return runCatching {
            BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
        }.getOrNull()
    }
}

fun main() = application {
    val server = remember { TypeBridgeWebSocketServer() }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var connectionCount by remember { mutableStateOf(server.connectionCount) }
    var showMain by remember { mutableStateOf(true) }
    var showPairing by remember { mutableStateOf(false) }

    server.onStateChange = {
        running = server.running
        connectionCount = server.connectionCount
    }
    remember { server.start() }

    val appIcon = remember { AppIcons.load() }

    // 主面板定位到屏幕右下角、任务栏之上（类似 macOS 菜单栏弹窗）
    val density = LocalDensity.current.density
    val workArea = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val mainWinW = 400f
    val mainWinH = 540f
    val mainX = (workArea.x + workArea.width) / density - mainWinW - 8f
    val mainY = (workArea.y + workArea.height) / density - mainWinH
    val mainState = rememberWindowState(
        size = DpSize(mainWinW.dp, mainWinH.dp),
        position = WindowPosition(x = mainX.dp, y = mainY.dp)
    )

    fun toggleServer() {
        if (running) {
            scope.launch { server.stop() }
            running = false
        } else {
            server.start()
            running = true
        }
    }

    // 托盘图标：不带右键菜单（避免 RDP 下原生菜单中文乱码）；双击托盘重新打开主面板
    // 有 Tray 组件常驻，关闭窗口后应用仍留在托盘，不会退出
    Tray(
        icon = appIcon ?: remember { TrayIconPainter() },
        tooltip = "投字",
        onAction = { showMain = true }
    )

    if (showMain) {
        Window(
            onCloseRequest = { showMain = false },
            title = "投字",
            icon = appIcon,
            state = mainState,
            undecorated = true,
            resizable = false
        ) {
            MainPanel(
                server = server,
                running = running,
                connectionCount = connectionCount,
                onToggleServer = { toggleServer() },
                onShowPairing = { showPairing = true },
                onClose = { showMain = false },
                onExit = ::exitApplication
            )
        }
    }

    if (showPairing) {
        Window(
            onCloseRequest = { showPairing = false },
            title = "配对安卓设备",
            icon = appIcon,
            state = rememberWindowState(size = DpSize(380.dp, 640.dp))
        ) {
            PairingPanel(server)
        }
    }
}
