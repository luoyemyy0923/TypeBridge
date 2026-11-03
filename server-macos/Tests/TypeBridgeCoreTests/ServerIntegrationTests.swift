import CryptoKit
import Foundation
import XCTest
@testable import TypeBridgeCore

/// 端到端：真实启动 FlyingFox 服务端，用 URLSession WebSocket 客户端完成握手 + 加密 ping。
/// 使用 8877 端口隔离测试；若 Keychain 不可用则自动跳过。
@MainActor
final class ServerIntegrationTests: XCTestCase {

    /// 与 App 的 8765 区分，避免本机正在运行 TypeBridge 时集成测试连到旧实例。
    private let testPort: UInt16 = 8877

    private var currentSocket: URLSessionWebSocketTask?

    func testHelloHandshakeAndEncryptedPing() async throws {
        try await withServer(psk: Crypto.randomData(32)) { psk in
            let deviceId = "test-device-\(UUID().uuidString.prefix(6))"
            let clientNonce = Crypto.randomData(16)
            _ = try await self.performFullHandshake(psk: psk, deviceId: deviceId, clientNonce: clientNonce)
            self.closeCurrentSocket()
        }
    }

    func testSessionResumeReusesKey() async throws {
        try await withServer(psk: Crypto.randomData(32)) { psk in
            let deviceId = "test-device-\(UUID().uuidString.prefix(6))"
            let clientNonce = Crypto.randomData(16)
            let handshake = try await self.performFullHandshake(
                psk: psk,
                deviceId: deviceId,
                clientNonce: clientNonce
            )
            try await self.resumeAndPing(
                deviceId: deviceId,
                token: handshake.token,
                key: handshake.key,
                sessionId: handshake.sessionId
            )
        }
    }

    // MARK: - Setup

    private func withServer(psk: Data, _ body: (Data) async throws -> Void) async throws {
        let previous = PSKStore.shared.current
        PSKStore.shared.setCurrent(psk)
        defer {
            closeCurrentSocket()
            if let previous {
                PSKStore.shared.setCurrent(previous)
            } else {
                PSKStore.shared.clear()
            }
        }

        try XCTSkipUnless(PSKStore.shared.current == psk, "Keychain unavailable in this environment")

        await WebSocketServer.shared.stop()
        try await Task.sleep(nanoseconds: 200_000_000)
        WebSocketServer.shared.port = testPort
        await WebSocketServer.shared.start()

        if case let .failed(message) = WebSocketServer.shared.state {
            throw XCTSkip("端口 \(testPort) 不可用：\(message)")
        }

        try await body(psk)

        await WebSocketServer.shared.stop()
    }

    // MARK: - Steps

    private func performFullHandshake(
        psk: Data,
        deviceId: String,
        clientNonce: Data
    ) async throws -> (token: String, key: SymmetricKey, sessionId: String) {
        closeCurrentSocket()
        let socket = openSocket()
        currentSocket = socket

        let authTag = Crypto.authTag(psk: psk, deviceId: deviceId, clientNonce: clientNonce)
        try await send([
            "type": "hello",
            "v": TB.protocolVersion,
            "cipher": "aes-256-gcm",
            "deviceId": deviceId,
            "clientNonce": clientNonce.base64EncodedString(),
            "authTag": authTag.base64EncodedString()
        ], over: socket)

        let ack = try await receiveJSON(from: socket)
        XCTAssertEqual(ack["type"] as? String, "hello_ack")
        XCTAssertEqual(ack["v"] as? Int, TB.protocolVersion)
        XCTAssertEqual(ack["cipher"] as? String, "aes-256-gcm")
        XCTAssertEqual(ack["serverFingerprint"] as? String, Crypto.fingerprint(psk))

        guard let serverNonceBase64 = ack["serverNonce"] as? String,
              let serverNonce = Data(base64Encoded: serverNonceBase64),
              let token = ack["sessionToken"] as? String else {
            throw TestError.malformedAck
        }

        let key = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)
        let sessionId = Crypto.sessionId(clientNonce: clientNonce, serverNonce: serverNonce)

        let pong = try await sendEncryptedPing(socket: socket, key: key, sessionId: sessionId)
        XCTAssertEqual(pong["type"] as? String, "pong")

        return (token, key, sessionId)
    }

    private func resumeAndPing(
        deviceId: String,
        token: String,
        key: SymmetricKey,
        sessionId: String
    ) async throws {
        closeCurrentSocket()
        let socket = openSocket()
        currentSocket = socket

        try await send([
            "type": "hello",
            "v": TB.protocolVersion,
            "deviceId": deviceId,
            "sessionToken": token,
            "clientNonce": Crypto.randomData(16).base64EncodedString()
        ], over: socket)

        let ack = try await receiveJSON(from: socket)
        XCTAssertEqual(ack["type"] as? String, "hello_ack")
        XCTAssertTrue(
            (ack["sessionToken"] as? String)?.isEmpty == false,
            "resume ack should rotate a new sessionToken; ack=\(ack)"
        )
        XCTAssertNotEqual(ack["sessionToken"] as? String, token, "resume should rotate the token")

        let pong = try await sendEncryptedPing(socket: socket, key: key, sessionId: sessionId)
        XCTAssertEqual(pong["type"] as? String, "pong")
    }

    private func sendEncryptedPing(
        socket: URLSessionWebSocketTask,
        key: SymmetricKey,
        sessionId: String
    ) async throws -> [String: Any] {
        let sender = Session(id: sessionId, key: key, token: "test-client")
        let plain = try JSONSerialization.data(withJSONObject: ["type": "ping", "ts": 1])
        let ciphertext = try sender.encrypt(plain)
        try await send(["type": "enc", "data": ciphertext.base64EncodedString()], over: socket)

        // 握手后服务端会先推送 actions / status，逐帧解密直到拿到 pong
        let receiver = Session(id: sessionId, key: key, token: "test-client-receiver")
        for _ in 0 ..< 6 {
            let frame = try await receiveJSON(from: socket)
            guard frame["type"] as? String == "enc",
                  let base64 = frame["data"] as? String,
                  let raw = Data(base64Encoded: base64) else {
                throw TestError.malformedFrame
            }
            let decrypted = try receiver.decrypt(raw)
            guard let object = try JSONSerialization.jsonObject(with: decrypted) as? [String: Any] else {
                throw TestError.malformedFrame
            }
            if object["type"] as? String == "pong" {
                return object
            }
        }
        throw TestError.pongMissing
    }

    // MARK: - Transport

    private func openSocket() -> URLSessionWebSocketTask {
        let url = URL(string: "ws://127.0.0.1:\(testPort)\(TB.wsPath)")!
        let socket = URLSession.shared.webSocketTask(with: url)
        socket.resume()
        return socket
    }

    private func closeCurrentSocket() {
        currentSocket?.cancel(with: .normalClosure, reason: nil)
        currentSocket = nil
    }

    private func send(_ frame: [String: Any], over socket: URLSessionWebSocketTask) async throws {
        let data = try JSONSerialization.data(withJSONObject: frame)
        let text = String(decoding: data, as: UTF8.self)
        try await socket.send(.string(text))
    }

    private func receiveJSON(from socket: URLSessionWebSocketTask) async throws -> [String: Any] {
        let message = try await socket.receive()
        let text: String
        switch message {
        case let .string(value):
            text = value
        case let .data(value):
            text = String(decoding: value, as: UTF8.self)
        @unknown default:
            throw TestError.malformedFrame
        }
        guard let object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else {
            throw TestError.malformedFrame
        }
        return object
    }

    private enum TestError: Error {
        case malformedAck
        case malformedFrame
        case pongMissing
    }
}
