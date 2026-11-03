package cn.aclin.typebridge.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object NetworkUtils {
    /** 当前 Wi-Fi 的 IPv4 地址；未连 Wi-Fi 时返回 null。 */
    fun currentWifiIp(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val properties = manager.getLinkProperties(network) ?: return null
        return properties.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address
            ?.hostAddress
    }

    /** 按 /24 前缀粗判是否同一局域网。 */
    fun isLikelySameLan(a: String, b: String): Boolean = prefix24(a) == prefix24(b)

    private fun prefix24(ip: String): String =
        ip.split('.').take(3).joinToString(".")
}
