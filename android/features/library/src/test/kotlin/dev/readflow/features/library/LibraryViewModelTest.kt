package dev.readflow.features.library

import dev.readflow.core.calibre.CalibreRepository
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.LocalBookImporter
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    ) = LibraryViewModel(
        repository = store,
        localSource = FakeLocalBookImporter(),
        settings = settings,
        calibreRepositoryFactory = { calibre },
    )

    private class FakeLibraryStore(
        initialItems: List<LibraryItem> = emptyList(),
        private val removeDownloadedAssetResult: Boolean = false,
    ) : LibraryStore {
        private val shelf = MutableStateFlow(initialItems)
        val upsertedBooks = mutableListOf<BookMeta>()
        val removedDownloadedAssetIds = mutableListOf<String>()

        override fun observeShelf(): Flow<List<LibraryItem>> = shelf
        override suspend fun count(): Int = upsertedBooks.size
        override suspend fun upsertBook(book: BookMeta) {
            upsertedBooks += book
            shelf.value = upsertedBooks.map { LibraryItem.Single(it) }
        }
        override suspend fun upsertAll(books: List<BookMeta>) {
            books.forEach { upsertBook(it) }
        }
        override suspend fun deleteBook(id: String) = Unit
        override suspend fun removeDownloadedAsset(id: String): Boolean {
            removedDownloadedAssetIds += id
            return removeDownloadedAssetResult
        }
        override suspend fun renameBook(id: String, title: String) = Unit
        override suspend fun setCollection(id: String, name: String?) = Unit
        override suspend fun renameBundle(oldName: String, newName: String) = Unit
        override suspend fun ungroupBundle(name: String) = Unit
        override suspend fun updateSortOrder(id: String, order: Int) = Unit
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
    ) : CalibreRepository {
        var searchedQuery: String? = null
        var downloadedBookId: String? = null

        override suspend fun search(query: String, offset: Int, limit: Int): ReadflowResult<List<BookMeta>> {
            searchedQuery = query
            return ReadflowResult.Success(searchResults)
        }

        override suspend fun metadata(bookId: String): ReadflowResult<BookMeta> =
            ReadflowResult.Success(remoteBook(bookId, "Remote EPUB"))

        override suspend fun download(bookId: String): ReadflowResult<BookMeta> {
            downloadedBookId = bookId
            return ReadflowResult.Success(downloadResult)
        }
    }

    private class FakeLocalBookImporter : LocalBookImporter {
        override suspend fun import(uri: Uri, mimeType: String?) =
            throw UnsupportedOperationException("Local import is not used by these tests")
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
