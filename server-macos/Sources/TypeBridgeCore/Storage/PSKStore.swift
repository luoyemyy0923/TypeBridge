import Foundation
import Security

/// 当前有效 PSK 的 Keychain 存储（同一时刻只有一个有效 PSK）。
public final class PSKStore: @unchecked Sendable {
    public static let shared = PSKStore()

    private let service = "com.typebridge.mac.psk"
    private let account = "current"
    private let lock = NSLock()

    private init() {}

    public var current: Data? {
        lock.lock()
        defer { lock.unlock() }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              data.count == 32 else {
            return nil
        }
        return data
    }

    public func setCurrent(_ psk: Data) {
        lock.lock()
        defer { lock.unlock() }
        removeLocked()
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: psk
        ]
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status != errSecSuccess {
            TBLog.server.error("keychain add psk failed: \(status)")
        } else {
            TBLog.server.log("psk rotated")
        }
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        removeLocked()
    }

    private func removeLocked() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
