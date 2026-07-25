package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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

    @Test
    fun metadataRequestsAreBatchedAndResultsKeepCalibreSearchOrder() = runTest {
        val requestedPaths = mutableListOf<String>()
        var requestedIds: String? = null
        val ids = (1..12).toList()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/ajax/search" -> respond(
                    content = """{"total_num":12,"book_ids":${ids.joinToString(prefix = "[", postfix = "]")}}""",
                    headers = JSON_HEADERS,
                )
                "/ajax/books" -> {
                    requestedIds = request.url.parameters["ids"]
                    respond(
                        content = ids.reversed().joinToString(prefix = "{", postfix = "}") { id ->
                            """"$id":{"id":$id,"title":"Book $id","authors":["Author"],"formats":["EPUB"]}"""
                        },
                        headers = JSON_HEADERS,
                    )
                }
                else -> error("metadata must use the Calibre batch endpoint: ${request.url}")
            }
        }
        val catalog = catalog("source-a", engine = engine)

        val entries = catalog.search("").successValue()

        assertEquals(listOf("/ajax/search", "/ajax/books"), requestedPaths)
        assertEquals(ids.joinToString(","), requestedIds)
        assertEquals(ids.map { "Book $it" }, entries.map { it.meta.title })
        catalog.close()
    }

    @Test
    fun missingBatchMetadataSkipsOnlyTheMissingBook() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/ajax/search" -> respond(
                    content = """{"total_num":2,"book_ids":[1,2],"library_id":"books"}""",
                    headers = JSON_HEADERS,
                )
                "/ajax/books" -> respond(
                    content = """{"1":{"id":1,"title":"Available","formats":["EPUB"]},"2":null}""",
                    headers = JSON_HEADERS,
                )
                else -> error("unexpected request: ${request.url}")
            }
        }
        val catalog = catalog("source-a", engine = engine)

        val result = catalog.search("")

        assertTrue(result is ReadflowResult.Success)
        assertEquals(listOf("Available"), (result as ReadflowResult.Success).value.map { it.meta.title })
        catalog.close()
    }

    @Test
    fun explicitLibraryIdUsesEncodedQualifiedRoutesAndIsNotOverriddenBySearch() = runTest {
        val requestedPaths = mutableListOf<String>()
        val libraryId = "Sci Fi/中文"
        val encodedLibraryId = "Sci%20Fi%2F%E4%B8%AD%E6%96%87"
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/ajax/search/$encodedLibraryId" -> respond(
                    content = """{"total_num":1,"book_ids":[42],"library_id":"books"}""",
                    headers = JSON_HEADERS,
                )
                "/ajax/books/$encodedLibraryId" -> respond(
                    content = """{"42":{"id":42,"title":"Custom Library","formats":["EPUB"]}}""",
                    headers = JSON_HEADERS,
                )
                else -> error("unexpected request: ${request.url}")
            }
        }
        val baseUrl = "http://192.168.1.5:8080"
        val client = CalibreClient(
            baseUrl = baseUrl,
            username = "",
            password = "",
            libraryId = libraryId,
            http = defaultCalibreHttpClient(engine, allowedBaseUrl = baseUrl),
        )

        val search = client.search("")
        val metadata = client.bookMetas(search.book_ids)

        assertEquals(
            listOf(
                "/ajax/search/$encodedLibraryId",
                "/ajax/books/$encodedLibraryId",
            ),
            requestedPaths,
        )
        assertEquals("Custom Library", metadata.getValue(42).title)
        assertEquals("$baseUrl/get/EPUB/42/$encodedLibraryId", client.downloadUrl(42, "EPUB"))
        assertEquals("$baseUrl/get/cover/42/$encodedLibraryId", client.coverUrl(42))
        client.close()
    }

    @Test
    fun serverReportedLibraryIdRoutesMetadataCoverAndDownload() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/ajax/search" -> respond(
                    content = """{"total_num":1,"book_ids":[42],"library_id":"books"}""",
                    headers = JSON_HEADERS,
                )
                "/ajax/books" -> respond(
                    content = """{"42":{"id":42,"title":"Shared","authors":["Author"],"formats":["EPUB"]}}""",
                    headers = JSON_HEADERS,
                )
                else -> error("unexpected request: ${request.url}")
            }
        }
        val catalog = catalog("source-a", engine = engine)

        val entry = catalog.search("").successValue().single()

        assertEquals(listOf("/ajax/search", "/ajax/books"), requestedPaths)
        assertEquals(
            "http://192.168.1.5:8080/get/EPUB/42/books",
            entry.downloadUrl,
        )
        assertTrue(entry.previewUrl?.contains("/get/cover/42/books") == true)
        catalog.close()
    }

    @Test
    fun batchMetadataUsesMapKeyWhenCalibreOmitsEmbeddedId() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/ajax/search" -> respond(
                    content = """{"total_num":1,"book_ids":[42],"library_id":"books"}""",
                    headers = JSON_HEADERS,
                )
                "/ajax/books" -> respond(
                    content = """{"42":{"application_id":999,"title":"Shared","authors":["Author"],"formats":["EPUB"]}}""",
                    headers = JSON_HEADERS,
                )
                else -> error("unexpected request: ${request.url}")
            }
        }
        val catalog = catalog("source-a", engine = engine)

        val result = catalog.search("")

        assertTrue("real Calibre metadata failed: $result", result is ReadflowResult.Success)
        val entry = (result as ReadflowResult.Success).value.single()
        assertEquals("Shared", entry.meta.title)
        assertEquals("42", entry.remoteKey?.remoteId)
        catalog.close()
    }

    @Test
    fun singleMetadataUsesRequestIdWhenCalibreOmitsEmbeddedId() = runTest {
        val baseUrl = "http://192.168.1.5:8080"
        val client = CalibreClient(
            baseUrl = baseUrl,
            username = "",
            password = "",
            libraryId = "books",
            http = defaultCalibreHttpClient(
                engine = MockEngine { request ->
                    assertEquals("/ajax/book/42/books", request.url.encodedPath)
                    respond(
                        content = """{"application_id":999,"title":"Shared","formats":["EPUB"]}""",
                        headers = JSON_HEADERS,
                    )
                },
                allowedBaseUrl = baseUrl,
            ),
        )

        val metadata = client.bookMeta(42)

        assertEquals(42, metadata.id)
        assertEquals("Shared", metadata.title)
        client.close()
    }

    @Test
    fun reopenedCatalogRediscoversLibraryIdBeforeDownload() = runTest {
        val searchCatalog = catalog("source-a", engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/ajax/search" -> respond(
                    content = """{"total_num":1,"book_ids":[42],"library_id":"books"}""",
                    headers = JSON_HEADERS,
                )
                "/ajax/books" -> respond(
                    content = """{"42":{"id":42,"title":"Shared","authors":["Author"],"formats":["EPUB"]}}""",
                    headers = JSON_HEADERS,
                )
                else -> error("unexpected search request: ${request.url}")
            }
        })
        val entry = searchCatalog.search("").successValue().single()
        searchCatalog.close()

        val requestedPaths = mutableListOf<String>()
        val downloadCatalog = catalog(
            sourceId = "source-a",
            booksDir = tempFolder.newFolder("rediscovered-library-download"),
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/ajax/search" -> respond(
                        content = """{"total_num":1,"book_ids":[42],"library_id":"books"}""",
                        headers = JSON_HEADERS,
                    )
                    "/ajax/book/42/books" -> respond(
                        content = """{"application_id":42,"title":"Shared","authors":["Author"],"formats":["EPUB"]}""",
                        headers = JSON_HEADERS,
                    )
                    "/get/EPUB/42/books" -> respond(
                        content = "epub bytes",
                        headers = headersOf(HttpHeaders.ContentLength, "10"),
                    )
                    "/ajax/book/42/calibre-library" -> respondError(HttpStatusCode.BadGateway)
                    else -> error("unexpected download request: ${request.url}")
                }
            },
        )

        val result = downloadCatalog.download(entry)

        assertTrue("reopened catalog download failed: $result", result is ReadflowResult.Success)
        assertEquals(
            listOf("/ajax/search", "/ajax/book/42/books", "/get/EPUB/42/books"),
            requestedPaths,
        )
        downloadCatalog.close()
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
            """{"42":{"id":42,"title":"Shared","authors":["Author"],"formats":["EPUB"]}}"""
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
