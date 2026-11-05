# macOS 端：局域网键入桥（投字 / TypeBridge Mac）

> **版本：v2.1**（与 Android v2.1 对齐）
> Mac 端作为 **WebSocket 服务端**，通过**二维码**向安卓下发 PSK，接受加密长连接；键盘注入用 `CGEventKeyboardSetUnicodeString` 注入文本，`kVK_Return` 敲回车；**默认指令库为空**，由用户按需创建。

---


## 1. 功能清单

| 模块 | 说明 |
|---|---|
| WebSocket 服务 | FlyingFox 监听 `0.0.0.0:8765`，支持多客户端、自动 PING/PONG |
| 加密通信 | PSK 握手 / `hello_ack` / 派生会话密钥 / 全链路 AES-256-GCM |
| 键盘注入 | `CGEventKeyboardSetUnicodeString` + `kVK_Return`；剪贴板 Cmd+V 兜底（`secure=true` 时禁用剪贴板） |
| 二维码配对 | 生成 `typebridge://connect?...` URI，独立面板展示 + 倒计时 |
| 网卡选择 | 枚举 IPv4 接口，过滤虚拟网卡，多候选下拉选择 |
| 网络监测 | `NWPathMonitor` 监听 IP 变化，自动刷新二维码 |
| 设备管理 | 按 `deviceId` 复用元数据（name / firstSeen），更新 lastSeen / ip；支持手动改名 |
| 指令系统 | 用户创建 / 编辑 / 删除；`sensitive` 指令固定文本不下发（**v2.1 UI 暂时隐藏此入口**） |
| 会话恢复 | `sessionToken` **30 天滑动续期**，每次恢复轮换；持久化到 `sessions.json` |
| 状态推送 | 连接变化 / 设备数变化时向所有客户端广播 `status` |
| 设备移除 | 下发加密 `4006`、断开连接、撤销会话；必须重新扫码 |
| 二维码消费 | 完整握手成功后清空 PSK、隐藏二维码；关闭窗口再打开生成新码 |
| 本机名称 | 可设置并在每次连接时通过 `deviceName` 上报；Android 端显示该名称 |
| 开机启动 | `SMAppService` 登录项开关 |

---

## 2. 工程结构

```
server-macos/
├── Package.swift                       # SwiftPM：TypeBridgeCore / TypeBridgeMac / Tests
├── Scripts/build-app.sh                # 打包为 dist/投字.app（ad-hoc 签名）
├── Resources/
│   ├── Info.plist
│   ├── AppIcon.icns
│   └── MenuBarIcon.png
├── Sources/
│   ├── TypeBridgeCore/
│   │   ├── Constants.swift             # 协议常量
│   │   ├── Constants/MacIdentity.swift # serverId / Mac 名称
│   │   ├── Network/
│   │   │   ├── WebSocketServer.swift    # FlyingFox 封装
│   │   │   ├── BridgeMessageHandler.swift
│   │   │   ├── Connection.swift         # 握手 / 会话恢复 / 业务路由
│   │   │   ├── Session.swift            # 方向计数 + sessions.json 持久化（30 天滑动）
│   │   │   ├── MessageFrames.swift      # 帧构造
│   │   │   └── Crypto.swift
│   │   ├── Inject/KeyboardInjector.swift
│   │   ├── Scan/QRCodeGenerator.swift
│   │   ├── Net/
│   │   │   ├── InterfacePicker.swift
│   │   │   ├── NetworkMonitor.swift
│   │   │   └── BonjourRegistrar.swift
│   │   ├── Storage/
│   │   │   ├── DeviceStore.swift
│   │   │   ├── ActionStore.swift
│   │   │   └── PSKStore.swift           # Keychain 存储当前有效 PSK
│   │   ├── Model/Models.swift
│   │   ├── Actions/
│   │   │   ├── ActionDispatcher.swift
│   │   │   ├── ActionEditorView.swift
│   │   │   └── ActionEditorWindowController.swift
│   │   ├── MenuBar/
│   │   │   ├── MenuBarController.swift
│   │   │   ├── MenuBarView.swift
│   │   │   ├── PairingContentView.swift
│   │   │   └── PairingWindowController.swift
│   │   └── App/
│   │       ├── AppDelegate.swift
│   │       └── LaunchAtLogin.swift      # SMAppService
│   └── TypeBridgeMac/main.swift
└── Tests/TypeBridgeCoreTests            # 13 个用例
```

> **v2.1 现状**：历史记录相关文件（`HistoryStore` / `HistoryItem`）已删除；实际产物为 `dist/投字.app`。

### 2.1 依赖（`Package.swift` 或 Xcode SPM）

```swift
dependencies: [
    .package(url: "https://github.com/swhitty/FlyingFox.git", from: "0.27.1")
]
```

> 选 FlyingFox 而不是 SwiftNIO 的理由：**轻量、API 简单、自动处理 WebSocket 帧（含 PING/PONG、分片）**。如果团队已有 NIO 栈，可平替为 `swift-nio + swift-nio-extras` 的 WebSocket。

### 2.2 权限（`Info.plist`）

```xml
<key>NSAccessibilityUsageDescription</key>
<string>需要辅助功能权限以将文本输入到当前聚焦的输入框</string>

<key>NSLocalNetworkUsageDescription</key>
<string>TypeBridge 需要局域网访问以接收来自配对的安卓设备的文本</string>

<key>NSBonjourServices</key>
<array>
    <string>_typebridge._tcp</string>
</array>
```

Entitlements（`com.apple.security.network.server` / `client`）与 v1 相同。

---

## 3. 通信协议（macOS 视角，与 Android §3 一致）

### 3.1 帧结构

明文字段：

```json
{ "type": "hello" | "hello_ack" | "enc", "...": "..." }
```

业务帧包裹：

```json
{ "type": "enc", "data": "<base64: iv || ciphertext || tag>" }
```

### 3.2 `hello` 处理流程（**关键：先认证再查设备**）

> **实现现状（v2.1）**：`hello` 新增可选 `deviceName`（安卓本机名称）；会话恢复成功后会**轮换新的 `sessionToken`** 并滑动续期 30 天；`hello_ack` 新增 `serverId`（Mac 稳定 UUID）供 Android 作为设备主键。下面伪代码保留了 v2 流程，字段细节以 §0.2 为准。

```swift
func handleHello(ws: WebSocketSession, dict: [String: Any]) {
    // 1. 版本检查
    guard (dict["v"] as? Int) == 2 else {
        ws.send(error(code: 4002, msg: "unsupported protocol"))
        return
    }

    // 2. 会话恢复路径
    if let token = dict["sessionToken"] as? String,
       let cached = SessionStore.shared.lookup(token: token) {
        // 复用已有会话密钥（不重新 PSK 认证）
        ws.attachSession(cached)
        sendHelloAck(ws, session: cached, isResume: true)
        return
    }

    // 3. 完整 PSK 认证
    guard let deviceId = dict["deviceId"] as? String,
          let clientNonceB64 = dict["clientNonce"] as? String,
          let clientNonce = Data(base64Encoded: clientNonceB64),
          let authTagB64 = dict["authTag"] as? String,
          let authTag = Data(base64Encoded: authTagB64) else {
        ws.send(error(code: 4001, msg: "malformed hello"))
        return
    }

    guard let psk = PSKStore.shared.current else {
        ws.send(error(code: 4003, msg: "expired"))
        return
    }
    let expected = Crypto.authTag(psk: psk, deviceId: deviceId, clientNonce: clientNonce)
    guard Crypto.constantTimeEquals(expected, authTag) else {
        ws.send(error(code: 4001, msg: "auth failed"))
        return
    }

    // 4. 认证通过后再查询/创建设备（防止未认证探测设备是否存在）
    let existing = DeviceStore.shared.find(by: deviceId)
    if let dev = existing {
        DeviceStore.shared.updateLastSeen(deviceId, ip: ws.remoteIP)
    } else {
        DeviceStore.shared.insert(Device(
            id: deviceId, name: "待命名设备", ip: ws.remoteIP,
            firstSeen: Date(), lastSeen: Date(), online: true
        ))
    }

    // 5. 生成 serverNonce，派生会话密钥
    var serverNonce = Data(count: 16)
    _ = serverNonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
    let sessionKey = Crypto.deriveSessionKey(psk: psk, clientNonce: clientNonce, serverNonce: serverNonce)
    let token = UUID().uuidString
    let session = Session(id: UUID(), key: sessionKey, token: token, createdAt: Date())
    SessionStore.shared.put(session)
    ws.attachSession(session)

    // 6. 回复 ack
    sendHelloAck(ws, session: session,
                 serverNonce: serverNonce,
                 known: existing != nil,
                 firstSeenAt: existing?.firstSeen,
                 isResume: false)
}
```

### 3.3 业务帧路由

```swift
func routeEncryptedFrame(ws: WebSocketSession, dict: [String: Any]) {
    guard let session = ws.session else { return }
    guard let dataB64 = dict["data"] as? String,
          let raw = Data(base64Encoded: dataB64) else { return }

    guard let plain = try? Crypto.decrypt(key: session.key, data: raw, aad: session.aadNext()) else {
        // 解密失败 → 可能是重放或篡改，断开
        ws.close(); return
    }
    guard let msg = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any],
          let type = msg["type"] as? String else { return }

    switch type {
    case "text":
        let text = msg["text"] as? String ?? ""
        let enter = (msg["enter"] as? Bool) ?? true
        let id = msg["id"] as? String ?? ""
        let secure = (msg["secure"] as? Bool) ?? false
        // v2.1：历史记录已移除；secure=true 时禁止剪贴板兜底
        KeyboardInjector.shared.inject(
            text: text,
            pressEnter: enter,
            allowClipboard: !secure
        )
        ws.sendEncrypted(buildAck(id: id, ok: true))
        broadcastStatus()

    case "action":
        let actionId = msg["actionId"] as? String ?? ""
        let liveText = msg["liveText"] as? String ?? ""
        guard let action = ActionStore.shared.actions.first(where: { $0.id == actionId }) else {
            ws.sendEncrypted(buildError(code: 4004, msg: "action not found"))
            return
        }
        ActionDispatcher.shared.dispatch(action, liveText: liveText)

    case "ping":
        ws.sendEncrypted(buildPong())

    default:
        break
    }
}
```

### 3.4 错误码

与 Android §3.6 完全一致：`4001 / 4002 / 4003 / 4004 / 4005 / 4006`。

> `4006 = device removed by Mac`：移除设备时以加密 `error` 帧下发，随后断开并撤销该设备所有会话。

### 3.5 指令库下发

```swift
func buildActionsMessage() -> [String: Any] {
    let (version, actions) = ActionStore.shared.snapshot()
    return [
        "type": "actions",
        "version": version,
        "actions": actions.map { a in
            [
                "id": a.id,
                "name": a.name,
                "behavior": a.behavior.rawValue,
                "text": a.sensitive ? "" : a.text,     // 敏感指令固定文本不下发
                "order": a.order,
                "sensitive": a.sensitive
            ]
        }
    ]
}
```

- **`sensitive=true` 的 `text` 强制置空**（纵深防御）。
- 广播时对每个会话**分别加密**（每台设备密钥不同）；`text` 字段不含敏感内容，故广播内容一致，可以一次打包多次加密。

---

## 4. 加密与 PSK

### 4.1 PSK 生命周期

- Mac 端**同一时刻只保留一个有效 PSK**（`PSKStore.shared.current`），存 Keychain。
- 用户点"生成新码" → 生成新 PSK → 覆盖旧 PSK（旧 PSK 立即失效，但**已建立的会话不受影响**——会话密钥已经派生完毕）。
- 旧 PSK 失效后，任何携带旧 PSK 的 `hello` 都会得到 `4001`。
- **v2.1 新增**：一次**完整 PSK 握手成功**后，Mac 立即清空本次 PSK；二维码随即隐藏并显示「已连接」。
- **v2.1 新增**：配对窗口关闭后再次打开会生成新二维码；窗口已打开时再次点菜单项只置前，不打断当前二维码。
- 已配对设备依靠 `sessionToken`（30 天滑动续期）恢复连接，不再依赖 PSK。

### 4.2 Crypto 工具

```swift
import CryptoKit

enum Crypto {
    static func deriveSessionKey(psk: Data, clientNonce: Data, serverNonce: Data) -> SymmetricKey {
        let salt = clientNonce + serverNonce
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: psk),
            salt: salt,
            info: Data("typebridge-v2-session".utf8),
            outputByteCount: 32
        )
    }

    static func authTag(psk: Data, deviceId: String, clientNonce: Data) -> Data {
        let key = SymmetricKey(data: psk)
        var data = Data(deviceId.utf8); data.append(clientNonce)
        return Data(HMAC<SHA256>.authenticationCode(for: data, using: key))
    }

    static func encrypt(_ plain: Data, key: SymmetricKey, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: key, authenticating: aad)
        return (box.nonce.withUnsafeBytes { Data($0) }) + box.ciphertext + box.tag
    }

    static func decrypt(key: SymmetricKey, data: Data, aad: Data) throws -> Data {
        let n = 12
        guard data.count > n else { throw CryptoError.bad }
        let nonce = try AES.GCM.Nonce(data: data.prefix(n))
        let ct = data.subdata(in: n..<(data.count - 16))
        let tag = data.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: key, authenticating: aad)
    }

    static func fingerprint(_ psk: Data) -> String {
        let digest = SHA256.hash(data: psk)
        return digest.prefix(4).map { String(format: "%02x", $0) }.joined()
    }
}
```

---

## 5. WebSocket 服务端（FlyingFox）

### 5.1 启动与路由

> **实现注意**：FlyingFox 0.27.1 的实际 WebSocket API 是 `WSMessageHandler` / `MessageFrameWSHandler`（流式），不是下面伪代码里的 `WSHandler` 闭包。实际路由为：
> `server.appendRoute("GET /typebridge")`，每条连接创建 `BridgeMessageHandler` 并返回 `AsyncStream<WSMessage>`。

```swift
import FlyingFox

final class WebSocketServer: ObservableObject {
    static let shared = WebSocketServer()

    @Published private(set) var state: ServerState = .stopped
    @Published private(set) var sessions: [UUID: WebSocketSession] = [:]

    private var server: HTTPServer?
    private let port: UInt16 = 8765
    private let queue = DispatchQueue(label: "ws.server", qos: .userInitiated)

    func start() async {
        guard server == nil else { return }

        let handler: WSHandler = { [weak self] request, writer, _ in
            guard let self else { return }
            let session = WebSocketSession(
                writer: writer, remoteIP: request.remoteAddress ?? "?"
            )
            await MainActor.run { self.sessions[session.id] = session }
            // 该 handler 会在连接关闭前持续被调用；readLoop 由 FlyingFox 内部驱动
        }

        let server = HTTPServer(port: port)
        await server.appendRoute("WS /typebridge", to: handler)
        self.server = server
        Task {
            do {
                try await server.run()
                await MainActor.run { self.state = .online }
            } catch {
                await MainActor.run { self.state = .error(error.localizedDescription) }
            }
        }
    }

    func stop() async {
        if let s = server { await s.stop() }
        server = nil
        state = .stopped
    }
}
```

> FlyingFox 的 WS 具体 API 会随版本略有变化：核心是 `WSHandler` + `server.appendRoute("WS /path", to: handler)`。**以实际安装版本的 README 为准**；能力上要求 ≥ 0.14（支持 WebSocket）。

### 5.2 会话对象

```swift
final class WebSocketSession {
    let id = UUID()
    let writer: WSFrameWriter
    let remoteIP: String
    var deviceName: String?
    var session: Session?

    func attachSession(_ s: Session) { self.session = s }

    func send(json: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let str = String(data: data, encoding: .utf8) else { return }
        Task { try? await writer.write(frame: WSFrame(fin: true, opCode: .text, data: Data(str.utf8))) }
    }

    func sendEncrypted(json: [String: Any]) {
        guard let session = session else { return }
        guard let plain = try? JSONSerialization.data(withJSONObject: json) else { return }
        let aad = session.aadNext()
        guard let ct = try? Crypto.encrypt(plain, key: session.key, aad: aad) else { return }
        send(json: ["type": "enc", "data": ct.base64EncodedString()])
    }

    func close() {
        Task { try? await writer.close() }
    }
}
```

### 5.3 广播

```swift
func broadcastStatus() {
    let msg: [String: Any] = [
        "type": "status",
        "state": "online",
        "devices": sessions.count
    ]
    sessions.values.forEach { $0.sendEncrypted(json: msg) }
}

func broadcastActions() {
    let msg = buildActionsMessage()
    sessions.values.forEach { $0.sendEncrypted(json: msg) }
}
```

> **加密广播**：每台设备密钥不同，所以是"循环调用 `sendEncrypted`"，不是"一次加密多次发送"。这也是 `sensitive=true` 固定文本不下发的原因之一——若下发固定文本则广播代价高。

### 5.4 收到帧的分发

FlyingFox 的 `WSHandler` 每次收到一帧都会回调一次；在 handler 内解析文本并分发：

```swift
let handler: WSHandler = { [weak self] request, writer, _ in
    // 这里 FlyingFox 会把每个收到的帧回调进来
    // 具体签名以官方 README 为准；核心是拿到 (data, opCode) 后判断是 text 还是 ping
    // 处理文本帧：解析 JSON → 若是 hello 走 handleHello；若是 enc 走 routeEncryptedFrame
}
```

> FlyingFox 自动回应 PING（协议层），我们的应用层心跳用 `{type: "ping"}` / `{type: "pong"}` 走加密通道，与协议层 PING 不冲突。

---

## 6. 键盘注入（重写核心）

### 6.1 权限检查

```swift
func ensureAccessibility() -> Bool {
    if AXIsProcessTrusted() { return true }
    let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary
    return AXIsProcessTrustedWithOptions(options)
}
```

首次调用 `AXIsProcessTrustedWithOptions` 会弹系统提示并跳转到"隐私与安全性 → 辅助功能"。

### 6.2 主路径：`CGEventKeyboardSetUnicodeString`

> **实现现状（v2.1）**：文本内部的 `\n` / `\r\n` 使用 **Shift+Return** 作为软换行，避免在聊天/编辑器里误触发发送；只有末尾的「发送时按回车」才使用普通 `kVK_Return`。

```swift
import Cocoa
import CoreGraphics

final class KeyboardInjector {
    static let shared = KeyboardInjector()
    private init() {}

    /// 主入口：`pressEnter=true` 时末尾敲回车
    func inject(text: String, pressEnter: Bool) {
        guard ensureAccessibility() else { return }
        DispatchQueue.main.async {
            self.dismissMenuPopover()             // 关键：先让焦点回到目标 App
            usleep(30_000)
            self.injectViaUnicode(text) ?? self.injectViaClipboard(text)
            if pressEnter {
                usleep(20_000)
                self.pressReturn()
            }
        }
    }

    // MARK: - 主路径
    /// 返回 nil 表示成功，返回 String 表示失败原因（供 fallback 判断）
    private func injectViaUnicode(_ text: String) -> String? {
        let src = CGEventSource(stateID: .hidSystemState)
        // 分块注入：每块最多 20 个 UTF-16 code unit，避免长文本卡顿
        let utf16 = Array(text.utf16)
        var i = 0
        while i < utf16.count {
            // 处理换行：单独走 Return
            if utf16[i] == 0x0A || utf16[i] == 0x0D {
                pressReturn()
                i += 1
                continue
            }
            let end = min(i + 20, utf16.count)
            var chunk = Array(utf16[i..<end])
            // 若 chunk 内有换行，切到换行前
            if let nl = chunk.firstIndex(where: { $0 == 0x0A || $0 == 0x0D }) {
                chunk = Array(chunk[..<nl])
            }
            if !chunk.isEmpty {
                guard let down = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: true),
                      let up = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: false) else {
                    return "CGEvent creation failed"
                }
                chunk.withUnsafeBufferPointer { buf in
                    down.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: buf.baseAddress!)
                    up.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: buf.baseAddress!)
                }
                down.post(tap: .cghidEventTap)
                up.post(tap: .cghidEventTap)
            }
            i = end
            usleep(8_000)
        }
        return nil
    }

    // MARK: - 兜底：剪贴板 + Cmd+V
    private func injectViaClipboard(_ text: String) {
        let pb = NSPasteboard.general
        let saved = pb.string(forType: .string)
        pb.clearContents()
        pb.setString(text, forType: .string)

        let src = CGEventSource(stateID: .hidSystemState)
        let vKey: CGKeyCode = 0x09   // kVK_ANSI_V
        if let down = CGEvent(keyboardEventSource: src, virtualKey: vKey, keyDown: true),
           let up = CGEvent(keyboardEventSource: src, virtualKey: vKey, keyDown: false) {
            down.flags = .maskCommand
            up.flags = .maskCommand
            down.post(tap: .cghidEventTap)
            usleep(10_000)
            up.post(tap: .cghidEventTap)
        }
        usleep(30_000)
        // 恢复剪贴板
        if let saved = saved {
            pb.clearContents()
            pb.setString(saved, forType: .string)
        }
    }

    // MARK: - 回车
    func pressReturn() {
        let src = CGEventSource(stateID: .hidSystemState)
        let ret: CGKeyCode = 0x24    // kVK_Return
        if let down = CGEvent(keyboardEventSource: src, virtualKey: ret, keyDown: true),
           let up = CGEvent(keyboardEventSource: src, virtualKey: ret, keyDown: false) {
            down.post(tap: .cghidEventTap)
            usleep(10_000)
            up.post(tap: .cghidEventTap)
        }
    }

    // MARK: - 辅助
    private func dismissMenuPopover() {
        // 让焦点回到目标 App：Mac 端 popover 打开时会抢焦点
        // 由 MenuBarController 主动 close popover
        NotificationCenter.default.post(name: .typeBridgeDismissPopover, object: nil)
    }
}
```

### 6.3 关键实现细节（写入文档，避免踩坑）

1. **换行不能塞进 unicode string**：Electron、部分终端、IDE 的输入框对 unicode string 里的 `\n` 不识别；必须单独发 `kVK_Return`。
2. **中文 / Emoji 用 unicode string**：只要目标 App 不是"强制走输入法"的特殊输入框（微信、QQ），unicode 注入对中文/Emoji 完全有效，因为它是"直接告诉系统这次按键产生的字符"。
3. **分块 20 个 code unit**：一次塞太长的 unicode string 在某些 App 会超时或截断。
4. **注入前关 popover**：菜单栏 popover 打开时焦点在 popover 上，注入会打到 popover 里的按钮。必须 `NSPopover.performClose` 且等 30ms。
5. **剪贴板兜底要恢复原内容**：不能永久污染用户剪贴板。
6. **失败判定**：`CGEvent` 创建失败（极少）或 `post` 后目标无反应——"无反应"无法检测，只能通过"特殊情况（目标是某些 Electron App）时优先走剪贴板"来规避。
7. **加密内容禁用剪贴板**：`secure=true` 时只走 unicode 注入；检测到系统处于安全输入态（`IsSecureEventInputEnabled()`）或注入失败时弹窗提示，绝不用剪贴板兜底，确保内容只出现在最终光标处。
8. **历史记录已移除**：v2.1 不再保存接收过的文本，启动时会清理旧的 `tb.history` 明文数据。

### 6.4 各 App 的策略建议

| 目标 App | 策略 |
|---|---|
| 大部分原生 App（Safari、Notes、Xcode、Terminal） | unicode 注入主路径即可 |
| VS Code / 微信 / QQ / 部分 Electron | 优先剪贴板 + Cmd+V（可在设置里全局切） |
| 输入法弹窗 | 无法可靠注入；建议用户在 Mac 端手动 |

---

## 7. 二维码生成与配对面板

### 7.1 网卡选择

```swift
// Net/InterfacePicker.swift
struct LANInterface: Identifiable, Hashable {
    let name: String       // en0
    let ip: String         // 192.168.1.10
    var id: String { "\(name)@\(ip)" }
}

enum InterfacePicker {
    private static let excludedPrefixes = ["utun", "awdl", "llw", "vmnet", "bridge", "docker", "tap", "tun"]

    static func candidates() -> [LANInterface] {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }

        var result: [LANInterface] = []
        var ptr = first
        while true {
            let flags = Int32(ptr.pointee.ifa_flags)
            let addr = ptr.pointee.ifa_addr.pointee
            if addr.sa_family == UInt8(AF_INET)
                && (flags & IFF_UP) != 0
                && (flags & IFF_LOOPBACK) == 0 {
                let name = String(cString: ptr.pointee.ifa_name)
                if !excludedPrefixes.contains(where: { name.hasPrefix($0) }) {
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(ptr.pointee.ifa_addr, socklen_t(addr.sa_len),
                                   &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        result.append(LANInterface(name: name, ip: String(cString: host)))
                    }
                }
            }
            if ptr.pointee.ifa_next == nil { break }
            ptr = ptr.pointee.ifa_next
        }
        // 私有网段优先
        let preferred = result.filter {
            $0.ip.hasPrefix("192.168.") || $0.ip.hasPrefix("10.")
            || $0.ip.hasPrefix("172.16.") || $0.ip.hasPrefix("172.17.")
            || $0.ip.hasPrefix("172.18.") || $0.ip.hasPrefix("172.19.")
            || $0.ip.hasPrefix("172.2") || $0.ip.hasPrefix("172.3")
        }
        return preferred.isEmpty ? result : preferred
    }

    /// 用户未选时，默认取第一个私有候选
    static func pickDefault() -> LANInterface? { candidates().first }
}
```

### 7.2 mDNS 服务注册

```swift
// 让二维码带 mdns 名（IP 漂移时安卓可 fallback）
func registerBonjour(ip: String) {
    let serviceName = "typebridge-\(UUID().uuidString.prefix(4).lowercased())"
    // 用 NetService 或 dnssd 注册 _typebridge._tcp.local.
    // 简化示例：
    let service = NetService(domain: "local.", type: "_typebridge._tcp.",
                             name: serviceName, port: Int32(8765))
    service.publish()
    // 客户端可通过 "<serviceName>.local" 解析到 ip
}
```

> 若走 FlyingFox，还需要额外向 Bonjour 注册服务（`NWListener.service` 是 NWListener 的能力，FlyingFox 不封装）。**最稳妥做法**：在启动 FlyingFox 之前先手写一份 DNS-SD 注册（`dnssd_register` / `NetService` 都可），把 `typebridge-xxxx.local:8765` 注册出去。

**如果觉得 Bonjour 注册麻烦（依赖 `NSBonjourServices` 声明、注册失败不易感知）**，v2 允许**先不注册 mDNS**，二维码只带 IP。这时 IP 漂移后用户必须重新扫码——这是可接受的取舍，不阻塞上线。**但文档里要明确写出这个选择**。

### 7.3 二维码生成

```swift
import CoreImage.CIFilterBuiltins

func makeQrImage(_ content: String, size: CGFloat = 220) -> NSImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(content.utf8)
    filter.correctionLevel = "M"
    guard let ci = filter.outputImage?.transformed(by: .init(scaleX: 8, y: 8)) else { return nil }
    let rep = NSCIImageRep(ciImage: ci)
    let image = NSImage(size: NSSize(width: size, height: size))
    image.addRepresentation(rep)
    return image
}
```

### 7.4 配对面板（独立窗口）

> **实现现状（v2.1）**：扫码并完成完整握手后，二维码立即隐藏并显示绿色「已连接」；此时可点「重新生成二维码」配对新设备。关闭窗口后再次打开会自动生成新二维码。

菜单栏 popover 宽度有限，二维码放不下。用独立窗口展示：

```swift
// MenuBar/PairingWindow.swift
final class PairingWindowController: NSWindowController {
    private var timer: Timer?
    private var currentQR: QrPayload?

    func show() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 320, height: 420),
            styleMask: [.titled, .closable], backing: .buffered, defer: false
        )
        window.title = "配对安卓设备"
        window.contentView = NSHostingView(rootView: PairingContentView(
            payload: currentQR,
            candidates: InterfacePicker.candidates(),
            onRegenerate: { [weak self] in self?.regenerate() },
            onSelectInterface: { [weak self] iface in self?.regenerate(with: iface) }
        ))
        window.center()
        window.makeKeyAndOrderFront(nil)
    }

    func regenerate(with iface: LANInterface? = nil) {
        guard let chosen = iface ?? InterfacePicker.pickDefault() else { return }
        let psk = Data((0..<32).map { _ in UInt8.random(in: 0...255) })
        PSKStore.shared.setCurrent(psk)
        let fp = Crypto.fingerprint(psk)
        let expires = Int(Date().addingTimeInterval(5 * 60).timeIntervalSince1970 * 1000)

        let payload = QrPayload(
            ip: chosen.ip, port: 8765, psk: psk, fp: fp, expires: expires
        )
        currentQR = payload
        // 5 分钟后自动作废
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 5 * 60, repeats: false) { [weak self] _ in
            PSKStore.shared.clear()
            self?.currentQR = nil
        }
    }
}
```

### 7.5 网络变化自动刷新

```swift
let monitor = NWPathMonitor()
monitor.pathUpdateHandler = { [weak self] path in
    guard path.status == .satisfied else { return }
    let current = InterfacePicker.pickDefault()
    if current?.ip != self?.lastBroadcastIP {
        self?.lastBroadcastIP = current?.ip
        DispatchQueue.main.async {
            self?.pairingWindow?.regenerate()
            // UI 上提示：网络已变化，二维码已更新
        }
    }
}
monitor.start(queue: DispatchQueue(label: "net.monitor"))
```

> **重要**：网络变化后旧 PSK 依然有效（尚未被替换）——但旧 IP 已经不可达，用户拿旧二维码也连不上。所以 `regenerate` 里要**同时换 IP 和 PSK**，同时旧 PSK 作废，防止"旧二维码还能完成握手但连到不可达的 IP"。

---

## 8. 指令系统（v2）

### 8.1 默认空库

```swift
final class ActionStore: ObservableObject {
    static let shared = ActionStore()
    @Published private(set) var actions: [ActionItem] = []
    @Published private(set) var version: Int = 0

    init() { restore() }   // 不再 ensureDefaults()

    private func restore() {
        version = UserDefaults.standard.integer(forKey: "action_version")
        if let data = UserDefaults.standard.data(forKey: "actions"),
           let list = try? JSONDecoder().decode([ActionItem].self, from: data) {
            actions = list
        }
        // 空库即空
    }
}
```

菜单栏在 `actions.isEmpty` 时显示"还没有自定义指令，点这里创建"。

### 8.2 模型（含 `sensitive`）

```swift
enum ActionBehavior: String, Codable, CaseIterable, Identifiable {
    case text = "text"
    case textEnter = "text_enter"
    var id: String { rawValue }
    var displayName: String { self == .text ? "纯文本" : "文本 + 回车" }
}

struct ActionItem: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var behavior: ActionBehavior
    var text: String
    var order: Int
    var sensitive: Bool = false
}
```

### 8.3 执行器

```swift
final class ActionDispatcher {
    static let shared = ActionDispatcher()

    func dispatch(_ action: ActionItem, liveText: String = "") {
        let payload = action.text.isEmpty ? liveText : action.text
        guard !payload.isEmpty else { return }
        KeyboardInjector.shared.inject(
            text: payload,
            pressEnter: action.behavior == .textEnter
        )
    }
}
```

> **v2 修正**：v1 里 `text` 行为（`pressEnter=false`）与 `text_enter` 的分支拆解，现在由 `pressEnter` 参数统一处理，不再需要两个方法。

### 8.4 编辑器 UI（简要）

参考 v1 §8 的 `ActionEditorView`，新增：

- `sensitive` 开关（"固定文本仅保存在 Mac 端，不发送给安卓"）；
- 当 `sensitive = true` 且 `text.isEmpty` 时，禁用保存（否则什么都不会发生）；
- 列表里 `sensitive` 指令的 `text` 显示为 `••••••`，眼睛图标切换明文。

---

## 9. 设备管理（按 `deviceId` 复用元数据）

```swift
final class DeviceStore: ObservableObject {
    static let shared = DeviceStore()
    @Published private(set) var devices: [Device] = []

    func find(by deviceId: String) -> Device? {
        devices.first(where: { $0.id == deviceId })
    }

    func insert(_ dev: Device) {
        devices.append(dev)
        save()
    }

    func updateLastSeen(_ deviceId: String, ip: String) {
        if let idx = devices.firstIndex(where: { $0.id == deviceId }) {
            devices[idx].lastSeen = Date()
            devices[idx].ip = ip
            devices[idx].online = true
            save()
        }
    }

    func markOffline(_ deviceId: String) {
        if let idx = devices.firstIndex(where: { $0.id == deviceId }) {
            devices[idx].online = false
            save()
        }
    }

    func remove(_ deviceId: String) {
        devices.removeAll(where: { $0.id == deviceId })
        save()
    }

    /// 30 天未活跃的设备折叠到"历史"
    var active: [Device] { devices.filter { $0.lastSeen > Date().addingTimeInterval(-30*86400) } }
    var archived: [Device] { devices.filter { $0.lastSeen <= Date().addingTimeInterval(-30*86400) } }
}
```

**关键语义（写死）**：

> `deviceId` 复用**仅限元数据**（`name` / `firstSeen` / `lastSeen` / `ip`）。PSK 每次扫码都换新的，**绝不复用**。`known=true` 只影响 UI 展示（"已配对设备"），不降低任何认证强度。

> **v2.1 移除语义**：在 Mac 上移除设备会**立即发送加密 `4006`、断开连接、清空该设备所有 sessionToken**。之后必须重新扫码（新的 PSK）才能再次配对。
> `Device.nameOverridden == true` 表示 Mac 手动改过名，后续连接不会被 Android 上报的 `deviceName` 覆盖。

---

## 10. 历史与持久化

> **v2.1 现状**：历史记录功能已整体移除（`HistoryStore` / `HistoryItem` / 菜单栏历史区块均已删除，启动时清理旧的 `tb.history` 明文数据）。下面的代码仅作历史设计记录。

```swift
struct HistoryItem: Identifiable, Codable {
    let id: UUID
    let text: String
    let receivedAt: Date
    let fromDevice: String
}

final class HistoryStore: ObservableObject {
    static let shared = HistoryStore()
    @Published private(set) var items: [HistoryItem] = []

    func append(text: String, from device: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.items.insert(HistoryItem(id: UUID(), text: text, receivedAt: Date(), fromDevice: device), at: 0)
            if self.items.count > 500 { self.items.removeLast() }
            self.save()
        }
    }

    func replay(_ item: HistoryItem) {
        KeyboardInjector.shared.inject(text: item.text, pressEnter: false)
    }
}
```

> **v2 修正**：`append` 内部 `DispatchQueue.main.async`，避免从 FlyingFox 的回调线程写 `@Published`。
>
> **v2.1 变更**：历史功能已移除；`text` 帧的 `secure=true` 会禁用剪贴板注入，若当前位置无法键盘注入则直接弹窗提示。

---

## 11. 菜单栏 UI

> **实现现状（v2.1）**：下面代码是 v2 参考。实际布局：
> - 菜单栏图标改为 App 图标；面板标题栏为「显示二维码配对」+「停止/启动」
> - 「指令库」按钮已移除；历史区块已删除，指令库区块暂时隐藏（底层逻辑保留）
> - 新增「Mac 名称」行（铅笔编辑）、设备行铅笔重命名、底部「开机自动启动」开关
> - 面板高度由 SwiftUI 动态内容尺寸决定，自动贴近菜单栏
> - 设备 IP 只显示 IPv4（剥离 `::ffff:`）
> 详见 §0.5。

```swift
struct MenuBarView: View {
    @ObservedObject var server: WebSocketServer = .shared
    @ObservedObject var devices: DeviceStore = .shared
    @ObservedObject var actions: ActionStore = .shared

    @State private var showActionEditor = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Circle().fill(statusColor).frame(width: 8, height: 8)
                Text(statusText).font(.headline)
                Spacer()
                Button(server.state == .online ? "停止" : "启动") {
                    Task {
                        if server.state == .online { await server.stop() }
                        else { await server.start() }
                    }
                }
            }

            Divider()

            // 配对入口
            Button {
                PairingWindowController.shared.show()
                PairingWindowController.shared.regenerate()
            } label: {
                Label("显示二维码配对", systemImage: "qrcode")
            }

            Divider()

            // 设备
            HStack {
                Text("已配对 (\(devices.active.count))")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
            }
            ForEach(devices.active) { dev in
                DeviceRow(dev: dev) {
                    devices.remove(dev.id)
                }
            }

            Divider()

            // 指令（空库时引导创建）
            HStack {
                Text("指令库 (\(actions.actions.count)) · v\(actions.version)")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("编辑") { showActionEditor = true }.font(.caption)
            }
            if actions.actions.isEmpty {
                Button {
                    showActionEditor = true
                } label: {
                    Text("还没有自定义指令，点这里创建")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(actions.actions.sorted(by: { $0.order < $1.order })) { act in
                            Button {
                                ActionDispatcher.shared.dispatch(act)
                            } label: {
                                VStack(spacing: 2) {
                                    Image(systemName: act.behavior == .textEnter
                                          ? "arrow.turn.down.left" : "text.alignleft")
                                    Text(act.name).font(.caption)
                                }
                                .padding(.horizontal, 10).padding(.vertical, 6)
                                .background(.quaternary, in: RoundedRectangle(cornerRadius: 6))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

        }
        .padding()
        .frame(width: 380)
        .sheet(isPresented: $showActionEditor) {
            ActionEditorView().environmentObject(actions)
        }
    }

    private var statusColor: Color {
        switch server.state {
        case .online: .green
        case .stopped: .gray
        case .error: .red
        }
    }
    private var statusText: String {
        switch server.state {
        case .online: "服务在线 · \(server.sessions.count) 台设备"
        case .stopped: "服务已停止"
        case .error(let msg): "服务异常：\(msg)"
        }
    }
}
```

---

## 12. 构建与运行

### 12.1 签名与能力

- **Entitlements**：`com.apple.security.network.server` + `com.apple.security.network.client`。
- **Info.plist**：`NSAccessibilityUsageDescription`、`NSLocalNetworkUsageDescription`、`NSBonjourServices`（若启用 mDNS）。
- **首次运行**：系统会弹辅助功能权限和局域网访问权限；两个都要允许，否则键入无效 / 扫描失败。
- **签名（v2.1）**：本机没有开发者证书，使用 **ad-hoc 签名**。每次重新构建后 cdhash 会变化，可能需要重新勾选辅助功能；若开启过「开机自动启动」，建议关掉再打开一次。
- **会话文件**：`~/Library/Application Support/TypeBridge/sessions.json`（0600），不随 App 重建而失效。

### 12.2 调试明文模式

开发阶段为了脱离加密排查问题，可加"**仅 Debug build** 允许明文"开关：

```swift
#if DEBUG
let allowPlaintext = UserDefaults.standard.bool(forKey: "debug_allow_plaintext")
if allowPlaintext {
    handlePlaintext(dict: dict, ws: ws)
    return
}
#endif
```

**Release build 无条件拒绝明文**（`hello` 里无 `authTag` / `sessionToken` 直接 4001）。

### 12.3 已知限制

| 限制 | 说明 |
|---|---|
| **需要用户走到 Mac 前扫码** | 只支持扫码的必然代价；`NeedRescan` UI 要明确写清 |
| **PSK 5 分钟过期** | 用户如果 5 分钟内没扫，需要重新生成 |
| **某些 App 注入不生效** | 微信 / QQ / VS Code 等强制走输入法的输入框——剪贴板兜底已缓解，但不保证 100% |
| **中文输入法组合态** | Mac 当前是中文输入法时，unicode 注入仍然有效，因为不走输入法 |

---

## 13. 常见问题（v2.1）

| 现象 | 原因 | 解决 |
|---|---|---|
| 安卓连不上 | Mac 端防火墙 / 服务未启动 | 系统设置 → 网络 → 防火墙 → 允许「投字」传入 |
| 连上了但不输出字 | 辅助功能权限未给 | 系统设置 → 隐私与安全性 → 辅助功能 → 勾选「投字」 |
| 中文/Emoji 变乱码 | 走了 v1 的键码路径 | v2 已改 `CGEventKeyboardSetUnicodeString`，重新构建 |
| 键入到自己的 popover 里 | popover 打开时注入 | v2 已加 `dismissMenuPopover` + 30ms 延迟 |
| 安卓提示"认证失败" | PSK 被重新生成 | 让用户重新扫码（`NeedRescan` UI 已引导） |
| 安卓提示"指纹不匹配" | Mac 被重新安装或二维码被篡改 | 重新生成二维码，在 UI 上对比指纹 |
| 生成的二维码扫描不了 | 网卡选错（选到 utun 或 docker） | 配对面板上的"网卡选择"下拉，改成 `en0` 之类的物理网卡 |
| 移除设备后还能继续发消息 | 旧版本移除只删除元数据 | v2.1 已改为：下发加密 `4006`、断开连接、撤销会话 |
| 指令面板空 | v2 默认空库；v2.1 暂时隐藏了 Mac 指令库入口 | Android 仍会收到空库；需要恢复编辑入口时取消 `MenuBarView` 中的注释 |
| 指令改后安卓没更新 | 全链路加密后需逐设备加密广播 | 检查 `ActionStore.onChanged` 是否接了 `broadcastActions` |

---

## 14. 与 Android v2.1 的契约（最终）

| 契约 | 说明 |
|---|---|
| 二维码是唯一信任根 | `hello` 的 `authTag` 由 PSK 算出；指纹在 `hello_ack` 里被客户端比对 |
| `deviceId` 仅作元数据 | `handleHello` **先 PSK 认证、后查 deviceId**，顺序不能反 |
| PSK 一次一码 | 用户每次点"生成新码"都会让旧 PSK 立即失效；**完整握手成功后也会立即清空本次 PSK**；已建立的会话不受影响 |
| 全链路加密 | 除 `hello` / `hello_ack` 外所有帧都是 `enc` |
| `sensitive` 固定文本不下发 | 指令库广播时 `text=""`；执行时只按 `actionId` 走 Mac 本地 |
| 会话恢复 | `sessionToken` **30 天滑动续期**，每次恢复轮换新 token；服务端持久化到 `sessions.json`，重启/重建均可恢复 |
| 设备命名 | `hello` 带 `deviceName`；`hello_ack` 带 `serverId`；Mac 手动改名优先于 Android 上报名称 |
| 二维码使用 | 完整握手成功后隐藏二维码并显示「已连接」；关闭配对窗口再打开会生成新码 |
| 设备移除 | Mac 移除时下发加密 `4006`、断开连接、撤销该设备所有会话；必须重新扫码才能再次配对 |
| 网络变化刷新二维码 | `NWPathMonitor` 检测到 IP 变化时，同时更新 IP 和 PSK，旧码作废 |
| 网卡选择 | 过滤 utun/awdl/vmnet/bridge/tap/tun/docker/llw，多候选下拉选择 |

---

## 构建与验证（v2.1）

```bash
cd server-macos
swift test                            # 13 个测试
./Scripts/build-app.sh                # 产物: dist/投字.app
open "dist/投字.app"
```

> 目前没有开发者证书，使用 ad-hoc 签名；重新构建后可能需要重新勾选辅助功能、重新开启开机启动。
