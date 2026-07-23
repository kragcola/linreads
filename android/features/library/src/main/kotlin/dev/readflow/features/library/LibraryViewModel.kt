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
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.ScannedBook
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import dev.readflow.extensions.api.SourceRegistry
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.stableRemoteBookId
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class LibraryFilter { ALL, OFFLINE }

data class LibraryUiState(
    val isLoading: Boolean = false,
    val items: List<LibraryItem> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val offlineCount: Int = 0,
)

/**
 * Online multi-source library search UI state.
 * [CalibreSearchUiState] is a backwards-compatible projection used by older call sites/tests.
 */
data class OnlineLibraryUiState(
    val sources: List<SourceDescriptor> = emptyList(),
    val selectedSourceId: String? = null,
    val query: String = "",
    val filter: OnlineCatalogFilter = OnlineCatalogFilter(),
    val isSearching: Boolean = false,
    val results: List<OnlineCatalogEntry> = emptyList(),
    val selectedEntryKeys: Set<String> = emptySet(),
    val downloadingKeys: Set<String> = emptySet(),
    val previewUrl: String? = null,
    val message: String? = null,
    val error: String? = null,
    val addSourceName: String = "",
    val addSourceUrl: String = "",
    val addSourceKind: SourceKind = SourceKind.JSON_HTTP,
)

/** Backwards-compatible Calibre-only projection for existing tests and runtime smoke labels. */
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
    private val sourceRegistry: SourceRegistry? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _openBook = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openBook: SharedFlow<String> = _openBook.asSharedFlow()

    private val _openBundle = MutableSharedFlow<BookBundle>(extraBufferCapacity = 1)
    val openBundle: SharedFlow<BookBundle> = _openBundle.asSharedFlow()

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    private val _onlineLibraryState = MutableStateFlow(OnlineLibraryUiState())
    val onlineLibraryState: StateFlow<OnlineLibraryUiState> = _onlineLibraryState.asStateFlow()

    private val _calibreSearchState = MutableStateFlow(CalibreSearchUiState())
    val calibreSearchState: StateFlow<CalibreSearchUiState> = _calibreSearchState.asStateFlow()

    private var scanJob: Job? = null
    private var onlineSearchJob: Job? = null
    private var onlinePreviewJob: Job? = null
    private var calibreSearchJob: Job? = null
    /** Generation token so stale search success/failure cannot mutate a newer source/query. */
    private var onlineSearchGeneration: Long = 0L
    private var onlinePreviewGeneration: Long = 0L
    private var allShelfItems: List<LibraryItem> = emptyList()
    private var libraryFilter: LibraryFilter = LibraryFilter.ALL
    private var clearDeleteFailureOnNextShelfEmission = false

    companion object {
        /** Peak concurrent online-library batch downloads. */
        const val ONLINE_BATCH_DOWNLOAD_CONCURRENCY = 3
    }

    init {
        viewModelScope.launch {
            repository.observeShelf()
                .catch { e -> _uiState.value = LibraryUiState(error = e.message ?: "加载失败") }
                .collect { items ->
                    allShelfItems = items
                    publishShelf()
                }
        }
        sourceRegistry?.let { registry ->
            viewModelScope.launch {
                registry.observeSources().collect { sources ->
                    val currentSelection = _onlineLibraryState.value.selectedSourceId
                    val resolvedSelection = currentSelection
                        ?.takeIf { id -> sources.any { it.id == id } }
                        ?: sources.firstOrNull { it.enabled && it.baseUrl.isNotBlank() }?.id
                        ?: sources.firstOrNull()?.id
                    val sourceChanged = resolvedSelection != currentSelection
                    if (sourceChanged) {
                        onlineSearchJob?.cancel()
                        onlineSearchJob = null
                        onlineSearchGeneration += 1L
                        onlinePreviewJob?.cancel()
                        onlinePreviewJob = null
                        onlinePreviewGeneration += 1L
                    }
                    _onlineLibraryState.update { state ->
                        state.copy(
                            sources = sources,
                            selectedSourceId = resolvedSelection,
                            isSearching = if (sourceChanged) false else state.isSearching,
                            results = if (sourceChanged) emptyList() else state.results,
                            selectedEntryKeys = if (sourceChanged) emptySet() else state.selectedEntryKeys,
                            downloadingKeys = if (sourceChanged) emptySet() else state.downloadingKeys,
                            previewUrl = if (sourceChanged) null else state.previewUrl,
                            error = if (sourceChanged) null else state.error,
                            message = if (sourceChanged) null else state.message,
                        )
                    }
                }
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
            deleteBooks(listOf(id), mode)
        }
    }

    fun deleteBundle(collectionId: String, mode: BookRemovalMode) {
        viewModelScope.launch {
            val bookIds = allShelfItems
                .filterIsInstance<LibraryItem.Bundle>()
                .firstOrNull { it.bundle.id == collectionId }
                ?.bundle
                ?.books
                ?.map(BookMeta::id)
                .orEmpty()
            deleteBooks(bookIds, mode)
        }
    }

    private suspend fun deleteBooks(ids: List<String>, mode: BookRemovalMode) {
        runCatching {
            ids.forEach { id ->
                assetOperations.delete(id) {
                    when (mode) {
                        BookRemovalMode.REMOVE_FROM_SHELF -> repository.deleteBook(id)
                        BookRemovalMode.DELETE_ALL -> repository.deleteBookCompletely(id)
                    }
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

    fun clearNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    fun removeDownloadedAsset(id: String) {
        viewModelScope.launch {
            val removed = assetOperations.delete(id) { repository.removeDownloadedAsset(id) }
            val message = if (removed) "已移除本地下载" else "未找到可移除的下载"
            _onlineLibraryState.update { it.copy(message = message, error = null) }
            _calibreSearchState.value = _calibreSearchState.value.copy(
                message = message,
                error = null,
            )
        }
    }

    fun renameBook(id: String, title: String) {
        viewModelScope.launch { repository.renameBook(id, title) }
    }

    fun reorder(
        items: List<LibraryItem>,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val completeItems = mergeVisibleShelfOrder(allShelfItems, items)
            if (completeItems == null) {
                _uiState.value = _uiState.value.copy(
                    notice = "排序失败：书架状态已变化，请重试",
                )
                onComplete(false)
                return@launch
            }
            val ids = completeItems.flatMap { item ->
                when (item) {
                    is LibraryItem.Single -> listOf(item.book.id)
                    is LibraryItem.Bundle -> item.bundle.books.map { it.id }
                }
            }
            runCatching { repository.updateShelfOrder(ids) }
                .onSuccess { onComplete(true) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(
                        notice = "排序失败：${error.message ?: "请稍后重试"}",
                    )
                    onComplete(false)
                }
        }
    }

    fun moveToGroup(
        bookId: String,
        targetCollectionId: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { repository.moveToGroup(bookId, targetCollectionId) }
                .onSuccess { onComplete(true) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(
                        notice = "移动到书组失败：${error.message ?: "请稍后重试"}",
                    )
                    onComplete(false)
                }
        }
    }

    fun createGroup(sourceId: String, targetId: String, name: String) {
        viewModelScope.launch {
            runCatching { repository.createGroup(sourceId, targetId, name) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(
                        notice = "建组失败：${error.message ?: "请稍后重试"}",
                    )
                }
        }
    }

    fun removeFromGroup(bookId: String) {
        viewModelScope.launch { repository.setCollection(bookId, null, null) }
    }

    fun ungroupBundle(
        collectionId: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { repository.ungroupBundle(collectionId) }
                .onSuccess { onComplete(true) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(
                        notice = "拆组失败：${error.message ?: "请稍后重试"}",
                    )
                    onComplete(false)
                }
        }
    }

    fun renameBundle(
        collectionId: String,
        newName: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { repository.renameBundle(collectionId, newName) }
                .onSuccess { onComplete(true) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(
                        notice = "书组改名失败：${error.message ?: "请稍后重试"}",
                    )
                    onComplete(false)
                }
        }
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        libraryFilter = filter
        publishShelf()
    }

    // region Online library / multi-source

    fun selectOnlineSource(sourceId: String) {
        // Cancel in-flight search so a slow previous source cannot leave isSearching=true
        // or apply stale success/failure onto the newly selected source.
        onlineSearchJob?.cancel()
        onlineSearchJob = null
        onlineSearchGeneration += 1L
        onlinePreviewJob?.cancel()
        onlinePreviewJob = null
        onlinePreviewGeneration += 1L
        _onlineLibraryState.update {
            it.copy(
                selectedSourceId = sourceId,
                isSearching = false,
                results = emptyList(),
                selectedEntryKeys = emptySet(),
                downloadingKeys = emptySet(),
                previewUrl = null,
                error = null,
                message = null,
            )
        }
        // Keep calibre projection in sync when switching away or clearing mid-search.
        _calibreSearchState.value = _calibreSearchState.value.copy(
            isSearching = false,
            downloadingBookId = null,
            results = if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                _calibreSearchState.value.results
            } else {
                emptyList()
            },
            error = null,
            message = null,
        )
    }

    fun updateOnlineQuery(query: String) {
        _onlineLibraryState.update { it.copy(query = query, error = null, message = null) }
        _calibreSearchState.value = _calibreSearchState.value.copy(query = query, error = null, message = null)
    }

    fun updateOnlineFilter(filter: OnlineCatalogFilter) {
        _onlineLibraryState.update { it.copy(filter = filter, error = null, message = null) }
    }

    fun searchOnlineLibrary() {
        val registry = sourceRegistry
        if (registry == null) {
            searchCalibre()
            return
        }
        onlineSearchJob?.cancel()
        val generation = ++onlineSearchGeneration
        onlineSearchJob = viewModelScope.launch {
            val state = _onlineLibraryState.value
            val sourceId = state.selectedSourceId
            if (sourceId.isNullOrBlank()) {
                if (generation == onlineSearchGeneration) {
                    _onlineLibraryState.update { it.copy(error = "请选择书源", message = null, isSearching = false) }
                }
                return@launch
            }
            val query = state.query
            val filter = state.filter
            _onlineLibraryState.update {
                it.copy(isSearching = true, error = null, message = null, selectedEntryKeys = emptySet())
            }
            if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                _calibreSearchState.value = _calibreSearchState.value.copy(
                    isSearching = true,
                    error = null,
                    message = null,
                )
            }
            try {
                when (val opened = registry.openCatalog(sourceId)) {
                    is ReadflowResult.Failure -> {
                        publishSearchFailure(sourceId, query, opened.error.message, generation)
                    }
                    is ReadflowResult.Success -> {
                        val catalog = opened.value
                        try {
                            when (val result = catalog.search(query, filter)) {
                                is ReadflowResult.Success ->
                                    publishSearchSuccess(sourceId, query, result.value, generation)
                                is ReadflowResult.Failure ->
                                    publishSearchFailure(sourceId, query, result.error.message, generation)
                            }
                        } finally {
                            catalog.close()
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Source switch / newer search cancelled us; do not leave spinner stuck if we are still current.
                if (generation == onlineSearchGeneration) {
                    _onlineLibraryState.update { it.copy(isSearching = false) }
                    if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                        _calibreSearchState.value = _calibreSearchState.value.copy(isSearching = false)
                    }
                }
                throw e
            }
        }
    }

    fun toggleOnlineSelection(entry: OnlineCatalogEntry) {
        val key = entry.selectionKey()
        _onlineLibraryState.update { state ->
            val next = state.selectedEntryKeys.toMutableSet()
            if (!next.add(key)) next.remove(key)
            state.copy(selectedEntryKeys = next)
        }
    }

    fun clearOnlineSelection() {
        _onlineLibraryState.update { it.copy(selectedEntryKeys = emptySet()) }
    }

    fun selectOnlineByAuthor(author: String) {
        val keys = _onlineLibraryState.value.results
            .filter { it.meta.author.equals(author, ignoreCase = true) }
            .map { it.selectionKey() }
            .toSet()
        _onlineLibraryState.update { it.copy(selectedEntryKeys = keys) }
    }

    fun selectOnlineBySeries(series: String) {
        val keys = _onlineLibraryState.value.results
            .filter { it.series?.equals(series, ignoreCase = true) == true }
            .map { it.selectionKey() }
            .toSet()
        _onlineLibraryState.update { it.copy(selectedEntryKeys = keys) }
    }

    fun downloadSelectedOnlineBooks() {
        val selected = _onlineLibraryState.value.selectedEntryKeys
        val entries = _onlineLibraryState.value.results.filter { it.selectionKey() in selected }
        if (entries.isEmpty()) {
            _onlineLibraryState.update { it.copy(error = "请先选择要下载的书籍", message = null) }
            return
        }
        val registry = sourceRegistry
        if (registry == null) {
            entries.forEach { downloadCalibreBook(it.meta.id) }
            return
        }
        val sourceId = _onlineLibraryState.value.selectedSourceId ?: return
        // Cap peak concurrency; each selected entry downloads exactly once.
        viewModelScope.launch {
            val successCount = AtomicInteger(0)
            val failureCount = AtomicInteger(0)
            val lastError = AtomicReference<String?>(null)
            val semaphore = Semaphore(ONLINE_BATCH_DOWNLOAD_CONCURRENCY)
            coroutineScope {
                entries.map { entry ->
                    async {
                        semaphore.withPermit {
                            when (val outcome = downloadOnlineEntryInternal(entry, sourceId, registry, clearMessages = false)) {
                                is OnlineDownloadOutcome.Success -> successCount.incrementAndGet()
                                is OnlineDownloadOutcome.Failure -> {
                                    failureCount.incrementAndGet()
                                    lastError.set(outcome.message)
                                }
                                OnlineDownloadOutcome.Skipped -> Unit
                            }
                        }
                    }
                }.awaitAll()
            }
            val ok = successCount.get()
            val fail = failureCount.get()
            val summary = when {
                fail == 0 && ok > 0 -> "已下载 $ok 本"
                ok == 0 && fail > 0 -> "批量下载失败：$fail 本${lastError.get()?.let { "（$it）" } ?: ""}"
                ok > 0 && fail > 0 -> "已下载 $ok 本，$fail 本失败${lastError.get()?.let { "（$it）" } ?: ""}"
                else -> null
            }
            if (summary != null) {
                _onlineLibraryState.update {
                    it.copy(
                        message = if (fail == 0) summary else null,
                        error = if (fail > 0) summary else null,
                    )
                }
            }
        }
    }

    fun downloadOnlineEntry(entry: OnlineCatalogEntry) {
        val registry = sourceRegistry
        if (registry == null) {
            downloadCalibreBook(entry.meta.id.removePrefix("calibre-").ifBlank { entry.meta.id })
            return
        }
        val sourceId = _onlineLibraryState.value.selectedSourceId ?: return
        viewModelScope.launch {
            downloadOnlineEntryInternal(entry, sourceId, registry, clearMessages = true)
        }
    }

    private suspend fun downloadOnlineEntryInternal(
        entry: OnlineCatalogEntry,
        sourceId: String,
        registry: SourceRegistry,
        clearMessages: Boolean,
    ): OnlineDownloadOutcome {
        val key = entry.selectionKey()
        return assetOperations.produce(bookId = shelfBookIdFor(entry, sourceId)) {
            _onlineLibraryState.update {
                it.copy(
                    downloadingKeys = it.downloadingKeys + key,
                    error = if (clearMessages) null else it.error,
                    message = if (clearMessages) null else it.message,
                )
            }
            if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                _calibreSearchState.value = _calibreSearchState.value.copy(
                    downloadingBookId = entry.meta.id,
                    error = if (clearMessages) null else _calibreSearchState.value.error,
                    message = if (clearMessages) null else _calibreSearchState.value.message,
                )
            }
            try {
                when (val opened = registry.openCatalog(sourceId)) {
                    is ReadflowResult.Failure -> {
                        publishDownloadFailure(entry, sourceId, opened.error.message, publishUiMessage = clearMessages)
                        OnlineDownloadOutcome.Failure(opened.error.message)
                    }
                    is ReadflowResult.Success -> {
                        val catalog = opened.value
                        try {
                            when (val result = catalog.download(entry)) {
                                is ReadflowResult.Success -> {
                                    repository.upsertBook(result.value)
                                    publishDownloadSuccess(entry, sourceId, result.value, publishUiMessage = clearMessages)
                                    OnlineDownloadOutcome.Success
                                }
                                is ReadflowResult.Failure -> {
                                    publishDownloadFailure(entry, sourceId, result.error.message, publishUiMessage = clearMessages)
                                    OnlineDownloadOutcome.Failure(result.error.message)
                                }
                            }
                        } finally {
                            catalog.close()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: "下载失败"
                publishDownloadFailure(entry, sourceId, message, publishUiMessage = clearMessages)
                OnlineDownloadOutcome.Failure(message)
            } finally {
                clearDownloadProgress(entry, sourceId)
            }
        }
    }

    private sealed class OnlineDownloadOutcome {
        data object Success : OnlineDownloadOutcome()
        data class Failure(val message: String) : OnlineDownloadOutcome()
        data object Skipped : OnlineDownloadOutcome()
    }

    fun previewOnlineEntry(entry: OnlineCatalogEntry) {
        val registry = sourceRegistry ?: return
        val sourceId = _onlineLibraryState.value.selectedSourceId ?: return
        onlinePreviewJob?.cancel()
        val generation = ++onlinePreviewGeneration
        onlinePreviewJob = viewModelScope.launch {
            try {
                when (val opened = registry.openCatalog(sourceId)) {
                    is ReadflowResult.Failure -> if (isCurrentPreview(sourceId, generation)) {
                        _onlineLibraryState.update {
                            it.copy(error = opened.error.message, previewUrl = null)
                        }
                    }
                    is ReadflowResult.Success -> {
                        val catalog = opened.value
                        try {
                            when (val result = catalog.previewUrl(entry)) {
                                is ReadflowResult.Success -> if (isCurrentPreview(sourceId, generation)) {
                                    _onlineLibraryState.update {
                                        it.copy(
                                            previewUrl = result.value,
                                            error = null,
                                            message = "预览地址已验证",
                                        )
                                    }
                                }
                                is ReadflowResult.Failure -> if (isCurrentPreview(sourceId, generation)) {
                                    _onlineLibraryState.update {
                                        it.copy(error = result.error.message, previewUrl = null)
                                    }
                                }
                            }
                        } finally {
                            catalog.close()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrentPreview(sourceId, generation)) {
                    _onlineLibraryState.update {
                        it.copy(error = error.message ?: "预览失败", previewUrl = null)
                    }
                }
            }
        }
    }

    private fun isCurrentPreview(sourceId: String, generation: Long): Boolean =
        generation == onlinePreviewGeneration &&
            _onlineLibraryState.value.selectedSourceId == sourceId

    fun clearOnlinePreview() {
        _onlineLibraryState.update { it.copy(previewUrl = null) }
    }

    fun updateAddSourceForm(name: String? = null, url: String? = null, kind: SourceKind? = null) {
        _onlineLibraryState.update { state ->
            state.copy(
                addSourceName = name ?: state.addSourceName,
                addSourceUrl = url ?: state.addSourceUrl,
                addSourceKind = kind ?: state.addSourceKind,
            )
        }
    }

    fun addOnlineSource() {
        val registry = sourceRegistry ?: return
        viewModelScope.launch {
            val state = _onlineLibraryState.value
            when (
                val result = registry.addUserSource(
                    kind = state.addSourceKind,
                    name = state.addSourceName,
                    baseUrl = state.addSourceUrl,
                )
            ) {
                is ReadflowResult.Success -> _onlineLibraryState.update {
                    it.copy(
                        message = "已添加书源「${result.value.name}」",
                        error = null,
                        addSourceName = "",
                        addSourceUrl = "",
                        selectedSourceId = result.value.id,
                    )
                }
                is ReadflowResult.Failure -> _onlineLibraryState.update {
                    it.copy(error = result.error.message, message = null)
                }
            }
        }
    }

    fun removeOnlineSource(sourceId: String) {
        val registry = sourceRegistry ?: return
        viewModelScope.launch {
            when (val result = registry.removeUserSource(sourceId)) {
                is ReadflowResult.Success -> _onlineLibraryState.update {
                    it.copy(message = "已删除书源", error = null)
                }
                is ReadflowResult.Failure -> _onlineLibraryState.update {
                    it.copy(error = result.error.message, message = null)
                }
            }
        }
    }

    // endregion

    fun updateCalibreQuery(query: String) = updateOnlineQuery(query)

    fun searchCalibre() {
        calibreSearchJob?.cancel()
        calibreSearchJob = viewModelScope.launch {
            val baseUrl = settings.calibreBaseUrl.first()
            if (baseUrl.isNullOrBlank()) {
                _calibreSearchState.value = _calibreSearchState.value.copy(
                    error = "请先在设置中连接 Calibre",
                    message = null,
                )
                _onlineLibraryState.update {
                    it.copy(error = "请先在设置中连接 Calibre", message = null, isSearching = false)
                }
                return@launch
            }
            _calibreSearchState.value = _calibreSearchState.value.copy(
                isSearching = true,
                error = null,
                message = null,
            )
            _onlineLibraryState.update {
                it.copy(
                    isSearching = true,
                    error = null,
                    message = null,
                    selectedSourceId = it.selectedSourceId ?: BUILTIN_CALIBRE_SOURCE_ID,
                )
            }
            val calibreRepo = runCatching { calibreRepositoryFactory(baseUrl) }
                .getOrElse { error ->
                    val msg = "Calibre: ${error.message ?: "服务器地址无效"}"
                    _calibreSearchState.value = _calibreSearchState.value.copy(
                        isSearching = false,
                        error = msg,
                    )
                    _onlineLibraryState.update { it.copy(isSearching = false, error = msg) }
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
                    val entries = result.value.map { OnlineCatalogEntry(meta = it) }
                    _calibreSearchState.value = _calibreSearchState.value.copy(
                        isSearching = false,
                        results = result.value,
                        message = if (result.value.isEmpty()) "没有找到匹配的 Calibre 书籍" else null,
                    )
                    if (_onlineLibraryState.value.query == query) {
                        _onlineLibraryState.update {
                            it.copy(
                                isSearching = false,
                                results = entries,
                                message = if (entries.isEmpty()) "没有找到匹配的书籍" else null,
                            )
                        }
                    }
                }
                is ReadflowResult.Failure -> if (_calibreSearchState.value.query == query) {
                    val msg = "Calibre: ${result.error.message}"
                    _calibreSearchState.value = _calibreSearchState.value.copy(
                        isSearching = false,
                        error = msg,
                    )
                    _onlineLibraryState.update { it.copy(isSearching = false, error = msg) }
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
                    _onlineLibraryState.update { it.copy(error = "请先在设置中连接 Calibre") }
                    return@produce
                }
                _calibreSearchState.value = _calibreSearchState.value.copy(
                    downloadingBookId = bookId,
                    error = null,
                    message = null,
                )
                _onlineLibraryState.update {
                    it.copy(downloadingKeys = it.downloadingKeys + bookId, error = null, message = null)
                }
                val calibreRepo = runCatching { calibreRepositoryFactory(baseUrl) }
                    .getOrElse { error ->
                        val msg = "Calibre: ${error.message ?: "服务器地址无效"}"
                        _calibreSearchState.value = _calibreSearchState.value.copy(
                            downloadingBookId = null,
                            error = msg,
                        )
                        _onlineLibraryState.update {
                            it.copy(downloadingKeys = it.downloadingKeys - bookId, error = msg)
                        }
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
                        _onlineLibraryState.update {
                            it.copy(
                                downloadingKeys = it.downloadingKeys - bookId,
                                message = "已下载《${result.value.title}》",
                            )
                        }
                    }
                    is ReadflowResult.Failure -> {
                        val msg = "Calibre: ${result.error.message}"
                        _calibreSearchState.value = _calibreSearchState.value.copy(
                            downloadingBookId = null,
                            error = msg,
                        )
                        _onlineLibraryState.update {
                            it.copy(downloadingKeys = it.downloadingKeys - bookId, error = msg)
                        }
                    }
                }
            }
        }
    }

    private fun publishSearchSuccess(
        sourceId: String,
        query: String,
        entries: List<OnlineCatalogEntry>,
        generation: Long,
    ) {
        if (generation != onlineSearchGeneration) return
        if (_onlineLibraryState.value.query != query) return
        if (_onlineLibraryState.value.selectedSourceId != sourceId) return
        _onlineLibraryState.update {
            it.copy(
                isSearching = false,
                results = entries,
                message = if (entries.isEmpty()) "没有找到匹配的书籍" else null,
                error = null,
            )
        }
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID && _calibreSearchState.value.query == query) {
            _calibreSearchState.value = _calibreSearchState.value.copy(
                isSearching = false,
                results = entries.map { it.meta },
                message = if (entries.isEmpty()) "没有找到匹配的 Calibre 书籍" else null,
                error = null,
            )
        }
    }

    private fun publishSearchFailure(
        sourceId: String,
        query: String,
        message: String,
        generation: Long,
    ) {
        // latest-wins: ignore stale failure/success from a cancelled or superseded search.
        if (generation != onlineSearchGeneration) return
        if (_onlineLibraryState.value.selectedSourceId != sourceId) return
        if (_onlineLibraryState.value.query != query) return
        _onlineLibraryState.update {
            it.copy(isSearching = false, error = message, message = null)
        }
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID && _calibreSearchState.value.query == query) {
            _calibreSearchState.value = _calibreSearchState.value.copy(
                isSearching = false,
                error = if (message.startsWith("Calibre:")) message else "Calibre: $message",
            )
        }
    }

    private fun publishDownloadSuccess(
        entry: OnlineCatalogEntry,
        sourceId: String,
        downloaded: BookMeta,
        publishUiMessage: Boolean = true,
    ) {
        val key = entry.selectionKey()
        val message = "已下载《${downloaded.title}》"
        _onlineLibraryState.update {
            it.copy(
                downloadingKeys = it.downloadingKeys - key,
                message = if (publishUiMessage) message else it.message,
                error = if (publishUiMessage) null else it.error,
                selectedEntryKeys = it.selectedEntryKeys - key,
            )
        }
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
            _calibreSearchState.value = _calibreSearchState.value.copy(
                downloadingBookId = null,
                message = if (publishUiMessage) message else _calibreSearchState.value.message,
            )
        }
    }

    private fun publishDownloadFailure(
        entry: OnlineCatalogEntry,
        sourceId: String,
        message: String,
        publishUiMessage: Boolean = true,
    ) {
        val key = entry.selectionKey()
        _onlineLibraryState.update {
            it.copy(
                downloadingKeys = it.downloadingKeys - key,
                error = if (publishUiMessage) message else it.error,
                message = if (publishUiMessage) null else it.message,
            )
        }
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
            _calibreSearchState.value = _calibreSearchState.value.copy(
                downloadingBookId = null,
                error = if (publishUiMessage) {
                    if (message.startsWith("Calibre:")) message else "Calibre: $message"
                } else {
                    _calibreSearchState.value.error
                },
            )
        }
    }

    private fun clearDownloadProgress(entry: OnlineCatalogEntry, sourceId: String) {
        val key = entry.selectionKey()
        _onlineLibraryState.update {
            it.copy(downloadingKeys = it.downloadingKeys - key)
        }
        if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
            _calibreSearchState.value = _calibreSearchState.value.copy(downloadingBookId = null)
        }
    }

    /**
     * Coordinator bookId must match the id written by the catalog download/upsert path.
     * Non-Calibre adapters use [stableRemoteBookId]; if the entry already carries a `remote-` id
     * (set at parse time), reuse it so coordination and file naming stay identical.
     */
    private fun shelfBookIdFor(entry: OnlineCatalogEntry, sourceId: String): String =
        when (sourceId) {
            BUILTIN_CALIBRE_SOURCE_ID -> {
                val raw = entry.meta.id
                if (raw.startsWith("calibre-")) raw else "calibre-$raw"
            }
            else -> {
                val id = entry.meta.id
                if (id.startsWith("remote-")) id else stableRemoteBookId(sourceId, id)
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

fun OnlineCatalogEntry.selectionKey(): String =
    meta.id + "|" + (series.orEmpty()) + "|" + meta.title

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
    is LibraryItem.Bundle -> "bundle:${bundle.id}"
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
