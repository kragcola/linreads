package dev.readflow.core.calibre

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreNetworkDiagnosticsTest {

    @Test
    fun magicDnsFailureIsReportedAsDnsInsteadOfGenericConnectionFailure() {
        val result = UnknownHostException("reader.tailnet.ts.net").toConnectionFailure(
            endpointKind = CalibreEndpointKind.MAGIC_DNS,
            network = CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true),
        )

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.DNS_FAILURE, result.kind)
        assertEquals("无法解析 Tailscale 主机名", result.message)
        assertTrue(result.nextStep.contains("MagicDNS"))
    }

    @Test
    fun tailnetConnectFailureWithoutVpnExplainsTheMissingVpnEvidence() {
        val result = ConnectException("failed to connect").toConnectionFailure(
            endpointKind = CalibreEndpointKind.TAILSCALE_IP,
            network = CalibreNetworkSnapshot.Active(vpnAppliesToApp = false, internetValidated = true),
        )

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.TAILNET_UNREACHABLE, result.kind)
        assertEquals("无法通过 Tailscale 连接服务器", result.message)
        assertTrue(result.nextStep.contains("未检测到"))
    }

    @Test
    fun tailnetConnectFailureWithVpnPointsToServerAclAndFirewall() {
        val result = ConnectException("failed to connect").toConnectionFailure(
            endpointKind = CalibreEndpointKind.TAILSCALE_IP,
            network = CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true),
        )

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.CONNECTION_REFUSED, result.kind)
        assertEquals("服务器拒绝或无法接受连接", result.message)
        assertTrue(result.nextStep.contains("Tailscale"))
        assertTrue(result.nextStep.contains("ACL"))
        assertTrue(result.nextStep.contains("防火墙"))
    }

    @Test
    fun tailnetConnectTimeoutWithVpnRemainsATcpTimeout() {
        val result = ConnectTimeoutException("connect timeout", null).toConnectionFailure(
            endpointKind = CalibreEndpointKind.TAILSCALE_IP,
            network = CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true),
        )

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.CONNECT_TIMEOUT, result.kind)
        assertEquals("连接 Calibre 超时", result.message)
        assertTrue(result.nextStep.contains("Tailscale"))
        assertTrue(result.nextStep.contains("ACL"))
    }

    @Test
    fun unknownNetworkSnapshotDoesNotHideATcpConnectTimeout() {
        val result = ConnectTimeoutException("connect timeout", null).toConnectionFailure(
            endpointKind = CalibreEndpointKind.TAILSCALE_IP,
            network = CalibreNetworkSnapshot.Unknown,
        )

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.CONNECT_TIMEOUT, result.kind)
        assertEquals("连接 Calibre 超时", result.message)
    }

    @Test
    fun unknownNetworkSnapshotDoesNotHideAConnectionRefusal() {
        val result = ConnectException("connection refused").toConnectionFailure(
            endpointKind = CalibreEndpointKind.MAGIC_DNS,
            network = CalibreNetworkSnapshot.Unknown,
        )

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.CONNECTION_REFUSED, result.kind)
        assertEquals("服务器拒绝或无法接受连接", result.message)
    }

    @Test
    fun requestTimeoutIsDistinctFromTcpConnectTimeout() {
        val requestTimeout = HttpRequestTimeoutException("http://192.168.1.5:8080", 15_000L, null)
            .toConnectionFailure()
        val connectTimeout = ConnectTimeoutException("connect timeout", null)
            .toConnectionFailure()

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.RESPONSE_TIMEOUT, requestTimeout.kind)
        assertEquals("Calibre 服务器响应超时", requestTimeout.message)
        assertEquals(CalibreConnectionCheckResult.Failure.Kind.CONNECT_TIMEOUT, connectTimeout.kind)
        assertEquals("连接 Calibre 超时", connectTimeout.message)
    }

    @Test
    fun tlsFailureHasCertificateGuidance() {
        val result = SSLHandshakeException("certificate verify failed").toConnectionFailure(
            endpointKind = CalibreEndpointKind.MAGIC_DNS,
        )

        assertEquals(CalibreConnectionCheckResult.Failure.Kind.TLS_FAILURE, result.kind)
        assertEquals("HTTPS 安全连接失败", result.message)
        assertTrue(result.nextStep.contains("证书"))
    }
}
