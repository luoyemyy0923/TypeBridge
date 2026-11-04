package cn.aclin.typebridge.windows.network

import cn.aclin.typebridge.windows.TB
import cn.aclin.typebridge.windows.crypto.Crypto

/**
 * 一条已建立的加密会话。计数值按方向独立维护：
 * `outCounter` 服务端 → 客户端，`inCounter` 客户端 → 服务端。
 * AAD 同时包含 sessionId 与 counter，重放的旧帧会因 AAD 不匹配而解密失败。
 */
class Session(
    val id: String,
    val key: ByteArray,
    val token: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    private val lock = Any()
    private var outCounter = 0L
    private var inCounter = 0L

    fun encrypt(plain: ByteArray): ByteArray = synchronized(lock) {
        val aad = Crypto.aad(id, outCounter)
        val ct = Crypto.encrypt(key, plain, aad)
        outCounter++
        ct
    }

    fun decrypt(data: ByteArray): ByteArray = synchronized(lock) {
        val aad = Crypto.aad(id, inCounter)
        val plain = Crypto.decrypt(key, data, aad)
        inCounter++
        plain
    }
}

data class SessionRecord(
    val token: String,
    val sessionId: String,
    val key: ByteArray,
    val deviceId: String,
    val fingerprint: String,
    val createdAt: Long
) {
    val isExpired: Boolean get() = System.currentTimeMillis() - createdAt > TB.sessionTTLMillis
}
