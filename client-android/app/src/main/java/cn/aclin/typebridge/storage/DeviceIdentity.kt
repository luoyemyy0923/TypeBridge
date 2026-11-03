package cn.aclin.typebridge.storage

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * 本机 Android 的 deviceId 与显示名。
 * deviceId 仅作为 hello 里的元数据，不参与认证；显示名会随 hello 一起上报给 Mac。
 */
object DeviceIdentity {
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"

    fun get(context: Context): String {
        val prefs = PrefsFactory.encrypted(context)
        prefs.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    fun getDeviceName(context: Context): String {
        val saved = PrefsFactory.encrypted(context)
            .getString(KEY_NAME, null)
            ?.trim()
            .orEmpty()
        if (saved.isNotEmpty()) return saved
        return defaultName()
    }

    fun setDeviceName(context: Context, name: String) {
        val clean = name.trim().ifEmpty { defaultName() }
        PrefsFactory.encrypted(context).edit().putString(KEY_NAME, clean).apply()
    }

    private fun defaultName(): String {
        val model = (Build.MODEL ?: "").trim()
        return model.ifEmpty { "Android" }
    }
}
