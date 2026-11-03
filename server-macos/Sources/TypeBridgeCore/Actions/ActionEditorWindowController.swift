import AppKit
import SwiftUI

@MainActor
public final class ActionEditorWindowController {
    public static let shared = ActionEditorWindowController()

    private var window: NSWindow?

    private init() {}

    public func show() {
        if window == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 560, height: 600),
                styleMask: [.titled, .closable, .resizable],
                backing: .buffered,
                defer: false
            )
            window.title = "指令库"
            window.isReleasedWhenClosed = false
            window.contentView = NSHostingView(rootView: ActionEditorView())
            window.center()
            self.window = window
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
}
