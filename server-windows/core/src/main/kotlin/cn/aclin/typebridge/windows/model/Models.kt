package cn.aclin.typebridge.windows.model

import cn.aclin.typebridge.windows.crypto.Crypto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val name: String,
    val ip: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val online: Boolean,
    val nameOverridden: Boolean? = null
) {
    val hasCustomName: Boolean get() = nameOverridden == true
}

@Serializable
enum class ActionBehavior(val raw: String) {
    @SerialName("text") TEXT("text"),
    @SerialName("text_enter") TEXT_ENTER("text_enter");

    val displayName: String get() = if (this == TEXT) "纯文本" else "文本 + 回车"

    companion object {
        fun fromRaw(raw: String): ActionBehavior = entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

@Serializable
data class ActionItem(
    val id: String,
    val name: String,
    val behavior: ActionBehavior,
    val text: String,
    val order: Int,
    val sensitive: Boolean = false
)

data class LANInterface(val name: String, val ip: String) {
    val id: String get() = "$name@$ip"
}

/**
 * 二维码载荷，对应 `typebridge://connect?...`。
 * query 值做严格 percent-encoding（`+` `/` `=` 都转义），
 * 否则安卓端 `Uri.getQueryParameter` 会把 `+` 解码成空格，损坏 base64 PSK。
 */
data class QrPayload(
    val ip: String,
    val mdns: String?,
    val port: Int,
    val psk: ByteArray,
    val fingerprint: String,
    val expiresAt: Long
) {
    fun uriString(): String {
        val params = linkedMapOf<String, String?>(
            "v" to "2",
            "ip" to ip,
            "mdns" to mdns,
            "port" to port.toString(),
            "psk" to Crypto.b64(psk),
            "fp" to fingerprint,
            "expires" to expiresAt.toString()
        )
        val parts = mutableListOf<String>()
        for ((k, v) in params) {
            if (v.isNullOrEmpty()) continue
            parts.add("${percentEncode(k)}=${percentEncode(v)}")
        }
        return "typebridge://connect?" + parts.joinToString("&")
    }

    private fun percentEncode(value: String): String {
        val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        return buildString {
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c.toChar() in allowed) append(c.toChar())
                else append('%').append(String.format("%02X", c))
            }
        }
    }
}
