import Combine
import Foundation

/// 指令库。v2 默认空库，由用户按需创建。
@MainActor
public final class ActionStore: ObservableObject {
    public static let shared = ActionStore()

    @Published public private(set) var actions: [ActionItem] = []
    @Published public private(set) var version: Int = 0

    /// 指令变化后需要通知所有已连接设备刷新指令面板。
    public var onChange: (() -> Void)?

    private let actionsKey = "tb.actions"
    private let versionKey = "tb.action.version"

    private init() {
        restore()
    }

    public func snapshot() -> (version: Int, actions: [ActionItem]) {
        (version, actions.sorted { $0.order < $1.order })
    }

    public func action(id: String) -> ActionItem? {
        actions.first { $0.id == id }
    }

    public func newID() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)).lowercased()
    }

    public func nextOrder() -> Int {
        (actions.map(\.order).max() ?? -1) + 1
    }

    /// 新增或更新一条指令，version 自增并广播。
    public func upsert(_ action: ActionItem) {
        if let idx = actions.firstIndex(where: { $0.id == action.id }) {
            actions[idx] = action
        } else {
            actions.append(action)
        }
        actions.sort { $0.order < $1.order }
        bumpVersion()
        save()
        onChange?()
    }

    public func delete(_ id: String) {
        guard actions.contains(where: { $0.id == id }) else { return }
        actions.removeAll { $0.id == id }
        bumpVersion()
        save()
        onChange?()
    }

    private func bumpVersion() {
        version += 1
        UserDefaults.standard.set(version, forKey: versionKey)
    }

    private func restore() {
        version = UserDefaults.standard.integer(forKey: versionKey)
        guard let data = UserDefaults.standard.data(forKey: actionsKey),
              let list = try? JSONDecoder().decode([ActionItem].self, from: data) else {
            return
        }
        actions = list.sorted { $0.order < $1.order }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(actions) else { return }
        UserDefaults.standard.set(data, forKey: actionsKey)
    }
}
