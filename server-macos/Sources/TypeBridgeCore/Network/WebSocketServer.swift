import Combine
import FlyingFox
import Foundation

@MainActor
public final class WebSocketServer: ObservableObject {
    public static let shared = WebSocketServer()

    public enum State: Equatable {
        case stopped
        case starting
        case online
        case failed(String)
    }

    @Published public private(set) var state: State = .stopped
    @Published public private(set) var connectionCount: Int = 0

    /// 默认 8765；集成测试会改成其他端口，避免与本机正在运行的 App 冲突。
    var port: UInt16 = TB.port

    private var server: HTTPServer?
    private var runTask: Task<Void, Never>?
    private var connections: [UUID: Connection] = [:]

    private init() {}

    public var isRunning: Bool {
        if case .online = state { return true }
        return false
    }

    public func start() async {
        guard server == nil else { return }
        state = .starting

        let httpServer = HTTPServer(port: port)
        await httpServer.appendRoute("GET /typebridge") { request in
            let handler = BridgeMessageHandler(remoteIP: request.remoteIPAddress ?? "unknown")
            let wsHandler = WebSocketHTTPHandler(handler: MessageFrameWSHandler(handler: handler))
            return try await wsHandler.handleRequest(request)
        }

        let task = Task.detached(priority: .userInitiated) {
            do {
                try await httpServer.run()
            } catch {
                if Task.isCancelled { return }
                await MainActor.run {
                    WebSocketServer.shared.state = .failed(error.localizedDescription)
                }
            }
        }

        server = httpServer
        runTask = task

        do {
            try await httpServer.waitUntilListening()
            state = .online
            TBLog.server.log("web socket server listening on port \(self.port, privacy: .public)")
            BonjourRegistrar.shared.register(port: Int32(port))
        } catch {
            state = .failed(error.localizedDescription)
            TBLog.server.error("server failed to listen: \(error.localizedDescription, privacy: .public)")
        }
    }

    public func stop() async {
        for connection in connections.values {
            connection.close()
        }
        connections.removeAll()
        connectionCount = 0

        if let server {
            await server.stop(timeout: 1)
        }
        runTask?.cancel()
        runTask = nil
        server = nil
        state = .stopped
        TBLog.server.log("web socket server stopped")
    }

    // MARK: - Connection registry

    func register(_ connection: Connection) -> Bool {
        guard connections.count < TB.maxConnections else {
            TBLog.server.error("rejecting connection: limit reached")
            return false
        }
        connection.bind(server: self)
        connections[connection.id] = connection
        connectionCount = connections.count
        TBLog.server.log("connection accepted from \(connection.remoteIP, privacy: .public)")
        return true
    }

    func connectionAuthenticated(_ connection: Connection) {
        broadcastStatus()
    }

    func unregister(_ connection: Connection) {
        guard connections.removeValue(forKey: connection.id) != nil else { return }
        connectionCount = connections.count
        if let deviceId = connection.deviceIdentifier {
            DeviceStore.shared.markOffline(deviceId)
        }
        TBLog.server.log("connection closed (\(self.connectionCount, privacy: .public) remaining)")
        broadcastStatus()
    }

    // MARK: - Broadcast

    public func broadcastStatus() {
        let frame = MessageFrames.status(online: isRunning, devices: connectionCount)
        for connection in connections.values {
            connection.sendEncrypted(frame)
        }
    }

    /// 每台设备密钥不同，逐设备加密（不是一次加密多次发送）。
    public func broadcastActions() {
        let (version, actions) = ActionStore.shared.snapshot()
        let frame = MessageFrames.actions(version: version, actions: actions)
        for connection in connections.values {
            connection.sendEncrypted(frame)
        }
    }

    /// 本机名称变更后让客户端重连一次，使 hello_ack 带上的新名称生效。
    public func refreshClients() {
        for connection in connections.values {
            connection.close()
        }
    }

    /// 在 Mac 上移除设备：立刻断开它的连接并清空会话，
    /// 之后只能通过重新扫码（新的 PSK）再次配对。
    public func disconnectDevice(_ deviceId: String) {
        for connection in connections.values where connection.deviceIdentifier == deviceId {
            // 先发一条加密的移除通知，再关闭连接；Android 收到后立即停止重连并弹窗
            connection.sendEncrypted(MessageFrames.error(code: 4006, message: "device removed by Mac"))
            connection.close()
        }
        SessionStore.shared.prune(deviceId: deviceId, keeping: [])
        TBLog.server.log("device removed and sessions revoked: \(deviceId, privacy: .public)")
    }
}
