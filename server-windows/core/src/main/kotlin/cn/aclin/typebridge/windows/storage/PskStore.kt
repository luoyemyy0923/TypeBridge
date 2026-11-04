package cn.aclin.typebridge.windows.storage

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File

/**
 * 当前有效 PSK 的 DPAPI 存储（同一时刻只有一个有效 PSK）。
 * 对应 macOS 的 Keychain；PSK 生命周期短（一次性消费）。
 */
object PskStore {
    private val file = File(AppDir.get(), "psk.bin")
    private val lock = Any()

    val current: ByteArray?
        get() = synchronized(lock) {
            if (!file.exists()) return null
            val blob = runCatching { file.readBytes() }.getOrNull() ?: return null
            runCatching { Crypt32Util.cryptUnprotectData(blob) }.getOrNull()
        }

    fun set(psk: ByteArray) = synchronized(lock) {
        runCatching {
            val blob = Crypt32Util.cryptProtectData(psk)
            file.writeBytes(blob)
        }
    }

    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
    }
}
