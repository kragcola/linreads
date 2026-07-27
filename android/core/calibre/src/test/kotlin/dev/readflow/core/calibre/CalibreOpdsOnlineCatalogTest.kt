package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.stableRemoteBookId
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalibreOpdsOnlineCatalogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun browseFollowsServerTitleNavigationAndPaginationWithoutAjax() = runTest {
        val requested = mutableListOf<String>()
        val catalog = catalog(
            engine = MockEngine { request ->
                requested += request.url.toString()
                when {
                    request.url.encodedPath == "/opds" -> respond(ROOT_FEED, headers = atomHeaders)
                    request.url.encodedPath == TITLE_PATH && request.url.parameters["offset"] == "1" -> {
                        respond(secondPublicationFeed(), headers = atomHeaders)
                    }
                    request.url.encodedPath == TITLE_PATH ->
                        respond(firstPublicationFeed(next = "$TITLE_PATH?offset=1"), headers = atomHeaders)
                    else -> error("unexpected request: ${request.url}")
                }
            },
        )

        val result = catalog.browsePage(offset = 0, limit = 100)

        assertTrue("browse failed: $result", result is ReadflowResult.Success)
        val page = (result as ReadflowResult.Success).value
        assertEquals(listOf("First book", "Second book"), page.entries.map { it.meta.title })
        assertEquals(
            listOf(
                stableRemoteBookId(SOURCE_ID, "42"),
                stableRemoteBookId(SOURCE_ID, "43"),
            ),
            page.entries.map { it.meta.id },
        )
        assertEquals(listOf("42", "43"), page.entries.map { it.remoteKey?.remoteId })
        assertEquals("A Series", page.entries.first().series)
        assertEquals(listOf("one", "two"), page.entries.first().tags)
        assertEquals(BookFormat.EPUB, page.entries.first().meta.format)
        assertTrue(page.entries.first().meta.coverUrl.orEmpty().contains(CALIBRE_COVER_SOURCE_QUERY_PARAMETER))
        assertTrue(page.entries.first().meta.coverUrl.orEmpty().contains("/get/cover/42/books"))
        assertFalse(page.entries.first().meta.coverUrl.orEmpty().contains("/get/thumb/"))
        assertEquals(2, page.nextOffset)
        assertFalse(page.hasMore)
        assertEquals("Otitle navigation must be selected", TITLE_PATH, java.net.URI(requested[1]).path)
        assertTrue(requested.none { "/ajax/" in it })
        assertTrue(requested.none { NEWEST_PATH in it })
        catalog.close()
    }

    @Test
    fun failedNextPageCanRetryWithoutReplayingSuccessfulFeeds() = runTest {
        val requestedPaths = mutableListOf<String>()
        var nextAttempts = 0
        val catalog = catalog(
            engine = MockEngine { request ->
                val pathAndQuery = request.url.encodedPath + request.url.encodedQuery.let {
                    query -> if (query.isBlank()) "" else "?$query"
                }
                requestedPaths += pathAndQuery
                when {
                    request.url.encodedPath == "/opds" -> respond(ROOT_FEED, headers = atomHeaders)
                    request.url.encodedPath == TITLE_PATH && request.url.parameters["offset"] == "1" -> {
                        nextAttempts += 1
                        if (nextAttempts == 1) respondError(HttpStatusCode.BadGateway)
                        else respond(secondPublicationFeed(), headers = atomHeaders)
                    }
                    request.url.encodedPath == TITLE_PATH ->
                        respond(firstPublicationFeed(next = "$TITLE_PATH?offset=1"), headers = atomHeaders)
                    else -> error("unexpected request: ${request.url}")
                }
            },
        )

        val first = catalog.browsePage(offset = 0, limit = 1)
        val failedNext = catalog.browsePage(offset = 1, limit = 1)
        val retriedNext = catalog.browsePage(offset = 1, limit = 1)

        assertTrue(first is ReadflowResult.Success)
        assertTrue(failedNext is ReadflowResult.Failure)
        assertTrue("retry failed: $retriedNext", retriedNext is ReadflowResult.Success)
        assertEquals(
            listOf("Second book"),
            (retriedNext as ReadflowResult.Success).value.entries.map { it.meta.title },
        )
        assertEquals(1, requestedPaths.count { it == "/opds" })
        assertEquals(1, requestedPaths.count { it == TITLE_PATH })
        assertEquals(2, requestedPaths.count { it == "$TITLE_PATH?offset=1" })
        catalog.close()
    }

    @Test
    fun searchFollowsOpdsTemplateAndEncodesAWholePathSegment() = runTest {
        val requestedPaths = mutableListOf<String>()
        val catalog = catalog(
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/opds" -> respond(ROOT_FEED, headers = atomHeaders)
                    else -> respond(secondPublicationFeed(), headers = atomHeaders)
                }
            },
        )

        val result = catalog.searchPage("A/B 中", offset = 0, limit = 10)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(2, requestedPaths.size)
        assertTrue(requestedPaths.last().startsWith("/opds/search/"))
        assertTrue("slash must remain inside one encoded search segment", "%2F" in requestedPaths.last())
        assertTrue(requestedPaths.none { "/ajax/" in it })
        catalog.close()
    }

    @Test
    fun explicitLibraryIdIsSentOnlyAsAnOpdsQueryParameter() = runTest {
        val libraryId = "A&B+C/% 中文"
        val requested = mutableListOf<String>()
        val catalog = catalog(
            libraryId = libraryId,
            engine = MockEngine { request ->
                requested += request.url.toString()
                assertEquals("/opds", request.url.encodedPath)
                assertEquals(libraryId, request.url.parameters["library_id"])
                respond(firstPublicationFeed(next = null), headers = atomHeaders)
            },
        )

        val result = catalog.browsePage(offset = 0, limit = 1)

        assertTrue("browse failed: $result", result is ReadflowResult.Success)
        assertEquals(1, requested.size)
        assertTrue(requested.none { "/ajax/" in it })
        catalog.close()
    }

    @Test
    fun calibre95AcquisitionMimeTypesAndGetPathsMapToRecognizedFormats() = runTest {
        val catalog = catalog(
            engine = MockEngine { request ->
                assertEquals("/opds", request.url.encodedPath)
                respond(calibre95FormatsFeed(), headers = atomHeaders)
            },
        )

        val result = catalog.browsePage(offset = 0, limit = 10)

        assertTrue("browse failed: $result", result is ReadflowResult.Success)
        val entries = (result as ReadflowResult.Success).value.entries
        assertEquals(
            listOf(
                BookFormat.AZW3,
                BookFormat.MOBI,
                BookFormat.TXT,
                BookFormat.MD,
                BookFormat.DOCX,
                BookFormat.CBZ,
            ),
            entries.map { it.meta.format },
        )
        assertEquals(
            listOf("azw3", "mobi", "txt", "md", "docx", "cbz"),
            entries.map { it.availableFormats.single() },
        )
        catalog.close()
    }

    @Test
    fun mixedOpenableAndUnopenableAcquisitionsSelectsOpenableFormat() = runTest {
        val books = tempFolder.newFolder("mixed-openable-books")
        val requestedPaths = mutableListOf<String>()
        val catalog = catalog(
            booksDir = books,
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/opds" -> respond(mixedAzw3AndPdfFeed(), headers = atomHeaders)
                    "/get/pdf/51/books" -> respond(
                        "pdf-content".toByteArray(),
                        headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
                    )
                    else -> error("unexpected request: ${request.url}")
                }
            },
        )

        val entry = (catalog.browsePage(offset = 0, limit = 1) as ReadflowResult.Success)
            .value
            .entries
            .single()

        assertEquals(BookFormat.PDF, entry.meta.format)
        assertEquals("$BASE_URL/get/pdf/51/books", entry.downloadUrl)
        val result = catalog.download(entry)

        assertTrue("download failed: $result", result is ReadflowResult.Success)
        assertEquals(BookFormat.PDF, (result as ReadflowResult.Success).value.format)
        assertEquals(listOf("/opds", "/get/pdf/51/books"), requestedPaths)
        catalog.close()
    }

    @Test
    fun unopenableEntriesRemainVisibleButDownloadsFailBeforeNetworkOrFileCreation() = runTest {
        val books = tempFolder.newFolder("unopenable-format-books")
        val requestedPaths = mutableListOf<String>()
        val catalog = catalog(
            booksDir = books,
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/opds" -> respond(unopenableFormatsFeed(), headers = atomHeaders)
                    else -> respond("unsupported-content".toByteArray())
                }
            },
        )

        val entries = (catalog.browsePage(offset = 0, limit = 10) as ReadflowResult.Success)
            .value
            .entries

        assertEquals(
            listOf(BookFormat.AZW3, BookFormat.MOBI, BookFormat.DOCX, BookFormat.CBZ),
            entries.map { it.meta.format },
        )
        val results = entries.map { catalog.download(it) }

        assertEquals("downloads must be rejected before network", listOf("/opds"), requestedPaths)
        assertTrue("downloads must not create files", books.listFiles().orEmpty().isEmpty())
        results.forEach { result ->
            assertTrue("download unexpectedly succeeded: $result", result is ReadflowResult.Failure)
            assertEquals(ReadflowError.Kind.UNSUPPORTED, (result as ReadflowResult.Failure).error.kind)
        }
        catalog.close()
    }

    @Test
    fun mixedUnknownOctetAndTxtUsesTxtAcquisitionForDownload() = runTest {
        val books = tempFolder.newFolder("mixed-acquisition-books")
        val requestedPaths = mutableListOf<String>()
        val catalog = catalog(
            booksDir = books,
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/opds" -> respond(mixedUnknownOctetAndTxtFeed(), headers = atomHeaders)
                    "/get/txt/50/books" -> respond(
                        "plain-text-content".toByteArray(),
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                    else -> error("unexpected request: ${request.url}")
                }
            },
        )

        val entry = (catalog.browsePage(offset = 0, limit = 1) as ReadflowResult.Success)
            .value
            .entries
            .single()

        assertEquals("$BASE_URL/get/txt/50/books", entry.downloadUrl)
        assertEquals(BookFormat.TXT, entry.meta.format)
        val result = catalog.download(entry)

        assertTrue("download failed: $result", result is ReadflowResult.Success)
        val meta = (result as ReadflowResult.Success).value
        assertEquals(BookFormat.TXT, meta.format)
        assertEquals("plain-text-content", File(books, "${meta.id}.txt").readText())
        assertEquals(listOf("/opds", "/get/txt/50/books"), requestedPaths)
        catalog.close()
    }

    @Test
    fun unknownAcquisitionFailsBeforeNetworkAndNeverCreatesBin() = runTest {
        val books = tempFolder.newFolder("unknown-format-books")
        val requestedPaths = mutableListOf<String>()
        val catalog = catalog(
            booksDir = books,
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/opds" -> respond(unknownFormatFeed(), headers = atomHeaders)
                    else -> respond("unknown-bytes".toByteArray())
                }
            },
        )

        val entry = (catalog.browsePage(offset = 0, limit = 1) as ReadflowResult.Success)
            .value
            .entries
            .single()
        assertEquals(BookFormat.UNKNOWN, entry.meta.format)

        val result = catalog.download(entry)

        assertTrue("download unexpectedly succeeded: $result", result is ReadflowResult.Failure)
        assertEquals(ReadflowError.Kind.UNSUPPORTED, (result as ReadflowResult.Failure).error.kind)
        assertEquals(listOf("/opds"), requestedPaths)
        assertTrue(books.listFiles().orEmpty().isEmpty())
        catalog.close()
    }

    @Test
    fun downloadDoesNotBorrowFormatFromAnotherAcquisition() = runTest {
        val books = tempFolder.newFolder("mismatched-acquisition-books")
        val requestedPaths = mutableListOf<String>()
        val catalog = catalog(
            booksDir = books,
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/opds" -> respond(unknownFormatFeed(), headers = atomHeaders)
                    else -> respond("unknown-bytes".toByteArray())
                }
            },
        )
        val unknownEntry = (catalog.browsePage(offset = 0, limit = 1) as ReadflowResult.Success)
            .value
            .entries
            .single()

        val result = catalog.download(unknownEntry.copy(availableFormats = listOf("txt")))

        assertTrue("download unexpectedly succeeded: $result", result is ReadflowResult.Failure)
        assertEquals(ReadflowError.Kind.UNSUPPORTED, (result as ReadflowResult.Failure).error.kind)
        assertEquals(listOf("/opds"), requestedPaths)
        assertTrue(books.listFiles().orEmpty().isEmpty())
        catalog.close()
    }

    @Test
    fun builtinDownloadKeepsLegacyNumericIdentityAndCommitsAtomically() = runTest {
        val books = tempFolder.newFolder("builtin-books")
        val catalog = catalog(
            sourceId = BUILTIN_CALIBRE_SOURCE_ID,
            booksDir = books,
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/opds" -> respond(ROOT_FEED, headers = atomHeaders)
                    TITLE_PATH -> respond(firstPublicationFeed(next = null), headers = atomHeaders)
                    "/get/epub/42/books" -> respond(
                        "epub-bytes".toByteArray(),
                        headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"),
                    )
                    else -> error("unexpected request: ${request.url}")
                }
            },
        )

        val entry = (catalog.browsePage(offset = 0, limit = 1) as ReadflowResult.Success).value.entries.single()
        assertEquals("42", entry.meta.id)
        val result = catalog.download(entry)

        assertTrue("download failed: $result", result is ReadflowResult.Success)
        val meta = (result as ReadflowResult.Success).value
        assertEquals("calibre-42", meta.id)
        assertEquals(BookFormat.EPUB, meta.format)
        assertEquals(DownloadStatus.DOWNLOADED, meta.downloadStatus)
        assertTrue(File(books, "calibre-42.epub").isFile)
        assertTrue(books.listFiles().orEmpty().none { it.name.endsWith(".part") })
        catalog.close()
    }

    @Test
    fun localStagingFailureIsReportedAsIoWithoutStartingDownload() = runTest {
        val booksPath = tempFolder.newFile("books-path-is-a-file")
        val requestedPaths = mutableListOf<String>()
        val catalog = catalog(
            booksDir = booksPath,
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/opds" -> respond(firstPublicationFeed(next = null), headers = atomHeaders)
                    else -> respond("epub-bytes".toByteArray())
                }
            },
        )
        val entry = (catalog.browsePage(offset = 0, limit = 1) as ReadflowResult.Success)
            .value
            .entries
            .single()

        val result = catalog.download(entry)

        assertTrue("download unexpectedly succeeded: $result", result is ReadflowResult.Failure)
        assertEquals(ReadflowError.Kind.IO, (result as ReadflowResult.Failure).error.kind)
        assertEquals(listOf("/opds"), requestedPaths)
        catalog.close()
    }

    @Test
    fun downloadHttpFailureRemainsNetworkErrorAndCleansStaging() = runTest {
        val books = tempFolder.newFolder("http-failure-books")
        val catalog = catalog(
            booksDir = books,
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/opds" -> respond(firstPublicationFeed(next = null), headers = atomHeaders)
                    "/get/epub/42/books" -> respondError(HttpStatusCode.BadGateway)
                    else -> error("unexpected request: ${request.url}")
                }
            },
        )
        val entry = (catalog.browsePage(offset = 0, limit = 1) as ReadflowResult.Success)
            .value
            .entries
            .single()

        val result = catalog.download(entry)

        assertTrue("download unexpectedly succeeded: $result", result is ReadflowResult.Failure)
        val error = (result as ReadflowResult.Failure).error
        assertEquals(ReadflowError.Kind.NETWORK, error.kind)
        assertEquals(HttpStatusCode.BadGateway.value, error.code)
        assertTrue(error.message.contains("phase=opds_download"))
        assertTrue(books.listFiles().orEmpty().isEmpty())
        catalog.close()
    }

    @Test
    fun crossOriginCoverIsDiscardedBeforeAddingTheCredentialMarker() = runTest {
        val catalog = catalog(
            engine = MockEngine {
                respond(
                    firstPublicationFeed(next = null).replace(
                        "/get/cover/42/books",
                        "https://attacker.example/private-cover?token=secret",
                    ),
                    headers = atomHeaders,
                )
            },
        )

        val result = catalog.browsePage(offset = 0, limit = 1)

        assertTrue(result is ReadflowResult.Success)
        val entry = (result as ReadflowResult.Success).value.entries.single()
        assertNull(entry.meta.coverUrl)
        assertNull(entry.previewUrl)
        catalog.close()
    }

    @Test
    fun gatewayDiagnosticContainsOnlyPhaseOriginAndStatus() = runTest {
        val secretQuery = "private search phrase"
        val catalog = catalog(
            baseUrl = "https://reader.tailnet.ts.net/calibre",
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/calibre/opds" -> respond(
                        ROOT_FEED.replace("/opds/", "/calibre/opds/"),
                        headers = atomHeaders,
                    )
                    else -> respondError(HttpStatusCode.BadGateway)
                }
            },
        )

        val result = catalog.searchPage(secretQuery, offset = 0, limit = 10)

        assertTrue(result is ReadflowResult.Failure)
        val error = (result as ReadflowResult.Failure).error
        assertEquals(502, error.code)
        assertEquals(
            "Calibre 请求收到 HTTP 502 " +
                "[phase=opds_search origin=https://reader.tailnet.ts.net status=502]。" +
                "该响应可能来自当前端点或中间代理",
            error.message,
        )
        assertFalse(error.message.contains(secretQuery))
        assertFalse(error.message.contains("/calibre/opds/search"))
        assertFalse(error.message.contains("已到达服务器"))
        catalog.close()
    }

    private fun catalog(
        baseUrl: String = BASE_URL,
        sourceId: String = SOURCE_ID,
        libraryId: String = "calibre-library",
        booksDir: File = tempFolder.root,
        engine: MockEngine,
    ): CalibreOpdsOnlineCatalog {
        val descriptor = SourceDescriptor(
            id = sourceId,
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson(baseUrl, libraryId),
            baseUrl = baseUrl,
            isBuiltin = sourceId == BUILTIN_CALIBRE_SOURCE_ID,
        )
        return CalibreOpdsOnlineCatalog(
            descriptor = descriptor,
            booksDir = booksDir,
            http = defaultCalibreHttpClient(engine = engine, allowedBaseUrl = baseUrl),
        )
    }

    private fun firstPublicationFeed(next: String?): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre-all:title</id>
          <title>Books</title>
          ${next?.let { "<link rel=\"next\" href=\"$it\" type=\"application/atom+xml\"/>" }.orEmpty()}
          <entry>
            <id>urn:uuid:atom-identity-must-not-win</id>
            <title>First book</title>
            <author><name>First Author</name></author>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">标签：one, two<br/>丛书：A Series [2]</div></content>
            <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/get/pdf/42/books"/>
            <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/get/epub/42/books"/>
            <link rel="http://opds-spec.org/thumbnail" type="image/jpeg" href="/get/thumb/42/books"/>
            <link rel="http://opds-spec.org/cover" type="image/jpeg" href="/get/cover/42/books"/>
          </entry>
        </feed>
    """.trimIndent()

    private fun secondPublicationFeed(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre-all:title:2</id>
          <title>Books</title>
          <entry>
            <id>urn:uuid:second</id>
            <title>Second book</title>
            <author><name>Second Author</name></author>
            <link rel="http://opds-spec.org/acquisition" type="application/x-mobipocket-ebook" href="/get/mobi/43/books"/>
          </entry>
        </feed>
    """.trimIndent()

    private fun calibre95FormatsFeed(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre-formats</id>
          <title>Formats</title>
          <entry><id>azw3</id><title>AZW3</title><link rel="http://opds-spec.org/acquisition" type="application/x-mobi8-ebook" href="/get/azw3/44/books"/></entry>
          <entry><id>mobi</id><title>MOBI</title><link rel="http://opds-spec.org/acquisition" type="application/x-mobipocket-ebook" href="/get/mobi/45/books"/></entry>
          <entry><id>txt</id><title>TXT</title><link rel="http://opds-spec.org/acquisition" type="text/plain" href="/get/txt/46/books"/></entry>
          <entry><id>md</id><title>MD</title><link rel="http://opds-spec.org/acquisition" type="text/x-markdown" href="/get/md/47/books"/></entry>
          <entry><id>docx</id><title>DOCX</title><link rel="http://opds-spec.org/acquisition" type="application/vnd.openxmlformats-officedocument.wordprocessingml.document" href="/get/docx/48/books"/></entry>
          <entry><id>cbz</id><title>CBZ</title><link rel="http://opds-spec.org/acquisition" type="application/x-cbz" href="/get/cbz/49/books"/></entry>
        </feed>
    """.trimIndent()

    private fun mixedUnknownOctetAndTxtFeed(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre-mixed-formats</id>
          <title>Formats</title>
          <entry>
            <id>mixed</id>
            <title>Mixed</title>
            <link rel="http://opds-spec.org/acquisition" type="application/octet-stream" href="/get/zip/50/books"/>
            <link rel="http://opds-spec.org/acquisition" type="text/plain" href="/get/txt/50/books"/>
          </entry>
        </feed>
    """.trimIndent()

    private fun mixedAzw3AndPdfFeed(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre-mixed-openable-formats</id>
          <title>Formats</title>
          <entry>
            <id>mixed-openable</id>
            <title>Mixed openable</title>
            <link rel="http://opds-spec.org/acquisition" type="application/x-mobi8-ebook" href="/get/azw3/51/books"/>
            <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/get/pdf/51/books"/>
          </entry>
        </feed>
    """.trimIndent()

    private fun unopenableFormatsFeed(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre-unopenable-formats</id>
          <title>Formats</title>
          <entry><id>azw3-only</id><title>AZW3 only</title><link rel="http://opds-spec.org/acquisition" type="application/x-mobi8-ebook" href="/get/azw3/52/books"/></entry>
          <entry><id>mobi-only</id><title>MOBI only</title><link rel="http://opds-spec.org/acquisition" type="application/x-mobipocket-ebook" href="/get/mobi/53/books"/></entry>
          <entry><id>docx-only</id><title>DOCX only</title><link rel="http://opds-spec.org/acquisition" type="application/vnd.openxmlformats-officedocument.wordprocessingml.document" href="/get/docx/54/books"/></entry>
          <entry><id>cbz-only</id><title>CBZ only</title><link rel="http://opds-spec.org/acquisition" type="application/x-cbz" href="/get/cbz/55/books"/></entry>
        </feed>
    """.trimIndent()

    private fun unknownFormatFeed(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre-unknown-format</id>
          <title>Formats</title>
          <entry>
            <id>unknown</id>
            <title>Unknown</title>
            <link rel="http://opds-spec.org/acquisition" type="application/x-unknown" href="/get/unknown/99/books"/>
          </entry>
        </feed>
    """.trimIndent()

    private companion object {
        const val BASE_URL = "http://100.101.102.103:8080"
        const val SOURCE_ID = "source-calibre"
        const val NEWEST_PATH = "/opds/navcatalog/4f6e6577657374"
        const val TITLE_PATH = "/opds/navcatalog/4f7469746c65"
        const val ROOT_FEED = """<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <id>urn:calibre:main</id>
              <title>calibre library</title>
              <link rel="search" href="/opds/search/{searchTerms}" type="application/atom+xml"/>
              <entry><id>newest</id><title>Localized newest</title><link href="$NEWEST_PATH" type="application/atom+xml;profile=opds-catalog"/></entry>
              <entry><id>title</id><title>Localized title</title><link href="$TITLE_PATH" type="application/atom+xml;profile=opds-catalog"/></entry>
            </feed>"""
        val atomHeaders = headersOf(HttpHeaders.ContentType, "application/atom+xml")
    }
}
