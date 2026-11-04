package cn.aclin.typebridge.windows.launch

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * 开机自启：写注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`。
 * 对应 macOS 的 SMAppService。
 */
object LaunchAtLogin {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "TypeBridge"

    fun isEnabled(): Boolean = runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
    }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            val exe = ProcessHandle.current().info().command().orElse(null) ?: return
            runCatching {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, exe)
            }
        } else {
            runCatching {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
                }
            }
        }
    }
}
