import CryptoKit
import Foundation

/// 一条已建立的加密会话。
/// 计数值按方向独立维护：`outCounter` 用于服务端 → 客户端，`inCounter` 用于客户端 → 服务端。
/// AAD 中同时包含 sessionId 与 counter，因此重放的旧帧会因 AAD 不匹配而解密失败。
public final class Session {
    public let id: String
    public let key: SymmetricKey
    public let token: String
    public let createdAt: Date

    private let lock = NSLock()
    private var outCounter: UInt64 = 0
    private var inCounter: UInt64 = 0

    public init(id: String, key: SymmetricKey, token: String, createdAt: Date = Date()) {
        self.id = id
        self.key = key
        self.token = token
        self.createdAt = createdAt
    }

    public func encrypt(_ plain: Data) throws -> Data {
        lock.lock()
        defer { lock.unlock() }
        let aad = Crypto.aad(sessionId: id, counter: outCounter)
        let ciphertext = try Crypto.encrypt(plain, key: key, aad: aad)
        outCounter += 1
        return ciphertext
    }

    public func decrypt(_ data: Data) throws -> Data {
        lock.lock()
        defer { lock.unlock() }
        let aad = Crypto.aad(sessionId: id, counter: inCounter)
        let plain = try Crypto.decrypt(key: key, data: data, aad: aad)
        inCounter += 1
        return plain
    }
}

public struct SessionRecord: Sendable {
    public let token: String
    public let sessionId: String
    public let key: SymmetricKey
    public let deviceId: String
    public let fingerprint: String
    public let createdAt: Date

    public var isExpired: Bool {
        Date().timeIntervalSince(createdAt) > TB.sessionTTL
    }
}

/// 服务端 `sessionToken → 会话密钥` 缓存，滑动过期，LRU 淘汰最近未使用的记录。
/// 记录写入 Application Support（0600 权限），不依赖代码签名，
/// 因此 App 重新构建、换路径或 Mac 重启后都能继续恢复会话。
public final class SessionStore: @unchecked Sendable {
    public static let shared = SessionStore()

    private struct StoredSession: Codable {
        let token: String
        let sessionId: String
        let key: Data
        let deviceId: String
        let fingerprint: String
        let createdAt: Date
    }

    private var records: [String: SessionRecord] = [:]
    private var order: [String] = []
    private let lock = NSLock()
    private let capacity = 32

    private init() {
        restore()
    }

    public func put(_ record: SessionRecord) {
        lock.lock()
        records[record.token] = record
        order.removeAll { $0 == record.token }
        order.append(record.token)
        while order.count > capacity {
            let evicted = order.removeFirst()
            records.removeValue(forKey: evicted)
        }
        persistLocked()
        lock.unlock()
    }

    public func lookup(token: String) -> SessionRecord? {
        lock.lock()
        defer { lock.unlock() }
        guard let record = records[token] else { return nil }
        guard !record.isExpired else {
            records.removeValue(forKey: token)
            order.removeAll { $0 == token }
            persistLocked()
            return nil
        }
        order.removeAll { $0 == token }
        order.append(token)
        return record
    }

    public func removeAll() {
        lock.lock()
        records.removeAll()
        order.removeAll()
        deletePersisted()
        lock.unlock()
    }

    /// 清理同一设备的历史 token，只保留指定 token（避免频繁重连把 32 个槽位占满）。
    public func prune(deviceId: String, keeping tokens: Set<String>) {
        lock.lock()
        let removeTokens = records.values
            .filter { $0.deviceId == deviceId && !tokens.contains($0.token) }
            .map(\.token)
        for token in removeTokens {
            records.removeValue(forKey: token)
            order.removeAll { $0 == token }
        }
        if !removeTokens.isEmpty {
            persistLocked()
        }
        lock.unlock()
    }

    // MARK: - Persistence

    private func restore() {
        guard let data = loadPersisted(),
              let stored = try? JSONDecoder().decode([StoredSession].self, from: data) else {
            return
        }

        let valid = stored.filter { Date().timeIntervalSince($0.createdAt) <= TB.sessionTTL }
        for item in valid {
            records[item.token] = SessionRecord(
                token: item.token,
                sessionId: item.sessionId,
                key: SymmetricKey(data: item.key),
                deviceId: item.deviceId,
                fingerprint: item.fingerprint,
                createdAt: item.createdAt
            )
        }
        order = valid.sorted { $0.createdAt < $1.createdAt }.map(\.token)

        if valid.count != stored.count {
            persistLocked()
        }
        if !valid.isEmpty {
            TBLog.server.log("restored \(valid.count, privacy: .public) persisted session(s)")
        }
    }

    private func persistLocked() {
        let stored: [StoredSession] = order.compactMap { token in
            guard let record = records[token] else { return nil }
            return StoredSession(
                token: record.token,
                sessionId: record.sessionId,
                key: record.key.withUnsafeBytes { Data($0) },
                deviceId: record.deviceId,
                fingerprint: record.fingerprint,
                createdAt: record.createdAt
            )
        }
        guard let data = try? JSONEncoder().encode(stored) else { return }
        savePersisted(data)
    }

    private var storageURL: URL {
        let directory = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TypeBridge", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory.appendingPathComponent("sessions.json")
    }

    private func loadPersisted() -> Data? {
        try? Data(contentsOf: storageURL)
    }

    private func savePersisted(_ data: Data) {
        let url = storageURL
        do {
            try data.write(to: url, options: [.atomic])
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o600],
                ofItemAtPath: url.path
            )
        } catch {
            TBLog.server.error("persist sessions failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func deletePersisted() {
        try? FileManager.default.removeItem(at: storageURL)
    }
}
