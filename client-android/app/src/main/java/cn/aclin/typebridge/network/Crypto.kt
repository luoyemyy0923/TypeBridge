package cn.aclin.typebridge.network

import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 与 macOS 端逐字节对齐的密码学实现。
 *
 * - sessionKey = HKDF-SHA256(PSK, salt = clientNonce || serverNonce, info = "typebridge-v2-session")
 * - sessionId  = sha256("typebridge-v2-session-id" || clientNonce || serverNonce) 前 16 字节 hex
 * - AAD        = utf8(sessionId) || UInt64(counter, big-endian)
 * - 密文        = iv(12B) || ciphertext || tag(16B)
 */
object Crypto {
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val SESSION_INFO = "typebridge-v2-session"
    private const val SESSION_ID_INFO = "typebridge-v2-session-id"

    fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()

            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, output, offset, take)
            offset += take
            counter += 1
        }
        return output
    }

    fun deriveSessionKey(psk: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        hkdfSha256(
            ikm = psk,
            salt = clientNonce + serverNonce,
            info = SESSION_INFO.toByteArray(),
            length = 32
        )

    fun sessionId(clientNonce: ByteArray, serverNonce: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(SESSION_ID_INFO.toByteArray())
        digest.update(clientNonce)
        digest.update(serverNonce)
        return digest.digest().copyOfRange(0, 16).toHex()
    }

    /** HMAC-SHA256(PSK, deviceId || clientNonce)。 */
    fun authTag(psk: ByteArray, deviceId: String, clientNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(psk, "HmacSHA256"))
        mac.update(deviceId.toByteArray())
        mac.update(clientNonce)
        return mac.doFinal()
    }

    /** sha256(PSK) 前 4 字节，8 位小写 hex。 */
    fun fingerprint(psk: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(psk).copyOfRange(0, 4).toHex()

    fun aad(sessionId: String, counter: Long): ByteArray =
        sessionId.toByteArray() + ByteBuffer.allocate(8).putLong(counter).array()

    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        require(data.size > IV_LEN + 16) { "ciphertext too short" }
        val iv = data.copyOfRange(0, IV_LEN)
        val ciphertext = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    fun b64d(text: String): ByteArray? = runCatching {
        Base64.decode(text, Base64.NO_WRAP)
    }.getOrNull()

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            builder.append(String.format("%02x", byte.toInt() and 0xFF))
        }
        return builder.toString()
    }
}
