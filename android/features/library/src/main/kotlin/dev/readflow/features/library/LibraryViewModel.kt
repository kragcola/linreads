package dev.readflow.features.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.calibre.CalibreClient
import dev.readflow.core.calibre.CalibreRepositoryImpl
import dev.readflow.core.database.LibraryRepository
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.FolderScanner
import dev.readflow.extensions.api.LocalFileBookSource
import dev.readflow.extensions.api.ScannedBook
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

    private val _openBook = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openBook: SharedFlow<String> = _openBook.asSharedFlow()

    private val _scanResults = MutableStateFlow<List<ScannedBook>?>(null)
    val scanResults: StateFlow<List<ScannedBook>?> = _scanResults.asStateFlow()

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

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            when (val result = localSource.import(uri)) {
                is ReadflowResult.Success -> repository.upsertBook(result.value.first)
                is ReadflowResult.Failure -> _uiState.value =
                    _uiState.value.copy(error = result.error.message)
            }
        }
    }

    fun scanFolder(context: Context, treeUri: Uri) {
        viewModelScope.launch {
            _scanResults.value = FolderScanner.scan(context, treeUri)
        }
    }

    fun clearScan() { _scanResults.value = null }

    fun importFromFolder(uris: List<Uri>) {
        viewModelScope.launch {
            val metas = uris.mapNotNull { uri ->
                (localSource.import(uri) as? ReadflowResult.Success)?.value?.first
            }
            if (metas.isNotEmpty()) repository.upsertAll(metas)
        }
    }

    fun deleteBook(id: String) {
        viewModelScope.launch { repository.deleteBook(id) }
    }

    fun renameBook(id: String, title: String) {
        viewModelScope.launch { repository.renameBook(id, title) }
    }

    /** After drag reorder, persist new sort positions for the visible flat list. */
    fun reorder(items: List<LibraryItem>) {
        viewModelScope.launch {
            items.forEachIndexed { idx, item ->
                val ids = when (item) {
                    is LibraryItem.Single -> listOf(item.book.id)
                    is LibraryItem.Bundle -> item.bundle.books.map { it.id }
                }
                ids.forEach { repository.updateSortOrder(it, idx) }
            }
        }
    }

    fun moveToGroup(bookId: String, groupName: String) {
        viewModelScope.launch { repository.setCollection(bookId, groupName) }
    }

    fun removeFromGroup(bookId: String) {
        viewModelScope.launch { repository.setCollection(bookId, null) }
    }

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
