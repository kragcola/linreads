package dev.readflow.features.library

import android.net.Uri
import dev.readflow.core.calibre.CalibreRepository
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.database.CoroutineBookAssetOperationCoordinator
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.BookRemovalMode
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.DownloadedAsset
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.LocalBookImporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun searchCalibreShowsRemoteResultsWithoutAddingThemToShelf() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val calibre = FakeCalibreRepository(
            searchResults = listOf(remoteBook("42", "Remote EPUB")),
        )
        val viewModel = viewModel(store = store, calibre = calibre)

        viewModel.updateCalibreQuery("remote")
        viewModel.searchCalibre()
        advanceUntilIdle()

        assertEquals("remote", calibre.searchedQuery)
        assertEquals(listOf(remoteBook("42", "Remote EPUB")), viewModel.calibreSearchState.value.results)
        assertEquals(emptyList<BookMeta>(), store.upsertedBooks)
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun latestCalibreSearchWinsWhenEarlierSearchIsStillRunning() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstSearchStarted = CompletableDeferred<Unit>()
        val releaseFirstSearch = CompletableDeferred<Unit>()
        val calibre = FakeCalibreRepository(
            searchHandler = { query ->
                if (query == "a") {
                    firstSearchStarted.complete(Unit)
                    releaseFirstSearch.await()
                }
                ReadflowResult.Success(listOf(remoteBook(query, "Result $query")))
            },
        )
        val viewModel = viewModel(calibre = calibre)

        viewModel.updateCalibreQuery("a")
        viewModel.searchCalibre()
        runCurrent()
        firstSearchStarted.await()
        viewModel.updateCalibreQuery("abc")
        viewModel.searchCalibre()
        runCurrent()
        releaseFirstSearch.complete(Unit)
        advanceUntilIdle()

        assertEquals("abc", viewModel.calibreSearchState.value.query)
        assertEquals(listOf(remoteBook("abc", "Result abc")), viewModel.calibreSearchState.value.results)
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
    fun downloadCalibreBookAddsDownloadedBookToShelf() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeLibraryStore()
        val downloaded = remoteBook("calibre-42", "Remote EPUB").copy(
            downloadStatus = DownloadStatus.DOWNLOADED,
            localUri = "file:///books/calibre-42.epub",
        )
        val calibre = FakeCalibreRepository(downloadResult = downloaded)
        val viewModel = viewModel(store = store, calibre = calibre)

        viewModel.downloadCalibreBook("42")
        advanceUntilIdle()

        assertEquals("42", calibre.downloadedBookId)
        assertEquals(listOf(downloaded), store.upsertedBooks)
        assertEquals("已下载《Remote EPUB》", viewModel.calibreSearchState.value.message)
    }

    @Test
    fun deleteAllCancelsMatchingDownloadBeforeDeletingBook() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val downloadStarted = CompletableDeferred<Unit>()
        val downloadCancelled = CompletableDeferred<Unit>()
        val store = FakeLibraryStore()
        val calibre = FakeCalibreRepository(
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

        viewModel.downloadCalibreBook("42")
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
        assertEquals("已移除本地下载", viewModel.calibreSearchState.value.message)
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
                LibraryItem.Bundle(BookBundle("Mixed", listOf(localBook, remoteOnly))),
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

    private fun viewModel(
        store: FakeLibraryStore = FakeLibraryStore(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        calibre: FakeCalibreRepository = FakeCalibreRepository(),
        localSource: LocalBookImporter = FakeLocalBookImporter(),
    ) = LibraryViewModel(
        repository = store,
        localSource = localSource,
        settings = settings,
        calibreRepositoryFactory = { calibre },
        assetOperations = CoroutineBookAssetOperationCoordinator(),
    )

    private class FakeLibraryStore(
        initialItems: List<LibraryItem> = emptyList(),
        private val removeDownloadedAssetResult: Boolean = false,
        private val deleteFailure: Throwable? = null,
    ) : LibraryStore {
        private val shelf = MutableStateFlow(initialItems)
        val upsertedBooks = mutableListOf<BookMeta>()
        val removedDownloadedAssetIds = mutableListOf<String>()
        val removedFromShelfIds = mutableListOf<String>()
        val deletedCompletelyIds = mutableListOf<String>()

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
        override suspend fun setCollection(id: String, name: String?) = Unit
        override suspend fun renameBundle(oldName: String, newName: String) = Unit
        override suspend fun ungroupBundle(name: String) = Unit
        override suspend fun updateSortOrder(id: String, order: Int) = Unit

        fun emitShelf(items: List<LibraryItem>) {
            shelf.value = items
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val calibreBaseUrl = MutableStateFlow<String?>("http://192.168.1.5:8080")
        override val fontSize = MutableStateFlow(18)
        override val lineSpacing = MutableStateFlow(1.75f)
        override val readingMode = MutableStateFlow(ReaderReadingMode.SCROLL)
        override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        override val deviceId = MutableStateFlow("device")
        override val engineOverrides = MutableStateFlow(emptyMap<BookFormat, String>())
        override val useSourceHanFont = MutableStateFlow(true)
        override val txtEncoding = MutableStateFlow(TxtEncoding.AUTO)
        override val fontChoice = MutableStateFlow<FontChoice>(FontChoice.SourceHan)
        override val readerGuideShown = MutableStateFlow(true)
        override val pageFlipStyle = MutableStateFlow(dev.readflow.core.model.PageFlipStyle.SLIDE)
        override suspend fun setCalibreBaseUrl(url: String) {
            calibreBaseUrl.value = url
        }
        override suspend fun setFontSize(size: Int) = Unit
        override suspend fun setLineSpacing(multiplier: Float) = Unit
        override suspend fun setReadingMode(mode: ReaderReadingMode) = Unit
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setEngineOverride(format: BookFormat, engineId: String?) = Unit
        override suspend fun setUseSourceHanFont(enabled: Boolean) = Unit
        override suspend fun setTxtEncoding(encoding: TxtEncoding) = Unit
        override suspend fun setFontChoice(choice: FontChoice) = Unit
        override suspend fun setReaderGuideShown(shown: Boolean) = Unit
        override suspend fun setPageFlipStyle(style: dev.readflow.core.model.PageFlipStyle) = Unit
    }

    private class FakeCalibreRepository(
        private val searchResults: List<BookMeta> = emptyList(),
        private val downloadResult: BookMeta = remoteBook("calibre-42", "Remote EPUB"),
        private val searchHandler: (suspend (String) -> ReadflowResult<List<BookMeta>>)? = null,
        private val downloadHandler: (suspend (String) -> ReadflowResult<BookMeta>)? = null,
    ) : CalibreRepository {
        var searchedQuery: String? = null
        var downloadedBookId: String? = null

        override suspend fun search(query: String, offset: Int, limit: Int): ReadflowResult<List<BookMeta>> {
            searchedQuery = query
            return searchHandler?.invoke(query) ?: ReadflowResult.Success(searchResults)
        }

        override suspend fun metadata(bookId: String): ReadflowResult<BookMeta> =
            ReadflowResult.Success(remoteBook(bookId, "Remote EPUB"))

        override suspend fun download(bookId: String): ReadflowResult<BookMeta> {
            downloadedBookId = bookId
            return downloadHandler?.invoke(bookId) ?: ReadflowResult.Success(downloadResult)
        }
    }

    private class FakeLocalBookImporter(
        private val results: ArrayDeque<ReadflowResult<Pair<BookMeta, DownloadedAsset>>> = ArrayDeque(),
    ) : LocalBookImporter {
        override suspend fun import(uri: Uri, mimeType: String?) = results.removeFirstOrNull()
            ?: throw UnsupportedOperationException("No import result configured")
    }

    private companion object {
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
