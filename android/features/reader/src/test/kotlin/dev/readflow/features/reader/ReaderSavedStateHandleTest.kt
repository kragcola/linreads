package dev.readflow.features.reader

import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.readflow.core.database.BookDao
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.BookWithProgress
import dev.readflow.core.database.BookmarkDao
import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.database.DownloadedCacheBook
import dev.readflow.core.database.ReadingProgressDao
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.ReadingSessionEntity
import dev.readflow.core.database.ReadingSessionDao
import dev.readflow.core.database.TextAnnotationDao
import dev.readflow.core.database.TextAnnotationEntity
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderState
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.ReadingProgress
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderMenuEntry
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.TransitionType
import dev.readflow.core.prefs.ReaderTypography
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.model.Bookmark
import dev.readflow.core.sync.NoOpSyncBackend
import dev.readflow.core.sync.SyncBackend
import dev.readflow.core.sync.SyncManager
import dev.readflow.render.api.EngineDescriptor
import dev.readflow.render.api.EngineStateStore
import dev.readflow.render.api.InitialLocatorAwareReaderEngine
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        viewModel.onIntent(ReaderIntent.SetLineSpacing(2.0f))
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
        val savedJson = Json.parseToJsonElement(json).jsonObject
        assertEquals(2.0f, savedJson.getValue("lineSpacing").jsonPrimitive.float, 0.001f)
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
    fun `selecting current reading mode does not ask engine to re-anchor`() = runTest(dispatcher) {
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
        engine.modeChanges.clear()

        viewModel.onIntent(ReaderIntent.SetMode(ReadingMode.PAGED))
        advanceUntilIdle()

        assertEquals("same-mode chip taps must not repaginate or re-anchor the reader", emptyList<ReadingMode>(), engine.modeChanges)
        assertEquals(ReaderReadingMode.PAGED, settings.readingMode.value)
    }

    @Test
    fun `direct uri open immediately hides the previous reader surface`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        assertEquals(LoadingState.Loaded, viewModel.uiState.value.loadingState)
        assertEquals(engine, viewModel.uiState.value.engine)

        viewModel.onIntent(ReaderIntent.OpenBook(Uri.parse("file:///tmp/direct-open.epub")))

        assertEquals(
            "direct URI open must show the loading surface immediately instead of leaving the previous reader visible",
            LoadingState.Loading,
            viewModel.uiState.value.loadingState,
        )
        assertNull(viewModel.uiState.value.engine)
    }

    @Test
    fun `consecutive direct uri opens close the previous engine once and attach the new engine`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstEngine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val secondEngine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 20)))
        val engines = ArrayDeque(listOf(firstEngine, secondEngine))
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = firstEngine,
            engineProvider = { engines.removeFirst() },
        )

        viewModel.onIntent(ReaderIntent.OpenBook(Uri.parse("file:///tmp/first-direct.txt")))
        advanceUntilIdle()
        assertEquals(firstEngine, viewModel.uiState.value.engine)

        viewModel.onIntent(ReaderIntent.OpenBook(Uri.parse("file:///tmp/second-direct.txt")))
        advanceUntilIdle()

        assertEquals(secondEngine, viewModel.uiState.value.engine)
        assertEquals("the second direct-open engine must remain active", 0, secondEngine.closeCalls)
        assertEquals("the replaced direct-open engine must be closed exactly once", 1, firstEngine.closeCalls)
    }

    @Test
    fun `ordinary failure while closing the previous book does not block the replacement open`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val firstEngine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val secondEngine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 20)))
        val engines = ArrayDeque(listOf(firstEngine, secondEngine))
        val progressDao = FakeProgressDao()
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = firstEngine,
            engineProvider = { engines.removeFirst() },
            progressDao = progressDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        progressDao.upsertFailure = IllegalStateException("old progress failed")

        viewModel.onIntent(ReaderIntent.OpenBook(Uri.parse("file:///tmp/replacement.txt")))
        advanceUntilIdle()

        assertEquals(LoadingState.Loaded, viewModel.uiState.value.loadingState)
        assertEquals(secondEngine, viewModel.uiState.value.engine)
        assertEquals(1, firstEngine.closeCalls)
        assertEquals(0, secondEngine.closeCalls)
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
                READER_TYPOGRAPHY_BASELINE_SAVED_STATE_KEY to ReaderTypography.BASELINE_VERSION,
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
    fun `restores saved line spacing before opening restored book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val restoredLocator = Locator(LocatorStrategy.Page(index = 4, total = 10), totalProgression = 0.4f)
        val handle = SavedStateHandle(
            mapOf(
                READER_TYPOGRAPHY_BASELINE_SAVED_STATE_KEY to ReaderTypography.BASELINE_VERSION,
                READER_STATE_SAVED_STATE_KEY to readerStateJsonWithLineSpacing(
                    ReaderState(
                        bookId = "book-1",
                        currentLocator = restoredLocator,
                        fontSize = 24,
                        readingMode = ReaderReadingMode.PAGED,
                        theme = ThemeMode.SEPIA,
                    ),
                    lineSpacing = 2.0f,
                ),
            ),
        )
        val settings = FakeSettingsRepository().apply {
            lineSpacing.value = 1.1f
            fontSize.value = 16
            readingMode.value = ReaderReadingMode.SCROLL
            themeMode.value = ThemeMode.LIGHT
        }
        val events = mutableListOf<String>()
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = events,
        )
        val viewModel = readerViewModel(
            handle = handle,
            engine = engine,
            settings = settings,
        )

        assertEquals(2.0f, viewModel.uiState.value.lineSpacing, 0.001f)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        val openIndex = events.indexOf("open:0.4")
        assertTrue(
            "saved line spacing must be applied before openBook, events=$events",
            events.indexOf("setLineSpacing:2.0") in 0 until openIndex,
        )
        assertEquals(2.0f, viewModel.uiState.value.lineSpacing, 0.001f)
        assertTrue(
            "initial global settings replay must not override the restored book geometry, events=$events",
            events.indexOf("setLineSpacing:1.1") !in 0..events.lastIndex,
        )
    }

    @Test
    fun `legacy saved reader typography is reset without losing reading state`() = runTest(dispatcher) {
        val locator = Locator(LocatorStrategy.Page(index = 4, total = 10), totalProgression = 0.4f)
        val handle = SavedStateHandle(
            mapOf(
                READER_STATE_SAVED_STATE_KEY to readerStateJsonWithLineSpacing(
                    ReaderState(
                        bookId = "book-1",
                        currentLocator = locator,
                        fontSize = 24,
                        readingMode = ReaderReadingMode.PAGED,
                        theme = ThemeMode.SEPIA,
                    ),
                    lineSpacing = 2.0f,
                ),
            ),
        )

        val viewModel = readerViewModel(
            handle = handle,
            engine = FakeReaderEngine(locator),
        )

        assertEquals(18f, viewModel.uiState.value.fontSizeSp, 0.001f)
        assertEquals(1.3f, viewModel.uiState.value.lineSpacing, 0.001f)
        assertEquals(ReadingMode.PAGED, viewModel.uiState.value.readingMode)
        assertEquals(ThemeMode.SEPIA, viewModel.uiState.value.themeMode)
        assertEquals(ReaderTypography.BASELINE_VERSION, handle[READER_TYPOGRAPHY_BASELINE_SAVED_STATE_KEY])
        val migrated = Json.decodeFromString<ReaderState>(checkNotNull(handle[READER_STATE_SAVED_STATE_KEY]))
        assertEquals(locator, migrated.currentLocator)
        assertEquals(18, migrated.fontSize)
        assertEquals(1.3f, migrated.lineSpacing, 0.001f)
    }

    @Test
    fun `skips initial font choice replay after opening restored book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val restoredLocator = Locator(LocatorStrategy.Page(index = 4, total = 10), totalProgression = 0.4f)
        val settings = FakeSettingsRepository().apply {
            useSourceHanFont.value = false
            fontChoice.value = FontChoice.System
        }
        val progressDao = FakeProgressDao().apply {
            upsert(
                ReadingProgressEntity(
                    bookId = "book-1",
                    locatorJson = Json.encodeToString(restoredLocator),
                    totalProgression = 0.4f,
                    progressPercent = 0.4f,
                    updatedAt = 100L,
                    deviceId = "phone",
                ),
            )
        }
        val events = mutableListOf<String>()
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = events,
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
            settings = settings,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(
            "font must be applied once as part of the pre-open layout transaction, not replayed after load",
            1,
            events.count { it == "setFont:system_serif" },
        )

        settings.setFontChoice(FontChoice.SystemSans)
        advanceUntilIdle()

        assertTrue(
            "real user font changes after open must still reach the engine, events=$events",
            "setFont:system_sans" in events,
        )
    }

    @Test
    fun `applies persisted epub font replacements before engine is exposed as loaded`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val replacements = mapOf("book serif" to "system_sans")
        val settings = FakeSettingsRepository().apply {
            epubFontReplacements.value = replacements
        }
        val events = mutableListOf<String>()
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = events,
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            settings = settings,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        val openIndex = events.indexOf("open:0.0")
        val replacementIndex = events.indexOf("setEpubFontReplacements:{book serif=system_sans}")
        assertTrue(
            "persisted replacements must be applied before openBook, events=$events",
            replacementIndex in 0 until openIndex,
        )
        assertEquals(LoadingState.Loaded, viewModel.uiState.value.loadingState)
        assertEquals(
            "collector must skip the initial map emission already applied at open",
            1,
            events.count { it.startsWith("setEpubFontReplacements:") },
        )
        assertEquals(listOf(replacements), engine.epubFontReplacementMaps)
    }

    @Test
    fun `live epub font replacement updates only the active engine`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository()
        val firstEvents = mutableListOf<String>()
        val secondEvents = mutableListOf<String>()
        val firstEngine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = firstEvents,
        )
        val secondEngine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = secondEvents,
        )
        var resolveCount = 0
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = firstEngine,
            engineProvider = {
                resolveCount += 1
                if (resolveCount == 1) firstEngine else secondEngine
            },
            settings = settings,
            bookDao = FakeBookDao(
                BookEntity(
                    id = "book-1",
                    title = "Saved Book",
                    author = "Author",
                    format = BookFormat.TXT.name,
                    downloadStatus = "DOWNLOADED",
                    localUri = "file:///tmp/saved-book.txt",
                ),
                BookEntity(
                    id = "book-2",
                    title = "Second Book",
                    author = "Author",
                    format = BookFormat.TXT.name,
                    downloadStatus = "DOWNLOADED",
                    localUri = "file:///tmp/second-book.txt",
                ),
            ),
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        assertEquals(1, firstEngine.epubFontReplacementMaps.size)

        settings.setEpubFontReplacements(mapOf("primary" to "system_sans"))
        advanceUntilIdle()
        assertEquals(
            listOf(emptyMap(), mapOf("primary" to "system_sans")),
            firstEngine.epubFontReplacementMaps,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-2"))
        advanceUntilIdle()
        assertEquals(LoadingState.Loaded, viewModel.uiState.value.loadingState)
        assertEquals(1, secondEngine.epubFontReplacementMaps.size)
        assertEquals(mapOf("primary" to "system_sans"), secondEngine.epubFontReplacementMaps.single())

        val firstMapsAfterReopen = firstEngine.epubFontReplacementMaps.toList()
        settings.setEpubFontReplacements(mapOf("secondary" to "system_monospace"))
        advanceUntilIdle()

        assertEquals(
            "stale engine must not receive replacement updates after reopen",
            firstMapsAfterReopen,
            firstEngine.epubFontReplacementMaps,
        )
        assertEquals(
            listOf(
                mapOf("primary" to "system_sans"),
                mapOf("secondary" to "system_monospace"),
            ),
            secondEngine.epubFontReplacementMaps,
        )
        assertTrue(
            "closed first engine should not keep receiving live settings",
            firstEngine.closeCalls >= 1,
        )
    }

    @Test
    fun `font choice intent updates reader settings and live engine together`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val settings = FakeSettingsRepository().apply {
            useSourceHanFont.value = false
            fontChoice.value = FontChoice.System
        }
        val events = mutableListOf<String>()
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = events,
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            settings = settings,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SetFontChoice(FontChoice.SystemSans))
        advanceUntilIdle()

        assertEquals(FontChoice.SystemSans, viewModel.uiState.value.fontChoice)
        assertEquals(FontChoice.SystemSans, settings.fontChoice.value)
        assertTrue("events=$events", "setFont:system_sans" in events)
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
    fun `opening and closing a book preserves the collected reader menu config`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val menuConfig = ReaderMenuConfig.resolve(
            ReaderMenuConfig(
                entries = listOf(
                    ReaderMenuEntry(ReaderCommandId.THEME, visible = true),
                    ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                    ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ),
            ),
        )
        val settings = FakeSettingsRepository().apply {
            readerMenuConfig.value = menuConfig
        }
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            settings = settings,
        )

        advanceUntilIdle()
        assertEquals(menuConfig, viewModel.uiState.value.menuConfig)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        assertEquals(menuConfig, viewModel.uiState.value.menuConfig)

        viewModel.onIntent(ReaderIntent.CloseBook)
        advanceUntilIdle()
        assertEquals(menuConfig, viewModel.uiState.value.menuConfig)
    }

    @Test
    fun `ordinary close failures are contained and the engine is not closed twice`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)

        CloseFailurePoint.entries.forEach { failurePoint ->
            var now = 1_000L
            val failure = IllegalStateException("$failurePoint failed")
            val sessionDao = FakeReadingSessionDao()
            val progressDao = FakeProgressDao()
            val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
            val viewModel = readerViewModel(
                handle = SavedStateHandle(),
                engine = engine,
                progressDao = progressDao,
                sessionDao = sessionDao,
                clock = { now },
            )

            viewModel.onIntent(ReaderIntent.OpenById("book-1"))
            advanceUntilIdle()
            now = 4_000L
            when (failurePoint) {
                CloseFailurePoint.READING_SESSION -> sessionDao.insertFailure = failure
                CloseFailurePoint.PROGRESS -> progressDao.upsertFailure = failure
                CloseFailurePoint.ENGINE_CLOSE -> engine.closeFailure = failure
            }

            viewModel.closeBook()

            assertEquals("$failurePoint must still clear reader state", ReaderUiState(), viewModel.uiState.value)
            assertEquals("$failurePoint must close the engine once", 1, engine.closeCalls)

            viewModel.closeBook()
            assertEquals("$failurePoint must not close the engine again", 1, engine.closeCalls)
        }
    }

    @Test
    fun `close rethrows cancellation after clearing state and closing the engine`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        var now = 1_000L
        val cancellation = CancellationException("session insert cancelled")
        val sessionDao = FakeReadingSessionDao()
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            sessionDao = sessionDao,
            clock = { now },
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        now = 4_000L
        sessionDao.insertFailure = cancellation
        engine.closeFailure = IllegalStateException("engine close failed during cancellation")

        var observed: Throwable? = null
        try {
            viewModel.closeBook()
        } catch (error: Throwable) {
            observed = error
        }

        assertEquals(cancellation, observed)
        assertEquals(ReaderUiState(), viewModel.uiState.value)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun `caller cancellation at close entry still clears state and closes the engine`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(handle = SavedStateHandle(), engine = engine)

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        val closeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel(CancellationException("caller cancelled before cleanup"))
            viewModel.closeBook()
        }
        advanceUntilIdle()

        assertTrue(closeJob.isCancelled)
        assertEquals(ReaderUiState(), viewModel.uiState.value)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun `ordinary persistence failures do not skip later close snapshots`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)

        listOf(CloseFailurePoint.READING_SESSION, CloseFailurePoint.PROGRESS).forEach { failurePoint ->
            var now = 1_000L
            val handle = SavedStateHandle()
            val sessionDao = FakeReadingSessionDao()
            val progressDao = FakeProgressDao()
            val engineStateStore = FakeEngineStateStore()
            val target = Locator(LocatorStrategy.Page(index = 7, total = 10), totalProgression = 0.7f)
            val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10))).apply {
                stateToSave = byteArrayOf(7, 0)
            }
            val viewModel = readerViewModel(
                handle = handle,
                engine = engine,
                engineStateStore = engineStateStore,
                progressDao = progressDao,
                sessionDao = sessionDao,
                clock = { now },
            )

            viewModel.onIntent(ReaderIntent.OpenById("book-1"))
            advanceUntilIdle()
            engine.currentLocator.value = target
            now = 4_000L
            when (failurePoint) {
                CloseFailurePoint.READING_SESSION -> {
                    sessionDao.insertFailure = IllegalStateException("session failed")
                }
                CloseFailurePoint.PROGRESS -> {
                    progressDao.upsertFailure = IllegalStateException("progress failed")
                }
                CloseFailurePoint.ENGINE_CLOSE -> error("not a persistence failure")
            }

            viewModel.closeBook()

            val savedReaderState = Json.decodeFromString<ReaderState>(
                checkNotNull(handle[READER_STATE_SAVED_STATE_KEY]),
            )
            assertEquals("$failurePoint must not skip the latest SavedState locator", target, savedReaderState.currentLocator)
            assertArrayEquals(
                "$failurePoint must not skip the engine-state snapshot",
                byteArrayOf(7, 0),
                engineStateStore.savedStates["book-1"],
            )
            if (failurePoint == CloseFailurePoint.READING_SESSION) {
                val savedProgress = checkNotNull(progressDao.savedProgress("book-1"))
                assertEquals(target, Json.decodeFromString<Locator>(savedProgress.locatorJson))
            }
            assertEquals(ReaderUiState(), viewModel.uiState.value)
            assertEquals(1, engine.closeCalls)
        }
    }

    @Test
    fun `clearing ViewModelStore closes the current engine once on the main thread`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(handle = SavedStateHandle(), engine = engine)
        val store = ViewModelStore()
        val storedViewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
            },
        )[ReaderViewModel::class.java]

        storedViewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        assertEquals(engine, storedViewModel.uiState.value.engine)

        store.clear()
        advanceUntilIdle()

        assertEquals("ViewModelStore.clear must close the active engine exactly once", 1, engine.closeCalls)
        assertEquals("engine close must run on the Android main thread", listOf(true), engine.closeOnMainLooper)
    }

    @Test
    fun `writes reading session on close when duration exceeds threshold`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val sessionDao = FakeReadingSessionDao()
        // Clock: open() reads 1_000, close() reads 4_000 → duration 3_000ms > 1s threshold.
        val ticks = mutableListOf(1_000L, 4_000L)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            sessionDao = sessionDao,
            clock = { if (ticks.size > 1) ticks.removeAt(0) else ticks.first() },
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.CloseBook)
        advanceUntilIdle()

        assertEquals(1, sessionDao.sessions.size)
        val session = sessionDao.sessions.first()
        assertEquals("book-1", session.bookId)
        assertEquals(1_000L, session.startedAt)
        assertEquals(3_000L, session.durationMs)
    }

    @Test
    fun `does not write reading session when duration below threshold`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val sessionDao = FakeReadingSessionDao()
        // Clock barely advances (open 1_000, close 1_500 → 500ms ≤ 1s) so the session is noise.
        val ticks = mutableListOf(1_000L, 1_500L)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            sessionDao = sessionDao,
            clock = { if (ticks.size > 1) ticks.removeAt(0) else ticks.first() },
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.CloseBook)
        advanceUntilIdle()

        assertTrue(sessionDao.sessions.isEmpty())
    }

    @Test
    fun `seekToProgress drives engine to the requested whole-book fraction`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val progressDao = FakeProgressDao()
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.SeekToProgress(0.42f))
        advanceUntilIdle()

        val seeked = engine.goToLocators.last()
        assertEquals(0.42f, seeked.totalProgression)
        assertEquals(LocatorStrategy.Unknown, seeked.strategy)
        // Seek is an explicit navigation → persisted.
        assertNotNull(progressDao.savedProgress("book-1"))
    }

    @Test
    fun `shows gesture guide on first open then dismiss persists the flag`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val settings = FakeSettingsRepository().apply { readerGuideShown.value = false }
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            settings = settings,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showGuide)

        viewModel.onIntent(ReaderIntent.DismissGuide)
        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.showGuide)
        assertTrue(settings.readerGuideShown.value)
    }

    @Test
    fun `does not show gesture guide when already seen`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val settings = FakeSettingsRepository().apply { readerGuideShown.value = true }
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            settings = settings,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.showGuide)
    }

    @Test
    fun `retry replays the last open-by-id request`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        // 未知 bookId → 打开失败进错误页；getById 被调用一次。
        val bookDao = FakeBookDao(
            BookEntity(
                id = "other",
                title = "Other",
                author = "A",
                format = BookFormat.TXT.name,
                downloadStatus = "DOWNLOADED",
                localUri = "file:///tmp/other.txt",
            ),
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            bookDao = bookDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("missing"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadingState is LoadingState.Error)
        assertEquals(1, bookDao.getByIdCalls)

        viewModel.onIntent(ReaderIntent.Retry)
        advanceUntilIdle()
        // 重试复用上次 ById 请求 → getById 再被调用一次。
        assertEquals(2, bookDao.getByIdCalls)
    }

    @Test
    fun `retry is a no-op when nothing was opened`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val engine = FakeReaderEngine(Locator(LocatorStrategy.Page(index = 0, total = 10)))
        val bookDao = FakeBookDao(
            BookEntity(
                id = "book-1",
                title = "B",
                author = "A",
                format = BookFormat.TXT.name,
                downloadStatus = "DOWNLOADED",
                localUri = "file:///tmp/saved-book.txt",
            ),
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            bookDao = bookDao,
        )

        viewModel.onIntent(ReaderIntent.Retry)
        advanceUntilIdle()

        assertEquals(0, bookDao.getByIdCalls)
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
    fun `remote sync winner is applied before reader surface is loaded`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val events = mutableListOf<String>()
        val localLocator = Locator(LocatorStrategy.Page(index = 1, total = 10), totalProgression = 0.1f)
        val remoteLocator = Locator(LocatorStrategy.Page(index = 8, total = 10), totalProgression = 0.8f)
        val remoteProgress = ReadingProgress(
            bookId = "book-1",
            locator = remoteLocator,
            progressPercent = 0.8f,
            updatedAt = Long.MAX_VALUE / 2,
            deviceId = "tablet",
        )
        val progressDao = FakeProgressDao().apply {
            upsert(
                ReadingProgressEntity(
                    bookId = "book-1",
                    locatorJson = Json.encodeToString(localLocator),
                    totalProgression = 0.1f,
                    progressPercent = 0.1f,
                    updatedAt = 100L,
                    deviceId = "phone",
                ),
            )
        }
        val engine = FakeReaderEngine(localLocator, events)
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
            syncManager = SyncManager(FakeSyncBackend(remoteProgress)),
        )
        val stateJob = launch {
            viewModel.uiState.collect { state ->
                if (state.loadingState == LoadingState.Loaded) {
                    events += "loaded:${state.engine?.currentLocator?.value?.totalProgression}"
                }
            }
        }

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        stateJob.cancel()
        assertTrue(
            "remote winner should be applied before Loaded is exposed, events=$events",
            events.indexOf("goTo:0.8") in 0 until events.indexOf("loaded:0.8"),
        )
        assertEquals("remote locator should be applied only once during open", listOf(remoteLocator), engine.goToLocators)
        assertEquals(remoteLocator, Json.decodeFromString<Locator>(progressDao.savedProgress("book-1")!!.locatorJson))
    }

    @Test
    fun `initial locator aware engine receives final restore locator before opening book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val events = mutableListOf<String>()
        val localLocator = Locator(LocatorStrategy.Page(index = 1, total = 10), totalProgression = 0.1f)
        val remoteLocator = Locator(LocatorStrategy.Page(index = 8, total = 10), totalProgression = 0.8f)
        val progressDao = FakeProgressDao().apply {
            upsert(
                ReadingProgressEntity(
                    bookId = "book-1",
                    locatorJson = Json.encodeToString(localLocator),
                    totalProgression = 0.1f,
                    progressPercent = 0.1f,
                    updatedAt = 100L,
                    deviceId = "phone",
                ),
            )
        }
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = events,
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
            syncManager = SyncManager(
                FakeSyncBackend(
                    ReadingProgress(
                        bookId = "book-1",
                        locator = remoteLocator,
                        progressPercent = 0.8f,
                        updatedAt = Long.MAX_VALUE / 2,
                        deviceId = "tablet",
                    ),
                ),
            ),
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(
            "engine should be primed with the final remote/local winner before openBook publishes a start locator",
            listOf(remoteLocator),
            engine.initialLocators,
        )
        assertTrue(
            "initial locator must be set before openBook, events=$events",
            events.indexOf("setInitial:0.8") in 0 until events.indexOf("open:0.8"),
        )
        assertEquals("open should already start at the final locator", remoteLocator, engine.openLocators.single())
        assertEquals("no post-open goTo needed when the engine opened at the final locator", emptyList<Locator>(), engine.goToLocators)
        assertEquals(remoteLocator, engine.currentLocator.value)
    }

    @Test
    fun `initial locator aware engine receives layout settings before opening book`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val events = mutableListOf<String>()
        val restoredLocator = Locator(LocatorStrategy.Page(index = 4, total = 10), totalProgression = 0.4f)
        val settings = FakeSettingsRepository().apply {
            fontSize.value = 24
            lineSpacing.value = 2.0f
            themeMode.value = ThemeMode.DARK
            useSourceHanFont.value = false
            fontChoice.value = FontChoice.System
            pageFlipStyle.value = PageFlipStyle.SIMULATION
            readingMode.value = ReaderReadingMode.PAGED
        }
        val progressDao = FakeProgressDao().apply {
            upsert(
                ReadingProgressEntity(
                    bookId = "book-1",
                    locatorJson = Json.encodeToString(restoredLocator),
                    totalProgression = 0.4f,
                    progressPercent = 0.4f,
                    updatedAt = 100L,
                    deviceId = "phone",
                ),
            )
        }
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = events,
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
            settings = settings,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        val openIndex = events.indexOf("open:0.4")
        assertTrue(
            "layout-affecting settings must be applied before openBook publishes the restored locator, events=$events",
            openIndex > 0 &&
                listOf(
                    "setFontSize:24.0",
                    "setLineSpacing:2.0",
                    "setTheme:DARK",
                    "setSerifFont:false",
                    "setFont:system_serif",
                    "setPageFlipStyle:SIMULATION",
                    "setMode:PAGED",
                ).all { events.indexOf(it) in 0 until openIndex },
        )
        assertEquals(ReadingMode.PAGED, viewModel.uiState.value.readingMode)
        assertEquals(PagingKind.PAGED, engine.pagingKind.value)
        assertEquals(emptyList<Locator>(), engine.goToLocators)
    }

    @Test
    fun `initial locator aware engine does not receive duplicate goTo when it normalizes restored locator`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val restoredLocator = Locator(
            LocatorStrategy.Section(spineIndex = 1, elementIndex = 4, charOffset = 12),
            totalProgression = 0.8f,
        )
        val normalizedLocator = restoredLocator.copy(totalProgression = 0.42f)
        val progressDao = FakeProgressDao().apply {
            upsert(
                ReadingProgressEntity(
                    bookId = "book-1",
                    locatorJson = Json.encodeToString(restoredLocator),
                    totalProgression = 0.8f,
                    progressPercent = 0.8f,
                    updatedAt = 100L,
                    deviceId = "phone",
                ),
            )
        }
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
            events = mutableListOf(),
        ).apply {
            openNormalizer = { normalizedLocator }
        }
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(listOf(restoredLocator), engine.initialLocators)
        assertEquals(normalizedLocator, engine.currentLocator.value)
        assertEquals("same Section payload should not cause a second post-open goTo", emptyList<Locator>(), engine.goToLocators)
    }

    @Test
    fun `initial locator aware engine does not receive duplicate goTo when it normalizes page locator`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val restoredLocator = Locator(
            LocatorStrategy.Page(index = 2, total = 20),
            totalProgression = 0.12f,
        )
        val normalizedLocator = Locator(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 140),
            totalProgression = 0.12f,
        )
        val progressDao = FakeProgressDao().apply {
            upsert(
                ReadingProgressEntity(
                    bookId = "book-1",
                    locatorJson = Json.encodeToString(restoredLocator),
                    totalProgression = 0.12f,
                    progressPercent = 0.12f,
                    updatedAt = 100L,
                    deviceId = "phone",
                ),
            )
        }
        val engine = FakeInitialLocatorAwareReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 20), totalProgression = 0f),
            events = mutableListOf(),
        ).apply {
            openNormalizer = { normalizedLocator }
        }
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(listOf(restoredLocator), engine.initialLocators)
        assertEquals(normalizedLocator, engine.currentLocator.value)
        assertEquals(
            "a Page locator normalized during open must not be replayed as a second visible goTo",
            emptyList<Locator>(),
            engine.goToLocators,
        )
    }

    @Test
    fun `remote sync replay does not goTo again for same display section`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val normalizedLocator = Locator(
            LocatorStrategy.Section(spineIndex = 1, elementIndex = 4, charOffset = 12),
            totalProgression = 0.42f,
        )
        val remoteLocator = normalizedLocator.copy(totalProgression = 0.8f)
        val progressDao = FakeProgressDao()
        val engine = FakeReaderEngine(normalizedLocator)
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
            syncManager = SyncManager(
                SequencedProgressSyncBackend(
                    pulls = listOf(
                        null,
                        ReadingProgress(
                            bookId = "book-1",
                            locator = remoteLocator,
                            progressPercent = 0.8f,
                            updatedAt = Long.MAX_VALUE / 2,
                            deviceId = "tablet",
                        ),
                    ),
                ),
            ),
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()

        assertEquals(
            "same Section payload with normalized progression should update saved progress without a visible engine jump",
            emptyList<Locator>(),
            engine.goToLocators,
        )
        assertEquals(normalizedLocator, engine.currentLocator.value)
        assertEquals(remoteLocator, Json.decodeFromString<Locator>(progressDao.savedProgress("book-1")!!.locatorJson))
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
        val progressDao = FakeProgressDao()
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.ANNOTATIONS))
        advanceUntilIdle()
        progressDao.clear()

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
        runCurrent()

        assertEquals(target, engine.currentLocator.value)
        assertEquals(listOf(target), engine.goToLocators)
        assertNull(viewModel.uiState.value.activePanel)
        assertEquals(false, viewModel.uiState.value.isUiVisible)
        assertSavedProgress(progressDao, target)
    }

    @Test
    fun `go to bookmark persists Room progress without waiting for debounce`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val target = Locator(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 12, charOffset = 6),
            totalProgression = 0.41f,
        )
        val progressDao = FakeProgressDao()
        val engine = FakeReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.BOOKMARKS))
        advanceUntilIdle()
        progressDao.clear()

        viewModel.onIntent(
            ReaderIntent.GoToBookmark(
                ReaderBookmarkItem(
                    id = "bookmark-1",
                    locator = target,
                    label = "书签 41%",
                    totalProgression = 0.41f,
                    createdAt = 123L,
                ),
            ),
        )
        runCurrent()

        assertEquals(target, engine.currentLocator.value)
        assertEquals(listOf(target), engine.goToLocators)
        assertNull(viewModel.uiState.value.activePanel)
        assertEquals(false, viewModel.uiState.value.isUiVisible)
        assertSavedProgress(progressDao, target)
    }

    @Test
    fun `go to search result persists Room progress without waiting for debounce`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val target = Locator(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 18, charOffset = 32),
            totalProgression = 0.58f,
        )
        val progressDao = FakeProgressDao()
        val engine = FakeReaderEngine(
            initialLocator = Locator(LocatorStrategy.Page(index = 0, total = 10), totalProgression = 0f),
        )
        val viewModel = readerViewModel(
            handle = SavedStateHandle(),
            engine = engine,
            progressDao = progressDao,
        )

        viewModel.onIntent(ReaderIntent.OpenById("book-1"))
        advanceUntilIdle()
        progressDao.clear()

        viewModel.onIntent(
            ReaderIntent.GoToSearchResult(
                ReaderSearchResult(index = 0, locator = target),
            ),
        )
        runCurrent()

        val saved = progressDao.savedProgress("book-1")
        assertNotNull(saved)
        assertEquals(target, Json.decodeFromString<Locator>(saved!!.locatorJson))
        assertEquals(0.58f, saved.totalProgression)
        assertEquals("device-id", saved.deviceId)
    }

    private fun assertSavedProgress(progressDao: FakeProgressDao, target: Locator) {
        val saved = progressDao.savedProgress("book-1")
        assertNotNull(saved)
        assertEquals(target, Json.decodeFromString<Locator>(saved!!.locatorJson))
        assertEquals(target.totalProgression ?: 0f, saved.totalProgression)
        assertEquals("device-id", saved.deviceId)
    }

    private fun readerStateJsonWithLineSpacing(
        state: ReaderState,
        lineSpacing: Float,
    ): String {
        val base = Json.parseToJsonElement(Json.encodeToString(state)).jsonObject
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("lineSpacing", lineSpacing)
        }.toString()
    }

    private fun readerViewModel(
        handle: SavedStateHandle,
        engine: FakeReaderEngine,
        engineProvider: () -> FakeReaderEngine = { engine },
        engineStateStore: EngineStateStore = FakeEngineStateStore(),
        progressDao: FakeProgressDao = FakeProgressDao(),
        syncManager: SyncManager = SyncManager(NoOpSyncBackend()),
        textAnnotationDao: TextAnnotationDao = FakeTextAnnotationDao(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        sessionDao: FakeReadingSessionDao = FakeReadingSessionDao(),
        bookDao: FakeBookDao = FakeBookDao(
            BookEntity(
                id = "book-1",
                title = "Saved Book",
                author = "Author",
                format = BookFormat.TXT.name,
                downloadStatus = "DOWNLOADED",
                localUri = "file:///tmp/saved-book.txt",
            ),
        ),
        clock: () -> Long = System::currentTimeMillis,
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
                        provider = engineProvider,
                    ),
                ),
                userOverrides = MutableStateFlow(emptyMap()),
            ),
            hostFactory = FakeHostFactory,
            bookDao = bookDao,
            progressDao = progressDao,
            bookmarkDao = FakeBookmarkDao(),
            textAnnotationDao = textAnnotationDao,
            syncManager = syncManager,
            settings = settings,
            engineStateStore = engineStateStore,
            readingSessionDao = sessionDao,
            clock = clock,
        )

    private class FakeReadingSessionDao : ReadingSessionDao {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var insertFailure: Throwable? = null
        override suspend fun insert(session: ReadingSessionEntity) {
            insertFailure?.let { throw it }
            sessions.add(session)
        }
        override suspend fun totalDurationSince(sinceMillis: Long) = sessions.filter { it.startedAt >= sinceMillis }.sumOf { it.durationMs }
        override suspend fun totalDurationForBook(bookId: String) = sessions.filter { it.bookId == bookId }.sumOf { it.durationMs }
        override suspend fun allForBackup() = sessions.toList()
    }

    private open class FakeReaderEngine(
        initialLocator: Locator,
        protected val events: MutableList<String> = mutableListOf(),
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
        val modeChanges = mutableListOf<ReadingMode>()
        val restoredStates = mutableListOf<ByteArray>()
        val epubFontReplacementMaps = mutableListOf<Map<String, String>>()
        var clearTextSelectionCalls = 0
        var closeCalls = 0
        var closeFailure: Throwable? = null
        val closeOnMainLooper = mutableListOf<Boolean>()
        var latestAnnotations: List<ReaderTextAnnotation> = emptyList()
        var stateToSave: ByteArray = ByteArray(0)

        override suspend fun supports(uri: Uri): Boolean = true
        override suspend fun openBook(uri: Uri): Locator = currentLocator.value
        override fun createView(): View = error("FakeReaderEngine does not create Android views in unit tests")
        override suspend fun close() {
            closeCalls += 1
            closeOnMainLooper += Looper.myLooper() == Looper.getMainLooper()
            events += "close"
            closeFailure?.let { throw it }
        }
        override suspend fun goTo(locator: Locator) {
            goToLocators += locator
            events += "goTo:${locator.totalProgression}"
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
            events += "setFontSize:$sp"
        }
        override suspend fun setLineSpacing(multiplier: Float) {
            events += "setLineSpacing:$multiplier"
        }
        override suspend fun setSerifFont(useSourceHan: Boolean) {
            events += "setSerifFont:$useSourceHan"
        }
        override suspend fun setFont(fontId: String) {
            events += "setFont:$fontId"
        }
        override suspend fun setEpubFontReplacements(replacements: Map<String, String>) {
            epubFontReplacementMaps += replacements
            events += "setEpubFontReplacements:$replacements"
        }
        override suspend fun setPageFlipStyle(style: PageFlipStyle) {
            events += "setPageFlipStyle:$style"
        }
        override suspend fun setTheme(mode: ThemeMode) {
            themes += mode
            events += "setTheme:$mode"
        }
        override suspend fun setMode(mode: ReadingMode) {
            modeChanges += mode
            events += "setMode:$mode"
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

    private class FakeInitialLocatorAwareReaderEngine(
        initialLocator: Locator,
        events: MutableList<String>,
    ) : FakeReaderEngine(initialLocator, events), InitialLocatorAwareReaderEngine {
        val initialLocators = mutableListOf<Locator>()
        val openLocators = mutableListOf<Locator>()
        var openNormalizer: ((Locator) -> Locator)? = null
        private var primedLocator: Locator? = null

        override fun setInitialLocator(locator: Locator?) {
            primedLocator = locator
            locator?.let(initialLocators::add)
            events += "setInitial:${locator?.totalProgression}"
        }

        override suspend fun openBook(uri: Uri): Locator {
            primedLocator?.let { currentLocator.value = openNormalizer?.invoke(it) ?: it }
            openLocators += currentLocator.value
            events += "open:${currentLocator.value.totalProgression}"
            return currentLocator.value
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

    private class FakeBookDao(vararg books: BookEntity) : BookDao {
        private val books = books.associateBy { it.id }.toMutableMap()
        var getByIdCalls = 0
            private set
        override fun observeAll(): Flow<List<BookEntity>> = MutableStateFlow(this.books.values.toList())
        override fun observeShelf(): Flow<List<BookWithProgress>> = MutableStateFlow(emptyList())
        override suspend fun count(): Int = this.books.size
        override suspend fun maxSortOrder(): Int? = this.books.values.maxOfOrNull { it.sortOrder }
        override suspend fun shelfRowsForOrderNormalization(): List<BookEntity> = this.books.values.toList()
        override suspend fun upsert(book: BookEntity) {
            books[book.id] = book
        }
        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { this.books[it.id] = it }
        }
        override suspend fun getById(id: String): BookEntity? {
            getByIdCalls += 1
            return books[id]
        }
        override suspend fun setLastReadAt(id: String, ts: Long) = Unit
        override suspend fun downloadedRemoteCacheBooks(
            remotePrefix: String,
            downloadedStatus: String,
        ): List<DownloadedCacheBook> = emptyList()
        override suspend fun clearDownloadedAsset(id: String, downloadStatus: String) = Unit
        override suspend fun deleteById(id: String) {
            books.remove(id)
        }
        override suspend fun updateTitle(id: String, title: String) = Unit
        override suspend fun updateCollection(
            id: String,
            collectionId: String?,
            name: String?,
        ): Int = 1
        override suspend fun moveToGroup(sourceId: String, targetCollectionId: String): Int = 1
        override suspend fun createGroup(
            sourceId: String,
            targetId: String,
            collectionId: String,
            name: String,
        ): Int = 2
        override suspend fun renameCollection(collectionId: String, newName: String): Int = 0
        override suspend fun clearCollection(collectionId: String): Int = 0
        override suspend fun updateSortOrder(id: String, order: Int) = Unit
    }

    private class FakeProgressDao : ReadingProgressDao {
        private val progress = mutableMapOf<String, ReadingProgressEntity>()
        var upsertFailure: Throwable? = null
        override suspend fun get(bookId: String): ReadingProgressEntity? = progress[bookId]
        override suspend fun allForBackup(): List<ReadingProgressEntity> = progress.values.toList()
        override suspend fun upsert(progress: ReadingProgressEntity) {
            upsertFailure?.let { throw it }
            this.progress[progress.bookId] = progress
        }
        fun savedProgress(bookId: String): ReadingProgressEntity? = progress[bookId]
        fun clear() {
            progress.clear()
        }
    }

    private enum class CloseFailurePoint {
        READING_SESSION,
        PROGRESS,
        ENGINE_CLOSE,
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

    private class SequencedProgressSyncBackend(
        pulls: List<ReadingProgress?>,
    ) : SyncBackend {
        override val backendId = "fake-sequence"
        override val isAvailable = true
        private val pendingPulls = ArrayDeque(pulls)

        override suspend fun pushProgress(bookId: String, progress: ReadingProgress): ReadflowResult<Unit> =
            ReadflowResult.Success(Unit)

        override suspend fun pullProgress(bookId: String): ReadflowResult<ReadingProgress?> =
            ReadflowResult.Success(if (pendingPulls.isEmpty()) null else pendingPulls.removeFirst())

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
        override val useSourceHanFont = MutableStateFlow(true)
        override val txtEncoding = MutableStateFlow(TxtEncoding.AUTO)
        override val fontChoice = MutableStateFlow<FontChoice>(FontChoice.System)
        override val epubFontReplacements = MutableStateFlow<Map<String, String>>(emptyMap())
        override val readerGuideShown = MutableStateFlow(true)
        override val pageFlipStyle = MutableStateFlow(dev.readflow.core.model.PageFlipStyle.SLIDE)
        override val readerMenuConfig = MutableStateFlow(ReaderMenuConfig.v1Defaults())

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

        override suspend fun setUseSourceHanFont(enabled: Boolean) {
            useSourceHanFont.value = enabled
        }

        override suspend fun setTxtEncoding(encoding: TxtEncoding) {
            txtEncoding.value = encoding
        }

        override suspend fun setFontChoice(choice: FontChoice) {
            fontChoice.value = choice
        }

        override suspend fun setEpubFontReplacements(replacements: Map<String, String>) {
            epubFontReplacements.value = replacements
        }

        override suspend fun setReaderGuideShown(shown: Boolean) {
            readerGuideShown.value = shown
        }

        override suspend fun setPageFlipStyle(style: dev.readflow.core.model.PageFlipStyle) {
            pageFlipStyle.value = style
        }
    }
}
