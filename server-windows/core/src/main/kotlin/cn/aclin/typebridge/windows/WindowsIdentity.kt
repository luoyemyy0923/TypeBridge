package cn.aclin.typebridge.windows

import cn.aclin.typebridge.windows.storage.AppDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class Identity(val serverId: String, val serverName: String?)

/**
 * Windows 端稳定标识与本机显示名称。
 * `serverName` 会在 hello_ack 里下发，安卓用它显示 Windows 电脑的名称。
 */
object WindowsIdentity {
    private val file = File(AppDir.get(), "identity.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private var serverId: String
    private var nameOverride: String?

    init {
        val existing = runCatching { json.decodeFromString<Identity>(file.readText()) }.getOrNull()
        serverId = existing?.serverId ?: UUID.randomUUID().toString()
        nameOverride = existing?.serverName
        if (existing == null) persist()
    }

    val id: String get() = synchronized(lock) { serverId }

    val defaultName: String get() = System.getenv("COMPUTERNAME") ?: "Windows"

    val name: String
        get() {
            val over = synchronized(lock) { nameOverride?.trim() }
            if (!over.isNullOrEmpty()) return over
            return defaultName
        }

    fun setName(name: String) {
        val t = name.trim()
        synchronized(lock) { nameOverride = if (t.isEmpty()) null else t }
        persist()
    }

    private fun persist() {
        val snapshot = synchronized(lock) { Identity(serverId, nameOverride) }
        runCatching { file.writeText(json.encodeToString(snapshot)) }
    }
}
