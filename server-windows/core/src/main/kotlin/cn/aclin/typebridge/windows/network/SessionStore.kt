package cn.aclin.typebridge.windows.network

import cn.aclin.typebridge.windows.TB
import cn.aclin.typebridge.windows.crypto.Crypto
import cn.aclin.typebridge.windows.storage.AppDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StoredSession(
    val token: String,
    val sessionId: String,
    val key: String,
    val deviceId: String,
    val fingerprint: String,
    val createdAt: Long
)

/**
 * 服务端 `sessionToken → 会话密钥` 缓存，滑动过期，LRU 淘汰最近未使用的记录。
 * 记录写入 `%APPDATA%\TypeBridge\sessions.json`，App 重启后仍可恢复会话。
 */
object SessionStore {
    private const val CAPACITY = 32
    private val lock = Any()
    private val records = LinkedHashMap<String, SessionRecord>()
    private val file = File(AppDir.get(), "sessions.json")
    private val json = Json { ignoreUnknownKeys = true }

    init { restore() }

    fun put(record: SessionRecord) = synchronized(lock) {
        records.remove(record.token)
        records[record.token] = record
        while (records.size > CAPACITY) {
            records.remove(records.keys.first())
        }
        persistLocked()
    }

    fun lookup(token: String): SessionRecord? = synchronized(lock) {
        val record = records[token]
        when {
            record == null -> null
            record.isExpired -> {
                records.remove(token)
                persistLocked()
                null
            }
            else -> {
                // LRU：移到末尾
                records.remove(token)
                records[token] = record
                record
            }
        }
    }

    fun removeAll() = synchronized(lock) {
        records.clear()
        runCatching { file.delete() }
    }

    /** 清理同一设备的历史 token，只保留 [keeping]（避免频繁重连占满容量）。 */
    fun prune(deviceId: String, keeping: Set<String>) = synchronized(lock) {
        val toRemove = records.values
            .filter { it.deviceId == deviceId && it.token !in keeping }
            .map { it.token }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { records.remove(it) }
            persistLocked()
        }
    }

    private fun restore() {
        val stored = runCatching { json.decodeFromString<List<StoredSession>>(file.readText()) }.getOrNull()
            ?: return
        val now = System.currentTimeMillis()
        val valid = stored.filter { now - it.createdAt <= TB.sessionTTLMillis }
        for (s in valid) {
            val key = Crypto.b64d(s.key) ?: continue
            records[s.token] = SessionRecord(s.token, s.sessionId, key, s.deviceId, s.fingerprint, s.createdAt)
        }
        if (valid.size != stored.size) persistLocked()
    }

    private fun persistLocked() {
        val stored = records.values.map {
            StoredSession(it.token, it.sessionId, Crypto.b64(it.key), it.deviceId, it.fingerprint, it.createdAt)
        }
        runCatching { file.writeText(json.encodeToString(stored)) }
    }
}
