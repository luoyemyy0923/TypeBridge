package cn.aclin.typebridge.windows.net

/**
 * 监听本机默认网卡 IP 变化（轮询，3s 间隔）。
 * IP 变化时回调，用于刷新二维码（同时换 IP 与 PSK）。
 */
object NetworkMonitor {
    private const val POLL_MILLIS = 3000L

    @Volatile
    private var lastIP: String? = null

    @Volatile
    private var running = false

    private var thread: Thread? = null

    var onIPChanged: ((String?) -> Unit)? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        lastIP = InterfacePicker.pickDefault()?.ip
        thread = Thread {
            while (running) {
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (e: InterruptedException) {
                    break
                }
                val current = InterfacePicker.pickDefault()?.ip
                if (current != lastIP) {
                    lastIP = current
                    onIPChanged?.invoke(current)
                }
            }
        }.apply {
            isDaemon = true
            name = "typebridge-network-monitor"
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
