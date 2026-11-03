import Foundation
import ServiceManagement

/// 使用 macOS 13+ 的 SMAppService 管理“开机自动启动”。
public enum LaunchAtLogin {
    public static var isEnabled: Bool {
        switch SMAppService.mainApp.status {
        case .enabled, .requiresApproval:
            return true
        default:
            return false
        }
    }

    /// 返回 nil 表示成功；否则返回可展示的错误描述。
    @discardableResult
    public static func setEnabled(_ enabled: Bool) -> String? {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                let status = SMAppService.mainApp.status
                if status == .enabled || status == .requiresApproval {
                    try SMAppService.mainApp.unregister()
                }
            }
            return nil
        } catch {
            TBLog.ui.error("launch at login failed: \(error.localizedDescription, privacy: .public)")
            return error.localizedDescription
        }
    }
}
