package dev.readflow.features.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.calibre.CalibreRepository
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.BookRemovalMode
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.UncoordinatedBookAssetOperations
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.FolderScanner
import dev.readflow.extensions.api.LocalBookImporter
import dev.readflow.extensions.api.ScannedBook
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class LibraryFilter { ALL, OFFLINE }

data class LibraryUiState(
    val isLoading: Boolean = false,
    val items: List<LibraryItem> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val offlineCount: Int = 0,
)

data class CalibreSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<BookMeta> = emptyList(),
    val downloadingBookId: String? = null,
    val message: String? = null,
    val error: String? = null,
)

data class ScanProgress(
    val found: Int = 0,
    val scanning: Boolean = true,
    val books: List<ScannedBook> = emptyList(),
)

class LibraryViewModel(
    private val repository: LibraryStore,
    private val localSource: LocalBookImporter,
    private val settings: SettingsRepository,
    private val calibreRepositoryFactory: (String) -> CalibreRepository,
    private val assetOperations: BookAssetOperationCoordinator = UncoordinatedBookAssetOperations,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _openBook = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openBook: SharedFlow<String> = _openBook.asSharedFlow()

    private val _openBundle = MutableSharedFlow<BookBundle>(extraBufferCapacity = 1)
    val openBundle: SharedFlow<BookBundle> = _openBundle.asSharedFlow()

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    private val _calibreSearchState = MutableStateFlow(CalibreSearchUiState())
    val calibreSearchState: StateFlow<CalibreSearchUiState> = _calibreSearchState.asStateFlow()

    private var scanJob: Job? = null
    private var calibreSearchJob: Job? = null
    private var allShelfItems: List<LibraryItem> = emptyList()
    private var libraryFilter: LibraryFilter = LibraryFilter.ALL
    private var clearDeleteFailureOnNextShelfEmission = false

    init {
        viewModelScope.launch {
            repository.observeShelf()
                .catch { e -> _uiState.value = LibraryUiState(error = e.message ?: "加载失败") }
                .collect { items ->
                    allShelfItems = items
                    publishShelf()
                }
        }
    }

    fun onItemClick(item: LibraryItem) {
        when (item) {
            is LibraryItem.Single -> _openBook.tryEmit(item.book.id)
            is LibraryItem.Bundle -> _openBundle.tryEmit(item.bundle)
        }
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            assetOperations.produce(bookId = null) {
                when (val result = localSource.import(uri)) {
                    is ReadflowResult.Success -> repository.upsertBook(result.value.first)
                    is ReadflowResult.Failure -> _uiState.value =
                        _uiState.value.copy(error = result.error.message)
                }
            }
        }
    }

    fun scanFolder(context: Context, treeUri: Uri) {
        scanJob?.cancel()
        val accumulated = mutableListOf<ScannedBook>()
        _scanProgress.value = ScanProgress()
        scanJob = viewModelScope.launch {
            FolderScanner.scan(context, treeUri) { book ->
                accumulated.add(book)
                _scanProgress.value = ScanProgress(found = accumulated.size, scanning = true, books = accumulated.toList())
            }
            _scanProgress.value = _scanProgress.value?.copy(scanning = false)
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _scanProgress.value = null
    }

    fun clearScan() { _scanProgress.value = null }

    fun importFromFolder(uris: List<Uri>) {
        viewModelScope.launch {
            val (imported, failed) = assetOperations.produce(bookId = null) {
                val results = uris.map { uri -> localSource.import(uri) }
                val metas = results.mapNotNull { result ->
                    (result as? ReadflowResult.Success)?.value?.first
                }
                if (metas.isNotEmpty()) repository.upsertAll(metas)
                metas.size to (results.size - metas.size)
            }
            _uiState.value = _uiState.value.copy(
                error = when {
                    failed == 0 -> null
                    imported == 0 -> "导入失败：${failed} 个文件无法导入"
                    else -> "已导入 $imported 本，${failed} 个文件失败"
                },
            )
        }
    }

    fun deleteBook(id: String, mode: BookRemovalMode) {
        viewModelScope.launch {
            runCatching {
                assetOperations.delete(id) {
                    when (mode) {
                        BookRemovalMode.REMOVE_FROM_SHELF -> repository.deleteBook(id)
                        BookRemovalMode.DELETE_ALL -> repository.deleteBookCompletely(id)
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                clearDeleteFailureOnNextShelfEmission = true
                _uiState.value = _uiState.value.copy(
                    error = error.message ?: "删除失败",
                    notice = "删除失败：${error.message ?: "请稍后重试"}",
                )
            }
        }
    }

    fun clearNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    fun removeDownloadedAsset(id: String) {
        viewModelScope.launch {
            val removed = assetOperations.delete(id) { repository.removeDownloadedAsset(id) }
            _calibreSearchState.value = _calibreSearchState.value.copy(
                message = if (removed) "已移除本地下载" else "未找到可移除的下载",
                error = null,
            )
        }
    }

    fun renameBook(id: String, title: String) {
        viewModelScope.launch { repository.renameBook(id, title) }
    }

    fun reorder(items: List<LibraryItem>) {
        viewModelScope.launch {
            val completeItems = mergeVisibleShelfOrder(allShelfItems, items) ?: return@launch
            val ids = completeItems.flatMap { item ->
                when (item) {
                    is LibraryItem.Single -> listOf(item.book.id)
                    is LibraryItem.Bundle -> item.bundle.books.map { it.id }
                }
            }
            repository.updateShelfOrder(ids)
        }
    }

    fun moveToGroup(bookId: String, groupName: String) {
        viewModelScope.launch { repository.setCollection(bookId, groupName) }
    }

    fun removeFromGroup(bookId: String) {
        viewModelScope.launch { repository.setCollection(bookId, null) }
    }

    fun ungroupBundle(name: String) {
        viewModelScope.launch { repository.ungroupBundle(name) }
    }

    fun renameBundle(oldName: String, newName: String) {
        viewModelScope.launch { repository.renameBundle(oldName, newName) }
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        libraryFilter = filter
        publishShelf()
    }

    fun updateCalibreQuery(query: String) {
        _calibreSearchState.value = _calibreSearchState.value.copy(query = query, error = null, message = null)
    }

    fun searchCalibre() {
        calibreSearchJob?.cancel()
        calibreSearchJob = viewModelScope.launch {
            val baseUrl = settings.calibreBaseUrl.first()
            if (baseUrl.isNullOrBlank()) {
                _calibreSearchState.value = _calibreSearchState.value.copy(
                    error = "请先在设置中连接 Calibre",
                    message = null,
                )
                return@launch
            }
            _calibreSearchState.value = _calibreSearchState.value.copy(
                isSearching = true,
                error = null,
                message = null,
            )
            val calibreRepo = runCatching { calibreRepositoryFactory(baseUrl) }
                .getOrElse { error ->
                    _calibreSearchState.value = _calibreSearchState.value.copy(
                        isSearching = false,
                        error = "Calibre: ${error.message ?: "服务器地址无效"}",
                    )
                    return@launch
            }
            val query = _calibreSearchState.value.query
            val result = try {
                calibreRepo.search(query)
            } finally {
                calibreRepo.close()
            }
            when (result) {
                is ReadflowResult.Success -> if (_calibreSearchState.value.query == query) {
                    _calibreSearchState.value = _calibreSearchState.value.copy(
                        isSearching = false,
                        results = result.value,
                        message = if (result.value.isEmpty()) "没有找到匹配的 Calibre 书籍" else null,
                    )
                }
                is ReadflowResult.Failure -> if (_calibreSearchState.value.query == query) {
                    _calibreSearchState.value = _calibreSearchState.value.copy(
                        isSearching = false,
                        error = "Calibre: ${result.error.message}",
                    )
                }
            }
        }
    }

    fun downloadCalibreBook(bookId: String) {
        viewModelScope.launch {
            assetOperations.produce(bookId = "calibre-$bookId") {
                val baseUrl = settings.calibreBaseUrl.first()
                if (baseUrl.isNullOrBlank()) {
                    _calibreSearchState.value = _calibreSearchState.value.copy(error = "请先在设置中连接 Calibre")
                    return@produce
                }
                _calibreSearchState.value = _calibreSearchState.value.copy(
                    downloadingBookId = bookId,
                    error = null,
                    message = null,
                )
                val calibreRepo = runCatching { calibreRepositoryFactory(baseUrl) }
                    .getOrElse { error ->
                        _calibreSearchState.value = _calibreSearchState.value.copy(
                            downloadingBookId = null,
                            error = "Calibre: ${error.message ?: "服务器地址无效"}",
                        )
                        return@produce
                    }
                val result = try {
                    calibreRepo.download(bookId)
                } finally {
                    calibreRepo.close()
                }
                when (result) {
                    is ReadflowResult.Success -> {
                        repository.upsertBook(result.value)
                        _calibreSearchState.value = _calibreSearchState.value.copy(
                            downloadingBookId = null,
                            message = "已下载《${result.value.title}》",
                        )
                    }
                    is ReadflowResult.Failure -> _calibreSearchState.value = _calibreSearchState.value.copy(
                        downloadingBookId = null,
                        error = "Calibre: ${result.error.message}",
                    )
                }
            }
        }
    }

    private fun publishShelf() {
        val clearDeleteFailure = clearDeleteFailureOnNextShelfEmission
        clearDeleteFailureOnNextShelfEmission = false
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            items = allShelfItems.filterFor(libraryFilter),
            error = if (clearDeleteFailure) null else _uiState.value.error,
            notice = if (clearDeleteFailure) null else _uiState.value.notice,
            filter = libraryFilter,
            offlineCount = allShelfItems.offlineReadableCount(),
        )
    }
}

private fun mergeVisibleShelfOrder(
    allItems: List<LibraryItem>,
    reorderedVisibleItems: List<LibraryItem>,
): List<LibraryItem>? {
    if (allItems.isEmpty()) return reorderedVisibleItems
    val reorderedKeys = reorderedVisibleItems.map(LibraryItem::shelfIdentity)
    if (reorderedKeys.distinct().size != reorderedKeys.size) return null

    val fullItemsByKey = allItems.associateBy(LibraryItem::shelfIdentity)
    val reorderedFullItems = reorderedKeys.mapNotNull(fullItemsByKey::get)
    if (reorderedFullItems.size != reorderedKeys.size) return null

    val reorderedKeySet = reorderedKeys.toSet()
    val replacements = reorderedFullItems.iterator()
    return allItems.map { item ->
        if (item.shelfIdentity() in reorderedKeySet) replacements.next() else item
    }
}

private fun LibraryItem.shelfIdentity(): String = when (this) {
    is LibraryItem.Single -> "book:${book.id}"
    is LibraryItem.Bundle -> "bundle:${bundle.name}"
}

private fun List<LibraryItem>.filterFor(filter: LibraryFilter): List<LibraryItem> =
    when (filter) {
        LibraryFilter.ALL -> this
        LibraryFilter.OFFLINE -> mapNotNull { item ->
            when (item) {
                is LibraryItem.Single ->
                    item.takeIf { it.book.isOfflineReadable }
                is LibraryItem.Bundle -> {
                    val offlineBooks = item.bundle.books.filter { it.isOfflineReadable }
                    if (offlineBooks.isEmpty()) null else item.copy(bundle = item.bundle.copy(books = offlineBooks))
                }
            }
        }
    }

private fun List<LibraryItem>.offlineReadableCount(): Int =
    sumOf { item ->
        when (item) {
            is LibraryItem.Single -> if (item.book.isOfflineReadable) 1 else 0
            is LibraryItem.Bundle -> item.bundle.books.count { it.isOfflineReadable }
        }
    }

private val BookMeta.isOfflineReadable: Boolean
    get() = localUri != null &&
        (!id.startsWith("calibre-") || downloadStatus == DownloadStatus.DOWNLOADED)
