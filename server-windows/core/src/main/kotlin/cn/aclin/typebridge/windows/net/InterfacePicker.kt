package cn.aclin.typebridge.windows.net

import cn.aclin.typebridge.windows.model.LANInterface
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 枚举可用的 IPv4 网卡，过滤虚拟网卡，私有网段优先。
 */
object InterfacePicker {
    private val excludedNames = listOf(
        "virtualbox", "vmware", "vethernet", "wsl", "tap", "tun",
        "loopback", "hyper-v", "docker", "virtual", "pseudo"
    )

    fun candidates(): List<LANInterface> {
        val list = mutableListOf<LANInterface>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.displayName?.lowercase() ?: iface.name?.lowercase() ?: continue
            if (excludedNames.any { name.contains(it) }) continue
            val addresses = runCatching { iface.interfaceAddresses }.getOrNull() ?: continue
            for (addr in addresses) {
                val inet = addr.address
                if (inet is Inet4Address) {
                    list.add(LANInterface(iface.displayName ?: iface.name, inet.hostAddress))
                }
            }
        }
        return list.sortedBy { rank(it.ip) }
    }

    fun pickDefault(): LANInterface? = candidates().firstOrNull()

    private fun rank(ip: String): Int {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return 0
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return 0
        }
        return 1
    }
}
