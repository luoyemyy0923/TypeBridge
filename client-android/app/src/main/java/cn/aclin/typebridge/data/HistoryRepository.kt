package cn.aclin.typebridge.data

import android.content.Context
import cn.aclin.typebridge.data.db.AppDatabase
import cn.aclin.typebridge.data.db.HistoryEntity
import cn.aclin.typebridge.model.HistoryItem
import cn.aclin.typebridge.network.Crypto
import cn.aclin.typebridge.storage.HistoryStore
import cn.aclin.typebridge.storage.PrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 历史记录迁移到 Room：
 * - 所有 text 在 Room 中统一以 AES-GCM 密文保存
 * - 首次启动时把旧的 EncryptedSharedPreferences 历史迁移进来
 */
class HistoryRepository {
    private val migrationKey = "history_room_migrated"

    suspend fun migrateLegacyIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val prefs = PrefsFactory.encrypted(context)
        if (prefs.getBoolean(migrationKey, false)) return@withContext

        val legacy = HistoryStore.list(context)
        if (legacy.isNotEmpty()) {
            val key = DbKeyStore.getOrCreate(context)
            val items = legacy.map { item ->
                HistoryEntity(
                    id = item.id,
                    text = encrypt(key, item.text),
                    sentAt = item.sentAt,
                    status = item.status,
                    enter = item.enter,
                    deviceName = item.deviceName
                )
            }
            AppDatabase.get(context).historyDao().insertAll(items)
        }

        HistoryStore.clear(context)
        prefs.edit().putBoolean(migrationKey, true).apply()
    }

    suspend fun list(context: Context): List<HistoryItem> = withContext(Dispatchers.IO) {
        val key = DbKeyStore.getOrCreate(context)
        AppDatabase.get(context).historyDao().getAll().map { entity ->
            HistoryItem(
                id = entity.id,
                text = decrypt(key, entity.text),
                sentAt = entity.sentAt,
                status = entity.status,
                enter = entity.enter,
                deviceName = entity.deviceName
            )
        }
    }

    suspend fun save(context: Context, item: HistoryItem) = withContext(Dispatchers.IO) {
        val key = DbKeyStore.getOrCreate(context)
        AppDatabase.get(context).historyDao().insert(
            HistoryEntity(
                id = item.id,
                text = encrypt(key, item.text),
                sentAt = item.sentAt,
                status = item.status,
                enter = item.enter,
                deviceName = item.deviceName
            )
        )
    }

    suspend fun updateStatus(context: Context, id: String, status: String) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(context).historyDao().updateStatus(id, status)
        }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        AppDatabase.get(context).historyDao().delete(id)
    }

    suspend fun deleteMany(context: Context, ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        AppDatabase.get(context).historyDao().deleteMany(ids)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        AppDatabase.get(context).historyDao().clear()
    }

    private fun encrypt(key: ByteArray, text: String): String =
        Crypto.b64(Crypto.encrypt(key, text.toByteArray(), DbKeyStore.HISTORY_AAD))

    private fun decrypt(key: ByteArray, encoded: String): String = runCatching {
        val raw = Crypto.b64d(encoded) ?: error("invalid base64")
        Crypto.decrypt(key, raw, DbKeyStore.HISTORY_AAD).decodeToString()
    }.getOrDefault("[无法解密]")
}
