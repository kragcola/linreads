package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalibreOnlineCatalogIdentityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sameRemoteIdIsNamespacedPerSourceWhileBuiltinKeepsLegacyId() = runTest {
        val first = catalog("source-a")
        val second = catalog("source-b")
        val builtin = catalog(BUILTIN_CALIBRE_SOURCE_ID)

        val firstEntry = first.search("").successValue().single()
        val secondEntry = second.search("").successValue().single()
        val builtinEntry = builtin.search("").successValue().single()

        assertEquals(stableRemoteBookId("source-a", "42"), firstEntry.meta.id)
        assertEquals(stableRemoteBookId("source-b", "42"), secondEntry.meta.id)
        assertNotEquals(firstEntry.meta.id, secondEntry.meta.id)
        assertEquals("42", builtinEntry.meta.id)
        assertEquals(BUILTIN_CALIBRE_SOURCE_ID, builtinEntry.remoteKey?.sourceId)
        assertTrue(
            firstEntry.meta.coverUrl?.contains("$CALIBRE_COVER_SOURCE_QUERY_PARAMETER=source-a") == true,
        )

        first.close()
        second.close()
        builtin.close()
    }

    @Test
    fun downloadRejectsEntryFromAnotherCalibreSource() = runTest {
        val first = catalog("source-a")
        val booksDir = tempFolder.newFolder("calibre-cross-source")
        val second = catalog("source-b", booksDir)
        val firstEntry = first.search("").successValue().single()

        val result = second.download(firstEntry)

        assertTrue(result is ReadflowResult.Failure)
        assertFalse(booksDir.listFiles()?.isNotEmpty() == true)
        first.close()
        second.close()
    }

    private fun catalog(sourceId: String, booksDir: java.io.File? = null): CalibreOnlineCatalog {
        val baseUrl = "http://192.168.1.5:8080"
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath == "/ajax/search") {
                """{"total_num":1,"book_ids":[42]}"""
            } else {
                """{"id":42,"title":"Shared","authors":["Author"],"formats":["EPUB"]}"""
            }
            respond(
                content = body,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = CalibreClient(
            baseUrl = baseUrl,
            username = "",
            password = "",
            libraryId = "calibre-library",
            http = defaultCalibreHttpClient(engine, allowedBaseUrl = baseUrl),
        )
        return CalibreOnlineCatalog(
            client = client,
            booksDir = booksDir,
            descriptor = SourceDescriptor(
                id = sourceId,
                adapterId = SourceAdapterIds.CALIBRE,
                name = sourceId,
                configVersion = 1,
                configJson = calibreSourceConfigJson(baseUrl),
                baseUrl = baseUrl,
                isBuiltin = sourceId == BUILTIN_CALIBRE_SOURCE_ID,
            ),
        )
    }

    private fun <T> ReadflowResult<T>.successValue(): T {
        assertTrue(this is ReadflowResult.Success)
        return (this as ReadflowResult.Success).value
    }
}
