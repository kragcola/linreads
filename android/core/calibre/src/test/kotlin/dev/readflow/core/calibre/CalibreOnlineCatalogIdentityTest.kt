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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
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

    @Test
    fun metadataRequestsAreBoundedAndResultsKeepCalibreSearchOrder() = runTest {
        val activeRequests = AtomicInteger(0)
        val peakRequests = AtomicInteger(0)
        val ids = (1..12).toList()
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/ajax/search") {
                respond(
                    content = """{"total_num":12,"book_ids":${ids.joinToString(prefix = "[", postfix = "]")}}""",
                    headers = JSON_HEADERS,
                )
            } else {
                val id = request.url.encodedPath.substringAfter("/ajax/book/").substringBefore('/').toInt()
                val active = activeRequests.incrementAndGet()
                peakRequests.accumulateAndGet(active, ::maxOf)
                try {
                    delay((13 - id) * 5L)
                    respond(
                        content = """{"id":$id,"title":"Book $id","authors":["Author"],"formats":["EPUB"]}""",
                        headers = JSON_HEADERS,
                    )
                } finally {
                    activeRequests.decrementAndGet()
                }
            }
        }
        val catalog = catalog("source-a", engine = engine)

        val entries = catalog.search("").successValue()

        assertTrue("metadata requests should overlap", peakRequests.get() > 1)
        assertTrue(
            "metadata concurrency must stay bounded",
            peakRequests.get() <= CalibreOnlineCatalog.METADATA_REQUEST_CONCURRENCY,
        )
        assertEquals(ids.map { "Book $it" }, entries.map { it.meta.title })
        catalog.close()
    }

    private fun catalog(
        sourceId: String,
        booksDir: java.io.File? = null,
        engine: MockEngine = defaultEngine(),
    ): CalibreOnlineCatalog {
        val baseUrl = "http://192.168.1.5:8080"
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

    private fun defaultEngine() = MockEngine { request ->
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

    private fun <T> ReadflowResult<T>.successValue(): T {
        assertTrue(this is ReadflowResult.Success)
        return (this as ReadflowResult.Success).value
    }

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
