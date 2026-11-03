package cn.aclin.typebridge.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** NsdManager 的简单包装：把二维码里的 `xxx.local` 解析成 IPv4。 */
class MdnsResolver(private val context: Context) {

    @Suppress("DEPRECATION")
    fun resolve(host: String, onResult: (String?) -> Unit) {
        val serviceName = host.removeSuffix(".local")
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            onResult(null)
            return
        }
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_typebridge._tcp."
        }
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onResult(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                onResult(serviceInfo.host?.hostAddress)
            }
        })
    }
}
