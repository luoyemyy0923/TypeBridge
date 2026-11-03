package cn.aclin.typebridge.model

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    /** Mac 端的稳定 UUID（hello_ack.serverId）；未连接前暂用二维码指纹占位 */
    val deviceId: String,
    val ip: String,
    val mdns: String? = null,
    val port: Int = 8765,
    val serverName: String = "",
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    /** 本次扫码得到的 PSK，收到 4001 后会被清空 */
    val psk: ByteArray? = null,
    val fingerprint: String? = null,
    /** 会话恢复所需；Mac 端缓存 24h */
    val sessionToken: String? = null,
    /** base64(AES 会话密钥)，仅用于 sessionToken 恢复 */
    val sessionKey: String? = null,
    val sessionId: String? = null,
    val actionVersion: Int = 0
) {
    val displayName: String
        get() = serverName.ifBlank { "（未命名设备）" }
}
