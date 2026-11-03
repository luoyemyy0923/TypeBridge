import Darwin
import Foundation

/// 枚举可用的 IPv4 网卡，过滤虚拟网卡，私有网段优先。
public enum InterfacePicker {
    private static let excludedPrefixes = [
        "utun", "awdl", "llw", "vmnet", "bridge", "docker", "tap", "tun"
    ]

    public static func candidates() -> [LANInterface] {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }

        var result: [LANInterface] = []
        var pointer: UnsafeMutablePointer<ifaddrs>? = first

        while let current = pointer {
            defer { pointer = current.pointee.ifa_next }

            guard let address = current.pointee.ifa_addr else { continue }
            let family = address.pointee.sa_family
            let flags = Int32(current.pointee.ifa_flags)
            guard family == UInt8(AF_INET),
                  (flags & IFF_UP) != 0,
                  (flags & IFF_LOOPBACK) == 0 else {
                continue
            }

            let name = String(cString: current.pointee.ifa_name)
            guard !excludedPrefixes.contains(where: { name.hasPrefix($0) }) else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let status = getnameinfo(
                address,
                socklen_t(address.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            if status == 0 {
                result.append(LANInterface(name: name, ip: String(cString: host)))
            }
        }

        return result.sorted { rank($0.ip) < rank($1.ip) }
    }

    public static func pickDefault() -> LANInterface? {
        candidates().first
    }

    private static func rank(_ ip: String) -> Int {
        if ip.hasPrefix("192.168.") || ip.hasPrefix("10.") {
            return 0
        }
        if ip.hasPrefix("172.") {
            let parts = ip.split(separator: ".")
            if parts.count > 1, let second = Int(parts[1]), (16 ... 31).contains(second) {
                return 0
            }
        }
        return 1
    }
}
