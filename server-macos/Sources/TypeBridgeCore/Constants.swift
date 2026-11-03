import Foundation
import os

public enum TB {
    public static let protocolVersion = 2
    public static let port: UInt16 = 8765
    public static let wsPath = "/typebridge"
    public static let maxConnections = 8
    /// 会话恢复的有效期。每次成功恢复都会自动续期并轮换 sessionToken，
    /// 因此只要在这段时间内使用过一次就不需要重新扫码。
    public static let sessionTTL: TimeInterval = 30 * 24 * 60 * 60
    public static let qrTTL: TimeInterval = 5 * 60
    public static let sessionInfo = Data("typebridge-v2-session".utf8)
    public static let sessionIdInfo = Data("typebridge-v2-session-id".utf8)
    public static let bundleIdentifier = "com.typebridge.mac"
    public static let serviceType = "_typebridge._tcp."
}

public enum TBLog {
    public static let server = Logger(subsystem: TB.bundleIdentifier, category: "server")
    public static let network = Logger(subsystem: TB.bundleIdentifier, category: "network")
    public static let inject = Logger(subsystem: TB.bundleIdentifier, category: "inject")
    public static let ui = Logger(subsystem: TB.bundleIdentifier, category: "ui")
}
