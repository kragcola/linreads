package dev.readflow.core.calibre

import io.ktor.client.HttpClient
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

    @Test
    fun succeedsWhenCalibreSearchEndpointReturnsJson() = runTest {
        val tester = testerWithResponse("""{"total_num": 12, "book_ids": [1]}""")

        val result = tester.check("http://192.168.1.5:8080")

        assertEquals(CalibreConnectionCheckResult.Success(bookCount = 12), result)
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
                nextStep = "当前版本暂未接入用户名密码，请先关闭 Content Server 认证或稍后配置凭据",
            ),
            result,
        )
    }

    @Test
    fun reportsNonCalibreJsonWithAddressGuidance() = runTest {
        val tester = testerWithResponse("""{"ok": true}""")

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

    private fun testerWithResponse(json: String): CalibreConnectionTester =
        testerWithEngine {
            respond(content = json, headers = jsonHeaders)
        }

    private fun testerWithEngine(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CalibreConnectionTester =
        KtorCalibreConnectionTester { defaultCalibreHttpClient(MockEngine(handler)) }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
