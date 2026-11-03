import AppKit
import Foundation

public final class AppDelegate: NSObject, NSApplicationDelegate {
    public override init() {
        super.init()
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        // 首次启动时提示辅助功能权限；未授权时注入会静默失败
        KeyboardInjector.shared.ensureAccessibility(prompt: true)

        MenuBarController.shared.install()

        ActionStore.shared.onChange = {
            WebSocketServer.shared.broadcastActions()
        }

        NetworkMonitor.shared.onIPChanged = { _ in
            PairingWindowController.shared.networkDidChange(ip: nil)
        }
        NetworkMonitor.shared.start()

        Task {
            await WebSocketServer.shared.start()
        }
    }

    public func applicationWillTerminate(_ notification: Notification) {
        BonjourRegistrar.shared.stop()
        Task {
            await WebSocketServer.shared.stop()
        }
    }
}
