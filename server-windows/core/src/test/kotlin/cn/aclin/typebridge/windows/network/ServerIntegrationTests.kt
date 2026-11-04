package cn.aclin.typebridge.windows.network

import cn.aclin.typebridge.windows.TB
import cn.aclin.typebridge.windows.crypto.Crypto
import cn.aclin.typebridge.windows.storage.PskStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 端到端集成测试：完整 PSK 握手 + 加密帧（ping/pong）往返。
 * 验证 WS 服务端、路径路由、会话派生、加密计数对齐全部正确。
 */
class ServerIntegrationTests {
    private val port = 18765
    private lateinit var server: TypeBridgeWebSocketServer

    companion object {
        init {
            // 隔离数据目录，避免污染 %APPDATA%\TypeBridge 的真实数据
            val dir = Files.createTempDirectory("typebridge-test")
            System.setProperty("typebridge.dataDir", dir.toString())
        }
    }

    @Before
    fun setUp() {
        server = TypeBridgeWebSocketServer(port)
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.stop() }
        PskStore.clear()
    }

    @Test
    fun fullHandshakeAndEncryptedPingPong() {
        val psk = ByteArray(32) { 0x42 }
        PskStore.set(psk)
        val deviceId = "test-device"
        val clientNonce = Crypto.randomBytes(16)

        val handshakeLatch = CountDownLatch(1)
        val pongLatch = CountDownLatch(1)
        val pairedLatch = CountDownLatch(1)
        val devicesLatch = CountDownLatch(1)

        server.onPaired = { pairedLatch.countDown() }
        server.onDevicesChanged = { devicesLatch.countDown() }

        var sessionKey: ByteArray? = null
        var sessionId: String? = null
        var outCounter = 0L
        var inCounter = 0L
        var pongOk = false
        var failure: String? = null

        val client = object : WebSocketClient(URI("ws://127.0.0.1:$port${TB.wsPath}")) {
            override fun onOpen(handshake: ServerHandshake) {
                val authTag = Crypto.authTag(psk, deviceId, clientNonce)
                val frame = buildJsonObject {
                    put("type", "hello")
                    put("v", 2)
                    put("cipher", "aes-256-gcm")
                    put("deviceId", deviceId)
                    put("deviceName", "test")
                    put("clientNonce", Crypto.b64(clientNonce))
                    put("authTag", Crypto.b64(authTag))
                }
                send(frame.toString())
            }

            override fun onMessage(message: String) {
                val obj = Message.parse(message) ?: return
                when (Message.typeOf(obj)) {
                    "hello_ack" -> {
                        val serverNonce = Message.str(obj, "serverNonce")?.let(Crypto::b64d)
                        if (serverNonce == null) {
                            failure = "missing serverNonce"
                            handshakeLatch.countDown()
                            return
                        }
                        sessionKey = Crypto.deriveSessionKey(psk, clientNonce, serverNonce)
                        sessionId = Crypto.sessionId(clientNonce, serverNonce)
                        val ping = buildJsonObject { put("type", "ping") }.toString()
                        val ct = Crypto.encrypt(sessionKey, ping.toByteArray(Charsets.UTF_8), Crypto.aad(sessionId, outCounter))
                        outCounter++
                        send(buildJsonObject { put("type", "enc"); put("data", Crypto.b64(ct)) }.toString())
                        handshakeLatch.countDown()
                    }

                    "enc" -> {
                        val raw = Message.str(obj, "data")?.let(Crypto::b64d)
                        val plain = raw?.let { Crypto.decrypt(sessionKey!!, it, Crypto.aad(sessionId!!, inCounter)) }
                        inCounter++
                        val business = plain?.let { Message.parse(String(it, Charsets.UTF_8)) }
                        if (business != null && Message.typeOf(business) == "pong") {
                            pongOk = true
                            pongLatch.countDown()
                        }
                    }

                    "error" -> {
                        failure = "server error ${Message.int(obj, "code")}: ${Message.str(obj, "msg")}"
                        handshakeLatch.countDown()
                    }
                }
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {}
            override fun onError(ex: Exception) {
                failure = ex.message
                handshakeLatch.countDown()
            }
        }

        client.connect()
        assertTrue("handshake failed: $failure", handshakeLatch.await(8, TimeUnit.SECONDS))
        assertTrue("no pong within timeout: $failure", pongLatch.await(8, TimeUnit.SECONDS))
        assertTrue("pong was not decoded correctly", pongOk)
        assertTrue("onPaired was not fired", pairedLatch.await(3, TimeUnit.SECONDS))
        assertTrue("onDevicesChanged was not fired", devicesLatch.await(3, TimeUnit.SECONDS))
        assertEquals(
            "test-device",
            cn.aclin.typebridge.windows.storage.DeviceStore.find(deviceId)?.id
        )
        client.close()
    }
}
