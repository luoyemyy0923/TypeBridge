package cn.aclin.typebridge.storage

import android.content.Context
import cn.aclin.typebridge.model.ActionItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Mac 端下发的只读指令库缓存。敏感指令的 text 只能是空串。 */
object ActionPrefs {
    private const val KEY_ACTIONS = "actions"
    private const val KEY_VERSION = "action_version"

    private val json = Json { ignoreUnknownKeys = true }

    fun list(context: Context): List<ActionItem> {
        val raw = PrefsFactory.encrypted(context).getString(KEY_ACTIONS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ActionItem>>(raw) }.getOrDefault(emptyList())
    }

    fun replaceAll(context: Context, version: Int, array: JsonArray): List<ActionItem> {
        val actions = array.mapNotNull { (it as? JsonObject)?.let(::parseAction) }
            .sortedBy { it.order }
        persist(context, version, actions)
        return actions
    }

    fun applyUpdate(context: Context, version: Int, change: String, obj: JsonObject): List<ActionItem> {
        val action = parseAction(obj)
        val current = list(context).toMutableList()
        current.removeAll { it.id == action.id }
        if (change == "upsert") {
            current.add(action)
        }
        current.sortBy { it.order }
        persist(context, version, current)
        return current
    }

    private fun parseAction(obj: JsonObject): ActionItem = ActionItem(
        id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        behavior = obj["behavior"]?.jsonPrimitive?.contentOrNull ?: "text",
        // 纵深防御：即便服务端误下发内容，敏感指令也不落盘
        text = if (obj["sensitive"]?.jsonPrimitive?.booleanOrNull == true) {
            ""
        } else {
            obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        },
        order = obj["order"]?.jsonPrimitive?.intOrNull ?: 0,
        sensitive = obj["sensitive"]?.jsonPrimitive?.booleanOrNull ?: false
    )

    private fun persist(context: Context, version: Int, actions: List<ActionItem>) {
        PrefsFactory.encrypted(context).edit()
            .putString(KEY_ACTIONS, json.encodeToString(actions))
            .putInt(KEY_VERSION, version)
            .apply()
    }
}
