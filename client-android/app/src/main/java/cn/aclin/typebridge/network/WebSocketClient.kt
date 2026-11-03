package cn.aclin.typebridge.network

import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 安卓端 WebSocket 客户端：PSK 握手 / 指纹比对 / 会话恢复 / 全链路 AES-GCM。
 * 所有回调统一切回主线程，ViewModel 无需再处理线程切换。
 */
class WebSocketClient(
    private val serverUrl: String,
    private val deviceId: String,
    private val deviceName: String,
    private val psk: ByteArray,
    private val expectedFingerprint: String,
    private val cachedSessionToken: String?,
    private val cachedSessionKey: ByteArray?,
    private val cachedSessionId: String?,
    private val listener: ClientListener
) {
    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
        }
    }

    interface ClientListener {
        fun onConnected(
            serverId: String?,
            known: Boolean,
            serverName: String,
            firstSeenAt: Long,
            sessionToken: String?
        )

        fun onDisconnected(reason: DisconnectReason)
        fun onAck(id: String, ok: Boolean)
        fun onPong()
        fun onStatus(state: String, devices: Int)
        fun onActions(version: Int, actions: JsonArray)
        fun onActionUpdate(version: Int, change: String, action: JsonObject)
        fun onServerError(code: Int, msg: String)
        fun onFingerprintMismatch(serverFingerprint: String)
        fun onHandshakeError(msg: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var opened = false

    @Volatile
    private var manualClose = false

    private var disconnectNotified = false
    private var outCounter = 0L
    private var inCounter = 0L

    @Volatile
    private var sessionKey: ByteArray? = cachedSessionKey

    @Volatile
    private var sessionId: String? = cachedSessionId

    @Volatile
    private var sessionToken: String? = cachedSessionToken

    private var usingResume = false
    private var triedFullAuth = false
    private var clientNonce: ByteArray? = null

    val sessionKeyBase64: String?
        get() = sessionKey?.let(Crypto::b64)

    val sessionIdValue: String?
        get() = sessionId

    fun isOpen(): Boolean = opened && sessionKey != null

    fun connect() {
        if (webSocket != null) return
        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("User-Agent", "TypeBridge-Android/2")
            .build()
        webSocket = httpClient.newWebSocket(request, socketListener)
    }

    fun disconnect() {
        manualClose = true
        opened = false
        webSocket?.close(1000, "user")
        webSocket = null
    }

    // MARK: - 发送

    /** [record] = false 时服务端不把该文本写入历史（用于加密快捷输入）。 */
    fun sendText(text: String, enter: Boolean, record: Boolean = true): String? {
        if (!isOpen()) return null
        val id = UUID.randomUUID().toString()
        val payload = Message.obj {
            put("type", "text")
            put("id", id)
            put("text", text)
            put("enter", enter)
            put("record", record)
            put("ts", System.currentTimeMillis())
        }
        return if (sendEncrypted(Message.encode(payload))) id else null
    }

    fun sendAction(actionId: String, liveText: String): String? {
        if (!isOpen()) return null
        val id = UUID.randomUUID().toString()
        val payload = Message.obj {
            put("type", "action")
            put("id", id)
            put("actionId", actionId)
            put("liveText", liveText)
            put("ts", System.currentTimeMillis())
        }
        return if (sendEncrypted(Message.encode(payload))) id else null
    }

    // MARK: - 握手

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            val canResume = cachedSessionToken != null && sessionKey != null && sessionId != null
            triedFullAuth = !canResume
            sendHello(webSocket, resume = canResume)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened = false
            if (!manualClose) {
                notifyDisconnected(ErrorClassifier.classify(t))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened = false
            if (!manualClose) {
                notifyDisconnected(DisconnectReason.Closed)
            }
        }
    }

    private fun sendHello(webSocket: WebSocket, resume: Boolean) {
        val nonce = Crypto.randomBytes(16)
        clientNonce = nonce

        if (resume && sessionToken != null) {
            usingResume = true
            val frame = Message.obj {
                put("type", "hello")
                put("v", 2)
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("sessionToken", sessionToken!!)
                put("clientNonce", Crypto.b64(nonce))
            }
            webSocket.send(Message.encode(frame))
        } else {
            usingResume = false
            val authTag = Crypto.authTag(psk, deviceId, nonce)
            val frame = Message.obj {
                put("type", "hello")
                put("v", 2)
                put("cipher", "aes-256-gcm")
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("clientNonce", Crypto.b64(nonce))
                put("authTag", Crypto.b64(authTag))
            }
            webSocket.send(Message.encode(frame))
        }
    }

    private fun handleFrame(text: String) {
        val obj = Message.parse(text) ?: return
        when (Message.typeOf(obj)) {
            "hello_ack" -> handleHelloAck(obj)
            "enc" -> handleEncrypted(obj)
            // 认证失败等错误发生在会话建立前，服务端以明文下发
            "error" -> handleTopLevelError(obj)
        }
    }

    private fun handleHelloAck(obj: JsonObject) {
        val fingerprint = obj["serverFingerprint"]?.jsonPrimitive?.contentOrNull
        if (fingerprint == null || !fingerprint.equals(expectedFingerprint, ignoreCase = true)) {
            notify { listener.onFingerprintMismatch(fingerprint.orEmpty()) }
            disconnect()
            return
        }

        if (!usingResume) {
            val serverNonce = obj["serverNonce"]?.jsonPrimitive?.contentOrNull?.let(Crypto::b64d)
            val nonce = clientNonce
            if (serverNonce == null || nonce == null) {
                notify { listener.onHandshakeError("hello_ack 缺少 nonce") }
                disconnect()
                return
            }
            sessionKey = Crypto.deriveSessionKey(psk, nonce, serverNonce)
            sessionId = Crypto.sessionId(nonce, serverNonce)
        }

        // 会话恢复时服务端返回 sessionToken=null，保留本地旧 token
        val ackToken = obj["sessionToken"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        if (ackToken != null) {
            sessionToken = ackToken
        }

        val serverId = obj["serverId"]?.jsonPrimitive?.contentOrNull
        val known = obj["known"]?.jsonPrimitive?.booleanOrNull ?: false
        val serverName = obj["serverName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val firstSeenAt = obj["firstSeenAt"]?.jsonPrimitive?.longOrNull ?: 0L

        notify {
            listener.onConnected(
                serverId = serverId,
                known = known,
                serverName = serverName,
                firstSeenAt = firstSeenAt,
                sessionToken = sessionToken
            )
        }
    }

    private fun handleTopLevelError(obj: JsonObject) {
        val code = obj["code"]?.jsonPrimitive?.intOrNull ?: -1
        val message = obj["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()

        // 会话过期 / Mac 重启：退回完整 PSK 握手一次
        if (usingResume && code == 4001 && !triedFullAuth) {
            triedFullAuth = true
            usingResume = false
            sessionKey = null
            sessionId = null
            val socket = webSocket
            if (socket != null) {
                sendHello(socket, resume = false)
                return
            }
        }
        notify { listener.onServerError(code, message) }
    }

    private fun handleEncrypted(obj: JsonObject) {
        val key = sessionKey ?: return
        val sid = sessionId ?: return
        val raw = obj["data"]?.jsonPrimitive?.contentOrNull?.let(Crypto::b64d) ?: return

        val plain = try {
            synchronized(stateLock) {
                val bytes = Crypto.decrypt(key, raw, Crypto.aad(sid, inCounter))
                inCounter += 1
                bytes
            }
        } catch (error: Exception) {
            notify { listener.onHandshakeError("加密帧解密失败") }
            disconnect()
            return
        }

        val message = Message.parse(plain.decodeToString()) ?: return
        route(message)
    }

    private fun sendEncrypted(plain: String): Boolean {
        val key = sessionKey ?: return false
        val sid = sessionId ?: return false
        val ciphertext = synchronized(stateLock) {
            val bytes = Crypto.encrypt(key, plain.toByteArray(), Crypto.aad(sid, outCounter))
            outCounter += 1
            bytes
        }
        val frame = Message.obj {
            put("type", "enc")
            put("data", Crypto.b64(ciphertext))
        }
        return webSocket?.send(Message.encode(frame)) ?: false
    }

    private fun route(message: JsonObject) {
        when (Message.typeOf(message)) {
            "ack" -> {
                val id = Message.string(message, "id").orEmpty()
                val ok = message["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                notify { listener.onAck(id, ok) }
            }

            "pong" -> notify { listener.onPong() }

            "status" -> {
                val state = Message.string(message, "state").orEmpty()
                val devices = Message.int(message, "devices") ?: 0
                notify { listener.onStatus(state, devices) }
            }

            "actions" -> {
                val version = Message.int(message, "version") ?: 0
                val actions = message["actions"] as? JsonArray ?: return
                notify { listener.onActions(version, actions) }
            }

            "action_update" -> {
                val version = Message.int(message, "version") ?: 0
                val change = Message.string(message, "change").orEmpty()
                val action = message["action"] as? JsonObject ?: return
                notify { listener.onActionUpdate(version, change, action) }
            }

            "error" -> {
                val code = Message.int(message, "code") ?: -1
                val msg = Message.string(message, "msg").orEmpty()
                notify { listener.onServerError(code, msg) }
            }
        }
    }

    private fun notifyDisconnected(reason: DisconnectReason) {
        synchronized(stateLock) {
            if (disconnectNotified) return
            disconnectNotified = true
        }
        notify { listener.onDisconnected(reason) }
    }

    private fun notify(block: () -> Unit) {
        mainHandler.post(block)
    }
}
