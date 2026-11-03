# 安卓端：局域网键入桥（投字 / TypeBridge Android）

> **版本：v2.1**
> 安卓端作为 **WebSocket 客户端**，通过**扫码**完成一次配对，之后走自动连接 / 自动重连；连接断开且无法在有限次数内自动恢复时，进入 `NeedRescan`，引导用户重新扫码。全链路 **AES-256-GCM** 加密，**PSK 为唯一信任根**，`deviceId` 仅作元数据标识。

---

## 0. v2.1 实现变更记录（以本节为准）

> 下面各章节保留了 v2 的设计说明；实现过程中有若干调整。**两者冲突时，以本节为准。**

### 0.1 命名与产物

- App 中文名：**投字**（Android `app_name` / macOS `CFBundleDisplayName`）
- Android：`applicationId = cn.aclin.typebridge`，`compileSdk/targetSdk = 36`，`minSdk = 24`，Java/Kotlin 目标 11
- macOS：SwiftPM 工程 `server-macos`（`TypeBridgeCore` + `TypeBridgeMac` + 测试），产物 `dist/投字.app`，Bundle ID 保持 `com.typebridge.mac`
- 构建：Gradle 9.3.1 + AGP 9.1（AGP 内置 Kotlin，已移除 `org.jetbrains.kotlin.android` 插件）；Gradle 9 需要 JDK 17+ 运行，源码/字节码目标仍为 Java 11
- 图标：笔 + 键盘。源文件 `assets/app-icon.png`（全出血）、`assets/app-icon-mac.png`（macOS 安全边距）；Mac 使用 `AppIcon.icns` + `MenuBarIcon.png`，Android 使用 `mipmap-*/ic_launcher.png`

### 0.2 协议与加密（双端最终实现）

- `sessionId` 由双端 nonce 确定性派生：
  `sha256("typebridge-v2-session-id" || clientNonce || serverNonce)` 前 16 字节 hex
- AAD = `utf8(sessionId) || UInt64(counter, big-endian)`
  - 发送方加密后 counter +1，接收方解密成功后 +1
  - counter 按方向独立计数，会话恢复时归零
- `hello` 新增可选 `deviceName`：Android 本机显示名，每次连接都会上报
- `hello_ack` 新增 `serverId`：Mac 的稳定 UUID，Android 用它作为 `Device.deviceId`
- 新增错误码 `4006 = device removed by Mac`：Mac 移除设备时先发加密 `error`，再断开
- `text` 帧新增可选 `record`（默认 `true`）：`record=false` 表示该文本**不写入历史**，Android 与 macOS 双方都跳过历史记录（用于加密快捷输入）
- 会话恢复：每次成功恢复都会**轮换新的 `sessionToken`** 并滑动续期，TTL = **30 天**；旧 token 保留一代作为 ack 丢失兜底
- 会话持久化：Mac 写入 `~/Library/Application Support/TypeBridge/sessions.json`（0600），不依赖 Keychain 或代码签名；重启、重新构建、换路径都能恢复
- 二维码 URI 的 query 值做严格 percent-encoding：避免 `+` 被 Android `Uri.getQueryParameter()` 解码成空格（`psk` 的 base64 尤其关键）
- Android 解析 `psk` 时额外把空格还原为 `+`，兼容旧二维码

### 0.3 二维码与 PSK 生命周期

- 一次**完整 PSK 握手成功**后：Mac 立即清空本次 PSK、隐藏二维码、显示「已连接」
- 再次配对需要点「重新生成二维码」
- 配对窗口关闭后再次打开：自动生成新二维码；窗口已打开时再次点菜单项只置前，不打断当前二维码
- 已连接设备通过 `sessionToken` 恢复，不再依赖 PSK

### 0.4 Android 最终 UI / 行为

- AppBar：标题显示当前服务名称 + 状态；右侧只有一个三点菜单：已配对设备 / 增加设备 / 快捷输入 / 历史记录 / 设置 / 断开连接
- 未连接（`Disconnected` / `NeedRescan`）：隐藏指令面板与输入区，只显示已配对设备列表与底部「新增连接」按钮；没有已配对设备时提示与按钮居中
- 连接中 / 重连中直接展示输入主页（输入框禁用，占位「正在连接…」），避免配对成功后再闪一下切换；冷启动有凭据时直接进入 `Connecting`
- 「已配对设备」中，当前连接设备带绿点「已连接」且不显示重连按钮
- 输入区：大文本域撑满剩余高度；底部为「发送时按回车」开关、快捷输入图标（闪电）+「发送」按钮
- 快捷输入：三点菜单进入管理页（分组增删改 / 条目增删改 / 分组与条目排序 / 默认分组「整理数据」）；输入栏闪电图标直接弹出**仅发送**的 BottomSheet；分组可展开折叠，状态持久化
- 历史：带 AppBar 的全屏 BottomSheet；内容最多 2 行，第二行左侧元信息、右侧图标为 重发 / 编辑 / 复制 / 删除；重发不新增历史，只更新原记录状态
- 设置：带 AppBar 的全屏 BottomSheet，可修改本机设备名称；保存后立即重连一次同步到 Mac
- Mac 移除设备时下发 `4006`：Android 立即断开并**删除本地该设备的配对记录**（已无法重连），弹窗提示重新扫码；标题与提示保留最后连接的服务名称
- 提示文案中的「Mac」全部替换为当前服务名称（无名称时显示「电脑」）

### 0.5 macOS 最终 UI / 行为

- 菜单栏图标使用 App 图标；面板标题栏为「显示二维码配对」+「停止/启动」
- 「指令库」按钮已移除；指令库 / 历史区块暂时隐藏（底层逻辑保留，可恢复）
- 新增「Mac 名称」设置（`hello_ack.serverName` 来源）、设备重命名、开机自动启动（`SMAppService`）
- 设备重命名优先于 Android 上报名称；未手动改名时每次连接同步最新名称
- 设备列表只显示 IPv4（自动剥离 `::ffff:` 前缀）
- 移除设备 = 立即断开 + 清空该设备所有会话，必须重新扫码才能再次配对
- 弹出面板使用 SwiftUI 动态高度，贴近菜单栏
- 键盘注入：文本内部换行使用 **Shift+Return**（避免聊天/编辑器误发送）；「发送时按回车」才使用普通 Return
- 构建产物无开发者证书，使用 ad-hoc 签名；重新构建后可能需要重新勾选辅助功能、重新开启开机启动

### 0.6 Android 本地存储与快捷输入（Room）

- 依赖：Room 2.8.4（`room-runtime` / `room-ktx` / `room-compiler`）+ KSP 2.3.9；`app/build.gradle.kts` 增加 `ksp` 插件
- 数据库：`typebridge.db`（版本 3），三张表
  - `quick_inputs`：`id`（自增主键）、`description`、`text`、`encrypted`、`enter`、`groupId`、`sortOrder`
  - `quick_input_groups`：`id`（自增主键）、`name`、`sortOrder`、`isDefault`
  - `history`：`id`、`text`、`sentAt`、`status`、`enter`、`deviceName`
- 迁移：
  - v1→v2：`quick_inputs` 增加 `enter INTEGER NOT NULL DEFAULT 0`（旧数据默认「否」）
  - v2→v3：创建 `quick_input_groups` 并插入「默认分组」；`quick_inputs` 增加 `groupId` / `sortOrder`，旧条目全部归入默认分组，`sortOrder = -id`（保持「新的在前」）
  - 旧 `EncryptedSharedPreferences` 历史在首次启动时一次性迁入 `history`（标记 `history_room_migrated`），此后 `HistoryStore` 仅保留作迁移用途
- 分组规则（单层，不支持嵌套）：
  - 分组支持新增 / 重命名 / 删除；删除分组时组内条目自动移动到默认分组，并顺延排序
  - 默认分组不可删除；默认分组可重命名，也可参与排序
  - 分组与组内条目都支持上移 / 下移排序
  - 分组展开 / 折叠状态按分组 id 持久化在 `EncryptedSharedPreferences`（`quick_group_collapsed`），关闭弹窗、重启 App 后保持
  - 默认分组提供「整理数据」：未分组（`groupId=0`）或分组已失效的条目全部移入默认分组
- 加密：`encrypted=true` 的快捷输入文本以 AES-256-GCM 密文存库；密钥 32B 只保存在 `EncryptedSharedPreferences`（键 `room_text_key`），AAD 按表区分（`typebridge-quick-input` / `typebridge-history`）；历史文本无论来源一律密文存储
- 加密内容展示：列表只显示首尾两个字符、中间全部替换为 `*`（≤2 字符全 `*`），不把明文长期留在内存；发送时才按 id 从数据库解密
- 加密项：不可编辑内容，只能删除后重新添加；支持「修改分组」（只更新 `groupId` / 组内排序，绝不写回文本）
- 明文快捷输入：正常回显、编辑、删除；编辑弹窗含「描述 / 文本 / 分组 / 加密保存 / 发送时按回车」；只填描述时自动把描述同步为内容，列表只显示一行
- 发送：快捷输入使用自身的 `enter` 开关（不跟随主页全局开关）；加密快捷输入以 `record=false` 发送，两端均不写历史

---

## 1. 功能清单

| 模块 | 说明 |
|---|---|
| 扫码配对 | CameraX + ML Kit 扫码，解析 `typebridge://connect?...` URI，校验后存设备 |
| 加密通信 | PSK 派生会话密钥，全链路 AES-256-GCM；`hello` / `hello_ack` 之外全部加密 |
| 自动连接 | `deviceId` 存 `EncryptedSharedPreferences`，启动即连，ON_START 自动恢复 |
| 状态机 | `Disconnected / Connecting / Connected / AutoReconnecting / NeedRescan` 五态 |
| 发送 | 输入框文本 + 全局"发送时回车"开关；ack 更新历史状态 |
| 快捷输入 | Room 持久化（描述 / 文本 / 是否加密 / 发送时回车）；加密项密文存储、界面只显示遮罩；单层分组管理（增删改、分组与组内排序、默认分组整理数据、折叠状态记忆） |
| 指令面板 | 由 Mac 下发的只读指令库；点击执行 `action`，`sensitive` 指令固定文本不下发 |
| 设备管理 | 已配对 Mac 列表（名称 / 指纹 / 首次配对 / 最后活跃）；不展示 IP；未连接时直接作为主页展示 |
| 历史 | Room 本地加密持久化，可重发 / 清空 / 删除单条；可一键复制 |
| 错误提示 | L1 扫码 / L2 网络 / L3 协议 / L4 应用，四层分类，每层明确可操作路径 |
| 诊断信息 | `NeedRescan` 面板一键复制诊断（手机 IP、Mac 记录 IP、错误码、时间戳） |

---

## 2. 工程结构

```
app/src/main/java/cn/aclin/typebridge/
├── ui/
│   ├── MainActivity.kt
│   ├── MainViewModel.kt
│   ├── screens/
│   │   ├── HomeScreen.kt
│   │   ├── ScanScreen.kt              # 扫码页（CameraX + ML Kit）
│   │   └── NeedRescanDialog.kt
│   ├── components/
│   │   ├── InputBar.kt
│   │   ├── StatusCard.kt
│   │   ├── DeviceList.kt
│   │   ├── ActionPanel.kt
│   │   └── HistoryList.kt
│   └── theme/Theme.kt
├── network/
│   ├── WebSocketClient.kt             # OkHttp WebSocket（明文 TCP + 应用层加密）
│   ├── Crypto.kt                      # PSK → 会话密钥 / AES-GCM 封装
│   ├── ConnectionState.kt
│   ├── Message.kt
│   └── ErrorClassifier.kt             # L1~L4 分类
├── discovery/
│   └── MdnsResolver.kt                # NsdManager 简单包装
├── scan/
│   ├── QrPayload.kt                   # 二维码 URI 解析
│   └── QrScanner.kt                   # CameraX + ML Kit 封装
├── storage/
│   ├── DeviceStore.kt                 # 设备列表（含 psk 加密存储）
│   ├── DeviceIdentity.kt              # 本机 deviceId（UUID）
│   ├── ActionPrefs.kt                 # 指令库缓存（EncryptedSharedPreferences）
│   ├── HistoryStore.kt                # 历史（EncryptedSharedPreferences）
│   └── PrefsFactory.kt                # EncryptedSharedPreferences 工厂
└── model/
    ├── Device.kt
    ├── ActionItem.kt
    └── HistoryItem.kt
```

> **v2.1 实际结构补充**：新增 `ui/screens/SettingsSheet.kt`、`DevicesSheet.kt`、`HistorySheet.kt`、`QuickInputSheet.kt`（`manage=false` 时仅发送）、`ui/components/RescanText.kt`、`util/NetworkUtils.kt`、`model/QuickInput.kt`、`model/QuickInputGroup.kt`、`storage/QuickInputPrefs.kt`（分组折叠状态）；新增 `data/` 包：`db/AppDatabase.kt`、`db/QuickInputEntity.kt`、`db/QuickInputGroupEntity.kt`、`db/HistoryEntity.kt`、`db/QuickInputDao.kt`、`db/QuickInputGroupDao.kt`、`db/HistoryDao.kt`、`DbKeyStore.kt`、`QuickInputRepository.kt`、`HistoryRepository.kt`；`storage/HistoryStore.kt` 仅保留用于旧历史迁移；删除 `ui/components/StatusCard.kt`（状态改由 AppBar 展示）。

### 2.1 依赖（`app/build.gradle.kts`）

```kotlin
android {
    minSdk = 24      // Android 7.0
    targetSdk = 34
}

dependencies {
    // Compose 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // 加密存储
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 扫码
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
```

> **v2.1 实际依赖**：以 `client-android/gradle/libs.versions.toml` 为准。
> 新增 `okhttp 4.12.0`、`security-crypto 1.1.0-alpha06`、`camera 1.3.4`、`mlkit barcode 17.2.0`；
> 本地数据库使用 `room 2.8.4`（runtime / ktx / compiler）+ KSP 2.3.9（`ksp` 插件）；
> 构建使用 Gradle 9.3.1 + AGP 9.1（内置 Kotlin，已移除 `kotlin-android` 插件），`compileSdk/targetSdk = 36`。

### 2.2 权限（`AndroidManifest.xml`）

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<!-- 若保留 mDNS fallback（推荐保留），需运行时权限（Android 13+） -->
<uses-permission
    android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"
    android:minSdkVersion="33" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

> **说明**：不申请定位权限；`NEARBY_WIFI_DEVICES` 只用于 mDNS 名称解析。

---

## 3. 通信协议（v2）

安卓视角的协议摘要，与 macOS 文档 §3 完全一致。**除 `hello` / `hello_ack` 外的所有帧在应用层统一包裹加密**。

### 3.1 帧结构

明文帧（TCP 上传输的是 JSON 字符串）：

```json
{
  "type": "hello" | "hello_ack" | "enc",
  "..." : "..."
}
```

- `hello` / `hello_ack`：明文字段，用于 PSK 认证与派生会话密钥。
- `enc`：业务帧的统一包裹，`data` 为 base64 密文（AES-256-GCM，AAD 里带会话 ID 防重放）。

```json
{ "type": "enc", "data": "<base64>" }
```

### 3.2 `hello`（Android → Mac）

```json
{
  "type": "hello",
  "v": 2,
  "cipher": "aes-256-gcm",
  "deviceId": "b7e2a9c1-...",           // App 内自定义 UUID
  "clientNonce": "<base64 16B>",
  "authTag": "<base64: HMAC-SHA256(PSK, deviceId || clientNonce)>"
}
```

> `authTag` 用来**证明客户端持有 PSK**。Mac 用它做认证；认证通过后才下发 `serverNonce` 并派生会话密钥。

### 3.3 `hello_ack`（Mac → Android）

```json
{
  "type": "hello_ack",
  "v": 2,
  "cipher": "aes-256-gcm",
  "serverNonce": "<base64 16B>",
  "serverName": "张三的MacBook",
  "serverFingerprint": "a1b2c3d4",      // sha256(PSK) 前 8 hex，供客户端与二维码 fp 比对
  "known": true,                         // 服务端是否已有该 deviceId
  "firstSeenAt": 1700000000000,
  "sessionToken": "<base64 32B>"        // 24h 有效；重连时携带可跳过完整握手
}
```

### 3.4 会话密钥派生

```
sessionKey  = HKDF-SHA256(
                ikm  = PSK,
                salt = clientNonce || serverNonce,
                info = "typebridge-v2-session",
                len  = 32
              )
```

之后所有 `enc.data` 用 `sessionKey` 做 AES-256-GCM 加密（每次随机 12B IV，IV 置于密文前置；AAD 为 `sessionId || frameCounter` 防重放）。

### 3.5 业务帧（`enc` 解包后）

**文本（含可选回车标志）**

```json
{
  "type": "text",
  "id": "a1b2c3d4",
  "text": "hello world",
  "enter": true,
  "record": true,                        // false = 不写入任何一端的历史（加密快捷输入）
  "ts": 1700000000000
}
```

> v2 把"是否回车"从"选哪条指令"改为 `enter` 布尔字段，与安卓端"发送时回车"开关一一对应。Mac 端按 `enter` 决定是否 `pressReturn()`。
> v2.1 新增 `record`（缺省视为 `true`）：`false` 时 Android 不落本地历史、Mac 也不调用 `HistoryStore.append`，用于加密快捷输入。

**执行指令**

```json
{
  "type": "action",
  "id": "b2c3d4e5",
  "actionId": "f47ac10b",
  "liveText": "hello world",
  "ts": 1700000000000
}
```

> `liveText` 仅在对应指令 `sensitive=false` 且 `text` 为空时使用；`sensitive=true` 时安卓端**必须**发空字符串，绝不携带用户密码。

**回执 / 心跳 / 状态 / 错误**

```json
{ "type": "ack", "id": "a1b2c3d4", "ok": true }
{ "type": "ping", "ts": 1700000000000 }
{ "type": "pong", "ts": 1700000000000 }
{ "type": "status", "state": "online", "devices": 1 }
{ "type": "error", "code": 4001, "msg": "auth failed" }
```

**指令库全量 / 增量**

```json
{
  "type": "actions",
  "version": 3,
  "actions": [
    { "id": "f47ac10b", "name": "登录密码", "behavior": "text_enter",
      "text": "", "order": 2, "sensitive": true }
  ]
}
```
```json
{ "type": "action_update", "version": 4, "change": "upsert",
  "action": { "id": "a99b88c7", "name": "登录密码", "behavior": "text_enter",
              "text": "", "order": 2, "sensitive": true } }
```

> **v2 关键变化**：`sensitive=true` 的指令，`text` 字段**永远是空字符串**（不落安卓缓存）。安卓端只展示名称 + 打码占位，执行时只发 `actionId`。这是纵深防御：即便某次连接降级为明文（debug / 异常），密码也不会通过局域网。

### 3.6 错误码

| code | 语义 | 客户端动作 |
|---|---|---|
| `4001` | 认证失败（PSK 不匹配） | 进 `NeedRescan`，清除本地 PSK |
| `4002` | 协议版本不兼容 | 提示升级 App / 电脑端版本 |
| `4003` | 二维码已过期 | 进 `NeedRescan`，提示重新生成 |
| `4004` | 指令不存在（`actionId` 找不到） | 提示"该指令已被移除"，刷新指令库 |
| `4005` | 服务端设备数超限 | 提示"该电脑已达连接上限" |
| `4006` | 已被 Mac 移除设备 | **立即停止重连**，删除本地该设备配对记录，保留最后服务名用于展示，弹窗提示重新扫码 |

> 说明：`4006` 由 Mac 在移除设备时以**加密 error 帧**下发，随后断开连接并撤销该设备的所有会话。
> v2.1 的移除语义已调整为：**移除即断开、即撤销，必须重新扫码才能再次配对**（不再"移除不拉黑"）。

### 3.7 会话恢复

重连时若本地 `sessionToken` 未过期：

```json
{ "type": "hello", "v": 2, "deviceId": "...", "deviceName": "我的手机",
  "sessionToken": "<base64>", "clientNonce": "<base64 16B>" }
```

Mac 校验通过后：

- 跳过 PSK 认证，复用上次的会话密钥
- **轮换一个新的 `sessionToken`**（非 `null`）写入 `hello_ack`，并把过期时间重置
- TTL = **30 天滑动**：只要在 30 天内连接过一次就不会过期
- Mac 端会话持久化在 `~/Library/Application Support/TypeBridge/sessions.json`（0600），重启/重建/换路径都能恢复

若校验失败（会话过期 / 被移除 / 会话文件丢失），退回完整 PSK 握手；若 PSK 也已失效（二维码已用过），直接进入 `NeedRescan`。Android 本地用 `EncryptedSharedPreferences` 保存 `sessionToken`、`sessionKey`、`sessionId` 以支持恢复。

---

## 4. 加密与 PSK

### 4.1 PSK 来源与生命周期

- **唯一来源**：扫码从二维码里取得。
- **一次一码**：Mac 端每次生成新码即让旧 PSK 作废；同一时间只有一个有效 PSK。
- **本地存储**：`EncryptedSharedPreferences`，与设备条目绑定。
- **作废时机**：本地收到 `4001 auth failed` 后**立即清除本设备 PSK**，并进 `NeedRescan`（下次扫码会带新 PSK）。

### 4.2 密钥派生与封装

```kotlin
// network/Crypto.kt
object Crypto {
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_LEN = 128
    private const val IV_LEN = 12

    /** 从 PSK + 双 nonce 派生会话密钥 */
    fun deriveSessionKey(psk: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): SecretKey {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(psk, clientNonce + serverNonce, "typebridge-v2-session".toByteArray()))
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, out.size)
        return SecretKeySpec(out, "AES")
    }

    /** AES-256-GCM 加密：返回 IV || ciphertext || tag */
    fun encrypt(key: SecretKey, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: SecretKey, data: ByteArray, aad: ByteArray): ByteArray {
        require(data.size > IV_LEN)
        val iv = data.copyOfRange(0, IV_LEN)
        val ct = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    /** hello 的 authTag */
    fun authTag(psk: ByteArray, deviceId: String, clientNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(psk, "HmacSHA256"))
        mac.update(deviceId.toByteArray())
        mac.update(clientNonce)
        return mac.doFinal()
    }

    fun fingerprint(psk: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(psk)
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
```

> 依赖：Bouncy Castle 已随 Android 一起提供 `HKDFBytesGenerator`（`org.bouncycastle.crypto.generators`），若没有则用 `javax.crypto.Mac` + 手写 HKDF。

### 4.3 会话 ID 与防重放

- `sessionId` 由双端 nonce 确定性派生：
  `sha256("typebridge-v2-session-id" || clientNonce || serverNonce)` 前 16 字节 hex
- `AAD = utf8(sessionId) || UInt64(counter, big-endian)`
- counter 按方向独立计数：发送方加密后 +1，接收方解密成功后 +1；会话恢复时归零
- 重放的旧帧因计数器/AAD 不匹配而解密失败，Mac 直接断开连接

---

## 5. 扫码连接

### 5.1 二维码 URI 格式

```
typebridge://connect
  ?v=2
  &ip=192.168.1.10
  &mdns=typebridge-a1b2c3.local     (可选：重连 fallback)
  &port=8765
  &psk=<base64 32B>
  &fp=<sha256(psk) 前 8 hex>
  &expires=1700000000000
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | ✅ | 协议版本，仅接受 `2` |
| `ip` | ✅ | IPv4，Mac 端当前局域网出口 IP |
| `mdns` | ⬜ | Bonjour 名，IP 失效时 fallback |
| `port` | ✅ | 默认 8765 |
| `psk` | ✅ | base64，解码后必须 32 字节 |
| `fp` | ✅ | PSK 指纹，`hello_ack.serverFingerprint` 需与其一致 |
| `expires` | ✅ | Mac 时间戳（毫秒）。**不硬性拦截**，过期由 Mac 端返回 `4003` |

> **实现注意（v2.1）**：所有 query 值必须做严格 percent-encoding（`+` → `%2B`、`/` → `%2F`、`=` → `%3D`）。
> 否则 Android 的 `Uri.getQueryParameter()` 会把未转义的 `+` 解码成空格，导致 base64 的 PSK 解码失败。
> Android 侧解析时也会把空格还原为 `+`，兼容旧二维码。

### 5.2 解析与校验

```kotlin
// scan/QrPayload.kt
@Serializable
data class QrPayload(
    val v: Int,
    val ip: String,
    val mdns: String?,
    val port: Int,
    val psk: ByteArray,          // 解码后
    val fp: String,
    val expires: Long
)

fun parseQr(raw: String): Result<QrPayload> {
    if (!raw.startsWith("typebridge://connect")) return Result.failure(NotTypeBridge)
    val uri = Uri.parse(raw)
    if (uri.getQueryParameter("v")?.toIntOrNull() != 2) return Result.failure(UnsupportedVersion)
    val pskB64 = uri.getQueryParameter("psk") ?: return Result.failure(MissingField)
    val psk = Base64.decode(pskB64, Base64.NO_WRAP)
    if (psk.size != 32) return Result.failure(InvalidPsk)
    return Result.success(QrPayload(
        v = 2,
        ip = uri.getQueryParameter("ip") ?: return Result.failure(MissingField),
        mdns = uri.getQueryParameter("mdns"),
        port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8765,
        psk = psk,
        fp = uri.getQueryParameter("fp") ?: return Result.failure(MissingField),
        expires = uri.getQueryParameter("expires")?.toLongOrNull() ?: Long.MAX_VALUE
    ))
}
```

**过期判断**：**不在客户端拦截**。理由：手机与 Mac 时钟可能不一致，误杀概率高。改为由 Mac 端在 `hello` 阶段返回 `4003 expired`，客户端按错误码展示提示。

### 5.3 扫码 UI 流程

1. 首页 → "扫码添加设备" → 进入 `ScanScreen`
2. `ScanScreen`：CameraX `PreviewView` + 中央方形扫描框 + 底部提示"对准 Mac 菜单栏的 TypeBridge 二维码"
3. 命中二维码 → `parseQr` → 弹半屏确认：
   ```
   即将连接
   ─────────────
   设备：MacBook（等待连接后确认名称）
   指纹：a1b2c3d4
   ─────────────
   [取消]        [连接]
   ```
4. 用户确认 → `vm.connectTo(device, psk)` → 回到首页，状态卡片进入 `Connecting`

> 若用户曾配对过同一 `deviceId`（即使本地 `deviceId` 已经变化，这里暂无办法识别），新设备条目会创建。v2 接受"重装 = 新设备"的语义。

### 5.4 CameraX + ML Kit 封装

```kotlin
// scan/QrScanner.kt
@Composable
fun ScanScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var handled by remember { mutableStateOf(false) }

    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            )
            analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { image ->
                if (handled) { image.close(); return@setAnalyzer }
                val media = image.toInputImage()
                scanner.process(media)
                    .addOnSuccessListener { barcodes ->
                        barcodes.firstOrNull()?.rawValue?.let {
                            handled = true
                            onResult(it)
                        }
                    }
                    .addOnCompleteListener { image.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { /* 相机不可用提示 */ }
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    }, modifier = Modifier.fillMaxSize())
}
```

> `image.toInputImage()` 需要 `androidx.camera:camera-mlkit-vision` 或手动转换 `ImageProxy → InputImage`。为简洁起见以 `toInputImage` 表示；实现时按官方示例补齐。

### 5.5 扫码失败处理

| 场景 | 提示 |
|---|---|
| 非 TypeBridge 二维码 | 静默继续扫描 |
| 二维码字段缺失 / PSK 非法 | "二维码不完整，请在 Mac 上重新生成" |
| 相机权限被拒 | 弹窗引导去系统设置开启 |
| 相机不可用（模拟器） | 显示"当前设备无相机"，返回首页 |

---

## 6. WebSocket 客户端

### 6.1 构造与生命周期

> **实现注意（v2.1）**：`serverUrl` 必须带 WebSocket 路径，即 `ws://<ip>:<port>/typebridge`。
> 实际构造还包含 `deviceName`（本机显示名）、`cachedSessionKey`、`cachedSessionId`；所有回调统一切回主线程。
> 所有帧的 JSON 解析、加解密、重连逻辑均在客户端完成，ViewModel 只处理状态与存储。

```kotlin
// network/WebSocketClient.kt
class WebSocketClient(
    private val serverUrl: String,             // ws://<ip>:<port>
    private val deviceId: String,
    private val psk: ByteArray,
    private val expectedFingerprint: String,   // 二维码里的 fp，用于比对
    private val cachedSessionToken: String?,   // 可选：本地持久化的会话 token
    private val actionVersion: Int,
    private val listener: ClientListener
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var opened = false
    private var sessionKey: SecretKey? = null
    private var sessionId: String = UUID.randomUUID().toString()
    private var counter: Long = 0
    private var manualClose = false

    fun connect() {
        manualClose = false
        val req = Request.Builder().url(serverUrl)
            .addHeader("User-Agent", "TypeBridge-Android/2")
            .build()

        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                opened = true
                sendHello(ws)
            }
            override fun onMessage(ws: WebSocket, text: String) = handleFrame(text)
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                opened = false
                if (!manualClose) listener.onDisconnected(classify(t, r))
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                opened = false
                if (!manualClose) listener.onDisconnected(DisconnectReason.Closed)
            }
        })
    }

    fun disconnect() {
        manualClose = true
        opened = false
        webSocket?.close(1000, "user")
        webSocket = null
    }

    // MARK: 发送 API
    fun sendText(text: String, enter: Boolean): String? {
        val key = sessionKey ?: return null
        val id = UUID.randomUUID().toString()
        val payload = buildJson {
            "type" to "text"; "id" to id; "text" to text; "enter" to enter
            "ts" to System.currentTimeMillis()
        }
        sendEncrypted(key, payload)
        return id
    }

    fun sendAction(actionId: String, liveText: String): String? {
        val key = sessionKey ?: return null
        val id = UUID.randomUUID().toString()
        val payload = buildJson {
            "type" to "action"; "id" to id; "actionId" to actionId
            "liveText" to liveText; "ts" to System.currentTimeMillis()
        }
        sendEncrypted(key, payload)
        return id
    }

    fun sendPing() { /* 明文 ping 或加密通道内的 ping 均可；此处用加密帧 */ }

    // MARK: 握手
    private fun sendHello(ws: WebSocket) {
        val clientNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val json = if (cachedSessionToken != null) {
            buildJson {
                "type" to "hello"; "v" to 2; "deviceId" to deviceId
                "sessionToken" to cachedSessionToken
                "clientNonce" to b64(clientNonce)
            }
        } else {
            buildJson {
                "type" to "hello"; "v" to 2; "deviceId" to deviceId
                "cipher" to "aes-256-gcm"
                "clientNonce" to b64(clientNonce)
                "authTag" to b64(Crypto.authTag(psk, deviceId, clientNonce))
            }
        }
        this.clientNonce = clientNonce
        ws.send(json)
    }

    private fun handleFrame(text: String) {
        val root = Json.parseToJsonElement(text).jsonObject
        when (root["type"]?.jsonPrimitive?.content) {
            "hello_ack" -> handleHelloAck(root)
            "enc" -> handleEncryptedFrame(root)
        }
    }

    private fun handleHelloAck(obj: JsonObject) {
        // 指纹比对：防止误扫/伪造
        val fp = obj["serverFingerprint"]?.jsonPrimitive?.content ?: return fail(FingerprintMismatch)
        if (!fp.equals(expectedFingerprint, ignoreCase = true)) {
            listener.onError("指纹不匹配", code = 4001); disconnect(); return
        }
        val serverNonce = b64d(obj["serverNonce"]!!.jsonPrimitive.content)
        sessionKey = Crypto.deriveSessionKey(psk, clientNonce!!, serverNonce)
        sessionToken = obj["sessionToken"]?.jsonPrimitive?.contentOrNull
        listener.onConnected(
            known = obj["known"]?.jsonPrimitive?.boolean ?: false,
            serverName = obj["serverName"]?.jsonPrimitive?.content ?: "",
            firstSeenAt = obj["firstSeenAt"]?.jsonPrimitive?.long ?: 0L,
            sessionToken = sessionToken
        )
    }

    private fun handleEncryptedFrame(obj: JsonObject) {
        val key = sessionKey ?: return
        val raw = b64d(obj["data"]!!.jsonPrimitive.content)
        val plain = Crypto.decrypt(key, raw, aadForNext())
        val msg = Json.parseToJsonElement(plain.decodeToString()).jsonObject
        route(msg)
    }

    private fun sendEncrypted(key: SecretKey, plain: String) {
        counter += 1
        val aad = "$sessionId|$counter".toByteArray()
        val ct = Crypto.encrypt(key, plain.toByteArray(), aad)
        val frame = buildJson { "type" to "enc"; "data" to b64(ct) }
        webSocket?.send(frame)
    }

    private fun route(msg: JsonObject) {
        when (msg["type"]?.jsonPrimitive?.content) {
            "ack" -> listener.onAck(msg["id"]!!.jsonPrimitive.content,
                                    msg["ok"]!!.jsonPrimitive.boolean)
            "pong" -> listener.onPong()
            "status" -> listener.onStatus(msg["state"]!!.jsonPrimitive.content,
                                          msg["devices"]!!.jsonPrimitive.int)
            "actions" -> listener.onActions(msg["version"]!!.jsonPrimitive.int,
                                            msg["actions"] as JsonArray)
            "action_update" -> listener.onActionUpdate(
                msg["version"]!!.jsonPrimitive.int,
                msg["change"]!!.jsonPrimitive.content,
                msg["action"] as JsonObject
            )
            "error" -> {
                val code = msg["code"]?.jsonPrimitive?.int ?: -1
                listener.onServerError(code, msg["msg"]?.jsonPrimitive?.content ?: "")
            }
        }
    }
}

// 监听器：去除 v1 里的 onError 重载歧义，拆成两个语义清晰的方法
interface ClientListener {
    fun onConnected(known: Boolean, serverName: String, firstSeenAt: Long, sessionToken: String?)
    fun onDisconnected(reason: DisconnectReason)
    fun onAck(id: String, ok: Boolean)
    fun onPong()
    fun onStatus(state: String, devices: Int)
    fun onActions(version: Int, actions: JsonArray)
    fun onActionUpdate(version: Int, change: String, action: JsonObject)
    fun onServerError(code: Int, msg: String)
    fun onError(msg: String, code: Int = -1)          // 客户端侧错误（本地判定）
}

enum class DisconnectReason { Closed, Timeout, Refused, UnknownHost, Network, FingerprintMismatch }
```

> **关键修正点**（v1 错误）：
> - 构造器带 `actionVersion`、`psk`、`expectedFingerprint`。
> - 监听器由调用方作为参数传入，不再 `.also { object : ... }` 凭空构造丢弃。
> - 去掉 `onError(msg, code)` 重载；`onServerError` 只处理服务端下发的 `error` 消息，`onError` 只处理本地网络/握手错误。

### 6.2 密码学前提

`sessionKey` 只有 `hello_ack` 成功后才有值；在它为空时 `sendText` / `sendAction` 返回 `null`，ViewModel 层据此拒绝发送。

---

## 7. 状态机与自动重连

### 7.1 状态定义

```kotlin
sealed class AppConnState {
    object Disconnected : AppConnState()
    object Connecting : AppConnState()
    data class Connected(val serverName: String, val known: Boolean) : AppConnState()
    data class AutoReconnecting(
        val attempt: Int, val reason: DisconnectReason,
        val blocker: PrecheckBlocker?
    ) : AppConnState()
    data class NeedRescan(val reason: RescanReason, val detail: String = "") : AppConnState()
}

sealed class PrecheckBlocker {
    object NoWifi : PrecheckBlocker()
    data class WrongSubnet(val phoneIp: String, val macIp: String) : PrecheckBlocker()
}

enum class RescanReason { AuthFailed, Expired, WrongSubnet, NoWifi, MacUnreachable, FingerprintMismatch, Manual }
```

### 7.2 生命周期

```kotlin
// ui/MainActivity.kt
class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认首次启动只打开首页，不自动连接；有设备才连
        vm.bootstrap()
        setContent { TypeBridgeTheme { HomeScreen(vm) } }
    }

    override fun onStart() { super.onStart(); vm.onAppForegrounded() }
    override fun onStop()  { super.onStop();  vm.onAppBackgrounded() }
}
```

> **关键**：用 `onStart` / `onStop` 而非 `onResume` / `onPause`。`onPause` 在下拉通知栏、来电、输入法弹出时都会触发，会引发无谓断开。

### 7.3 ViewModel 核心（完整）

```kotlin
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<AppConnState>(AppConnState.Disconnected)
    val state: StateFlow<AppConnState> = _state

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private val _currentDevice = MutableStateFlow<Device?>(null)
    val currentDevice: StateFlow<Device?> = _currentDevice

    private val _actions = MutableStateFlow<List<ActionItem>>(emptyList())
    val actions: StateFlow<List<ActionItem>> = _actions

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history

    private val _sendEnter = MutableStateFlow(true)
    val sendEnter: StateFlow<Boolean> = _sendEnter

    private var ws: WebSocketClient? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var isInForeground = false
    private var userRequestedDisconnect = false

    fun bootstrap() {
        _devices.value = DeviceStore.list(getApplication())
        _history.value = HistoryStore.list(getApplication())
        _sendEnter.value = DeviceStore.getSendEnter(getApplication())
        val last = DeviceStore.lastConnected(getApplication()) ?: return
        _currentDevice.value = last
        // 首次启动不立刻连，等 onStart 触发
    }

    fun onAppForegrounded() {
        isInForeground = true
        userRequestedDisconnect = false
        val dev = _currentDevice.value ?: return
        if (ws?.isOpen() == true) return
        beginConnect(dev, firstAttempt = true)
    }

    fun onAppBackgrounded() {
        isInForeground = false
        reconnectJob?.cancel()
        ws?.disconnect(); ws = null
        _state.value = AppConnState.Disconnected
    }

    /** 扫码后调用 */
    fun connectWithScannedDevice(payload: QrPayload) {
        val phoneIp = NetworkUtils.currentWifiIp(getApplication())
        if (phoneIp == null) {
            _state.value = AppConnState.NeedRescan(RescanReason.NoWifi)
            return
        }
        if (!NetworkUtils.isLikelySameLan(phoneIp, payload.ip)) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.WrongSubnet,
                detail = "手机 $phoneIp / Mac ${payload.ip}"
            )
            return
        }
        val device = Device(
            deviceId = DeviceIdentity.get(getApplication()),
            ip = payload.ip, mdns = payload.mdns, port = payload.port,
            serverName = "", firstSeenAt = 0, lastSeenAt = System.currentTimeMillis(),
            psk = payload.psk, fingerprint = payload.fp,
            sessionToken = null, actionVersion = 0
        )
        DeviceStore.save(getApplication(), device)
        _devices.value = DeviceStore.list(getApplication())
        _currentDevice.value = device
        beginConnect(device, firstAttempt = true)
    }

    private fun beginConnect(device: Device, firstAttempt: Boolean) {
        reconnectJob?.cancel()
        ws?.disconnect()
        _state.value = AppConnState.Connecting

        ws = WebSocketClient(
            serverUrl = "ws://${device.ip}:${device.port}",
            deviceId = device.deviceId,
            psk = device.psk!!,
            expectedFingerprint = device.fingerprint!!,
            cachedSessionToken = device.sessionToken,
            actionVersion = device.actionVersion,
            listener = object : ClientListener {
                override fun onConnected(known: Boolean, serverName: String,
                                        firstSeenAt: Long, sessionToken: String?) {
                    reconnectAttempt = 0
                    _state.value = AppConnState.Connected(serverName, known)
                    val updated = device.copy(
                        serverName = serverName,
                        firstSeenAt = if (firstSeenAt > 0) firstSeenAt else device.firstSeenAt,
                        lastSeenAt = System.currentTimeMillis(),
                        sessionToken = sessionToken
                    )
                    _currentDevice.value = updated
                    DeviceStore.save(getApplication(), updated)
                }

                override fun onDisconnected(reason: DisconnectReason) {
                    if (!isInForeground) { _state.value = AppConnState.Disconnected; return }
                    startAutoReconnect(device, reason, attempt = 1)
                }

                override fun onServerError(code: Int, msg: String) {
                    when (code) {
                        4001 -> {
                            // 认证失败：PSK 已作废，必须重新扫码
                            DeviceStore.clearPsk(getApplication(), device.deviceId)
                            _state.value = AppConnState.NeedRescan(RescanReason.AuthFailed, msg)
                        }
                        4003 -> _state.value = AppConnState.NeedRescan(RescanReason.Expired, msg)
                        4002 -> _state.value = AppConnState.NeedRescan(
                            RescanReason.MacUnreachable, "协议版本不兼容，请升级 App 或 Mac 端"
                        )
                        4004 -> {
                            // 指令不存在：刷新指令库
                            // 主动再发一次同步请求或等待下次 push
                        }
                        4005 -> _state.value = AppConnState.NeedRescan(
                            RescanReason.MacUnreachable, "该电脑已达连接上限"
                        )
                    }
                }

                override fun onError(msg: String, code: Int) {
                    if (!isInForeground) return
                    startAutoReconnect(device, DisconnectReason.UnknownHost, attempt = 1)
                }

                override fun onAck(id: String, ok: Boolean) {
                    updateHistoryStatus(id, if (ok) "已送达" else "失败")
                }
                override fun onPong() {}
                override fun onStatus(state: String, devices: Int) {}
                override fun onActions(version: Int, actions: JsonArray) {
                    val list = ActionPrefs.replaceAll(getApplication(), version, actions)
                    _actions.value = list
                    DeviceStore.updateActionVersion(getApplication(), device.deviceId, version)
                }
                override fun onActionUpdate(version: Int, change: String, action: JsonObject) {
                    val list = ActionPrefs.applyUpdate(getApplication(), version, change, action)
                    _actions.value = list
                }
            }
        ).also { it.connect() }

        if (!firstAttempt) return
    }

    private fun startAutoReconnect(device: Device, reason: DisconnectReason, attempt: Int) {
        if (attempt > MAX_ATTEMPTS) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.MacUnreachable,
                detail = reason.name
            )
            return
        }
        // 快速预检
        val phoneIp = NetworkUtils.currentWifiIp(getApplication())
        if (phoneIp == null) {
            _state.value = AppConnState.NeedRescan(RescanReason.NoWifi)
            return
        }
        if (!NetworkUtils.isLikelySameLan(phoneIp, device.ip)) {
            _state.value = AppConnState.NeedRescan(
                RescanReason.WrongSubnet, detail = "手机 $phoneIp / Mac ${device.ip}"
            )
            return
        }

        val delayMs = when (attempt) { 1 -> 2_000L; 2 -> 4_000L; 3 -> 8_000L; else -> 15_000L }
        _state.value = AppConnState.AutoReconnecting(attempt, reason, null)

        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if (!isInForeground) return@launch
            // 重新构造 ws 并连接
            ws?.disconnect()
            beginConnect(device, firstAttempt = false)
            // 递归触发下一次重试（若本次再失败）
            // 由 onDisconnected/onError 继续推进 attempt
        }
    }

    /** 用户主动重试（UI 上"重试"按钮） */
    fun manualRetry() {
        val d = _currentDevice.value ?: return
        userRequestedDisconnect = false
        reconnectAttempt = 0
        beginConnect(d, firstAttempt = true)
    }

    /** 用户主动断开（顶部菜单） */
    fun manualDisconnect() {
        userRequestedDisconnect = true
        ws?.disconnect(); ws = null
        _state.value = AppConnState.Disconnected
    }

    /** 发送 */
    fun send(text: String) {
        if (text.isBlank()) return
        if (_state.value !is AppConnState.Connected) return
        val enter = _sendEnter.value
        val id = ws?.sendText(text, enter) ?: return
        val item = HistoryItem(
            id = id, text = text, sentAt = System.currentTimeMillis(),
            status = "发送中", enter = enter,
            deviceName = _currentDevice.value?.serverName ?: ""
        )
        _history.value = listOf(item) + _history.value
        HistoryStore.save(getApplication(), item)
    }

    fun execAction(actionId: String, liveText: String) {
        val act = _actions.value.firstOrNull { it.id == actionId } ?: return
        val live = if (act.sensitive) "" else liveText
        ws?.sendAction(actionId, live)
    }

    fun setSendEnter(v: Boolean) {
        _sendEnter.value = v
        DeviceStore.setSendEnter(getApplication(), v)
    }

    fun resend(item: HistoryItem) = send(item.text)

    fun clearHistory() {
        HistoryStore.clear(getApplication())
        _history.value = emptyList()
    }

    fun removeDevice(device: Device) {
        DeviceStore.remove(getApplication(), device.deviceId)
        _devices.value = DeviceStore.list(getApplication())
        if (_currentDevice.value?.deviceId == device.deviceId) {
            ws?.disconnect(); ws = null
            _currentDevice.value = null
            _state.value = AppConnState.Disconnected
        }
    }

    private fun updateHistoryStatus(id: String, status: String) {
        _history.value = _history.value.map {
            if (it.id == id) it.copy(status = status) else it
        }
        HistoryStore.updateStatus(getApplication(), id, status)
    }

    companion object { const val MAX_ATTEMPTS = 3 }
}
```

### 7.4 "无法自动连接"的严格定义（写死）

> **`NeedRescan` 的进入条件**（满足其一）：
> 1. `onStart` 后快速预检失败：`NoWifi` 或 `WrongSubnet`；
> 2. 快速预检通过但 3 次指数退避（2s / 4s / 8s）全部连接失败；
> 3. 服务端返回 `4001`（PSK 失效）或 `4003`（过期）；
> 4. 收到 `hello_ack` 但 `serverFingerprint` 与二维码 `fp` 不一致；
> 5. 服务端返回 `4006`（被 Mac 移除设备）：立即断开、清空本地 PSK/会话并弹窗。
>
> **不算 `NeedRescan` 的场景**：
> - 用户主动 `manualDisconnect()`；
> - 用户主动切后台触发 `onStop` 断开——回前台后照常走 `onStart` 自动重连；
> - 短暂 Wi-Fi 抖动（3 次重试内恢复）。

### 7.5 提示频率控制

`NeedRescan` 状态由 **AppBar 的状态副标题 + 自动弹出的 `NeedRescanDialog`** 展示；不带系统通知。用户点「稍后」后不再重复弹出，直到状态再次变化。

---

## 8. 设备唯一 ID

```kotlin
// storage/DeviceIdentity.kt
object DeviceIdentity {
    private const val KEY = "device_id"
    fun get(context: Context): String {
        val prefs = PrefsFactory.encrypted(context)
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
```

**语义（写死）**：

- `deviceId` 是 App 内自定义 UUID，**卸载重装会变**；
- 它**只用于服务端元数据识别**（保持设备条目连续），**不参与认证**；
- 认证始终走本次扫码的 PSK；如果重新安装了 App，新 `deviceId` 会被服务端当作新设备记录（旧记录保留在服务端设备列表里，30 天未活跃后自动折叠）。

---

## 9. 错误分类与提示体系

### 9.1 L1 扫码层

| 场景 | 触发 | 提示 | 可操作 |
|---|---|---|---|
| 非 TypeBridge 二维码 | scheme 不符 | （静默继续） | — |
| 字段缺失 / PSK 非法 | `parseQr` 失败 | "二维码不完整，请在 Mac 上重新生成" | 重新扫码 |
| 相机权限被拒 | `Permission denied` | "需要相机权限扫码，请前往设置开启" | 打开设置 |
| 版本不支持 | `v != 2` | "App 与 Mac 版本不兼容，请升级" | — |

### 9.2 L2 网络层

| 场景 | 检测 | 提示 | 可操作 |
|---|---|---|---|
| 手机不在 Wi-Fi | `NetworkUtils.currentWifiIp()` 为 null | "请先连接与 Mac 相同的 Wi-Fi" | 打开 Wi-Fi 设置 |
| 网段不同 | `/24` 前缀不一致 | "手机和 Mac 不在同一网络" + 具体 IP | "仍然尝试" / "重新扫码" |
| 连接超时 | `SocketTimeoutException` | "无法连接到 Mac，可能原因：Mac 睡眠 / 已换网 / 防火墙" | 重试 / 重新扫码 |
| 连接被拒 | `ECONNREFUSED` | "Mac 上 TypeBridge 未启动" | "请在 Mac 菜单栏启动 TypeBridge" |
| DNS / mDNS 失败 | `UnknownHostException` | "找不到 Mac" | 重新扫码 |

### 9.3 L3 协议层

| 场景 | 错误码 | 提示 |
|---|---|---|
| PSK 认证失败 | `4001` | "认证失败，二维码可能已被 Mac 重置，请重新扫码" |
| 协议版本不兼容 | `4002` | "App 与 Mac 版本不兼容" |
| 二维码过期 | `4003` | "二维码已过期，请在 Mac 上点击「生成新码」" |
| 指纹不匹配 | 本地判定 | "服务器指纹不匹配，可能扫到了伪造二维码" |

### 9.4 L4 应用层

| 场景 | 错误码 | 提示 |
|---|---|---|
| 指令不存在 | `4004` | "该指令已移除，正在刷新" |
| 服务端上限 | `4005` | "该电脑已达连接上限" |
| 被 Mac 移除设备 | `4006` | "「<服务名>」已移除该设备，请重新扫码配对" |

### 9.5 诊断信息一键复制

`NeedRescan` 状态卡片里放一个"复制诊断信息"按钮，拼接：

```
TypeBridge v2 诊断
手机 IP: 192.168.1.42 (Wi-Fi: MyHome)
Mac 记录: 192.168.1.10:8765
deviceId: b7e2a9c1-...
指纹: a1b2c3d4
错误: 4001 auth failed
时间: 2024-11-01 12:34:56
```

---

## 10. 主界面（Compose）

> **实现现状（v2.1）**：下面 10.1 ~ 10.6 的代码是 v2 设计参考。实际 UI 已调整为：
>
> - 顶部 `TopAppBar`：标题 = 当前服务名称 + 连接状态；右侧只有一个三点菜单
>   （已配对设备 / 增加设备 / 快捷输入 / 历史记录 / 设置 / 断开连接）
> - 未连接（`Disconnected` / `NeedRescan`）时不显示输入区，改为已配对设备列表 + 底部「新增连接」；无设备时按钮居中
> - 移除重复的 `StatusCard`；已配对设备、历史、快捷输入、设置使用**带 AppBar 的全屏 BottomSheet**
> - 输入区为撑满剩余高度的大文本域 +「发送时按回车」开关 + 快捷输入图标 +「发送」按钮
> - 快捷输入按分组展示：组头为「折叠箭头 + 分组名 + 条目数 + 操作图标」，组内条目为左右布局（左侧两行：加密标记 + 描述 / 内容，右侧为排序与操作图标）；条目与分组名左对齐
> - 历史每条：内容最多 2 行，第二行左侧为元信息，右侧图标为 重发 / 编辑 / 复制 / 删除
> - 所有提示中的「Mac」替换为当前服务名称（无名称时用「电脑」）
>
> 详见 §0.4。

### 10.1 整体

```kotlin
@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val sendEnter by vm.sendEnter.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        StatusCard(state = state, device = vm.currentDevice.collectAsStateWithLifecycle().value,
                   onRetry = { vm.manualRetry() },
                   onRescan = { showScanner = true })

        DeviceList(devices = devices, current = vm.currentDevice.collectAsStateWithLifecycle().value,
                   onAdd = { showScanner = true },
                   onSelect = { vm.manualRetry() },
                   onRemove = { vm.removeDevice(it) })

        // v2：仅在 actions 非空时显示指令面板
        if (actions.isNotEmpty()) {
            ActionPanel(actions = actions, inputText = text,
                        onExec = { vm.execAction(it, text) })
        }

        // 输入区 + 回车开关
        InputBar(
            enabled = state is AppConnState.Connected,
            value = text, onValueChange = { text = it },
            sendEnter = sendEnter, onSendEnterChange = { vm.setSendEnter(it) },
            onSend = { if (text.isNotBlank()) { vm.send(text); text = "" } }
        )

        HistoryList(items = history,
                    onResend = { vm.resend(it) },
                    onClear = { vm.clearHistory() })
    }

    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            ScanScreen(
                onResult = { raw ->
                    showScanner = false
                    parseQr(raw).onSuccess { vm.connectWithScannedDevice(it) }
                               .onFailure { /* L1 提示 */ }
                },
                onCancel = { showScanner = false }
            )
        }
    }
}
```

### 10.2 StatusCard（含 `NeedRescan` 的 CTA）

```kotlin
@Composable
fun StatusCard(
    state: AppConnState, device: Device?,
    onRetry: () -> Unit, onRescan: () -> Unit
) {
    val (color, text, primary) = when (state) {
        is AppConnState.Connected ->
            Triple(Color(0xFF2E7D32),
                   "已连接 ${state.serverName.ifBlank { device?.ip ?: "" }}"
                        + if (state.known) "（已配对）" else "（首次配对）",
                   null)
        AppConnState.Connecting ->
            Triple(Color(0xFFF57C00), "正在连接…", null)
        is AppConnState.AutoReconnecting ->
            Triple(Color(0xFFF57C00), "重连中（第 ${state.attempt} 次）", "重试")
        AppConnState.Disconnected ->
            Triple(Color(0xFF757575), "未连接", "连接")
        is AppConnState.NeedRescan ->
            Triple(Color(0xFFC62828),
                   when (state.reason) {
                       RescanReason.AuthFailed -> "认证失败，需要重新扫码"
                       RescanReason.Expired -> "二维码已过期"
                       RescanReason.NoWifi -> "请连接 Wi-Fi 后重新扫码"
                       RescanReason.WrongSubnet -> "手机与 Mac 不在同一网络"
                       RescanReason.MacUnreachable -> "无法连接到 Mac，请重新扫码"
                       RescanReason.FingerprintMismatch -> "指纹不匹配，请重新扫码"
                       RescanReason.Manual -> "请重新扫码"
                   },
                   "重新扫码")
    }

    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Circle, null, tint = color, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, fontWeight = FontWeight.Medium,
                 modifier = Modifier.weight(1f))
            primary?.let {
                val action = if (state is AppConnState.NeedRescan) onRescan else onRetry
                TextButton(onClick = action) { Text(it) }
            }
            if (state is AppConnState.NeedRescan) {
                TextButton(onClick = { copyDiagnostics(state, device) }) {
                    Text("复制诊断")
                }
            }
        }
    }
}
```

### 10.3 InputBar（含回车开关）

```kotlin
@Composable
fun InputBar(
    enabled: Boolean, value: String, onValueChange: (String) -> Unit,
    sendEnter: Boolean, onSendEnterChange: (Boolean) -> Unit,
    onSend: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "输入要发送到 Mac 的文字…" else "未连接") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                Text("发送")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = sendEnter, onCheckedChange = onSendEnterChange,
                   enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Text("发送时按回车", style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

### 10.4 ActionPanel（敏感指令处理）

```kotlin
@Composable
fun ActionPanel(
    actions: List<ActionItem>, inputText: String, onExec: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("指令库", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(actions, key = { it.id }) { act ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(96.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onExec(act.id) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(
                            if (act.behavior == "text_enter") Icons.Default.Send
                            else Icons.Default.TextFields,
                            null, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(act.name, maxLines = 1, fontSize = 11.sp)
                    }
                    // v2：sensitive 指令永远显示打码；非敏感指令显示"使用输入文本"或固定文本前 12 字符
                    val sub = when {
                        act.sensitive -> "••••••"
                        act.text.isEmpty() -> "使用输入文本"
                        else -> act.text.take(12)
                    }
                    Text(sub, fontSize = 10.sp, maxLines = 1,
                         overflow = TextOverflow.Ellipsis,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
```

### 10.5 DeviceList（不展示 IP）

```kotlin
@Composable
fun DeviceList(
    devices: List<Device>, current: Device?,
    onAdd: () -> Unit, onSelect: (Device) -> Unit, onRemove: (Device) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("已配对设备", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text("扫码添加") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        devices.forEach { dev ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (dev.deviceId == current?.deviceId)
                        MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dev.serverName.ifBlank { "（未命名 Mac）" },
                             style = MaterialTheme.typography.bodyMedium)
                        Text("指纹 ${dev.fingerprint ?: "—"} · 首次配对 ${
                            formatDate(dev.firstSeenAt)
                        }", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onSelect(dev) }) { Text("连接") }
                    IconButton(onClick = { onRemove(dev) }) {
                        Icon(Icons.Default.Delete, "移除")
                    }
                }
            }
        }
    }
}
```

### 10.6 NeedRescanDialog

```kotlin
@Composable
fun NeedRescanDialog(
    reason: RescanReason, detail: String,
    onRescan: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要重新扫码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(headlineFor(reason))
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                Text("请走到 Mac 前，点击菜单栏 TypeBridge → 显示二维码，然后扫描。",
                     style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onRescan) { Text("重新扫码") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
    )
}

fun headlineFor(reason: RescanReason): String = when (reason) {
    RescanReason.AuthFailed -> "认证失败，可能 Mac 上二维码已重置"
    RescanReason.Expired -> "二维码已过期"
    RescanReason.NoWifi -> "手机当前未连接 Wi-Fi"
    RescanReason.WrongSubnet -> "手机与 Mac 不在同一网络"
    RescanReason.MacUnreachable -> "无法连接到 Mac"
    RescanReason.FingerprintMismatch -> "服务器指纹与二维码不一致"
    RescanReason.Manual -> "已断开连接"
}
```

---

## 11. 存储层

### 11.1 `PrefsFactory`

```kotlin
object PrefsFactory {
    private var encrypted: SharedPreferences? = null
    fun encrypted(context: Context): SharedPreferences {
        return encrypted ?: run {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "typebridge_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { encrypted = it }
        }
    }
}
```

### 11.2 数据模型

```kotlin
// model/Device.kt（v2.1 实际字段）
data class Device(
    val deviceId: String,          // hello_ack.serverId（Mac 稳定 UUID）
    val ip: String,
    val mdns: String?,
    val port: Int,
    val serverName: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val psk: ByteArray?,           // 加密存储
    val fingerprint: String?,
    val sessionToken: String?,     // 滑动续期，30 天
    val sessionKey: String?,       // base64(AES 会话密钥)，用于恢复
    val sessionId: String?,        // 确定性派生，用于 AAD
    val actionVersion: Int
)
```

> **重要**：`Device.deviceId` 存的是 `hello_ack.serverId`（Mac 端稳定 UUID），不是本机 Android 的 `deviceId`。多台 Mac 靠它区分。本机 `deviceId` 由 `DeviceIdentity.get()` 获取，`deviceName` 由 `DeviceIdentity.getDeviceName()` 获取，二者都在 `hello` 中上报。

```kotlin
// model/ActionItem.kt
data class ActionItem(
    val id: String, val name: String, val behavior: String,
    val text: String, val order: Int, val sensitive: Boolean
)

// model/HistoryItem.kt
data class HistoryItem(
    val id: String, val text: String, val sentAt: Long,
    val status: String, val enter: Boolean, val deviceName: String
)

// model/QuickInput.kt（v2.1）
data class QuickInput(
    val id: Long = 0,
    val description: String,
    val text: String,          // 加密项在内存中为遮罩预览（首尾字符 + 中间 *）
    val encrypted: Boolean,
    val enter: Boolean = false,// 发送时是否按回车
    val groupId: Long = 0,     // 0 = 未分组（展示时归入默认分组）
    val sortOrder: Int = 0     // 组内排序
)

// model/QuickInputGroup.kt（v2.1）
data class QuickInputGroup(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false  // 默认分组不可删除
)
```

### 11.3 `DeviceStore`（简化示意）

```kotlin
object DeviceStore {
    private const val KEY_LIST = "devices"
    private const val KEY_LAST = "last_device_id"
    private const val KEY_SEND_ENTER = "send_enter"

    fun list(context: Context): List<Device> { /* 反序列化 */ }
    fun save(context: Context, d: Device) { /* 覆盖 deviceId 相同项并置为最近 */ }
    fun remove(context: Context, deviceId: String) { /* 删除条目 */ }
    fun lastConnected(context: Context): Device? { /* 取 last_device_id */ }
    fun clearPsk(context: Context, deviceId: String) { /* 置 psk = null，其他字段保留 */ }
    fun getSendEnter(context: Context): Boolean = /* 默认 true */
    fun setSendEnter(context: Context, v: Boolean) { /* ... */ }
    fun updateActionVersion(context: Context, deviceId: String, version: Int) { /* ... */ }
}
```

### 11.4 `HistoryStore`

> **v2.1 现状**：历史已迁移到 Room（`data/db/HistoryEntity` + `HistoryDao` + `HistoryRepository`），文本统一 AES-GCM 密文存储；旧 `EncryptedSharedPreferences` 数据在首次启动时一次性迁入（标记 `history_room_migrated`），`HistoryStore` 仅保留用于该迁移。

- 保存最近 200 条；`EncryptedSharedPreferences` 或 SQLCipher。
- 提供 `list / save / updateStatus / clear`。
- **默认不记录"固定敏感文本"**：`text` 为用户输入框原文；`sensitive` 指令只记 `[指令] 名称`，不含内容。

---

## 12. 常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 扫了二维码没反应 | 手机和 Mac 不在同一 Wi-Fi | 用 `NeedRescan` 面板的"复制诊断"看两边 IP |
| 重连一直失败 | Mac 睡眠 / 换了 Wi-Fi / 防火墙 | 尝试唤醒 Mac，然后重新扫码 |
| `4001 auth failed` | 二维码被重新生成（旧 PSK 已作废） | 重新扫码 |
| `4003 expired` | 二维码超 5 分钟 | Mac 上点"生成新码"后扫 |
| 收到 `hello_ack` 但立刻断开 | 指纹不匹配 | 检查 Mac 端是否被重装 |
| App 后台后不断重连耗电 | 用了 `onResume` 而非 `onStart` | 用 `onStart` / `onStop` |
| 输入中文偶发丢字 | 走明文路径（不该出现） | 检查 `sessionKey` 是否成功派生 |
| 指令面板空 | Mac 端指令库为空（v2 默认） | 在 Mac 菜单栏创建指令 |

---

## 13. 与 macOS v2 的契约（一句话总结）

- 唯一信任根是**二维码里的 PSK**；
- **`deviceId` 是纯元数据**，绝不参与认证；
- **未认证的 `hello` 不允许查询设备是否存在**；
- **`sensitive=true` 的固定文本永远不下发**，执行时安卓只发 `actionId`；
- **`NeedRescan` 是唯一兜底**：任何时候连不上、认证失败、指纹不符，都收敛到"请重新扫码"。



---

# macOS 端：局域网键入桥（投字 / TypeBridge Mac）

> **版本：v2.1**（与 Android v2.1 对齐）
> Mac 端作为 **WebSocket 服务端**，通过**二维码**向安卓下发 PSK，接受加密长连接；键盘注入用 `CGEventKeyboardSetUnicodeString` 注入文本，`kVK_Return` 敲回车；**默认指令库为空**，由用户按需创建。

---


## 1. 功能清单

| 模块 | 说明 |
|---|---|
| WebSocket 服务 | FlyingFox 监听 `0.0.0.0:8765`，支持多客户端、自动 PING/PONG |
| 加密通信 | PSK 握手 / `hello_ack` / 派生会话密钥 / 全链路 AES-256-GCM |
| 键盘注入 | `CGEventKeyboardSetUnicodeString` + `kVK_Return`；剪贴板 Cmd+V 兜底 |
| 二维码配对 | 生成 `typebridge://connect?...` URI，独立面板展示 + 倒计时 |
| 网卡选择 | 枚举 IPv4 接口，过滤虚拟网卡，多候选下拉选择 |
| 网络监测 | `NWPathMonitor` 监听 IP 变化，自动刷新二维码 |
| 设备管理 | 按 `deviceId` 复用元数据（name / firstSeen），更新 lastSeen / ip；支持手动改名 |
| 指令系统 | 用户创建 / 编辑 / 删除；`sensitive` 指令固定文本不下发（**v2.1 UI 暂时隐藏此入口**） |
| 会话恢复 | `sessionToken` **30 天滑动续期**，每次恢复轮换；持久化到 `sessions.json` |
| 状态推送 | 连接变化 / 设备数变化时向所有客户端广播 `status` |
| 设备移除 | 下发加密 `4006`、断开连接、撤销会话；必须重新扫码 |
| 历史 | 记录收到的文本（最近 500 条）；`record=false`（加密快捷输入）不记录 |
| 二维码消费 | 完整握手成功后清空 PSK、隐藏二维码；关闭窗口再打开生成新码 |
| 本机名称 | 可设置并在每次连接时通过 `deviceName` 上报；Android 端显示该名称 |
| 开机启动 | `SMAppService` 登录项开关 |

---

## 2. 工程结构

```
TypeBridgeMac/
├── App/
│   └── TypeBridgeApp.swift
├── MenuBar/
│   ├── MenuBarController.swift
│   ├── MenuBarView.swift
│   └── PairingWindow.swift            # 二维码独立窗口
├── Network/
│   ├── WebSocketServer.swift          # FlyingFox 封装
│   ├── ConnectionManager.swift        # 会话、广播、重放保护
│   ├── SessionStore.swift             # sessionToken → sessionKey 缓存（30 天滑动，持久化）
│   ├── Message.swift                  # Codable 协议模型
│   └── Crypto.swift
├── Inject/
│   └── KeyboardInjector.swift
├── Scan/
│   └── QRCodeGenerator.swift
├── Net/
│   └── InterfacePicker.swift          # 网卡枚举 + NWPathMonitor
├── Storage/
│   ├── DeviceStore.swift
│   ├── HistoryStore.swift
│   ├── ActionStore.swift
│   └── PSKStore.swift                 # Keychain 存储当前有效 PSK
├── Model/
│   ├── Device.swift
│   ├── HistoryItem.swift
│   ├── ActionItem.swift
│   └── QrPayload.swift
├── Actions/
│   ├── ActionEditorView.swift
│   └── ActionDispatcher.swift
└── Resources/
    ├── AppIcon.icns
    └── MenuBarIcon.png
```

> **v2.1 实际结构补充**：新增 `Constants/MacIdentity.swift`（serverId / Mac 名称）、`App/LaunchAtLogin.swift`（SMAppService）、`Resources/AppIcon.icns`、`Resources/MenuBarIcon.png`；实际产物为 `dist/投字.app`，测试目标 `TypeBridgeCoreTests`（13 个用例）。

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
        HistoryStore.shared.append(text: text, from: ws.deviceName ?? "当前设备")
        KeyboardInjector.shared.inject(text: text, pressEnter: enter)
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
        HistoryStore.shared.append(text: "[指令] \(action.name)", from: ws.deviceName ?? "")

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
> **v2.1 补充**：收到 `text` 帧时先读取 `record`（缺省 `true`）；`record=false`（加密快捷输入）只注入文本，不调用 `append`，避免密码类内容进入 Mac 历史。

---

## 11. 菜单栏 UI

> **实现现状（v2.1）**：下面代码是 v2 参考。实际布局：
> - 菜单栏图标改为 App 图标；面板标题栏为「显示二维码配对」+「停止/启动」
> - 「指令库」按钮已移除；指令库 / 历史区块暂时隐藏（底层逻辑保留）
> - 新增「Mac 名称」行（铅笔编辑）、设备行铅笔重命名、底部「开机自动启动」开关
> - 面板高度由 SwiftUI 动态内容尺寸决定，自动贴近菜单栏
> - 设备 IP 只显示 IPv4（剥离 `::ffff:`）
> 详见 §0.5。

```swift
struct MenuBarView: View {
    @ObservedObject var server: WebSocketServer = .shared
    @ObservedObject var devices: DeviceStore = .shared
    @ObservedObject var history: HistoryStore = .shared
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

            Divider()

            // 历史
            HStack {
                Text("历史 (\(history.items.count))")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("清空") { history.items.removeAll() }.font(.caption)
            }
            ScrollView {
                LazyVStack(alignment: .leading) {
                    ForEach(history.items.prefix(20)) { item in
                        HStack(alignment: .top) {
                            Text(item.text).lineLimit(2)
                            Spacer()
                            Button("重放") { history.replay(item) }.font(.caption)
                        }
                    }
                }
            }
            .frame(maxHeight: 200)
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

## 15. 构建与验证（v2.1）

### Android

```bash
cd client-android
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 8 个加密单测
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 需要 Android SDK 36；Gradle 9 需要 JDK 17+ 运行，源码/字节码目标为 Java 11。

### macOS

```bash
cd server-macos
swift test                            # 13 个测试
./Scripts/build-app.sh                # 产物: dist/投字.app
open "dist/投字.app"
```

> 目前没有开发者证书，使用 ad-hoc 签名；重新构建后可能需要重新勾选辅助功能、重新开启开机启动。

---

## 结语

**两份文档的核心不变式**（任何一方实现时都不能破坏）：

1. **PSK 是唯一信任根，从二维码来，不从连接来。**
2. **`deviceId` 是纯元数据，不参与认证。**
3. **未认证的 `hello` 不允许探测设备是否存在。**
4. **`sensitive` 固定文本永不下发，只在 Mac 端执行时读取。**
5. **`NeedRescan` 是唯一兜底**：任何时候连不上 / 认证失败 / 指纹不符 / 被移除，都收敛到"请重新扫码"。
6. **移除设备 = 断开连接 + 撤销会话**：必须重新扫码才能再次配对。

把这 6 条钉死，双端实现就不会走偏；其余细节（UI 打磨、错误文案、动画）可以在评审阶段逐步完善。