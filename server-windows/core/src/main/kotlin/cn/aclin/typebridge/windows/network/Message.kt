package cn.aclin.typebridge.windows.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 协议帧 JSON 的解析入口（与安卓 `Message.kt` 对齐）。 */
object Message {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    fun typeOf(obj: JsonObject): String? = obj["type"]?.jsonPrimitive?.contentOrNull

    fun str(obj: JsonObject, key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
    fun int(obj: JsonObject, key: String): Int? = obj[key]?.jsonPrimitive?.intOrNull
    fun long(obj: JsonObject, key: String): Long? = obj[key]?.jsonPrimitive?.longOrNull
    fun bool(obj: JsonObject, key: String): Boolean? = obj[key]?.jsonPrimitive?.booleanOrNull
}
