package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenericHttpOnlineCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun searchParsesJsonCatalogAndAppliesQueryFilter() = runTest {
        val base = "http://192.168.1.40:8080/catalog.json"
        val body = """
            {
              "books": [
                {
                  "id": "a1",
                  "title": "Alpha",
                  "author": "Ann",
                  "format": "epub",
                  "series": "Saga",
                  "tags": ["scifi"],
                  "downloadUrl": "http://192.168.1.40:8080/files/a1.epub",
                  "coverUrl": "http://192.168.1.40:8080/covers/a1.jpg"
                },
                {
                  "id": "b2",
                  "title": "Beta",
                  "author": "Bob",
                  "format": "pdf",
                  "downloadUrl": "http://192.168.1.40:8080/files/b2.pdf"
                }
              ]
            }
        """.trimIndent()
        val catalog = catalog(
            kind = SourceKind.JSON_HTTP,
            baseUrl = base,
            engine = MockEngine { request ->
                assertEquals(base, request.url.toString())
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

        val result = catalog.search("alpha")
        assertTrue(result is ReadflowResult.Success)
        val entries = (result as ReadflowResult.Success).value
        assertEquals(1, entries.size)
        val expectedId = stableRemoteBookId("src-test", "a1")
        assertEquals(expectedId, entries.single().meta.id)
        assertEquals("Saga", entries.single().series)
        assertEquals("http://192.168.1.40:8080/covers/a1.jpg", entries.single().meta.coverUrl)
        catalog.close()
    }

    @Test
    fun searchStripsUnsafeCoverAndPreviewUrls() = runTest {
        val base = "http://192.168.1.40:8080/catalog.json"
        val body = """
            {
              "books": [
                {
                  "id": "unsafe-cover",
                  "title": "Unsafe",
                  "author": "Ann",
                  "format": "epub",
                  "downloadUrl": "http://192.168.1.40:8080/files/u.epub",
                  "coverUrl": "http://example.com/evil.jpg",
                  "previewUrl": "http://192.168.1.99:8080/other.jpg"
                }
              ]
            }
        """.trimIndent()
        val catalog = catalog(
            kind = SourceKind.JSON_HTTP,
            baseUrl = base,
            engine = MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

        val result = catalog.search("")
        assertTrue(result is ReadflowResult.Success)
        val entry = (result as ReadflowResult.Success).value.single()
        assertNull(entry.meta.coverUrl)
        assertNull(entry.previewUrl)
        catalog.close()
    }

    @Test
    fun searchRejectsOversizedCatalogBeforeParsing() = runTest {
        val base = "http://192.168.1.40:8080/catalog.json"
        val oversizedBody = " ".repeat(8 * 1024 * 1024 + 1)
        val catalog = catalog(
            kind = SourceKind.JSON_HTTP,
            baseUrl = base,
            engine = MockEngine {
                respond(
                    oversizedBody,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val result = catalog.search("")

        assertTrue(result is ReadflowResult.Failure)
        assertTrue(
            (result as ReadflowResult.Failure).error.message.contains("目录响应过大"),
        )
        catalog.close()
    }

    @Test
    fun searchAndDownloadShareStableBookId() = runTest {
        val base = "http://192.168.1.42:8080/catalog.json"
        val downloadUrl = "http://192.168.1.42:8080/files/ok.epub"
        val body = """
            {
              "books": [
                {
                  "id": "ok",
                  "title": "OK",
                  "author": "A",
                  "format": "epub",
                  "downloadUrl": "$downloadUrl"
                }
              ]
            }
        """.trimIndent()
        val booksDir = tempFolder.newFolder("books-id-align")
        val catalog = catalog(
            kind = SourceKind.JSON_HTTP,
            baseUrl = base,
            booksDir = booksDir,
            engine = MockEngine { request ->
                when (request.url.toString()) {
                    downloadUrl -> respond(
                        "epub-bytes".toByteArray(),
                        headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"),
                    )
                    else -> respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )

        val search = catalog.search("") as ReadflowResult.Success
        val entry = search.value.single()
        val expected = stableRemoteBookId("src-test", "ok")
        assertEquals(expected, entry.meta.id)

        val downloaded = catalog.download(entry) as ReadflowResult.Success
        assertEquals(expected, downloaded.value.id)
        assertEquals(DownloadStatus.DOWNLOADED, downloaded.value.downloadStatus)
        catalog.close()
    }

    @Test
    fun opdsPrefersEpubAcquisitionOverPdf() = runTest {
        val base = "http://192.168.1.50:8080/opds"
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Mixed Formats</title>
                <id>urn:book:mixed</id>
                <author><name>Author</name></author>
                <link rel="http://opds-spec.org/acquisition"
                      href="/files/mixed.pdf"
                      type="application/pdf"/>
                <link rel="http://opds-spec.org/acquisition"
                      href="/files/mixed.epub"
                      type="application/epub+zip"/>
                <link rel="http://opds-spec.org/image"
                      href="/covers/mixed.jpg"
                      type="image/jpeg"/>
              </entry>
            </feed>
        """.trimIndent()
        val catalog = catalog(
            kind = SourceKind.OPDS,
            baseUrl = base,
            engine = MockEngine {
                respond(feed, headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
            },
        )

        val result = catalog.search("")
        assertTrue(
            "OPDS search should succeed, got: $result",
            result is ReadflowResult.Success,
        )
        val entry = (result as ReadflowResult.Success).value.single()
        assertEquals("http://192.168.1.50:8080/files/mixed.epub", entry.downloadUrl)
        assertEquals(BookFormat.EPUB, entry.meta.format)
        assertEquals(stableRemoteBookId("src-test", "urn:book:mixed"), entry.meta.id)
        assertEquals("http://192.168.1.50:8080/covers/mixed.jpg", entry.meta.coverUrl)
        catalog.close()
    }

    @Test
    fun selectPreferredOpdsAcquisitionRanksEpubAbovePdfAndOctetStream() {
        val pdf = OpdsAcquisitionCandidate(
            url = "http://192.168.1.1/a.pdf",
            type = "application/pdf",
            formatHint = "pdf",
        )
        val octet = OpdsAcquisitionCandidate(
            url = "http://192.168.1.1/a.bin",
            type = "application/octet-stream",
            formatHint = null,
        )
        val epub = OpdsAcquisitionCandidate(
            url = "http://192.168.1.1/a.epub",
            type = "application/epub+zip",
            formatHint = "epub",
        )
        assertEquals(epub, selectPreferredOpdsAcquisition(listOf(pdf, octet, epub)))
        assertEquals(pdf, selectPreferredOpdsAcquisition(listOf(octet, pdf)))
    }

    @Test
    fun downloadFailureCleansStagingAndDoesNotCommitShelfFile() = runTest {
        val base = "http://192.168.1.41:8080/catalog.json"
        val downloadUrl = "http://192.168.1.41:8080/files/broken.epub"
        val booksDir = tempFolder.newFolder("books")
        val catalog = catalog(
            kind = SourceKind.JSON_HTTP,
            baseUrl = base,
            booksDir = booksDir,
            engine = MockEngine { request ->
                when (request.url.toString()) {
                    downloadUrl -> respondError(HttpStatusCode.InternalServerError)
                    else -> respond("[]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        val entry = OnlineCatalogEntry(
            meta = BookMeta(
                id = "broken",
                title = "Broken",
                author = "X",
                format = BookFormat.EPUB,
            ),
            downloadUrl = downloadUrl,
        )

        val result = catalog.download(entry)
        assertTrue(result is ReadflowResult.Failure)
        assertEquals(0, booksDir.listFiles()?.count { it.extension == "epub" } ?: 0)
        assertEquals(0, booksDir.listFiles()?.count { it.name.endsWith(".part") } ?: 0)
        catalog.close()
    }

    @Test
    fun downloadCommitsAtomicallyOnSuccess() = runTest {
        val base = "http://192.168.1.42:8080/catalog.json"
        val downloadUrl = "http://192.168.1.42:8080/files/ok.epub"
        val booksDir = tempFolder.newFolder("books-ok")
        val catalog = catalog(
            kind = SourceKind.JSON_HTTP,
            baseUrl = base,
            booksDir = booksDir,
            engine = MockEngine { request ->
                when (request.url.toString()) {
                    downloadUrl -> respond(
                        "epub-bytes".toByteArray(),
                        headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"),
                    )
                    else -> respond("[]")
                }
            },
        )
        val entry = OnlineCatalogEntry(
            meta = BookMeta(id = "ok", title = "OK", author = "A", format = BookFormat.EPUB),
            downloadUrl = downloadUrl,
        )

        val result = catalog.download(entry)
        assertTrue(result is ReadflowResult.Success)
        val meta = (result as ReadflowResult.Success).value
        assertEquals(DownloadStatus.DOWNLOADED, meta.downloadStatus)
        assertEquals(stableRemoteBookId("src-test", "ok"), meta.id)
        assertTrue(meta.localUri != null)
        val committed = booksDir.listFiles()?.filter { it.extension == "epub" }.orEmpty()
        assertEquals(1, committed.size)
        assertFalse(booksDir.listFiles()?.any { it.name.endsWith(".part") } == true)
        catalog.close()
    }

    @Test
    fun previewUrlRejectsCrossOriginAndPublicHttp() = runTest {
        val base = "http://192.168.1.43:8080/catalog.json"
        val catalog = catalog(
            kind = SourceKind.JSON_HTTP,
            baseUrl = base,
            engine = MockEngine { respond("[]") },
        )

        val crossOrigin = catalog.previewUrl(
            OnlineCatalogEntry(
                meta = BookMeta(id = "1", title = "t", author = "a", format = BookFormat.EPUB),
                previewUrl = "http://192.168.1.99:8080/cover.jpg",
            ),
        )
        assertTrue(crossOrigin is ReadflowResult.Failure)

        val publicHttp = catalog.previewUrl(
            OnlineCatalogEntry(
                meta = BookMeta(id = "2", title = "t", author = "a", format = BookFormat.EPUB),
                previewUrl = "http://example.com/cover.jpg",
            ),
        )
        assertTrue(publicHttp is ReadflowResult.Failure)

        val safe = catalog.previewUrl(
            OnlineCatalogEntry(
                meta = BookMeta(id = "3", title = "t", author = "a", format = BookFormat.EPUB),
                previewUrl = "http://192.168.1.43:8080/cover.jpg",
            ),
        )
        assertTrue(safe is ReadflowResult.Success)
        assertEquals("http://192.168.1.43:8080/cover.jpg", (safe as ReadflowResult.Success).value)
        catalog.close()
    }

    @Test
    fun stableRemoteBookIdSanitizesSourceAndRemoteIds() {
        val id = stableRemoteBookId("source/with spaces!", "remote@id/1")
        assertTrue(id.startsWith("remote-"))
        assertFalse(id.contains(" "))
        assertFalse(id.contains("@"))
        assertFalse(id.contains("/"))
        assertTrue(id.matches(Regex("remote-[a-zA-Z0-9_-]+-[a-zA-Z0-9_-]+-[0-9a-f]{8}")))
    }

    @Test
    fun stableRemoteBookIdCollisionResistantForLongRemoteIds() {
        val prefix = "y".repeat(50)
        val a = stableRemoteBookId("src", prefix + "one")
        val b = stableRemoteBookId("src", prefix + "two")
        assertNotEquals(a, b)
        assertEquals(a.substringAfterLast("-").length, 8)
    }

    @Test
    fun sanitizeCatalogMediaUrlDropsUnsafeUrls() {
        val base = "http://192.168.1.40:8080/catalog.json"
        assertNull(sanitizeCatalogMediaUrl("http://example.com/x.jpg", base))
        assertNull(sanitizeCatalogMediaUrl("http://192.168.1.99:8080/x.jpg", base))
        assertEquals(
            "http://192.168.1.40:8080/covers/x.jpg",
            sanitizeCatalogMediaUrl("http://192.168.1.40:8080/covers/x.jpg", base),
        )
    }

    private fun catalog(
        kind: SourceKind,
        baseUrl: String,
        booksDir: File = tempFolder.root,
        engine: MockEngine,
    ) = GenericHttpOnlineCatalog(
        descriptor = SourceDescriptor(
            id = "src-test",
            kind = kind,
            name = "Test",
            baseUrl = baseUrl,
        ),
        booksDir = booksDir,
        // Mirror production client: non-2xx must fail so error bodies are never committed.
        http = HttpClient(engine) { expectSuccess = true },
    )
}
