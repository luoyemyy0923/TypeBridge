package cn.aclin.typebridge.storage

import android.content.Context
import cn.aclin.typebridge.model.HistoryItem
import kotlinx.serialization.json.Json

object HistoryStore {
    private const val KEY_HISTORY = "history"
    private const val LIMIT = 200

    private val json = Json { ignoreUnknownKeys = true }

    fun list(context: Context): List<HistoryItem> {
        val raw = PrefsFactory.encrypted(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<HistoryItem>>(raw) }.getOrDefault(emptyList())
    }

    fun save(context: Context, item: HistoryItem) {
        val list = (listOf(item) + list(context)).take(LIMIT)
        persist(context, list)
    }

    fun updateStatus(context: Context, id: String, status: String) {
        val list = list(context).map { if (it.id == id) it.copy(status = status) else it }
        persist(context, list)
    }

    fun clear(context: Context) {
        PrefsFactory.encrypted(context).edit().remove(KEY_HISTORY).apply()
    }

    fun delete(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, list: List<HistoryItem>) {
        val raw = json.encodeToString(list)
        PrefsFactory.encrypted(context).edit().putString(KEY_HISTORY, raw).apply()
    }
}
