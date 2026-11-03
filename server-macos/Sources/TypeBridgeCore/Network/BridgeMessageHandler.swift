import FlyingFox
import Foundation

/// 每条 WebSocket 连接创建一个实例，因此可以拿到对端 IP。
struct BridgeMessageHandler: WSMessageHandler {
    let remoteIP: String

    func makeMessages(for client: AsyncStream<WSMessage>) async throws -> AsyncStream<WSMessage> {
        let connection = Connection(
            remoteIP: (remoteIP).ipv4Normalized
        )
        let accepted = await WebSocketServer.shared.register(connection)
        let outgoing = connection.start(incoming: client)
        if !accepted {
            connection.rejectBusy()
        }
        return outgoing
    }
}
