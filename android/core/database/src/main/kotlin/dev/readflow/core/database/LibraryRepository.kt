package dev.readflow.core.database

import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(private val bookDao: BookDao) {

    fun observeShelf(): Flow<List<LibraryItem>> =
        bookDao.observeShelf().map { rows -> groupIntoItems(rows.map { it.toMeta() }) }

    suspend fun count(): Int = bookDao.count()
    suspend fun upsertBook(book: BookMeta) = bookDao.upsert(book.toEntity())
    suspend fun upsertAll(books: List<BookMeta>) = bookDao.upsertAll(books.map { it.toEntity() })
    suspend fun deleteBook(id: String) = bookDao.deleteById(id)
    suspend fun renameBook(id: String, title: String) = bookDao.updateTitle(id, title)
    suspend fun setCollection(id: String, name: String?) = bookDao.updateCollectionName(id, name)
    suspend fun ungroupBundle(name: String) = bookDao.clearCollection(name)
    suspend fun updateSortOrder(id: String, order: Int) = bookDao.updateSortOrder(id, order)

    private fun groupIntoItems(books: List<BookMeta>): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()
        val seenCollections = mutableSetOf<String>()
        val byCollection = books.filter { it.collectionName != null }.groupBy { it.collectionName!! }
        for (book in books) {
            val col = book.collectionName
            if (col == null) {
                items += LibraryItem.Single(book)
            } else if (seenCollections.add(col)) {
                val members = byCollection.getValue(col)
                items += if (members.size == 1) LibraryItem.Bundle(BookBundle(col, members))
                         else LibraryItem.Bundle(BookBundle(col, members))
            }
        }
        return items
    }
}

private fun BookWithProgress.toMeta() = BookMeta(
    id = book.id, title = book.title, author = book.author,
    format = runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.UNKNOWN),
    coverUrl = book.coverUrl,
    downloadStatus = runCatching { DownloadStatus.valueOf(book.downloadStatus) }.getOrDefault(DownloadStatus.NOT_DOWNLOADED),
    localUri = book.localUri, lastReadAt = book.lastReadAt, collectionName = book.collectionName,
    progress = progress,
)

private fun BookMeta.toEntity() = BookEntity(
    id = id, title = title, author = author, format = format.name, coverUrl = coverUrl,
    downloadStatus = downloadStatus.name, localUri = localUri, lastReadAt = lastReadAt,
    collectionName = collectionName,
)
