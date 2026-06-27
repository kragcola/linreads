package dev.readflow.core.database

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedBookCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun trimDeletesOldestRemoteFilesAndMarksThemNotDownloaded() = runTest {
        val dao = FakeBookDao()
        val oldFile = temp.newFile("old.epub")
        val keptFile = temp.newFile("kept.epub")
        val newestFile = temp.newFile("newest.epub")
        dao.upsert(cachedEntity("calibre-old", oldFile.toURI().toString(), lastReadAt = 100))
        dao.upsert(cachedEntity("calibre-kept", keptFile.toURI().toString(), lastReadAt = 200))
        dao.upsert(cachedEntity("calibre-newest", newestFile.toURI().toString(), lastReadAt = 300))
        dao.upsert(cachedEntity("local-book", temp.newFile("local.epub").toURI().toString(), lastReadAt = 50))
        val cache = DownloadedBookCache(bookDao = dao, cacheLimit = 2)

        val evictions = cache.trim(protectedBookId = "calibre-newest")

        assertEquals(listOf("calibre-old"), evictions.map { it.bookId })
        assertFalse(oldFile.exists())
        assertTrue(keptFile.exists())
        assertTrue(newestFile.exists())
        assertEquals(DownloadStatus.NOT_DOWNLOADED.name, dao.book("calibre-old")?.downloadStatus)
        assertNull(dao.book("calibre-old")?.localUri)
        assertEquals(DownloadStatus.DOWNLOADED.name, dao.book("local-book")?.downloadStatus)
    }

    @Test
    fun removeDownloadedAssetDeletesRemoteFileAndKeepsBookRow() = runTest {
        val dao = FakeBookDao()
        val file = temp.newFile("remote.epub")
        dao.upsert(cachedEntity("calibre-42", file.toURI().toString(), lastReadAt = 100))
        val cache = DownloadedBookCache(bookDao = dao)

        val eviction = cache.removeDownloadedAsset("calibre-42")

        assertEquals("calibre-42", eviction?.bookId)
        assertEquals(file.toURI().toString(), eviction?.localUri)
        assertFalse(file.exists())
        assertEquals(DownloadStatus.NOT_DOWNLOADED.name, dao.book("calibre-42")?.downloadStatus)
        assertNull(dao.book("calibre-42")?.localUri)
        assertEquals("calibre-42", dao.book("calibre-42")?.id)
    }

    @Test
    fun removeDownloadedAssetIgnoresLocalImports() = runTest {
        val dao = FakeBookDao()
        val file = temp.newFile("local.epub")
        dao.upsert(cachedEntity("local-book", file.toURI().toString(), lastReadAt = 100))
        val cache = DownloadedBookCache(bookDao = dao)

        val eviction = cache.removeDownloadedAsset("local-book")

        assertNull(eviction)
        assertTrue(file.exists())
        assertEquals(DownloadStatus.DOWNLOADED.name, dao.book("local-book")?.downloadStatus)
        assertEquals(file.toURI().toString(), dao.book("local-book")?.localUri)
    }

    @Test
    fun repositoryTrimsCacheAfterDownloadedRemoteUpsert() = runTest {
        val dao = FakeBookDao()
        val cache = RecordingCache()
        val repository = LibraryRepository(dao, cache)
        val book = BookMeta(
            id = "calibre-42",
            title = "Remote",
            author = "Author",
            format = BookFormat.EPUB,
            downloadStatus = DownloadStatus.DOWNLOADED,
            localUri = "file:///books/calibre-42.epub",
        )

        repository.upsertBook(book)

        assertEquals(listOf("calibre-42"), cache.protectedBookIds)
    }

    @Test
    fun repositoryPreservesLocalShelfStateWhenStableImportIsUpsertedAgain() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(
            BookEntity(
                id = "local-txt-stable",
                title = "User renamed title",
                author = "Reader",
                format = BookFormat.TXT.name,
                downloadStatus = DownloadStatus.DOWNLOADED.name,
                localUri = "file:///books/local-txt-stable.txt",
                lastReadAt = 1234L,
                collectionName = "Offline",
                sortOrder = 7,
            ),
        )

        repository.upsertBook(
            BookMeta(
                id = "local-txt-stable",
                title = "incoming-filename",
                author = "未知作者",
                format = BookFormat.TXT,
                downloadStatus = DownloadStatus.DOWNLOADED,
                localUri = "file:///books/local-txt-stable.txt",
                lastReadAt = null,
                collectionName = null,
            ),
        )

        val book = dao.book("local-txt-stable")
        assertEquals("User renamed title", book?.title)
        assertEquals("Reader", book?.author)
        assertEquals(1234L, book?.lastReadAt)
        assertEquals("Offline", book?.collectionName)
        assertEquals(7, book?.sortOrder)
    }

    @Test
    fun repositoryRemovesDownloadedAssetThroughCache() = runTest {
        val dao = FakeBookDao()
        val cache = RecordingCache()
        val repository = LibraryRepository(dao, cache)

        val removed = repository.removeDownloadedAsset("calibre-42")

        assertTrue(removed)
        assertEquals(listOf("calibre-42"), cache.removedBookIds)
    }

    private fun cachedEntity(id: String, localUri: String, lastReadAt: Long) = BookEntity(
        id = id,
        title = id,
        author = "Author",
        format = BookFormat.EPUB.name,
        downloadStatus = DownloadStatus.DOWNLOADED.name,
        localUri = localUri,
        lastReadAt = lastReadAt,
    )

    private class RecordingCache : DownloadedBookCacheStore {
        val protectedBookIds = mutableListOf<String?>()
        val removedBookIds = mutableListOf<String>()
        override suspend fun trim(protectedBookId: String?): List<DownloadedCacheEviction> {
            protectedBookIds += protectedBookId
            return emptyList()
        }
        override suspend fun removeDownloadedAsset(bookId: String): DownloadedCacheEviction? {
            removedBookIds += bookId
            return DownloadedCacheEviction(bookId, localUri = "file:///books/$bookId.epub")
        }
    }

    private class FakeBookDao : BookDao {
        private val books = mutableMapOf<String, BookEntity>()
        override fun observeAll(): Flow<List<BookEntity>> = MutableStateFlow(books.values.toList())
        override fun observeShelf(): Flow<List<BookWithProgress>> = MutableStateFlow(
            books.values.map { BookWithProgress(it, progress = 0f) },
        )
        override suspend fun count(): Int = books.size
        override suspend fun upsert(book: BookEntity) {
            books[book.id] = book
        }
        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { upsert(it) }
        }
        override suspend fun getById(id: String): BookEntity? = books[id]
        override suspend fun updateLastReadAt(id: String, ts: Long) {
            books[id]?.let { books[id] = it.copy(lastReadAt = ts) }
        }
        override suspend fun downloadedRemoteCacheBooks(
            remotePrefix: String,
            downloadedStatus: String,
        ): List<DownloadedCacheBook> = books.values
            .filter { it.id.startsWith(remotePrefix) && it.downloadStatus == downloadedStatus && it.localUri != null }
            .map { DownloadedCacheBook(it.id, it.localUri, it.lastReadAt) }
        override suspend fun clearDownloadedAsset(id: String, downloadStatus: String) {
            books[id]?.let { books[id] = it.copy(downloadStatus = downloadStatus, localUri = null) }
        }
        override suspend fun deleteById(id: String) {
            books.remove(id)
        }
        override suspend fun updateTitle(id: String, title: String) {
            books[id]?.let { books[id] = it.copy(title = title) }
        }
        override suspend fun updateCollectionName(id: String, name: String?) {
            books[id]?.let { books[id] = it.copy(collectionName = name) }
        }
        override suspend fun renameCollection(oldName: String, newName: String) {
            books.replaceAll { _, book ->
                if (book.collectionName == oldName) book.copy(collectionName = newName) else book
            }
        }
        override suspend fun clearCollection(name: String) {
            books.replaceAll { _, book ->
                if (book.collectionName == name) book.copy(collectionName = null) else book
            }
        }
        override suspend fun updateSortOrder(id: String, order: Int) {
            books[id]?.let { books[id] = it.copy(sortOrder = order) }
        }
        fun book(id: String): BookEntity? = books[id]
    }
}
