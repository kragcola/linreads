package dev.readflow.features.reader

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.database.BookDao
import dev.readflow.core.database.BookmarkDao
import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.database.ReadingProgressDao
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.ReadingSessionDao
import dev.readflow.core.database.TextAnnotationDao
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ReaderState
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ReadingProgress
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.prefs.ReaderTypography
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.prefs.canonicalBookIdentity
import dev.readflow.core.prefs.canonicalEpubFontFamilyKey
import dev.readflow.core.prefs.mergedEpubFontReplacements
import dev.readflow.core.sync.SyncManager
import dev.readflow.render.api.EngineStateStore
import dev.readflow.render.api.EpubCssFontFamilyInfo
import dev.readflow.render.api.InitialLocatorAwareReaderEngine
import dev.readflow.render.api.PageTransitionHostFactory
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderEngineRegistry
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.SearchHighlightableReaderEngine
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.ZoomableReaderEngine
import dev.readflow.render.api.toReadingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal const val READER_STATE_SAVED_STATE_KEY = "reader_state_json"
internal const val READER_TYPOGRAPHY_BASELINE_SAVED_STATE_KEY = "reader_typography_baseline_version"

/** Optimistic Room lag overlay; Main-confined via [ReaderViewModel] pending flow. */
private sealed class PendingBookmarkMutation {
    abstract val id: String

    data class Insert(val entity: BookmarkEntity) : PendingBookmarkMutation() {
        override val id: String get() = entity.id
    }

    data class Remove(override val id: String) : PendingBookmarkMutation()
}

data class ReaderUiState(
    val loadingState: LoadingState = LoadingState.Idle,
    val engine: ReaderEngine? = null,
    val bookTitle: String = "",
    val fontSizeSp: Float = ReaderTypography.DEFAULT_FONT_SP.toFloat(),
    val lineSpacing: Float = ReaderTypography.DEFAULT_LINE_SPACING,
    val fontChoice: FontChoice = FontChoice.System,
    val readingMode: ReadingMode = ReadingMode.SCROLL,
    val supportedModes: Set<ReadingMode> = setOf(ReadingMode.SCROLL),
    val pageFlipStyle: PageFlipStyle = PageFlipStyle.SLIDE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isUiVisible: Boolean = false,  // 默认隐藏，点中间呼出
    val activePanel: ReaderPanel? = null,
    val search: ReaderSearchState = ReaderSearchState(),
    val bookmarks: ReaderBookmarkState = ReaderBookmarkState(),
    val annotations: ReaderAnnotationState = ReaderAnnotationState(),
    val canBookmark: Boolean = false,
    val textSelection: ReaderTextSelection? = null,
    val showGuide: Boolean = false,
    /** Resolved bottom-menu order/visibility from [SettingsRepository]; defaults match legacy row. */
    val menuConfig: ReaderMenuConfig = ReaderMenuConfig.v1Defaults(),
    /** CSS family catalog for the open EPUB (empty for non-EPUB). */
    val epubCssFontCatalog: List<EpubCssFontFamilyInfo> = emptyList(),
    /** Book-scoped CSS family -> fontId for the open book (canonical keys). */
    val epubBookFontReplacements: Map<String, String> = emptyMap(),
)

class ReaderViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val engineRegistry: ReaderEngineRegistry,
    val hostFactory: PageTransitionHostFactory,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val textAnnotationDao: TextAnnotationDao,
    private val syncManager: SyncManager,
    private val settings: SettingsRepository,
    private val engineStateStore: EngineStateStore,
    private val readingSessionDao: dev.readflow.core.database.ReadingSessionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val savedTypographyIsCurrent =
        savedStateHandle.get<Int>(READER_TYPOGRAPHY_BASELINE_SAVED_STATE_KEY) == ReaderTypography.BASELINE_VERSION
    private var restoredReaderState: ReaderState? = savedStateHandle.readerStateOrNull()?.let { restored ->
        if (savedTypographyIsCurrent) restored else restored.copy(
            fontSize = ReaderTypography.DEFAULT_FONT_SP,
            lineSpacing = ReaderTypography.DEFAULT_LINE_SPACING,
        )
    }
    private var currentBookId: String? = restoredReaderState?.bookId

    private val _uiState = MutableStateFlow(restoredReaderState.toReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        if (!savedTypographyIsCurrent) {
            savedStateHandle[READER_TYPOGRAPHY_BASELINE_SAVED_STATE_KEY] = ReaderTypography.BASELINE_VERSION
            restoredReaderState?.let { migrated ->
                savedStateHandle[READER_STATE_SAVED_STATE_KEY] = Json.encodeToString(migrated)
            }
        }
        // Menu config is owned by ViewModel, not Compose — collect resolved prefs Flow.
        // When the active panel's command becomes hidden, clear activePanel in the same
        // atomic update; isUiVisible is left unchanged.
        viewModelScope.launch {
            settings.readerMenuConfig.collect { config ->
                _uiState.update { state ->
                    val active = state.activePanel
                    val clearActive = active != null &&
                        !ReaderCommandCatalog.isPanelCommandVisible(config, active)
                    state.copy(
                        menuConfig = config,
                        activePanel = if (clearActive) null else active,
                    )
                }
            }
        }
    }

    private var progressJob: Job? = null
    private var fontPreviewPersistJob: Job? = null
    private var searchJob: Job? = null
    /** Monotonic owner for in-flight search; only the latest generation may publish. */
    private var searchGeneration: Long = 0L
    /**
     * In-flight search-result navigation ([goTo] + highlight + progress). Cancelled/superseded by a
     * newer next/previous/result request; invalidated by close/open via [searchNavigationGeneration].
     */
    private var searchNavigationJob: Job? = null
    /** Monotonic owner for search navigation; close/open and superseding requests bump this. */
    private var searchNavigationGeneration: Long = 0L
    private var bookmarkJob: Job? = null
    private var textSelectionJob: Job? = null
    private var annotationJob: Job? = null
    private var settingsFontJob: Job? = null
    private var settingsLineSpacingJob: Job? = null
    private var settingsEpubFontReplacementJob: Job? = null
    /** Serializes book-scoped font map mutations for the open book. */
    private val epubBookFontMutex = Mutex()
    private var openJob: Job? = null
    private var activeEngine: ReaderEngine? = _uiState.value.engine
    /**
     * Bumps on each successful book attach and on close so a stale navigation completion cannot
     * persist progress or selectedIndex into a newly opened book.
     */
    private var bookSessionGeneration: Long = 0L
    private val sessionMutex = Mutex()
    /** Serializes bookmark add/remove so rapid taps cannot create duplicate actives. */
    private val bookmarkMutex = Mutex()
    /**
     * Main-confined optimistic overlay while Room observe lags.
     * Scoped by [pendingBookmarkBookId]; merged into Room entities before projection.
     */
    private var pendingBookmarkBookId: String? = null
    private val pendingBookmarkMutations =
        MutableStateFlow<Map<String, PendingBookmarkMutation>>(emptyMap())
    /** Last Room emission for the open book; used to re-project under [bookmarkMutex]. */
    private var latestRoomBookmarkEntities: List<BookmarkEntity> = emptyList()
    private var latestBookmarkLocator: Locator? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionStartedAt: Long? = null
    // 记录最近一次打开请求，供错误页「重试」复用。
    private var lastOpenRequest: OpenRequest? = null

    fun onIntent(intent: ReaderIntent) = when (intent) {
        is ReaderIntent.OpenById -> openById(intent.bookId)
        is ReaderIntent.OpenBook -> openByUri(null, intent.uri)
        ReaderIntent.Retry -> retry()
        ReaderIntent.CloseBook -> viewModelScope.launch { closeBook() }
        is ReaderIntent.GoTo -> goTo(intent.locator)
        is ReaderIntent.SeekToProgress -> seekToProgress(intent.fraction)
        is ReaderIntent.GoToTocEntry -> goToTocEntry(intent.entry)
        is ReaderIntent.SetFontSize -> setFontSize(intent.sp)
        is ReaderIntent.PreviewFontSize -> previewFontSize(intent.sp)
        is ReaderIntent.PreviewZoom -> previewZoom(intent.scale)
        is ReaderIntent.SetLineSpacing -> setLineSpacing(intent.multiplier)
        is ReaderIntent.SetFontChoice -> setFontChoice(intent.choice)
        is ReaderIntent.SetEpubBookFontReplacement -> setEpubBookFontReplacement(intent.family, intent.choice)
        is ReaderIntent.ClearEpubBookFontReplacement -> clearEpubBookFontReplacement(intent.family)
        is ReaderIntent.SetMode -> setMode(intent.mode)
        is ReaderIntent.SetPageFlipStyle -> setPageFlipStyle(intent.style)
        is ReaderIntent.SetTheme -> setTheme(intent.theme)
        is ReaderIntent.OpenPanel -> updateUiStateAndPersist { it.copy(activePanel = intent.panel, isUiVisible = true) }
        ReaderIntent.ClosePanel -> updateUiStateAndPersist { it.copy(activePanel = null) }
        ReaderIntent.ToggleBookmark -> toggleBookmark()
        is ReaderIntent.GoToBookmark -> goToBookmark(intent.bookmark)
        is ReaderIntent.RemoveBookmark -> removeBookmark(intent.bookmark)
        is ReaderIntent.SaveTextAnnotation -> saveTextAnnotation(intent.note)
        is ReaderIntent.GoToAnnotation -> goToAnnotation(intent.annotation)
        is ReaderIntent.RemoveAnnotation -> removeAnnotation(intent.annotation)
        is ReaderIntent.SetSearchQuery -> setSearchQuery(intent.query)
        ReaderIntent.SubmitSearch -> submitSearch()
        is ReaderIntent.GoToSearchResult -> goToSearchResult(intent.result)
        ReaderIntent.GoToPreviousSearchResult -> goToAdjacentSearchResult(previous = true)
        ReaderIntent.GoToNextSearchResult -> goToAdjacentSearchResult(previous = false)
        ReaderIntent.ClearSearch -> clearSearch()
        ReaderIntent.ClearTextSelection -> clearTextSelection()
        ReaderIntent.ToggleChrome -> updateUiStateAndPersist { it.copy(isUiVisible = !it.isUiVisible, activePanel = null) }
        ReaderIntent.FontPanel -> updateUiStateAndPersist { it.copy(activePanel = ReaderPanel.FONT, isUiVisible = true) }
        ReaderIntent.ThemePanel -> updateUiStateAndPersist { it.copy(activePanel = ReaderPanel.THEME, isUiVisible = true) }
        ReaderIntent.DismissGuide -> dismissGuide()
    }

    private fun dismissGuide() {
        if (!_uiState.value.showGuide) return
        _uiState.update { it.copy(showGuide = false) }
        viewModelScope.launch { settings.setReaderGuideShown(true) }
    }

    /** 错误页「重试」：复用最近一次打开请求重新加载。 */
    private fun retry() {
        when (val request = lastOpenRequest) {
            is OpenRequest.ById -> openById(request.bookId)
            is OpenRequest.ByUri -> openByUri(null, request.uri)
            null -> Unit
        }
    }

    private sealed interface OpenRequest {
        data class ById(val bookId: String) : OpenRequest
        data class ByUri(val uri: Uri) : OpenRequest
    }

    private data class ReaderOpenSettings(
        val fontSize: Float,
        val lineSpacing: Float,
        val theme: ThemeMode,
        val useSourceHanFont: Boolean,
        val fontChoice: FontChoice,
        /** Merged global + book-scoped replacements already applied (book wins). */
        val epubFontReplacements: Map<String, String>,
        val epubBookFontReplacements: Map<String, String>,
        val epubGlobalFontReplacements: Map<String, String>,
        val flipStyle: PageFlipStyle,
        val txtEncodingCharsetName: String?,
        val readingMode: ReadingMode?,
    )

    private fun openById(bookId: String) {
        lastOpenRequest = OpenRequest.ById(bookId)
        val restoredForBook = restoredReaderState?.takeIf { it.bookId == bookId }
        _uiState.update { it.copy(loadingState = LoadingState.Loading) }
        replaceOpenJob {
            sessionMutex.withLock {
                closeLocked()
                currentBookId = bookId
                _uiState.update { it.copy(loadingState = LoadingState.Loading) }
                persistReaderState(bookId = bookId, loadingState = LoadingState.Loading)
                val book = bookDao.getById(bookId)
                if (book == null) {
                    val error = ReadflowError.notFound("book", bookId)
                    _uiState.update { it.copy(loadingState = LoadingState.Error(error)) }
                    persistReaderState(bookId = bookId, loadingState = LoadingState.Error(error), error = error)
                    return@withLock
                }
                val uri = book.localUri?.let { Uri.parse(it) }
                if (uri == null) {
                    val error = ReadflowError.io("本地文件未找到")
                    _uiState.update { it.copy(loadingState = LoadingState.Error(error), bookTitle = book.title) }
                    persistReaderState(bookId = bookId, loadingState = LoadingState.Error(error), error = error)
                    return@withLock
                }
                openByUriLocked(bookId, uri, book.title, restoredForBook)
            }
        }
    }

    private fun openByUri(
        bookId: String?,
        uri: Uri,
        title: String = "",
        restoredForBook: ReaderState? = restoredReaderState?.takeIf { it.bookId == bookId },
    ) {
        // openById 已记录 ById 请求；这里只记录直接以 Uri 打开的路径。
        if (bookId == null) {
            lastOpenRequest = OpenRequest.ByUri(uri)
        }
        _uiState.update {
            it.copy(
                loadingState = LoadingState.Loading,
                engine = null,
                activePanel = null,
                search = ReaderSearchState(),
                bookmarks = ReaderBookmarkState(),
                annotations = ReaderAnnotationState(),
                textSelection = null,
            )
        }
        replaceOpenJob {
            sessionMutex.withLock {
                closeLocked()
                _uiState.update { it.copy(loadingState = LoadingState.Loading) }
                openByUriLocked(bookId, uri, title, restoredForBook)
            }
        }
    }

    private suspend fun openByUriLocked(
        bookId: String?,
        uri: Uri,
        title: String,
        restoredForBook: ReaderState?,
    ) {
        val engine = runCatching { engineRegistry.resolve(uri) }.getOrElse { error ->
            if (error is CancellationException) throw error
            val readflowError = ReadflowError.io(error.message ?: "无法打开文件")
            _uiState.update { it.copy(loadingState = LoadingState.Error(readflowError), bookTitle = title) }
            bookId?.let {
                persistReaderState(bookId = it, loadingState = LoadingState.Error(readflowError), error = readflowError)
            }
            return
        }
        var engineAttached = false
        try {
            restoreEngineStateIfPresent(bookId, engine)
            val restoredLocator = restoredForBook?.currentLocator
            val persistedProgress = bookId?.let { id -> progressDao.get(id) }
            val persistedLocator = persistedProgress?.let { saved ->
                runCatching { Json.decodeFromString<Locator>(saved.locatorJson) }.getOrNull()
            }
            val requestedInitialLocator = restoredLocator ?: persistedLocator
            var displayLocator = if (bookId != null && requestedInitialLocator != null) {
                syncInitialProgressBeforeLoad(
                    bookId = bookId,
                    locator = requestedInitialLocator,
                    persistedProgress = persistedProgress,
                ) ?: requestedInitialLocator
            } else {
                requestedInitialLocator
            }
            val openSettings = readerOpenSettings(restoredForBook, engine)
            val initialLocatorAwareEngine = engine as? InitialLocatorAwareReaderEngine
            if (initialLocatorAwareEngine != null) {
                applyReaderOpenSettings(engine, openSettings)
            }
            initialLocatorAwareEngine?.setInitialLocator(displayLocator)
            val openedLocator = runCatching { engine.openBook(uri) }.getOrElse { error ->
                if (error is CancellationException) throw error
                val readflowError = ReadflowError.io(error.message ?: "无法打开文件")
                _uiState.update { it.copy(loadingState = LoadingState.Error(readflowError), bookTitle = title) }
                bookId?.let {
                    persistReaderState(bookId = it, loadingState = LoadingState.Error(readflowError), error = readflowError)
                }
                return
            }
            if (initialLocatorAwareEngine != null && displayLocator != null) {
                displayLocator = openedLocator
            }
            if (initialLocatorAwareEngine == null) {
                applyReaderOpenSettings(engine, openSettings)
            }
            if (bookId != null && displayLocator == null) {
                syncInitialProgressBeforeLoad(
                    bookId = bookId,
                    locator = engine.currentLocator.value,
                    persistedProgress = persistedProgress,
                )?.let { displayLocator = it }
            }
            displayLocator?.let { locator ->
                if (!engine.currentLocator.value.sameDisplayPositionAs(locator)) {
                    try {
                        engine.goTo(locator)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        Unit
                    }
                }
            }
            currentBookId = bookId
            activeEngine = engine
            engineAttached = true
            // New book session: drop any in-flight search navigation from a prior open.
            searchNavigationJob?.cancel()
            searchNavigationJob = null
            searchNavigationGeneration += 1L
            bookSessionGeneration += 1L
            _uiState.update {
                it.copy(
                    loadingState = LoadingState.Loaded,
                    engine = engine,
                    bookTitle = title,
                    fontSizeSp = openSettings.fontSize,
                    lineSpacing = openSettings.lineSpacing,
                    fontChoice = openSettings.fontChoice,
                    readingMode = engine.pagingKind.value.toReadingMode(),
                    supportedModes = engine.supportedModes,
                    pageFlipStyle = openSettings.flipStyle,
                    themeMode = openSettings.theme,
                    isUiVisible = restoredForBook?.isUiVisible ?: it.isUiVisible,
                    activePanel = null,
                    search = ReaderSearchState(),
                    bookmarks = ReaderBookmarkState(),
                    annotations = ReaderAnnotationState(),
                    canBookmark = bookId != null,
                    textSelection = null,
                )
            }
            watchProgress(engine, bookId)
            watchBookmarks(engine, bookId)
            watchTextSelection(engine)
            watchAnnotations(engine, bookId)
            watchSettings(engine)
            if (!settings.readerGuideShown.first()) {
                _uiState.update { it.copy(showGuide = true) }
            }
            sessionStartedAt = clock()
            bookId?.let {
                persistReaderState(bookId = it, locator = engine.currentLocator.value, loadingState = LoadingState.Loaded)
            }
        } finally {
            if (!engineAttached) {
                withContext(NonCancellable) { runCatching { engine.close() } }
            }
        }
    }

    private fun replaceOpenJob(block: suspend () -> Unit) {
        openJob?.cancel()
        openJob = viewModelScope.launch { block() }
    }

    private suspend fun readerOpenSettings(
        restoredForBook: ReaderState?,
        engine: ReaderEngine,
    ): ReaderOpenSettings {
        settings.ensureCurrentTypographyBaseline()
        val requestedMode = (restoredForBook?.readingMode ?: settings.readingMode.first())
            .toReadingMode()
            ?.takeIf { it in engine.supportedModes }
        val globalEpub = settings.epubFontReplacements.first()
        val bookId = currentBookId
        val bookKey = canonicalBookIdentity(bookId)
        val bookScoped = if (bookKey != null) {
            settings.epubBookFontReplacements.first()[bookKey].orEmpty()
        } else {
            emptyMap()
        }
        return ReaderOpenSettings(
            fontSize = restoredForBook?.fontSize?.toFloat() ?: settings.fontSize.first().toFloat(),
            lineSpacing = clampedReaderLineSpacing(restoredForBook?.lineSpacing ?: settings.lineSpacing.first()),
            theme = restoredForBook?.theme ?: settings.themeMode.first(),
            useSourceHanFont = settings.useSourceHanFont.first(),
            fontChoice = FontChoice.parse(settings.fontChoice.first().serialize()),
            epubFontReplacements = mergedEpubFontReplacements(globalEpub, bookScoped),
            epubBookFontReplacements = bookScoped,
            epubGlobalFontReplacements = globalEpub,
            flipStyle = settings.pageFlipStyle.first(),
            txtEncodingCharsetName = settings.txtEncoding.first().charsetName,
            readingMode = requestedMode,
        )
    }

    private suspend fun applyReaderOpenSettings(
        engine: ReaderEngine,
        openSettings: ReaderOpenSettings,
    ) {
        engine.setFontSize(openSettings.fontSize)
        engine.setLineSpacing(openSettings.lineSpacing)
        engine.setTheme(openSettings.theme)
        engine.setSerifFont(openSettings.useSourceHanFont)
        engine.setFont(openSettings.fontChoice.serialize())
        engine.setEpubFontReplacements(openSettings.epubFontReplacements)
        engine.setPageFlipStyle(openSettings.flipStyle)
        if (openSettings.txtEncodingCharsetName != null) {
            engine.setTxtEncodingOverride(openSettings.txtEncodingCharsetName)
        }
        openSettings.readingMode?.let { mode ->
            try {
                engine.setMode(mode)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
        val catalog = engine.epubCssFontCatalog(
            bookReplacements = openSettings.epubBookFontReplacements,
            globalReplacements = openSettings.epubGlobalFontReplacements,
        )
        _uiState.update {
            it.copy(
                epubCssFontCatalog = catalog,
                epubBookFontReplacements = openSettings.epubBookFontReplacements,
            )
        }
    }

    private suspend fun syncInitialProgressBeforeLoad(
        bookId: String,
        locator: Locator,
        persistedProgress: ReadingProgressEntity?,
    ): Locator? {
        val local = initialProgressForSync(
            bookId = bookId,
            locator = locator,
            persistedProgress = persistedProgress,
            deviceId = settings.deviceId.first(),
        )
        val remoteWinner = syncManager.syncProgress(bookId, local) ?: return null
        persistRemoteProgress(bookId, remoteWinner)
        persistReaderState(bookId = bookId, locator = remoteWinner.locator)
        return remoteWinner.locator
    }

    private fun initialProgressForSync(
        bookId: String,
        locator: Locator,
        persistedProgress: ReadingProgressEntity?,
        deviceId: String,
    ): ReadingProgress {
        val persistedLocator = persistedProgress?.let {
            runCatching { Json.decodeFromString<Locator>(it.locatorJson) }.getOrNull()
        }
        if (persistedProgress != null && persistedLocator == locator) {
            return ReadingProgress(
                bookId = bookId,
                locator = locator,
                progressPercent = persistedProgress.progressPercent,
                updatedAt = persistedProgress.updatedAt,
                deviceId = persistedProgress.deviceId,
            )
        }
        val progression = locator.totalProgression ?: 0f
        return ReadingProgress(
            bookId = bookId,
            locator = locator,
            progressPercent = progression,
            updatedAt = 0L,
            deviceId = deviceId,
        )
    }

    private fun watchProgress(engine: ReaderEngine, bookId: String?) {
        progressJob?.cancel()
        bookId ?: return
        progressJob = viewModelScope.launch {
            val deviceId = settings.deviceId.first()
            @Suppress("OPT_IN_USAGE")
            engine.currentLocator.debounce(2_000L).collect { locator ->
                val progress = persistProgress(bookId, locator, deviceId)
                persistReaderState(bookId = bookId, locator = locator)
                saveEngineStateIfPresent(bookId, engine)
                val remoteWinner = syncManager.syncProgress(bookId, progress)
                if (remoteWinner != null) {
                    applyRemoteProgress(bookId, remoteWinner, engine)
                }
            }
        }
    }

    private fun watchBookmarks(engine: ReaderEngine, bookId: String?) {
        bookmarkJob?.cancel()
        clearPendingBookmarkMutations()
        if (bookId == null) {
            _uiState.update { it.copy(bookmarks = ReaderBookmarkState(), canBookmark = false) }
            return
        }
        pendingBookmarkBookId = bookId
        bookmarkJob = viewModelScope.launch {
            // Ack only on upstream DAO emissions (onEach), never on locator/pending combine ticks.
            val roomBookmarks = bookmarkDao.observeForBook(bookId).onEach { entities ->
                latestRoomBookmarkEntities = entities
                acknowledgePendingBookmarkMutations(entities)
            }
            combine(
                roomBookmarks,
                engine.currentLocator,
                pendingBookmarkMutations,
            ) { entities, locator, pending ->
                Triple(entities, locator, pending)
            }.collect { (entities, locator, pending) ->
                latestBookmarkLocator = locator
                projectBookmarks(entities, locator, pending)
            }
        }
    }

    private fun mergePendingBookmarks(
        roomEntities: List<BookmarkEntity>,
        pending: Map<String, PendingBookmarkMutation>,
    ): List<BookmarkEntity> {
        if (pending.isEmpty()) {
            return roomEntities.filterNot { it.isDeleted }
        }
        val byId = roomEntities
            .asSequence()
            .filterNot { it.isDeleted }
            .associateByTo(linkedMapOf()) { it.id }
        for (mutation in pending.values) {
            when (mutation) {
                is PendingBookmarkMutation.Insert -> byId[mutation.entity.id] = mutation.entity
                is PendingBookmarkMutation.Remove -> byId.remove(mutation.id)
            }
        }
        return byId.values.toList()
    }

    private fun projectBookmarks(
        roomEntities: List<BookmarkEntity>,
        locator: Locator?,
        pending: Map<String, PendingBookmarkMutation>,
    ) {
        val bookmarks = readerBookmarkStateFor(mergePendingBookmarks(roomEntities, pending), locator)
        _uiState.update { it.copy(bookmarks = bookmarks, canBookmark = true) }
    }

    /**
     * Re-project under [bookmarkMutex] so the next serialized toggle sees optimistic state
     * without waiting for the combine collector.
     */
    private fun projectBookmarksFromOverlay(locator: Locator?) {
        projectBookmarks(latestRoomBookmarkEntities, locator, pendingBookmarkMutations.value)
    }

    private fun acknowledgePendingBookmarkMutations(roomEntities: List<BookmarkEntity>) {
        val pending = pendingBookmarkMutations.value
        if (pending.isEmpty()) return
        val activeIds = roomEntities
            .asSequence()
            .filterNot { it.isDeleted }
            .map { it.id }
            .toSet()
        pendingBookmarkMutations.update { current ->
            current.filterValues { mutation ->
                when (mutation) {
                    // Keep Insert until Room lists the id.
                    is PendingBookmarkMutation.Insert -> mutation.entity.id !in activeIds
                    // Keep Remove until Room no longer lists the id.
                    is PendingBookmarkMutation.Remove -> mutation.id in activeIds
                }
            }
        }
    }

    private fun setPendingBookmarkInsert(entity: BookmarkEntity) {
        if (pendingBookmarkBookId != entity.bookId) return
        pendingBookmarkMutations.update { current ->
            current + (entity.id to PendingBookmarkMutation.Insert(entity))
        }
    }

    private fun setPendingBookmarkRemove(bookmarkId: String, bookId: String) {
        if (pendingBookmarkBookId != bookId) return
        // Always Replace with Remove (including over Insert). Room may emit the upserted
        // active row before delete invalidation; dropping overlay would resurrect it.
        pendingBookmarkMutations.update { current ->
            current + (bookmarkId to PendingBookmarkMutation.Remove(bookmarkId))
        }
    }

    private fun clearPendingBookmarkMutation(bookmarkId: String) {
        pendingBookmarkMutations.update { current ->
            if (bookmarkId !in current) current else current - bookmarkId
        }
    }

    private fun clearPendingBookmarkMutations() {
        pendingBookmarkBookId = null
        latestRoomBookmarkEntities = emptyList()
        latestBookmarkLocator = null
        if (pendingBookmarkMutations.value.isNotEmpty()) {
            pendingBookmarkMutations.value = emptyMap()
        }
    }

    private fun watchTextSelection(engine: ReaderEngine) {
        textSelectionJob?.cancel()
        val selectableEngine = engine as? TextSelectableReaderEngine
        if (selectableEngine == null) {
            _uiState.update { it.copy(textSelection = null) }
            return
        }
        textSelectionJob = viewModelScope.launch {
            selectableEngine.currentTextSelection.collect { selection ->
                _uiState.update { it.copy(textSelection = selection) }
            }
        }
    }

    private fun watchAnnotations(engine: ReaderEngine, bookId: String?) {
        annotationJob?.cancel()
        val annotatableEngine = engine as? TextAnnotatableReaderEngine
        if (bookId == null || annotatableEngine == null) {
            annotatableEngine?.setTextAnnotations(emptyList())
            _uiState.update { it.copy(annotations = ReaderAnnotationState()) }
            return
        }
        annotationJob = viewModelScope.launch {
            textAnnotationDao.observeForBook(bookId).collect { entities ->
                val annotations = readerAnnotationStateFor(entities)
                annotatableEngine.setTextAnnotations(annotations.renderAnnotations)
                _uiState.update { it.copy(annotations = annotations) }
            }
        }
    }

    private fun watchSettings(engine: ReaderEngine) {
        settingsFontJob?.cancel()
        settingsFontJob = viewModelScope.launch {
            var firstEmission = true
            settings.fontChoice.collect { choice ->
                val canonical = FontChoice.parse(choice.serialize())
                if (firstEmission) {
                    firstEmission = false
                    _uiState.update { it.copy(fontChoice = canonical) }
                    return@collect
                }
                if (canonical != _uiState.value.fontChoice) {
                    _uiState.update { it.copy(fontChoice = canonical) }
                    engine.setFont(canonical.serialize())
                }
            }
        }
        settingsLineSpacingJob?.cancel()
        settingsLineSpacingJob = viewModelScope.launch {
            var firstEmission = true
            settings.lineSpacing.collect { spacing ->
                if (firstEmission) {
                    firstEmission = false
                    return@collect
                }
                val clamped = clampedReaderLineSpacing(spacing)
                engine.setLineSpacing(clamped)
                _uiState.update { it.copy(lineSpacing = clamped) }
            }
        }
        settingsEpubFontReplacementJob?.cancel()
        settingsEpubFontReplacementJob = viewModelScope.launch {
            var firstEmission = true
            combine(
                settings.epubFontReplacements,
                settings.epubBookFontReplacements,
            ) { global, byBook ->
                val bookKey = canonicalBookIdentity(currentBookId)
                val bookScoped = if (bookKey != null) byBook[bookKey].orEmpty() else emptyMap()
                Triple(global, bookScoped, mergedEpubFontReplacements(global, bookScoped))
            }.collect { (global, bookScoped, merged) ->
                if (firstEmission) {
                    firstEmission = false
                    // Still refresh catalog/status for the open book after first open apply.
                    refreshEpubFontUi(engine, bookScoped, global)
                    return@collect
                }
                engine.setEpubFontReplacements(merged)
                refreshEpubFontUi(engine, bookScoped, global)
            }
        }
    }

    private fun refreshEpubFontUi(
        engine: ReaderEngine,
        bookScoped: Map<String, String>,
        global: Map<String, String>,
    ) {
        val catalog = engine.epubCssFontCatalog(
            bookReplacements = bookScoped,
            globalReplacements = global,
        )
        _uiState.update {
            it.copy(
                epubCssFontCatalog = catalog,
                epubBookFontReplacements = bookScoped,
            )
        }
    }

    private fun goTo(locator: Locator) {
        val engine = _uiState.value.engine ?: return
        clearTransientSearchHighlight(engine)
        viewModelScope.launch {
            engine.goTo(locator)
            persistExplicitNavigation(engine, locator)
        }
        updateUiStateAndPersist { it.copy(activePanel = null) }
    }

    private fun seekToProgress(fraction: Float) {
        val engine = _uiState.value.engine ?: return
        clearTransientSearchHighlight(engine)
        viewModelScope.launch {
            engine.seekToProgress(fraction)
            persistExplicitNavigation(engine, engine.currentLocator.value)
        }
    }

    private fun goToTocEntry(entry: TocEntry) {
        val engine = _uiState.value.engine ?: return
        clearTransientSearchHighlight(engine)
        viewModelScope.launch {
            engine.goToTocEntry(entry)
            persistExplicitNavigation(engine, engine.currentLocator.value)
        }
        updateUiStateAndPersist { it.copy(activePanel = null, isUiVisible = false) }
    }

    private fun toggleBookmark() {
        val bookId = currentBookId ?: return
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch {
            bookmarkMutex.withLock {
                // Re-read under the lock so concurrent taps share one decision.
                val bookmarks = _uiState.value.bookmarks
                val currentBookmark = bookmarks.currentBookmarkId?.let { id ->
                    bookmarks.items.firstOrNull { it.id == id }
                }
                val now = System.currentTimeMillis()
                val deviceId = settings.deviceId.first()
                if (currentBookmark != null) {
                    setPendingBookmarkRemove(currentBookmark.id, bookId)
                    // Publish under lock so the next rapid toggle sees the optimistic state.
                    projectBookmarksFromOverlay(engine.currentLocator.value)
                    try {
                        bookmarkDao.markDeleted(currentBookmark.id, now, deviceId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        clearPendingBookmarkMutation(currentBookmark.id)
                        projectBookmarksFromOverlay(engine.currentLocator.value)
                        return@withLock
                    }
                    syncManager.syncBookmark(currentBookmark.toDeletedBookmarkModel(bookId, deviceId, now))
                } else {
                    val entity = readerBookmarkEntityFor(
                        bookId = bookId,
                        locator = engine.currentLocator.value,
                        deviceId = deviceId,
                        now = now,
                        id = UUID.randomUUID().toString(),
                    )
                    setPendingBookmarkInsert(entity)
                    projectBookmarksFromOverlay(engine.currentLocator.value)
                    try {
                        bookmarkDao.upsert(entity)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        clearPendingBookmarkMutation(entity.id)
                        projectBookmarksFromOverlay(engine.currentLocator.value)
                        return@withLock
                    }
                    entity.toBookmarkModel()?.let { syncManager.syncBookmark(it) }
                }
            }
        }
    }

    private fun goToBookmark(bookmark: ReaderBookmarkItem) {
        val engine = _uiState.value.engine ?: return
        clearTransientSearchHighlight(engine)
        viewModelScope.launch {
            engine.goTo(bookmark.locator)
            persistExplicitNavigation(engine, bookmark.locator)
        }
        updateUiStateAndPersist { it.copy(activePanel = null, isUiVisible = false) }
    }

    private fun removeBookmark(bookmark: ReaderBookmarkItem) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            bookmarkMutex.withLock {
                setPendingBookmarkRemove(bookmark.id, bookId)
                projectBookmarksFromOverlay(
                    latestBookmarkLocator ?: _uiState.value.engine?.currentLocator?.value,
                )
                val now = System.currentTimeMillis()
                val deviceId = settings.deviceId.first()
                try {
                    bookmarkDao.markDeleted(bookmark.id, now, deviceId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    clearPendingBookmarkMutation(bookmark.id)
                    projectBookmarksFromOverlay(
                        latestBookmarkLocator ?: _uiState.value.engine?.currentLocator?.value,
                    )
                    return@withLock
                }
                syncManager.syncBookmark(bookmark.toDeletedBookmarkModel(bookId, deviceId, now))
            }
        }
    }

    private fun saveTextAnnotation(note: String?) {
        val bookId = currentBookId ?: return
        val selection = _uiState.value.textSelection ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val deviceId = settings.deviceId.first()
            textAnnotationDao.upsert(
                readerTextAnnotationEntityFor(
                    bookId = bookId,
                    selection = selection,
                    note = note,
                    color = DEFAULT_HIGHLIGHT_COLOR,
                    deviceId = deviceId,
                    now = now,
                    id = UUID.randomUUID().toString(),
                ),
            )
            clearTextSelection()
        }
    }

    private fun goToAnnotation(annotation: ReaderAnnotationItem) {
        val engine = _uiState.value.engine ?: return
        clearTransientSearchHighlight(engine)
        viewModelScope.launch {
            engine.goTo(annotation.start)
            persistExplicitNavigation(engine, annotation.start)
        }
        updateUiStateAndPersist { it.copy(activePanel = null, isUiVisible = false) }
    }

    private fun removeAnnotation(annotation: ReaderAnnotationItem) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            val existing = try {
                textAnnotationDao.getById(annotation.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return@launch
            } ?: return@launch
            if (existing.bookId != bookId) return@launch
            val now = System.currentTimeMillis()
            val deviceId = settings.deviceId.first()
            try {
                textAnnotationDao.upsert(
                    existing.copy(
                        isDeleted = true,
                        updatedAt = now,
                        deviceId = deviceId,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return@launch
            }
        }
    }

    private fun setSearchQuery(query: String) {
        searchJob?.cancel()
        searchGeneration += 1L
        clearTransientSearchHighlight(_uiState.value.engine)
        _uiState.update { it.copy(search = it.search.withQuery(query)) }
    }

    private fun submitSearch() {
        val engine = _uiState.value.engine ?: return
        val query = _uiState.value.search.query.trim()
        if (query.isEmpty()) {
            clearSearch()
            return
        }
        searchJob?.cancel()
        val generation = ++searchGeneration
        // New search session: drop any previous in-page selection highlight before results arrive.
        clearTransientSearchHighlight(engine)
        _uiState.update {
            it.copy(
                search = it.search.copy(
                    query = query,
                    results = emptyList(),
                    selectedIndex = null,
                    isSearching = true,
                    message = null,
                ),
            )
        }
        // Capability short-circuit: no engine work when format cannot search.
        if (!engine.supportsSearch) {
            if (generation == searchGeneration) {
                _uiState.update { state ->
                    if (state.search.query != query) {
                        state
                    } else {
                        state.copy(
                            search = state.search.copy(
                                results = emptyList(),
                                selectedIndex = null,
                                isSearching = false,
                                message = "当前格式暂不支持搜索",
                            ),
                        )
                    }
                }
            }
            return
        }
        searchJob = viewModelScope.launch {
            val searchResult = try {
                Result.success(engine.search(query))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (generation != searchGeneration) return@launch
            _uiState.update { state ->
                if (state.search.query != query || generation != searchGeneration) {
                    state
                } else {
                    searchResult.fold(
                        onSuccess = { hits ->
                            val results = readerSearchResultsFor(hits)
                            state.copy(
                                search = state.search.copy(
                                    results = results,
                                    selectedIndex = null,
                                    isSearching = false,
                                    message = when {
                                        results.isNotEmpty() -> null
                                        else -> "未找到结果"
                                    },
                                ),
                            )
                        },
                        onFailure = { error ->
                            state.copy(
                                search = state.search.copy(
                                    results = emptyList(),
                                    selectedIndex = null,
                                    isSearching = false,
                                    message = "搜索失败：${error.message ?: "未知错误"}",
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun goToSearchResult(result: ReaderSearchResult) {
        val engine = _uiState.value.engine ?: return
        // Supersede any in-flight navigation: newer next/previous/click owns goTo + persist.
        searchNavigationJob?.cancel()
        val navigationGeneration = ++searchNavigationGeneration
        val sessionGeneration = bookSessionGeneration
        val targetBookId = currentBookId
        applySearchHighlight(engine, result.toSearchHit())
        // Keep SEARCH chrome so previous/next and result list stay usable for repeated stepping.
        // selectedIndex updates immediately so rapid stepping reflects the latest request even while
        // an older goTo is still in flight (it will no-op on completion via generation guards).
        updateUiStateAndPersist {
            it.copy(
                search = it.search.copy(selectedIndex = result.index),
                activePanel = ReaderPanel.SEARCH,
                isUiVisible = true,
            )
        }
        searchNavigationJob = viewModelScope.launch {
            try {
                engine.goTo(result.locator)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Engine navigation failed; do not persist a locator we never reached.
                return@launch
            }
            // Stale completion must not persist progress into a newer navigation request
            // or a different book session (close/open). Highlight + selectedIndex were applied
            // eagerly for the owning request; a superseding request already overwrote them.
            if (navigationGeneration != searchNavigationGeneration) return@launch
            if (sessionGeneration != bookSessionGeneration) return@launch
            if (currentBookId != targetBookId) return@launch
            if (_uiState.value.engine !== engine) return@launch
            persistExplicitNavigation(engine, result.locator)
        }
    }

    private fun goToAdjacentSearchResult(previous: Boolean) {
        val search = _uiState.value.search
        val targetIndex = if (previous) {
            search.previousSearchResultIndex()
        } else {
            search.nextSearchResultIndex()
        } ?: return
        val result = search.results.getOrNull(targetIndex) ?: return
        goToSearchResult(result)
    }

    private suspend fun persistExplicitNavigation(engine: ReaderEngine, locator: Locator) {
        val bookId = currentBookId ?: return
        persistProgress(bookId, locator, settings.deviceId.first())
        persistReaderState(bookId = bookId, locator = locator)
        saveEngineStateIfPresent(bookId, engine)
    }

    private fun clearSearch() {
        searchJob?.cancel()
        searchGeneration += 1L
        clearTransientSearchHighlight(_uiState.value.engine)
        _uiState.update { it.copy(search = it.search.cleared()) }
    }

    /**
     * Paint the exact selected search hit (locator + matchLength). Engines that do not implement
     * [SearchHighlightableReaderEngine] no-op. Never stores hits as [ReaderTextAnnotation].
     */
    private fun applySearchHighlight(engine: ReaderEngine, hit: ReaderSearchHit) {
        (engine as? SearchHighlightableReaderEngine)?.setSearchHighlight(hit)
    }

    /**
     * Clear engine paint and drop [ReaderSearchState.selectedIndex]. Used by query change, new
     * search, clear, and explicit non-search navigation. Closing only the SEARCH panel does not call this.
     */
    private fun clearTransientSearchHighlight(engine: ReaderEngine?) {
        (engine as? SearchHighlightableReaderEngine)?.setSearchHighlight(null)
        _uiState.update { state ->
            if (state.search.selectedIndex == null) state
            else state.copy(search = state.search.copy(selectedIndex = null))
        }
    }

    private fun ReaderSearchResult.toSearchHit(): ReaderSearchHit =
        ReaderSearchHit(
            locator = locator,
            snippet = snippet,
            matchLength = matchLength,
            matchStart = matchStart,
        )

    private fun clearTextSelection() {
        (_uiState.value.engine as? TextSelectableReaderEngine)?.clearTextSelection()
        _uiState.update { it.copy(textSelection = null) }
    }

    private fun setFontSize(sp: Float) {
        val engine = _uiState.value.engine ?: return
        val clamped = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        fontPreviewPersistJob?.cancel()
        updateUiStateAndPersist { it.copy(fontSizeSp = clamped) }
        viewModelScope.launch {
            engine.setFontSize(clamped)
            settings.setFontSize(clamped.toInt())
        }
    }

    private fun previewFontSize(sp: Float) {
        val engine = _uiState.value.engine ?: return
        val clamped = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        updateUiStateAndPersist { it.copy(fontSizeSp = clamped) }
        viewModelScope.launch {
            engine.setFontSize(clamped)
        }
        fontPreviewPersistJob?.cancel()
        fontPreviewPersistJob = viewModelScope.launch {
            delay(FONT_PREVIEW_COMMIT_DELAY_MS)
            settings.setFontSize(clamped.toInt())
        }
    }

    private fun previewZoom(scale: Float) {
        val engine = _uiState.value.engine as? ZoomableReaderEngine ?: return
        val clamped = scale.coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        viewModelScope.launch {
            engine.setZoom(clamped)
        }
    }

    private fun setLineSpacing(multiplier: Float) {
        val engine = _uiState.value.engine ?: return
        val clamped = clampedReaderLineSpacing(multiplier)
        updateUiStateAndPersist { it.copy(lineSpacing = clamped) }
        viewModelScope.launch {
            engine.setLineSpacing(clamped)
            settings.setLineSpacing(clamped)
        }
    }

    private fun setFontChoice(choice: FontChoice) {
        val canonical = FontChoice.parse(choice.serialize())
        val engine = _uiState.value.engine
        updateUiStateAndPersist { it.copy(fontChoice = canonical) }
        viewModelScope.launch {
            engine?.setFont(canonical.serialize())
            settings.setFontChoice(canonical)
        }
    }

    private fun setEpubBookFontReplacement(family: String, choice: FontChoice) {
        val familyKey = canonicalEpubFontFamilyKey(family) ?: return
        val bookKey = canonicalBookIdentity(currentBookId) ?: return
        val fontId = choice.serialize()
        viewModelScope.launch {
            epubBookFontMutex.withLock {
                val current = settings.epubBookFontReplacements.first()[bookKey].orEmpty()
                val next = current + (familyKey to fontId)
                settings.setEpubBookFontReplacements(bookKey, next)
            }
        }
    }

    private fun clearEpubBookFontReplacement(family: String) {
        val familyKey = canonicalEpubFontFamilyKey(family) ?: return
        val bookKey = canonicalBookIdentity(currentBookId) ?: return
        viewModelScope.launch {
            epubBookFontMutex.withLock {
                val current = settings.epubBookFontReplacements.first()[bookKey].orEmpty()
                if (familyKey !in current) return@withLock
                settings.setEpubBookFontReplacements(bookKey, current - familyKey)
            }
        }
    }

    private fun setTheme(theme: ThemeMode) {
        val engine = _uiState.value.engine
        updateUiStateAndPersist { it.copy(themeMode = theme) }
        viewModelScope.launch {
            settings.setThemeMode(theme)
            engine?.setTheme(theme)
        }
    }

    private fun setPageFlipStyle(style: PageFlipStyle) {
        val engine = _uiState.value.engine
        _uiState.update { it.copy(pageFlipStyle = style) }
        viewModelScope.launch {
            settings.setPageFlipStyle(style)
            engine?.setPageFlipStyle(style)
        }
    }

    private fun setMode(mode: ReadingMode) {
        val engine = _uiState.value.engine ?: return
        if (mode !in engine.supportedModes) return
        if (mode == _uiState.value.readingMode) return
        viewModelScope.launch {
            engine.setMode(mode)
            settings.setReadingMode(mode.toReaderReadingMode())
            _uiState.update {
                it.copy(
                    readingMode = engine.pagingKind.value.toReadingMode(),
                    supportedModes = engine.supportedModes,
                )
            }
            persistReaderState()
        }
    }

    suspend fun closeBook() {
        val callerContext = currentCoroutineContext()
        var closeCancellation: CancellationException? = null
        withContext(NonCancellable) {
            try {
                openJob?.cancelAndJoin()
            } catch (error: CancellationException) {
                closeCancellation = error
            } catch (_: Throwable) {
                Unit
            } finally {
                openJob = null
            }
            try {
                sessionMutex.withLock { closeLocked() }
            } catch (error: CancellationException) {
                closeCancellation = closeCancellation ?: error
            } catch (_: Throwable) {
                Unit
            }
        }
        closeCancellation?.let { throw it }
        callerContext.ensureActive()
    }

    private suspend fun closeLocked() {
        val bookId = currentBookId
        val engine = activeEngine
        val locator = engine?.currentLocator?.value
        progressJob?.cancel()
        fontPreviewPersistJob?.cancel()
        searchJob?.cancel()
        searchGeneration += 1L
        // Invalidate in-flight search navigation so a slow goTo cannot persist after close.
        searchNavigationJob?.cancel()
        searchNavigationJob = null
        searchNavigationGeneration += 1L
        bookSessionGeneration += 1L
        bookmarkJob?.cancel()
        clearPendingBookmarkMutations()
        textSelectionJob?.cancel()
        annotationJob?.cancel()
        settingsFontJob?.cancel()
        settingsLineSpacingJob?.cancel()
        settingsEpubFontReplacementJob?.cancel()
        var closeCancellation: CancellationException? = null

        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (error: CancellationException) {
                closeCancellation = closeCancellation ?: error
            } catch (_: Throwable) {
                Unit
            }
        }

        try {
            // Write reading session before close (threshold > 1s to filter noise)
            val sessionStart = sessionStartedAt
            sessionStartedAt = null
            attempt {
                if (bookId != null && sessionStart != null) {
                    val duration = clock() - sessionStart
                    if (duration > 1_000L) {
                        // Room suspend DAOs run on Room's own executor; no manual withContext needed.
                        readingSessionDao.insert(
                            dev.readflow.core.database.ReadingSessionEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                bookId = bookId,
                                startedAt = sessionStart,
                                durationMs = duration,
                                deviceId = settings.deviceId.first(),
                            )
                        )
                    }
                }
            }
            // Force-save progress before close so debounce doesn't swallow it.
            if (bookId != null && locator != null) {
                attempt { persistProgress(bookId, locator, settings.deviceId.first()) }
                attempt { persistReaderState(bookId = bookId, locator = locator) }
                attempt { saveEngineStateIfPresent(bookId, engine) }
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    attempt { engine?.close() }
                }
            } finally {
                _uiState.update { current ->
                    ReaderUiState(menuConfig = current.menuConfig)
                }
                currentBookId = null
                activeEngine = null
            }
        }
        closeCancellation?.let { throw it }
    }

    override fun onCleared() {
        openJob?.cancel()
        activeEngine?.let { engine ->
            cleanupScope.launch { runCatching { engine.close() } }
        }
        super.onCleared()
    }

    private suspend fun restoreEngineStateIfPresent(bookId: String?, engine: ReaderEngine) {
        val id = bookId ?: return
        val state = try {
            engineStateStore.load(id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (state == null || state.isEmpty()) return
        try {
            engine.restoreState(state)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            try {
                engineStateStore.evict(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    private suspend fun saveEngineStateIfPresent(bookId: String?, engine: ReaderEngine?) {
        val id = bookId ?: return
        val state = try {
            engine?.saveState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (state == null || state.isEmpty()) return
        try {
            engineStateStore.save(id, state)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    private fun updateUiStateAndPersist(transform: (ReaderUiState) -> ReaderUiState) {
        _uiState.update(transform)
        persistReaderState()
    }

    private fun persistReaderState(
        bookId: String? = currentBookId,
        locator: Locator? = null,
        loadingState: LoadingState = _uiState.value.loadingState,
        error: ReadflowError? = (loadingState as? LoadingState.Error)?.error,
    ) {
        val id = bookId ?: return
        val state = _uiState.value
        val currentLocator = locator
            ?: state.engine?.currentLocator?.value
            ?: restoredReaderState?.takeIf { it.bookId == id }?.currentLocator
        val readerState = ReaderState(
            bookId = id,
            loadingState = loadingState,
            currentLocator = currentLocator,
            fontSize = state.fontSizeSp.toInt(),
            lineSpacing = state.lineSpacing,
            readingMode = state.readingMode.toReaderReadingMode(),
            theme = state.themeMode,
            isUiVisible = state.isUiVisible,
            error = error,
        )
        savedStateHandle[READER_STATE_SAVED_STATE_KEY] = Json.encodeToString(readerState)
        restoredReaderState = readerState
    }

    private suspend fun persistProgress(
        bookId: String,
        locator: Locator,
        deviceId: String,
    ): ReadingProgress {
        val now = System.currentTimeMillis()
        val progression = locator.totalProgression ?: 0f
        progressDao.upsert(
            ReadingProgressEntity(
                bookId = bookId,
                locatorJson = Json.encodeToString(locator),
                totalProgression = progression,
                progressPercent = progression,
                updatedAt = now,
                deviceId = deviceId,
            ),
        )
        bookDao.updateLastReadAt(bookId, now)
        return ReadingProgress(bookId, locator, progression, now, deviceId)
    }

    private suspend fun applyRemoteProgress(
        bookId: String,
        progress: ReadingProgress,
        engine: ReaderEngine,
    ) {
        persistRemoteProgress(bookId, progress)
        if (!engine.currentLocator.value.sameDisplayPositionAs(progress.locator)) {
            engine.goTo(progress.locator)
        }
        persistReaderState(bookId = bookId, locator = progress.locator)
    }

    private suspend fun persistRemoteProgress(
        bookId: String,
        progress: ReadingProgress,
    ) {
        val progression = progress.totalProgression()
        progressDao.upsert(
            ReadingProgressEntity(
                bookId = bookId,
                locatorJson = Json.encodeToString(progress.locator),
                totalProgression = progression,
                progressPercent = progression,
                updatedAt = progress.updatedAt,
                deviceId = progress.deviceId,
            ),
        )
        bookDao.updateLastReadAt(bookId, progress.updatedAt)
    }

    private fun ReadingProgress.totalProgression(): Float =
        (locator.totalProgression ?: progressPercent).coerceIn(0f, 1f)

    private fun Locator.sameDisplayPositionAs(other: Locator): Boolean {
        if (strategy != other.strategy) return false
        if (strategy != LocatorStrategy.Unknown) return true
        return progression == other.progression && totalProgression == other.totalProgression
    }

    private companion object {
        const val MIN_FONT_SP = dev.readflow.core.prefs.ReaderTypography.MIN_FONT_SP
        const val MAX_FONT_SP = dev.readflow.core.prefs.ReaderTypography.MAX_FONT_SP
        const val MIN_ZOOM_SCALE = 1f
        const val MAX_ZOOM_SCALE = 4f
        const val FONT_PREVIEW_COMMIT_DELAY_MS = 350L
        const val DEFAULT_HIGHLIGHT_COLOR = 0x66FFE082
    }
}

private fun SavedStateHandle.readerStateOrNull(): ReaderState? =
    get<String>(READER_STATE_SAVED_STATE_KEY)?.let { json ->
        runCatching { Json.decodeFromString<ReaderState>(json) }.getOrNull()
    }

private fun ReaderState?.toReaderUiState(): ReaderUiState {
    val restored = this ?: return ReaderUiState()
    return ReaderUiState(
        loadingState = when (val loading = restored.loadingState) {
            is LoadingState.Error -> loading
            else -> LoadingState.Idle
        },
        bookTitle = restored.bookMeta?.title.orEmpty(),
        fontSizeSp = restored.fontSize.toFloat(),
        lineSpacing = clampedReaderLineSpacing(restored.lineSpacing),
        readingMode = restored.readingMode.toReadingMode(),
        themeMode = restored.theme,
        isUiVisible = restored.isUiVisible,
        canBookmark = true,
    )
}

private fun ReaderReadingMode.toReadingMode(): ReadingMode = when (this) {
    ReaderReadingMode.SCROLL -> ReadingMode.SCROLL
    ReaderReadingMode.PAGED -> ReadingMode.PAGED
}

private fun ReadingMode.toReaderReadingMode(): ReaderReadingMode = when (this) {
    ReadingMode.SCROLL -> ReaderReadingMode.SCROLL
    ReadingMode.PAGED -> ReaderReadingMode.PAGED
}
