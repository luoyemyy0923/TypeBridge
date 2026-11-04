package cn.aclin.typebridge.windows

/**
 * 协议常量，与 macOS `Constants.swift`、安卓端保持一致。
 */
object TB {
    const val protocolVersion = 2
    const val port = 8765
    const val wsPath = "/typebridge"
    const val maxConnections = 8

    /** 会话恢复有效期：30 天滑动续期。 */
    const val sessionTTLMillis = 30L * 24 * 60 * 60 * 1000

    /** 二维码有效期：5 分钟。 */
    const val qrTTLMillis = 5L * 60 * 1000

    const val sessionInfo = "typebridge-v2-session"
    const val sessionIdInfo = "typebridge-v2-session-id"
    const val serviceType = "_typebridge._tcp."
    const val appName = "投字"
}
