import Foundation

public extension String {
    /// FlyingFox 双栈监听时可能返回 IPv4-mapped IPv6（::ffff:192.168.1.10），
    /// 展示和存储时统一还原成 IPv4。
    var ipv4Normalized: String {
        let prefix = "::ffff:"
        guard hasPrefix(prefix) else { return self }
        return String(dropFirst(prefix.count))
    }
}

public struct Device: Identifiable, Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var ip: String
    public var firstSeen: Date
    public var lastSeen: Date
    public var online: Bool
    /// Mac 端手动改过名后置 true，后续连接不再被 Android 上报的名称覆盖
    public var nameOverridden: Bool?

    public var hasCustomName: Bool { nameOverridden == true }

    public init(
        id: String,
        name: String,
        ip: String,
        firstSeen: Date,
        lastSeen: Date,
        online: Bool,
        nameOverridden: Bool? = nil
    ) {
        self.id = id
        self.name = name
        self.ip = ip
        self.firstSeen = firstSeen
        self.lastSeen = lastSeen
        self.online = online
        self.nameOverridden = nameOverridden
    }
}

public enum ActionBehavior: String, Codable, CaseIterable, Identifiable, Sendable {
    case text
    case textEnter = "text_enter"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .text: return "纯文本"
        case .textEnter: return "文本 + 回车"
        }
    }
}

public struct ActionItem: Identifiable, Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var behavior: ActionBehavior
    public var text: String
    public var order: Int
    public var sensitive: Bool

    public init(id: String, name: String, behavior: ActionBehavior, text: String, order: Int, sensitive: Bool = false) {
        self.id = id
        self.name = name
        self.behavior = behavior
        self.text = text
        self.order = order
        self.sensitive = sensitive
    }
}

public struct LANInterface: Identifiable, Hashable, Sendable {
    public let name: String
    public let ip: String

    public var id: String { "\(name)@\(ip)" }

    public init(name: String, ip: String) {
        self.name = name
        self.ip = ip
    }
}

/// 二维码载荷，对应 `typebridge://connect?...`。
public struct QrPayload: Sendable {
    public let ip: String
    public let mdns: String?
    public let port: UInt16
    public let psk: Data
    public let fingerprint: String
    public let expiresAt: Date

    public init(ip: String, mdns: String?, port: UInt16, psk: Data, fingerprint: String, expiresAt: Date) {
        self.ip = ip
        self.mdns = mdns
        self.port = port
        self.psk = psk
        self.fingerprint = fingerprint
        self.expiresAt = expiresAt
    }

    public var uriString: String {
        var components = URLComponents()
        components.scheme = "typebridge"
        components.host = "connect"
        var items: [URLQueryItem] = [
            URLQueryItem(name: "v", value: String(TB.protocolVersion)),
            URLQueryItem(name: "ip", value: ip)
        ]
        if let mdns, !mdns.isEmpty {
            items.append(URLQueryItem(name: "mdns", value: mdns))
        }
        items.append(URLQueryItem(name: "port", value: String(port)))
        items.append(URLQueryItem(name: "psk", value: psk.base64EncodedString()))
        items.append(URLQueryItem(name: "fp", value: fingerprint))
        items.append(URLQueryItem(
            name: "expires",
            value: String(Int64(expiresAt.timeIntervalSince1970 * 1000))
        ))
        // URLComponents 默认不会转义 query 里的 '+'，而 Android 的
        // Uri.getQueryParameter 会把 '+' 解码成空格，导致 base64 损坏。
        // 这里手动做严格 percent-encoding，保证 '+' '/' '=' 都被转义。
        components.percentEncodedQuery = items.map { item in
            "\(Self.percentEncode(item.name))=\(Self.percentEncode(item.value ?? ""))"
        }.joined(separator: "&")
        return components.url?.absoluteString ?? ""
    }

    private static func percentEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}
