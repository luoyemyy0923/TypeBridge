import AppKit
import ApplicationServices
import Carbon
import CoreGraphics
import Foundation

extension Notification.Name {
    /// 注入前通知菜单栏关闭 popover，让焦点回到目标 App。
    public static let typeBridgeDismissPopover = Notification.Name("typeBridgeDismissPopover")
}

/// 键盘注入器：主路径 `CGEventKeyboardSetUnicodeString`，兜底剪贴板 + Cmd+V。
@MainActor
public final class KeyboardInjector {
    public static let shared = KeyboardInjector()

    private let pasteboardRestoreDelay: UInt64 = 50_000_000
    private let chunkSize = 20

    private var preferClipboard: Bool {
        get { UserDefaults.standard.bool(forKey: "tb.preferClipboard") }
        set { UserDefaults.standard.set(newValue, forKey: "tb.preferClipboard") }
    }

    public var usesClipboardFirst: Bool {
        get { preferClipboard }
        set { preferClipboard = newValue }
    }

    private init() {}

    @discardableResult
    public func ensureAccessibility(prompt: Bool = false) -> Bool {
        if AXIsProcessTrusted() { return true }
        guard prompt else { return false }
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    /// `pressEnter=true` 时在文本末尾敲回车。
    /// `allowClipboard=false` 用于加密内容：绝不经剪贴板，只允许直接键盘注入；
    /// 若当前位置不支持注入则弹窗提示，不做任何兜底。
    @discardableResult
    public func inject(text: String, pressEnter: Bool, allowClipboard: Bool = true) async -> Bool {
        guard ensureAccessibility() else {
            TBLog.inject.error("accessibility permission missing")
            return false
        }
        guard !text.isEmpty || pressEnter else { return true }

        NotificationCenter.default.post(name: .typeBridgeDismissPopover, object: nil)
        try? await Task.sleep(nanoseconds: 30_000_000)

        if !text.isEmpty {
            if allowClipboard {
                if preferClipboard {
                    await pasteViaClipboard(text)
                } else if !(await injectViaUnicode(text)) {
                    await pasteViaClipboard(text)
                }
            } else {
                // 加密内容：安全输入模式或注入失败时不走剪贴板，直接提示
                if IsSecureEventInputEnabled() {
                    TBLog.inject.error("secure input enabled; encrypted text not injectable")
                    showSecureInjectionAlert()
                    return false
                }
                if !(await injectViaUnicode(text)) {
                    TBLog.inject.error("unicode injection failed; encrypted text not pasted")
                    showSecureInjectionAlert()
                    return false
                }
            }
        }

        if pressEnter {
            try? await Task.sleep(nanoseconds: 20_000_000)
            pressReturn()
        }
        return true
    }

    /// 加密内容无法注入时的提示：明确说明不会使用剪贴板。
    private func showSecureInjectionAlert() {
        let alert = NSAlert()
        alert.messageText = "无法注入加密内容"
        alert.informativeText = "当前光标位置不支持键盘注入（可能处于安全输入状态）。加密内容不会通过剪贴板填充，请移动光标到可输入的文本框后重试。"
        alert.alertStyle = .warning
        alert.addButton(withTitle: "好")
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    public func pressReturn() {
        let source = CGEventSource(stateID: .hidSystemState)
        let returnKey: CGKeyCode = 0x24 // kVK_Return
        guard let down = CGEvent(keyboardEventSource: source, virtualKey: returnKey, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: returnKey, keyDown: false) else {
            return
        }
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
    }

    /// 文本内部的换行使用 Shift+Return，避免在聊天 / 编辑器里被当成“发送”。
    /// 末尾的「发送时按回车」仍然使用普通 Return，明确表示提交。
    public func pressSoftReturn() {
        let source = CGEventSource(stateID: .hidSystemState)
        let returnKey: CGKeyCode = 0x24 // kVK_Return
        guard let down = CGEvent(keyboardEventSource: source, virtualKey: returnKey, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: returnKey, keyDown: false) else {
            return
        }
        down.flags = .maskShift
        up.flags = .maskShift
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
    }

    // MARK: - Unicode 主路径

    /// 返回 false 表示 CGEvent 创建失败，需要走剪贴板兜底。
    private func injectViaUnicode(_ text: String) async -> Bool {
        let source = CGEventSource(stateID: .hidSystemState)
        let utf16 = Array(text.utf16)
        var index = 0

        while index < utf16.count {
            // 换行必须单独发按键：部分 App 不识别 unicode string 里的 \n；
            // 这里用 Shift+Return 作为“软换行”，避免触发提交 / 发送。
            if utf16[index] == 0x0D, index + 1 < utf16.count, utf16[index + 1] == 0x0A {
                pressSoftReturn()
                try? await Task.sleep(nanoseconds: 8_000_000)
                index += 2
                continue
            }
            if utf16[index] == 0x0A || utf16[index] == 0x0D {
                pressSoftReturn()
                try? await Task.sleep(nanoseconds: 8_000_000)
                index += 1
                continue
            }

            var end = min(index + chunkSize, utf16.count)
            if let newline = utf16[index ..< end].firstIndex(where: { $0 == 0x0A || $0 == 0x0D }) {
                end = newline
            }
            if end <= index {
                index += 1
                continue
            }

            let chunk = Array(utf16[index ..< end])
            guard let down = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true),
                  let up = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) else {
                return false
            }
            chunk.withUnsafeBufferPointer { buffer in
                down.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: buffer.baseAddress!)
                up.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: buffer.baseAddress!)
            }
            down.post(tap: .cghidEventTap)
            up.post(tap: .cghidEventTap)

            index = end
            try? await Task.sleep(nanoseconds: 8_000_000)
        }
        return true
    }

    // MARK: - 剪贴板兜底

    private func pasteViaClipboard(_ text: String) async {
        let pasteboard = NSPasteboard.general
        let saved = pasteboard.string(forType: .string)

        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)

        sendCommandV()

        try? await Task.sleep(nanoseconds: pasteboardRestoreDelay)
        pasteboard.clearContents()
        if let saved {
            pasteboard.setString(saved, forType: .string)
        }
    }

    private func sendCommandV() {
        let source = CGEventSource(stateID: .hidSystemState)
        let vKey: CGKeyCode = 0x09 // kVK_ANSI_V
        guard let down = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: false) else {
            return
        }
        down.flags = .maskCommand
        up.flags = .maskCommand
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
    }
}
