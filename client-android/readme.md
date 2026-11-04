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
- `text` 帧新增可选 `record`（默认 `true`）：`record=false` 表示 Android 不写入本地历史（用于加密快捷输入）
- `text` 帧新增可选 `secure`（默认 `false`）：`secure=true` 表示加密内容，macOS **禁止剪贴板兜底**，只允许直接键盘注入；无法注入时弹窗提示，不留下任何剪贴板痕迹
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

- AppBar：标题显示当前服务名称 + 状态；标题可点击打开下拉框，列出全部已配对服务端（当前连接带对勾），选中立即切换并重连
- AppBar 右侧为「历史记录」图标 + 三点菜单：已配对设备 / 增加设备 / 快捷输入 / 设置 / 断开连接
- 所有 BottomSheet 不带右上角关闭叉号，下滑或点击遮罩关闭
- 未连接（`Disconnected` / `NeedRescan`）：隐藏指令面板与输入区，只显示已配对设备列表与底部「新增连接」按钮；没有已配对设备时提示与按钮居中
- 连接中 / 重连中直接展示输入主页（输入框禁用，占位「正在连接…」），避免配对成功后再闪一下切换；冷启动有凭据时直接进入 `Connecting`
- 「已配对设备」中，当前连接设备带绿点「已连接」且不显示重连按钮
- 输入区：大文本域撑满剩余高度；底部为「发送时按回车」开关、快捷输入图标（闪电）+「发送」按钮
- 快捷输入：三点菜单进入管理页（分组增删改 / 条目增删改 / 分组与条目排序 / 默认分组「整理数据」）；输入栏闪电图标直接弹出**仅发送**的 BottomSheet；分组可展开折叠，状态持久化
- 历史：带 AppBar 的全屏 BottomSheet；条目间为 0.5dp 分隔线；内容最多 2 行，第二行左侧元信息、右侧图标（36dp 按钮 + 18dp 图标）为 重发 / 编辑 / 复制 / 删除；重发不新增历史，只更新原记录状态
- 历史多选删除：AppBar「多选」进入选择态，AppBar 切换为「取消 / 删除」，每项出现复选框；未选中时「删除」不可点
- 历史清空需二次确认（「确定清空全部 N 条…」）；清空按钮为图标
- 设置：带 AppBar 的全屏 BottomSheet，可修改本机设备名称；保存后立即重连一次同步到 Mac
- Mac 移除设备时下发 `4006`：Android 立即断开并**删除本地该设备的配对记录**（已无法重连），弹窗提示重新扫码；标题与提示保留最后连接的服务名称
- 提示文案中的「Mac」全部替换为当前服务名称（无名称时显示「电脑」）

### 0.5 macOS 最终 UI / 行为

- 菜单栏图标使用 App 图标；面板标题栏为「显示二维码配对」+「停止/启动」
- 「指令库」按钮已移除；历史记录功能已移除（启动时清理旧的明文历史），指令库区块暂时隐藏（底层逻辑保留）
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
- 加密项：内容与「是否加密」不可修改；描述、发送时回车、所属分组可正常修改（只更新元信息，绝不写回文本），编辑弹窗只展示这三项；如需改内容只能删除后重新添加
- 明文快捷输入：正常回显、编辑、删除；编辑弹窗含「描述 / 文本 / 分组 / 加密保存 / 发送时按回车」；只填描述时自动把描述同步为内容，列表只显示一行
- 发送：快捷输入使用自身的 `enter` 开关（不跟随主页全局开关）；加密快捷输入以 `record=false` + `secure=true` 发送：不写历史，且 Mac 端禁用剪贴板注入

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
| 历史 | Room 本地加密持久化，可重发 / 复制 / 删除单条 / 多选批量删除；清空需二次确认 |
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
  "record": true,                        // false = Android 不写本地历史（加密快捷输入）
  "secure": false,                       // true = 加密内容，macOS 禁用剪贴板兜底
  "ts": 1700000000000
}
```

> v2 把"是否回车"从"选哪条指令"改为 `enter` 布尔字段，与安卓端"发送时回车"开关一一对应。Mac 端按 `enter` 决定是否 `pressReturn()`。
> v2.1 新增 `record`（缺省 `true`）与 `secure`（缺省 `false`）。加密快捷输入会同时发 `record=false, secure=true`：Android 不落历史，Mac 不允许剪贴板注入。

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
> - 顶部 `TopAppBar`：标题 = 当前服务名称 + 连接状态；点击标题可下拉切换已配对服务端（右下角箭头，当前项带对勾）
> - AppBar 右侧为「历史记录」图标 + 三点菜单（已配对设备 / 增加设备 / 快捷输入 / 设置 / 断开连接）
> - 所有 BottomSheet 不带右上角关闭叉号，下滑或点击遮罩关闭
> - 未连接（`Disconnected` / `NeedRescan`）时不显示输入区，改为已配对设备列表 + 底部「新增连接」；无设备时按钮居中
> - 移除重复的 `StatusCard`；已配对设备、历史、快捷输入、设置使用**带 AppBar 的全屏 BottomSheet**
> - 输入区为撑满剩余高度的大文本域 +「发送时按回车」开关 + 快捷输入图标 +「发送」按钮
> - 快捷输入按分组展示：组头为「折叠箭头 + 分组名 + 条目数 + 操作图标」，组内条目为左右布局（左侧两行：加密标记 + 描述 / 内容，右侧为排序与操作图标）；条目与分组名左对齐
> - 历史每条：内容最多 2 行，条目间 0.5dp 分隔线，第二行左侧为元信息，右侧 36dp/18dp 图标为 重发 / 编辑 / 复制 / 删除；支持「多选」批量删除与清空二次确认
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

## 构建与验证（v2.1）

```bash
cd client-android
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 8 个加密单测
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 需要 Android SDK 36；Gradle 9 需要 JDK 17+ 运行，源码/字节码目标为 Java 11。
