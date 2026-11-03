import Combine
import Foundation

/// 设备元数据存储。`deviceId` 只复用元数据（name / firstSeen / lastSeen / ip），PSK 绝不复用。
@MainActor
public final class DeviceStore: ObservableObject {
    public static let shared = DeviceStore()

    @Published public private(set) var devices: [Device] = []

    private let key = "tb.devices"
    private let archiveInterval: TimeInterval = 30 * 24 * 60 * 60

    private init() {
        restore()
    }

    public func find(by deviceId: String) -> Device? {
        devices.first { $0.id == deviceId }
    }

    public func name(for deviceId: String) -> String? {
        find(by: deviceId)?.name
    }

    /// 认证成功后调用：命中则刷新 lastSeen / ip，未命中则新建。
    /// `reportedName` 来自 Android 的 hello；未被 Mac 手动改名时每次连接都会同步最新名称。
    @discardableResult
    public func touch(deviceId: String, ip: String, reportedName: String? = nil) -> (known: Bool, firstSeen: Date) {
        let cleanName = reportedName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanIP = ip.ipv4Normalized

        if let idx = devices.firstIndex(where: { $0.id == deviceId }) {
            devices[idx].lastSeen = Date()
            devices[idx].ip = cleanIP
            devices[idx].online = true
            if !devices[idx].hasCustomName,
               let cleanName, !cleanName.isEmpty,
               devices[idx].name != cleanName {
                devices[idx].name = cleanName
            }
            save()
            return (true, devices[idx].firstSeen)
        }

        let initialName: String
        if let cleanName, !cleanName.isEmpty {
            initialName = cleanName
        } else {
            initialName = "待命名设备"
        }
        let device = Device(
            id: deviceId,
            name: initialName,
            ip: cleanIP,
            firstSeen: Date(),
            lastSeen: Date(),
            online: true
        )
        devices.append(device)
        save()
        return (false, device.firstSeen)
    }

    public func markOffline(_ deviceId: String) {
        guard let idx = devices.firstIndex(where: { $0.id == deviceId }) else { return }
        devices[idx].online = false
        save()
    }

    public func rename(_ deviceId: String, to name: String) {
        guard let idx = devices.firstIndex(where: { $0.id == deviceId }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        devices[idx].name = trimmed.isEmpty ? "待命名设备" : trimmed
        devices[idx].nameOverridden = true
        save()
    }

    public func remove(_ deviceId: String) {
        devices.removeAll { $0.id == deviceId }
        save()
    }

    public var active: [Device] {
        devices.filter { $0.lastSeen > Date().addingTimeInterval(-archiveInterval) }
    }

    public var archived: [Device] {
        devices.filter { $0.lastSeen <= Date().addingTimeInterval(-archiveInterval) }
    }

    private func restore() {
        guard let data = UserDefaults.standard.data(forKey: key),
              let list = try? JSONDecoder().decode([Device].self, from: data) else {
            return
        }
        devices = list.map { device in
            var copy = device
            copy.online = false
            return copy
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(devices) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
