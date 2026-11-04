import FlyingFox
import Foundation

/// 一条 WebSocket 连接：负责明文握手、会话恢复、加密帧路由与回执。
public final class Connection: Identifiable, @unchecked Sendable {
    public let id = UUID()
    public let remoteIP: String

    private let lock = NSLock()
    private var session: Session?
    private var deviceId: String?
    private var deviceName: String?
    private var continuation: AsyncStream<WSMessage>.Continuation?
    private var finished = false
    private var didShutdown = false
    private weak var server: WebSocketServer?

    init(remoteIP: String) {
        self.remoteIP = remoteIP
    }

    func bind(server: WebSocketServer) {
        self.server = server
    }

    var deviceIdentifier: String? {
        lock.lock()
        defer { lock.unlock() }
        return deviceId
    }

    private var currentDeviceName: String {
        lock.lock()
        defer { lock.unlock() }
        return deviceName ?? "当前设备"
    }

    // MARK: - Lifecycle

    /// 返回服务端 → 客户端的消息流。
    func start(incoming: AsyncStream<WSMessage>) -> AsyncStream<WSMessage> {
        var outgoingContinuation: AsyncStream<WSMessage>.Continuation!
        let outgoing = AsyncStream<WSMessage> { continuation in
            outgoingContinuation = continuation
        }

        lock.lock()
        continuation = outgoingContinuation
        lock.unlock()

        Task { [weak self] in
            for await message in incoming {
                guard let self else { break }
                await self.receive(message)
            }
            await self?.shutdown()
        }

        return outgoing
    }

    func close() {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        let outgoing = continuation
        lock.unlock()

        outgoing?.yield(.close(.normalClosure))
        outgoing?.finish()
    }

    func rejectBusy() {
        sendRaw(MessageFrames.error(code: 4005, message: "server device limit reached"))
        close()
    }

    private func shutdown() async {
        let (alreadyDone, outgoing) = takeShutdownState()
        guard !alreadyDone else { return }
        outgoing?.finish()
        await WebSocketServer.shared.unregister(self)
    }

    private func takeShutdownState() -> (Bool, AsyncStream<WSMessage>.Continuation?) {
        lock.lock()
        defer { lock.unlock() }
        let alreadyDone = didShutdown
        didShutdown = true
        return (alreadyDone, continuation)
    }

    private func currentSession() -> Session? {
        lock.lock()
        defer { lock.unlock() }
        return session
    }

    // MARK: - Frame I/O

    private func receive(_ message: WSMessage) async {
        switch message {
        case let .text(text):
            await handleText(text)
        case let .data(data):
            if let text = String(data: data, encoding: .utf8) {
                await handleText(text)
            }
        case .close:
            break
        }
    }

    private func sendRaw(_ frame: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: frame),
              let text = String(data: data, encoding: .utf8) else { return }

        lock.lock()
        let outgoing = finished ? nil : continuation
        lock.unlock()

        outgoing?.yield(.text(text))
    }

    func sendEncrypted(_ frame: [String: Any]) {
        guard let session else {
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "debug_allow_plaintext") {
                sendRaw(frame)
            }
            #endif
            return
        }
        guard let plain = try? JSONSerialization.data(withJSONObject: frame),
              let ciphertext = try? session.encrypt(plain) else {
            TBLog.server.error("frame encryption failed")
            return
        }
        sendRaw(["type": "enc", "data": ciphertext.base64EncodedString()])
    }

    private func sendError(code: Int, message: String) {
        sendRaw(MessageFrames.error(code: code, message: message))
    }

    // MARK: - Incoming routing

    private func handleText(_ text: String) async {
        guard let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)),
              let dict = object as? [String: Any],
              let type = dict["type"] as? String else {
            return
        }

        switch type {
        case "hello":
            await handleHello(dict)
        case "enc":
            await handleEncrypted(dict)
        default:
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "debug_allow_plaintext") {
                await handleBusiness(dict)
            }
            #endif
        }
    }

    private func handleEncrypted(_ dict: [String: Any]) async {
        guard let current = currentSession() else {
            sendError(code: 4001, message: "not authenticated")
            return
        }
        guard let base64 = dict["data"] as? String,
              let raw = Data(base64Encoded: base64) else {
            return
        }
        guard let plain = try? current.decrypt(raw),
              let object = try? JSONSerialization.jsonObject(with: plain),
              let business = object as? [String: Any] else {
            // 解密失败视为重放 / 篡改，直接断开
            TBLog.server.error("encrypted frame rejected from \(self.remoteIP, privacy: .public)")
            close()
            return
        }

        await handleBusiness(business)
    }

    // MARK: - Handshake

    private func handleHello(_ dict: [String: Any]) async {
        guard let version = dict["v"] as? Int, version == TB.protocolVersion else {
            sendError(code: 4002, message: "unsupported protocol version")
            return
        }

        // 路径一：会话恢复（sessionToken 未过期）
        if let token = dict["sessionToken"] as? String,
           let record = SessionStore.shared.lookup(token: token) {
            if let requested = dict["deviceId"] as? String, requested != record.deviceId {
                sendError(code: 4001, message: "session device mismatch")
                return
            }
            let reportedName = dict["deviceName"] as? String

            // 滑动续期：每次成功恢复都轮换新的 sessionToken，
            // 旧 token 暂时保留（防 ack 丢失），由容量上限自然淘汰。
            let newToken = Crypto.randomData(32).base64EncodedString()
            // 保留刚刚用过的旧 token 作为 ack 丢失时的兜底，其余历史 token 清理
            SessionStore.shared.prune(deviceId: record.deviceId, keeping: [record.token])
            SessionStore.shared.put(SessionRecord(
                token: newToken,
                sessionId: record.sessionId,
                key: record.key,
                deviceId: record.deviceId,
                fingerprint: record.fingerprint,
                createdAt: Date()
            ))

            let resumed = Session(id: record.sessionId, key: record.key, token: newToken)
            attach(resumed)
            let info = await DeviceStore.shared.touch(
                deviceId: record.deviceId,
                ip: remoteIP,
                reportedName: reportedName
            )
            let name = await DeviceStore.shared.name(for: record.deviceId)
            setDevice(id: record.deviceId, name: name)
            sendHelloAck(
                fingerprint: record.fingerprint,
                known: true,
                firstSeen: info.firstSeen,
                serverNonce: nil,
                token: newToken
            )
            await sendActions()
            await WebSocketServer.shared.connectionAuthenticated(self)
            return
        }

        // 路径二：完整 PSK 认证（先认证，再查设备）
        guard let deviceId = dict["deviceId"] as? String, !deviceId.isEmpty,
              let nonceBase64 = dict["clientNonce"] as? String,
              let clientNonce = Data(base64Encoded: nonceBase64), clientNonce.count == 16,
              let tagBase64 = dict["authTag"] as? String,
              let authTag = Data(base64Encoded: tagBase64) else {
            sendError(code: 4001, message: "malformed hello")
            return
        }

        guard let psk = PSKStore.shared.current else {
            sendError(code: 4003, message: "pairing code expired")
            return
        }

        let expected = Crypto.authTag(psk: psk, deviceId: deviceId, clientNonce: clientNonce)
        guard Crypto.constantTimeEquals(expected, authTag) else {
            sendError(code: 4001, message: "auth failed")
            return
        }

        let serverNonce = Crypto.randomData(16)
        let key = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)
        let sessionId = Crypto.sessionId(clientNonce: clientNonce, serverNonce: serverNonce)
        let token = Crypto.randomData(32).base64EncodedString()
        let fingerprint = Crypto.fingerprint(psk)

        // 完整握手后只保留本次会话，清理该设备的历史 token
        SessionStore.shared.prune(deviceId: deviceId, keeping: [])
        SessionStore.shared.put(SessionRecord(
            token: token,
            sessionId: sessionId,
            key: key,
            deviceId: deviceId,
            fingerprint: fingerprint,
            createdAt: Date()
        ))

        let newSession = Session(id: sessionId, key: key, token: token)
        attach(newSession)

        let info = await DeviceStore.shared.touch(
            deviceId: deviceId,
            ip: remoteIP,
            reportedName: dict["deviceName"] as? String
        )
        let name = await DeviceStore.shared.name(for: deviceId)
        setDevice(id: deviceId, name: name)

        sendHelloAck(
            fingerprint: fingerprint,
            known: info.known,
            firstSeen: info.firstSeen,
            serverNonce: serverNonce,
            token: token
        )
        await sendActions()
        await WebSocketServer.shared.connectionAuthenticated(self)
        // 二维码已被成功使用：隐藏二维码并提示已连接
        await PairingWindowController.shared.markPaired()
    }

    private func sendHelloAck(
        fingerprint: String,
        known: Bool,
        firstSeen: Date,
        serverNonce: Data?,
        token: String?
    ) {
        let frame = MessageFrames.helloAck(
            serverId: MacIdentity.serverId,
            serverName: MacIdentity.serverName,
            fingerprint: fingerprint,
            known: known,
            firstSeen: firstSeen,
            serverNonce: serverNonce,
            sessionToken: token
        )
        sendRaw(frame)
    }

    private func attach(_ newSession: Session) {
        lock.lock()
        session = newSession
        lock.unlock()
    }

    private func setDevice(id: String, name: String?) {
        lock.lock()
        deviceId = id
        deviceName = name
        lock.unlock()
    }

    private func sendActions() async {
        let (version, actions) = await ActionStore.shared.snapshot()
        sendEncrypted(MessageFrames.actions(version: version, actions: actions))
    }

    // MARK: - Business frames

    private func handleBusiness(_ dict: [String: Any]) async {
        guard let type = dict["type"] as? String else { return }

        switch type {
        case "text":
            let text = dict["text"] as? String ?? ""
            let pressEnter = dict["enter"] as? Bool ?? true
            let messageId = dict["id"] as? String ?? ""
            // 加密快捷输入：secure=true，禁止走剪贴板，内容只出现在最终光标处
            let secure = dict["secure"] as? Bool ?? false

            let ok = await KeyboardInjector.shared.inject(
                text: text,
                pressEnter: pressEnter,
                allowClipboard: !secure
            )
            sendEncrypted(MessageFrames.ack(id: messageId, ok: ok))
            await WebSocketServer.shared.broadcastStatus()

        case "action":
            let actionId = dict["actionId"] as? String ?? ""
            let liveText = dict["liveText"] as? String ?? ""
            let messageId = dict["id"] as? String ?? ""

            guard let action = await ActionStore.shared.action(id: actionId) else {
                sendEncrypted(MessageFrames.error(code: 4004, message: "action not found"))
                return
            }
            await ActionDispatcher.shared.dispatch(action, liveText: liveText)
            sendEncrypted(MessageFrames.ack(id: messageId, ok: true))

        case "ping":
            sendEncrypted(MessageFrames.pong())

        default:
            break
        }
    }
}
