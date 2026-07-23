package dev.readflow.features.library

import android.net.Uri
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.database.CoroutineBookAssetOperationCoordinator
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.BookRemovalMode
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.DownloadedAsset
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.LocalBookImporter
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.RemoteBookKey
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun deleteModeDispatchesToMatchingRepositoryOperation() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val viewModel = viewModel(store = store)

        viewModel.deleteBook("keep-data", BookRemovalMode.REMOVE_FROM_SHELF)
        viewModel.deleteBook("delete-all", BookRemovalMode.DELETE_ALL)
        advanceUntilIdle()

        assertEquals(listOf("keep-data"), store.removedFromShelfIds)
        assertEquals(listOf("delete-all"), store.deletedCompletelyIds)
    }

    @Test
    fun shelfEmissionClearsDeleteFailureAndRestoresLibraryContent() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val original = localBook("local-1", "Original")
        val recovered = localBook("local-2", "Recovered")
        val store = FakeLibraryStore(
            initialItems = listOf(LibraryItem.Single(original)),
            deleteFailure = IllegalStateException("delete failed"),
        )
        val viewModel = viewModel(store = store)
        advanceUntilIdle()

        viewModel.deleteBook(original.id, BookRemovalMode.REMOVE_FROM_SHELF)
        advanceUntilIdle()
        assertEquals("delete failed", viewModel.uiState.value.error)

        store.emitShelf(listOf(LibraryItem.Single(recovered)))
        advanceUntilIdle()

        assertEquals(listOf(recovered.id), viewModel.uiState.value.items.map { (it as LibraryItem.Single).book.id })
        assertEquals("a successful shelf emission must clear the page-level delete error", null, viewModel.uiState.value.error)
    }

    @Test
    fun calibreAdapterSearchShowsRemoteResultsWithoutAddingThemToShelf() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val calibre = FakeCalibreCatalog(
            searchResults = listOf(remoteBook("42", "Remote EPUB")),
        )
        val viewModel = viewModel(store = store, calibre = calibre)

        advanceUntilIdle()
        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.updateOnlineQuery("remote")
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()

        assertEquals("remote", calibre.searchedQuery)
        assertEquals(
            listOf(remoteBook("42", "Remote EPUB")),
            viewModel.onlineLibraryState.value.results.map(OnlineCatalogEntry::meta),
        )
        assertEquals(emptyList<BookMeta>(), store.upsertedBooks)
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun latestCalibreAdapterSearchWinsWhenEarlierSearchIsStillRunning() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstSearchStarted = CompletableDeferred<Unit>()
        val releaseFirstSearch = CompletableDeferred<Unit>()
        val calibre = FakeCalibreCatalog(
            searchHandler = { query ->
                if (query == "a") {
                    firstSearchStarted.complete(Unit)
                    releaseFirstSearch.await()
                }
                ReadflowResult.Success(listOf(remoteBook(query, "Result $query")))
            },
        )
        val viewModel = viewModel(calibre = calibre)

        advanceUntilIdle()
        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.updateOnlineQuery("a")
        viewModel.searchOnlineLibrary()
        runCurrent()
        firstSearchStarted.await()
        viewModel.updateOnlineQuery("abc")
        viewModel.searchOnlineLibrary()
        runCurrent()
        releaseFirstSearch.complete(Unit)
        advanceUntilIdle()

        assertEquals("abc", viewModel.onlineLibraryState.value.query)
        assertEquals(
            listOf(remoteBook("abc", "Result abc")),
            viewModel.onlineLibraryState.value.results.map(OnlineCatalogEntry::meta),
        )
    }

    @Test
    fun folderImportReportsPartialFailuresAndKeepsSuccessfulBooks() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val successfulBook = localBook("local-1", "Local TXT")
        val importer = FakeLocalBookImporter(
            results = ArrayDeque(
                listOf(
                    ReadflowResult.Success(
                    successfulBook to DownloadedAsset(
                        bookId = successfulBook.id,
                        format = "txt",
                        localUri = successfulBook.localUri.orEmpty(),
                        sizeBytes = 12,
                    ),
                ),
                    ReadflowResult.Failure(ReadflowError.io("cannot read")),
                ),
            ),
        )
        val viewModel = viewModel(store = store, localSource = importer)

        viewModel.importFromFolder(listOf(Uri.EMPTY, Uri.EMPTY))
        advanceUntilIdle()

        assertEquals(listOf(successfulBook), store.upsertedBooks)
        assertEquals("已导入 1 本，1 个文件失败", viewModel.uiState.value.error)
    }

    @Test
    fun calibreAdapterDownloadAddsDownloadedBookToShelf() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val downloaded = remoteBook("calibre-42", "Remote EPUB").copy(
            downloadStatus = DownloadStatus.DOWNLOADED,
            localUri = "file:///books/calibre-42.epub",
        )
        val calibre = FakeCalibreCatalog(
            searchResults = listOf(remoteBook("42", "Remote EPUB")),
            downloadResult = downloaded,
        )
        val viewModel = viewModel(store = store, calibre = calibre)

        advanceUntilIdle()
        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        viewModel.downloadOnlineEntry(viewModel.onlineLibraryState.value.results.single())
        advanceUntilIdle()

        assertEquals("42", calibre.downloadedBookId)
        assertEquals(listOf(downloaded), store.upsertedBooks)
        assertEquals("已下载《Remote EPUB》", viewModel.onlineLibraryState.value.message)
    }

    @Test
    fun deleteAllCancelsMatchingDownloadBeforeDeletingBook() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val downloadStarted = CompletableDeferred<Unit>()
        val downloadCancelled = CompletableDeferred<Unit>()
        val store = FakeLibraryStore()
        val calibre = FakeCalibreCatalog(
            searchResults = listOf(remoteBook("42", "Remote EPUB")),
            downloadHandler = {
                downloadStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    downloadCancelled.complete(Unit)
                }
            },
        )
        val viewModel = viewModel(store = store, calibre = calibre)

        advanceUntilIdle()
        viewModel.selectOnlineSource(BUILTIN_CALIBRE_SOURCE_ID)
        viewModel.searchOnlineLibrary()
        advanceUntilIdle()
        viewModel.downloadOnlineEntry(viewModel.onlineLibraryState.value.results.single())
        runCurrent()
        downloadStarted.await()
        viewModel.deleteBook("calibre-42", BookRemovalMode.DELETE_ALL)
        runCurrent()

        assertTrue("delete must cancel and join the matching managed-asset producer", downloadCancelled.isCompleted)
        assertEquals(listOf("calibre-42"), store.deletedCompletelyIds)
        assertTrue("a cancelled download must never upsert after complete deletion", store.upsertedBooks.isEmpty())
    }

    @Test
    fun removeDownloadedAssetDelegatesToStoreAndShowsMessage() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore(removeDownloadedAssetResult = true)
        val viewModel = viewModel(store = store)

        viewModel.removeDownloadedAsset("calibre-42")
        advanceUntilIdle()

        assertEquals(listOf("calibre-42"), store.removedDownloadedAssetIds)
        assertEquals("已移除本地下载", viewModel.onlineLibraryState.value.message)
    }

    @Test
    fun offlineFilterShowsOnlyLocallyReadableBooks() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val localBook = localBook("local-1", "Local TXT")
        val downloadedRemote = remoteBook("calibre-42", "Downloaded EPUB").copy(
            downloadStatus = DownloadStatus.DOWNLOADED,
            localUri = "file:///books/calibre-42.epub",
        )
        val remoteOnly = remoteBook("43", "Remote Only")
        val store = FakeLibraryStore(
            initialItems = listOf(
                LibraryItem.Single(localBook),
                LibraryItem.Single(downloadedRemote),
                LibraryItem.Single(remoteOnly),
            ),
        )
        val viewModel = viewModel(store = store)
        advanceUntilIdle()

        viewModel.setLibraryFilter(LibraryFilter.OFFLINE)
        advanceUntilIdle()

        assertEquals(LibraryFilter.OFFLINE, viewModel.uiState.value.filter)
        assertEquals(2, viewModel.uiState.value.offlineCount)
        assertEquals(
            listOf(localBook.id, downloadedRemote.id),
            viewModel.uiState.value.items.filterIsInstance<LibraryItem.Single>().map { it.book.id },
        )
    }

    @Test
    fun offlineFilterKeepsBundlesWithOfflineMembersOnly() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val localBook = localBook("local-1", "Local TXT")
        val remoteOnly = remoteBook("43", "Remote Only")
        val store = FakeLibraryStore(
            initialItems = listOf(
                LibraryItem.Bundle(
                    BookBundle(
                        id = "Mixed",
                        name = "Mixed",
                        books = listOf(localBook, remoteOnly),
                    ),
                ),
            ),
        )
        val viewModel = viewModel(store = store)
        advanceUntilIdle()

        viewModel.setLibraryFilter(LibraryFilter.OFFLINE)
        advanceUntilIdle()

        val bundle = viewModel.uiState.value.items.single() as LibraryItem.Bundle
        assertEquals("Mixed", bundle.bundle.name)
        assertEquals(listOf(localBook.id), bundle.bundle.books.map { it.id })
    }

    @Test
    fun deleteBundleRemovesAllOriginalMembersWhenOfflineFilterHidesRemoteMembers() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val collectionId = "collection-mixed-delete"
        val remoteBefore = remoteBook("remote-before", "Remote Before")
        val offlineMember = localBook("offline-member", "Offline Member")
        val remoteAfter = remoteBook("remote-after", "Remote After")
        val originalBooks = listOf(remoteBefore, offlineMember, remoteAfter)
        val store = FakeLibraryStore(
            initialItems = listOf(
                LibraryItem.Bundle(
                    BookBundle(
                        id = collectionId,
                        name = "Mixed Delete",
                        books = originalBooks,
                    ),
                ),
            ),
        )
        val viewModel = viewModel(store = store)
        advanceUntilIdle()

        viewModel.setLibraryFilter(LibraryFilter.OFFLINE)
        advanceUntilIdle()

        val visibleBundle = viewModel.uiState.value.items.single() as LibraryItem.Bundle
        assertEquals(collectionId, visibleBundle.bundle.id)
        assertEquals(listOf(offlineMember.id), visibleBundle.bundle.books.map { it.id })

        viewModel.deleteBundle(collectionId, BookRemovalMode.REMOVE_FROM_SHELF)
        advanceUntilIdle()

        assertEquals(originalBooks.map { it.id }, store.removedFromShelfIds)
    }

    @Test
    fun reorderPersistsOneFlattenedShelfOrder() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val first = localBook("first", "First")
        val groupedA = localBook("group-a", "Group A")
        val groupedB = localBook("group-b", "Group B")
        val store = FakeLibraryStore()
        val viewModel = viewModel(store = store)

        viewModel.reorder(
            listOf(
                LibraryItem.Single(first),
                LibraryItem.Bundle(
                    BookBundle(
                        id = "Group",
                        name = "Group",
                        books = listOf(groupedA, groupedB),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(listOf("first", "group-a", "group-b")), store.savedShelfOrders)
    }

    @Test
    fun offlineReorderPersistsHiddenBooksBetweenReorderedVisibleSlots() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val offlineA = localBook("offline-a", "Offline A")
        val hiddenRemote = remoteBook("hidden-remote", "Hidden Remote")
        val offlineB = localBook("offline-b", "Offline B")
        val store = FakeLibraryStore(
            initialItems = listOf(
                LibraryItem.Single(offlineA),
                LibraryItem.Single(hiddenRemote),
                LibraryItem.Single(offlineB),
            ),
        )
        val viewModel = viewModel(store = store)
        advanceUntilIdle()

        viewModel.setLibraryFilter(LibraryFilter.OFFLINE)
        advanceUntilIdle()
        viewModel.reorder(
            listOf(
                LibraryItem.Single(offlineB),
                LibraryItem.Single(offlineA),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(listOf("offline-b", "hidden-remote", "offline-a")),
            store.savedShelfOrders,
        )
    }

    @Test
    fun offlineReorderKeepsSameNameBundlesDistinctByBundleId() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstBundleBook = localBook("first-bundle-book", "First Bundle Book")
        val secondBundleBook = localBook("second-bundle-book", "Second Bundle Book")
        val hiddenRemote = remoteBook("hidden-remote", "Hidden Remote")
        val firstBundle = BookBundle(
            id = "first-bundle",
            name = "Same Name",
            books = listOf(firstBundleBook),
        )
        val secondBundle = BookBundle(
            id = "second-bundle",
            name = "Same Name",
            books = listOf(secondBundleBook),
        )
        val store = FakeLibraryStore(
            initialItems = listOf(
                LibraryItem.Bundle(firstBundle),
                LibraryItem.Single(hiddenRemote),
                LibraryItem.Bundle(secondBundle),
            ),
        )
        val viewModel = viewModel(store = store)
        advanceUntilIdle()

        viewModel.setLibraryFilter(LibraryFilter.OFFLINE)
        advanceUntilIdle()
        viewModel.reorder(
            listOf(
                LibraryItem.Bundle(secondBundle),
                LibraryItem.Bundle(firstBundle),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(listOf("second-bundle-book", "hidden-remote", "first-bundle-book")),
            store.savedShelfOrders,
        )
    }

    @Test
    fun reorderFailurePublishesARecoverableNotice() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore(
            updateShelfOrderFailure = IllegalStateException("书架状态已变化，请重试"),
        )
        val viewModel = viewModel(store = store)
        var completed: Boolean? = null

        viewModel.reorder(
            listOf(LibraryItem.Single(localBook("source-book", "Source"))),
        ) { completed = it }
        advanceUntilIdle()

        assertTrue(
            "reorder failure must surface a retryable notice",
            viewModel.uiState.value.notice?.contains("书架状态已变化，请重试") == true,
        )
        assertEquals(false, completed)
    }

    @Test
    fun reorderSuccessCompletesWithTrue() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val viewModel = viewModel()
        var completed: Boolean? = null

        viewModel.reorder(
            listOf(LibraryItem.Single(localBook("source-book", "Source"))),
        ) { completed = it }
        advanceUntilIdle()

        assertEquals(true, completed)
    }

    @Test
    fun ungroupFailurePublishesARecoverableNotice() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore(
            ungroupBundleFailure = IllegalStateException("书组状态已变化，请重试"),
        )
        val viewModel = viewModel(store = store)
        var completed: Boolean? = null

        viewModel.ungroupBundle("Reading List") { completed = it }
        advanceUntilIdle()

        assertTrue(
            "ungroup failure must surface a retryable notice",
            viewModel.uiState.value.notice?.contains("书组状态已变化，请重试") == true,
        )
        assertEquals(false, completed)
    }

    @Test
    fun ungroupSuccessCompletesWithTrue() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val viewModel = viewModel(store = store)
        var completed: Boolean? = null

        viewModel.ungroupBundle("collection-reading-list") { completed = it }
        advanceUntilIdle()

        assertEquals(true, completed)
        assertEquals(listOf("collection-reading-list"), store.ungroupedCollectionIds)
    }

    @Test
    fun renameBundleFailurePublishesARecoverableNotice() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore(
            renameBundleFailure = IllegalStateException("书组状态已变化，请重试"),
        )
        val viewModel = viewModel(store = store)
        var completed: Boolean? = null

        viewModel.renameBundle("Reading List", "Renamed List") { completed = it }
        advanceUntilIdle()

        assertTrue(
            "rename failure must surface a retryable notice",
            viewModel.uiState.value.notice?.contains("书组状态已变化，请重试") == true,
        )
        assertEquals(false, completed)
    }

    @Test
    fun renameBundleSuccessCompletesWithTrue() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val viewModel = viewModel(store = store)
        var completed: Boolean? = null

        viewModel.renameBundle("collection-reading-list", "Renamed List") { completed = it }
        advanceUntilIdle()

        assertEquals(true, completed)
        assertEquals(
            listOf("collection-reading-list" to "Renamed List"),
            store.renamedBundleCalls,
        )
    }

    @Test
    fun createGroupDelegatesToOneAtomicStoreOperation() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val viewModel = viewModel(store = store)

        viewModel.createGroup(
            sourceId = "source-book",
            targetId = "target-book",
            name = "Reading List",
        )
        advanceUntilIdle()

        assertEquals(
            listOf(Triple("source-book", "target-book", "Reading List")),
            store.createdGroupCalls,
        )
        assertEquals(emptyList<Triple<String, String?, String?>>(), store.setCollectionCalls)
    }

    @Test
    fun createGroupFailurePublishesARecoverableNotice() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore(
            createGroupFailure = IllegalStateException("书籍状态已变化，请重试"),
        )
        val viewModel = viewModel(store = store)

        viewModel.createGroup(
            sourceId = "source-book",
            targetId = "target-book",
            name = "Reading List",
        )
        advanceUntilIdle()

        assertEquals(
            "建组失败：书籍状态已变化，请重试",
            viewModel.uiState.value.notice,
        )
    }

    @Test
    fun moveToGroupFailurePublishesARecoverableNotice() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore(
            moveToGroupFailure = IllegalStateException("书籍状态已变化，请重试"),
        )
        val viewModel = viewModel(store = store)
        var completed: Boolean? = null

        viewModel.moveToGroup("source-book", "Reading List") { completed = it }
        advanceUntilIdle()

        assertEquals(
            "移动到书组失败：书籍状态已变化，请重试",
            viewModel.uiState.value.notice,
        )
        assertEquals(false, completed)
    }

    @Test
    fun moveToGroupSuccessCompletesAfterTheStoreWrite() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val viewModel = viewModel(store = store)
        var completed: Boolean? = null

        viewModel.moveToGroup("source-book", "collection-reading-list") { completed = it }
        advanceUntilIdle()

        assertEquals(listOf("source-book" to "collection-reading-list"), store.movedToGroupCalls)
        assertEquals(true, completed)
    }

    private fun viewModel(
        store: FakeLibraryStore = FakeLibraryStore(),
        calibre: FakeCalibreCatalog = FakeCalibreCatalog(),
        localSource: LocalBookImporter = FakeLocalBookImporter(),
    ) = LibraryViewModel(
        repository = store,
        localSource = localSource,
        assetOperations = CoroutineBookAssetOperationCoordinator(),
        sourceRegistry = FakeCalibreSourceRegistry(calibre),
    )

    private class FakeLibraryStore(
        initialItems: List<LibraryItem> = emptyList(),
        private val removeDownloadedAssetResult: Boolean = false,
        private val deleteFailure: Throwable? = null,
        private val createGroupFailure: Throwable? = null,
        private val moveToGroupFailure: Throwable? = null,
        private val updateShelfOrderFailure: Throwable? = null,
        private val renameBundleFailure: Throwable? = null,
        private val ungroupBundleFailure: Throwable? = null,
    ) : LibraryStore {
        private val shelf = MutableStateFlow(initialItems)
        val upsertedBooks = mutableListOf<BookMeta>()
        val removedDownloadedAssetIds = mutableListOf<String>()
        val removedFromShelfIds = mutableListOf<String>()
        val deletedCompletelyIds = mutableListOf<String>()
        val savedShelfOrders = mutableListOf<List<String>>()
        val createdGroupCalls = mutableListOf<Triple<String, String, String>>()
        val setCollectionCalls = mutableListOf<Triple<String, String?, String?>>()
        val movedToGroupCalls = mutableListOf<Pair<String, String>>()
        val renamedBundleCalls = mutableListOf<Pair<String, String>>()
        val ungroupedCollectionIds = mutableListOf<String>()

        override fun observeShelf(): Flow<List<LibraryItem>> = shelf
        override suspend fun count(): Int = upsertedBooks.size
        override suspend fun upsertBook(book: BookMeta) {
            upsertedBooks += book
            shelf.value = upsertedBooks.map { LibraryItem.Single(it) }
        }
        override suspend fun upsertAll(books: List<BookMeta>) {
            books.forEach { upsertBook(it) }
        }
        override suspend fun deleteBook(id: String) {
            deleteFailure?.let { throw it }
            removedFromShelfIds += id
        }
        override suspend fun deleteBookCompletely(id: String) { deletedCompletelyIds += id }
        override suspend fun removeDownloadedAsset(id: String): Boolean {
            removedDownloadedAssetIds += id
            return removeDownloadedAssetResult
        }
        override suspend fun renameBook(id: String, title: String) = Unit
        override suspend fun setCollection(id: String, collectionId: String?, name: String?) {
            setCollectionCalls += Triple(id, collectionId, name)
        }
        override suspend fun moveToGroup(sourceId: String, targetCollectionId: String) {
            moveToGroupFailure?.let { throw it }
            movedToGroupCalls += sourceId to targetCollectionId
        }
        override suspend fun createGroup(sourceId: String, targetId: String, name: String) {
            createGroupFailure?.let { throw it }
            createdGroupCalls += Triple(sourceId, targetId, name)
        }
        override suspend fun renameBundle(collectionId: String, newName: String) {
            renameBundleFailure?.let { throw it }
            renamedBundleCalls += collectionId to newName
        }
        override suspend fun ungroupBundle(collectionId: String) {
            ungroupBundleFailure?.let { throw it }
            ungroupedCollectionIds += collectionId
        }
        override suspend fun updateShelfOrder(ids: List<String>) {
            updateShelfOrderFailure?.let { throw it }
            savedShelfOrders += ids
        }

        fun emitShelf(items: List<LibraryItem>) {
            shelf.value = items
        }
    }

    private class FakeCalibreCatalog(
        private val searchResults: List<BookMeta> = emptyList(),
        private val downloadResult: BookMeta = remoteBook("calibre-42", "Remote EPUB"),
        private val searchHandler: (suspend (String) -> ReadflowResult<List<BookMeta>>)? = null,
        private val downloadHandler: (suspend (String) -> ReadflowResult<BookMeta>)? = null,
    ) : OnlineBookCatalog {
        override val descriptor = CALIBRE_DESCRIPTOR
        var searchedQuery: String? = null
        var downloadedBookId: String? = null

        override suspend fun search(
            query: String,
            filter: OnlineCatalogFilter,
            offset: Int,
            limit: Int,
        ): ReadflowResult<List<OnlineCatalogEntry>> {
            searchedQuery = query
            return when (val result = searchHandler?.invoke(query) ?: ReadflowResult.Success(searchResults)) {
                is ReadflowResult.Success -> ReadflowResult.Success(
                    result.value.map { book ->
                        OnlineCatalogEntry(
                            meta = book,
                            remoteKey = RemoteBookKey(BUILTIN_CALIBRE_SOURCE_ID, book.id.removePrefix("calibre-")),
                        )
                    },
                )
                is ReadflowResult.Failure -> result
            }
        }

        override suspend fun download(entry: OnlineCatalogEntry): ReadflowResult<BookMeta> {
            val bookId = entry.remoteKey?.remoteId ?: entry.meta.id.removePrefix("calibre-")
            downloadedBookId = bookId
            return downloadHandler?.invoke(bookId) ?: ReadflowResult.Success(downloadResult)
        }
    }

    private class FakeCalibreSourceRegistry(
        private val catalog: OnlineBookCatalog,
    ) : SourceRegistry {
        override fun observeSources(): Flow<List<SourceDescriptor>> = flowOf(listOf(CALIBRE_DESCRIPTOR))

        override suspend fun openCatalog(sourceId: String): ReadflowResult<OnlineBookCatalog> =
            if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                ReadflowResult.Success(catalog)
            } else {
                ReadflowResult.Failure(ReadflowError.notFound("source", sourceId))
            }

        override suspend fun removeUserSource(sourceId: String) =
            ReadflowResult.Failure(ReadflowError.unsupported("unused"))
    }

    private class FakeLocalBookImporter(
        private val results: ArrayDeque<ReadflowResult<Pair<BookMeta, DownloadedAsset>>> = ArrayDeque(),
    ) : LocalBookImporter {
        override suspend fun import(uri: Uri, mimeType: String?) = results.removeFirstOrNull()
            ?: throw UnsupportedOperationException("No import result configured")
    }

    private companion object {
        val CALIBRE_DESCRIPTOR = SourceDescriptor(
            id = BUILTIN_CALIBRE_SOURCE_ID,
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Calibre",
            configVersion = 1,
            configJson = "{\"baseUrl\":\"http://192.168.1.5:8080\"}",
            baseUrl = "http://192.168.1.5:8080",
            enabled = true,
            isBuiltin = true,
            capabilities = SourceCapabilities(canSearch = true, canDownload = true),
        )

        fun localBook(id: String, title: String) = BookMeta(
            id = id,
            title = title,
            author = "Author",
            format = BookFormat.TXT,
            localUri = "file:///books/$id.txt",
        )

        fun remoteBook(id: String, title: String) = BookMeta(
            id = id,
            title = title,
            author = "Author",
            format = BookFormat.EPUB,
            coverUrl = "http://192.168.1.5:8080/get/cover/42/calibre-library",
        )
    }
}
