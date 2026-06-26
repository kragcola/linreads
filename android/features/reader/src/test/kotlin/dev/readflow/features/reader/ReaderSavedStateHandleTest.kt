package dev.readflow.features.reader

import android.net.Uri
import android.view.View
import androidx.lifecycle.SavedStateHandle
import dev.readflow.core.database.BookDao
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.BookWithProgress
import dev.readflow.core.database.BookmarkDao
import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.database.DownloadedCacheBook
import dev.readflow.core.database.ReadingProgressDao
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.TextAnnotationDao
import dev.readflow.core.database.TextAnnotationEntity
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ReaderState
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ReadingProgress
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.TransitionType
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.model.Bookmark
import dev.readflow.core.sync.NoOpSyncBackend
import dev.readflow.core.sync.SyncBackend
import dev.readflow.core.sync.SyncManager
import dev.readflow.render.api.EngineDescriptor
import dev.readflow.render.api.EngineStateStore
import dev.readflow.render.api.PageTransitionHost
import dev.readflow.render.api.PageTransitionHostFactory
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderEngineRegistry
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderSavedStateHandleTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `persists semantic reader state as SavedStateHandle json`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val handle = SavedStateHandle()
        val start = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f)
        val moved = Locator(LocatorStrategy.Page(index = 4, total = 10), totalProgression = 0.4f)
        val engine = FakeReaderEngine(start)
        val viewModel = readerViewModel(handle = handle, engine = engine)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.GoTo(moved))
        viewModel.onIntent(ReaderIntent.SetFontSize(22f))
        viewModel.onIntent(ReaderIntent.SetTheme(ThemeMode.DARK))
        viewModel.onIntent(ReaderIntent.ToggleChrome)
        advanceUntilIdle()

        val json = handle.get<String>(READER_STATE_SAVED_STATE_KEY)
        assertNotNull(json)
        val saved = Json.decodeFromString<ReaderState>(json!!)
        assertEquals("book-1", saved.bookId)
        assertEquals(moved, saved.currentLocator)
        assertEquals(22, saved.fontSize)
        assertEquals(ThemeMode.DARK, saved.theme)
        assertEquals(true, saved.isUiVisible)
        assertEquals(LoadingState.Loaded, saved.loadingState)
    }

    @Test
    fun `persists and restores paged reading mode`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val handle = SavedStateHandle()
        val settings = FakeSettingsRepository()
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(handle = handle, engine = engine, settings = settings)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetMode(ReadingMode.PAGED))
        advanceUntilIdle()

        val json = handle.get<String>(READER_STATE_SAVED_STATE_KEY)
        assertNotNull(json)
        val saved = Json.decodeFromString<ReaderState>(json!!)
        assertEquals(ReaderReadingMode.PAGED, saved.readingMode)
        assertEquals(ReaderReadingMode.PAGED, settings.readingMode.value)

        val reopenedEngine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val reopened = readerViewModel(handle = handle, engine = reopenedEngine, settings = settings)
        reopened.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(ReadingMode.PAGED, reopened.uiState.value.readingMode)
        assertEquals(PagingKind.PAGED, reopenedEngine.pagingKind.value)
    }

    @Test
    fun `restores paged reading mode from settings after destination is recreated`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            readingMode.value = ReaderReadingMode.PAGED
        }
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            settings = settings,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(ReadingMode.PAGED, viewModel.uiState.value.readingMode)
        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
    }

    @Test
    fun `restores json state snapshot and applies semantic locator on reopen`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val restoredLocator = Locator(
            LocatorStrategy.Section(spineIndex = 2, elementIndex = 8, charOffset = 40),
            totalProgression = 0.68f,
        )
        val error = ReadflowError.io("之前打开失败")
        val handle = SavedStateHandle(
            mapOf(
                READER_STATE_SAVED_STATE_KEY to Json.encodeToString(
                    ReaderState(
                        bookId = "book-1",
                        loadingState = LoadingState.Error(error),
                        currentLocator = restoredLocator,
                        fontSize = 24,
                        theme = ThemeMode.SEPIA,
                        isUiVisible = true,
                    ),
                ),
            ),
        )
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(handle = handle, engine = engine)

        assertEquals(LoadingState.Error(error), viewModel.uiState.value.loadingState)
        assertEquals(24f, viewModel.uiState.value.fontSizeSp)
        assertEquals(ThemeMode.SEPIA, viewModel.uiState.value.themeMode)
        assertEquals(true, viewModel.uiState.value.isUiVisible)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(listOf(restoredLocator), engine.goToLocators)
        assertEquals(restoredLocator, engine.currentLocator.value)
        assertEquals(listOf(24f), engine.fontSizes)
        assertEquals(listOf(ThemeMode.SEPIA), engine.themes)
        assertEquals(LoadingState.Loaded, viewModel.uiState.value.loadingState)
        assertEquals(24f, viewModel.uiState.value.fontSizeSp)
        assertEquals(ThemeMode.SEPIA, viewModel.uiState.value.themeMode)
        assertEquals(true, viewModel.uiState.value.isUiVisible)
    }

    @Test
    fun `restores engine acceleration state from store when opening book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val cacheState = byteArrayOf(7, 8, 9)
        val store = FakeEngineStateStore(initialStates = mapOf("book-1" to cacheState))
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(handle = SavedStateHandle(), engine = engine, engineStateStore = store)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(listOf("book-1"), store.loadBookIds)
        assertArrayEquals(cacheState, engine.restoredStates.single())
    }

    @Test
    fun `saves non-empty engine acceleration state before closing book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val cacheState = byteArrayOf(10, 11, 12)
        val events = mutableListOf<String>()
        val store = FakeEngineStateStore(events = events)
        val engine = FakeReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10)),
            events = events,
        ).apply {
            stateToSave = cacheState
        }
        val viewModel = readerViewModel(handle = SavedStateHandle(), engine = engine, engineStateStore = store)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(ReaderIntent.CloseBook)
        advanceUntilIdle()

        assertArrayEquals(cacheState, store.savedStates["book-1"])
        assertEquals(listOf("saveState", "storeSave:book-1", "close"), events)
    }

    @Test
    fun `does not write empty engine acceleration state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val store = FakeEngineStateStore()
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(handle = SavedStateHandle(), engine = engine, engineStateStore = store)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.CloseBook)
        advanceUntilIdle()

        assertNull(store.savedStates["book-1"])
    }

    @Test
    fun `applies remote sync winner to engine and local progress`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val localLocator = Locator(LocatorStrategy.Page(index = 1, total = 10), totalProgression = 0.1f)
        val remoteLocator = Locator(LocatorStrategy.Page(index = 8, total = 10), totalProgression = 0.8f)
        val remoteProgress = ReadingProgress(
            bookId = "book-1",
            locator = remoteLocator,
            progressPercent = 0.8f,
            updatedAt = Long.MAX_VALUE / 2,
            deviceId = "tablet",
        )
        val progressDao = FakeProgressDao()
        val engine = FakeReaderEngine(localLocator)
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
            syncManager = SyncManager(FakeSyncBackend(remoteProgress)),
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(listOf(remoteLocator), engine.goToLocators)
        assertEquals(remoteLocator, engine.currentLocator.value)
        val saved = progressDao.savedProgress("book-1")
        assertNotNull(saved)
        assertEquals(remoteLocator, Json.decodeFromString<Locator>(saved!!.locatorJson))
        assertEquals(0.8f, saved.totalProgression)
        assertEquals("tablet", saved.deviceId)
    }

    @Test
    fun `saves note annotation clears selection and exposes note in annotation state`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val selection = ReaderTextSelection(
            start = Locator(
                LocatorStrategy.Section(spineIndex = 0, elementIndex = 3, charOffset = 12),
                totalProgression = 0.24f,
            ),
            end = Locator(
                LocatorStrategy.Section(spineIndex = 0, elementIndex = 3, charOffset = 18),
                totalProgression = 0.26f,
            ),
            selectedText = "重点内容",
        )
        val engine = FakeReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10)),
        ).apply {
            emitSelection(selection)
        }
        val textAnnotationDao = FakeTextAnnotationDao()
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            textAnnotationDao = textAnnotationDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        assertEquals(selection, viewModel.uiState.value.textSelection)

        viewModel.onIntent(ReaderIntent.SaveTextAnnotation(note = "  这里要回看  "))
        advanceUntilIdle()

        val saved = textAnnotationDao.savedAnnotations("book-1").single()
        assertEquals("重点内容", saved.selectedText)
        assertEquals("这里要回看", saved.note)
        assertEquals(0x66FFE082, saved.color)
        val anchor = Json.decodeFromString<ReaderTextAnnotationAnchor>(saved.anchorJson)
        assertEquals(selection.start, anchor.start)
        assertEquals(selection.end, anchor.end)
        assertNull(viewModel.uiState.value.textSelection)
        assertEquals(1, engine.clearTextSelectionCalls)
        assertEquals(listOf("这里要回看"), viewModel.uiState.value.annotations.items.map { it.note })
        assertEquals(listOf("这里要回看"), engine.latestAnnotations.map { it.note })
    }

    @Test
    fun `go to annotation navigates engine and closes annotations panel`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val target = Locator(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 9, charOffset = 24),
            totalProgression = 0.72f,
        )
        val engine = FakeReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10)),
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.ANNOTATIONS))
        advanceUntilIdle()

        viewModel.onIntent(
            ReaderIntent.GoToAnnotation(
                ReaderAnnotationItem(
                    id = "annotation-1",
                    start = target,
                    end = target,
                    selectedText = "重点内容",
                    note = "这里要回看",
                    color = 0x66FFE082,
                    totalProgression = 0.72f,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(target, engine.currentLocator.value)
        assertEquals(listOf(target), engine.goToLocators)
        assertNull(viewModel.uiState.value.activePanel)
        assertEquals(false, viewModel.uiState.value.isUiVisible)
    }

    private fun readerViewModel(
        handle: SavedStateHandle,
        engine: FakeReaderEngine,
        engineStateStore: EngineStateStore = FakeEngineStateStore(),
        progressDao: FakeProgressDao = FakeProgressDao(),
        syncManager: SyncManager = SyncManager(NoOpSyncBackend()),
        textAnnotationDao: TextAnnotationDao = FakeTextAnnotationDao(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): ReaderViewModel =
        ReaderViewModel(
            savedStateHandle = handle,
            engineRegistry = ReaderEngineRegistry(
                descriptors = setOf(
                    EngineDescriptor(
                        id = engine.id,
                        format = BookFormat.TXT,
                        priority = 0,
                        quickSupports = { true },
                        provider = { engine },
                    ),
                ),
                userOverrides = MutableStateFlow(emptyMap()),
            ),
            hostFactory = FakeHostFactory,
            bookDao = FakeBookDao(
                BookEntity(
                    id = "book-1",
                    title = "Saved Book",
                    author = "Author",
                    format = BookFormat.TXT.name,
                    downloadStatus = "DOWNLOADED",
                    localUri = "file:///tmp/saved-book.txt",
                ),
            ),
            progressDao = progressDao,
            bookmarkDao = FakeBookmarkDao(),
            textAnnotationDao = textAnnotationDao,
            syncManager = syncManager,
            settings = settings,
            engineStateStore = engineStateStore,
        )

    private class FakeReaderEngine(
        initialLocator: Locator,
        private val events: MutableList<String> = mutableListOf(),
    ) : ReaderEngine, TextSelectableReaderEngine, TextAnnotatableReaderEngine {
        override val id: String = "fake-txt"
        override val format: BookFormat = BookFormat.TXT
        override val priority: Int = 0
        override val pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
        override val supportedModes = setOf(ReadingMode.SCROLL, ReadingMode.PAGED)
        override val supportsSearch: Boolean = false
        override val currentLocator = MutableStateFlow(initialLocator)
        override val currentTextSelection = MutableStateFlow<ReaderTextSelection?>(null)
        override val pageCount = MutableStateFlow(10)
        override val chapterInfo = MutableStateFlow(ChapterInfo(0, 1, "Chapter", 0f))
        override val tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
        val goToLocators = mutableListOf<Locator>()
        val fontSizes = mutableListOf<Float>()
        val themes = mutableListOf<ThemeMode>()
        val restoredStates = mutableListOf<ByteArray>()
        var clearTextSelectionCalls = 0
        var latestAnnotations: List<ReaderTextAnnotation> = emptyList()
        var stateToSave: ByteArray = ByteArray(0)

        override suspend fun supports(uri: Uri): Boolean = true
        override suspend fun openBook(uri: Uri): Locator = currentLocator.value
        override fun createView(): View = error("FakeReaderEngine does not create Android views in unit tests")
        override suspend fun close() {
            events += "close"
        }
        override suspend fun goTo(locator: Locator) {
            goToLocators += locator
            currentLocator.value = locator
        }
        override suspend fun saveState(): ByteArray {
            events += "saveState"
            return stateToSave
        }
        override suspend fun restoreState(state: ByteArray) {
            restoredStates += state
        }
        override suspend fun setFontSize(sp: Float) {
            fontSizes += sp
        }
        override suspend fun setTheme(mode: ThemeMode) {
            themes += mode
        }
        override suspend fun setMode(mode: ReadingMode) {
            pagingKind.value = when (mode) {
                ReadingMode.SCROLL -> PagingKind.CONTINUOUS
                ReadingMode.PAGED -> PagingKind.PAGED
            }
        }

        override fun clearTextSelection() {
            clearTextSelectionCalls += 1
            currentTextSelection.value = null
        }

        override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
            latestAnnotations = annotations
        }

        fun emitSelection(selection: ReaderTextSelection?) {
            currentTextSelection.value = selection
        }
    }

    private class FakeEngineStateStore(
        initialStates: Map<String, ByteArray> = emptyMap(),
        private val events: MutableList<String> = mutableListOf(),
    ) : EngineStateStore {
        val loadBookIds = mutableListOf<String>()
        val savedStates = initialStates.toMutableMap()

        override suspend fun load(bookId: String): ByteArray? {
            loadBookIds += bookId
            return savedStates[bookId]
        }

        override suspend fun save(bookId: String, state: ByteArray) {
            events += "storeSave:$bookId"
            savedStates[bookId] = state
        }

        override suspend fun evict(bookId: String) {
            savedStates.remove(bookId)
        }
    }

    private object FakeHostFactory : PageTransitionHostFactory {
        override fun paged(transition: TransitionType): PageTransitionHost = FakeHost
        override fun continuous(): PageTransitionHost = FakeHost
    }

    private object FakeHost : PageTransitionHost {
        override fun hostView(): View = error("FakeHost does not create Android views in unit tests")
        override fun bind(engine: ReaderEngine) = Unit
        override fun setTransition(type: TransitionType) = Unit
        override fun setOffscreenPageLimit(limit: Int) = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
        override fun setOnPageSettled(callback: (pageIndex: Int) -> Unit) = Unit
        override fun unbind() = Unit
    }

    private class FakeBookDao(book: BookEntity) : BookDao {
        private val books = mutableMapOf(book.id to book)
        override fun observeAll(): Flow<List<BookEntity>> = MutableStateFlow(books.values.toList())
        override fun observeShelf(): Flow<List<BookWithProgress>> = MutableStateFlow(emptyList())
        override suspend fun count(): Int = books.size
        override suspend fun upsert(book: BookEntity) {
            books[book.id] = book
        }
        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { this.books[it.id] = it }
        }
        override suspend fun getById(id: String): BookEntity? = books[id]
        override suspend fun updateLastReadAt(id: String, ts: Long) = Unit
        override suspend fun downloadedRemoteCacheBooks(
            remotePrefix: String,
            downloadedStatus: String,
        ): List<DownloadedCacheBook> = emptyList()
        override suspend fun clearDownloadedAsset(id: String, downloadStatus: String) = Unit
        override suspend fun deleteById(id: String) {
            books.remove(id)
        }
        override suspend fun updateTitle(id: String, title: String) = Unit
        override suspend fun updateCollectionName(id: String, name: String?) = Unit
        override suspend fun renameCollection(oldName: String, newName: String) = Unit
        override suspend fun clearCollection(name: String) = Unit
        override suspend fun updateSortOrder(id: String, order: Int) = Unit
    }

    private class FakeProgressDao : ReadingProgressDao {
        private val progress = mutableMapOf<String, ReadingProgressEntity>()
        override suspend fun get(bookId: String): ReadingProgressEntity? = progress[bookId]
        override suspend fun allForBackup(): List<ReadingProgressEntity> = progress.values.toList()
        override suspend fun upsert(progress: ReadingProgressEntity) {
            this.progress[progress.bookId] = progress
        }
        fun savedProgress(bookId: String): ReadingProgressEntity? = progress[bookId]
    }

    private class FakeSyncBackend(
        private val remoteProgress: ReadingProgress?,
    ) : SyncBackend {
        override val backendId = "fake"
        override val isAvailable = true

        override suspend fun pushProgress(bookId: String, progress: ReadingProgress): ReadflowResult<Unit> =
            ReadflowResult.Success(Unit)

        override suspend fun pullProgress(bookId: String): ReadflowResult<ReadingProgress?> =
            ReadflowResult.Success(remoteProgress)

        override suspend fun pushBookmark(bookmark: Bookmark): ReadflowResult<Unit> =
            ReadflowResult.Success(Unit)

        override suspend fun pullBookmarks(bookId: String): ReadflowResult<List<Bookmark>> =
            ReadflowResult.Success(emptyList())
    }

    private class FakeBookmarkDao : BookmarkDao {
        override fun observeForBook(bookId: String): Flow<List<BookmarkEntity>> = MutableStateFlow(emptyList())
        override suspend fun getById(id: String): BookmarkEntity? = null
        override suspend fun allForBackup(): List<BookmarkEntity> = emptyList()
        override suspend fun upsert(bookmark: BookmarkEntity) = Unit
        override suspend fun markDeleted(id: String, updatedAt: Long, deviceId: String) = Unit
    }

    private class FakeTextAnnotationDao(
        initialAnnotations: List<TextAnnotationEntity> = emptyList(),
    ) : TextAnnotationDao {
        private val annotationsByBook = initialAnnotations
            .groupBy { it.bookId }
            .mapValues { (_, entries) -> entries.toMutableList() }
            .toMutableMap()
        private val flows = mutableMapOf<String, MutableStateFlow<List<TextAnnotationEntity>>>()

        override fun observeForBook(bookId: String): Flow<List<TextAnnotationEntity>> =
            flows.getOrPut(bookId) { MutableStateFlow(savedAnnotations(bookId)) }

        override suspend fun getById(id: String): TextAnnotationEntity? =
            annotationsByBook.values.firstNotNullOfOrNull { entries ->
                entries.firstOrNull { it.id == id }
            }

        override suspend fun allForBackup(): List<TextAnnotationEntity> =
            annotationsByBook.values.flatten()

        override suspend fun upsert(annotation: TextAnnotationEntity) {
            val entries = annotationsByBook.getOrPut(annotation.bookId) { mutableListOf() }
            val index = entries.indexOfFirst { it.id == annotation.id }
            if (index >= 0) {
                entries[index] = annotation
            } else {
                entries += annotation
            }
            flows.getOrPut(annotation.bookId) { MutableStateFlow(emptyList()) }.value = savedAnnotations(annotation.bookId)
        }

        fun savedAnnotations(bookId: String): List<TextAnnotationEntity> =
            annotationsByBook[bookId]?.toList().orEmpty()
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val calibreBaseUrl = MutableStateFlow<String?>(null)
        override val fontSize = MutableStateFlow(16)
        override val lineSpacing = MutableStateFlow(1.75f)
        override val readingMode = MutableStateFlow(ReaderReadingMode.SCROLL)
        override val themeMode = MutableStateFlow(ThemeMode.LIGHT)
        override val deviceId = MutableStateFlow("device-id")
        override val engineOverrides = MutableStateFlow(emptyMap<BookFormat, String>())

        override suspend fun setCalibreBaseUrl(url: String) {
            calibreBaseUrl.value = url
        }
        override suspend fun setFontSize(size: Int) {
            fontSize.value = size
        }
        override suspend fun setLineSpacing(multiplier: Float) {
            lineSpacing.value = multiplier
        }
        override suspend fun setReadingMode(mode: ReaderReadingMode) {
            readingMode.value = mode
        }
        override suspend fun setThemeMode(mode: ThemeMode) {
            themeMode.value = mode
        }
        override suspend fun setEngineOverride(format: BookFormat, engineId: String?) {
            engineOverrides.value = if (engineId == null) {
                engineOverrides.value - format
            } else {
                engineOverrides.value + (format to engineId)
            }
        }
    }
}
