package cn.aclin.typebridge.network

sealed class AppConnState {
    data object Disconnected : AppConnState()
    data object Connecting : AppConnState()
    data class Connected(val serverName: String, val known: Boolean) : AppConnState()
    data class AutoReconnecting(
        val attempt: Int,
        val reason: DisconnectReason,
        val blocker: PrecheckBlocker? = null
    ) : AppConnState()
    data class NeedRescan(val reason: RescanReason, val detail: String = "") : AppConnState()
}

sealed class PrecheckBlocker {
    data object NoWifi : PrecheckBlocker()
    data class WrongSubnet(val phoneIp: String, val macIp: String) : PrecheckBlocker()
}

enum class RescanReason {
    AuthFailed,
    Expired,
    WrongSubnet,
    NoWifi,
    MacUnreachable,
    FingerprintMismatch,
    DeviceRemoved,
    Manual
}

enum class DisconnectReason {
    Closed,
    Timeout,
    Refused,
    UnknownHost,
    Network,
    FingerprintMismatch
}
