package dev.readflow

import dev.readflow.core.calibre.CALIBRE_COVER_SOURCE_QUERY_PARAMETER
import dev.readflow.core.calibre.SourceCredentialStore
import dev.readflow.core.calibre.authenticatedCalibreCoverUrl
import dev.readflow.core.calibre.calibreCredentialScopeForRequestUrl
import dev.readflow.extensions.api.SourceCredentials
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.Response
import java.security.MessageDigest
import java.nio.charset.Charset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CalibreCoverAuthenticationTest {

    @Test
    fun authenticatedCoverRequestStripsMarkerAndRetainsScopedCredentialsWithoutPreemptiveAuth() {
        val credentials = SourceCredentials("reader", "secret")
        val store = RecordingCredentialStore(
            expectedScope = "http://192.168.1.5:8080",
            credentials = credentials,
        )
        val markedUrl = authenticatedCalibreCoverUrl(
            "http://192.168.1.5:8080/get/cover/42/calibre-library",
            "source-calibre",
        )

        val authenticated = authenticatedCalibreCoverRequest(
            Request.Builder().url(markedUrl).build(),
            store,
        )

        assertNull(authenticated.url.queryParameter(CALIBRE_COVER_SOURCE_QUERY_PARAMETER))
        assertEquals(
            "http://192.168.1.5:8080/get/cover/42/calibre-library",
            authenticated.url.toString(),
        )
        assertNull(authenticated.header("Authorization"))
        assertEquals(credentials, authenticated.tag(SourceCredentials::class.java))
        assertEquals("source-calibre", store.requestedSourceId)
    }

    @Test
    fun coverRequestNeverUsesCredentialsBoundToAnotherOrigin() {
        val store = RecordingCredentialStore(
            expectedScope = "http://192.168.1.5:8080",
            credentials = SourceCredentials("reader", "secret"),
        )
        val markedUrl = authenticatedCalibreCoverUrl(
            "http://192.168.1.6:8080/get/cover/42/calibre-library",
            "source-calibre",
        )

        val authenticated = authenticatedCalibreCoverRequest(
            Request.Builder().url(markedUrl).build(),
            store,
        )

        assertNull(authenticated.header("Authorization"))
        assertNull(authenticated.tag(SourceCredentials::class.java))
        assertEquals("http://192.168.1.6:8080", store.requestedScope)
    }

    @Test
    fun credentialScopeCanonicalizesHostCaseAndDefaultPort() {
        assertEquals(
            "https://books.example",
            calibreCredentialScopeForRequestUrl("HTTPS://BOOKS.EXAMPLE:443/get/cover/42"),
        )
        assertEquals(
            "http://[::1]",
            calibreCredentialScopeForRequestUrl("http://[::1]:80/get/cover/42"),
        )
    }

    @Test
    fun expandedTailscaleIpv6CoverKeepsCredentialsAfterOkHttpCanonicalization() {
        val configuredBaseUrl = "http://[fd7a:115c:a1e0:0:0:0:0:1234]:8080"
        val credentials = SourceCredentials("reader", "secret")
        val store = RecordingCredentialStore(
            expectedScope = calibreCredentialScopeForRequestUrl(configuredBaseUrl),
            credentials = credentials,
        )
        val markedUrl = authenticatedCalibreCoverUrl(
            "$configuredBaseUrl/get/cover/42/calibre-library",
            "source-calibre",
        )

        val authenticated = authenticatedCalibreCoverRequest(
            Request.Builder().url(markedUrl).build(),
            store,
        )

        assertNull(authenticated.header("Authorization"))
        assertEquals(credentials, authenticated.tag(SourceCredentials::class.java))
        assertEquals(
            calibreCredentialScopeForRequestUrl("http://[fd7a:115c:a1e0::1234]:8080"),
            store.requestedScope,
        )
    }

    @Test
    fun digestChallengeRetriesCoverWithAValidDigestAuthorization() {
        val credentials = SourceCredentials("reader", "secret")
        val initial = Request.Builder()
            .url("http://192.168.1.5:8080/get/cover/42/calibre-library?size=600")
            .tag(SourceCredentials::class.java, credentials)
            .build()
        val response = Response.Builder()
            .request(initial)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .header(
                "WWW-Authenticate",
                "Digest realm=\"calibre\", nonce=\"test-nonce\", algorithm=MD5, " +
                    "qop=\"auth\", opaque=\"opaque-token\"",
            )
            .build()

        val retry = authenticateCalibreCover(response) { "fixed-cnonce" }
        val authorization = requireNotNull(retry?.header("Authorization"))
        val directives = DIGEST_DIRECTIVE.findAll(authorization.removePrefix("Digest "))
            .associate { match ->
                match.groupValues[1].lowercase() to
                    match.groupValues[2].ifEmpty { match.groupValues[3] }
            }
        val uri = "/get/cover/42/calibre-library?size=600"
        val ha1 = md5("reader:calibre:secret")
        val ha2 = md5("GET:$uri")
        val expected = md5("$ha1:test-nonce:00000001:fixed-cnonce:auth:$ha2")

        assertEquals("Digest ", authorization.take(7))
        assertEquals("reader", directives["username"])
        assertEquals(uri, directives["uri"])
        assertEquals("opaque-token", directives["opaque"])
        assertEquals(expected, directives["response"])
    }

    @Test
    fun digestChallengeWithoutCharsetUsesCalibreUtf8ForNonAsciiCredentials() {
        val credentials = SourceCredentials("读者", "密钥")
        val initial = Request.Builder()
            .url("http://192.168.1.5:8080/get/cover/42/calibre-library")
            .tag(SourceCredentials::class.java, credentials)
            .build()
        val response = Response.Builder()
            .request(initial)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .header(
                "WWW-Authenticate",
                "Digest realm=\"calibre\", nonce=\"test-nonce\", algorithm=MD5, qop=\"auth\"",
            )
            .build()

        val retry = authenticateCalibreCover(response) { "fixed-cnonce" }
        val authorization = requireNotNull(retry?.header("Authorization"))
        val directives = DIGEST_DIRECTIVE.findAll(authorization.removePrefix("Digest "))
            .associate { match ->
                match.groupValues[1].lowercase() to
                    match.groupValues[2].ifEmpty { match.groupValues[3] }
            }
        val ha1 = md5("读者:calibre:密钥", Charsets.UTF_8)
        val ha2 = md5("GET:/get/cover/42/calibre-library", Charsets.UTF_8)
        val expected = md5(
            "$ha1:test-nonce:00000001:fixed-cnonce:auth:$ha2",
            Charsets.UTF_8,
        )

        assertEquals(expected, directives["response"])
    }

    @Test
    fun basicChallengeWithoutCharsetUsesCalibreUtf8ForNonAsciiCredentials() {
        val credentials = SourceCredentials("读者", "密钥")
        val initial = Request.Builder()
            .url("http://192.168.1.5:8080/get/cover/42/calibre-library")
            .tag(SourceCredentials::class.java, credentials)
            .build()
        val response = Response.Builder()
            .request(initial)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .header("WWW-Authenticate", "Basic realm=\"calibre\"")
            .build()

        val retry = authenticateCalibreCover(response)

        assertEquals(
            okhttp3.Credentials.basic("读者", "密钥", Charsets.UTF_8),
            retry?.header("Authorization"),
        )
    }

    private fun md5(value: String, charset: Charset = Charsets.ISO_8859_1): String =
        MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(charset))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class RecordingCredentialStore(
        private val expectedScope: String,
        private val credentials: SourceCredentials,
    ) : SourceCredentialStore {
        var requestedSourceId: String? = null
        var requestedScope: String? = null

        override fun get(sourceId: String, scope: String): SourceCredentials? {
            requestedSourceId = sourceId
            requestedScope = scope
            return credentials.takeIf { scope == expectedScope }
        }

        override fun put(sourceId: String, scope: String, credentials: SourceCredentials) = Unit
        override fun remove(sourceId: String) = Unit
    }

    private companion object {
        val DIGEST_DIRECTIVE = Regex("""([A-Za-z]+)=(?:"([^"]*)"|([^,\s]+))""")
    }
}
