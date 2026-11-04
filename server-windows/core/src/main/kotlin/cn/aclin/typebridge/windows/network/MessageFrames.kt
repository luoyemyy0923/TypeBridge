package cn.aclin.typebridge.windows.network

import cn.aclin.typebridge.windows.TB
import cn.aclin.typebridge.windows.crypto.Crypto
import cn.aclin.typebridge.windows.model.ActionItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 明文 / 加密帧的统一构造，键名与协议文档保持一致。 */
object MessageFrames {

    fun helloAck(
        serverId: String,
        serverName: String,
        fingerprint: String,
        known: Boolean,
        firstSeenAt: Long,
        serverNonce: ByteArray?,
        sessionToken: String?
    ): JsonObject = buildJsonObject {
        put("type", "hello_ack")
        put("v", TB.protocolVersion)
        put("cipher", "aes-256-gcm")
        put("serverId", serverId)
        put("serverName", serverName)
        put("serverFingerprint", fingerprint)
        put("known", known)
        put("firstSeenAt", firstSeenAt)
        put("serverNonce", serverNonce?.let { Crypto.b64(it) })
        put("sessionToken", sessionToken)
    }

    fun error(code: Int, message: String): JsonObject = buildJsonObject {
        put("type", "error")
        put("code", code)
        put("msg", message)
    }

    fun ack(id: String, ok: Boolean): JsonObject = buildJsonObject {
        put("type", "ack")
        put("id", id)
        put("ok", ok)
    }

    fun pong(): JsonObject = buildJsonObject {
        put("type", "pong")
        put("ts", System.currentTimeMillis())
    }

    fun status(online: Boolean, devices: Int): JsonObject = buildJsonObject {
        put("type", "status")
        put("state", if (online) "online" else "offline")
        put("devices", devices)
    }

    fun actions(version: Int, actions: List<ActionItem>): JsonObject = buildJsonObject {
        put("type", "actions")
        put("version", version)
        put("actions", buildJsonArray {
            for (a in actions) {
                add(buildJsonObject {
                    put("id", a.id)
                    put("name", a.name)
                    put("behavior", a.behavior.raw)
                    // 敏感指令的固定文本永不下发
                    put("text", if (a.sensitive) "" else a.text)
                    put("order", a.order)
                    put("sensitive", a.sensitive)
                })
            }
        })
    }
}
