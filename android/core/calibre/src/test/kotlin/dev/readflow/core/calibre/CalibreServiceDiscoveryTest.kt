package dev.readflow.core.calibre

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreServiceDiscoveryTest {

    @Test
    fun privateIpv4DiscoveryBuildsAValidatedCalibreUrl() {
        assertEquals(
            "http://192.168.2.1:8080",
            discoveredCalibreBaseUrl(InetAddress.getByName("192.168.2.1"), 8080),
        )
    }

    @Test
    fun discoveryRejectsAddressesOutsideTheLanTrustBoundary() {
        assertNull(discoveredCalibreBaseUrl(InetAddress.getByName("127.0.0.1"), 8080))
        assertNull(discoveredCalibreBaseUrl(InetAddress.getByName("8.8.8.8"), 8080))
        assertNull(discoveredCalibreBaseUrl(InetAddress.getByName("192.168.2.1"), 0))
    }

    @Test
    fun discoverySkipsRejectedCandidatesAndUsesTheNextReachableCalibre() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            CalibreDiscoveryCandidate("Public impostor", InetAddress.getByName("8.8.8.8"), 8080),
            CalibreDiscoveryCandidate("Offline Calibre", InetAddress.getByName("192.168.2.2"), 8080),
            CalibreDiscoveryCandidate("Books in calibre", InetAddress.getByName("192.168.2.1"), 8080),
        )
        val checkedUrls = mutableListOf<String>()
        val result = discoverReachableCalibre(
            candidates = kotlinx.coroutines.flow.flowOf(*candidates.toTypedArray()),
            connectionTester = CalibreConnectionTester { baseUrl ->
                checkedUrls += baseUrl
                if (baseUrl == "http://192.168.2.1:8080") {
                    CalibreConnectionCheckResult.Success(bookCount = 19)
                } else {
                    CalibreConnectionCheckResult.Failure("无法连接", "继续查找")
                }
            },
        )

        assertTrue(result is CalibreDiscoveryResult.Found)
        assertEquals("http://192.168.2.1:8080", (result as CalibreDiscoveryResult.Found).baseUrl)
        assertEquals(
            listOf("http://192.168.2.2:8080", "http://192.168.2.1:8080"),
            checkedUrls,
        )
    }

    @Test
    fun stalledCandidateHasItsOwnBudgetAndDoesNotConsumeTheDiscoveryWindow() =
        kotlinx.coroutines.runBlocking {
            val candidates = listOf(
                CalibreDiscoveryCandidate("Stalled Calibre", InetAddress.getByName("192.168.2.2"), 8080),
                CalibreDiscoveryCandidate("Books in calibre", InetAddress.getByName("192.168.2.1"), 8080),
            )
            val result = kotlinx.coroutines.withTimeoutOrNull(100L) {
                discoverReachableCalibre(
                    candidates = kotlinx.coroutines.flow.flowOf(*candidates.toTypedArray()),
                    connectionTester = CalibreConnectionTester { baseUrl ->
                        if (baseUrl == "http://192.168.2.2:8080") {
                            kotlinx.coroutines.delay(1_000L)
                            CalibreConnectionCheckResult.Failure("超时", "继续查找")
                        } else {
                            CalibreConnectionCheckResult.Success(bookCount = 19)
                        }
                    },
                    probeTimeoutMillis = 10L,
                )
            }

            assertTrue(result is CalibreDiscoveryResult.Found)
            assertEquals("http://192.168.2.1:8080", (result as CalibreDiscoveryResult.Found).baseUrl)
        }
}
