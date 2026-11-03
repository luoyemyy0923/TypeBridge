package cn.aclin.typebridge.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences 工厂（Keystore 主密钥 + AES256_SIV/GCM）。
 * 所有设备元数据、PSK、会话密钥与历史都只落在这里。
 */
object PrefsFactory {
    private const val FILE_NAME = "typebridge_secure"

    @Volatile
    private var instance: SharedPreferences? = null

    @Suppress("DEPRECATION")
    fun encrypted(context: Context): SharedPreferences =
        instance ?: synchronized(this) {
            instance ?: run {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { instance = it }
            }
        }
}
