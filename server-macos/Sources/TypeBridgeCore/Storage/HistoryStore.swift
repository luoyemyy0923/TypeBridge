import Combine
import Foundation

/// 最近 500 条接收历史，本地 UserDefaults 持久化。
@MainActor
public final class HistoryStore: ObservableObject {
    public static let shared = HistoryStore()

    @Published public private(set) var items: [HistoryItem] = []

    private let key = "tb.history"
    private let limit = 500

    private init() {
        restore()
    }

    public func append(text: String, from device: String) {
        items.insert(HistoryItem(text: text, fromDevice: device), at: 0)
        if items.count > limit {
            items.removeLast(items.count - limit)
        }
        save()
    }

    public func clear() {
        items.removeAll()
        save()
    }

    private func restore() {
        guard let data = UserDefaults.standard.data(forKey: key),
              let list = try? JSONDecoder().decode([HistoryItem].self, from: data) else {
            return
        }
        items = list
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
