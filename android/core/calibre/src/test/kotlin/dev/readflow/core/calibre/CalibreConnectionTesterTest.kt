package dev.readflow.core.calibre

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreConnectionTesterTest {

    // Real Calibre /ajax/search response — includes sort_order, offset, num, base_url that the
    // contract does not declare.  Used to verify ignoreUnknownKeys = true.
    private val realSearchResponse = """
        {
          "total_num": 12,
          "sort_order": "asc",
          "offset": 0,
          "num": 1,
          "book_ids": [42],
          "base_url": "/ajax/search/books",
          "library_id": "books"
        }
    """.trimIndent()

    // Real Calibre /ajax/book/<id>/<library> response — includes 20+ extra fields.
    private val realBookMetaResponse = """
        {
          "id": 42,
          "title": "Flatland: A Romance of Many Dimensions",
          "authors": ["Edwin A. Abbott"],
          "author_sort": "Abbott, Edwin A.",
          "formats": ["EPUB", "PDF"],
          "tags": ["fiction", "mathematics"],
          "series": null,
          "series_index": 1.0,
          "cover": "/get/cover/42/calibre-library",
          "has_cover": true,
          "last_modified": "2024-01-15T10:30:00+00:00",
          "timestamp": "2023-06-01T08:00:00+00:00",
          "pubdate": "1884-01-01T00:00:00+00:00",
          "publisher": null,
          "comments": "A satirical novella by the English schoolmaster Edwin Abbott.",
          "identifiers": {"isbn": "9780486272634"},
          "languages": ["eng"],
          "rating": null,
          "size": 204800,
          "uuid": "550e8400-e29b-41d4-a716-446655440000"
        }
    """.trimIndent()

    @Test
    fun succeedsWhenCalibreSearchEndpointReturnsJson() = runTest {
        val tester = testerWithRoutes(
            searchJson = """{"total_num": 12, "book_ids": []}""",
        )

        val result = tester.check("http://192.168.1.5:8080")

        assertEquals(CalibreConnectionCheckResult.Success(bookCount = 12), result)
    }

    @Test
    fun succeedsWithRealCalibreWirePayloadIncludingExtraFields() = runTest {
        // Regression: default Json rejects unknown keys; ignoreUnknownKeys = true is required.
        val tester = testerWithRoutes(
            searchJson = realSearchResponse,
            bookMetaJson = realBookMetaResponse,
        )

        val result = tester.check("http://192.168.1.5:8080")

        assertEquals(CalibreConnectionCheckResult.Success(bookCount = 12), result)
    }

    @Test
    fun probesBookMetaWhenSearchReturnsIds() = runTest {
        val probedPaths = mutableListOf<String>()
        val tester = testerWithEngine { request ->
            probedPaths += request.url.encodedPath
            when {
                request.url.encodedPath == "/ajax/search" ->
                    respond(realSearchResponse, headers = jsonHeaders)
                request.url.encodedPath == "/ajax/books" ->
                    respond("""{"42":$realBookMetaResponse}""", headers = jsonHeaders)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val result = tester.check("http://192.168.1.5:8080")

        assertTrue(result is CalibreConnectionCheckResult.Success)
        assertTrue(
            "Expected book meta probe; got paths: $probedPaths",
            probedPaths.contains("/ajax/books"),
        )
    }

    @Test
    fun reportsParseFailureWhenBookMetaHasUnexpectedShape() = runTest {
        // If Calibre changes its wire format, the probe should surface it during connection test
        // rather than silently breaking in the library view.
        val tester = testerWithEngine { request ->
            when {
                request.url.encodedPath == "/ajax/search" ->
                    respond("""{"total_num":1,"book_ids":[1]}""", headers = jsonHeaders)
                // Return a malformed book meta (title is an int instead of a string) to simulate
                // a wire-format mismatch the app cannot handle.
                else -> respond("""{"1":{"id":1,"title":9999}}""", headers = jsonHeaders)
            }
        }

        val result = tester.check("http://192.168.1.5:8080")

        assertTrue(
            "Expected parse failure but got: $result",
            result is CalibreConnectionCheckResult.Failure,
        )
    }

    @Test
    fun bookMetaProbeServerFailureDoesNotReportAWorkingConnection() = runTest {
        val tester = testerWithEngine { request ->
            when {
                request.url.encodedPath == "/ajax/search" ->
                    respond(
                        """{"total_num":1,"book_ids":[99],"library_id":"books"}""",
                        headers = jsonHeaders,
                    )
                else -> respondError(HttpStatusCode.BadGateway)
            }
        }

        val result = tester.check("http://192.168.1.5:8080")

        assertEquals(
            CalibreConnectionCheckResult.Failure(
                message = "Calibre 服务器暂时不可用（HTTP 502）",
                nextStep = "确认电脑端 Calibre Content Server 正在运行后再重试",
            ),
            result,
        )
    }

    @Test
    fun reportsAuthenticationFailureWithNextStep() = runTest {
        val tester = testerWithEngine {
            respondError(HttpStatusCode.Unauthorized)
        }

        val result = tester.check("http://192.168.1.5:8080")

        assertEquals(
            CalibreConnectionCheckResult.Failure(
                message = "Calibre 服务器需要认证",
                nextStep = "请在书源设置中填写 Calibre 用户名和密码",
                kind = CalibreConnectionCheckResult.Failure.Kind.AUTHENTICATION_REQUIRED,
            ),
            result,
        )
    }

    @Test
    fun reportsNonCalibreJsonWithAddressGuidance() = runTest {
        val tester = testerWithRoutes(searchJson = """{"ok": true}""")

        val result = tester.check("http://192.168.1.5:8080")

        assertEquals(
            CalibreConnectionCheckResult.Failure(
                message = "服务器响应不像 Calibre Content Server",
                nextStep = "确认地址直接指向 Calibre Content Server，例如 http://192.168.1.5:8080",
            ),
            result,
        )
    }

    @Test
    fun normalizesAndUsesSearchEndpoint() = runTest {
        var requestedUrl = ""
        val tester = testerWithEngine {
            requestedUrl = it.url.toString()
            respond(
                content = """{"total_num": 0, "book_ids": []}""",
                headers = jsonHeaders,
            )
        }

        val result = tester.check(" http://192.168.1.5:8080/ ")

        assertTrue(result is CalibreConnectionCheckResult.Success)
        assertEquals("http://192.168.1.5:8080/ajax/search?query=&num=1&offset=0", requestedUrl)
    }

    @Test
    fun refusesRedirectFromPrivateCalibreToPublicHttp() = runTest {
        var requestCount = 0
        val tester = testerWithEngine { request ->
            requestCount++
            if (requestCount == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "http://example.com/books"),
                )
            } else {
                error("public redirect must not be requested: ${request.url}")
            }
        }

        val result = tester.check("http://192.168.1.5:8080")

        assertTrue(result is CalibreConnectionCheckResult.Failure)
        assertEquals(1, requestCount)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Routes search and optional book-meta to pre-canned JSON strings. */
    private fun testerWithRoutes(
        searchJson: String,
        bookMetaJson: String? = null,
    ): CalibreConnectionTester = testerWithEngine { request ->
        when {
            request.url.encodedPath == "/ajax/search" ->
                respond(searchJson, headers = jsonHeaders)
            request.url.encodedPath == "/ajax/books" && bookMetaJson != null ->
                respond("""{"42":$bookMetaJson}""", headers = jsonHeaders)
            request.url.encodedPath == "/ajax/books" ->
                respondError(HttpStatusCode.NotFound)
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun testerWithEngine(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CalibreConnectionTester =
        KtorCalibreConnectionTester { baseUrl ->
            defaultCalibreHttpClient(MockEngine(handler), allowedBaseUrl = baseUrl)
        }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
