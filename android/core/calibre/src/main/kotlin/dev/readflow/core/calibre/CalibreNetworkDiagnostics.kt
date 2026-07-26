package dev.readflow.core.calibre

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A point-in-time network observation used by request gates and post-failure diagnostics. */
sealed interface CalibreNetworkSnapshot {
    data object NoActiveNetwork : CalibreNetworkSnapshot
    data object Unknown : CalibreNetworkSnapshot
    data class Active(
        val vpnAppliesToApp: Boolean,
        val internetValidated: Boolean,
    ) : CalibreNetworkSnapshot
}

fun interface CalibreNetworkSnapshotProvider {
    fun snapshot(): CalibreNetworkSnapshot
}

object UnknownCalibreNetworkSnapshotProvider : CalibreNetworkSnapshotProvider {
    override fun snapshot(): CalibreNetworkSnapshot = CalibreNetworkSnapshot.Unknown
}

internal class CalibreVpnRequiredException : IOException(
    "Tailscale VPN is not active for authenticated Calibre HTTP traffic",
)

class AndroidCalibreNetworkSnapshotProvider(context: Context) : CalibreNetworkSnapshotProvider {
    private val connectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE,
    ) as? ConnectivityManager

    override fun snapshot(): CalibreNetworkSnapshot = runCatching {
        val manager = connectivityManager ?: return CalibreNetworkSnapshot.Unknown
        val network = manager.activeNetwork ?: return CalibreNetworkSnapshot.NoActiveNetwork
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return CalibreNetworkSnapshot.Unknown
        CalibreNetworkSnapshot.Active(
            vpnAppliesToApp = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            internetValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }.getOrDefault(CalibreNetworkSnapshot.Unknown)
}

internal enum class CalibreEndpointKind { TAILSCALE_IP, MAGIC_DNS, OTHER }

internal fun calibreEndpointKind(baseUrl: String): CalibreEndpointKind {
    val host = runCatching { URI(baseUrl).host?.withoutIpv6Brackets() }.getOrNull().orEmpty()
    return when {
        host.isTailscaleMagicDnsHostname() -> CalibreEndpointKind.MAGIC_DNS
        host.isTailscaleIpv4() || host.isTailscaleIpv6() -> CalibreEndpointKind.TAILSCALE_IP
        else -> CalibreEndpointKind.OTHER
    }
}

internal fun classifyCalibreConnectionFailure(
    error: Throwable,
    endpointKind: CalibreEndpointKind,
    network: CalibreNetworkSnapshot,
): CalibreConnectionCheckResult.Failure {
    error.findCause<CalibreVpnRequiredException>()?.let {
        return CalibreConnectionCheckResult.Failure(
            message = "已停止通过未受 VPN 保护的 HTTP 连接访问 Calibre",
            nextStep = "打开 Tailscale 并确认其 VPN 对 LinReads 生效后重试",
            kind = CalibreConnectionCheckResult.Failure.Kind.TAILNET_UNREACHABLE,
        )
    }
    error.findCause<UnknownHostException>()?.let {
        return if (endpointKind == CalibreEndpointKind.MAGIC_DNS) {
            CalibreConnectionCheckResult.Failure(
                message = "无法解析 Tailscale 主机名",
                nextStep = "确认 Tailscale 已连接、MagicDNS 已启用，且本设备与电脑登录同一 tailnet",
                kind = CalibreConnectionCheckResult.Failure.Kind.DNS_FAILURE,
            )
        } else {
            CalibreConnectionCheckResult.Failure(
                message = "无法解析服务器地址",
                nextStep = "检查服务器地址拼写和当前网络连接",
                kind = CalibreConnectionCheckResult.Failure.Kind.DNS_FAILURE,
            )
        }
    }
    error.findCause<SSLException>()?.let {
        return CalibreConnectionCheckResult.Failure(
            message = "HTTPS 安全连接失败",
            nextStep = "检查服务器证书、HTTPS 端口和设备时间是否正确",
            kind = CalibreConnectionCheckResult.Failure.Kind.TLS_FAILURE,
        )
    }
    if (error.findCause<ConnectTimeoutException>() != null || error.isSocketConnectTimeout()) {
        return tailnetFailureOr(
            endpointKind = endpointKind,
            network = network,
            fallback = CalibreConnectionCheckResult.Failure(
                message = "连接 Calibre 超时",
                nextStep = "确认服务器正在运行，并检查地址、端口、ACL 和防火墙",
                kind = CalibreConnectionCheckResult.Failure.Kind.CONNECT_TIMEOUT,
            ),
        )
    }
    if (error.findCause<HttpRequestTimeoutException>() != null || error.findCause<SocketTimeoutException>() != null) {
        return CalibreConnectionCheckResult.Failure(
            message = "Calibre 服务器响应超时",
            nextStep = "服务器已开始处理请求但未及时返回；检查 Content Server 负载、反向代理和网络质量",
            kind = CalibreConnectionCheckResult.Failure.Kind.RESPONSE_TIMEOUT,
        )
    }
    if (error.findCause<NoRouteToHostException>() != null || error.findCause<ConnectException>() != null) {
        return tailnetFailureOr(
            endpointKind = endpointKind,
            network = network,
            fallback = CalibreConnectionCheckResult.Failure(
                message = "服务器拒绝或无法接受连接",
                nextStep = "确认 Calibre Content Server 正在运行，并检查端口和电脑防火墙",
                kind = CalibreConnectionCheckResult.Failure.Kind.CONNECTION_REFUSED,
            ),
        )
    }
    return CalibreConnectionCheckResult.Failure(
        message = error.message?.takeIf(String::isNotBlank) ?: "无法连接到服务器",
        nextStep = "检查服务器状态、地址、端口和网络连接",
    )
}

/** Distinguishes transport failures from local file I/O in the download pipeline. */
internal fun Throwable.isCalibreTransportFailure(): Boolean =
    findCause<CalibreVpnRequiredException>() != null ||
        findCause<UnknownHostException>() != null ||
        findCause<SSLException>() != null ||
        findCause<ConnectTimeoutException>() != null ||
        findCause<HttpRequestTimeoutException>() != null ||
        findCause<SocketTimeoutException>() != null ||
        findCause<NoRouteToHostException>() != null ||
        findCause<ConnectException>() != null

private fun tailnetFailureOr(
    endpointKind: CalibreEndpointKind,
    network: CalibreNetworkSnapshot,
    fallback: CalibreConnectionCheckResult.Failure,
): CalibreConnectionCheckResult.Failure {
    if (endpointKind == CalibreEndpointKind.OTHER) return fallback
    return when (network) {
        is CalibreNetworkSnapshot.Active -> if (network.vpnAppliesToApp) {
            // The VPN is already the app's active transport. Preserve the observed TCP
            // failure instead of incorrectly telling the user that Tailnet is unavailable.
            fallback.copy(
                nextStep = when (fallback.kind) {
                    CalibreConnectionCheckResult.Failure.Kind.CONNECT_TIMEOUT ->
                        "Tailscale 已连接；目标 TCP 建连超时，请检查 Calibre、端口、Tailscale ACL 和电脑防火墙"
                    CalibreConnectionCheckResult.Failure.Kind.CONNECTION_REFUSED ->
                        "Tailscale 已连接；确认 Calibre Content Server 正在运行，并检查端口、Tailscale ACL 和电脑防火墙"
                    else -> fallback.nextStep
                },
            )
        } else {
            CalibreConnectionCheckResult.Failure(
                message = "无法通过 Tailscale 连接服务器",
                nextStep = "当前未检测到可用于本应用的 VPN 连接；请打开 Tailscale 后重试",
                kind = CalibreConnectionCheckResult.Failure.Kind.TAILNET_UNREACHABLE,
            )
        }
        CalibreNetworkSnapshot.NoActiveNetwork -> CalibreConnectionCheckResult.Failure(
            message = "无法通过 Tailscale 连接服务器",
            nextStep = "当前没有活动网络；连接网络并打开 Tailscale 后重试",
            kind = CalibreConnectionCheckResult.Failure.Kind.TAILNET_UNREACHABLE,
        )
        CalibreNetworkSnapshot.Unknown -> fallback.copy(
            nextStep = "未能读取本机 Tailscale 状态；${fallback.nextStep}",
        )
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    repeat(8) {
        if (current is T) return current
        val next = current?.cause
        if (next == null || next === current) return null
        current = next
    }
    return null
}

private fun Throwable.isSocketConnectTimeout(): Boolean =
    findCause<SocketTimeoutException>()?.message?.contains("connect", ignoreCase = true) == true
