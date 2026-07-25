package dev.readflow.core.calibre

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreDigestAuthenticationTest {

    @Test
    fun searchWaitsForDigestChallengeAndRetriesWithDigestAuthorization() = runTest {
        val authorizations = mutableListOf<String?>()
        val requestPaths = mutableListOf<String>()
        var acceptedDigest = false
        val baseUrl = "http://192.168.1.5:8080"
        val engine = MockEngine { request ->
            val authorization = request.headers[HttpHeaders.Authorization]
            authorizations += authorization
            requestPaths += request.url.encodedPath
            when {
                authorization == null -> respond(
                    content = "",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.WWWAuthenticate, DIGEST_CHALLENGE),
                )
                authorization.startsWith("Basic ") -> respond(
                    content = "Basic authentication is not accepted",
                    status = HttpStatusCode.BadRequest,
                )
                isValidDigestAuthorization(
                    authorization = authorization,
                    method = request.method,
                    requestUri = request.url.encodedPath + "?" + request.url.encodedQuery,
                ) -> {
                    acceptedDigest = true
                    respond(
                        content = SEARCH_RESPONSE,
                        headers = JSON_HEADERS,
                    )
                }
                else -> respond(
                    content = "",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.WWWAuthenticate, DIGEST_CHALLENGE),
                )
            }
        }
        val client = CalibreClient(
            baseUrl = baseUrl,
            username = USERNAME,
            password = PASSWORD,
            libraryId = "calibre-library",
            http = defaultCalibreHttpClient(
                engine = engine,
                allowedBaseUrl = baseUrl,
                username = USERNAME,
                password = PASSWORD,
            ),
        )

        val result = client.use {
            runCatching { it.search() }
        }

        assertNull("The first request must wait for the Digest challenge", authorizations.first())
        assertTrue("Digest handshake failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(2, authorizations.size)
        assertEquals(listOf("/ajax/search", "/ajax/search"), requestPaths)
        assertTrue(acceptedDigest)
        assertEquals(CalibreSearchResult(total_num = 1, book_ids = listOf(42)), result.getOrThrow())
    }

    private fun isValidDigestAuthorization(
        authorization: String,
        method: HttpMethod,
        requestUri: String,
    ): Boolean {
        if (!authorization.startsWith("Digest ", ignoreCase = true)) return false
        val values = DIGEST_DIRECTIVE.findAll(authorization.removePrefix("Digest "))
            .associate { match ->
                val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
                match.groupValues[1].lowercase() to value
            }
        if (values["username"] != USERNAME || values["realm"] != REALM) return false
        if (values["nonce"] != NONCE || values["uri"] != requestUri) return false
        if (values["qop"] != QOP || values["nc"] != "00000001") return false
        val cnonce = values["cnonce"].orEmpty()
        if (cnonce.isEmpty()) return false

        val ha1 = md5("$USERNAME:$REALM:$PASSWORD")
        val ha2 = md5("${method.value}:$requestUri")
        val expectedResponse = md5("$ha1:$NONCE:${values["nc"]}:$cnonce:$QOP:$ha2")
        return values["response"] == expectedResponse
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val USERNAME = "reader"
        const val PASSWORD = "secret"
        const val REALM = "calibre"
        const val NONCE = "test-nonce"
        const val QOP = "auth"
        const val DIGEST_CHALLENGE =
            "Digest realm=\"$REALM\", nonce=\"$NONCE\", algorithm=MD5, qop=\"$QOP\""
        const val SEARCH_RESPONSE = """{"total_num":1,"book_ids":[42]}"""
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val DIGEST_DIRECTIVE = Regex("""([A-Za-z]+)=(?:"([^"]*)"|([^,\s]+))""")
    }
}
