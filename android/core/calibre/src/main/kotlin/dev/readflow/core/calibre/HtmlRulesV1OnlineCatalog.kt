package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineBookPreview
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.RemoteBookKey
import dev.readflow.extensions.api.SourceAdapterFactory
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.applyCatalogFilter
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

class HtmlRulesV1SourceAdapterFactory(
    private val booksDir: File,
    private val httpFactory: (HtmlSourceRequestPolicy) -> HttpClient = ::defaultHtmlRulesHttpClient,
) : SourceAdapterFactory {
    override val adapterId = SourceAdapterIds.HTML_RULES_V1
    override val latestConfigVersion = 1

    override fun capabilities(configVersion: Int, configJson: String): SourceCapabilities =
        runCatching {
            htmlSourceCapabilities(sourceConfigJson.decodeFromString(HtmlRulesV1Config.serializer(), configJson))
        }.getOrElse {
            htmlSourceCapabilities(config = null)
        }

    override fun validate(configVersion: Int, configJson: String): ReadflowResult<Unit> {
        if (configVersion != latestConfigVersion) {
            return ReadflowResult.Failure(ReadflowError.unsupported("不支持的 HTML 规则版本：$configVersion"))
        }
        return runCatching {
            val config = sourceConfigJson.decodeFromString(HtmlRulesV1Config.serializer(), configJson)
            validateHtmlRulesConfig(config)
            ReadflowResult.Success(Unit)
        }.getOrElse { error ->
            ReadflowResult.Failure(ReadflowError.parse(error.message ?: "HTML 书源规则无效"))
        }
    }

    override fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog> = runCatching {
        val config = descriptor.htmlRulesConfig()
        validateHtmlRulesConfig(config)
        val policy = HtmlSourceRequestPolicy(config.allowedHosts.toSet(), config.allowLanHttp)
        ReadflowResult.Success(
            HtmlRulesV1OnlineCatalog(
                descriptor = descriptor.copy(baseUrl = config.searchUrlTemplate),
                config = config,
                requestPolicy = policy,
                http = httpFactory(policy),
                booksDir = booksDir,
            ),
        )
    }.getOrElse { error ->
        ReadflowResult.Failure(ReadflowError.parse(error.message ?: "HTML 书源规则无效"))
    }
}

class HtmlRulesV1OnlineCatalog(
    override val descriptor: SourceDescriptor,
    private val config: HtmlRulesV1Config,
    private val requestPolicy: HtmlSourceRequestPolicy = HtmlSourceRequestPolicy(
        config.allowedHosts.toSet(),
        config.allowLanHttp,
    ),
    private val http: HttpClient = defaultHtmlRulesHttpClient(requestPolicy),
    private val booksDir: File? = null,
) : OnlineBookCatalog {
    override val capabilities = htmlSourceCapabilities(config, canDownload = booksDir != null)

    override suspend fun search(
        query: String,
        filter: OnlineCatalogFilter,
        offset: Int,
        limit: Int,
    ): ReadflowResult<List<OnlineCatalogEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = renderSearchUrl(
                    config.searchUrlTemplate,
                    query,
                    page = offset / limit.coerceAtLeast(1) + 1,
                )
                val page = fetchHtml(url)
                val entries = parseSearchResults(page.document, page.url)
                    .applyCatalogFilter(filter)
                    .take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
                ReadflowResult.Success(entries)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "HTML 书源搜索失败"))
            }
        }

    override suspend fun download(entry: OnlineCatalogEntry): ReadflowResult<BookMeta> =
        download(entry, HtmlBookDownloadLimits())

    internal suspend fun download(
        entry: OnlineCatalogEntry,
        limits: HtmlBookDownloadLimits,
    ): ReadflowResult<BookMeta> =
        withContext(Dispatchers.IO) {
            val targetDir = booksDir
                ?: return@withContext ReadflowResult.Failure(ReadflowError.io("HTML 书源下载目录未配置"))
            runCatching {
                val detailUrl = entry.detailReference
                    ?: error("搜索结果缺少详情地址")
                entry.remoteKey?.let { key ->
                    require(key.sourceId == descriptor.id) { "搜索结果不属于当前书源" }
                }
                val remoteId = entry.remoteKey?.remoteId ?: detailUrl
                val bookId = stableRemoteBookId(descriptor.id, remoteId)
                val fetchBudget = ByteBudget(limits.maxFetchBytes, "整书 HTML 响应总量")
                val detailPage = fetchHtml(detailUrl, fetchBudget)
                val chapterUrls = chapterUrls(detailPage.document, detailPage.url)
                require(chapterUrls.size <= limits.maxChapters) {
                    "书籍章节超过上限 ${limits.maxChapters}"
                }

                targetDir.mkdirs()
                val outFile = File(targetDir, "$bookId.txt")
                val stagingFile = File.createTempFile("$bookId-", ".part", targetDir)
                try {
                    var outputBytes = 0L
                    stagingFile.outputStream().buffered().use { output ->
                        chapterUrls.forEachIndexed { index, chapterUrl ->
                            val chapter = fetchChapter(chapterUrl, fetchBudget)
                            val section = buildString {
                                chapter.title?.let {
                                    append(it)
                                    append("\n\n")
                                }
                                append(chapter.body)
                                if (index != chapterUrls.lastIndex) append("\n\n")
                            }.toByteArray(Charsets.UTF_8)
                            outputBytes += section.size
                            require(outputBytes <= limits.maxOutputBytes) {
                                "整书 TXT 超过上限 ${limits.maxOutputBytes / 1024 / 1024} MB"
                            }
                            output.write(section)
                        }
                    }
                    try {
                        Files.move(
                            stagingFile.toPath(),
                            outFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            stagingFile.toPath(),
                            outFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                } finally {
                    stagingFile.delete()
                }
                ReadflowResult.Success(
                    entry.meta.copy(
                        id = bookId,
                        format = BookFormat.TXT,
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        localUri = outFile.toURI().toString(),
                    ),
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ReadflowResult.Failure(ReadflowError.io(error.message ?: "HTML 书源下载失败"))
            }
        }

    override suspend fun preview(entry: OnlineCatalogEntry): ReadflowResult<OnlineBookPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                entry.remoteKey?.let { key ->
                    require(key.sourceId == descriptor.id) { "搜索结果不属于当前书源" }
                }
                val detailUrl = entry.detailReference
                    ?: return@withContext ReadflowResult.Failure(ReadflowError.parse("搜索结果缺少详情地址"))
                val detailPage = fetchHtml(detailUrl)
                val chapterUrl = chapterUrls(detailPage.document, detailPage.url).first()
                val chapter = fetchChapter(chapterUrl)
                ReadflowResult.Success(
                    OnlineBookPreview(
                        title = entry.meta.title,
                        author = entry.meta.author,
                        chapterTitle = chapter.title,
                        body = chapter.body,
                    ),
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "正文预览失败"))
            }
        }

    override fun close() {
        http.close()
    }

    private fun parseSearchResults(document: Document, pageUrl: String): List<OnlineCatalogEntry> {
        val items = document.select(config.search.itemSelector)
        require(items.isNotEmpty()) { "搜索规则未匹配到条目：${config.search.itemSelector}" }
        return items.map { item ->
            val title = requiredText(item, config.search.titleSelector, "书名")
            val author = requiredText(item, config.search.authorSelector, "作者")
            val detailElement = item.selectFirst(config.search.detailLinkSelector)
                ?: error("详情链接规则未匹配：${config.search.detailLinkSelector}")
            val href = detailElement.attr("href").takeIf(String::isNotBlank)
                ?: error("详情链接缺少 href")
            val detailUrl = resolveUrl(pageUrl, href)
            requestPolicy.requireAllowed(detailUrl)
            val remoteId = detailUrl
            val localId = stableRemoteBookId(descriptor.id, remoteId)
            val series = config.search.seriesSelector
                ?.let(item::selectFirst)
                ?.text()
                ?.trim()
                ?.takeIf(String::isNotBlank)
            OnlineCatalogEntry(
                meta = BookMeta(
                    id = localId,
                    title = title,
                    author = author,
                    format = BookFormat.TXT,
                    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                ),
                remoteKey = RemoteBookKey(descriptor.id, remoteId),
                series = series,
                availableFormats = emptyList(),
                detailReference = detailUrl,
            )
        }
    }

    private fun chapterUrls(document: Document, pageUrl: String): List<String> {
        val chapterItems = document.select(config.detail.chapterItemSelector)
        require(chapterItems.isNotEmpty()) { "目录规则未匹配到章节：${config.detail.chapterItemSelector}" }
        return chapterItems.map { chapterItem ->
            val link = chapterItem.selectFirst(config.detail.chapterLinkSelector)
                ?: error("章节链接规则未匹配：${config.detail.chapterLinkSelector}")
            val href = link.attr("href").takeIf(String::isNotBlank) ?: error("章节链接缺少 href")
            resolveUrl(pageUrl, href).also(requestPolicy::requireAllowed)
        }.distinct()
    }

    private suspend fun fetchChapter(firstUrl: String, budget: ByteBudget? = null): ParsedChapter {
        var url: String? = firstUrl
        var pageCount = 0
        var title: String? = null
        val bodyParts = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        while (url != null) {
            require(pageCount < MAX_CHAPTER_PAGES) { "章节分页超过上限 $MAX_CHAPTER_PAGES" }
            require(visited.add(url)) { "章节分页形成循环" }
            val page = fetchHtml(url, budget)
            if (title == null) {
                title = config.chapter.titleSelector
                    ?.let(page.document::selectFirst)
                    ?.text()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            val bodyElements = page.document.select(config.chapter.bodySelector)
            require(bodyElements.isNotEmpty()) { "正文规则未匹配：${config.chapter.bodySelector}" }
            val text = sanitizeBody(bodyElements)
            require(text.isNotBlank()) { "正文规则匹配结果为空" }
            bodyParts += text
            pageCount += 1
            url = config.chapter.nextPageSelector
                ?.let(page.document::selectFirst)
                ?.attr("href")
                ?.takeIf(String::isNotBlank)
                ?.let { resolveUrl(page.url, it) }
                ?.also(requestPolicy::requireAllowed)
        }
        return ParsedChapter(title = title, body = bodyParts.joinToString("\n\n"))
    }

    private suspend fun fetchHtml(startUrl: String, budget: ByteBudget? = null): HtmlPage {
        var url = startUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requestPolicy.requireAllowed(url)
            val response = http.prepareGet(url).execute { it.toBufferedResponse(config.charset) }
            budget?.consume(response.byteCount)
            if (response.status in 300..399) {
                require(redirectCount < MAX_REDIRECTS) { "书源重定向超过上限 $MAX_REDIRECTS" }
                val location = response.location ?: error("书源重定向缺少 Location")
                url = resolveUrl(url, location)
                return@repeat
            }
            require(response.status in 200..299) { "书源请求失败：HTTP ${response.status}" }
            response.contentType?.let { type ->
                require(type.contains("html", ignoreCase = true) || type.startsWith("text/", ignoreCase = true)) {
                    "书源返回的不是 HTML：$type"
                }
            }
            return HtmlPage(url, Jsoup.parse(response.body, url))
        }
        error("书源重定向超过上限 $MAX_REDIRECTS")
    }

    private data class ParsedChapter(val title: String?, val body: String)
    private data class HtmlPage(val url: String, val document: Document)
}

internal data class HtmlBookDownloadLimits(
    val maxChapters: Int = MAX_BOOK_CHAPTERS,
    val maxFetchBytes: Long = MAX_HTML_BOOK_FETCH_BYTES,
    val maxOutputBytes: Long = MAX_HTML_BOOK_OUTPUT_BYTES,
) {
    init {
        require(maxChapters in 1..MAX_BOOK_CHAPTERS)
        require(maxFetchBytes in 1..MAX_HTML_BOOK_FETCH_BYTES)
        require(maxOutputBytes in 1..MAX_HTML_BOOK_OUTPUT_BYTES)
    }
}

private class ByteBudget(
    private val maxBytes: Long,
    private val label: String,
) {
    private var consumedBytes = 0L

    fun consume(bytes: Int) {
        consumedBytes += bytes
        require(consumedBytes <= maxBytes) { "$label 超过上限 ${maxBytes / 1024 / 1024} MB" }
    }
}

private fun htmlSourceCapabilities(
    config: HtmlRulesV1Config?,
    canDownload: Boolean = true,
) = SourceCapabilities(
    canSearch = true,
    canFilterByAuthor = true,
    canFilterBySeries = !config?.search?.seriesSelector.isNullOrBlank(),
    canPreviewText = true,
    canDownload = canDownload,
    canBatchAcrossSource = config?.searchUrlTemplate?.contains("{page}") == true,
)

class HtmlSourceRequestPolicy(
    allowedHosts: Set<String>,
    private val allowLanHttp: Boolean,
    private val resolveHost: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    private val allowedHosts = allowedHosts.map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()
    private val pinnedAddresses = ConcurrentHashMap<String, List<InetAddress>>()

    init {
        require(this.allowedHosts.isNotEmpty()) { "allowedHosts 不能为空" }
        require(this.allowedHosts.none { it.contains('*') || it.contains('/') }) { "allowedHosts 不支持通配符或路径" }
    }

    fun requireAllowed(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: error("书源地址格式不正确")
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "书源只支持 HTTP 或 HTTPS GET" }
        require(uri.userInfo == null) { "书源地址不得包含凭据" }
        val host = uri.host?.lowercase()?.takeIf(String::isNotBlank) ?: error("书源地址缺少主机")
        require(host in allowedHosts) { "书源请求主机未获授权：$host" }
        val addresses = pinnedAddresses.computeIfAbsent(host) {
            resolveHost(host).toList()
        }
        require(addresses.isNotEmpty()) { "无法解析书源主机：$host" }
        require(addresses.none { it.isAnyLocalAddress || it.isMulticastAddress || it.isLinkLocalAddress || it.isMetadataAddress() }) {
            "书源请求解析到禁止的地址"
        }
        val hasPrivateAddress = addresses.any(InetAddress::isPrivateAddress)
        require(!hasPrivateAddress || addresses.all(InetAddress::isPrivateAddress)) {
            "书源请求解析到混合的公网和私网地址"
        }
        if (scheme == "http") {
            require(allowLanHttp && addresses.all(InetAddress::isPrivateAddress)) {
                "HTTP 仅允许已显式授权的局域网书源"
            }
        } else if (!allowLanHttp) {
            require(!hasPrivateAddress) { "公网 HTTPS 书源不得访问私网或本机地址" }
        }
    }

    internal fun addressesForTransport(host: String): List<InetAddress> {
        val normalized = host.trim().lowercase()
        require(normalized in allowedHosts) { "书源请求主机未获授权：$normalized" }
        return pinnedAddresses[normalized]
            ?: throw UnknownHostException("书源请求未经过地址策略校验：$normalized")
    }
}

internal fun renderSearchUrl(template: String, query: String, page: Int): String {
    require(template.contains("{query}")) { "搜索地址模板必须包含 {query}" }
    require(template.replace("{query}", "").replace("{page}", "").none { it == '{' || it == '}' }) {
        "搜索地址模板包含不支持的变量"
    }
    val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
    return template.replace("{query}", encodedQuery).replace("{page}", page.toString())
}

private fun validateHtmlRulesConfig(config: HtmlRulesV1Config) {
    require(config.allowedHosts.isNotEmpty()) { "allowedHosts 不能为空" }
    require(config.searchUrlTemplate.contains("{query}")) { "搜索地址模板必须包含 {query}" }
    require(Charset.isSupported(config.charset)) { "不支持的字符编码：${config.charset}" }
    listOf(
        config.search.itemSelector,
        config.search.titleSelector,
        config.search.authorSelector,
        config.search.detailLinkSelector,
        config.detail.chapterItemSelector,
        config.detail.chapterLinkSelector,
        config.chapter.bodySelector,
    ).forEach { selector ->
        require(selector.isNotBlank()) { "HTML selector 不能为空" }
        Jsoup.parse("").select(selector)
    }
    config.search.seriesSelector?.let { Jsoup.parse("").select(it) }
    config.chapter.titleSelector?.let { Jsoup.parse("").select(it) }
    config.chapter.nextPageSelector?.let { Jsoup.parse("").select(it) }
    val sampleUrl = renderSearchUrl(config.searchUrlTemplate, "test", 1)
    val uri = URI(sampleUrl)
    require(uri.host?.lowercase() in config.allowedHosts.map(String::lowercase)) {
        "搜索地址主机必须包含在 allowedHosts 中"
    }
    require(uri.scheme.equals("https", ignoreCase = true) || config.allowLanHttp) {
        "HTTP HTML 书源必须显式开启 allowLanHttp"
    }
}

private fun requiredText(item: Element, selector: String, fieldName: String): String =
    item.selectFirst(selector)?.text()?.trim()?.takeIf(String::isNotBlank)
        ?: error("$fieldName 规则未匹配：$selector")

private fun sanitizeBody(elements: Iterable<Element>): String = elements.mapNotNull { element ->
    val copy = element.clone()
    copy.select("script,style,noscript,iframe,object,embed,form").remove()
    val cleaned = Jsoup.clean(
        copy.html(),
        Safelist.none().addTags("p", "br", "div", "section", "article", "h1", "h2", "h3", "blockquote", "li"),
    )
    Jsoup.parseBodyFragment(cleaned).body().wholeText().trim().takeIf(String::isNotBlank)
}.joinToString("\n\n")

private fun resolveUrl(baseUrl: String, reference: String): String = URI(baseUrl).resolve(reference).toString()

private data class BufferedHttpResponse(
    val status: Int,
    val location: String?,
    val contentType: String?,
    val body: String,
    val byteCount: Int,
)

private suspend fun HttpResponse.toBufferedResponse(charsetName: String): BufferedHttpResponse {
    val body = readLimited(bodyAsChannel(), Charset.forName(charsetName))
    return BufferedHttpResponse(
        status = status.value,
        location = headers[HttpHeaders.Location],
        contentType = headers[HttpHeaders.ContentType],
        body = body.text,
        byteCount = body.byteCount,
    )
}

private suspend fun readLimited(channel: ByteReadChannel, charset: Charset): LimitedBody {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    return ByteArrayOutputStream().use { output ->
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read == -1) break
            require(output.size() + read <= MAX_HTML_RESPONSE_BYTES) {
                "HTML 响应过大，最大支持 ${MAX_HTML_RESPONSE_BYTES / 1024} KB"
            }
            output.write(buffer, 0, read)
        }
        val bytes = output.toByteArray()
        LimitedBody(text = bytes.toString(charset), byteCount = bytes.size)
    }
}

private data class LimitedBody(val text: String, val byteCount: Int)

private fun defaultHtmlRulesHttpClient(policy: HtmlSourceRequestPolicy): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            policy.addressesForTransport(hostname)
                    },
                )
                followRedirects(false)
                followSslRedirects(false)
            }
        }
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000L
            requestTimeoutMillis = 15_000L
            socketTimeoutMillis = 15_000L
        }
    }

private fun InetAddress.isMetadataAddress(): Boolean = hostAddress == "169.254.169.254"

private fun InetAddress.isPrivateAddress(): Boolean =
    isLoopbackAddress || isSiteLocalAddress ||
        (address.size == 16 && (address[0].toInt() and 0xfe) == 0xfc)

private const val MAX_HTML_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_REDIRECTS = 5
private const val MAX_CHAPTER_PAGES = 3
private const val MAX_SEARCH_RESULTS = 200
private const val MAX_BOOK_CHAPTERS = 500
private const val MAX_HTML_BOOK_FETCH_BYTES = 64L * 1024 * 1024
private const val MAX_HTML_BOOK_OUTPUT_BYTES = 64L * 1024 * 1024
