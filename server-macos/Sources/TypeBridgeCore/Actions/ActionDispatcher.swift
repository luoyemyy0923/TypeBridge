import Foundation

@MainActor
public final class ActionDispatcher {
    public static let shared = ActionDispatcher()

    private init() {}

    /// 固定文本优先；`liveText` 仅在指令 `sensitive=false` 且 `text` 为空时使用。
    public func dispatch(_ action: ActionItem, liveText: String = "") async {
        let payload = action.text.isEmpty ? liveText : action.text
        guard !payload.isEmpty else { return }
        await KeyboardInjector.shared.inject(
            text: payload,
            pressEnter: action.behavior == .textEnter
        )
    }
}
