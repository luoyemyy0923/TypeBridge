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

        // 历史记录功能已移除：清掉旧版本留下的明文历史
        UserDefaults.standard.removeObject(forKey: "tb.history")

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
