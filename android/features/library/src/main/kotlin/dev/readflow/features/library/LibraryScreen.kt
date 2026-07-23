package dev.readflow.features.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.BookBundle
import dev.readflow.core.ui.BookCover
import dev.readflow.core.ui.BookGrid
import dev.readflow.core.ui.EmptyState
import dev.readflow.core.ui.PaperSurface
import dev.readflow.core.ui.ReadflowType
import dev.readflow.core.ui.Dimens
import org.koin.androidx.compose.koinViewModel

private val SUPPORTED_MIMES = arrayOf("text/plain", "application/epub+zip", "application/pdf")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val calibreSearchState by viewModel.calibreSearchState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.clearNotice()
    }
    val context = LocalContext.current

    var openedBundle by remember { mutableStateOf<BookBundle?>(null) }
    var showOnlineLibrary by remember { mutableStateOf(false) }
    val onlineLibraryState by viewModel.onlineLibraryState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.openBook.collect { bookId -> onOpenBook(bookId) }
    }
    LaunchedEffect(Unit) {
        viewModel.openBundle.collect { bundle -> openedBundle = bundle }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBook(it) }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.scanFolder(context, it) }
    }

    var showAddMenu by remember { mutableStateOf(false) }
    var showShelfMenu by remember { mutableStateOf(false) }
    val visibleBookCount = state.items.sumOf { item ->
        when (item) {
            is dev.readflow.core.model.LibraryItem.Single -> 1
            is dev.readflow.core.model.LibraryItem.Bundle -> item.bundle.books.size
        }
    }
    val shelfSummary = when {
        state.filter == LibraryFilter.OFFLINE -> "$visibleBookCount 本离线可读"
        visibleBookCount == 0 -> "尚未添加书籍"
        state.offlineCount == 0 -> "共 $visibleBookCount 本"
        else -> "共 $visibleBookCount 本 · ${state.offlineCount} 本可离线"
    }
    val activeFilterLabel = when (state.filter) {
        LibraryFilter.ALL -> "全部书籍"
        LibraryFilter.OFFLINE -> "离线可读"
    }

    PaperSurface {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    val fiberColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
                    val edgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .drawBehind {
                                val fiberStep = 12.dp.toPx()
                                val fiberStroke = 0.5.dp.toPx()
                                var y = fiberStep / 2f
                                while (y < size.height) {
                                    drawLine(
                                        color = fiberColor,
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = fiberStroke,
                                    )
                                    y += fiberStep
                                }
                                drawLine(
                                    color = edgeColor,
                                    start = Offset(0f, size.height - fiberStroke / 2f),
                                    end = Offset(size.width, size.height - fiberStroke / 2f),
                                    strokeWidth = fiberStroke,
                                )
                            }
                            .padding(start = 20.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "书架",
                                    style = ReadflowType.title,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = shelfSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm)) {
                                Box {
                                    IconButton(
                                        onClick = { showAddMenu = true },
                                        modifier = Modifier.size(Dimens.touchTarget),
                                    ) {
                                        Icon(Icons.Outlined.Add, contentDescription = "导入书籍")
                                    }
                                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("选择文件") },
                                            onClick = { showAddMenu = false; fileLauncher.launch(SUPPORTED_MIMES) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("扫描文件夹") },
                                            onClick = { showAddMenu = false; folderLauncher.launch(null) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("在线书库") },
                                            onClick = { showAddMenu = false; showOnlineLibrary = true },
                                        )
                                    }
                                }
                                Box {
                                    IconButton(
                                        onClick = { showShelfMenu = true },
                                        modifier = Modifier
                                            .size(Dimens.touchTarget)
                                            .semantics {
                                                contentDescription = "书架筛选，当前$activeFilterLabel"
                                            },
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = null)
                                    }
                                    DropdownMenu(
                                        expanded = showShelfMenu,
                                        onDismissRequest = { showShelfMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("全部书籍") },
                                            onClick = {
                                                showShelfMenu = false
                                                viewModel.setLibraryFilter(LibraryFilter.ALL)
                                            },
                                            modifier = Modifier.semantics {
                                                if (state.filter == LibraryFilter.ALL) {
                                                    stateDescription = "当前筛选"
                                                }
                                            },
                                            trailingIcon = {
                                                if (state.filter == LibraryFilter.ALL) {
                                                    Text("当前", style = MaterialTheme.typography.labelSmall)
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("离线可读 (${state.offlineCount})") },
                                            onClick = {
                                                showShelfMenu = false
                                                viewModel.setLibraryFilter(LibraryFilter.OFFLINE)
                                            },
                                            modifier = Modifier.semantics {
                                                if (state.filter == LibraryFilter.OFFLINE) {
                                                    stateDescription = "当前筛选"
                                                }
                                            },
                                            trailingIcon = {
                                                if (state.filter == LibraryFilter.OFFLINE) {
                                                    Text("当前", style = MaterialTheme.typography.labelSmall)
                                                }
                                            },
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = onSettings,
                                    modifier = Modifier.size(Dimens.touchTarget),
                                ) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "设置")
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
                    state.error != null && state.items.isEmpty() ->
                        Text("加载失败：${state.error}", color = MaterialTheme.colorScheme.onBackground)
                    state.items.isEmpty() && state.filter == LibraryFilter.ALL -> EmptyState(
                        onConnectCalibre = onSettings,
                        onImportLocal = { fileLauncher.launch(SUPPORTED_MIMES) },
                    )
                    else -> Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (state.items.isEmpty()) {
                            OfflineEmptyState(
                                onShowAll = { viewModel.setLibraryFilter(LibraryFilter.ALL) },
                            )
                        } else {
                            BookGrid(
                                items = state.items,
                                onItemClick = viewModel::onItemClick,
                                onDelete = viewModel::deleteBook,
                                onDeleteBundle = viewModel::deleteBundle,
                                onRename = viewModel::renameBook,
                                onMoveToGroup = viewModel::moveToGroup,
                                onCreateGroup = viewModel::createGroup,
                                onReorder = viewModel::reorder,
                                onUngroup = viewModel::ungroupBundle,
                                onRenameBundle = viewModel::renameBundle,
                                onRemoveDownload = viewModel::removeDownloadedAsset,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // 扫描进度 dialog（不可外部关闭）
        if (scanProgress?.scanning == true) {
            ImportProgressDialog(
                found = scanProgress!!.found,
                onCancel = viewModel::cancelScan,
            )
        }

        // 扫描完成 → 展示预览 sheet
        if (scanProgress?.scanning == false && scanProgress!!.books.isNotEmpty()) {
            ImportPreviewSheet(
                books = scanProgress!!.books,
                onImport = { viewModel.importFromFolder(it) },
                onDismiss = viewModel::clearScan,
            )
        }

        // 分组详情 sheet
        openedBundle?.let { bundle ->
            BundleDetailSheet(
                bundle = bundle,
                onOpenBook = onOpenBook,
                onDismiss = { openedBundle = null },
            )
        }

        if (showOnlineLibrary) {
            OnlineLibrarySheet(
                state = onlineLibraryState,
                calibreCompat = calibreSearchState,
                onSelectSource = viewModel::selectOnlineSource,
                onQueryChange = viewModel::updateOnlineQuery,
                onFilterChange = viewModel::updateOnlineFilter,
                onSearch = viewModel::searchOnlineLibrary,
                onToggleSelect = viewModel::toggleOnlineSelection,
                onSelectAuthor = viewModel::selectOnlineByAuthor,
                onSelectSeries = viewModel::selectOnlineBySeries,
                onDownloadEntry = viewModel::downloadOnlineEntry,
                onDownloadSelected = viewModel::downloadSelectedOnlineBooks,
                onPreview = viewModel::previewOnlineEntry,
                onClearPreview = viewModel::clearOnlinePreview,
                onAddSourceForm = viewModel::updateAddSourceForm,
                onAddSource = viewModel::addOnlineSource,
                onRemoveSource = viewModel::removeOnlineSource,
                onDismiss = { showOnlineLibrary = false },
            )
        }
    }
}

@Composable
private fun OfflineEmptyState(
    onShowAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "没有离线可读的书",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "下载 Calibre 书籍或导入本地文件后，会出现在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onShowAll) {
            Text("查看全部")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineLibrarySheet(
    state: OnlineLibraryUiState,
    calibreCompat: CalibreSearchUiState,
    onSelectSource: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (dev.readflow.extensions.api.OnlineCatalogFilter) -> Unit,
    onSearch: () -> Unit,
    onToggleSelect: (dev.readflow.extensions.api.OnlineCatalogEntry) -> Unit,
    onSelectAuthor: (String) -> Unit,
    onSelectSeries: (String) -> Unit,
    onDownloadEntry: (dev.readflow.extensions.api.OnlineCatalogEntry) -> Unit,
    onDownloadSelected: () -> Unit,
    onPreview: (dev.readflow.extensions.api.OnlineCatalogEntry) -> Unit,
    onClearPreview: () -> Unit,
    onAddSourceForm: (name: String?, url: String?, kind: dev.readflow.extensions.api.SourceKind?) -> Unit,
    onAddSource: () -> Unit,
    onRemoveSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val selected = state.sources.firstOrNull { it.id == state.selectedSourceId }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("在线书库", style = MaterialTheme.typography.titleMedium)
            if (selected?.kind == dev.readflow.extensions.api.SourceKind.CALIBRE) {
                Text("Calibre 书源", style = MaterialTheme.typography.titleSmall)
            }

            // Source picker
            Text("书源", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sources.forEach { source ->
                    val selectedSource = source.id == state.selectedSourceId
                    OutlinedButton(
                        onClick = { onSelectSource(source.id) },
                        enabled = source.enabled || source.baseUrl.isNotBlank() || source.isBuiltin,
                    ) {
                        Text(
                            text = if (selectedSource) "✓ ${source.name}" else source.name,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text("搜索书名或作者") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onSearch,
                    enabled = !state.isSearching,
                ) {
                    Text("搜索")
                }
            }

            // Result filters
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.filter.author,
                    onValueChange = { onFilterChange(state.filter.copy(author = it)) },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.filter.series,
                    onValueChange = { onFilterChange(state.filter.copy(series = it)) },
                    label = { Text("系列") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.filter.format,
                    onValueChange = { onFilterChange(state.filter.copy(format = it)) },
                    label = { Text("格式") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.filter.tag,
                    onValueChange = { onFilterChange(state.filter.copy(tag = it)) },
                    label = { Text("标签") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            val statusError = state.error ?: calibreCompat.error
            val statusMessage = state.message ?: calibreCompat.message
            when {
                state.isSearching || calibreCompat.isSearching -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (selected?.kind == dev.readflow.extensions.api.SourceKind.CALIBRE) "正在搜索 Calibre" else "正在搜索书源")
                }
                statusError != null -> Text(
                    text = statusError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                statusMessage != null -> Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.selectedEntryKeys.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已选 ${state.selectedEntryKeys.size} 本")
                    Button(onClick = onDownloadSelected) { Text("批量下载") }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.results, key = { it.selectionKey() }) { entry ->
                    OnlineCatalogResultRow(
                        entry = entry,
                        selected = entry.selectionKey() in state.selectedEntryKeys,
                        isDownloading = entry.selectionKey() in state.downloadingKeys ||
                            calibreCompat.downloadingBookId == entry.meta.id,
                        onToggleSelect = { onToggleSelect(entry) },
                        onDownload = { onDownloadEntry(entry) },
                        onPreview = { onPreview(entry) },
                        onAuthorBatch = { onSelectAuthor(entry.meta.author) },
                        onSeriesBatch = { entry.series?.let(onSelectSeries) },
                    )
                }
            }

            val previewUrl = state.previewUrl
            if (previewUrl != null) {
                Text(
                    text = "预览：$previewUrl",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl))
                            runCatching { context.startActivity(intent) }
                        },
                    ) {
                        Text("打开预览")
                    }
                    TextButton(onClick = onClearPreview) { Text("清除预览") }
                }
            }

            // Add generic user source (OPDS / JSON HTTP)
            Text("添加书源（OPDS / JSON）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onAddSourceForm(null, null, dev.readflow.extensions.api.SourceKind.JSON_HTTP)
                    },
                ) { Text(if (state.addSourceKind == dev.readflow.extensions.api.SourceKind.JSON_HTTP) "✓ JSON" else "JSON") }
                OutlinedButton(
                    onClick = {
                        onAddSourceForm(null, null, dev.readflow.extensions.api.SourceKind.OPDS)
                    },
                ) { Text(if (state.addSourceKind == dev.readflow.extensions.api.SourceKind.OPDS) "✓ OPDS" else "OPDS") }
            }
            OutlinedTextField(
                value = state.addSourceName,
                onValueChange = { onAddSourceForm(it, null, null) },
                label = { Text("书源名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.addSourceUrl,
                onValueChange = { onAddSourceForm(null, it, null) },
                label = { Text("书源地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddSource) { Text("保存书源") }
                selected?.takeIf { !it.isBuiltin }?.let { userSource ->
                    OutlinedButton(onClick = { onRemoveSource(userSource.id) }) {
                        Text("删除当前书源")
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun OnlineCatalogResultRow(
    entry: dev.readflow.extensions.api.OnlineCatalogEntry,
    selected: Boolean,
    isDownloading: Boolean,
    onToggleSelect: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onAuthorBatch: () -> Unit,
    onSeriesBatch: () -> Unit,
) {
    val book = entry.meta
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onToggleSelect) {
            Text(if (selected) "已选" else "选择")
        }
        BookCover(
            book = book,
            showProgress = false,
            modifier = Modifier
                .width(44.dp)
                .height(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(book.author)
                    append(" · ")
                    append(book.format.name)
                    entry.series?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAuthorBatch) { Text("同作者") }
                if (!entry.series.isNullOrBlank()) {
                    TextButton(onClick = onSeriesBatch) { Text("同系列") }
                }
                TextButton(onClick = onPreview) { Text("预览") }
            }
        }
        OutlinedButton(
            onClick = onDownload,
            enabled = !isDownloading,
        ) {
            Text(if (isDownloading) "下载中" else "下载")
        }
    }
}
