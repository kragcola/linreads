package dev.readflow.core.calibre

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
    fun rejectsPublicHttpProbeCandidateBeforeNetwork() = runTest {
        val tester = RecordingConnectionTester()
        val probe = GuidedCalibreEndpointProbe(tester)

        val result = probe.probe("8.8.8.8")

        assertEquals(emptyList<String>(), tester.checkedUrls)
        assertTrue(result is CalibreProbeResult.Failure)
        assertEquals(
            "HTTP 只允许局域网私有地址：10.x、172.16-31.x、192.168.x，公网地址请使用 HTTPS",
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

        override suspend fun check(baseUrl: String): CalibreConnectionCheckResult {
            checkedUrls += baseUrl
            return results[baseUrl] ?: CalibreConnectionCheckResult.Failure(
                message = "无法连接到服务器",
                nextStep = "确认手机和 Calibre 在同一局域网，并检查端口是否为 8080",
            )
        }
    }
}
