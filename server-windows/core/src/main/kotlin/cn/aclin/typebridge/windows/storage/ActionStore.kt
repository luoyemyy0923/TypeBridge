package cn.aclin.typebridge.windows.storage

import cn.aclin.typebridge.windows.model.ActionItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class ActionSnapshot(val version: Int, val actions: List<ActionItem>)

/** 指令库。v2 默认空库，由用户按需创建。 */
object ActionStore {
    private val file = File(AppDir.get(), "actions.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var actions: MutableList<ActionItem> = mutableListOf()

    @Volatile
    var version: Int = 0
        private set

    /** 指令变化后通知已连接设备刷新指令面板。 */
    var onChange: (() -> Unit)? = null

    init { restore() }

    fun snapshot(): Pair<Int, List<ActionItem>> = synchronized(lock) {
        version to actions.sortedBy { it.order }
    }

    fun action(id: String): ActionItem? = synchronized(lock) { actions.firstOrNull { it.id == id } }

    fun newID(): String = UUID.randomUUID().toString().replace("-", "").take(8).lowercase()

    fun nextOrder(): Int = synchronized(lock) { (actions.maxOfOrNull { it.order } ?: -1) + 1 }

    fun upsert(action: ActionItem) {
        synchronized(lock) {
            val idx = actions.indexOfFirst { it.id == action.id }
            if (idx >= 0) actions[idx] = action else actions.add(action)
            actions.sortBy { it.order }
            version++
            save()
        }
        onChange?.invoke()
    }

    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            if (actions.removeAll { it.id == id }) {
                version++
                save()
                true
            } else false
        }
        if (removed) onChange?.invoke()
        return removed
    }

    private fun restore() {
        val snapshot = runCatching { json.decodeFromString<ActionSnapshot>(file.readText()) }.getOrNull()
        if (snapshot != null) {
            version = snapshot.version
            actions = snapshot.actions.sortedBy { it.order }.toMutableList()
        }
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(ActionSnapshot(version, actions))) }
    }
}
