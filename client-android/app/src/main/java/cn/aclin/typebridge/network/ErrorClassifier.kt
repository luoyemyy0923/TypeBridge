package cn.aclin.typebridge.network

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 把 OkHttp / 系统异常折算成可提示的断线原因。 */
object ErrorClassifier {
    fun classify(error: Throwable): DisconnectReason = when (error) {
        is SocketTimeoutException -> DisconnectReason.Timeout
        is ConnectException -> DisconnectReason.Refused
        is UnknownHostException -> DisconnectReason.UnknownHost
        is NoRouteToHostException -> DisconnectReason.Network
        is SocketException -> DisconnectReason.Network
        is IOException -> DisconnectReason.Network
        else -> DisconnectReason.Network
    }
}
