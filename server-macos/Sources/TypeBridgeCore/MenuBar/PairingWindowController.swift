import AppKit
import Combine
import SwiftUI

/// 二维码独立窗口（菜单栏 popover 太窄，放不下二维码）。
@MainActor
public final class PairingWindowController: ObservableObject {
    public static let shared = PairingWindowController()

    @Published public private(set) var payload: QrPayload?
    @Published public private(set) var paired: Bool = false
    @Published public private(set) var secondsRemaining: Int = 0
    @Published public private(set) var interfaces: [LANInterface] = []
    @Published public var selectedInterfaceID: String?
    @Published public private(set) var message: String?

    private var window: NSWindow?
    private var timer: Timer?
    private var expiresAt: Date?

    private init() {}

    public func show() {
        if window == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 380, height: 640),
                styleMask: [.titled, .closable],
                backing: .buffered,
                defer: false
            )
            window.title = "配对安卓设备"
            window.isReleasedWhenClosed = false
            window.contentView = NSHostingView(rootView: PairingContentView(controller: self))
            window.center()
            self.window = window
        }
        let wasVisible = window?.isVisible == true
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        // 关闭后再次打开时生成新二维码；窗口已打开则只置前，不打断当前二维码
        if !wasVisible {
            regenerate()
        }
    }

    /// 二维码被成功使用：隐藏二维码，清空本次 PSK，并提示已连接。
    public func markPaired() {
        PSKStore.shared.clear()
        payload = nil
        paired = true
        message = "已连接"
        stopCountdown()
        TBLog.ui.log("pairing code consumed; waiting for manual regeneration")
    }

    /// 生成新码：换 IP / PSK，旧 PSK 立即失效。
    public func regenerate(with interface: LANInterface? = nil) {
        paired = false
        interfaces = InterfacePicker.candidates()

        let chosen: LANInterface? = interface
            ?? interfaces.first { $0.id == selectedInterfaceID }
            ?? interfaces.first

        guard let chosen else {
            payload = nil
            selectedInterfaceID = nil
            message = "没有可用的局域网 IPv4 网卡"
            stopCountdown()
            return
        }

        selectedInterfaceID = chosen.id
        message = nil

        let psk = Crypto.randomData(32)
        PSKStore.shared.setCurrent(psk)
        let fingerprint = Crypto.fingerprint(psk)
        let mdns = BonjourRegistrar.shared.ensureRegistered(port: Int32(TB.port))
        let expires = Date().addingTimeInterval(TB.qrTTL)

        payload = QrPayload(
            ip: chosen.ip,
            mdns: mdns,
            port: TB.port,
            psk: psk,
            fingerprint: fingerprint,
            expiresAt: expires
        )
        expiresAt = expires
        startCountdown()
        TBLog.ui.log("pairing code regenerated for \(chosen.ip, privacy: .public)")
    }

    public func selectInterface(id: String?) {
        guard let id,
              let chosen = interfaces.first(where: { $0.id == id }),
              chosen.ip != payload?.ip else {
            return
        }
        regenerate(with: chosen)
    }

    /// 网络变化后旧 IP 不可达 -> 同时换 IP 与 PSK。
    public func networkDidChange(ip: String?) {
        guard window?.isVisible == true, payload != nil else { return }
        regenerate()
    }

    public func copyURI() {
        guard let payload else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(payload.uriString, forType: .string)
    }

    // MARK: - Countdown

    private func startCountdown() {
        timer?.invalidate()
        updateCountdown()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.updateCountdown() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func stopCountdown() {
        timer?.invalidate()
        timer = nil
        secondsRemaining = 0
        expiresAt = nil
    }

    private func updateCountdown() {
        guard let expiresAt else {
            secondsRemaining = 0
            return
        }
        let remaining = Int(expiresAt.timeIntervalSinceNow.rounded())
        secondsRemaining = max(remaining, 0)
        if remaining <= 0 {
            PSKStore.shared.clear()
            payload = nil
            message = "二维码已过期，请重新生成"
            stopCountdown()
        }
    }
}
