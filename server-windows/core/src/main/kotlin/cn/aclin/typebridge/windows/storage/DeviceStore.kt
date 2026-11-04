package cn.aclin.typebridge.windows.storage

import cn.aclin.typebridge.windows.model.Device
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 设备元数据存储。`deviceId` 只复用元数据（name / firstSeen / lastSeen / ip），PSK 绝不复用。
 */
object DeviceStore {
    private const val ARCHIVE_MILLIS = 30L * 24 * 60 * 60 * 1000
    private val file = File(AppDir.get(), "devices.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var devices: MutableList<Device> = mutableListOf()

    init { restore() }

    fun find(deviceId: String): Device? = synchronized(lock) { devices.firstOrNull { it.id == deviceId } }

    fun name(deviceId: String): String? = find(deviceId)?.name

    /** 认证成功后调用：命中则刷新 lastSeen / ip，未命中则新建。返回 (known, firstSeen)。 */
    fun touch(deviceId: String, ip: String, reportedName: String? = null): Pair<Boolean, Long> = synchronized(lock) {
        val cleanName = reportedName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanIP = ip.removePrefix("::ffff:")
        val now = System.currentTimeMillis()
        val idx = devices.indexOfFirst { it.id == deviceId }
        if (idx >= 0) {
            val d = devices[idx]
            // 用 copy 生成新实例替换，保证已返回给 UI 的旧 List 不被原地污染，
            // Compose 的结构相等比较才能正确感知 online 变化并触发重绘。
            val newName = if (!d.hasCustomName && cleanName != null && d.name != cleanName) cleanName else d.name
            devices[idx] = d.copy(lastSeen = now, ip = cleanIP, online = true, name = newName)
            save()
            true to d.firstSeen
        } else {
            val device = Device(deviceId, cleanName ?: "待命名设备", cleanIP, now, now, true)
            devices.add(device)
            save()
            false to device.firstSeen
        }
    }

    fun markOffline(deviceId: String) = synchronized(lock) {
        val idx = devices.indexOfFirst { it.id == deviceId }
        if (idx >= 0 && devices[idx].online) {
            devices[idx] = devices[idx].copy(online = false)
            save()
        }
    }

    fun rename(deviceId: String, name: String) = synchronized(lock) {
        val idx = devices.indexOfFirst { it.id == deviceId }
        if (idx >= 0) {
            val t = name.trim()
            devices[idx] = devices[idx].copy(name = if (t.isEmpty()) "待命名设备" else t, nameOverridden = true)
            save()
        }
    }

    fun remove(deviceId: String) = synchronized(lock) {
        if (devices.removeAll { it.id == deviceId }) save()
    }

    val all: List<Device> get() = synchronized(lock) { devices.toList() }

    val active: List<Device> get() =
        synchronized(lock) { devices.filter { it.lastSeen > System.currentTimeMillis() - ARCHIVE_MILLIS } }

    val archived: List<Device> get() =
        synchronized(lock) { devices.filter { it.lastSeen <= System.currentTimeMillis() - ARCHIVE_MILLIS } }

    private fun restore() {
        devices = runCatching { json.decodeFromString<List<Device>>(file.readText()) }.getOrNull()
            ?.map { it.copy(online = false) }
            ?.toMutableList()
            ?: mutableListOf()
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(devices)) }
    }
}
