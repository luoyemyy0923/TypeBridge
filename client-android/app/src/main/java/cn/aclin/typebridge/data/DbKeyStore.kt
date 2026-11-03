package cn.aclin.typebridge.data

import android.content.Context
import cn.aclin.typebridge.network.Crypto
import cn.aclin.typebridge.storage.PrefsFactory

/**
 * Room 中敏感文本的加密密钥。
 * 密钥本身只存在 EncryptedSharedPreferences（Keystore 保护），不落明文。
 */
object DbKeyStore {
    private const val KEY = "room_text_key"
    private val lock = Any()

    val QUICK_INPUT_AAD: ByteArray = "typebridge-quick-input".toByteArray()
    val HISTORY_AAD: ByteArray = "typebridge-history".toByteArray()

    fun getOrCreate(context: Context): ByteArray = synchronized(lock) {
        val prefs = PrefsFactory.encrypted(context)
        val existing = prefs.getString(KEY, null)
            ?.let(Crypto::b64d)
            ?.takeIf { it.size == 32 }
        if (existing != null) {
            existing
        } else {
            val key = Crypto.randomBytes(32)
            prefs.edit().putString(KEY, Crypto.b64(key)).apply()
            key
        }
    }
}
