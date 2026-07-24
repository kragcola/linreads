package dev.readflow.features.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.calibre.calibreSourceConfigJson
import dev.readflow.core.calibre.htmlRulesV1ConfigJson
import dev.readflow.core.calibre.httpCatalogSourceConfigJson
import dev.readflow.core.calibre.readBoundedSourceConfigBytes
import dev.readflow.core.calibre.HtmlChapterRules
import dev.readflow.core.calibre.HtmlDetailRules
import dev.readflow.core.calibre.HtmlRulesV1Config
import dev.readflow.core.calibre.HtmlSearchRules
import dev.readflow.core.database.LibraryStore
import dev.readflow.core.model.BookBundle
import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.BookRemovalMode
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.UncoordinatedBookAssetOperations
import dev.readflow.extensions.api.FolderScanner
import dev.readflow.extensions.api.LocalBookImporter
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineBookPreview
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.ScannedBook
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceCredentials
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceRegistry
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.stableRemoteBookId
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.net.URI

enum class LibraryFilter { ALL, OFFLINE }

data class LibraryUiState(
    val isLoading: Boolean = false,
    val items: List<LibraryItem> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val offlineCount: Int = 0,
)

/** Online multi-source library search UI state. */
data class OnlineLibraryUiState(
    val sources: List<SourceDescriptor> = emptyList(),
    val selectedSourceId: String? = null,
    val query: String = "",
    val filter: OnlineCatalogFilter = OnlineCatalogFilter(),
    val isSearching: Boolean = false,
    val isSelectingBatch: Boolean = false,
    val isAddingSource: Boolean = false,
    val results: List<OnlineCatalogEntry> = emptyList(),
    val selectedEntryKeys: Set<String> = emptySet(),
    val downloadingKeys: Set<String> = emptySet(),
    val preview: OnlineBookPreview? = null,
    val message: String? = null,
    val error: String? = null,
    val addSourceName: String = "",
    val addSourceUrl: String = "",
    val addSourceAdapterId: String = SourceAdapterIds.HTML_RULES_V1,
    val htmlSourceDraft: HtmlSourceDraft = HtmlSourceDraft(),
    val metadataFacets: OnlineMetadataFacets = OnlineMetadataFacets(),
    val editingSourceId: String? = null,
    val sourceUsername: String = "",
    val sourcePassword: String = "",
    val isLoadingSourceCredentials: Boolean = false,
    val sourceCredentialsLoadFailed: Boolean = false,
) {
    val allCurrentResultsSelected: Boolean
        get() = results.isNotEmpty() && results.all { it.selectionKey() in selectedEntryKeys }
}

data class MetadataFacet(val value: String, val count: Int)

data class OnlineMetadataFacets(
    val authors: List<MetadataFacet> = emptyList(),
    val series: List<MetadataFacet> = emptyList(),
    val formats: List<MetadataFacet> = emptyList(),
    val tags: List<MetadataFacet> = emptyList(),
)

internal fun buildOnlineMetadataFacets(entries: List<OnlineCatalogEntry>): OnlineMetadataFacets =
    OnlineMetadataFacets(
        authors = buildMetadataFacet(entries.map(OnlineCatalogEntry::individualAuthors)),
        series = buildMetadataFacet(entries.map { listOfNotNull(it.series) }),
        formats = buildMetadataFacet(
            entries.map { entry ->
                entry.availableFormats.ifEmpty { listOf(entry.meta.format.name) }
            },
        ),
        tags = buildMetadataFacet(entries.map(OnlineCatalogEntry::tags)),
    )

internal fun OnlineCatalogEntry.individualAuthors(): List<String> =
    authors
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
        .ifEmpty { listOf(meta.author.trim()).filter(String::isNotBlank) }

private fun buildMetadataFacet(valuesByBook: List<List<String>>): List<MetadataFacet> {
    data class Counter(var displayValue: String, var count: Int)

    val counters = mutableMapOf<String, Counter>()
    valuesByBook.forEach { rawValues ->
        rawValues
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .forEach { value ->
                val key = value.lowercase()
                val counter = counters[key]
                if (counter == null) {
                    counters[key] = Counter(value, 1)
                } else {
                    counter.count += 1
                    if (value < counter.displayValue) counter.displayValue = value
                }
            }
    }
    return counters.values
        .map { MetadataFacet(it.displayValue, it.count) }
        .sortedWith(
            compareByDescending<MetadataFacet> { it.count }
                .thenBy { it.value.lowercase() }
                .thenBy(MetadataFacet::value),
        )
}

data class HtmlSourceDraft(
    val searchUrlTemplate: String = "",
    val additionalAllowedHosts: String = "",
    val allowLanHttp: Boolean = false,
    val charset: String = "UTF-8",
    val itemSelector: String = ".bookbox, .book-item, li",
    val titleSelector: String = "h3 a, .bookname a, .title",
    val authorSelector: String = ".author, .writer",
    val detailLinkSelector: String = "h3 a, .bookname a, a",
    val seriesSelector: String = "",
    val chapterItemSelector: String = ".listmain dd, .chapter-list li, dd",
    val chapterLinkSelector: String = "a",
    val chapterTitleSelector: String = "",
    val bodySelector: String = "#chaptercontent, #content, .content",
    val nextPageSelector: String = "",
) {
    internal fun toConfig(): HtmlRulesV1Config {
        val renderedSearchUrl = searchUrlTemplate
            .replace("{query}", "query")
            .replace("{page}", "1")
        val primaryHost = runCatching { URI(renderedSearchUrl).host }
            .getOrNull()
            ?.trim()
            ?.lowercase()
            .orEmpty()
        val additionalHosts = additionalAllowedHosts
            .split(Regex("[,\\s]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(String::lowercase)
        return HtmlRulesV1Config(
            searchUrlTemplate = searchUrlTemplate.trim(),
            allowedHosts = (listOf(primaryHost) + additionalHosts).filter(String::isNotBlank).distinct(),
            allowLanHttp = allowLanHttp,
            charset = charset.trim().ifBlank { "UTF-8" },
            search = HtmlSearchRules(
                itemSelector = itemSelector.trim(),
                titleSelector = titleSelector.trim(),
                authorSelector = authorSelector.trim(),
                detailLinkSelector = detailLinkSelector.trim(),
                seriesSelector = seriesSelector.trim().ifBlank { null },
            ),
            detail = HtmlDetailRules(
                chapterItemSelector = chapterItemSelector.trim(),
                chapterLinkSelector = chapterLinkSelector.trim(),
            ),
            chapter = HtmlChapterRules(
                titleSelector = chapterTitleSelector.trim().ifBlank { null },
                bodySelector = bodySelector.trim(),
                nextPageSelector = nextPageSelector.trim().ifBlank { null },
            ),
        )
    }
}

data class ScanProgress(
    val found: Int = 0,
    val scanning: Boolean = true,
    val books: List<ScannedBook> = emptyList(),
)

class LibraryViewModel(
    private val repository: LibraryStore,
    private val localSource: LocalBookImporter,
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

    private var scanJob: Job? = null
    private var onlineSearchJob: Job? = null
    private var onlinePreviewJob: Job? = null
    private var onlineBatchSelectionJob: Job? = null
    /** Generation token so stale search success/failure cannot mutate a newer source/query. */
    private var onlineSearchGeneration: Long = 0L
    private var onlinePreviewGeneration: Long = 0L
    private var onlineBatchSelectionGeneration: Long = 0L
    private var allShelfItems: List<LibraryItem> = emptyList()
    private var libraryFilter: LibraryFilter = LibraryFilter.ALL
    private var clearDeleteFailureOnNextShelfEmission = false

    companion object {
        /** Peak concurrent online-library batch downloads. */
        const val ONLINE_BATCH_DOWNLOAD_CONCURRENCY = 3
        internal const val ONLINE_BATCH_PAGE_SIZE = 100
        internal const val ONLINE_BATCH_MAX_PAGES = 10
        internal const val ONLINE_BATCH_MAX_ITEMS = ONLINE_BATCH_PAGE_SIZE * ONLINE_BATCH_MAX_PAGES
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
                        ?: sources.firstOrNull {
                            it.enabled && it.baseUrl.isNotBlank() && it.adapterId != SourceAdapterIds.CALIBRE
                        }?.id
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
                        onlineBatchSelectionJob?.cancel()
                        onlineBatchSelectionJob = null
                        onlineBatchSelectionGeneration += 1L
                    }
                    _onlineLibraryState.update { state ->
                        state.copy(
                            sources = sources,
                            selectedSourceId = resolvedSelection,
                            filter = if (sourceChanged) OnlineCatalogFilter() else state.filter,
                            isSearching = if (sourceChanged) false else state.isSearching,
                            isSelectingBatch = if (sourceChanged) false else state.isSelectingBatch,
                            results = if (sourceChanged) emptyList() else state.results,
                            metadataFacets = if (sourceChanged) OnlineMetadataFacets() else state.metadataFacets,
                            selectedEntryKeys = if (sourceChanged) emptySet() else state.selectedEntryKeys,
                            downloadingKeys = if (sourceChanged) emptySet() else state.downloadingKeys,
                            preview = if (sourceChanged) null else state.preview,
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
        onlineBatchSelectionJob?.cancel()
        onlineBatchSelectionJob = null
        onlineBatchSelectionGeneration += 1L
        _onlineLibraryState.update {
            it.copy(
                selectedSourceId = sourceId,
                filter = OnlineCatalogFilter(),
                isSearching = false,
                isSelectingBatch = false,
                results = emptyList(),
                metadataFacets = OnlineMetadataFacets(),
                selectedEntryKeys = emptySet(),
                downloadingKeys = emptySet(),
                preview = null,
                error = null,
                message = null,
            )
        }
    }

    fun updateOnlineQuery(query: String) {
        _onlineLibraryState.update { it.copy(query = query, error = null, message = null) }
    }

    fun updateOnlineFilter(filter: OnlineCatalogFilter) {
        _onlineLibraryState.update { it.copy(filter = filter, error = null, message = null) }
    }

    fun searchOnlineLibrary() {
        val registry = sourceRegistry
        if (registry == null) {
            _onlineLibraryState.update { it.copy(error = "书源服务未配置", isSearching = false) }
            return
        }
        onlineSearchJob?.cancel()
        onlineBatchSelectionJob?.cancel()
        onlineBatchSelectionJob = null
        onlineBatchSelectionGeneration += 1L
        _onlineLibraryState.update { it.copy(isSelectingBatch = false) }
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

    fun toggleAllCurrentOnlineResults() {
        _onlineLibraryState.update { state ->
            val currentKeys = state.results.mapTo(linkedSetOf(), OnlineCatalogEntry::selectionKey)
            val nextSelection = if (currentKeys.isNotEmpty() && currentKeys.all(state.selectedEntryKeys::contains)) {
                state.selectedEntryKeys - currentKeys
            } else {
                state.selectedEntryKeys + currentKeys
            }
            state.copy(selectedEntryKeys = nextSelection, error = null, message = null)
        }
    }

    fun applyOnlineFacet(filter: OnlineCatalogFilter) {
        updateOnlineFilter(filter)
        searchOnlineLibrary()
    }

    fun selectOnlineByAuthor(author: String) {
        selectOnlineBatch(
            query = author,
            filter = OnlineCatalogFilter(author = author),
            matches = { entry ->
                entry.individualAuthors().any { it.equals(author, ignoreCase = true) }
            },
        )
    }

    fun selectOnlineBySeries(series: String) {
        selectOnlineBatch(
            query = series,
            filter = OnlineCatalogFilter(series = series),
            matches = { it.series?.equals(series, ignoreCase = true) == true },
        )
    }

    private fun selectOnlineBatch(
        query: String,
        filter: OnlineCatalogFilter,
        matches: (OnlineCatalogEntry) -> Boolean,
    ) {
        val snapshot = _onlineLibraryState.value
        val sourceId = snapshot.selectedSourceId ?: return
        val source = snapshot.sources.firstOrNull { it.id == sourceId } ?: return
        if (!source.capabilities.canBatchAcrossSource) {
            val keys = snapshot.results.filter(matches).mapTo(linkedSetOf(), OnlineCatalogEntry::selectionKey)
            _onlineLibraryState.update {
                it.copy(
                    selectedEntryKeys = keys,
                    message = if (keys.isEmpty()) "当前结果中没有匹配的书籍" else "已选择当前结果中的 ${keys.size} 本",
                    error = null,
                )
            }
            return
        }
        val registry = sourceRegistry ?: return
        onlineBatchSelectionJob?.cancel()
        val generation = ++onlineBatchSelectionGeneration
        onlineBatchSelectionJob = viewModelScope.launch {
            _onlineLibraryState.update {
                it.copy(isSelectingBatch = true, error = null, message = null)
            }
            try {
                when (val opened = registry.openCatalog(sourceId)) {
                    is ReadflowResult.Failure -> publishBatchSelectionFailure(sourceId, generation, opened.error.message)
                    is ReadflowResult.Success -> {
                        val catalog = opened.value
                        try {
                            val discovered = linkedMapOf<String, OnlineCatalogEntry>()
                            val seenEntryKeys = mutableSetOf<String>()
                            var offset = 0
                            var pageCount = 0
                            while (pageCount < ONLINE_BATCH_MAX_PAGES && discovered.size < ONLINE_BATCH_MAX_ITEMS) {
                                when (
                                    val page = catalog.search(
                                        query = query,
                                        filter = filter,
                                        offset = offset,
                                        limit = ONLINE_BATCH_PAGE_SIZE,
                                    )
                                ) {
                                    is ReadflowResult.Failure -> {
                                        publishBatchSelectionFailure(sourceId, generation, page.error.message)
                                        return@launch
                                    }
                                    is ReadflowResult.Success -> {
                                        if (page.value.isEmpty()) break
                                        var sawNewEntry = false
                                        page.value.forEach { entry ->
                                            val key = entry.selectionKey()
                                            if (seenEntryKeys.add(key)) {
                                                sawNewEntry = true
                                                if (matches(entry)) discovered.putIfAbsent(key, entry)
                                            }
                                        }
                                        if (!sawNewEntry) break
                                        offset += ONLINE_BATCH_PAGE_SIZE
                                        pageCount += 1
                                    }
                                }
                            }
                            if (isCurrentBatchSelection(sourceId, generation)) {
                                val entries = discovered.values.take(ONLINE_BATCH_MAX_ITEMS)
                                _onlineLibraryState.update { state ->
                                    val merged = (state.results + entries).distinctBy(OnlineCatalogEntry::selectionKey)
                                    state.copy(
                                        isSelectingBatch = false,
                                        results = merged,
                                        metadataFacets = buildOnlineMetadataFacets(merged),
                                        selectedEntryKeys = entries.mapTo(linkedSetOf(), OnlineCatalogEntry::selectionKey),
                                        message = if (entries.isEmpty()) "没有找到匹配的书籍" else "已选择 ${entries.size} 本",
                                        error = null,
                                    )
                                }
                            }
                        } finally {
                            catalog.close()
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (isCurrentBatchSelection(sourceId, generation)) {
                    _onlineLibraryState.update { it.copy(isSelectingBatch = false) }
                }
                throw error
            } catch (error: Throwable) {
                publishBatchSelectionFailure(sourceId, generation, error.message ?: "批量选择失败")
            }
        }
    }

    private fun publishBatchSelectionFailure(sourceId: String, generation: Long, message: String) {
        if (!isCurrentBatchSelection(sourceId, generation)) return
        _onlineLibraryState.update {
            it.copy(isSelectingBatch = false, error = message, message = null)
        }
    }

    private fun isCurrentBatchSelection(sourceId: String, generation: Long): Boolean =
        generation == onlineBatchSelectionGeneration &&
            _onlineLibraryState.value.selectedSourceId == sourceId

    fun downloadSelectedOnlineBooks() {
        val selected = _onlineLibraryState.value.selectedEntryKeys
        val entries = _onlineLibraryState.value.results.filter { it.selectionKey() in selected }
        if (entries.isEmpty()) {
            _onlineLibraryState.update { it.copy(error = "请先选择要下载的书籍", message = null) }
            return
        }
        val registry = sourceRegistry
        if (registry == null) {
            _onlineLibraryState.update { it.copy(error = "书源服务未配置") }
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
                    if (it.selectedSourceId != sourceId) it else {
                        it.copy(
                            message = if (fail == 0) summary else null,
                            error = if (fail > 0) summary else null,
                        )
                    }
                }
            }
        }
    }

    fun downloadOnlineEntry(entry: OnlineCatalogEntry) {
        val registry = sourceRegistry
        if (registry == null) {
            _onlineLibraryState.update { it.copy(error = "书源服务未配置") }
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
                if (it.selectedSourceId != sourceId) it else {
                    it.copy(
                        downloadingKeys = it.downloadingKeys + key,
                        error = if (clearMessages) null else it.error,
                        message = if (clearMessages) null else it.message,
                    )
                }
            }
            try {
                when (val opened = registry.openCatalog(sourceId)) {
                    is ReadflowResult.Failure -> {
                        publishDownloadFailure(sourceId, entry, opened.error.message, publishUiMessage = clearMessages)
                        OnlineDownloadOutcome.Failure(opened.error.message)
                    }
                    is ReadflowResult.Success -> {
                        val catalog = opened.value
                        try {
                            when (val result = catalog.download(entry)) {
                                is ReadflowResult.Success -> {
                                    repository.upsertBook(result.value)
                                    publishDownloadSuccess(
                                        sourceId,
                                        entry,
                                        result.value,
                                        publishUiMessage = clearMessages,
                                    )
                                    OnlineDownloadOutcome.Success
                                }
                                is ReadflowResult.Failure -> {
                                    publishDownloadFailure(
                                        sourceId,
                                        entry,
                                        result.error.message,
                                        publishUiMessage = clearMessages,
                                    )
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
                publishDownloadFailure(sourceId, entry, message, publishUiMessage = clearMessages)
                OnlineDownloadOutcome.Failure(message)
            } finally {
                clearDownloadProgress(sourceId, entry)
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
        val state = _onlineLibraryState.value
        val sourceId = state.selectedSourceId ?: return
        val source = state.sources.firstOrNull { it.id == sourceId } ?: return
        if (!source.capabilities.canPreviewText) {
            _onlineLibraryState.update {
                it.copy(error = "该书源不支持应用内正文预览", preview = null)
            }
            return
        }
        onlinePreviewJob?.cancel()
        val generation = ++onlinePreviewGeneration
        onlinePreviewJob = viewModelScope.launch {
            try {
                when (val opened = registry.openCatalog(sourceId)) {
                    is ReadflowResult.Failure -> if (isCurrentPreview(sourceId, generation)) {
                        _onlineLibraryState.update {
                            it.copy(error = opened.error.message, preview = null)
                        }
                    }
                    is ReadflowResult.Success -> {
                        val catalog = opened.value
                        try {
                            when (val result = catalog.preview(entry)) {
                                is ReadflowResult.Success -> if (isCurrentPreview(sourceId, generation)) {
                                    _onlineLibraryState.update {
                                        it.copy(
                                            preview = result.value,
                                            error = null,
                                            message = null,
                                        )
                                    }
                                }
                                is ReadflowResult.Failure -> if (isCurrentPreview(sourceId, generation)) {
                                    _onlineLibraryState.update {
                                        it.copy(error = result.error.message, preview = null)
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
                        it.copy(error = error.message ?: "预览失败", preview = null)
                    }
                }
            }
        }
    }

    private fun isCurrentPreview(sourceId: String, generation: Long): Boolean =
        generation == onlinePreviewGeneration &&
            _onlineLibraryState.value.selectedSourceId == sourceId

    fun clearOnlinePreview() {
        _onlineLibraryState.update { it.copy(preview = null) }
    }

    fun updateAddSourceForm(
        name: String? = null,
        url: String? = null,
        adapterId: String? = null,
    ) {
        _onlineLibraryState.update { state ->
            state.copy(
                addSourceName = name ?: state.addSourceName,
                addSourceUrl = url ?: state.addSourceUrl,
                addSourceAdapterId = adapterId ?: state.addSourceAdapterId,
            )
        }
    }

    fun updateSourceCredentials(username: String, password: String) {
        _onlineLibraryState.update {
            it.copy(sourceUsername = username, sourcePassword = password, error = null, message = null)
        }
    }

    fun prepareSourceEditor(sourceId: String? = null) {
        val registry = sourceRegistry
        if (sourceId == null) {
            _onlineLibraryState.update {
                it.copy(
                    editingSourceId = null,
                    addSourceName = "",
                    addSourceUrl = "",
                    addSourceAdapterId = SourceAdapterIds.HTML_RULES_V1,
                    htmlSourceDraft = HtmlSourceDraft(),
                    sourceUsername = "",
                    sourcePassword = "",
                    isLoadingSourceCredentials = false,
                    sourceCredentialsLoadFailed = false,
                    error = null,
                    message = null,
                )
            }
            return
        }
        val source = _onlineLibraryState.value.sources.firstOrNull { it.id == sourceId } ?: return
        _onlineLibraryState.update {
            it.copy(
                editingSourceId = source.id,
                addSourceName = source.name,
                addSourceUrl = source.baseUrl,
                addSourceAdapterId = source.adapterId,
                sourceUsername = "",
                sourcePassword = "",
                isLoadingSourceCredentials = source.adapterId == SourceAdapterIds.CALIBRE,
                sourceCredentialsLoadFailed = false,
                error = null,
                message = null,
            )
        }
        if (source.adapterId != SourceAdapterIds.CALIBRE || registry == null) return
        viewModelScope.launch {
            try {
                val credentials = registry.sourceCredentials(source.id)
                _onlineLibraryState.update { state ->
                    if (state.editingSourceId != source.id) state else {
                        state.copy(
                            sourceUsername = credentials?.username.orEmpty(),
                            sourcePassword = credentials?.password.orEmpty(),
                            isLoadingSourceCredentials = false,
                            sourceCredentialsLoadFailed = false,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _onlineLibraryState.update { state ->
                    if (state.editingSourceId != source.id) state else {
                        state.copy(
                            isLoadingSourceCredentials = false,
                            sourceCredentialsLoadFailed = true,
                            error = "无法读取已保存的 Calibre 凭据，请重试或重置登录凭据",
                        )
                    }
                }
            }
        }
    }

    fun resetSourceCredentials() {
        val registry = sourceRegistry ?: return
        val sourceId = _onlineLibraryState.value.editingSourceId ?: return
        viewModelScope.launch {
            when (val result = registry.clearSourceCredentials(sourceId)) {
                is ReadflowResult.Success -> _onlineLibraryState.update { state ->
                    if (state.editingSourceId != sourceId) state else {
                        state.copy(
                            sourceUsername = "",
                            sourcePassword = "",
                            sourceCredentialsLoadFailed = false,
                            error = null,
                            message = "已重置登录凭据",
                        )
                    }
                }
                is ReadflowResult.Failure -> _onlineLibraryState.update { state ->
                    if (state.editingSourceId != sourceId) state else {
                        state.copy(error = result.error.message, message = null)
                    }
                }
            }
        }
    }

    fun clearSourceEditor() {
        _onlineLibraryState.update {
            it.copy(
                editingSourceId = null,
                addSourceName = "",
                addSourceUrl = "",
                addSourceAdapterId = SourceAdapterIds.HTML_RULES_V1,
                htmlSourceDraft = HtmlSourceDraft(),
                sourceUsername = "",
                sourcePassword = "",
                isLoadingSourceCredentials = false,
                sourceCredentialsLoadFailed = false,
                error = null,
            )
        }
    }

    fun updateHtmlSourceDraft(draft: HtmlSourceDraft) {
        _onlineLibraryState.update { it.copy(htmlSourceDraft = draft, error = null, message = null) }
    }

    fun addOnlineSource(onSuccess: () -> Unit = {}) = saveOnlineSource(onSuccess)

    fun saveOnlineSource(onSuccess: () -> Unit = {}) {
        val registry = sourceRegistry ?: run {
            _onlineLibraryState.update { it.copy(error = "书源服务未配置", message = null) }
            return
        }
        if (_onlineLibraryState.value.isAddingSource) return
        val editorState = _onlineLibraryState.value
        if (editorState.sourceCredentialsLoadFailed) {
            _onlineLibraryState.update {
                it.copy(error = "请先重试或重置登录凭据", message = null)
            }
            return
        }
        if (
            editorState.addSourceAdapterId == SourceAdapterIds.CALIBRE &&
            editorState.sourceUsername.isBlank() &&
            editorState.sourcePassword.isNotEmpty()
        ) {
            _onlineLibraryState.update { it.copy(error = "请填写用户名，或清空密码", message = null) }
            return
        }
        _onlineLibraryState.update { it.copy(isAddingSource = true, error = null, message = null) }
        viewModelScope.launch {
            val state = _onlineLibraryState.value
            val configJson = runCatching {
                when (state.addSourceAdapterId) {
                    SourceAdapterIds.CALIBRE -> calibreSourceConfigJson(state.addSourceUrl)
                    SourceAdapterIds.OPDS, SourceAdapterIds.JSON_HTTP -> httpCatalogSourceConfigJson(state.addSourceUrl)
                    SourceAdapterIds.HTML_RULES_V1 -> htmlRulesV1ConfigJson(state.htmlSourceDraft.toConfig())
                    else -> error("未安装书源适配器：${state.addSourceAdapterId}")
                }
            }.getOrElse { error ->
                _onlineLibraryState.update {
                    it.copy(
                        isAddingSource = false,
                        error = error.message ?: "书源配置无效",
                        message = null,
                    )
                }
                return@launch
            }
            val result = try {
                val name = state.addSourceName.trim().ifBlank {
                    defaultSourceName(state.addSourceAdapterId)
                }
                val credentials = if (state.addSourceAdapterId == SourceAdapterIds.CALIBRE) {
                    SourceCredentials(state.sourceUsername.trim(), state.sourcePassword)
                } else {
                    null
                }
                state.editingSourceId?.let { sourceId ->
                    registry.updateUserSource(
                        sourceId = sourceId,
                        name = name,
                        configVersion = 1,
                        configJson = configJson,
                        credentials = credentials,
                    )
                } ?: registry.addUserSource(
                    adapterId = state.addSourceAdapterId,
                    name = name,
                    configVersion = 1,
                    configJson = configJson,
                    credentials = credentials,
                )
            } catch (error: CancellationException) {
                _onlineLibraryState.update { it.copy(isAddingSource = false) }
                throw error
            } catch (error: Throwable) {
                _onlineLibraryState.update {
                    it.copy(
                        isAddingSource = false,
                        error = error.message ?: "添加书源失败",
                        message = null,
                    )
                }
                return@launch
            }
            when (result) {
                is ReadflowResult.Success -> {
                    val wasEditing = state.editingSourceId != null
                    _onlineLibraryState.update {
                        it.copy(
                            message = if (wasEditing) {
                                "已更新书源「${result.value.name}」"
                            } else {
                                "已添加书源「${result.value.name}」"
                            },
                            error = null,
                            isAddingSource = false,
                            addSourceName = "",
                            addSourceUrl = "",
                            htmlSourceDraft = HtmlSourceDraft(),
                            editingSourceId = null,
                            sourceUsername = "",
                            sourcePassword = "",
                            isLoadingSourceCredentials = false,
                            sourceCredentialsLoadFailed = false,
                            selectedSourceId = result.value.id,
                            filter = OnlineCatalogFilter(),
                            results = emptyList(),
                            metadataFacets = OnlineMetadataFacets(),
                            selectedEntryKeys = emptySet(),
                        )
                    }
                    onSuccess()
                }
                is ReadflowResult.Failure -> _onlineLibraryState.update {
                    it.copy(isAddingSource = false, error = result.error.message, message = null)
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

    /**
     * Import a versioned source-configuration JSON via document picker.
     * UI performs a bounded stream read; registry validates the JSON string only.
     * Never executes file contents as code.
     */
    fun importSourceConfigFromUri(context: Context, uri: Uri) {
        val registry = sourceRegistry ?: run {
            _onlineLibraryState.update { it.copy(error = "书源服务未配置", message = null) }
            return
        }
        if (_onlineLibraryState.value.isAddingSource) return
        _onlineLibraryState.update { it.copy(isAddingSource = true, error = null, message = null) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: return@withContext ReadflowResult.Failure(
                            dev.readflow.core.model.ReadflowError.io("无法打开配置文件"),
                        )
                    input.use { stream ->
                        val raw = when (val read = readBoundedSourceConfigBytes(stream)) {
                            is ReadflowResult.Failure -> return@use read
                            is ReadflowResult.Success -> read.value
                        }
                        registry.importUserSourceConfig(raw)
                    }
                }
            } catch (error: CancellationException) {
                _onlineLibraryState.update { it.copy(isAddingSource = false) }
                throw error
            } catch (error: Throwable) {
                _onlineLibraryState.update {
                    it.copy(
                        isAddingSource = false,
                        error = error.message ?: "导入书源配置失败",
                        message = null,
                    )
                }
                return@launch
            }
            applySourceConfigImportResult(result)
        }
    }

    /** Imports already-bounded JSON and applies the same selection/error state as document import. */
    internal fun importSourceConfigText(rawJson: String) {
        val registry = sourceRegistry ?: run {
            _onlineLibraryState.update { it.copy(error = "书源服务未配置", message = null) }
            return
        }
        if (_onlineLibraryState.value.isAddingSource) return
        _onlineLibraryState.update { it.copy(isAddingSource = true, error = null, message = null) }
        viewModelScope.launch {
            // Already-read JSON string: stay on viewModelScope (Main under tests) so
            // StandardTestDispatcher.advanceUntilIdle observes registry + selection updates.
            // URI import keeps Dispatchers.IO for ContentResolver stream I/O.
            val result = try {
                registry.importUserSourceConfig(rawJson)
            } catch (error: CancellationException) {
                _onlineLibraryState.update { it.copy(isAddingSource = false) }
                throw error
            } catch (error: Throwable) {
                _onlineLibraryState.update {
                    it.copy(
                        isAddingSource = false,
                        error = error.message ?: "导入书源配置失败",
                        message = null,
                    )
                }
                return@launch
            }
            applySourceConfigImportResult(result)
        }
    }

    private fun applySourceConfigImportResult(result: ReadflowResult<SourceDescriptor>) {
        when (result) {
            is ReadflowResult.Success -> _onlineLibraryState.update {
                it.copy(
                    isAddingSource = false,
                    message = "已导入书源「${result.value.name}」",
                    error = null,
                    selectedSourceId = result.value.id,
                    filter = OnlineCatalogFilter(),
                    results = emptyList(),
                    metadataFacets = OnlineMetadataFacets(),
                    selectedEntryKeys = emptySet(),
                )
            }
            is ReadflowResult.Failure -> _onlineLibraryState.update {
                it.copy(isAddingSource = false, error = result.error.message, message = null)
            }
        }
    }

    // endregion

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
                metadataFacets = buildOnlineMetadataFacets(entries),
                message = if (entries.isEmpty()) "没有找到匹配的书籍" else null,
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
    }

    private fun publishDownloadSuccess(
        sourceId: String,
        entry: OnlineCatalogEntry,
        downloaded: BookMeta,
        publishUiMessage: Boolean = true,
    ) {
        val key = entry.selectionKey()
        val message = "已下载《${downloaded.title}》"
        _onlineLibraryState.update {
            if (it.selectedSourceId != sourceId) it else {
                it.copy(
                    downloadingKeys = it.downloadingKeys - key,
                    message = if (publishUiMessage) message else it.message,
                    error = if (publishUiMessage) null else it.error,
                    selectedEntryKeys = it.selectedEntryKeys - key,
                )
            }
        }
    }

    private fun publishDownloadFailure(
        sourceId: String,
        entry: OnlineCatalogEntry,
        message: String,
        publishUiMessage: Boolean = true,
    ) {
        val key = entry.selectionKey()
        _onlineLibraryState.update {
            if (it.selectedSourceId != sourceId) it else {
                it.copy(
                    downloadingKeys = it.downloadingKeys - key,
                    error = if (publishUiMessage) message else it.error,
                    message = if (publishUiMessage) null else it.message,
                )
            }
        }
    }

    private fun clearDownloadProgress(sourceId: String, entry: OnlineCatalogEntry) {
        val key = entry.selectionKey()
        _onlineLibraryState.update {
            if (it.selectedSourceId != sourceId) it else {
                it.copy(downloadingKeys = it.downloadingKeys - key)
            }
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

internal fun defaultSourceName(adapterId: String): String = when (adapterId) {
    SourceAdapterIds.HTML_RULES_V1 -> "网页小说站"
    SourceAdapterIds.OPDS -> "OPDS 书库"
    SourceAdapterIds.JSON_HTTP -> "JSON 目录"
    SourceAdapterIds.CALIBRE -> "Calibre"
    else -> "在线书源"
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
