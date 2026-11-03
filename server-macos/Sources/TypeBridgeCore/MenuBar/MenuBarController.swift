import AppKit
import SwiftUI

@MainActor
public final class MenuBarController: NSObject {
    public static let shared = MenuBarController()

    private var statusItem: NSStatusItem?
    private let popover = NSPopover()
    private var dismissObserver: NSObjectProtocol?

    private override init() {
        super.init()
    }

    public func install() {
        guard statusItem == nil else { return }

        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = item.button {
            if let url = Bundle.main.url(forResource: "MenuBarIcon", withExtension: "png"),
               let icon = NSImage(contentsOf: url) {
                icon.size = NSSize(width: 20, height: 20)
                button.image = icon
            } else {
                let fallback = NSImage(
                    systemSymbolName: "keyboard",
                    accessibilityDescription: "投字"
                )
                fallback?.isTemplate = true
                button.image = fallback
            }
            button.target = self
            button.action = #selector(togglePopover(_:))
            button.sendAction(on: [.leftMouseUp])
        }
        statusItem = item

        popover.behavior = .transient
        let hosting = NSHostingController(rootView: MenuBarView())
        hosting.sizingOptions = [.preferredContentSize]
        popover.contentViewController = hosting

        dismissObserver = NotificationCenter.default.addObserver(
            forName: .typeBridgeDismissPopover,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.dismissForInjection()
            }
        }
    }

    @objc private func togglePopover(_ sender: Any?) {
        if popover.isShown {
            popover.performClose(nil)
            return
        }
        guard let button = statusItem?.button else { return }

        NSApp.activate(ignoringOtherApps: true)
        if let window = button.window, let contentView = window.contentView {
            let rect = button.convert(button.bounds, to: contentView)
            popover.show(relativeTo: rect, of: contentView, preferredEdge: .minY)
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        }
    }

    /// 注入前关闭 popover 并把焦点交还原 App，否则按键会打到 popover 里的按钮。
    private func dismissForInjection() {
        guard popover.isShown else { return }
        popover.performClose(nil)
        NSApp.hide(nil)
    }

    deinit {
        if let dismissObserver {
            NotificationCenter.default.removeObserver(dismissObserver)
        }
    }
}
