package dev.readflow

import dev.readflow.core.calibre.CALIBRE_COVER_SOURCE_QUERY_PARAMETER
import dev.readflow.core.calibre.SourceCredentialStore
import dev.readflow.core.calibre.authenticatedCalibreCoverUrl
import dev.readflow.core.calibre.calibreCredentialScopeForRequestUrl
import dev.readflow.extensions.api.SourceCredentials
import okhttp3.Credentials
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CalibreCoverAuthenticationTest {

    @Test
    fun authenticatedCoverRequestStripsInternalMarkerAndAddsScopedBasicAuth() {
        val store = RecordingCredentialStore(
            expectedScope = "http://192.168.1.5:8080",
            credentials = SourceCredentials("reader", "secret"),
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
        assertEquals(Credentials.basic("reader", "secret"), authenticated.header("Authorization"))
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
}
