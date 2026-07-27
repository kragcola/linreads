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
import dev.readflow.extensions.api.OnlineCatalogPage
import dev.readflow.extensions.api.SourceCredentials
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.applyCatalogFilter
import dev.readflow.extensions.api.sourceEntryKey
import dev.readflow.extensions.api.stableRemoteBookId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.copyTo
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Calibre profile backed by OPDS. AJAX is never required to browse, search, cover, or download. */
class CalibreOpdsOnlineCatalog internal constructor(
    override val descriptor: SourceDescriptor,
    private val booksDir: File,
    private val http: HttpClient,
    private val networkSnapshotProvider: CalibreNetworkSnapshotProvider =
        UnknownCalibreNetworkSnapshotProvider,
) : OnlineBookCatalog {
    constructor(
        descriptor: SourceDescriptor,
        booksDir: File,
        credentials: SourceCredentials?,
        networkSnapshotProvider: CalibreNetworkSnapshotProvider =
            UnknownCalibreNetworkSnapshotProvider,
    ) : this(
        descriptor = descriptor,
        booksDir = booksDir,
        http = defaultCalibreHttpClient(
            allowedBaseUrl = descriptor.baseUrl,
            username = credentials?.username.orEmpty(),
            password = credentials?.password.orEmpty(),
            networkSnapshotProvider = networkSnapshotProvider,
        ),
        networkSnapshotProvider = networkSnapshotProvider,
    )

    private val opdsUrl = requireCalibreOpdsUrl(
        rawUrl = descriptor.baseUrl,
        libraryId = descriptor.calibreConfig().libraryId,
    )
    private val stateMutex = Mutex()
    private var rootFeed: CalibreOpdsFeed? = null
    private var browseState: FeedState? = null
    private val searchStates = mutableMapOf<String, FeedState>()

    override suspend fun search(
        query: String,
        filter: OnlineCatalogFilter,
        offset: Int,
        limit: Int,
    ): ReadflowResult<List<OnlineCatalogEntry>> = when (val result = searchPage(query, filter, offset, limit)) {
        is ReadflowResult.Success -> ReadflowResult.Success(result.value.entries)
        is ReadflowResult.Failure -> result
    }

    override suspend fun searchPage(
        query: String,
        filter: OnlineCatalogFilter,
        offset: Int,
        limit: Int,
    ): ReadflowResult<OnlineCatalogPage> = loadPage(query.trim(), filter, offset, limit)

    override suspend fun browsePage(
        filter: OnlineCatalogFilter,
        offset: Int,
        limit: Int,
    ): ReadflowResult<OnlineCatalogPage> = loadPage("", filter, offset, limit)

    private suspend fun loadPage(
        query: String,
        filter: OnlineCatalogFilter,
        offset: Int,
        limit: Int,
    ): ReadflowResult<OnlineCatalogPage> = withContext(Dispatchers.IO) {
        runCatching {
            stateMutex.withLock {
                val state = stateFor(query)
                val requestedLimit = limit.coerceAtLeast(1)
                var scanIndex = offset.coerceAtLeast(0)
                val matches = mutableListOf<OnlineCatalogEntry>()
                val scanned = linkedSetOf<String>()

                while (matches.size < requestedLimit) {
                    while (scanIndex >= state.entries.size && !state.exhausted) {
                        appendNextPage(state)
                    }
                    if (scanIndex >= state.entries.size) break
                    val entry = state.entries[scanIndex]
                    scanIndex += 1
                    scanned += entry.sourceEntryKey()
                    val queryMatches = state.serverSearch || query.isBlank() || entry.matchesQuery(query)
                    if (queryMatches && listOf(entry).applyCatalogFilter(filter).isNotEmpty()) {
                        matches += entry
                    }
                }

                OnlineCatalogPage(
                    entries = matches,
                    nextOffset = scanIndex,
                    hasMore = scanIndex < state.entries.size || !state.exhausted,
                    scannedEntryKeys = scanned,
                )
            }.let { page -> ReadflowResult.Success(page) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ReadflowResult.Failure(
                error.toCalibreReadflowError(
                    baseUrl = descriptor.baseUrl,
                    network = networkSnapshotProvider.snapshot(),
                ),
            )
        }
    }

    private suspend fun stateFor(query: String): FeedState {
        if (query.isBlank()) {
            browseState?.let { return it }
            return initialBrowseState().also { browseState = it }
        }
        searchStates[query]?.let { return it }
        val root = rootFeed()
        val template = root.searchTemplate
        val state = if (template != null) {
            val url = expandCalibreOpdsSearchTemplate(template, query)
            feedState(fetchFeed(url, CalibreRequestPhase.OPDS_SEARCH), serverSearch = true, initialUrl = url)
        } else {
            initialBrowseState().copyForLocalSearch()
        }
        searchStates[query] = state
        return state
    }

    private suspend fun initialBrowseState(): FeedState {
        val root = rootFeed()
        if (root.publications.isNotEmpty()) return feedState(root, serverSearch = false, initialUrl = opdsUrl)
        if (root.navigation.isEmpty()) return feedState(root, serverSearch = false, initialUrl = opdsUrl)

        var navigation = root.navigation
        val visited = linkedSetOf(opdsUrl)
        repeat(MAX_NAVIGATION_DEPTH) {
            val target = navigation.firstOrNull { isCalibreTitleNavigation(it.url) } ?: navigation.first()
            require(visited.add(target.url)) { "OPDS 导航形成循环" }
            val feed = fetchFeed(target.url, CalibreRequestPhase.OPDS_NAVIGATION)
            if (feed.publications.isNotEmpty() || feed.navigation.isEmpty()) {
                return feedState(feed, serverSearch = false, initialUrl = target.url, visited = visited)
            }
            navigation = feed.navigation
        }
        error("OPDS 导航层级过深")
    }

    private suspend fun rootFeed(): CalibreOpdsFeed {
        rootFeed?.let { return it }
        return fetchFeed(opdsUrl, CalibreRequestPhase.OPDS_ROOT).also { rootFeed = it }
    }

    private fun feedState(
        feed: CalibreOpdsFeed,
        serverSearch: Boolean,
        initialUrl: String,
        visited: Set<String> = setOf(initialUrl),
    ) = FeedState(
        entries = feed.publications.map(::asCalibreEntry).toMutableList(),
        nextUrl = feed.nextUrl,
        serverSearch = serverSearch,
        visitedUrls = visited.toMutableSet(),
    )

    private suspend fun appendNextPage(state: FeedState) {
        val next = state.nextUrl ?: return
        require(next !in state.visitedUrls) { "OPDS 分页形成循环" }
        require(state.visitedUrls.size < MAX_FEEDS_PER_SESSION) { "OPDS 分页数量超过安全上限" }
        val phase = if (state.serverSearch) CalibreRequestPhase.OPDS_SEARCH else CalibreRequestPhase.OPDS_PAGE
        val feed = fetchFeed(next, phase)
        state.visitedUrls += next
        state.entries += feed.publications.map(::asCalibreEntry)
        state.nextUrl = feed.nextUrl
    }

    private suspend fun fetchFeed(url: String, phase: CalibreRequestPhase): CalibreOpdsFeed {
        requireAllowedCalibreRequestUrl(url)
        requireSameCalibreOrigin(url, descriptor.baseUrl)
        val body = withCalibreRequestContext(phase, url) {
            http.prepareGet(url).execute { response -> readCatalogBody(response.bodyAsChannel()) }
        }
        return try {
            parseCalibreOpdsFeed(body, descriptor, url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw CalibreRequestContextException(
                phase = phase,
                origin = calibreCredentialScopeForRequestUrl(descriptor.baseUrl),
                cause = error,
            )
        }
    }

    private fun asCalibreEntry(entry: OnlineCatalogEntry): OnlineCatalogEntry {
        val remoteId = entry.remoteKey?.remoteId ?: entry.meta.id
        val cover = entry.meta.coverUrl?.let { candidate ->
            runCatching {
                requireSameCalibreOrigin(candidate, descriptor.baseUrl)
                authenticatedCalibreCoverUrl(candidate, descriptor.id)
            }.getOrNull()
        }
        val metaId = if (descriptor.id == BUILTIN_CALIBRE_SOURCE_ID) {
            remoteId
        } else {
            stableRemoteBookId(descriptor.id, remoteId)
        }
        return entry.copy(
            meta = entry.meta.copy(id = metaId, coverUrl = cover),
            remoteKey = dev.readflow.extensions.api.RemoteBookKey(descriptor.id, remoteId),
            previewUrl = cover,
        )
    }

    override suspend fun download(entry: OnlineCatalogEntry): ReadflowResult<BookMeta> = withContext(Dispatchers.IO) {
        val downloadUrl = entry.downloadUrl
            ?: return@withContext ReadflowResult.Failure(ReadflowError.unsupported("该条目没有下载地址"))
        if (entry.remoteKey?.sourceId?.let { it != descriptor.id } == true) {
            return@withContext ReadflowResult.Failure(ReadflowError.parse("搜索结果不属于当前书源"))
        }
        val format = entry.meta.format.takeIf(BookFormat::hasPublishedReaderEngine)
            ?: return@withContext ReadflowResult.Failure(
                ReadflowError.unsupported("LinReads 暂不支持该 Calibre 书籍格式"),
            )
        val extension = format.name.lowercase()
        runCatching {
            requireAllowedCalibreRequestUrl(downloadUrl)
            requireSameCalibreOrigin(downloadUrl, descriptor.baseUrl)
            booksDir.mkdirs()
            val remoteId = entry.remoteKey?.remoteId ?: entry.meta.id
            val localBookId = if (
                descriptor.id == BUILTIN_CALIBRE_SOURCE_ID && remoteId.toLongOrNull() != null
            ) {
                "calibre-$remoteId"
            } else {
                stableRemoteBookId(descriptor.id, remoteId)
            }
            val output = File(booksDir, "$localBookId.$extension")
            val staging = File.createTempFile("$localBookId-", ".part", booksDir)
            try {
                val bytes = staging.outputStream().channel.use { channel ->
                    withCalibreRequestContext(CalibreRequestPhase.OPDS_DOWNLOAD, downloadUrl) {
                        http.prepareGet(downloadUrl) {
                            timeout {
                                connectTimeoutMillis = DOWNLOAD_CONNECT_TIMEOUT_MS
                                socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MS
                                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            }
                        }.execute { response -> response.bodyAsChannel().copyTo(channel) }
                    }
                }
                require(bytes > 0L && staging.length() > 0L) { "Calibre 返回了空文件" }
                moveAtomically(staging, output)
            } finally {
                staging.delete()
            }
            ReadflowResult.Success(
                entry.meta.copy(
                    id = localBookId,
                    format = format,
                    downloadStatus = DownloadStatus.DOWNLOADED,
                    localUri = output.toURI().toString(),
                ),
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ReadflowResult.Failure(
                if (
                    error.findCalibreCause<ResponseException>() != null ||
                    error.isCalibreTransportFailure()
                ) {
                    error.toCalibreReadflowError(
                        baseUrl = descriptor.baseUrl,
                        network = networkSnapshotProvider.snapshot(),
                    )
                } else {
                    ReadflowError.io(error.message ?: "Calibre 下载失败")
                },
            )
        }
    }

    override suspend fun preview(entry: OnlineCatalogEntry): ReadflowResult<OnlineBookPreview> =
        ReadflowResult.Failure(ReadflowError.unsupported("Calibre 不提供在线正文预览，请先下载"))

    override fun close() = http.close()

    private data class FeedState(
        val entries: MutableList<OnlineCatalogEntry>,
        var nextUrl: String?,
        val serverSearch: Boolean,
        val visitedUrls: MutableSet<String>,
    ) {
        val exhausted: Boolean get() = nextUrl == null

        fun copyForLocalSearch() = FeedState(
            entries = entries.toMutableList(),
            nextUrl = nextUrl,
            serverSearch = false,
            visitedUrls = visitedUrls.toMutableSet(),
        )
    }

    private companion object {
        const val MAX_NAVIGATION_DEPTH = 4
        const val MAX_FEEDS_PER_SESSION = 256
        const val DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000L
        const val DOWNLOAD_SOCKET_TIMEOUT_MS = 60_000L
    }
}

private fun OnlineCatalogEntry.matchesQuery(query: String): Boolean =
    meta.title.contains(query, ignoreCase = true) ||
        meta.author.contains(query, ignoreCase = true) ||
        series?.contains(query, ignoreCase = true) == true ||
        tags.any { it.contains(query, ignoreCase = true) }

private fun BookFormat.hasPublishedReaderEngine(): Boolean = when (this) {
    BookFormat.EPUB,
    BookFormat.PDF,
    BookFormat.TXT,
    BookFormat.MD,
    -> true
    else -> false
}

private fun moveAtomically(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
