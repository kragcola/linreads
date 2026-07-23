package dev.readflow.core.calibre

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import dev.readflow.extensions.api.applyCatalogFilter
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
/**
 * Generic user-configured catalog: OPDS Atom feed and/or JSON HTTP list.
 * No site-specific scrapers — only well-known open formats the user points at.
 *
 * JSON shape (array or `{ "books": [...] }`):
 * ```
 * { "id","title","author","format","series","tags","downloadUrl","coverUrl","previewUrl" }
 * ```
 */
class GenericHttpOnlineCatalog(
    override val descriptor: SourceDescriptor,
    private val booksDir: File,
    private val http: HttpClient = defaultGenericSourceHttpClient(descriptor.baseUrl),
) : OnlineBookCatalog {

    override suspend fun search(
        query: String,
        filter: OnlineCatalogFilter,
        offset: Int,
        limit: Int,
    ): ReadflowResult<List<OnlineCatalogEntry>> =
        runCatching {
            requireAllowedCalibreRequestUrl(descriptor.baseUrl)
            val body = http.prepareGet(descriptor.baseUrl).execute { response ->
                readCatalogBody(response.bodyAsChannel())
            }
            val entries = when (descriptor.kind) {
                SourceKind.OPDS -> parseOpdsFeed(body, descriptor)
                SourceKind.JSON_HTTP -> parseJsonCatalog(body, descriptor)
                SourceKind.CALIBRE -> error("Use CalibreOnlineCatalog for Calibre")
            }
            val q = query.trim()
            val filtered = entries
                .filter { entry ->
                    q.isEmpty() ||
                        entry.meta.title.contains(q, ignoreCase = true) ||
                        entry.meta.author.contains(q, ignoreCase = true) ||
                        entry.series?.contains(q, ignoreCase = true) == true ||
                        entry.tags.any { it.contains(q, ignoreCase = true) }
                }
                .applyCatalogFilter(filter)
                .drop(offset.coerceAtLeast(0))
                .take(limit.coerceAtLeast(1))
            ReadflowResult.Success(filtered)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "书源请求失败"))
        }

    override suspend fun download(entry: OnlineCatalogEntry): ReadflowResult<BookMeta> =
        withContext(Dispatchers.IO) {
            val downloadUrl = entry.downloadUrl
                ?: return@withContext ReadflowResult.Failure(ReadflowError.unsupported("该条目没有下载地址"))
            runCatching {
                requireAllowedCalibreRequestUrl(downloadUrl)
                requireSameCalibreOrigin(downloadUrl, descriptor.baseUrl)
                booksDir.mkdirs()
                val format = entry.meta.format
                val ext = when (format) {
                    BookFormat.UNKNOWN -> extensionFromUrl(downloadUrl) ?: "bin"
                    else -> format.name.lowercase()
                }
                // Search parse already assigns stableRemoteBookId; raw ids (tests / manual) still hash once.
                val bookId = shelfBookId(descriptor.id, entry.meta.id)
                val outFile = File(booksDir, "$bookId.$ext")
                val stagingFile = File.createTempFile("$bookId-", ".part", booksDir)
                try {
                    val downloadedBytes = stagingFile.outputStream().channel.use { channel ->
                        http.prepareGet(downloadUrl) {
                            timeout {
                                connectTimeoutMillis = 5_000L
                                socketTimeoutMillis = 60_000L
                                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            }
                        }.execute { response ->
                            response.bodyAsChannel().copyTo(channel)
                        }
                    }
                    require(downloadedBytes > 0L && stagingFile.length() > 0L) { "书源返回了空文件" }
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
                        format = BookFormat.fromExtension(ext),
                        downloadStatus = DownloadStatus.DOWNLOADED,
                        localUri = outFile.toURI().toString(),
                        coverUrl = entry.meta.coverUrl,
                    ),
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                ReadflowResult.Failure(ReadflowError.io(error.message ?: "下载失败"))
            }
        }

    override suspend fun previewUrl(entry: OnlineCatalogEntry): ReadflowResult<String> =
        runCatching {
            val url = entry.previewUrl ?: entry.meta.coverUrl
                ?: return ReadflowResult.Failure(ReadflowError.notFound("preview", entry.meta.id))
            requireAllowedCalibreRequestUrl(url)
            requireSameCalibreOrigin(url, descriptor.baseUrl)
            ReadflowResult.Success(url)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "预览地址不安全"))
        }

    override fun close() {
        http.close()
    }
}

private const val MAX_CATALOG_RESPONSE_BYTES = 8 * 1024 * 1024

private suspend fun readCatalogBody(channel: ByteReadChannel): String {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    return ByteArrayOutputStream().use { output ->
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read == -1) break
            require(output.size() + read <= MAX_CATALOG_RESPONSE_BYTES) {
                "书源目录响应过大，最大支持 8 MB"
            }
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }
}

internal fun defaultGenericSourceHttpClient(allowedBaseUrl: String): HttpClient {
    val client = HttpClient {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
    }
    client.plugin(HttpSend).intercept { request ->
        requireAllowedCalibreRequestUrl(request.url.buildString())
        requireSameCalibreOrigin(request.url.buildString(), allowedBaseUrl)
        execute(request)
    }
    return client
}

@Serializable
private data class JsonCatalogBook(
    val id: String? = null,
    val title: String? = null,
    val author: String? = null,
    val format: String? = null,
    val series: String? = null,
    val tags: List<String> = emptyList(),
    val downloadUrl: String? = null,
    val coverUrl: String? = null,
    val previewUrl: String? = null,
)

@Serializable
private data class JsonCatalogEnvelope(val books: List<JsonCatalogBook> = emptyList())

private fun parseJsonCatalog(body: String, descriptor: SourceDescriptor): List<OnlineCatalogEntry> {
    val json = Json { ignoreUnknownKeys = true }
    val books = runCatching {
        json.decodeFromString(JsonCatalogEnvelope.serializer(), body).books
    }.getOrElse {
        json.decodeFromString(ListSerializer(JsonCatalogBook.serializer()), body)
    }
    return books.mapIndexed { index, book ->
        val rawId = book.id?.takeIf { it.isNotBlank() } ?: "item-$index"
        val bookId = stableRemoteBookId(descriptor.id, rawId)
        val format = BookFormat.fromExtension(book.format.orEmpty())
        val safeCover = sanitizeCatalogMediaUrl(book.coverUrl, descriptor.baseUrl)
        val safePreview = sanitizeCatalogMediaUrl(book.previewUrl, descriptor.baseUrl) ?: safeCover
        OnlineCatalogEntry(
            meta = BookMeta(
                id = bookId,
                title = book.title?.ifBlank { null } ?: "未命名",
                author = book.author?.ifBlank { null } ?: "Unknown",
                format = format,
                coverUrl = safeCover,
                downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            ),
            series = book.series,
            tags = book.tags,
            availableFormats = listOfNotNull(book.format),
            downloadUrl = book.downloadUrl,
            previewUrl = safePreview,
        )
    }
}

internal data class OpdsAcquisitionCandidate(
    val url: String,
    val type: String,
    val formatHint: String?,
)

private fun parseOpdsFeed(body: String, descriptor: SourceDescriptor): List<OnlineCatalogEntry> {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = true
    val parser = factory.newPullParser()
    parser.setInput(body.reader())
    val entries = mutableListOf<OnlineCatalogEntry>()
    var event = parser.eventType
    var inEntry = false
    var title: String? = null
    var author: String? = null
    var id: String? = null
    var series: String? = null
    val tags = mutableListOf<String>()
    val acquisitionCandidates = mutableListOf<OpdsAcquisitionCandidate>()
    var coverUrl: String? = null

    fun flushEntry() {
        if (!inEntry) return
        val rawId = id?.takeIf { it.isNotBlank() } ?: "opds-${entries.size}"
        val bookId = stableRemoteBookId(descriptor.id, rawId)
        val best = selectPreferredOpdsAcquisition(acquisitionCandidates)
        val formatHint = best?.formatHint
        val format = BookFormat.fromExtension(formatHint.orEmpty())
        val safeCover = sanitizeCatalogMediaUrl(coverUrl, descriptor.baseUrl)
        entries += OnlineCatalogEntry(
            meta = BookMeta(
                id = bookId,
                title = title?.ifBlank { null } ?: "未命名",
                author = author?.ifBlank { null } ?: "Unknown",
                format = format,
                coverUrl = safeCover,
                downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            ),
            series = series,
            tags = tags.toList(),
            availableFormats = listOfNotNull(formatHint) +
                acquisitionCandidates.mapNotNull { it.formatHint }.filter { it != formatHint }.distinct(),
            downloadUrl = best?.url,
            previewUrl = safeCover,
        )
        title = null
        author = null
        id = null
        series = null
        tags.clear()
        acquisitionCandidates.clear()
        coverUrl = null
        inEntry = false
    }

    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when {
                parser.name.equals("entry", ignoreCase = true) -> {
                    flushEntry()
                    inEntry = true
                }
                inEntry && parser.name.equals("title", ignoreCase = true) ->
                    title = parser.nextText()
                inEntry && parser.name.equals("id", ignoreCase = true) ->
                    id = parser.nextText()
                inEntry && parser.name.equals("name", ignoreCase = true) &&
                    parser.prefix?.contains("author", ignoreCase = true) != true -> {
                    // author/name in OPDS
                    if (author == null) author = parser.nextText()
                }
                inEntry && parser.name.equals("category", ignoreCase = true) -> {
                    val term = parser.getAttributeValue(null, "term")
                        ?: parser.getAttributeValue(null, "label")
                    if (!term.isNullOrBlank()) tags += term
                }
                inEntry && parser.name.equals("link", ignoreCase = true) -> {
                    val rel = parser.getAttributeValue(null, "rel").orEmpty()
                    val href = parser.getAttributeValue(null, "href")
                    val type = parser.getAttributeValue(null, "type").orEmpty()
                    if (!href.isNullOrBlank()) {
                        val absolute = resolveAgainstBase(href, descriptor.baseUrl)
                        when {
                            rel.contains("acquisition", ignoreCase = true) ||
                                type.contains("epub", ignoreCase = true) ||
                                type.contains("pdf", ignoreCase = true) ||
                                type.contains("octet-stream", ignoreCase = true) -> {
                                val formatHint = when {
                                    type.contains("epub", ignoreCase = true) -> "epub"
                                    type.contains("pdf", ignoreCase = true) -> "pdf"
                                    else -> extensionFromUrl(absolute)
                                }
                                acquisitionCandidates += OpdsAcquisitionCandidate(
                                    url = absolute,
                                    type = type,
                                    formatHint = formatHint,
                                )
                            }
                            rel.contains("image", ignoreCase = true) ||
                                rel.contains("thumbnail", ignoreCase = true) ||
                                type.startsWith("image/") -> coverUrl = absolute
                        }
                    }
                }
            }
            XmlPullParser.END_TAG -> if (parser.name.equals("entry", ignoreCase = true)) {
                flushEntry()
            }
        }
        event = parser.next()
    }
    flushEntry()
    return entries
}

/**
 * Prefer EPUB, then PDF, then other supported acquisition types (octet-stream / known formats).
 * Deterministic: first best-ranked candidate wins when ranks tie.
 */
internal fun selectPreferredOpdsAcquisition(
    candidates: List<OpdsAcquisitionCandidate>,
): OpdsAcquisitionCandidate? =
    candidates.minByOrNull { acquisitionPreferenceRank(it) }

private fun acquisitionPreferenceRank(candidate: OpdsAcquisitionCandidate): Int {
    val type = candidate.type
    val hint = candidate.formatHint.orEmpty()
    return when {
        type.contains("epub", ignoreCase = true) || hint.equals("epub", ignoreCase = true) -> 0
        type.contains("pdf", ignoreCase = true) || hint.equals("pdf", ignoreCase = true) -> 1
        type.contains("octet-stream", ignoreCase = true) -> 2
        hint.isNotBlank() && BookFormat.fromExtension(hint) != BookFormat.UNKNOWN -> 3
        else -> 4
    }
}

/** Null when [url] is missing or fails protocol / same-origin policy for [baseUrl]. */
internal fun sanitizeCatalogMediaUrl(url: String?, baseUrl: String): String? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        requireAllowedCalibreRequestUrl(url)
        requireSameCalibreOrigin(url, baseUrl)
        url
    }.getOrNull()
}

/**
 * Search results already carry [stableRemoteBookId]; keep that id on download.
 * Callers that pass a raw remote id still get a single stable hash.
 */
internal fun shelfBookId(sourceId: String, metaId: String): String =
    if (metaId.startsWith("remote-")) metaId else stableRemoteBookId(sourceId, metaId)

private fun resolveAgainstBase(href: String, baseUrl: String): String {
    if (href.startsWith("http://") || href.startsWith("https://")) return href
    val base = URI(baseUrl)
    return base.resolve(href).toString()
}

private fun extensionFromUrl(url: String): String? {
    val path = runCatching { URI(url).path }.getOrNull() ?: return null
    val ext = path.substringAfterLast('.', missingDelimiterValue = "")
    return ext.takeIf { it.isNotBlank() && it.length <= 5 }
}
