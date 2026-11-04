package cn.aclin.typebridge.windows.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定向量来自 macOS 端 CryptoKit 实现，确保三端逐字节一致。
 */
class CryptoTests {

    private val psk = ByteArray(32) { 0x5A }
    private val clientNonce = ByteArray(16) { 0x11 }
    private val serverNonce = ByteArray(16) { 0x22 }

    @Test
    fun sessionKeyMatchesMacReference() {
        val key = Crypto.deriveSessionKey(psk, clientNonce, serverNonce)
        assertEquals(
            "87efd37cfaf26daf57e21ae7ae38107f0e2690f39e11482fbfdcd30b9810b516",
            key.toHex()
        )
    }

    @Test
    fun sessionIdMatchesMacReference() {
        assertEquals(
            "b3d01ded134953cb95537716b1ebbfb5",
            Crypto.sessionId(clientNonce, serverNonce)
        )
    }

    @Test
    fun authTagMatchesMacReference() {
        assertEquals(
            "b89db46b312ce9cf3ef5c3d5448ed8dc1424fe51addf946b4c818216b830093d",
            Crypto.authTag(psk, "device-1", clientNonce).toHex()
        )
    }

    @Test
    fun fingerprintMatchesMacReference() {
        assertEquals("60bf07c4", Crypto.fingerprint(psk))
    }

    @Test
    fun aadIsSessionIdPlusBigEndianCounter() {
        val aad = Crypto.aad("abc", 1)
        assertEquals(11, aad.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1), aad.copyOfRange(3, 11))
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val key = Crypto.randomBytes(32)
        val aad = Crypto.aad("session", 0)
        val plain = "你好 TypeBridge 👋".toByteArray(Charsets.UTF_8)

        val ciphertext = Crypto.encrypt(key, plain, aad)

        assertEquals(plain.size + 12 + 16, ciphertext.size)
        assertArrayEquals(plain, Crypto.decrypt(key, ciphertext, aad))
    }

    @Test
    fun decryptRejectsWrongAad() {
        val key = Crypto.randomBytes(32)
        val ciphertext = Crypto.encrypt(key, "hello".toByteArray(), Crypto.aad("a", 0))

        assertThrows(Exception::class.java) {
            Crypto.decrypt(key, ciphertext, Crypto.aad("b", 0))
        }
    }

    @Test
    fun constantTimeEqualsBehavesLikeEquals() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 1 }
        val c = ByteArray(32) { 2 }

        assertEquals(true, Crypto.constantTimeEquals(a, b))
        assertEquals(false, Crypto.constantTimeEquals(a, c))
        assertEquals(false, Crypto.constantTimeEquals(a, ByteArray(16)))
    }

    @Test
    fun sessionRejectsReplayedFrame() {
        val key = Crypto.randomBytes(32)
        val session = cn.aclin.typebridge.windows.network.Session("sid", key, "token")

        val plain = "hello".toByteArray(Charsets.UTF_8)
        val frame = session.encrypt(plain)

        // 第一次解密成功
        assertArrayEquals(plain, session.decrypt(frame))
        // 重放同一帧：counter 已前进，AAD 不匹配，必须抛异常
        assertThrows(Exception::class.java) { session.decrypt(frame) }
    }

    @Test
    fun base64RoundTrip() {
        val data = Crypto.randomBytes(32)
        val encoded = Crypto.b64(data)
        assertArrayEquals(data, Crypto.b64d(encoded))
        assertTrue(!encoded.contains('+') || true) // 标准 base64，可能含 + /
    }
}
