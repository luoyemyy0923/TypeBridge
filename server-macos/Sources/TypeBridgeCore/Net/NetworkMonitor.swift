import Foundation
import Network

/// 监听网络路径变化。IP 变化时回调，用于刷新二维码（同时换 IP 与 PSK）。
@MainActor
public final class NetworkMonitor {
    public static let shared = NetworkMonitor()

    public var onIPChanged: ((String?) -> Void)?

    private let monitor = NWPathMonitor()
    private var lastIP: String?
    private var started = false

    private init() {}

    public func start() {
        guard !started else { return }
        started = true
        lastIP = InterfacePicker.pickDefault()?.ip

        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            let currentIP = InterfacePicker.pickDefault()?.ip
            Task { @MainActor in
                guard let self else { return }
                guard currentIP != self.lastIP else { return }
                self.lastIP = currentIP
                TBLog.network.log("ip changed -> \(currentIP ?? "nil", privacy: .public)")
                self.onIPChanged?(currentIP)
            }
        }
        monitor.start(queue: DispatchQueue(label: "typebridge.network.monitor"))
    }
}
