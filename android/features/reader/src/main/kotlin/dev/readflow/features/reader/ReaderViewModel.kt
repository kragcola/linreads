package dev.readflow.features.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.database.BookDao
import dev.readflow.core.database.ReadingProgressDao
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.Locator
import dev.readflow.core.model.ReadingProgress
import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.core.sync.SyncManager
import dev.readflow.render.api.PageTransitionHostFactory
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderEngineRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ReaderUiState(
    val loadingState: LoadingState = LoadingState.Idle,
    val engine: ReaderEngine? = null,
    val bookTitle: String = "",
    val fontSizeSp: Float = 16f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isUiVisible: Boolean = false,  // 默认隐藏，点中间呼出
    val activePanel: ReaderPanel? = null,
)

class ReaderViewModel(
    private val engineRegistry: ReaderEngineRegistry,
    val hostFactory: PageTransitionHostFactory,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val syncManager: SyncManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var currentBookId: String? = null
    private var progressJob: Job? = null

    fun onIntent(intent: ReaderIntent) = when (intent) {
        is ReaderIntent.OpenById -> openById(intent.bookId)
        is ReaderIntent.OpenBook -> openByUri(null, intent.uri)
        ReaderIntent.CloseBook -> close()
        is ReaderIntent.GoTo -> goTo(intent.locator)
        is ReaderIntent.GoToTocEntry -> goToTocEntry(intent.entry)
        is ReaderIntent.SetFontSize -> setFontSize(intent.sp)
        is ReaderIntent.SetMode -> setMode(intent.mode)
        is ReaderIntent.SetTheme -> setTheme(intent.theme)
        is ReaderIntent.OpenPanel -> _uiState.update { it.copy(activePanel = intent.panel, isUiVisible = true) }
        ReaderIntent.ClosePanel -> _uiState.update { it.copy(activePanel = null) }
        ReaderIntent.ToggleChrome -> _uiState.update { it.copy(isUiVisible = !it.isUiVisible, activePanel = null) }
        ReaderIntent.FontPanel -> _uiState.update { it.copy(activePanel = ReaderPanel.FONT, isUiVisible = true) }
        ReaderIntent.ThemePanel -> _uiState.update { it.copy(activePanel = ReaderPanel.THEME, isUiVisible = true) }
    }

    private fun openById(bookId: String) {
        _uiState.update { it.copy(loadingState = LoadingState.Loading) }
        viewModelScope.launch {
            val book = bookDao.getById(bookId)
            if (book == null) {
                _uiState.update { it.copy(loadingState = LoadingState.Error(ReadflowError.notFound("book", bookId))) }
                return@launch
            }
            val uri = book.localUri?.let { Uri.parse(it) }
            if (uri == null) {
                _uiState.update { it.copy(loadingState = LoadingState.Error(ReadflowError.io("本地文件未找到"))) }
                return@launch
            }
            openByUri(bookId, uri, book.title)
        }
    }

    private fun openByUri(bookId: String?, uri: Uri, title: String = "") {
        viewModelScope.launch {
            val engine = engineRegistry.resolve(uri)
            engine.openBook(uri)
            val savedFontSize = settings.fontSize.first().toFloat()
            val savedTheme = settings.themeMode.first()
            engine.setFontSize(savedFontSize)
            engine.setTheme(savedTheme)
            // Restore saved progress
            bookId?.let { id ->
                progressDao.get(id)?.let { saved ->
                    runCatching {
                        engine.goTo(Json.decodeFromString<Locator>(saved.locatorJson))
                    }
                }
            }
            currentBookId = bookId
            _uiState.update {
                it.copy(
                    loadingState = LoadingState.Loaded,
                    engine = engine,
                    bookTitle = title,
                    fontSizeSp = savedFontSize,
                    themeMode = savedTheme,
                    activePanel = null,
                )
            }
            watchProgress(engine, bookId)
        }
    }

    private fun watchProgress(engine: ReaderEngine, bookId: String?) {
        progressJob?.cancel()
        bookId ?: return
        progressJob = viewModelScope.launch {
            @Suppress("OPT_IN_USAGE")
            engine.currentLocator.debounce(2_000L).collect { locator ->
                val now = System.currentTimeMillis()
                progressDao.upsert(
                    ReadingProgressEntity(
                        bookId = bookId,
                        locatorJson = Json.encodeToString(locator),
                        totalProgression = locator.totalProgression ?: 0f,
                        progressPercent = locator.totalProgression ?: 0f,
                        updatedAt = now,
                        deviceId = "local",
                    )
                )
                bookDao.updateLastReadAt(bookId, now)
                syncManager.syncProgress(
                    bookId,
                    ReadingProgress(bookId, locator, locator.totalProgression ?: 0f, now, "local"),
                )
            }
        }
    }

    private fun goTo(locator: Locator) {
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch { engine.goTo(locator) }
        _uiState.update { it.copy(activePanel = null) }
    }

    private fun goToTocEntry(entry: TocEntry) {
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch { engine.goToTocEntry(entry) }
        _uiState.update { it.copy(activePanel = null, isUiVisible = false) }
    }

    private fun setFontSize(sp: Float) {
        val engine = _uiState.value.engine ?: return
        val clamped = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        _uiState.update { it.copy(fontSizeSp = clamped) }
        viewModelScope.launch {
            engine.setFontSize(clamped)
            settings.setFontSize(clamped.toInt())
        }
    }

    private fun setTheme(theme: ThemeMode) {
        val engine = _uiState.value.engine
        _uiState.update { it.copy(themeMode = theme) }
        viewModelScope.launch {
            settings.setThemeMode(theme)
            engine?.setTheme(theme)
        }
    }

    private fun setMode(mode: dev.readflow.render.api.ReadingMode) {
        val engine = _uiState.value.engine ?: return
        viewModelScope.launch { engine.setMode(mode) }
    }

    private fun close() {
        val bookId = currentBookId
        val locator = _uiState.value.engine?.currentLocator?.value
        progressJob?.cancel()
        val engine = _uiState.value.engine
        viewModelScope.launch {
            // Force-save progress before close so debounce doesn't swallow it.
            if (bookId != null && locator != null) {
                val now = System.currentTimeMillis()
                progressDao.upsert(
                    ReadingProgressEntity(
                        bookId = bookId,
                        locatorJson = Json.encodeToString(locator),
                        totalProgression = locator.totalProgression ?: 0f,
                        progressPercent = locator.totalProgression ?: 0f,
                        updatedAt = now,
                        deviceId = "local",
                    ),
                )
                bookDao.updateLastReadAt(bookId, now)
            }
            engine?.close()
            _uiState.value = ReaderUiState()
        }
    }

    private companion object {
        const val MIN_FONT_SP = 12f
        const val MAX_FONT_SP = 32f
    }
}
