package dev.readflow.features.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.clearNotice()
    }
    val context = LocalContext.current

    var openedBundle by remember { mutableStateOf<BookBundle?>(null) }
    var showOnlineLibrary by remember { mutableStateOf(false) }
    var showSourceEditor by remember { mutableStateOf(false) }
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
                        onOpenOnlineLibrary = { showOnlineLibrary = true },
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
                onOpenSourceEditor = { showSourceEditor = true },
                onRemoveSource = viewModel::removeOnlineSource,
                onDismiss = {
                    showOnlineLibrary = false
                    showSourceEditor = false
                    viewModel.clearOnlinePreview()
                },
            )
        }
        onlineLibraryState.preview?.let { preview ->
            OnlineBookPreviewWindow(
                preview = preview,
                onDismiss = viewModel::clearOnlinePreview,
            )
        }
        if (showSourceEditor) {
            SourceEditorWindow(
                state = onlineLibraryState,
                onFormChange = viewModel::updateAddSourceForm,
                onHtmlDraftChange = viewModel::updateHtmlSourceDraft,
                onSave = {
                    viewModel.addOnlineSource { showSourceEditor = false }
                },
                onDismiss = { showSourceEditor = false },
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
            text = "从在线书库下载或导入本地文件后，会出现在这里。",
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
    onOpenSourceEditor: () -> Unit,
    onRemoveSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
            selected?.let { Text(it.name, style = MaterialTheme.typography.titleSmall) }

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
                        enabled = source.enabled,
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

            if (selected?.capabilities?.canFilterByAuthor == true) {
                OutlinedTextField(
                    value = state.filter.author,
                    onValueChange = { onFilterChange(state.filter.copy(author = it)) },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selected?.capabilities?.canFilterBySeries == true) {
                OutlinedTextField(
                    value = state.filter.series,
                    onValueChange = { onFilterChange(state.filter.copy(series = it)) },
                    label = { Text("系列") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selected?.capabilities?.canFilterByFormat == true) {
                OutlinedTextField(
                    value = state.filter.format,
                    onValueChange = { onFilterChange(state.filter.copy(format = it)) },
                    label = { Text("格式") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selected?.capabilities?.canFilterByTag == true) {
                OutlinedTextField(
                    value = state.filter.tag,
                    onValueChange = { onFilterChange(state.filter.copy(tag = it)) },
                    label = { Text("标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val statusError = state.error
            val statusMessage = state.message
            when {
                state.isSearching || state.isSelectingBatch -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.isSelectingBatch) "正在汇总匹配书籍"
                        else "正在搜索${selected?.name?.let { "「$it」" }.orEmpty()}",
                    )
                }
                statusError != null -> Text(
                    text = statusError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "在线书库错误：$statusError"
                    },
                )
                statusMessage != null -> Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.selectedEntryKeys.isNotEmpty() && selected?.capabilities?.canDownload == true) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已选 ${state.selectedEntryKeys.size} 本")
                    Button(onClick = onDownloadSelected) { Text("下载所选") }
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
                        isDownloading = entry.selectionKey() in state.downloadingKeys,
                        canDownload = selected?.capabilities?.canDownload == true,
                        canPreview = selected?.capabilities?.canPreviewText == true,
                        canBatchAcrossSource = selected?.capabilities?.canBatchAcrossSource == true,
                        onToggleSelect = { onToggleSelect(entry) },
                        onDownload = { onDownloadEntry(entry) },
                        onPreview = { onPreview(entry) },
                        onAuthorBatch = { onSelectAuthor(entry.meta.author) },
                        onSeriesBatch = { entry.series?.let(onSelectSeries) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSourceEditor) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加书源")
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineBookPreviewWindow(
    preview: dev.readflow.extensions.api.OnlineBookPreview,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = preview.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "退出正文预览",
                                )
                            }
                        },
                    )
                },
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        top = 16.dp,
                        end = 20.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "meta") {
                        Text(
                            text = listOfNotNull(preview.author, preview.chapterTitle).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item(key = "body") {
                        Text(
                            text = preview.body,
                            fontSize = 16.sp,
                            lineHeight = 27.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceEditorWindow(
    state: OnlineLibraryUiState,
    onFormChange: (name: String?, url: String?, adapterId: String?) -> Unit,
    onHtmlDraftChange: (HtmlSourceDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val adapterOptions = listOf(
        dev.readflow.extensions.api.SourceAdapterIds.HTML_RULES_V1 to "网页书源",
        dev.readflow.extensions.api.SourceAdapterIds.OPDS to "OPDS",
        dev.readflow.extensions.api.SourceAdapterIds.JSON_HTTP to "JSON",
        dev.readflow.extensions.api.SourceAdapterIds.CALIBRE to "Calibre",
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("添加书源") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回在线书库",
                                )
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = onSave,
                                enabled = !state.isAddingSource,
                            ) {
                                Text(if (state.isAddingSource) "保存中" else "保存")
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("书源类型", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        adapterOptions.forEach { (adapterId, label) ->
                            FilterChip(
                                selected = state.addSourceAdapterId == adapterId,
                                onClick = { onFormChange(null, null, adapterId) },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.addSourceName,
                        onValueChange = { onFormChange(it, null, null) },
                        label = { Text("书源名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.addSourceAdapterId == dev.readflow.extensions.api.SourceAdapterIds.HTML_RULES_V1) {
                        HtmlSourceRuleFields(
                            draft = state.htmlSourceDraft,
                            onChange = onHtmlDraftChange,
                        )
                    } else {
                        val addressLabel = when (state.addSourceAdapterId) {
                            dev.readflow.extensions.api.SourceAdapterIds.CALIBRE -> "Calibre 服务器地址"
                            dev.readflow.extensions.api.SourceAdapterIds.OPDS -> "OPDS 地址"
                            else -> "JSON 目录地址"
                        }
                        OutlinedTextField(
                            value = state.addSourceUrl,
                            onValueChange = { onFormChange(null, it, null) },
                            label = { Text(addressLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    state.error?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun HtmlSourceRuleFields(
    draft: HtmlSourceDraft,
    onChange: (HtmlSourceDraft) -> Unit,
) {
    Text("搜索页规则", style = MaterialTheme.typography.labelLarge)
    SourceRuleField(
        value = draft.searchUrlTemplate,
        label = "搜索地址模板",
        onValueChange = { onChange(draft.copy(searchUrlTemplate = it)) },
    )
    SourceRuleField(
        value = draft.itemSelector,
        label = "结果条目选择器",
        onValueChange = { onChange(draft.copy(itemSelector = it)) },
    )
    SourceRuleField(
        value = draft.titleSelector,
        label = "书名选择器",
        onValueChange = { onChange(draft.copy(titleSelector = it)) },
    )
    SourceRuleField(
        value = draft.authorSelector,
        label = "作者选择器",
        onValueChange = { onChange(draft.copy(authorSelector = it)) },
    )
    SourceRuleField(
        value = draft.detailLinkSelector,
        label = "详情链接选择器",
        onValueChange = { onChange(draft.copy(detailLinkSelector = it)) },
    )
    SourceRuleField(
        value = draft.seriesSelector,
        label = "系列选择器（可选）",
        onValueChange = { onChange(draft.copy(seriesSelector = it)) },
    )
    Text("目录与正文规则", style = MaterialTheme.typography.labelLarge)
    SourceRuleField(
        value = draft.chapterItemSelector,
        label = "章节条目选择器",
        onValueChange = { onChange(draft.copy(chapterItemSelector = it)) },
    )
    SourceRuleField(
        value = draft.chapterLinkSelector,
        label = "章节链接选择器",
        onValueChange = { onChange(draft.copy(chapterLinkSelector = it)) },
    )
    SourceRuleField(
        value = draft.chapterTitleSelector,
        label = "章节标题选择器（可选）",
        onValueChange = { onChange(draft.copy(chapterTitleSelector = it)) },
    )
    SourceRuleField(
        value = draft.bodySelector,
        label = "正文选择器",
        onValueChange = { onChange(draft.copy(bodySelector = it)) },
    )
    SourceRuleField(
        value = draft.nextPageSelector,
        label = "章节下一页选择器（可选）",
        onValueChange = { onChange(draft.copy(nextPageSelector = it)) },
    )
    SourceRuleField(
        value = draft.additionalAllowedHosts,
        label = "额外允许域名（可选）",
        onValueChange = { onChange(draft.copy(additionalAllowedHosts = it)) },
    )
    SourceRuleField(
        value = draft.charset,
        label = "字符编码",
        onValueChange = { onChange(draft.copy(charset = it)) },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("允许局域网 HTTP", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = draft.allowLanHttp,
            onCheckedChange = { onChange(draft.copy(allowLanHttp = it)) },
            modifier = Modifier.semantics { contentDescription = "允许局域网 HTTP" },
        )
    }
}

@Composable
private fun SourceRuleField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnlineCatalogResultRow(
    entry: dev.readflow.extensions.api.OnlineCatalogEntry,
    selected: Boolean,
    isDownloading: Boolean,
    canDownload: Boolean,
    canPreview: Boolean,
    canBatchAcrossSource: Boolean,
    onToggleSelect: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onAuthorBatch: () -> Unit,
    onSeriesBatch: () -> Unit,
) {
    val book = entry.meta
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canDownload) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.semantics {
                        contentDescription = if (selected) {
                            "取消选择《${book.title}》"
                        } else {
                            "选择《${book.title}》"
                        }
                    },
                )
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canDownload) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = !isDownloading,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (isDownloading) "下载中" else "下载")
                }
            }
        }
        if (canDownload || canPreview) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (canDownload) {
                    TextButton(onClick = onAuthorBatch) {
                        Text(if (canBatchAcrossSource) "同作者全选" else "当前结果同作者")
                    }
                    if (!entry.series.isNullOrBlank()) {
                        TextButton(onClick = onSeriesBatch) {
                            Text(if (canBatchAcrossSource) "同系列全选" else "当前结果同系列")
                        }
                    }
                }
                if (canPreview) {
                    TextButton(onClick = onPreview) { Text("正文预览") }
                }
            }
        }
    }
}
