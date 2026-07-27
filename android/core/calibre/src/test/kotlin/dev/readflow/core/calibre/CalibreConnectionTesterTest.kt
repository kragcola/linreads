package dev.readflow.core.calibre

import dev.readflow.extensions.api.SourceCredentials
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreConnectionTesterTest {

    @Test
    fun connectionBaselineUsesOpdsAndNeverRequiresAjax() = runTest {
        val requestedPaths = mutableListOf<String>()
        val tester = testerWithEngine { request ->
            requestedPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/opds" -> respond(OPDS_ROOT, headers = atomHeaders)
                else -> respondError(HttpStatusCode.BadGateway)
            }
        }

        val result = tester.check("http://192.168.1.5:8080")

        assertEquals(CalibreConnectionCheckResult.Success(bookCount = null), result)
        assertEquals(listOf("/opds"), requestedPaths)
    }

    @Test
    fun derivesOpdsPathWithoutChangingConfiguredOriginOrProxyPrefix() = runTest {
        val cases = listOf(
            "http://192.168.1.5:8080" to "/opds",
            "http://192.168.1.5:8080/opds" to "/opds",
            "http://192.168.1.5:8080/calibre" to "/calibre/opds",
            "http://192.168.1.5:8080/calibre/opds" to "/calibre/opds",
        )

        cases.forEach { (configured, expectedPath) ->
            var requestedUrl = ""
            val result = testerWithEngine { request ->
                requestedUrl = request.url.toString()
                respond(OPDS_ROOT, headers = atomHeaders)
            }.check(configured)

            assertTrue("connection failed for $configured: $result", result is CalibreConnectionCheckResult.Success)
            assertEquals(expectedPath, java.net.URI(requestedUrl).path)
            assertEquals(java.net.URI(configured).scheme, java.net.URI(requestedUrl).scheme)
            assertEquals(java.net.URI(configured).host, java.net.URI(requestedUrl).host)
            assertEquals(java.net.URI(configured).port, java.net.URI(requestedUrl).port)
        }
    }

    @Test
    fun explicitHttpsMagicDnsUsesOnlyItsConfiguredOrigin() = runTest {
        val requested = mutableListOf<String>()
        val result = testerWithEngine { request ->
            requested += request.url.toString()
            respond(OPDS_ROOT, headers = atomHeaders)
        }.check("https://reader.tailnet.ts.net")

        assertTrue(result is CalibreConnectionCheckResult.Success)
        assertEquals(listOf("https://reader.tailnet.ts.net/opds"), requested)
    }

    @Test
    fun explicitTailnetHttpCredentialsFailClosedWithoutPositiveVpnEvidence() = runTest {
        val networks = listOf(
            CalibreNetworkSnapshot.Unknown,
            CalibreNetworkSnapshot.Active(vpnAppliesToApp = false, internetValidated = true),
        )
        val endpoints = listOf(
            "http://100.101.102.103:8080",
            "http://reader.tailnet.ts.net:8080",
        )

        networks.forEach { network ->
            endpoints.forEach { endpoint ->
                var requests = 0
                val result = testerWithEngine(
                    networkSnapshotProvider = CalibreNetworkSnapshotProvider { network },
                ) {
                    requests += 1
                    respond(OPDS_ROOT, headers = atomHeaders)
                }.check(endpoint, credentials)

                assertEquals("$endpoint with $network", 0, requests)
                assertTrue("$endpoint with $network should fail closed: $result", result is CalibreConnectionCheckResult.Failure)
                assertEquals(
                    CalibreConnectionCheckResult.Failure.Kind.TAILNET_UNREACHABLE,
                    (result as CalibreConnectionCheckResult.Failure).kind,
                )
            }
        }
    }

    @Test
    fun explicitTailnetHttpWithoutCredentialsStillRunsAnUnauthenticatedProbe() = runTest {
        val networks = listOf(
            CalibreNetworkSnapshot.Unknown,
            CalibreNetworkSnapshot.Active(vpnAppliesToApp = false, internetValidated = true),
        )

        networks.forEach { network ->
            var requests = 0
            val result = testerWithEngine(
                networkSnapshotProvider = CalibreNetworkSnapshotProvider { network },
            ) {
                requests += 1
                respond(OPDS_ROOT, headers = atomHeaders)
            }.check("http://100.101.102.103:8080")

            assertEquals("unauthenticated probe with $network", 1, requests)
            assertTrue("unauthenticated probe failed with $network: $result", result is CalibreConnectionCheckResult.Success)
        }
    }

    @Test
    fun digestCredentialsWaitForChallengeThenRetryOpds() = runTest {
        val authorizations = mutableListOf<String?>()
        val tester = testerWithEngine(
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
        ) { request ->
            val authorization = request.headers[HttpHeaders.Authorization]
            authorizations += authorization
            if (authorization == null) {
                respond(
                    content = "",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(
                        HttpHeaders.WWWAuthenticate,
                        "Digest realm=\"calibre\", nonce=\"test-nonce\", algorithm=MD5, qop=\"auth\"",
                    ),
                )
            } else {
                respond(OPDS_ROOT, headers = atomHeaders)
            }
        }

        val result = tester.check("http://100.101.102.103:8080", credentials)

        assertTrue("Digest OPDS connection failed: $result", result is CalibreConnectionCheckResult.Success)
        assertNull(authorizations.first())
        assertEquals(2, authorizations.size)
        assertTrue(authorizations.last().orEmpty().startsWith("Digest "))
    }

    @Test
    fun reportsAuthenticationRequiredWithSafeRequestContext() = runTest {
        val result = testerWithEngine {
            respondError(HttpStatusCode.Unauthorized)
        }.check("http://192.168.1.5:8080")

        assertTrue(result is CalibreConnectionCheckResult.Failure)
        val failure = result as CalibreConnectionCheckResult.Failure
        assertEquals(CalibreConnectionCheckResult.Failure.Kind.AUTHENTICATION_REQUIRED, failure.kind)
        assertTrue(failure.message.contains("phase=opds_root"))
        assertTrue(failure.message.contains("origin=http://192.168.1.5:8080"))
    }

    @Test
    fun gatewayFailureDoesNotClaimItReachedCalibreAndDoesNotExposePath() = runTest {
        val result = testerWithEngine {
            respondError(HttpStatusCode.BadGateway)
        }.check("https://reader.tailnet.ts.net/private-proxy")

        assertTrue(result is CalibreConnectionCheckResult.Failure)
        val failure = result as CalibreConnectionCheckResult.Failure
        assertEquals(CalibreConnectionCheckResult.Failure.Kind.SERVER_RESPONSE, failure.kind)
        assertTrue(failure.message.contains("phase=opds_root"))
        assertTrue(failure.message.contains("origin=https://reader.tailnet.ts.net"))
        assertTrue(failure.message.contains("status=502"))
        assertFalse(failure.message.contains("private-proxy"))
        assertFalse(failure.nextStep.contains("已到达服务器"))
    }

    @Test
    fun rejectsNonAtomResponseAsNotCalibre() = runTest {
        val result = testerWithEngine {
            respond("<html><body>not calibre</body></html>")
        }.check("http://192.168.1.5:8080")

        assertTrue(result is CalibreConnectionCheckResult.Failure)
        val failure = result as CalibreConnectionCheckResult.Failure
        assertTrue(failure.message.contains("不是可识别的 Calibre OPDS"))
        assertFalse(failure.message.contains("html"))
    }

    @Test
    fun refusesRedirectFromPrivateCalibreToPublicHttp() = runTest {
        var requestCount = 0
        val result = testerWithEngine { request ->
            requestCount += 1
            if (requestCount == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "http://example.com/opds"),
                )
            } else {
                error("public redirect must not be requested: ${request.url}")
            }
        }.check("http://192.168.1.5:8080")

        assertTrue(result is CalibreConnectionCheckResult.Failure)
        assertEquals(1, requestCount)
    }

    private fun testerWithEngine(
        networkSnapshotProvider: CalibreNetworkSnapshotProvider = UnknownCalibreNetworkSnapshotProvider,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CalibreConnectionTester = KtorCalibreConnectionTester(
        httpClientFactory = { baseUrl, username, password ->
            defaultCalibreHttpClient(
                engine = MockEngine(handler),
                allowedBaseUrl = baseUrl,
                username = username,
                password = password,
                networkSnapshotProvider = networkSnapshotProvider,
            )
        },
        networkSnapshotProvider = networkSnapshotProvider,
    )

    private companion object {
        val credentials = SourceCredentials(username = "reader", password = "secret")
        val atomHeaders = headersOf(HttpHeaders.ContentType, "application/atom+xml")
        const val OPDS_ROOT = """<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:calibre:main</id>
              <title>calibre library</title>
              <link rel="search" href="/opds/search/{searchTerms}" type="application/atom+xml"/>
              <entry>
                <id>calibre-nav:newest</id>
                <title>Newest</title>
                <link href="/opds/navcatalog/newest" type="application/atom+xml;profile=opds-catalog"/>
              </entry>
            </feed>"""
    }
}
