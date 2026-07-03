package dev.readflow.features.reader

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.database.BookDao
import dev.readflow.core.database.BookmarkDao
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
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.sync.SyncManager
import dev.readflow.render.api.EngineStateStore
import dev.readflow.render.api.InitialLocatorAwareReaderEngine
import dev.readflow.render.api.PageTransitionHostFactory
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderEngineRegistry
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.ZoomableReaderEngine
import dev.readflow.render.api.toReadingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

internal const val READER_STATE_SAVED_STATE_KEY = "reader_state_json"

data class ReaderUiState(
    val loadingState: LoadingState = LoadingState.Idle,
    val engine: ReaderEngine? = null,
    val bookTitle: String = "",
    val fontSizeSp: Float = 16f,
    val lineSpacing: Float = 1.3f,
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

    private var restoredReaderState: ReaderState? = savedStateHandle.readerStateOrNull()
    private var currentBookId: String? = restoredReaderState?.bookId

    private val _uiState = MutableStateFlow(restoredReaderState.toReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null
    private var fontPreviewPersistJob: Job? = null
    private var searchJob: Job? = null
    private var bookmarkJob: Job? = null
    private var textSelectionJob: Job? = null
    private var annotationJob: Job? = null
    private var settingsFontJob: Job? = null
    private var settingsLineSpacingJob: Job? = null
    private var sessionStartedAt: Long? = null
    // 记录最近一次打开请求，供错误页「重试」复用。
    private var lastOpenRequest: OpenRequest? = null

    fun onIntent(intent: ReaderIntent) = when (intent) {
        is ReaderIntent.OpenById -> openById(intent.bookId)
        is ReaderIntent.OpenBook -> openByUri(null, intent.uri)
        ReaderIntent.Retry -> retry()
        ReaderIntent.CloseBook -> close()
        is ReaderIntent.GoTo -> goTo(intent.locator)
        is ReaderIntent.SeekToProgress -> seekToProgress(intent.fraction)
        is ReaderIntent.GoToTocEntry -> goToTocEntry(intent.entry)
        is ReaderIntent.SetFontSize -> setFontSize(intent.sp)
        is ReaderIntent.PreviewFontSize -> previewFontSize(intent.sp)
        is ReaderIntent.PreviewZoom -> previewZoom(intent.scale)
        is ReaderIntent.SetLineSpacing -> setLineSpacing(intent.multiplier)
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
        is ReaderIntent.SetSearchQuery -> setSearchQuery(intent.query)
        ReaderIntent.SubmitSearch -> submitSearch()
        is ReaderIntent.GoToSearchResult -> goToSearchResult(intent.result)
        ReaderIntent.ClearSearch -> clearSearch()
        ReaderIntent.ClearTextSelection -> clearTextSelection()
        ReaderIntent.ToggleChrome -> updateUiStateAndPersist { it.copy(isUiVisible = !it.isUiVisible, activePanel = null) }
        ReaderIntent.FontPanel -> updateUiStateAndPersist { it.copy(activePanel = ReaderPanel.FONT, isUiVisible = true) }
        ReaderIntent.ThemePanel -> updateUiStateAndPersist { it.copy(activePanel = ReaderPanel.THEME, isUiVisible = true) }
        ReaderIntent.DismissGuide -> dismissGuide()
    }

    private fun dismissGuide() {
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

    private fun openById(bookId: String) {
        lastOpenRequest = OpenRequest.ById(bookId)
        val restoredForBook = restoredReaderState?.takeIf { it.bookId == bookId }
        _uiState.update { it.copy(loadingState = LoadingState.Loading) }
        currentBookId = bookId
        persistReaderState(bookId = bookId, loadingState = LoadingState.Loading)
        viewModelScope.launch {
            val book = bookDao.getById(bookId)
            if (book == null) {
                val error = ReadflowError.notFound("book", bookId)
                _uiState.update { it.copy(loadingState = LoadingState.Error(error)) }
                persistReaderState(bookId = bookId, loadingState = LoadingState.Error(error), error = error)
                return@launch
            }
            val uri = book.localUri?.let { Uri.parse(it) }
            if (uri == null) {
                val error = ReadflowError.io("本地文件未找到")
                _uiState.update { it.copy(loadingState = LoadingState.Error(error), bookTitle = book.title) }
                persistReaderState(bookId = bookId, loadingState = LoadingState.Error(error), error = error)
                return@launch
            }
            openByUri(bookId, uri, book.title, restoredForBook)
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
        viewModelScope.launch {
            val engine = runCatching { engineRegistry.resolve(uri) }.getOrElse { error ->
                val readflowError = ReadflowError.io(error.message ?: "无法打开文件")
                _uiState.update { it.copy(loadingState = LoadingState.Error(readflowError), bookTitle = title) }
                bookId?.let {
                    persistReaderState(bookId = it, loadingState = LoadingState.Error(readflowError), error = readflowError)
                }
                return@launch
            }
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
            (engine as? InitialLocatorAwareReaderEngine)?.setInitialLocator(displayLocator)
            runCatching { engine.openBook(uri) }.onFailure { error ->
                val readflowError = ReadflowError.io(error.message ?: "无法打开文件")
                _uiState.update { it.copy(loadingState = LoadingState.Error(readflowError), bookTitle = title) }
                bookId?.let {
                    persistReaderState(bookId = it, loadingState = LoadingState.Error(readflowError), error = readflowError)
                }
                return@launch
            }
            val savedFontSize = restoredForBook?.fontSize?.toFloat() ?: settings.fontSize.first().toFloat()
            val savedLineSpacing = clampedReaderLineSpacing(settings.lineSpacing.first())
            val savedTheme = restoredForBook?.theme ?: settings.themeMode.first()
            engine.setFontSize(savedFontSize)
            engine.setLineSpacing(savedLineSpacing)
            engine.setTheme(savedTheme)
            engine.setSerifFont(settings.useSourceHanFont.first())
            engine.setFont(settings.fontChoice.first().serialize())
            val savedFlipStyle = settings.pageFlipStyle.first()
            engine.setPageFlipStyle(savedFlipStyle)
            val txtEnc = settings.txtEncoding.first()
            if (txtEnc.charsetName != null) {
                engine.setTxtEncodingOverride(txtEnc.charsetName)
            }
            val savedReadingMode = (restoredForBook?.readingMode ?: settings.readingMode.first()).toReadingMode()
                ?.takeIf { it in engine.supportedModes }
            savedReadingMode?.let { mode ->
                runCatching { engine.setMode(mode) }
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
                    runCatching { engine.goTo(locator) }
                }
            }
            currentBookId = bookId
            _uiState.update {
                it.copy(
                    loadingState = LoadingState.Loaded,
                    engine = engine,
                    bookTitle = title,
                    fontSizeSp = savedFontSize,
                    lineSpacing = savedLineSpacing,
                    readingMode = engine.pagingKind.value.toReadingMode(),
                    supportedModes = engine.supportedModes,
                    pageFlipStyle = savedFlipStyle,
                    themeMode = savedTheme,
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
        if (bookId == null) {
            _uiState.update { it.copy(bookmarks = ReaderBookmarkState(), canBookmark = false) }
            return
        }
        bookmarkJob = viewModelScope.launch {
            combine(bookmarkDao.observeForBook(bookId), engine.currentLocator) { entities, locator ->
                readerBookmarkStateFor(entities, locator)
            }.collect { bookmarks ->
                _uiState.update { it.copy(bookmarks = bookmarks, canBookmark = true) }
            }
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
            settings.fontChoice.collect { choice ->
                engine.setFont(choice.serialize())
            }
        }
        settingsLineSpacingJob?.cancel()
        settingsLineSpacingJob = viewModelScope.launch {
            settings.lineSpacing.collect { spacing ->
                val clamped = clampedReaderLineSpacing(spacing)
                engine.setLineSpacing(clamped)
                _uiState.update { it.copy(lineSpacing = clamped) }
            }
        }
    }

    private fun goTo(locator: Locator) {
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch {
            engine.goTo(locator)
            persistExplicitNavigation(engine, locator)
        }
        updateUiStateAndPersist { it.copy(activePanel = null) }
    }

    private fun seekToProgress(fraction: Float) {
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch {
            engine.seekToProgress(fraction)
            persistExplicitNavigation(engine, engine.currentLocator.value)
        }
    }

    private fun goToTocEntry(entry: TocEntry) {
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch {
            engine.goToTocEntry(entry)
            persistExplicitNavigation(engine, engine.currentLocator.value)
        }
        updateUiStateAndPersist { it.copy(activePanel = null, isUiVisible = false) }
    }

    private fun toggleBookmark() {
        val bookId = currentBookId ?: return
        val engine = _uiState.value.engine ?: return
        val currentBookmarkId = _uiState.value.bookmarks.currentBookmarkId
        val currentBookmark = _uiState.value.bookmarks.items.firstOrNull { it.id == currentBookmarkId }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val deviceId = settings.deviceId.first()
            if (currentBookmark != null) {
                bookmarkDao.markDeleted(currentBookmark.id, now, deviceId)
                syncManager.syncBookmark(currentBookmark.toDeletedBookmarkModel(bookId, deviceId, now))
            } else {
                val entity = readerBookmarkEntityFor(
                    bookId = bookId,
                    locator = engine.currentLocator.value,
                    deviceId = deviceId,
                    now = now,
                    id = UUID.randomUUID().toString(),
                )
                bookmarkDao.upsert(entity)
                entity.toBookmarkModel()?.let { syncManager.syncBookmark(it) }
            }
        }
    }

    private fun goToBookmark(bookmark: ReaderBookmarkItem) {
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch {
            engine.goTo(bookmark.locator)
            persistExplicitNavigation(engine, bookmark.locator)
        }
        updateUiStateAndPersist { it.copy(activePanel = null, isUiVisible = false) }
    }

    private fun removeBookmark(bookmark: ReaderBookmarkItem) {
        val bookId = currentBookId ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val deviceId = settings.deviceId.first()
            bookmarkDao.markDeleted(bookmark.id, now, deviceId)
            syncManager.syncBookmark(bookmark.toDeletedBookmarkModel(bookId, deviceId, now))
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
        viewModelScope.launch {
            engine.goTo(annotation.start)
            persistExplicitNavigation(engine, annotation.start)
        }
        updateUiStateAndPersist { it.copy(activePanel = null, isUiVisible = false) }
    }

    private fun setSearchQuery(query: String) {
        searchJob?.cancel()
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
        searchJob = viewModelScope.launch {
            val searchResult = runCatching { engine.search(query) }
            _uiState.update { state ->
                if (state.search.query != query) {
                    state
                } else {
                    searchResult.fold(
                        onSuccess = { locators ->
                            val results = readerSearchResultsFor(locators)
                            state.copy(
                                search = state.search.copy(
                                    results = results,
                                    selectedIndex = null,
                                    isSearching = false,
                                    message = when {
                                        results.isNotEmpty() -> null
                                        !engine.supportsSearch -> "当前格式暂不支持搜索"
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
        viewModelScope.launch {
            engine.goTo(result.locator)
            persistExplicitNavigation(engine, result.locator)
        }
        updateUiStateAndPersist {
            it.copy(
                search = it.search.copy(selectedIndex = result.index),
                activePanel = null,
                isUiVisible = false,
            )
        }
    }

    private suspend fun persistExplicitNavigation(engine: ReaderEngine, locator: Locator) {
        val bookId = currentBookId ?: return
        persistProgress(bookId, locator, settings.deviceId.first())
        persistReaderState(bookId = bookId, locator = locator)
        saveEngineStateIfPresent(bookId, engine)
    }

    private fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(search = it.search.cleared()) }
    }

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

    private fun close() {
        val bookId = currentBookId
        val locator = _uiState.value.engine?.currentLocator?.value
        progressJob?.cancel()
        fontPreviewPersistJob?.cancel()
        searchJob?.cancel()
        bookmarkJob?.cancel()
        textSelectionJob?.cancel()
        annotationJob?.cancel()
        settingsFontJob?.cancel()
        settingsLineSpacingJob?.cancel()
        val engine = _uiState.value.engine
        viewModelScope.launch {
            // Write reading session before close (threshold > 1s to filter noise)
            val sessionStart = sessionStartedAt
            sessionStartedAt = null
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
            // Force-save progress before close so debounce doesn't swallow it.
            if (bookId != null && locator != null) {
                persistProgress(bookId, locator, settings.deviceId.first())
                persistReaderState(bookId = bookId, locator = locator)
                saveEngineStateIfPresent(bookId, engine)
            }
            engine?.close()
            _uiState.value = ReaderUiState()
        }
    }

    private suspend fun restoreEngineStateIfPresent(bookId: String?, engine: ReaderEngine) {
        val id = bookId ?: return
        val state = runCatching { engineStateStore.load(id) }.getOrNull()
        if (state == null || state.isEmpty()) return
        runCatching {
            engine.restoreState(state)
        }.onFailure {
            runCatching { engineStateStore.evict(id) }
        }
    }

    private suspend fun saveEngineStateIfPresent(bookId: String?, engine: ReaderEngine?) {
        val id = bookId ?: return
        val state = runCatching { engine?.saveState() }.getOrNull()
        if (state == null || state.isEmpty()) return
        runCatching { engineStateStore.save(id, state) }
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
