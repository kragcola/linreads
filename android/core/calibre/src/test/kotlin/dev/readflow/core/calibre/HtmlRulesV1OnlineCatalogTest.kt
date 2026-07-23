package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.OnlineBookPreview
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.RemoteBookKey
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.concurrent.thread

class HtmlRulesV1OnlineCatalogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val publicHost = "203.0.113.8"
    private val baseUrl = "https://$publicHost"

    @Test
    fun searchThenPreviewResolvesRelativeLinksEncodesQueryAndSanitizesChapter() = runTest {
        val requested = mutableListOf<String>()
        val catalog = catalog(
            engine = MockEngine { request ->
                requested += request.url.toString()
                when (request.url.encodedPath) {
                    "/search" -> respondHtml(
                        """
                        <ul><li class="book">
                          <a class="detail" href="/book/1"><span class="title">三体</span></a>
                          <span class="author">刘慈欣</span><span class="series">地球往事</span>
                        </li></ul>
                        """.trimIndent(),
                    )
                    "/book/1" -> respondHtml(
                        "<ol><li class='chapter'><a href='../chapter/1'>第一章</a></li></ol>",
                    )
                    "/chapter/1" -> respondHtml(
                        """
                        <h1 class="chapter-title">科学边界</h1>
                        <article class="content"><script>alert('x')</script><p>第一段。</p><p>第二段。</p></article>
                        """.trimIndent(),
                    )
                    else -> error("Unexpected request: ${request.url}")
                }
            },
        )

        val search = catalog.search("三 体")
        assertTrue(search is ReadflowResult.Success)
        val entry = (search as ReadflowResult.Success).value.single()
        assertEquals("三体", entry.meta.title)
        assertEquals("刘慈欣", entry.meta.author)
        assertEquals("地球往事", entry.series)
        assertEquals("$baseUrl/book/1", entry.detailReference)

        val preview = catalog.preview(entry)
        assertTrue(preview is ReadflowResult.Success)
        val content = (preview as ReadflowResult.Success<OnlineBookPreview>).value
        assertEquals("科学边界", content.chapterTitle)
        assertTrue(content.body.contains("第一段。"))
        assertTrue(content.body.contains("第二段。"))
        assertFalse(content.body.contains("alert"))
        assertTrue(requested.first().contains("q=%E4%B8%89%20%E4%BD%93"))
        catalog.close()
    }

    @Test
    fun previewRejectsEntryFromAnotherSourceBeforeNetworkRequest() = runTest {
        var requested = false
        val catalog = catalog(
            engine = MockEngine {
                requested = true
                respondHtml("<article class='content'>unexpected</article>")
            },
        )
        val detailUrl = "$baseUrl/book/1"
        val foreignEntry = entry(detailUrl).copy(
            remoteKey = RemoteBookKey("source-other", detailUrl),
        )

        val result = catalog.preview(foreignEntry)

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("不属于当前书源"))
        assertFalse(requested)
        catalog.close()
    }

    @Test
    fun searchMovesSynchronousDnsWorkOffCallerThread() = runTest {
        val uiDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "html-ui")
        }.asCoroutineDispatcher()
        var dnsThreadName: String? = null
        val config = rules()
        val policy = HtmlSourceRequestPolicy(
            allowedHosts = config.allowedHosts.toSet(),
            allowLanHttp = false,
            resolveHost = {
                dnsThreadName = Thread.currentThread().name
                listOf(InetAddress.getByName(publicHost))
            },
        )
        val descriptor = SourceDescriptor(
            id = "source-html",
            adapterId = SourceAdapterIds.HTML_RULES_V1,
            name = "Fixture",
            configVersion = 1,
            configJson = htmlRulesV1ConfigJson(config),
            baseUrl = config.searchUrlTemplate,
        )
        val catalog = HtmlRulesV1OnlineCatalog(
            descriptor = descriptor,
            config = config,
            requestPolicy = policy,
            http = HttpClient(
                MockEngine {
                    respondHtml(
                        "<li class='book'><a class='detail' href='/book/1'>" +
                            "<b class='title'>T</b></a><i class='author'>A</i></li>",
                    )
                },
            ) {
                expectSuccess = false
                followRedirects = false
            },
        )

        try {
            val result = withContext(uiDispatcher) { catalog.search("q") }

            assertTrue(result is ReadflowResult.Success)
            assertFalse("DNS must not run on the caller/UI thread", dnsThreadName == "html-ui")
        } finally {
            catalog.close()
            uiDispatcher.close()
        }
    }

    @Test
    fun downloadWritesAllChaptersAsAtomicSourceScopedTxt() = runTest {
        val booksDir = tempFolder.newFolder("html-download")
        val config = rules()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/search" -> respondHtml(
                    """
                    <li class="book">
                      <a class="detail" href="/book/1"><span class="title">三体</span></a>
                      <span class="author">刘慈欣</span>
                    </li>
                    """.trimIndent(),
                )
                "/book/1" -> respondHtml(
                    """
                    <ol>
                      <li class="chapter"><a href="/chapter/1">第一章</a></li>
                      <li class="chapter"><a href="/chapter/2">第二章</a></li>
                    </ol>
                    """.trimIndent(),
                )
                "/chapter/1" -> respondHtml(
                    "<h1 class='chapter-title'>第一章</h1><article class='content'><p>第一段。</p></article>",
                )
                "/chapter/2" -> respondHtml(
                    "<h1 class='chapter-title'>第二章</h1><article class='content'><script>bad()</script><p>第二段。</p></article>",
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val descriptor = SourceDescriptor(
            id = "source-html",
            adapterId = SourceAdapterIds.HTML_RULES_V1,
            name = "Fixture",
            configVersion = 1,
            configJson = htmlRulesV1ConfigJson(config),
            baseUrl = config.searchUrlTemplate,
        )
        val factory = HtmlRulesV1SourceAdapterFactory(
            booksDir = booksDir,
            httpFactory = {
                HttpClient(engine) {
                    expectSuccess = false
                    followRedirects = false
                }
            },
        )
        val catalog = (factory.open(descriptor) as ReadflowResult.Success).value

        val entry = (catalog.search("三体") as ReadflowResult.Success).value.single()
        val result = catalog.download(entry)

        assertTrue(result is ReadflowResult.Success)
        val downloaded = (result as ReadflowResult.Success).value
        val expectedId = stableRemoteBookId("source-html", "$baseUrl/book/1")
        assertEquals(expectedId, downloaded.id)
        assertEquals(BookFormat.TXT, downloaded.format)
        assertEquals(DownloadStatus.DOWNLOADED, downloaded.downloadStatus)
        val file = java.io.File(java.net.URI(requireNotNull(downloaded.localUri)))
        assertEquals(
            "第一章\n\n第一段。\n\n第二章\n\n第二段。",
            file.readText(),
        )
        assertFalse(file.readText().contains("bad"))
        assertFalse(booksDir.listFiles()?.any { it.name.endsWith(".part") } == true)
        catalog.close()
    }

    @Test
    fun downloadRejectsExcessiveChapterCountWithoutArtifacts() = runTest {
        val booksDir = tempFolder.newFolder("html-chapter-limit")
        val detailUrl = "$baseUrl/book/too-many"
        val chapterList = (1..501).joinToString(separator = "") { index ->
            "<li class='chapter'><a href='/chapter/$index'>Chapter $index</a></li>"
        }
        val catalog = catalog(
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/book/too-many" -> respondHtml("<ol>$chapterList</ol>")
                    else -> error("Unexpected request: ${request.url}")
                }
            },
            booksDir = booksDir,
        )

        try {
            val result = catalog.download(entry(detailUrl))

            assertTrue(result is ReadflowResult.Failure)
            assertTrue((result as ReadflowResult.Failure).error.message.contains("章节超过上限"))
            assertNoDownloadArtifacts(booksDir, detailUrl)
        } finally {
            catalog.close()
        }
    }

    @Test
    fun downloadAggregateFetchLimitDeletesStagingFile() = runTest {
        val booksDir = tempFolder.newFolder("html-fetch-limit")
        val detailUrl = "$baseUrl/book/fetch-limit"
        val catalog = catalog(
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/book/fetch-limit" -> respondHtml(
                        "<ol><li class='chapter'><a href='/chapter/large'>Chapter</a></li></ol>",
                    )
                    "/chapter/large" -> respondHtml(
                        "<article class='content'>${"x".repeat(512)}</article>",
                    )
                    else -> error("Unexpected request: ${request.url}")
                }
            },
            booksDir = booksDir,
        )

        try {
            val result = catalog.download(
                entry(detailUrl),
                HtmlBookDownloadLimits(maxFetchBytes = 256),
            )

            assertTrue(result is ReadflowResult.Failure)
            assertTrue((result as ReadflowResult.Failure).error.message.contains("整书 HTML 响应总量"))
            assertNoDownloadArtifacts(booksDir, detailUrl)
        } finally {
            catalog.close()
        }
    }

    @Test
    fun downloadChapterPageLimitDeletesStagingFile() = runTest {
        val booksDir = tempFolder.newFolder("html-page-limit")
        val detailUrl = "$baseUrl/book/paged"
        val requestedPaths = mutableListOf<String>()
        val config = rules().copy(
            chapter = rules().chapter.copy(nextPageSelector = ".next"),
        )
        val catalog = catalog(
            config = config,
            engine = MockEngine { request ->
                requestedPaths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/book/paged" -> respondHtml(
                        "<ol><li class='chapter'><a href='/chapter/1'>Chapter</a></li></ol>",
                    )
                    "/chapter/1", "/chapter/2", "/chapter/3" -> {
                        val page = request.url.encodedPath.substringAfterLast('/').toInt()
                        respondHtml(
                            "<article class='content'>Page $page</article>" +
                                "<a class='next' href='/chapter/${page + 1}'>Next</a>",
                        )
                    }
                    else -> error("Unexpected request: ${request.url}")
                }
            },
            booksDir = booksDir,
        )

        try {
            val result = catalog.download(entry(detailUrl))

            assertTrue(result is ReadflowResult.Failure)
            assertTrue((result as ReadflowResult.Failure).error.message.contains("章节分页超过上限"))
            assertFalse(requestedPaths.contains("/chapter/4"))
            assertNoDownloadArtifacts(booksDir, detailUrl)
        } finally {
            catalog.close()
        }
    }

    @Test
    fun downloadCancellationPropagatesAndDeletesStagingFile() = runTest {
        val booksDir = tempFolder.newFolder("html-download-cancel")
        val detailUrl = "$baseUrl/book/cancel"
        val catalog = catalog(
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/book/cancel" -> respondHtml(
                        "<ol><li class='chapter'><a href='/chapter/cancel'>Chapter</a></li></ol>",
                    )
                    "/chapter/cancel" -> throw CancellationException("cancel fixture")
                    else -> error("Unexpected request: ${request.url}")
                }
            },
            booksDir = booksDir,
        )

        try {
            val thrown = runCatching { catalog.download(entry(detailUrl)) }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
            assertNoDownloadArtifacts(booksDir, detailUrl)
        } finally {
            catalog.close()
        }
    }

    @Test
    fun missingRequiredSelectorFailsClosed() = runTest {
        val catalog = catalog(
            config = rules().copy(search = rules().search.copy(titleSelector = ".missing")),
            engine = MockEngine { respondHtml("<li class='book'><a class='detail' href='/book/1'></a><i class='author'>A</i></li>") },
        )

        val result = catalog.search("query")

        assertTrue(result is ReadflowResult.Failure)
        assertTrue((result as ReadflowResult.Failure).error.message.contains("书名"))
        catalog.close()
    }

    @Test
    fun crossHostDetailAndRedirectAreRejected() = runTest {
        val crossHostCatalog = catalog(
            engine = MockEngine {
                respondHtml(
                    "<li class='book'><a class='detail' href='https://203.0.113.9/book/1'><b class='title'>T</b></a><i class='author'>A</i></li>",
                )
            },
        )
        assertTrue(crossHostCatalog.search("q") is ReadflowResult.Failure)
        crossHostCatalog.close()

        val redirectCatalog = catalog(
            engine = MockEngine {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://203.0.113.9/search?q=q"),
                )
            },
        )
        assertTrue(redirectCatalog.search("q") is ReadflowResult.Failure)
        redirectCatalog.close()
    }

    @Test
    fun publicSourceCannotResolveToPrivateOrMetadataAddress() {
        val privatePolicy = HtmlSourceRequestPolicy(
            allowedHosts = setOf("books.example"),
            allowLanHttp = false,
            resolveHost = { listOf(InetAddress.getByName("127.0.0.1")) },
        )
        assertTrue(runCatching { privatePolicy.requireAllowed("https://books.example/search") }.isFailure)

        val ipv6PrivatePolicy = HtmlSourceRequestPolicy(
            allowedHosts = setOf("books.example"),
            allowLanHttp = false,
            resolveHost = { listOf(InetAddress.getByName("fd00::1")) },
        )
        assertTrue(runCatching { ipv6PrivatePolicy.requireAllowed("https://books.example/search") }.isFailure)

        val metadataPolicy = HtmlSourceRequestPolicy(
            allowedHosts = setOf("169.254.169.254"),
            allowLanHttp = true,
        )
        assertTrue(runCatching { metadataPolicy.requireAllowed("http://169.254.169.254/latest") }.isFailure)
    }

    @Test
    fun mixedPublicAndPrivateDnsAnswersFailClosed() {
        val policy = HtmlSourceRequestPolicy(
            allowedHosts = setOf("books.example"),
            allowLanHttp = false,
            resolveHost = {
                listOf(
                    InetAddress.getByName("203.0.113.8"),
                    InetAddress.getByName("10.0.0.8"),
                )
            },
        )

        val thrown = runCatching { policy.requireAllowed("https://books.example/search") }.exceptionOrNull()

        assertTrue(thrown?.message?.contains("混合的公网和私网地址") == true)
    }

    @Test
    fun transportUsesTheAddressValidatedByPolicy() = runTest {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(name = "html-source-rebinding-fixture") {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val body = """
                        <li class='book'>
                          <a class='detail' href='/book/1'><b class='title'>T</b></a>
                          <i class='author'>A</i>
                        </li>
                    """.trimIndent().toByteArray()
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=UTF-8\r\n")
                        append("Content-Length: ${body.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    socket.getOutputStream().use { output ->
                        output.write(headers)
                        output.write(body)
                    }
                }
            }
        }
        val config = rules().copy(
            searchUrlTemplate = "http://localhost:${server.localPort}/search?q={query}",
            allowedHosts = listOf("localhost"),
            allowLanHttp = true,
        )
        val policy = HtmlSourceRequestPolicy(
            allowedHosts = setOf("localhost"),
            allowLanHttp = true,
            resolveHost = { listOf(InetAddress.getByName("127.0.0.2")) },
        )
        val descriptor = SourceDescriptor(
            id = "source-rebinding",
            adapterId = SourceAdapterIds.HTML_RULES_V1,
            name = "Rebinding fixture",
            configVersion = 1,
            configJson = htmlRulesV1ConfigJson(config),
            baseUrl = config.searchUrlTemplate,
        )
        val catalog = HtmlRulesV1OnlineCatalog(
            descriptor = descriptor,
            config = config,
            requestPolicy = policy,
        )

        try {
            val result = catalog.search("q")

            assertTrue("transport must not resolve a different address than policy", result is ReadflowResult.Failure)
        } finally {
            catalog.close()
            server.close()
            serverThread.join(1_000L)
        }
    }

    private fun catalog(
        config: HtmlRulesV1Config = rules(),
        engine: MockEngine,
        booksDir: java.io.File? = null,
    ): HtmlRulesV1OnlineCatalog {
        val descriptor = SourceDescriptor(
            id = "source-html",
            adapterId = SourceAdapterIds.HTML_RULES_V1,
            name = "Fixture",
            configVersion = 1,
            configJson = htmlRulesV1ConfigJson(config),
            baseUrl = config.searchUrlTemplate,
        )
        val policy = HtmlSourceRequestPolicy(config.allowedHosts.toSet(), allowLanHttp = false)
        return HtmlRulesV1OnlineCatalog(
            descriptor = descriptor,
            config = config,
            requestPolicy = policy,
            http = HttpClient(engine) {
                expectSuccess = false
                followRedirects = false
            },
            booksDir = booksDir,
        )
    }

    private fun entry(detailUrl: String) = OnlineCatalogEntry(
        meta = BookMeta(
            id = stableRemoteBookId("source-html", detailUrl),
            title = "Fixture",
            author = "Author",
            format = BookFormat.TXT,
        ),
        remoteKey = RemoteBookKey("source-html", detailUrl),
        detailReference = detailUrl,
    )

    private fun assertNoDownloadArtifacts(booksDir: java.io.File, detailUrl: String) {
        val bookId = stableRemoteBookId("source-html", detailUrl)
        assertFalse(java.io.File(booksDir, "$bookId.txt").exists())
        assertFalse(booksDir.listFiles()?.any { it.name.endsWith(".part") } == true)
    }

    private fun rules() = HtmlRulesV1Config(
        searchUrlTemplate = "$baseUrl/search?q={query}&page={page}",
        allowedHosts = listOf(publicHost),
        search = HtmlSearchRules(
            itemSelector = ".book",
            titleSelector = ".title",
            authorSelector = ".author",
            detailLinkSelector = ".detail",
            seriesSelector = ".series",
        ),
        detail = HtmlDetailRules(
            chapterItemSelector = ".chapter",
            chapterLinkSelector = "a",
        ),
        chapter = HtmlChapterRules(
            titleSelector = ".chapter-title",
            bodySelector = ".content",
        ),
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondHtml(body: String) = respond(
        content = body,
        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=UTF-8"),
    )
}
