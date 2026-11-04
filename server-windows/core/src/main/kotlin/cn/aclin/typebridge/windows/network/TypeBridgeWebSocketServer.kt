package cn.aclin.typebridge.windows.network

import cn.aclin.typebridge.windows.TB
import cn.aclin.typebridge.windows.storage.ActionStore
import cn.aclin.typebridge.windows.storage.DeviceStore
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import javax.swing.SwingUtilities

/**
 * TypeBridge WebSocket 服务端（Java-WebSocket 封装）。
 * 对应 macOS `WebSocketServer.swift`。
 */
class TypeBridgeWebSocketServer(private val port: Int = TB.port) :
    WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    private val lock = Any()
    private val connections = LinkedHashMap<WebSocket, Connection>()

    @Volatile
    var running = false
        private set

    /** 服务启停/设备数变化时回调（用于刷新托盘 UI）。 */
    var onStateChange: (() -> Unit)? = null

    /** 任意设备完整握手成功后回调（用于隐藏二维码、提示已连接）。 */
    var onPaired: (() -> Unit)? = null

    /** 设备元数据变化时回调（接入/断开/改名/移除，用于刷新设备列表）。 */
    var onDevicesChanged: (() -> Unit)? = null

    val connectionCount: Int get() = synchronized(lock) { connections.size }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val path = handshake.resourceDescriptor
        if (path != TB.wsPath) {
            conn.close(1008, "bad path")
            return
        }
        val connection = Connection(conn, conn.remoteSocketAddress?.address?.hostAddress ?: "unknown")
        connection.onAuthenticated = {
            broadcastStatus()
            SwingUtilities.invokeLater { onDevicesChanged?.invoke() }
        }
        connection.onPaired = { SwingUtilities.invokeLater { onPaired?.invoke() } }
        val accepted = synchronized(lock) {
            if (connections.size >= TB.maxConnections) {
                false
            } else {
                connections[conn] = connection
                true
            }
        }
        if (!accepted) {
            connection.rejectBusy()
            return
        }
        SwingUtilities.invokeLater { onStateChange?.invoke() }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        synchronized(lock) { connections[conn] }?.onMessage(message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val connection = synchronized(lock) { connections.remove(conn) } ?: return
        val deviceId = connection.deviceIdentifier
        connection.onClose()
        // 仅当没有同 deviceId 的其他活动连接时才标记离线，
        // 避免重连时旧连接延迟关闭把新连接刚置的在线状态覆盖掉。
        if (deviceId != null) {
            val stillConnected = synchronized(lock) { connections.values.any { it.deviceIdentifier == deviceId } }
            if (!stillConnected) {
                DeviceStore.markOffline(deviceId)
            }
        }
        broadcastStatus()
        SwingUtilities.invokeLater {
            onStateChange?.invoke()
            onDevicesChanged?.invoke()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        System.err.println("ws error: ${ex.message}")
    }

    override fun onStart() {
        running = true
        SwingUtilities.invokeLater { onStateChange?.invoke() }
    }

    fun broadcastStatus() {
        val frame = MessageFrames.status(running, connectionCount)
        snapshot().forEach { it.sendEncrypted(frame) }
    }

    fun broadcastActions() {
        val (version, actions) = ActionStore.snapshot()
        val frame = MessageFrames.actions(version, actions)
        snapshot().forEach { it.sendEncrypted(frame) }
    }

    /** 移除设备：发加密 4006、断开连接、撤销该设备所有会话。 */
    fun disconnectDevice(deviceId: String) {
        snapshot().filter { it.deviceIdentifier == deviceId }.forEach { conn ->
            conn.sendEncrypted(MessageFrames.error(4006, "device removed by Windows"))
            conn.close()
        }
        SessionStore.prune(deviceId, emptySet())
    }

    private fun snapshot(): List<Connection> = synchronized(lock) { connections.values.toList() }
}
