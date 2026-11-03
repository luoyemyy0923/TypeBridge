package cn.aclin.typebridge.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 协议帧的统一 JSON 入口。 */
object Message {
    fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(block)

    fun encode(obj: JsonObject): String = obj.toString()

    fun parse(text: String): JsonObject? = runCatching {
        Json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    fun typeOf(obj: JsonObject): String? = obj["type"]?.jsonPrimitive?.contentOrNull

    fun string(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull

    fun int(obj: JsonObject, key: String): Int? =
        obj[key]?.jsonPrimitive?.intOrNull
}
