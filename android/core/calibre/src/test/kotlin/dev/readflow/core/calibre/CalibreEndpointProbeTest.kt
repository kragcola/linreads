package dev.readflow.core.calibre

import dev.readflow.extensions.api.SourceCredentials
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreEndpointProbeTest {

    @Test
    fun probesCommonPortsUntilCalibreResponds() = runTest {
        val tester = RecordingConnectionTester(
            results = mapOf(
                "http://192.168.1.5:8080" to CalibreConnectionCheckResult.Failure(
                    message = "连接 Calibre 超时",
                    nextStep = "检查 IP 与端口",
                ),
                "http://192.168.1.5:8081" to CalibreConnectionCheckResult.Success(bookCount = 7),
            ),
        )
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe("192.168.1.5")

        assertEquals(
            listOf("http://192.168.1.5:8080", "http://192.168.1.5:8081"),
            tester.checkedUrls,
        )
        assertEquals(
            CalibreProbeResult.Success(
                baseUrl = "http://192.168.1.5:8081",
                bookCount = 7,
            ),
            result,
        )
    }

    @Test
    fun normalizesExplicitUrlAndDoesNotTryOtherPortsAfterSuccess() = runTest {
        val tester = RecordingConnectionTester(
            results = mapOf(
                "http://192.168.1.5:8080" to CalibreConnectionCheckResult.Success(bookCount = 2),
            ),
        )
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe(" http://192.168.1.5:8080/ ")

        assertEquals(listOf("http://192.168.1.5:8080"), tester.checkedUrls)
        assertEquals(
            CalibreProbeResult.Success(
                baseUrl = "http://192.168.1.5:8080",
                bookCount = 2,
            ),
            result,
        )
    }

    @Test
    fun doesNotForwardCredentialsToExplicitTailnetHttpWhenVpnStateIsUnknown() = runTest {
        val configured = "http://100.101.102.103:8080"
        val credentials = SourceCredentials(username = "reader", password = "secret")
        val tester = RecordingConnectionTester(
            results = mapOf(configured to CalibreConnectionCheckResult.Success(bookCount = 2)),
        )
        val probe = GuidedCalibreEndpointProbe(
            connectionTester = tester,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Unknown
            },
        )

        val result = probe.probe(configured, credentials)

        assertEquals(listOf(configured), tester.checkedUrls)
        assertEquals(listOf<SourceCredentials?>(null), tester.checkedCredentials)
        assertEquals(CalibreProbeResult.Success(baseUrl = configured, bookCount = 2), result)
    }

    @Test
    fun doesNotForwardCredentialsToExplicitTailnetHttpWhenVpnDoesNotApplyToApp() = runTest {
        val configured = "http://100.101.102.103:8080"
        val credentials = SourceCredentials(username = "reader", password = "secret")
        val tester = RecordingConnectionTester(
            results = mapOf(configured to CalibreConnectionCheckResult.Success(bookCount = 2)),
        )
        val probe = GuidedCalibreEndpointProbe(
            connectionTester = tester,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(
                    vpnAppliesToApp = false,
                    internetValidated = true,
                )
            },
        )

        val result = probe.probe(configured, credentials)

        assertEquals(listOf(configured), tester.checkedUrls)
        assertEquals(listOf<SourceCredentials?>(null), tester.checkedCredentials)
        assertEquals(CalibreProbeResult.Success(baseUrl = configured, bookCount = 2), result)
    }

    @Test
    fun triesOriginalHttpsMagicDnsBeforeDirectHttpFallback() = runTest {
        val original = "https://reader.tailnet.ts.net"
        val fallback = "http://reader.tailnet.ts.net:8080"
        val tester = RecordingConnectionTester(
            results = mapOf(
                original to CalibreConnectionCheckResult.Failure(
                    message = "HTTPS 端点不可用",
                    nextStep = "检查 HTTPS 服务",
                ),
                fallback to CalibreConnectionCheckResult.Success(bookCount = 7),
            ),
        )
        val probe = GuidedCalibreEndpointProbe(tester, vpnConnectedNetwork)

        val result = probe.probe(original)

        assertEquals(listOf(original, fallback), tester.checkedUrls)
        assertEquals(CalibreProbeResult.Success(baseUrl = fallback, bookCount = 7), result)
    }

    @Test
    fun resnapshotsVpnBeforeForwardingCredentialsToDirectHttpFallback() = runTest {
        val original = "https://reader.tailnet.ts.net"
        val fallback = "http://reader.tailnet.ts.net:8080"
        val credentials = SourceCredentials(username = "reader", password = "secret")
        var snapshotCount = 0
        val changingNetwork = CalibreNetworkSnapshotProvider {
            snapshotCount += 1
            CalibreNetworkSnapshot.Active(
                vpnAppliesToApp = snapshotCount == 1,
                internetValidated = true,
            )
        }
        val tester = RecordingConnectionTester(
            results = mapOf(
                original to CalibreConnectionCheckResult.Failure(
                    message = "HTTPS 端点连接超时",
                    nextStep = "检查 HTTPS 服务",
                    kind = CalibreConnectionCheckResult.Failure.Kind.CONNECT_TIMEOUT,
                ),
                fallback to CalibreConnectionCheckResult.Success(bookCount = 7),
            ),
        )
        val probe = GuidedCalibreEndpointProbe(tester, changingNetwork)

        val result = probe.probe(original, credentials)

        assertEquals(listOf(original), tester.checkedUrls)
        assertEquals(listOf<SourceCredentials?>(credentials), tester.checkedCredentials)
        assertTrue("VPN state must be sampled again before HTTP fallback", snapshotCount >= 2)
        assertTrue(result is CalibreProbeResult.Failure)
    }

    @Test
    fun doesNotProbeDirectHttpFallbackWithoutPositiveVpnEvidence() = runTest {
        val original = "https://reader.tailnet.ts.net"
        val fallback = "http://reader.tailnet.ts.net:8080"
        val tester = RecordingConnectionTester(
            results = mapOf(
                original to CalibreConnectionCheckResult.Failure(
                    message = "HTTPS 端点不可用",
                    nextStep = "检查 HTTPS 服务",
                ),
                fallback to CalibreConnectionCheckResult.Success(bookCount = 7),
            ),
        )
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe(original)

        assertEquals(listOf(original), tester.checkedUrls)
        assertTrue(result is CalibreProbeResult.Failure)
        assertTrue((result as CalibreProbeResult.Failure).nextStep.contains("Tailscale"))
    }

    @Test
    fun preservesReachedServerFailureWhenDirectFallbackAlsoFails() = runTest {
        val original = "https://reader.tailnet.ts.net"
        val fallback = "http://reader.tailnet.ts.net:8080"
        val tester = RecordingConnectionTester(
            results = mapOf(
                original to CalibreConnectionCheckResult.Failure(
                    message = "Calibre 服务器暂时不可用（HTTP 502）",
                    nextStep = "已到达服务器地址；请检查 Calibre Content Server 或前置反向代理后重试",
                    kind = CalibreConnectionCheckResult.Failure.Kind.SERVER_RESPONSE,
                ),
                fallback to CalibreConnectionCheckResult.Failure(
                    message = "无法通过 Tailscale 连接服务器",
                    nextStep = "请打开 Tailscale 后重试",
                    kind = CalibreConnectionCheckResult.Failure.Kind.TAILNET_UNREACHABLE,
                ),
            ),
        )
        val probe = GuidedCalibreEndpointProbe(tester, vpnConnectedNetwork)

        val result = probe.probe(original)

        assertTrue(result is CalibreProbeResult.Failure)
        result as CalibreProbeResult.Failure
        assertEquals("Calibre 服务器暂时不可用（HTTP 502）", result.message)
        assertTrue(result.nextStep.contains("已到达服务器地址"))
        assertEquals(listOf(original, fallback), tester.checkedUrls)
    }

    @Test
    fun doesNotAddFallbackForExplicitHttpsMagicDnsPort() = runTest {
        val configured = "https://reader.tailnet.ts.net:8443"
        val tester = RecordingConnectionTester(
            results = mapOf(configured to CalibreConnectionCheckResult.Success(bookCount = 2)),
        )
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe(configured)

        assertEquals(listOf(configured), tester.checkedUrls)
        assertEquals(CalibreProbeResult.Success(baseUrl = configured, bookCount = 2), result)
    }

    @Test
    fun bracketsBareTailscaleIpv6BeforeTryingCommonPorts() = runTest {
        val address = "fd7a:115c:a1e0::1234"
        val tester = RecordingConnectionTester(
            results = mapOf(
                "http://[$address]:8080" to CalibreConnectionCheckResult.Success(bookCount = 3),
            ),
        )
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe(address)

        assertEquals(listOf("http://[$address]:8080"), tester.checkedUrls)
        assertEquals(
            CalibreProbeResult.Success(baseUrl = "http://[$address]:8080", bookCount = 3),
            result,
        )
    }

    @Test
    fun rejectsPublicHttpProbeCandidateBeforeNetwork() = runTest {
        val tester = RecordingConnectionTester()
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe("8.8.8.8")

        assertEquals(emptyList<String>(), tester.checkedUrls)
        assertTrue(result is CalibreProbeResult.Failure)
        assertEquals(
            "HTTP 仅允许本机、局域网或 Tailscale 地址；其他地址请使用 HTTPS",
            (result as CalibreProbeResult.Failure).message,
        )
    }

    @Test
    fun reportsAttemptedCandidatesWhenNothingResponds() = runTest {
        val tester = RecordingConnectionTester()
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe("192.168.1.5")

        assertTrue(result is CalibreProbeResult.Failure)
        val failure = result as CalibreProbeResult.Failure
        assertEquals("没有在常用 Calibre 地址发现服务", failure.message)
        assertEquals(
            listOf(
                CalibreProbeAttempt("http://192.168.1.5:8080", "无法连接到服务器"),
                CalibreProbeAttempt("http://192.168.1.5:8081", "无法连接到服务器"),
            ),
            failure.attempts,
        )
    }

    private class RecordingConnectionTester(
        private val results: Map<String, CalibreConnectionCheckResult> = emptyMap(),
    ) : CalibreConnectionTester {
        val checkedUrls = mutableListOf<String>()
        val checkedCredentials = mutableListOf<SourceCredentials?>()

        override suspend fun check(baseUrl: String): CalibreConnectionCheckResult {
            return recordCheck(baseUrl, credentials = null)
        }

        override suspend fun check(
            baseUrl: String,
            credentials: SourceCredentials?,
        ): CalibreConnectionCheckResult {
            return recordCheck(baseUrl, credentials)
        }

        private fun recordCheck(
            baseUrl: String,
            credentials: SourceCredentials?,
        ): CalibreConnectionCheckResult {
            checkedUrls += baseUrl
            checkedCredentials += credentials
            return results[baseUrl] ?: CalibreConnectionCheckResult.Failure(
                message = "无法连接到服务器",
                nextStep = "确认手机和 Calibre 在同一局域网，并检查端口是否为 8080",
            )
        }
    }

    private companion object {
        val vpnConnectedNetwork = CalibreNetworkSnapshotProvider {
            CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
        }
    }
}
