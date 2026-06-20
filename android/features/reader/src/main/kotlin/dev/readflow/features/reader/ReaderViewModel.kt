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
import dev.readflow.core.sync.SyncManager
import dev.readflow.render.api.PageTransitionHostFactory
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderEngineRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
    val isUiVisible: Boolean = true,
)

class ReaderViewModel(
    private val engineRegistry: ReaderEngineRegistry,
    val hostFactory: PageTransitionHostFactory,
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val syncManager: SyncManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var currentBookId: String? = null
    private var progressJob: Job? = null

    fun onIntent(intent: ReaderIntent) = when (intent) {
        is ReaderIntent.OpenById -> openById(intent.bookId)
        is ReaderIntent.OpenBook -> openByUri(null, intent.uri)
        is ReaderIntent.CloseBook -> close()
        is ReaderIntent.GoTo -> goTo(intent.locator)
        is ReaderIntent.SetFontSize -> setFontSize(intent.sp)
        is ReaderIntent.SetTheme -> _uiState.update { it.copy(themeMode = intent.theme) }
        is ReaderIntent.ToggleChrome -> _uiState.update { it.copy(isUiVisible = !it.isUiVisible) }
        else -> Unit
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
            // Restore saved progress
            bookId?.let { id ->
                progressDao.get(id)?.let { saved ->
                    runCatching {
                        engine.goTo(Json.decodeFromString<Locator>(saved.locatorJson))
                    }
                }
            }
            currentBookId = bookId
            _uiState.update { it.copy(loadingState = LoadingState.Loaded, engine = engine, bookTitle = title) }
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
                // LWW sync (no-op with NoOpSyncBackend; activates with future backends)
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
    }

    private fun setFontSize(sp: Float) {
        val engine = _uiState.value.engine ?: return
        _uiState.update { it.copy(fontSizeSp = sp) }
        viewModelScope.launch { engine.setFontSize(sp) }
    }

    private fun close() {
        progressJob?.cancel()
        val engine = _uiState.value.engine
        viewModelScope.launch {
            engine?.close()
            _uiState.value = ReaderUiState()
        }
    }
}
