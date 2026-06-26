package dev.readflow.core.database

import dev.readflow.core.model.DownloadStatus
import java.io.File
import java.net.URI

data class DownloadedCacheEviction(
    val bookId: String,
    val localUri: String?,
)

interface DownloadedBookCacheStore {
    suspend fun trim(protectedBookId: String? = null): List<DownloadedCacheEviction>
    suspend fun removeDownloadedAsset(bookId: String): DownloadedCacheEviction?
}

class DownloadedBookCache(
    private val bookDao: BookDao,
    cacheLimit: Int = DownloadedBookCachePlanner.DEFAULT_CACHE_LIMIT,
    private val deleteLocalUri: (String) -> Boolean = ::deleteFileUri,
) : DownloadedBookCacheStore {

    private val planner = DownloadedBookCachePlanner(cacheLimit)

    override suspend fun trim(protectedBookId: String?): List<DownloadedCacheEviction> {
        val evictions = planner.evictions(
            candidates = bookDao.downloadedRemoteCacheBooks(
                remotePrefix = DownloadedBookCachePlanner.REMOTE_CACHE_ID_PREFIX,
                downloadedStatus = DownloadStatus.DOWNLOADED.name,
            ),
            protectedBookId = protectedBookId,
        )
        val removed = mutableListOf<DownloadedCacheEviction>()
        for (book in evictions) {
            val canClear = book.localUri == null || deleteLocalUri(book.localUri)
            if (canClear) {
                bookDao.clearDownloadedAsset(book.id, DownloadStatus.NOT_DOWNLOADED.name)
                removed += DownloadedCacheEviction(book.id, book.localUri)
            }
        }
        return removed
    }

    override suspend fun removeDownloadedAsset(bookId: String): DownloadedCacheEviction? {
        if (!bookId.startsWith(DownloadedBookCachePlanner.REMOTE_CACHE_ID_PREFIX)) return null
        val book = bookDao.getById(bookId) ?: return null
        if (book.downloadStatus != DownloadStatus.DOWNLOADED.name) return null
        val localUri = book.localUri ?: return null
        if (!deleteLocalUri(localUri)) return null
        bookDao.clearDownloadedAsset(bookId, DownloadStatus.NOT_DOWNLOADED.name)
        return DownloadedCacheEviction(bookId, localUri)
    }
}

private fun deleteFileUri(localUri: String): Boolean =
    runCatching {
        val uri = URI(localUri)
        if (uri.scheme != "file") return false
        val file = File(uri)
        !file.exists() || file.delete()
    }.getOrDefault(false)
