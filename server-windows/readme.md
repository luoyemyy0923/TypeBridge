# Windows 服务端设计文档（投字 / TypeBridge Windows）

> **状态：已实现（v2.1，三端对齐）** · 加密/会话/WS 服务端/键盘注入/二维码/存储/托盘与配对窗口/开机自启/`.exe`·`.msi` 打包均已完成；mDNS 未实现（二维码只带 IP）。
> Windows 端作为 **WebSocket 服务端**，核心功能与 macOS 端对等（mDNS 除外）：通过**二维码**向安卓下发 PSK、接受加密长连接、注入键盘。
> 技术栈：**Kotlin + Compose Desktop**（复用安卓端加密与协议逻辑）。

---

## 1. 目标与范围

| 目标 | 说明 |
|---|---|
| 功能对等 | 除 mDNS 外与 macOS 端 v2.1 对齐：扫码配对、加密通信、键盘注入、设备管理、会话恢复、开机自启 |
| 协议不变 | `typebridge://connect` URI、v2 帧协议、AES-256-GCM、错误码 4001-4006 三端一致 |
| 代码复用 | 最大化复用安卓端已实现的 `Crypto` / 帧模型 / 协议常量 |
| 无外部服务 | 局域网内单机运行，不依赖云、不依赖 Apple Bonjour（mDNS 可选） |

---

## 2. 技术选型与理由

| 决策 | 选择 | 理由 |
|---|---|---|
| 语言/UI | **Kotlin + Compose Desktop** | 与安卓客户端同语言同 UI 框架；`Tray()`/`Window`/通知是一等 API |
| 加密 | 复用安卓 `Crypto.kt`（HKDF + AES-GCM + HMAC） | BouncyCastle + `javax.crypto` 在桌面 JVM 全部可用，近乎原样平移 |
| WebSocket 服务端 | **Java-WebSocket**（TooTallNate） | 纯 JVM、轻量、无 HttpListener 依赖，是 FlyingFox 的最佳对应物 |
| 键盘注入 | **JNA + `user32::SendInput`** | `KEYEVENTF_UNICODE` 直接注入 Unicode；剪贴板 + `Ctrl+V` 兜底 |
| 二维码 | **ZXing**（core + javase） | 与安卓端同一套库生态 |
| 安全存储 | **DPAPI**（`CryptProtectData`，JNA） | Keychain 的 Windows 对应物；会话/设备用 `%APPDATA%` JSON 文件 |
| 打包 | Compose Desktop `jpackage`（`packageMsi` / `packageExe` / `createDistributable`） | 产出 `.msi` / `.exe`，输出到 `app/dist` |

> 被排除：**Swift 直迁**（无 SwiftUI、FlyingFox 仅 Apple 平台、社区不推荐用 Swift 写 Windows 生产 GUI）、**C#/.NET**（备选，加密原语内置但需重写协议逻辑）、**Go**（不契合现有 Java/Kotlin 栈）。

---

## 3. 模块映射（macOS → Windows）

| macOS（Swift） | Windows（Kotlin/JVM） | 复用 / 重写 |
|---|---|---|
| `Constants.swift`（TB 协议常量） | `Constants.kt` | ✅ 1:1 平移 |
| `Network/Crypto.swift` | `Crypto.kt`（复用安卓） | ✅ 1:1 平移 |
| `Network/Session.swift` + `SessionStore` | `Session.kt` + `SessionStore.kt` | ✅ 1:1 平移（持久化路径改 `%APPDATA%`） |
| `Network/MessageFrames.swift` | `MessageFrames.kt` | ✅ 1:1 平移 |
| `Model/Models.swift`（Device/Action/QrPayload/LANInterface） | `model/`（复用安卓模型 + QrPayload） | ✅ 1:1 平移 |
| `Network/WebSocketServer.swift` + `Connection.swift` + `BridgeMessageHandler.swift` | `network/WebSocketServer.kt` + `Connection.kt` | 🔶 重写（Java-WebSocket API） |
| `Inject/KeyboardInjector.swift`（CGEvent） | `inject/KeyboardInjector.kt`（SendInput） | 🔶 重写 |
| `Scan/QRCodeGenerator.swift`（CoreImage） | `scan/QrCodeGenerator.kt`（ZXing） | 🔶 重写 |
| `Net/InterfacePicker.swift`（getifaddrs） | `net/InterfacePicker.kt`（`java.net.NetworkInterface`） | 🔶 重写 |
| `Net/NetworkMonitor.swift`（NWPathMonitor） | `net/NetworkMonitor.kt`（轮询 + Win 网络事件） | 🔶 重写 |
| `Net/BonjourRegistrar.swift` | `net/MdnsRegistrar.kt`（jmdns） | ❌ 未实现（二维码只带 IP，安卓可直接连接） |
| `Storage/PSKStore.swift`（Keychain） | `storage/PskStore.kt`（DPAPI） | 🔶 重写 |
| `Storage/DeviceStore.swift` / `ActionStore.swift` | `storage/DeviceStore.kt` / `ActionStore.kt` / `SettingsStore.kt` | ✅ 平移（UserDefaults → `%APPDATA%` JSON） |
| `Constants/MacIdentity.swift` | `Constants/WindowsIdentity.kt`（serverId/serverName） | ✅ 平移（Host.current → `COMPUTERNAME`） |
| `App/AppDelegate.swift` + `MenuBar/*` | `app/`（`main.kt` + `Tray` + 窗口） | 🔶 重写（Compose Desktop） |
| `App/LaunchAtLogin.swift`（SMAppService） | `app/LaunchAtLogin.kt`（注册表 Run 键） | 🔶 重写 |

---

## 4. 工程结构

```
server-windows/
├── build.gradle.kts              # 根：统一声明插件版本（apply false）
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat         # Gradle 9.3.1 wrapper（复用安卓工程）
├── core/                         # 纯 JVM 核心库（无 Compose 依赖，可独立测试）
│   ├── build.gradle.kts
│   └── src/main/kotlin/cn/aclin/typebridge/windows/
│       ├── Constants.kt          # 协议常量（TB 对齐）
│       ├── WindowsIdentity.kt    # serverId / serverName
│       ├── crypto/Crypto.kt      # HKDF/AES-GCM/HMAC/fingerprint（复用安卓）
│       ├── network/
│       │   ├── TypeBridgeWebSocketServer.kt  # Java-WebSocket 封装（0.0.0.0:8765）
│       │   ├── Connection.kt     # 握手 / 会话恢复 / enc 解密 / 业务路由
│       │   ├── Session.kt        # 方向计数防重放
│       │   ├── SessionStore.kt   # token→key 缓存，30 天滑动续期
│       │   ├── MessageFrames.kt  # 帧构造
│       │   └── Message.kt        # JSON 解析入口
│       ├── inject/KeyboardInjector.kt  # JNA SendInput + 剪贴板兜底 + secure
│       ├── scan/QrCodeGenerator.kt     # ZXing
│       ├── net/InterfacePicker.kt / NetworkMonitor.kt
│       ├── launch/LaunchAtLogin.kt     # 注册表 Run 键
│       ├── storage/PskStore.kt / DeviceStore.kt / ActionStore.kt / SettingsStore.kt / AppDir.kt
│       └── model/Models.kt       # Device / ActionItem / QrPayload / LANInterface
└── app/                          # Compose Desktop 壳
    ├── build.gradle.kts
    ├── dist/                     # 打包输出（.msi / .exe / 绿色包）
    └── src/main/
        ├── kotlin/cn/aclin/typebridge/windows/app/
        │   ├── Main.kt               # application { Tray + Window }
        │   ├── MainPanel.kt          # 主面板（服务状态 / 设备列表 / 设置）
        │   └── PairingPanel.kt       # 配对窗口（二维码/指纹/重新生成）
        └── resources/                # app-icon.png / app-icon.ico
```

---

## 5. 依赖清单（`build.gradle.kts`）

实际版本（已落地）：**Gradle 9.3.1 + Kotlin 2.3.20 + Compose Multiplatform 1.11.1**。

- **core**：`kotlinx-serialization-json 1.10.0`（JSON 帧 + @Serializable 持久化）、`Java-WebSocket 1.5.7`（`api`）、`jna / jna-platform 5.14.0`（SendInput / DPAPI）、`zxing core / javase 3.5.3`（二维码）、`junit 4.13.2`（测试）。
- **app**：`compose.desktop.currentOs` + `compose.material3`，依赖 `project(":core")`。

> 说明：安卓端 `Crypto.kt` 用 `javax.crypto.Mac` 手写 HKDF，**无需 BouncyCastle**；mDNS 未实现（二维码只带 IP）。

---

## 6. 协议与加密对齐（关键，不能错）

**唯一信任根是二维码里的 PSK**；`deviceId` 只做元数据。以下函数必须与安卓端 `Crypto.kt`、macOS 端 `Crypto.swift` 产出完全一致，否则所有业务帧解不开：

| 函数 | 定义 |
|---|---|
| `deriveSessionKey` | `HKDF-SHA256(psk, salt=clientNonce‖serverNonce, info="typebridge-v2-session", len=32)` |
| `sessionId` | `sha256("typebridge-v2-session-id" ‖ clientNonce ‖ serverNonce)` 前 16 字节 hex |
| `authTag` | `HMAC-SHA256(psk, deviceId ‖ clientNonce)` |
| `encrypt` | AES-256-GCM，密文 `iv(12B)‖ciphertext‖tag(16B)` |
| `aad` | `utf8(sessionId) ‖ UInt64(counter, big-endian)`，**按方向独立计数** |
| `fingerprint` | `sha256(psk)` 前 4 字节小写 hex |
| `constantTimeEquals` | 恒时比较 |

**握手流程**（与 macOS `Connection.handleHello` 对齐）：
1. `v != 2` → `4002`；
2. 带 `sessionToken` 且未过期 → 会话恢复：复用旧 key，**轮换新 token**，滑动续期 30 天；
3. 否则完整 PSK 认证：`authTag` 校验失败 → `4001`；无有效 PSK → `4003`；**先认证、后查设备**；
4. 派生 `serverNonce` / `sessionKey` / `sessionId` / `token`，回 `hello_ack`（含 `serverId` / `serverName` / `serverFingerprint` / `known` / `firstSeenAt` / `sessionToken`）；
5. 完整握手成功后**清空本次 PSK**、隐藏二维码。

**错误码**：`4001` 认证失败 / `4002` 版本不兼容 / `4003` 二维码过期 / `4004` 指令不存在 / `4005` 设备上限 / `4006` 已被移除。

---

## 7. 各模块详细设计

### 7.1 WebSocket 服务端（Java-WebSocket）

```kotlin
class TypeBridgeWebSocketServer : WebSocketServer(InetSocketAddress("0.0.0.0", TB.port)) {
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        // 校验路径 /typebridge（handshake.resourceDescriptor）
        // 注册 Connection，超出 TB.maxConnections(8) 则发 4005 并关闭
    }
    override fun onMessage(conn: WebSocket, message: String) {
        // 解析 JSON：hello / enc / (debug 明文)
    }
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        // unregister + markOffline + broadcastStatus
    }
    override fun onError(conn: WebSocket?, ex: Exception) { /* 记日志 */ }
}
```

- 每连接一个 `Connection` 实例，持有 `session`（含 `outCounter`/`inCounter`）、`deviceId`、`deviceName`、对端 IP。
- `sendEncrypted(frame)`：`JSON → Session.encrypt → {"type":"enc","data":base64}`；**每台设备密钥不同，逐设备加密**。
- 广播 `status` / `actions`：遍历所有连接分别加密发送。
- 移除设备：先发加密 `4006`，再关闭连接，`SessionStore.prune(deviceId, keeping=[])`。
- 线程模型：Java-WebSocket 回调在各自线程；用锁保护 `Session` 计数器与连接表；UI 状态切回主线程。

### 7.2 键盘注入（JNA + SendInput）

主路径 `SendInput` + `KEYEVENTF_UNICODE`（`wVk=0, wScan=字符`），与 macOS `CGEventKeyboardSetUnicodeString` 结构对应：

```kotlin
fun injectUnicode(text: String) {
    // 分块（每块 ≤20 code unit），换行单独处理
    // SendInput: INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(
    //     wVk=0, wScan=char, dwFlags=KEYEVENTF_UNICODE))        // 按下
    //           + KEYEVENTF_UNICODE | KEYEVENTF_KEYUP           // 释放
}
```

- **换行**：文本内部 `\n`/`\r\n` → `Shift+Return`（软换行，避免误发送）；末尾「发送时按回车」→ 普通 `VK_RETURN`。
- **剪贴板兜底**：`java.awt` 剪贴板写入文本 → 模拟 `Ctrl+V`（`VK_CONTROL` + `VK_V`）→ 延迟后恢复原剪贴板。
- **`secure=true`（加密快捷输入）**：**默认禁用剪贴板**，只走 `SendInput` Unicode；注入失败时返回 `ok=false`，不留剪贴板痕迹。主面板可开启「加密内容允许使用剪贴板」，开启后与普通文本一样允许剪贴板兜底。
- **UIPI 限制（macOS 无对应物）**：非提权进程无法把输入注入到**管理员权限**窗口（SendInput 被静默丢弃）。策略：检测到目标窗口不可注入时弹窗提示（"请以普通权限运行目标程序，或手动输入"），不偷偷提权。

### 7.3 二维码 + 网卡 + PSK

- **PSK**：`SecureRandom` 生成 32 字节，`PskStore` 用 DPAPI 存当前 PSK（同一时刻仅一个有效）；完整握手成功后立即 `clear()`。
- **二维码 URI**：与 macOS `QrPayload.uriString` 完全一致，query 值做严格 percent-encoding（`+`/`/`/`=` 全部转义，否则安卓端 base64 PSK 解码失败）：
  ```
  typebridge://connect?v=2&ip=192.168.1.10&port=8765&psk=<b64>&fp=<8hex>&expires=<ms>
  ```
  > Windows 未实现 mDNS，因此不生成 `mdns` 字段（macOS 才有）；安卓端按 `ip` 直连。
- **网卡枚举**：`java.net.NetworkInterface.getNetworkInterfaces()`，过滤 `!isUp() || isLoopback()` 及虚拟网卡（名称含 `VirtualBox`/`VMware`/`vEthernet`/`WSL`/`TAP`/`Hyper-V`/`Loopback`），只取 IPv4；私有网段（`192.168.` / `10.` / `172.16-31.`）优先。
- **倒计时**：`expires = now + 5min`，过期由服务端在 `hello` 阶段判 `4003`（与 macOS 一致，客户端不拦截时钟）。

### 7.4 存储

| 数据 | Windows 位置 | 说明 |
|---|---|---|
| 会话密钥 | `%APPDATA%\TypeBridge\sessions.json` | 与 macOS `sessions.json` 对齐，重启/重建可恢复 |
| 设备元数据 | `%APPDATA%\TypeBridge\devices.json` | name/firstSeen/lastSeen/ip/online/nameOverridden |
| 指令库 | `%APPDATA%\TypeBridge\actions.json` | 默认空库 |
| 当前 PSK | `%APPDATA%\TypeBridge\psk.bin`（**DPAPI 加密**） | 对应 Keychain；生命周期短 |
| serverId / serverName | `%APPDATA%\TypeBridge\identity.json` | `serverId` 稳定 UUID；`serverName` 默认取 `COMPUTERNAME` |
| 界面偏好 | `%APPDATA%\TypeBridge\settings.json` | 优先剪贴板、加密内容允许使用剪贴板等设置 |

> Windows 无 POSIX 0600；`%APPDATA%` 本身按用户隔离。PSK 用 DPAPI（`CryptProtectData`，JNA）做加密，等价于 Keychain 的语义。

### 7.5 托盘与主面板（Compose Desktop）

- 托盘：只保留图标与 tooltip「投字」，**不带右键菜单**（规避 RDP 下原生菜单中文乱码）；双击托盘重新打开主面板，关闭面板后应用仍驻留托盘。
- 主面板（`MainPanel`，无边框 `Window` 400×540，定位到屏幕右下角/任务栏之上）：
  - 头部：服务状态（在线/停止 + 连接设备数）、「显示二维码配对」、「停止/启动」
  - 设备列表：改名（`nameOverridden=true` 后不再被安卓上报名覆盖）、移除（发 `4006` + 断开 + 撤销会话）
  - 设置：电脑名称、优先使用剪贴板注入、加密内容允许使用剪贴板、开机自动启动、退出
- 配对窗口（独立 `Window` 380×640）：二维码 + `ip:port` + 指纹 + 剩余秒数倒计时 + 网卡下拉 +「重新生成二维码」「复制连接串」；配对成功后显示绿色「已连接」。

### 7.6 开机自启

- 写注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`：键 `TypeBridge` = exe 绝对路径（JNA `Advapi32` 或直接写值）。
- 开关对应 macOS `SMAppService`；关闭时删除该键。

---

## 8. 与 macOS 的差异点

| 维度 | macOS | Windows |
|---|---|---|
| 注入权限 | 辅助功能权限（AXIsProcessTrusted） | 无显式授权；但受 **UIPI** 限制（提权窗口不可注入） |
| 注入主路径 | `CGEventKeyboardSetUnicodeString` | `SendInput` + `KEYEVENTF_UNICODE` |
| 剪贴板 | `NSPasteboard` + `Cmd+V` | `java.awt` 剪贴板 + `Ctrl+V` |
| 安全输入态 | `IsSecureEventInputEnabled()` | 安全桌面（UAC/锁屏），SendInput 无法到达 |
| 安全存储 | Keychain | DPAPI |
| mDNS | Bonjour（NetService） | 未实现（二维码只带 `ip`，安卓直连） |
| 开机自启 | SMAppService | 注册表 Run 键 |
| 会话/设备存储 | `~/Library/Application Support/` | `%APPDATA%\TypeBridge\` |
| 本机名 | `Host.current().localizedName` | `COMPUTERNAME`（`System.getenv`） |

---

## 9. 实施阶段回顾（均已完成）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **0 脚手架** | `server-windows` Gradle + Compose Desktop 工程；`main.kt` 跑通 `Tray` + 空窗 | 编译 + 启动可见托盘 |
| **1 核心移植** | `Crypto.kt` / `Session.kt` / `SessionStore.kt` / `MessageFrames.kt` / `Constants.kt` / 模型 | 单测对齐安卓 `CryptoTests`（加解密/派生/指纹恒时） |
| **2 WS 服务端** | Java-WebSocket 起 `0.0.0.0:8765`；hello 认证 / 会话恢复 / enc 解密 / 业务路由 / 4001-4006 / broadcast | 与安卓真机握手成功 |
| **3 键盘注入** | `SendInput` Unicode + 回车 + 剪贴板兜底 + `secure` 默认禁剪贴板（可开启） | 注入中英文/Emoji/换行/回车 |
| **4 二维码+PSK** | ZXing 生成 URI + 网卡枚举/过滤/排序 + PSK 生成/DPAPI/一次性消费 | 扫码配对 + `4003` 过期 |
| **5 托盘 UI** | 托盘（无右键菜单，双击打开主面板）+ 主面板 + 配对窗口（倒计时/网卡/复制串/重新生成）+ 设备列表（改名/移除 `4006`） | 全流程联调 |
| **6 增强** | 开机自启（注册表）+ 网络变化刷新二维码（mDNS 未实现，二维码只带 IP） | 换网自动刷新 |
| **7 打包发布** | `createDistributable` 出 `.exe`/`.msi` + 安卓真机↔Windows 端到端回归 | 可分发安装包 |

> 每阶段末尾做一次编译验证；**阶段 1 必须先过加密单测**——这是全链路的地基，错了后面全断。

---

## 10. 风险与待确认点

| 风险 | 说明 | 应对 |
|---|---|---|
| 中文/Emoji 注入 | 部分输入法强制的输入框（微信/QQ/某些 Electron）不识别 Unicode 注入 | 剪贴板兜底；必要时按目标 App 提供「优先剪贴板」开关（对齐 macOS `preferClipboard`） |
| UIPI（提权窗口） | 普通权限进程无法注入管理员窗口 | 检测后弹窗提示，不偷偷提权 |
| mDNS 未实现 | 不装 Bonjour；jmdns 发布 `_typebridge._tcp` 需验证与安卓 `NsdManager` 兼容性 | 当前二维码只带 IP，安卓可直接连接；如后续需要再引入 jmdns |
| DPAPI 依赖 | 换用户/换机器后 PSK 不可恢复 | PSK 本就是短命一次一码，无影响 |
| Java-WebSocket 路径 | 默认不校验 `/typebridge` 路径 | 已实现：握手回调校验 `resourceDescriptor`，不匹配返回 1008 |
| 托盘左键行为 | Windows 下 Compose `Tray` 右击弹菜单、双击 `onAction` | 已实现：托盘不带右键菜单，双击托盘重新打开主面板 |

---

## 11. 契约不变式（三端共享）

见根 `../readme.md`「三端共享的契约不变式」6 条。Windows 端实现同样钉死：

1. PSK 是唯一信任根，从二维码来，不从连接来；
2. `deviceId` 是纯元数据，不参与认证；
3. 未认证的 `hello` 不允许探测设备是否存在；
4. `sensitive` 固定文本永不下发，只在电脑端执行时本地读取；
5. `NeedRescan` 是唯一兜底；
6. 移除设备 = 断开连接 + 撤销会话。
