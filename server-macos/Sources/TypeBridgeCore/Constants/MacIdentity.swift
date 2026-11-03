import Foundation

/// Mac 端稳定标识与本机显示名称。
/// `serverName` 会在 hello_ack 里下发，Android 用它显示 Mac 的名称。
public enum MacIdentity {
    private static let serverIdKey = "tb.serverId"
    private static let serverNameKey = "tb.serverName"

    public static let serverId: String = {
        if let existing = UserDefaults.standard.string(forKey: serverIdKey), !existing.isEmpty {
            return existing
        }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: serverIdKey)
        return id
    }()

    private static let defaultServerName: String = {
        Host.current().localizedName ?? ProcessInfo.processInfo.hostName
    }()

    /// 用户自定义名称优先，否则回退到系统电脑名。
    public static var serverName: String {
        let saved = UserDefaults.standard
            .string(forKey: serverNameKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let saved, !saved.isEmpty {
            return saved
        }
        return defaultServerName
    }

    /// 传入空字符串表示恢复为系统电脑名。
    public static func setServerName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            UserDefaults.standard.removeObject(forKey: serverNameKey)
        } else {
            UserDefaults.standard.set(trimmed, forKey: serverNameKey)
        }
    }
}
