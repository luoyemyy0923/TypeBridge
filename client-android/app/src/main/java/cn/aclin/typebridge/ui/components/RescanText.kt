package cn.aclin.typebridge.ui.components

import cn.aclin.typebridge.network.RescanReason

fun needRescanTitle(reason: RescanReason, serverName: String = "电脑"): String {
    val name = serverName.ifBlank { "电脑" }
    return when (reason) {
        RescanReason.AuthFailed -> "认证失败，需要重新扫码"
        RescanReason.Expired -> "二维码已过期"
        RescanReason.NoWifi -> "请连接 Wi-Fi 后重新扫码"
        RescanReason.WrongSubnet -> "手机与 $name 不在同一网络"
        RescanReason.MacUnreachable -> "无法连接到 $name，请重新扫码"
        RescanReason.FingerprintMismatch -> "服务器指纹不匹配，请重新扫码"
        RescanReason.DeviceRemoved -> "$name 已移除该设备，请重新扫码配对"
        RescanReason.Manual -> "请重新扫码"
    }
}
