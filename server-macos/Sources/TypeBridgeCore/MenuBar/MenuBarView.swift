import AppKit
import SwiftUI

public struct MenuBarView: View {
    @ObservedObject private var server = WebSocketServer.shared
    @ObservedObject private var devices = DeviceStore.shared
    @ObservedObject private var history = HistoryStore.shared
    @ObservedObject private var actions = ActionStore.shared

    @State private var preferClipboard = UserDefaults.standard.bool(forKey: "tb.preferClipboard")
    @State private var editingDeviceId: String?
    @State private var editingName: String = ""
    @State private var editingServerName = false
    @State private var serverNameDraft = ""
    @State private var launchAtLoginError: String?

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            Divider()
            nameSection
            Divider()
            deviceSection
            Divider()
            // 暂时隐藏指令库与历史记录两个区块；底层数据与功能仍然保留
            // actionSection
            // Divider()
            // historySection
            // Divider()
            footer
        }
        .padding(14)
        .frame(width: 380)
        .alert("无法设置开机启动", isPresented: Binding(
            get: { launchAtLoginError != nil },
            set: { if !$0 { launchAtLoginError = nil } }
        )) {
            Button("好", role: .cancel) { launchAtLoginError = nil }
        } message: {
            Text(launchAtLoginError ?? "")
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
            Text(statusText)
                .font(.headline)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer()
            Button {
                PairingWindowController.shared.show()
            } label: {
                Label("显示二维码配对", systemImage: "qrcode")
            }
            Button(server.isRunning ? "停止" : "启动") {
                Task {
                    if server.isRunning || server.state == .starting {
                        await server.stop()
                    } else {
                        await server.start()
                    }
                }
            }
            .disabled(server.state == .starting)
        }
    }

    private var statusColor: Color {
        switch server.state {
        case .online: return .green
        case .starting: return .orange
        case .stopped: return .gray
        case .failed: return .red
        }
    }

    private var statusText: String {
        switch server.state {
        case .online:
            return "服务在线 · \(server.connectionCount) 台设备"
        case .starting:
            return "正在启动…"
        case .stopped:
            return "服务已停止"
        case let .failed(message):
            return "服务异常：\(message)"
        }
    }

    // MARK: - Devices

    private var deviceSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("已配对 (\(devices.active.count))")
                .font(.caption)
                .foregroundStyle(.secondary)

            if devices.active.isEmpty {
                Text("还没有配对设备，点上方显示二维码")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(devices.active) { device in
                            if editingDeviceId == device.id {
                                HStack(spacing: 6) {
                                    Circle()
                                        .fill(device.online ? Color.green : Color.secondary.opacity(0.4))
                                        .frame(width: 6, height: 6)
                                    TextField("设备名称", text: $editingName)
                                        .textFieldStyle(.roundedBorder)
                                        .frame(width: 150)
                                        .onSubmit { saveRename(device.id) }
                                    Button {
                                        saveRename(device.id)
                                    } label: {
                                        Image(systemName: "checkmark")
                                    }
                                    .buttonStyle(.borderless)
                                    .help("保存名称")

                                    Button {
                                        editingDeviceId = nil
                                    } label: {
                                        Image(systemName: "xmark")
                                    }
                                    .buttonStyle(.borderless)
                                    .help("取消")
                                    Spacer()
                                }
                            } else {
                                HStack(spacing: 6) {
                                    Circle()
                                        .fill(device.online ? Color.green : Color.secondary.opacity(0.4))
                                        .frame(width: 6, height: 6)
                                    Text(device.name)
                                        .font(.callout)
                                        .lineLimit(1)
                                    Text(device.ip.ipv4Normalized)
                                        .font(.caption2)
                                        .foregroundStyle(.tertiary)
                                    Spacer()
                                    Button {
                                        editingDeviceId = device.id
                                        editingName = device.name
                                    } label: {
                                        Image(systemName: "pencil")
                                    }
                                    .buttonStyle(.borderless)
                                    .help("重命名设备")

                                    Button {
                                        WebSocketServer.shared.disconnectDevice(device.id)
                                        devices.remove(device.id)
                                    } label: {
                                        Image(systemName: "minus.circle")
                                    }
                                    .buttonStyle(.borderless)
                                    .help("移除设备并断开连接（需要重新扫码才能再次配对）")
                                }
                            }
                        }
                    }
                }
                .frame(maxHeight: CGFloat(min(devices.active.count, 5)) * 30)

                Text("点铅笔可手动改名；未手动改名时会跟随安卓端上报的名称")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
    }

    private func saveRename(_ deviceId: String) {
        devices.rename(deviceId, to: editingName)
        editingDeviceId = nil
        editingName = ""
    }

    // MARK: - Mac name

    /// 修改本机在 Android 端显示的名称；保存后主动断开客户端，让它们重连拿到新 hello_ack。
    private var nameSection: some View {
        HStack(spacing: 6) {
            Text("Mac 名称")
                .font(.caption)
                .foregroundStyle(.secondary)

            if editingServerName {
                TextField("电脑名称", text: $serverNameDraft)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 150)
                    .onSubmit { saveServerName() }
                Button {
                    saveServerName()
                } label: {
                    Image(systemName: "checkmark")
                }
                .buttonStyle(.borderless)
                .help("保存名称")
                Button {
                    editingServerName = false
                } label: {
                    Image(systemName: "xmark")
                }
                .buttonStyle(.borderless)
                .help("取消")
            } else {
                Text(MacIdentity.serverName)
                    .font(.callout)
                    .lineLimit(1)
                Spacer()
                Button {
                    serverNameDraft = MacIdentity.serverName
                    editingServerName = true
                } label: {
                    Image(systemName: "pencil")
                }
                .buttonStyle(.borderless)
                .help("修改 Mac 名称（Android 端会显示这个名称）")
            }
        }
    }

    private func saveServerName() {
        MacIdentity.setServerName(serverNameDraft)
        editingServerName = false
        WebSocketServer.shared.refreshClients()
    }

    // MARK: - Actions

    private var actionSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("指令库 (\(actions.actions.count)) · v\(actions.version)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("编辑") {
                    ActionEditorWindowController.shared.show()
                }
                .font(.caption)
            }

            if actions.actions.isEmpty {
                Button {
                    ActionEditorWindowController.shared.show()
                } label: {
                    Text("还没有自定义指令，点这里创建")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(actions.actions.sorted { $0.order < $1.order }) { action in
                            Button {
                                Task { await ActionDispatcher.shared.dispatch(action) }
                            } label: {
                                VStack(spacing: 2) {
                                    Image(systemName: action.behavior == .textEnter
                                        ? "arrow.turn.down.left"
                                        : "text.alignleft")
                                    Text(action.name.isEmpty ? "未命名" : action.name)
                                        .font(.caption)
                                        .lineLimit(1)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    // MARK: - History

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("历史 (\(history.items.count))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("清空") { history.clear() }
                    .font(.caption)
                    .disabled(history.items.isEmpty)
            }

            if history.items.isEmpty {
                Text("还没有发送记录")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(history.items.prefix(20)) { item in
                            HStack(alignment: .top) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.text)
                                        .font(.callout)
                                        .lineLimit(2)
                                    Text("\(item.fromDevice) · \(item.receivedAt.formatted(date: .omitted, time: .shortened))")
                                        .font(.caption2)
                                        .foregroundStyle(.tertiary)
                                }
                                Spacer()
                                Button("重放") {
                                    Task {
                                        await KeyboardInjector.shared.inject(text: item.text, pressEnter: false)
                                    }
                                }
                                .font(.caption)
                            }
                        }
                    }
                }
                .frame(maxHeight: min(CGFloat(history.items.count), 5) * 48)
            }
        }
    }

    // MARK: - Footer

    private var footer: some View {
        VStack(alignment: .leading, spacing: 4) {
            Toggle("开机自动启动", isOn: Binding(
                get: { LaunchAtLogin.isEnabled },
                set: { newValue in
                    if let error = LaunchAtLogin.setEnabled(newValue) {
                        launchAtLoginError = error
                    }
                }
            ))
            .font(.caption)

            HStack {
                Toggle("优先使用剪贴板注入", isOn: $preferClipboard)
                    .font(.caption)
                    .onChange(of: preferClipboard) { newValue in
                        KeyboardInjector.shared.usesClipboardFirst = newValue
                    }
                Spacer()
                Button("退出") {
                    NSApp.terminate(nil)
                }
                .font(.caption)
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .keyboardShortcut("q", modifiers: .command)
                .help("退出 TypeBridge（⌘Q）")
            }
            Text("适用于 VS Code / 微信等对 unicode 注入不生效的 App。")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
    }
}
