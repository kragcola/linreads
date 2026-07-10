package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ReadflowResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalibreDownloadPlannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun selectsBestFormatByReadingPriority() {
        val meta = CalibreBookMeta(
            id = 42,
            title = "Remote Book",
            authors = listOf("Author"),
            formats = listOf("PDF", "MOBI", "EPUB"),
        )

        val choice = meta.bestDownloadFormat()

        assertEquals(CalibreDownloadFormat("EPUB", BookFormat.EPUB), choice)
    }

    @Test
    fun fallsBackThroughSupportedFormats() {
        val meta = CalibreBookMeta(
            id = 42,
            title = "Remote Book",
            formats = listOf("PDF", "MOBI"),
        )

        val choice = meta.bestDownloadFormat()

        assertEquals(CalibreDownloadFormat("MOBI", BookFormat.MOBI), choice)
    }

    @Test
    fun returnsNullWhenNoReadableFormatExists() {
        val meta = CalibreBookMeta(
            id = 42,
            title = "Remote Book",
            formats = listOf("ZIP", "HTML"),
        )

        assertNull(meta.bestDownloadFormat())
    }

    @Test
    fun downloadsBestFormatIntoPrivateDirectory() = runTest {
        var requestedUrl = ""
        val client = CalibreClient(
            baseUrl = "http://192.168.1.5:8080",
            username = "",
            password = "",
            libraryId = "calibre-library",
            http = defaultCalibreHttpClient(
                MockEngine { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        content = "epub bytes",
                        headers = headersOf(HttpHeaders.ContentLength, "10"),
                    )
                },
            ),
        )
        val downloader = CalibreBookDownloader(
            client = client,
            booksDir = temp.newFolder("books"),
        )
        val meta = CalibreBookMeta(
            id = 42,
            title = "Remote/Book",
            authors = listOf("Author"),
            formats = listOf("PDF", "EPUB"),
        )

        val result = downloader.download(meta)

        assertTrue(result is ReadflowResult.Success<*>)
        val downloaded = (result as ReadflowResult.Success).value
        assertEquals("calibre-42", downloaded.meta.id)
        assertEquals(BookFormat.EPUB, downloaded.meta.format)
        assertEquals("epub bytes", downloaded.file.readText())
        assertEquals("http://192.168.1.5:8080/get/EPUB/42/calibre-library", requestedUrl)
    }

    @Test
    fun downloadMayOutliveConnectionProbeRequestTimeout() = runTest {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    delay(8_001)
                    respond(
                        content = "slow epub bytes",
                        headers = headersOf(HttpHeaders.ContentLength, "15"),
                    )
                }
            },
        )
        val client = CalibreClient(
            baseUrl = "http://192.168.1.5:8080",
            username = "",
            password = "",
            libraryId = "calibre-library",
            http = defaultCalibreHttpClient(engine),
        )
        val downloader = CalibreBookDownloader(
            client = client,
            booksDir = temp.newFolder("slow-books"),
        )

        val result = downloader.download(
            CalibreBookMeta(
                id = 42,
                title = "Slow download",
                formats = listOf("EPUB"),
            ),
        )

        assertTrue(
            "formal download must not inherit the 8 second probe timeout",
            result is ReadflowResult.Success<*>,
        )
    }

    @Test(expected = CancellationException::class)
    fun downloadDoesNotConvertCoroutineCancellationIntoFailure() = runTest {
        val client = CalibreClient(
            baseUrl = "http://192.168.1.5:8080",
            username = "",
            password = "",
            libraryId = "calibre-library",
            http = defaultCalibreHttpClient(
                MockEngine { throw CancellationException("cancelled") },
            ),
        )
        val downloader = CalibreBookDownloader(
            client = client,
            booksDir = temp.newFolder("cancelled-books"),
        )

        downloader.download(
            CalibreBookMeta(
                id = 42,
                title = "Cancelled",
                formats = listOf("EPUB"),
            ),
        )
    }
}
