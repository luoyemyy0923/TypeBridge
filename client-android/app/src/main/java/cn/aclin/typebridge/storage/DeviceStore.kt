package cn.aclin.typebridge.storage

import android.content.Context
import cn.aclin.typebridge.model.Device
import kotlinx.serialization.json.Json

object DeviceStore {
    private const val KEY_LIST = "devices"
    private const val KEY_LAST = "last_device_id"
    private const val KEY_SEND_ENTER = "send_enter"

    private val json = Json { ignoreUnknownKeys = true }

    fun list(context: Context): List<Device> {
        val raw = PrefsFactory.encrypted(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Device>>(raw) }.getOrDefault(emptyList())
    }

    fun find(context: Context, deviceId: String): Device? =
        list(context).firstOrNull { it.deviceId == deviceId }

    /** 覆盖 deviceId 相同项并标记为最近连接。 */
    fun save(context: Context, device: Device) {
        val list = list(context).toMutableList()
        val index = list.indexOfFirst { it.deviceId == device.deviceId }
        if (index >= 0) {
            list[index] = device
        } else {
            list.add(0, device)
        }
        persist(context, list)
        PrefsFactory.encrypted(context).edit().putString(KEY_LAST, device.deviceId).apply()
    }

    fun remove(context: Context, deviceId: String) {
        val list = list(context).filterNot { it.deviceId == deviceId }
        persist(context, list)
        val prefs = PrefsFactory.encrypted(context)
        if (prefs.getString(KEY_LAST, null) == deviceId) {
            prefs.edit().remove(KEY_LAST).apply()
        }
    }

    fun lastConnected(context: Context): Device? {
        val id = PrefsFactory.encrypted(context).getString(KEY_LAST, null) ?: return null
        return find(context, id)
    }

    /** 4001 后清空本设备 PSK 与会话，保留元数据。 */
    fun clearPsk(context: Context, deviceId: String) {
        update(context, deviceId) {
            it.copy(psk = null, sessionToken = null, sessionKey = null, sessionId = null)
        }
    }

    fun updateActionVersion(context: Context, deviceId: String, version: Int) {
        update(context, deviceId) { it.copy(actionVersion = version) }
    }

    fun getSendEnter(context: Context): Boolean =
        PrefsFactory.encrypted(context).getBoolean(KEY_SEND_ENTER, true)

    fun setSendEnter(context: Context, value: Boolean) {
        PrefsFactory.encrypted(context).edit().putBoolean(KEY_SEND_ENTER, value).apply()
    }

    private fun update(context: Context, deviceId: String, transform: (Device) -> Device) {
        val list = list(context).toMutableList()
        val index = list.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            list[index] = transform(list[index])
            persist(context, list)
        }
    }

    private fun persist(context: Context, list: List<Device>) {
        val raw = json.encodeToString(list)
        PrefsFactory.encrypted(context).edit().putString(KEY_LIST, raw).apply()
    }
}
