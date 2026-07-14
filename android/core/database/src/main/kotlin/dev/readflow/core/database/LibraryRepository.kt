package dev.readflow.core.database

import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

interface LibraryStore {
    fun observeShelf(): Flow<List<LibraryItem>>
    suspend fun count(): Int
    suspend fun upsertBook(book: BookMeta)
    suspend fun upsertAll(books: List<BookMeta>)
    suspend fun deleteBook(id: String)
    suspend fun deleteBookCompletely(id: String)
    suspend fun removeDownloadedAsset(id: String): Boolean
    suspend fun renameBook(id: String, title: String)
    suspend fun setCollection(id: String, collectionId: String?, name: String?)
    suspend fun moveToGroup(sourceId: String, targetCollectionId: String)
    suspend fun createGroup(sourceId: String, targetId: String, name: String)
    suspend fun renameBundle(collectionId: String, newName: String)
    suspend fun ungroupBundle(collectionId: String)
    suspend fun updateShelfOrder(ids: List<String>)
}

class LibraryRepository(
    private val bookDao: BookDao,
    private val downloadedBookCache: DownloadedBookCacheStore = DownloadedBookCache(bookDao),
    private val completeBookDeletionStore: CompleteBookDeletionStore? = null,
) : LibraryStore {

    private val shelfWriteMutex = Mutex()

    override fun observeShelf(): Flow<List<LibraryItem>> = flow {
        shelfWriteMutex.withLock { bookDao.normalizeShelfOrderIfNeeded() }
        emitAll(
            bookDao.observeShelf().map { rows ->
                groupIntoItems(rows.stableShelfOrder().map { it.toMeta() })
            },
        )
    }

    override suspend fun count(): Int = bookDao.count()
    override suspend fun upsertBook(book: BookMeta) {
        shelfWriteMutex.withLock {
            bookDao.normalizeShelfOrderIfNeeded()
            val existing = bookDao.getById(book.id)
            val entity = book.toEntity().preserveShelfStateFrom(existing)
            val ordered = if (existing == null) {
                entity.copy(sortOrder = (bookDao.maxSortOrder() ?: -1) + 1)
            } else {
                entity
            }
            bookDao.upsert(ordered)
        }
        trimCacheIfDownloadedRemote(book)
    }

    override suspend fun upsertAll(books: List<BookMeta>) {
        shelfWriteMutex.withLock {
            bookDao.normalizeShelfOrderIfNeeded()
            var nextSortOrder = (bookDao.maxSortOrder() ?: -1) + 1
            val entities = books.map { book ->
                val existing = bookDao.getById(book.id)
                val entity = book.toEntity().preserveShelfStateFrom(existing)
                if (existing == null) entity.copy(sortOrder = nextSortOrder++) else entity
            }
            bookDao.upsertAll(entities)
        }
        books.lastOrNull { it.isDownloadedRemoteCacheBook() }?.let { downloadedBookCache.trim(it.id) }
    }
    override suspend fun deleteBook(id: String) {
        shelfWriteMutex.withLock { bookDao.deleteById(id) }
    }
    override suspend fun deleteBookCompletely(id: String) {
        shelfWriteMutex.withLock {
            checkNotNull(completeBookDeletionStore) { "完整删除未配置" }.delete(id)
        }
    }
    override suspend fun removeDownloadedAsset(id: String): Boolean =
        downloadedBookCache.removeDownloadedAsset(id) != null
    override suspend fun renameBook(id: String, title: String) {
        shelfWriteMutex.withLock {
            bookDao.normalizeShelfOrderIfNeeded()
            bookDao.updateTitle(id, title)
        }
    }
    override suspend fun setCollection(id: String, collectionId: String?, name: String?) {
        shelfWriteMutex.withLock {
            check(bookDao.updateCollection(id, collectionId, name) == 1) {
                "书籍状态已变化，请重试"
            }
        }
    }
    override suspend fun moveToGroup(sourceId: String, targetCollectionId: String) {
        shelfWriteMutex.withLock {
            check(bookDao.moveToGroup(sourceId, targetCollectionId) == 1) {
                "书籍或目标书组状态已变化，请重试"
            }
        }
    }
    override suspend fun createGroup(sourceId: String, targetId: String, name: String) {
        shelfWriteMutex.withLock {
            check(bookDao.createGroup(sourceId, targetId, UUID.randomUUID().toString(), name) == 2) {
                "书籍状态已变化，请重试"
            }
        }
    }
    override suspend fun renameBundle(collectionId: String, newName: String) {
        shelfWriteMutex.withLock {
            check(bookDao.renameCollection(collectionId, newName) > 0) {
                "书组状态已变化，请重试"
            }
        }
    }
    override suspend fun ungroupBundle(collectionId: String) {
        shelfWriteMutex.withLock {
            check(bookDao.clearCollection(collectionId) > 0) {
                "书组状态已变化，请重试"
            }
        }
    }
    override suspend fun updateShelfOrder(ids: List<String>) {
        shelfWriteMutex.withLock { bookDao.replaceShelfOrder(ids) }
    }

    private fun groupIntoItems(books: List<BookMeta>): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()
        val seenCollections = mutableSetOf<String>()
        val byCollection = books
            .mapNotNull { book -> book.effectiveCollectionId()?.let { it to book } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        for (book in books) {
            val collectionId = book.effectiveCollectionId()
            if (collectionId == null) {
                items += LibraryItem.Single(book)
            } else if (seenCollections.add(collectionId)) {
                val members = byCollection.getValue(collectionId)
                items += LibraryItem.Bundle(
                    BookBundle(
                        name = members.firstNotNullOfOrNull(BookMeta::collectionName).orEmpty(),
                        books = members,
                        id = collectionId,
                    ),
                )
            }
        }
        return items
    }

    private suspend fun trimCacheIfDownloadedRemote(book: BookMeta) {
        if (book.isDownloadedRemoteCacheBook()) {
            downloadedBookCache.trim(protectedBookId = book.id)
        }
    }
}

private fun BookMeta.isDownloadedRemoteCacheBook(): Boolean =
    id.startsWith(DownloadedBookCachePlanner.REMOTE_CACHE_ID_PREFIX) &&
        downloadStatus == DownloadStatus.DOWNLOADED &&
        localUri != null

private fun BookWithProgress.toMeta() = BookMeta(
    id = book.id, title = book.title, author = book.author,
    format = runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.UNKNOWN),
    coverUrl = book.coverUrl,
    downloadStatus = runCatching { DownloadStatus.valueOf(book.downloadStatus) }.getOrDefault(DownloadStatus.NOT_DOWNLOADED),
    localUri = book.localUri, lastReadAt = book.lastReadAt, collectionName = book.collectionName,
    progress = progress,
    collectionId = book.collectionId,
)

private fun BookMeta.toEntity() = BookEntity(
    id = id, title = title, author = author, format = format.name, coverUrl = coverUrl,
    downloadStatus = downloadStatus.name, localUri = localUri, lastReadAt = lastReadAt,
    collectionName = collectionName,
    collectionId = collectionId ?: collectionName?.let(::legacyCollectionId),
)

private fun BookEntity.preserveShelfStateFrom(existing: BookEntity?): BookEntity =
    if (existing == null) {
        this
    } else {
        val preserveImportedMetadata = id.startsWith("local-")
        copy(
            title = if (preserveImportedMetadata) existing.title else title,
            author = if (preserveImportedMetadata) existing.author else author,
            lastReadAt = existing.lastReadAt,
            collectionName = existing.collectionName,
            collectionId = existing.collectionId,
            sortOrder = existing.sortOrder,
        )
    }

private fun BookMeta.effectiveCollectionId(): String? =
    collectionId ?: collectionName?.let(::legacyCollectionId)

private fun legacyCollectionId(name: String): String = buildString {
    append("legacy:")
    name.encodeToByteArray().forEach { byte ->
        append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
    }
}

private fun List<BookWithProgress>.stableShelfOrder(): List<BookWithProgress> =
    sortedWith(
        compareBy<BookWithProgress> { it.book.sortOrder }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.book.title }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.book.id },
    )
