package cn.aclin.typebridge.windows.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Settings(
    val preferClipboard: Boolean = false,
    val allowSecureClipboard: Boolean = false
)

/** 应用设置（对应 macOS 的 UserDefaults）。 */
object SettingsStore {
    private val file = File(AppDir.get(), "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var preferClipboard: Boolean = false
        private set

    @Volatile
    var allowSecureClipboard: Boolean = false
        private set

    init { restore() }

    fun setPreferClipboard(value: Boolean) {
        preferClipboard = value
        persist()
    }

    fun setAllowSecureClipboard(value: Boolean) {
        allowSecureClipboard = value
        persist()
    }

    private fun persist() {
        runCatching {
            file.writeText(json.encodeToString(Settings(preferClipboard, allowSecureClipboard)))
        }
    }

    private fun restore() {
        val settings = runCatching { json.decodeFromString<Settings>(file.readText()) }.getOrNull()
        preferClipboard = settings?.preferClipboard ?: false
        allowSecureClipboard = settings?.allowSecureClipboard ?: false
    }
}
