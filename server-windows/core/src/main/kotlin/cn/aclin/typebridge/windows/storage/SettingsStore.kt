package cn.aclin.typebridge.windows.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Settings(val preferClipboard: Boolean = false)

/** 应用设置（对应 macOS 的 UserDefaults）。 */
object SettingsStore {
    private val file = File(AppDir.get(), "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var preferClipboard: Boolean = false
        private set

    init { restore() }

    fun setPreferClipboard(value: Boolean) {
        preferClipboard = value
        runCatching { file.writeText(json.encodeToString(Settings(value))) }
    }

    private fun restore() {
        preferClipboard = runCatching { json.decodeFromString<Settings>(file.readText()) }
            .getOrNull()?.preferClipboard ?: false
    }
}
