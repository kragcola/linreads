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
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.applyCatalogFilter
import dev.readflow.extensions.api.stableRemoteBookId
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Calibre Content Server as a generic [OnlineBookCatalog] adapter.
 * Download path reuses staging + atomic commit from [CalibreBookDownloader].
 */
class CalibreOnlineCatalog(
    private val client: CalibreClient,
    booksDir: File?,
    override val descriptor: SourceDescriptor = SourceDescriptor(
        id = BUILTIN_CALIBRE_SOURCE_ID,
        adapterId = SourceAdapterIds.CALIBRE,
        name = "Calibre",
        configVersion = 1,
        configJson = calibreSourceConfigJson(""),
        baseUrl = "",
        isBuiltin = true,
    ),
) : OnlineBookCatalog {

    private val downloader = booksDir?.let { CalibreBookDownloader(client, it) }

    constructor(
        baseUrl: String,
        booksDir: File?,
        name: String = "Calibre",
    ) : this(
        client = CalibreClient(baseUrl),
        booksDir = booksDir,
        descriptor = SourceDescriptor(
            id = BUILTIN_CALIBRE_SOURCE_ID,
            adapterId = SourceAdapterIds.CALIBRE,
            name = name,
            configVersion = 1,
            configJson = calibreSourceConfigJson(requireValidCalibreBaseUrl(baseUrl)),
            baseUrl = requireValidCalibreBaseUrl(baseUrl),
            isBuiltin = true,
        ),
    )

    override suspend fun search(
        query: String,
        filter: OnlineCatalogFilter,
        offset: Int,
        limit: Int,
    ): ReadflowResult<List<OnlineCatalogEntry>> =
        runCatching {
            val ids = client.search(query, limit, offset).book_ids
            val entries = ids.map { id ->
                val wire = client.bookMeta(id)
                OnlineCatalogEntry(
                    meta = wire.toCatalogMeta(client, descriptor.id),
                    remoteKey = RemoteBookKey(descriptor.id, id.toString()),
                    series = wire.series,
                    tags = wire.tags,
                    availableFormats = wire.formats,
                    downloadUrl = wire.bestDownloadFormat()?.let { client.downloadUrl(id, it.remoteFormat) },
                    previewUrl = client.coverUrl(id),
                )
            }
            ReadflowResult.Success(entries.applyCatalogFilter(filter))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "Calibre error"))
        }

    override suspend fun download(entry: OnlineCatalogEntry): ReadflowResult<BookMeta> =
        runCatching {
            val downloader = downloader
                ?: return ReadflowResult.Failure(ReadflowError.io("Calibre 下载目录未配置"))
            entry.remoteKey?.let { key ->
                require(key.sourceId == descriptor.id) { "搜索结果不属于当前书源" }
            }
            val remoteId = entry.remoteKey?.remoteId?.toIntOrNull()
                ?: entry.meta.id.removePrefix("calibre-").toIntOrNull()
                ?: entry.meta.id.toIntOrNull()
                ?: return ReadflowResult.Failure(ReadflowError.parse("无效的 Calibre 书籍 ID"))
            val wire = client.bookMeta(remoteId)
            val localBookId = if (descriptor.id == BUILTIN_CALIBRE_SOURCE_ID) {
                "calibre-$remoteId"
            } else {
                stableRemoteBookId(descriptor.id, remoteId.toString())
            }
            when (val result = downloader.download(wire, localBookId)) {
                is ReadflowResult.Success -> ReadflowResult.Success(result.value.meta)
                is ReadflowResult.Failure -> result
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ReadflowResult.Failure(ReadflowError.network(null, error.message ?: "Calibre error"))
        }

    override suspend fun preview(entry: OnlineCatalogEntry): ReadflowResult<OnlineBookPreview> =
        ReadflowResult.Failure(ReadflowError.unsupported("Calibre 不提供在线正文预览，请先下载"))

    override fun close() {
        client.close()
    }
}

/**
 * Bridges existing [CalibreRepository] call sites to [CalibreOnlineCatalog] / BookSource path.
 * Keeps search/download behavior and staging downloads.
 */
class CalibreBookSource(
    private val catalog: CalibreOnlineCatalog,
) : dev.readflow.extensions.api.BookSource, AutoCloseable {
    override val sourceId: String = catalog.descriptor.id
    override val sourceName: String = catalog.descriptor.name

    override suspend fun search(query: String, offset: Int, limit: Int) =
        when (val result = catalog.search(query, offset = offset, limit = limit)) {
            is ReadflowResult.Success -> ReadflowResult.Success(result.value.map { it.meta })
            is ReadflowResult.Failure -> result
        }

    override suspend fun getMetadata(bookId: String): ReadflowResult<BookMeta> =
        when (val result = catalog.search(query = "", offset = 0, limit = 500)) {
            is ReadflowResult.Failure -> result
            is ReadflowResult.Success -> {
                val match = result.value.firstOrNull {
                    it.meta.id == bookId || it.meta.id == "calibre-$bookId" || it.meta.id.removePrefix("calibre-") == bookId
                }
                if (match != null) ReadflowResult.Success(match.meta)
                else ReadflowResult.Failure(ReadflowError.notFound("book", bookId))
            }
        }

    override suspend fun getDownloadUrl(bookId: String, format: String): ReadflowResult<String> =
        ReadflowResult.Failure(ReadflowError.unsupported("Use download()"))

    override suspend fun getCoverUrl(bookId: String): ReadflowResult<String> =
        when (val meta = getMetadata(bookId)) {
            is ReadflowResult.Success -> ReadflowResult.Success(meta.value.coverUrl.orEmpty())
            is ReadflowResult.Failure -> meta
        }

    override suspend fun download(bookId: String, format: String) =
        ReadflowResult.Failure(ReadflowError.unsupported("Use catalog.download"))

    override suspend fun getDownloadStatus(bookId: String) = DownloadStatus.NOT_DOWNLOADED

    override suspend fun isAvailable(): Boolean = true

    override fun close() = catalog.close()
}

/** Adapter: existing CalibreRepository API → OnlineCatalog-backed implementation. */
class CatalogBackedCalibreRepository(
    private val catalog: CalibreOnlineCatalog,
) : CalibreRepository {
    override suspend fun search(query: String, offset: Int, limit: Int): ReadflowResult<List<BookMeta>> =
        when (val result = catalog.search(query, offset = offset, limit = limit)) {
            is ReadflowResult.Success -> ReadflowResult.Success(result.value.map { it.meta })
            is ReadflowResult.Failure -> result
        }

    override suspend fun metadata(bookId: String): ReadflowResult<BookMeta> {
        val remoteId = bookId.removePrefix("calibre-")
        return when (val result = catalog.search(query = "", offset = 0, limit = 500)) {
            is ReadflowResult.Failure -> result
            is ReadflowResult.Success -> {
                val match = result.value.firstOrNull {
                    it.meta.id == bookId ||
                        it.meta.id == remoteId ||
                        it.meta.id.removePrefix("calibre-") == remoteId
                }
                if (match != null) ReadflowResult.Success(match.meta)
                else {
                    // Direct metadata fetch via download path's client is not exposed; fall back.
                    ReadflowResult.Failure(ReadflowError.notFound("book", bookId))
                }
            }
        }
    }

    override suspend fun download(bookId: String): ReadflowResult<BookMeta> {
        val entry = OnlineCatalogEntry(
            meta = BookMeta(
                id = bookId,
                title = "",
                author = "",
                format = BookFormat.UNKNOWN,
            ),
        )
        return catalog.download(entry)
    }

    override fun close() = catalog.close()
}

private fun CalibreBookMeta.toCatalogMeta(client: CalibreClient, sourceId: String) = BookMeta(
    id = if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) id.toString() else stableRemoteBookId(sourceId, id.toString()),
    title = title,
    author = authors.joinToString(", ").ifEmpty { "Unknown" },
    format = bestDownloadFormat()?.bookFormat ?: BookFormat.UNKNOWN,
    coverUrl = client.coverUrl(id),
    downloadStatus = DownloadStatus.NOT_DOWNLOADED,
)
