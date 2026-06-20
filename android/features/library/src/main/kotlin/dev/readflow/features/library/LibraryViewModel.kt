package dev.readflow.features.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.calibre.CalibreClient
import dev.readflow.core.calibre.CalibreRepositoryImpl
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.LocalFileBookSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isLoading: Boolean = false,
    val items: List<LibraryItem> = emptyList(),
    val error: String? = null,
)

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val localSource: LocalFileBookSource,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** One-shot navigation event: bookId to open in reader. */
    private val _openBook = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openBook: SharedFlow<String> = _openBook.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.observeShelf()
                .catch { e -> _uiState.value = LibraryUiState(error = e.message ?: "加载失败") }
                .collect { items -> _uiState.value = LibraryUiState(isLoading = false, items = items) }
        }
        viewModelScope.launch {
            settings.calibreBaseUrl.collect { url ->
                if (!url.isNullOrBlank()) refreshFromCalibre(url)
            }
        }
    }

    fun onItemClick(item: LibraryItem) {
        val bookId = when (item) {
            is LibraryItem.Single -> item.book.id
            is LibraryItem.Bundle -> item.bundle.books.first().id
        }
        _openBook.tryEmit(bookId)
    }

    /** Called after SAF picker returns a Uri. Copies file, upserts to Room. */
    fun importBook(uri: Uri) {
        viewModelScope.launch {
            when (val result = localSource.import(uri)) {
                is ReadflowResult.Success -> repository.upsertBook(result.value.first)
                is ReadflowResult.Failure -> _uiState.value =
                    _uiState.value.copy(error = result.error.message)
            }
        }
    }

    /** Fetches the Calibre library and upserts all books into Room. */
    fun refreshFromCalibre(baseUrl: String) {
        viewModelScope.launch {
            val calibreRepo = CalibreRepositoryImpl(CalibreClient(baseUrl))
            when (val result = calibreRepo.search("")) {
                is ReadflowResult.Success -> result.value.forEach { repository.upsertBook(it) }
                is ReadflowResult.Failure -> _uiState.value =
                    _uiState.value.copy(error = "Calibre: ${result.error.message}")
            }
        }
    }
}
