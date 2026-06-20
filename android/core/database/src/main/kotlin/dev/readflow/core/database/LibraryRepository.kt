package dev.readflow.core.database

import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Library data facade (Layer 1). Observes the shelf as a list of [LibraryItem]:
 * books sharing a `collectionName` collapse into a [LibraryItem.Bundle], the rest
 * are [LibraryItem.Single]. Ordering preserves the DAO's recent-first sort, with
 * each bundle positioned at its most-recent member (设计文档 §2.1 / §2.1.2).
 */
class LibraryRepository(
    private val bookDao: BookDao,
) {
    fun observeShelf(): Flow<List<LibraryItem>> =
        bookDao.observeShelf().map { rows -> groupIntoItems(rows.map { it.toMeta() }) }

    suspend fun count(): Int = bookDao.count()

    suspend fun upsertBook(book: BookMeta) = bookDao.upsert(book.toEntity())

    suspend fun upsertAll(books: List<BookMeta>) =
        bookDao.upsertAll(books.map { it.toEntity() })

    private fun groupIntoItems(books: List<BookMeta>): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()
        val seenCollections = mutableSetOf<String>()
        val byCollection = books.filter { it.collectionName != null }
            .groupBy { it.collectionName!! }

        for (book in books) {
            val collection = book.collectionName
            if (collection == null) {
                items += LibraryItem.Single(book)
            } else if (seenCollections.add(collection)) {
                // First (most-recent) member of this collection anchors the bundle's position.
                val members = byCollection.getValue(collection)
                if (members.size == 1) {
                    items += LibraryItem.Single(members.first())
                } else {
                    items += LibraryItem.Bundle(BookBundle(collection, members))
                }
            }
        }
        return items
    }
}

private fun BookWithProgress.toMeta(): BookMeta = BookMeta(
    id = book.id,
    title = book.title,
    author = book.author,
    format = runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.UNKNOWN),
    coverUrl = book.coverUrl,
    downloadStatus = runCatching { DownloadStatus.valueOf(book.downloadStatus) }
        .getOrDefault(DownloadStatus.NOT_DOWNLOADED),
    localUri = book.localUri,
    lastReadAt = book.lastReadAt,
    collectionName = book.collectionName,
    progress = progress,
)

private fun BookMeta.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    format = format.name,
    coverUrl = coverUrl,
    downloadStatus = downloadStatus.name,
    localUri = localUri,
    lastReadAt = lastReadAt,
    collectionName = collectionName,
)
