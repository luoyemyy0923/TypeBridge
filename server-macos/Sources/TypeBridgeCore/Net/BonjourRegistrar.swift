import Foundation

/// 注册 `_typebridge._tcp.` Bonjour 服务，二维码里的 `mdns` 字段用它做 IP 漂移时的 fallback。
/// 注册失败不影响主流程：二维码仍只带 IP。
public final class BonjourRegistrar: NSObject, NetServiceDelegate, @unchecked Sendable {
    public static let shared = BonjourRegistrar()

    private let lock = NSLock()
    private var service: NetService?
    private var registeredName: String?

    public var localName: String? {
        lock.lock()
        defer { lock.unlock() }
        return registeredName.map { "\($0).local" }
    }

    @discardableResult
    public func ensureRegistered(port: Int32 = Int32(TB.port)) -> String? {
        lock.lock()
        defer { lock.unlock() }
        if service != nil, let registeredName {
            return "\(registeredName).local"
        }
        let name = "typebridge-" + String(UUID().uuidString.prefix(4)).lowercased()
        let newService = NetService(domain: "local.", type: TB.serviceType, name: name, port: port)
        newService.delegate = self
        newService.publish()
        service = newService
        registeredName = name
        TBLog.network.log("published bonjour service \(name, privacy: .public)")
        return "\(name).local"
    }

    public func register(port: Int32 = Int32(TB.port)) {
        lock.lock()
        let shouldRegister = service == nil
        lock.unlock()
        if shouldRegister {
            ensureRegistered(port: port)
        }
    }

    public func stop() {
        lock.lock()
        let current = service
        service = nil
        registeredName = nil
        lock.unlock()
        current?.stop()
    }

    public func netServiceDidPublish(_ sender: NetService) {
        TBLog.network.log("bonjour published: \(sender.name, privacy: .public)")
    }

    public func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        TBLog.network.error("bonjour publish failed: \(String(describing: errorDict), privacy: .public)")
    }
}
