package dev.readflow.core.database

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
                collectionId = "group-offline",
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
        assertEquals("group-offline", book?.collectionId)
        assertEquals("Offline", book?.collectionName)
        assertEquals(7, book?.sortOrder)
    }

    @Test
    fun repositoryPreservesLocalShelfStateDuringBatchImport() = runTest {
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
                collectionId = "group-offline",
                collectionName = "Offline",
                sortOrder = 7,
            ),
        )

        repository.upsertAll(
            listOf(
                BookMeta(
                    id = "local-txt-stable",
                    title = "incoming-filename",
                    author = "未知作者",
                    format = BookFormat.TXT,
                    downloadStatus = DownloadStatus.DOWNLOADED,
                    localUri = "file:///books/local-txt-stable.txt",
                ),
            ),
        )

        val book = dao.book("local-txt-stable")
        assertEquals("User renamed title", book?.title)
        assertEquals("Reader", book?.author)
        assertEquals(1234L, book?.lastReadAt)
        assertEquals("group-offline", book?.collectionId)
        assertEquals("Offline", book?.collectionName)
        assertEquals(7, book?.sortOrder)
    }

    @Test
    fun repositorySerializesGroupCreationWithShelfUpsert() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(shelfEntity(id = "source", title = "Source", lastReadAt = null))
        dao.upsert(shelfEntity(id = "target", title = "Target", lastReadAt = null, sortOrder = 1))
        val upsertReadStarted = CompletableDeferred<Unit>()
        val allowUpsertToContinue = CompletableDeferred<Unit>()
        dao.beforeGetById = {
            upsertReadStarted.complete(Unit)
            allowUpsertToContinue.await()
        }

        val upsertJob = launch {
            repository.upsertBook(
                BookMeta(
                    id = "source",
                    title = "Refreshed Source",
                    author = "Author",
                    format = BookFormat.EPUB,
                    downloadStatus = DownloadStatus.DOWNLOADED,
                ),
            )
        }
        upsertReadStarted.await()
        val createGroupJob = launch {
            repository.createGroup("source", "target", "Reading List")
        }
        yield()
        val groupWriteRanWhileUpsertWasBlocked = dao.createGroupCalls > 0

        allowUpsertToContinue.complete(Unit)
        upsertJob.join()
        createGroupJob.join()

        assertFalse(groupWriteRanWhileUpsertWasBlocked)
        assertEquals("Reading List", dao.book("source")?.collectionName)
        assertEquals("Reading List", dao.book("target")?.collectionName)
    }

    @Test
    fun repositorySerializesMoveToGroupWithShelfUpsert() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(shelfEntity(id = "source", title = "Source", lastReadAt = null))
        dao.upsert(
            shelfEntity(
                id = "target-member",
                title = "Target",
                lastReadAt = null,
                collectionId = "Reading List",
                collectionName = "Reading List",
                sortOrder = 1,
            ),
        )
        val upsertReadStarted = CompletableDeferred<Unit>()
        val allowUpsertToContinue = CompletableDeferred<Unit>()
        dao.beforeGetById = {
            upsertReadStarted.complete(Unit)
            allowUpsertToContinue.await()
        }

        val upsertJob = launch {
            repository.upsertBook(
                BookMeta(
                    id = "source",
                    title = "Refreshed Source",
                    author = "Author",
                    format = BookFormat.EPUB,
                    downloadStatus = DownloadStatus.DOWNLOADED,
                ),
            )
        }
        upsertReadStarted.await()
        val moveJob = launch {
            repository.moveToGroup("source", "Reading List")
        }
        yield()
        val groupWriteRanWhileUpsertWasBlocked = dao.updateCollectionNameCalls > 0

        allowUpsertToContinue.complete(Unit)
        upsertJob.join()
        moveJob.join()

        assertFalse(groupWriteRanWhileUpsertWasBlocked)
        assertEquals("Reading List", dao.book("source")?.collectionName)
    }

    @Test
    fun repositoryRejectsRenameWhenTheGroupDisappeared() = runTest {
        val repository = LibraryRepository(FakeBookDao(), RecordingCache())

        val failure = runCatching {
            repository.renameBundle("Missing Group", "Renamed Group")
        }.exceptionOrNull()

        assertTrue(
            "renaming a missing group must fail as a recoverable state change",
            failure is IllegalStateException && failure.message?.contains("状态已变化") == true,
        )
    }

    @Test
    fun repositoryRejectsUngroupWhenTheGroupDisappeared() = runTest {
        val repository = LibraryRepository(FakeBookDao(), RecordingCache())

        val failure = runCatching {
            repository.ungroupBundle("Missing Group")
        }.exceptionOrNull()

        assertTrue(
            "ungrouping a missing group must fail as a recoverable state change",
            failure is IllegalStateException && failure.message?.contains("状态已变化") == true,
        )
    }

    @Test
    fun shelfOrderDoesNotChangeWhenLastReadTimestampChanges() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(shelfEntity(id = "alpha", title = "Alpha", lastReadAt = 100L))
        dao.upsert(shelfEntity(id = "beta", title = "Beta", lastReadAt = 200L))

        val before = repository.observeShelf().first().singleBookIds()
        dao.updateLastReadAt("alpha", 300L)
        val after = repository.observeShelf().first().singleBookIds()

        assertEquals("opening and closing a book must not move neighboring shelf items", before, after)
    }

    @Test
    fun repositoryFreezesLegacyTiedRowsInTheirPreviouslyVisibleOrder() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(shelfEntity(id = "older", title = "A older", lastReadAt = 100L))
        dao.upsert(shelfEntity(id = "recent", title = "Z recent", lastReadAt = 200L))

        val visibleIds = repository.observeShelf().first().singleBookIds()

        assertEquals(listOf("recent", "older"), visibleIds)
        assertEquals(0, dao.book("recent")?.sortOrder)
        assertEquals(1, dao.book("older")?.sortOrder)
    }

    @Test
    fun lastReadWriteFreezesLegacyOrderBeforeChangingTheTimestamp() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(shelfEntity(id = "older", title = "A older", lastReadAt = 100L))
        dao.upsert(shelfEntity(id = "recent", title = "Z recent", lastReadAt = 200L))

        dao.updateLastReadAt("older", 300L)

        assertEquals(
            listOf("recent", "older"),
            repository.observeShelf().first().singleBookIds(),
        )
    }

    @Test
    fun repositoryPreservesRemoteShelfPositionWhenMetadataRefreshes() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(
            shelfEntity(
                id = "calibre-42",
                title = "Old metadata",
                lastReadAt = 1234L,
                collectionName = "Favorites",
                sortOrder = 7,
            ),
        )
        dao.upsert(shelfEntity(id = "neighbor", title = "Middle", lastReadAt = 5678L, sortOrder = 8))
        val orderBeforeRefresh = repository.observeShelf().first().bookIdsInShelfOrder()

        repository.upsertBook(
            BookMeta(
                id = "calibre-42",
                title = "Fresh metadata",
                author = "Updated author",
                format = BookFormat.EPUB,
                downloadStatus = DownloadStatus.DOWNLOADED,
                localUri = "file:///books/calibre-42.epub",
            ),
        )

        val book = dao.book("calibre-42")
        assertEquals("Fresh metadata", book?.title)
        assertEquals("Updated author", book?.author)
        assertEquals(1234L, book?.lastReadAt)
        assertEquals("Favorites", book?.collectionName)
        assertEquals(7, book?.sortOrder)
        assertEquals(orderBeforeRefresh, repository.observeShelf().first().bookIdsInShelfOrder())
    }

    @Test
    fun repositoryAppendsANewBookAfterExistingManualOrder() = runTest {
        val dao = FakeBookDao()
        val repository = LibraryRepository(dao, RecordingCache())
        dao.upsert(shelfEntity(id = "existing", title = "Existing", lastReadAt = null, sortOrder = 5))

        repository.upsertBook(
            BookMeta(
                id = "new-book",
                title = "New book",
                author = "Author",
                format = BookFormat.EPUB,
                downloadStatus = DownloadStatus.DOWNLOADED,
                localUri = "file:///books/new.epub",
            ),
        )

        assertEquals(6, dao.book("new-book")?.sortOrder)
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
        var beforeGetById: (suspend () -> Unit)? = null
        var createGroupCalls: Int = 0
        var updateCollectionNameCalls: Int = 0
        override fun observeAll(): Flow<List<BookEntity>> = MutableStateFlow(books.values.toList())
        override fun observeShelf(): Flow<List<BookWithProgress>> = MutableStateFlow(
            books.values
                .sortedWith(
                    compareBy<BookEntity> { it.sortOrder }
                        .thenBy { it.lastReadAt == null }
                        .thenByDescending { it.lastReadAt }
                        .thenBy { it.title.lowercase() },
                )
                .map { BookWithProgress(it, progress = 0f) },
        )
        override suspend fun count(): Int = books.size
        override suspend fun maxSortOrder(): Int? = books.values.maxOfOrNull { it.sortOrder }
        override suspend fun shelfRowsForOrderNormalization(): List<BookEntity> = books.values
            .sortedWith(
                compareBy<BookEntity> { it.sortOrder }
                    .thenBy { it.lastReadAt == null }
                    .thenByDescending { it.lastReadAt }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id.lowercase() },
            )
        override suspend fun upsert(book: BookEntity) {
            books[book.id] = book
        }
        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { upsert(it) }
        }
        override suspend fun getById(id: String): BookEntity? {
            beforeGetById?.invoke()
            return books[id]
        }
        override suspend fun setLastReadAt(id: String, ts: Long) {
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
        override suspend fun updateCollection(
            id: String,
            collectionId: String?,
            name: String?,
        ): Int {
            updateCollectionNameCalls++
            val book = books[id] ?: return 0
            books[id] = book.copy(collectionId = collectionId, collectionName = name)
            return 1
        }
        override suspend fun moveToGroup(sourceId: String, targetCollectionId: String): Int {
            val source = books[sourceId] ?: return 0
            if (source.collectionId != null) return 0
            val target = books.values.firstOrNull {
                it.id != sourceId && it.collectionId == targetCollectionId
            } ?: return 0
            books[sourceId] = source.copy(
                collectionId = targetCollectionId,
                collectionName = target.collectionName,
            )
            updateCollectionNameCalls++
            return 1
        }
        override suspend fun createGroup(
            sourceId: String,
            targetId: String,
            collectionId: String,
            name: String,
        ): Int {
            createGroupCalls++
            val source = books[sourceId]
            val target = books[targetId]
            if (source?.collectionId != null || target?.collectionId != null || sourceId == targetId) {
                return 0
            }
            if (source == null || target == null) return 0
            updateCollection(sourceId, collectionId, name)
            updateCollection(targetId, collectionId, name)
            return 2
        }
        override suspend fun renameCollection(collectionId: String, newName: String): Int {
            val affected = books.values.count { it.collectionId == collectionId }
            books.replaceAll { _, book ->
                if (book.collectionId == collectionId) book.copy(collectionName = newName) else book
            }
            return affected
        }
        override suspend fun clearCollection(collectionId: String): Int {
            val affected = books.values.count { it.collectionId == collectionId }
            books.replaceAll { _, book ->
                if (book.collectionId == collectionId) {
                    book.copy(collectionId = null, collectionName = null)
                } else {
                    book
                }
            }
            return affected
        }
        override suspend fun updateSortOrder(id: String, order: Int) {
            books[id]?.let { books[id] = it.copy(sortOrder = order) }
        }
        fun book(id: String): BookEntity? = books[id]
    }

    private fun shelfEntity(
        id: String,
        title: String,
        lastReadAt: Long?,
        collectionName: String? = null,
        collectionId: String? = collectionName,
        sortOrder: Int = 0,
    ) = BookEntity(
        id = id,
        title = title,
        author = "Author",
        format = BookFormat.EPUB.name,
        downloadStatus = DownloadStatus.DOWNLOADED.name,
        lastReadAt = lastReadAt,
        collectionId = collectionId,
        collectionName = collectionName,
        sortOrder = sortOrder,
    )

    private fun List<LibraryItem>.singleBookIds(): List<String> =
        map { (it as LibraryItem.Single).book.id }

    private fun List<LibraryItem>.bookIdsInShelfOrder(): List<String> =
        flatMap { item ->
            when (item) {
                is LibraryItem.Single -> listOf(item.book.id)
                is LibraryItem.Bundle -> item.bundle.books.map { it.id }
            }
        }
}
