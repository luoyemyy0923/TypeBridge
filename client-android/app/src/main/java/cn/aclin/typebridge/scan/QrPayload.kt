package cn.aclin.typebridge.scan

import android.net.Uri
import android.util.Base64

data class QrPayload(
    val v: Int,
    val ip: String,
    val mdns: String?,
    val port: Int,
    val psk: ByteArray,
    val fp: String,
    val expires: Long
)

enum class QrError {
    NotTypeBridge,
    UnsupportedVersion,
    MissingField,
    InvalidPsk
}

sealed class QrResult {
    data class Ok(val payload: QrPayload) : QrResult()
    data class Err(val reason: QrError) : QrResult()
}

/**
 * 解析 `typebridge://connect?...`。
 * `expires` 只做记录、不做本地拦截：手机与 Mac 时钟可能不一致，过期由服务端 4003 判定。
 */
fun parseQr(raw: String): QrResult {
    val uri = runCatching { Uri.parse(raw) }.getOrNull()
        ?: return QrResult.Err(QrError.NotTypeBridge)
    if (uri.scheme != "typebridge" || uri.host != "connect") {
        return QrResult.Err(QrError.NotTypeBridge)
    }

    val version = uri.getQueryParameter("v")?.toIntOrNull()
        ?: return QrResult.Err(QrError.MissingField)
    if (version != 2) {
        return QrResult.Err(QrError.UnsupportedVersion)
    }

    val ip = uri.getQueryParameter("ip")?.takeIf { it.isNotBlank() }
        ?: return QrResult.Err(QrError.MissingField)
    val fingerprint = uri.getQueryParameter("fp")?.takeIf { it.isNotBlank() }
        ?: return QrResult.Err(QrError.MissingField)
    val pskBase64Raw = uri.getQueryParameter("psk")
        ?: return QrResult.Err(QrError.MissingField)

    // Android 的 Uri.getQueryParameter 会把未转义的 '+' 解码成空格，
    // 而标准 base64 可能包含 '+'；这里还原后再解码。
    val pskBase64 = pskBase64Raw.replace(' ', '+')
    val psk = runCatching { Base64.decode(pskBase64, Base64.NO_WRAP) }.getOrNull()
        ?: return QrResult.Err(QrError.InvalidPsk)
    if (psk.size != 32) {
        return QrResult.Err(QrError.InvalidPsk)
    }

    val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8765
    val expires = uri.getQueryParameter("expires")?.toLongOrNull() ?: Long.MAX_VALUE
    val mdns = uri.getQueryParameter("mdns")?.takeIf { it.isNotBlank() }

    return QrResult.Ok(
        QrPayload(
            v = version,
            ip = ip,
            mdns = mdns,
            port = port,
            psk = psk,
            fp = fingerprint.lowercase(),
            expires = expires
        )
    )
}

fun qrErrorMessage(reason: QrError): String = when (reason) {
    QrError.NotTypeBridge -> "这不是 TypeBridge 二维码"
    QrError.UnsupportedVersion -> "App 与电脑端版本不兼容，请升级"
    QrError.MissingField -> "二维码不完整，请在电脑端重新生成"
    QrError.InvalidPsk -> "二维码密钥无效，请在电脑端重新生成"
}
