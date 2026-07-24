package dev.readflow.features.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
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
private val SOURCE_CONFIG_MIMES = arrayOf("application/json", "text/json", "application/octet-stream", "text/*")

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
    val sourceConfigLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importSourceConfigFromUri(context, it) }
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
                                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("离线可读") },
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
                                                    Text("✓", color = MaterialTheme.colorScheme.primary)
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when {
                    state.isLoading && state.items.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.error != null && state.items.isEmpty() -> {
                        Text(
                            text = "加载失败：${state.error}",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    state.items.isEmpty() && state.filter == LibraryFilter.OFFLINE -> {
                        OfflineEmptyState(onShowAll = { viewModel.setLibraryFilter(LibraryFilter.ALL) })
                    }
                    state.items.isEmpty() -> {
                        EmptyState(
                            onOpenOnlineLibrary = { showOnlineLibrary = true },
                            onImportLocal = { fileLauncher.launch(SUPPORTED_MIMES) },
                        )
                    }
                    else -> {
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
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // Scan progress dialog (not dismissible externally)
        if (scanProgress?.scanning == true) {
            ImportProgressDialog(
                found = scanProgress!!.found,
                onCancel = viewModel::cancelScan,
            )
        }

        // Scan complete → review sheet
        if (scanProgress?.scanning == false && scanProgress!!.books.isNotEmpty()) {
            ImportPreviewSheet(
                books = scanProgress!!.books,
                onImport = { viewModel.importFromFolder(it) },
                onDismiss = viewModel::clearScan,
            )
        }

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
                onImportSourceConfig = { sourceConfigLauncher.launch(SOURCE_CONFIG_MIMES) },
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
    onImportSourceConfig: () -> Unit,
    onRemoveSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = state.sources.firstOrNull { it.id == state.selectedSourceId }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var sourceActionsExpanded by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(false) }
    var pendingDeleteSourceId by remember { mutableStateOf<String?>(null) }
    val canFilterAuthor = selected?.capabilities?.canFilterByAuthor == true
    val canFilterSeries = selected?.capabilities?.canFilterBySeries == true
    val canFilterFormat = selected?.capabilities?.canFilterByFormat == true
    val canFilterTag = selected?.capabilities?.canFilterByTag == true
    val hasSecondaryFilters = canFilterAuthor || canFilterSeries || canFilterFormat || canFilterTag
    val activeFilterCount = listOf(
        state.filter.author,
        state.filter.series,
        state.filter.format,
        state.filter.tag,
    ).count { it.isNotBlank() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("在线书库", style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "关闭在线书库" },
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("书源", style = MaterialTheme.typography.labelLarge)
                Box {
                    IconButton(
                        onClick = { sourceActionsExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "管理书源" },
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sourceActionsExpanded,
                        onDismissRequest = { sourceActionsExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("添加书源") },
                            onClick = {
                                sourceActionsExpanded = false
                                onOpenSourceEditor()
                            },
                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("导入 JSON") },
                            onClick = {
                                sourceActionsExpanded = false
                                onImportSourceConfig()
                            },
                            enabled = !state.isAddingSource,
                            modifier = Modifier.semantics { contentDescription = "导入书源配置" },
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                        )
                        selected?.let { userSource ->
                            DropdownMenuItem(
                                text = { Text("删除当前书源") },
                                onClick = {
                                    sourceActionsExpanded = false
                                    pendingDeleteSourceId = userSource.id
                                },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            )
                        }
                    }
                }
            }
            if (state.sources.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("还没有书源", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "添加网页书源、OPDS、JSON 或 Calibre 后即可搜索和预览。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenSourceEditor) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加第一个书源")
                    }
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = sourceMenuExpanded,
                    onExpandedChange = { sourceMenuExpanded = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "书源选择器" },
                ) {
                    OutlinedTextField(
                        value = selected?.name ?: "选择书源",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("当前书源") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "当前书源：${selected?.name ?: "未选择"}"
                                role = Role.DropdownList
                            },
                    )
                    ExposedDropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false },
                    ) {
                        state.sources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.name) },
                                onClick = {
                                    sourceMenuExpanded = false
                                    if (source.enabled) onSelectSource(source.id)
                                },
                                enabled = source.enabled,
                                trailingIcon = {
                                    if (source.id == state.selectedSourceId) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text("搜索书名或作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = onSearch,
                            enabled = !state.isSearching,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "搜索" },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                            )
                        }
                    },
                )

                if (hasSecondaryFilters) {
                    OutlinedButton(
                        onClick = { filtersExpanded = !filtersExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = if (activeFilterCount > 0) {
                                    "筛选条件，已启用 $activeFilterCount 项"
                                } else {
                                    "筛选条件"
                                }
                            },
                    ) {
                        Text(
                            if (filtersExpanded) {
                                "收起筛选"
                            } else if (activeFilterCount > 0) {
                                "筛选条件（$activeFilterCount）"
                            } else {
                                "筛选条件"
                            },
                        )
                    }
                    if (filtersExpanded) {
                        if (canFilterAuthor) {
                            OutlinedTextField(
                                value = state.filter.author,
                                onValueChange = { onFilterChange(state.filter.copy(author = it)) },
                                label = { Text("作者") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (canFilterSeries) {
                            OutlinedTextField(
                                value = state.filter.series,
                                onValueChange = { onFilterChange(state.filter.copy(series = it)) },
                                label = { Text("系列") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (canFilterFormat) {
                            OutlinedTextField(
                                value = state.filter.format,
                                onValueChange = { onFilterChange(state.filter.copy(format = it)) },
                                label = { Text("格式") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (canFilterTag) {
                            OutlinedTextField(
                                value = state.filter.tag,
                                onValueChange = { onFilterChange(state.filter.copy(tag = it)) },
                                label = { Text("标签") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
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
                        liveRegion = LiveRegionMode.Polite
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

            if (state.results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
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
            } else if (
                state.sources.isNotEmpty() &&
                !state.isSearching &&
                statusError == null &&
                statusMessage == null
            ) {
                Text(
                    text = if (selected == null) {
                        "选择书源后即可搜索"
                    } else {
                        "输入书名或作者开始搜索"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingDeleteSourceId?.let { sourceId ->
        val sourceName = state.sources.firstOrNull { it.id == sourceId }?.name ?: "该书源"
        AlertDialog(
            onDismissRequest = { pendingDeleteSourceId = null },
            title = { Text("删除书源") },
            text = { Text("确定删除「$sourceName」？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteSourceId = null
                        onRemoveSource(sourceId)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSourceId = null }) {
                    Text("取消")
                }
            },
        )
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
    var advancedSourceOptionsExpanded by rememberSaveable(state.addSourceAdapterId) {
        mutableStateOf(false)
    }
    // HTML_RULES first; Calibre optional at the end of the type list.
    val adapterOptions = listOf(
        SourceEditorOption(
            dev.readflow.extensions.api.SourceAdapterIds.HTML_RULES_V1,
            "网页小说站",
            "自定义搜索、目录与正文规则",
        ),
        SourceEditorOption(
            dev.readflow.extensions.api.SourceAdapterIds.OPDS,
            "OPDS / Atom",
            "开放目录与公共电子书库",
        ),
        SourceEditorOption(
            dev.readflow.extensions.api.SourceAdapterIds.JSON_HTTP,
            "JSON 目录",
            "接入自建或第三方结构化书目接口",
        ),
        SourceEditorOption(
            dev.readflow.extensions.api.SourceAdapterIds.CALIBRE,
            "Calibre",
            "自托管 Calibre 内容服务器",
        ),
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
                    Column {
                        adapterOptions.forEach { option ->
                            val selected = state.addSourceAdapterId == option.adapterId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .clickable { onFormChange(null, null, option.adapterId) }
                                    .semantics {
                                        contentDescription = "${option.title}，${option.description}"
                                        role = Role.RadioButton
                                        stateDescription = if (selected) "已选择" else "未选择"
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        option.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = if (
                            state.addSourceAdapterId ==
                            dev.readflow.extensions.api.SourceAdapterIds.HTML_RULES_V1
                        ) {
                            "只需填写地址；常用解析规则已有通用默认值，网站结构不同再到高级设置调整。"
                        } else {
                            "只需填写地址；名称会自动生成，已有自定义配置仍可通过 JSON 导入。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.addSourceAdapterId == dev.readflow.extensions.api.SourceAdapterIds.HTML_RULES_V1) {
                        SourceRuleField(
                            value = state.htmlSourceDraft.searchUrlTemplate,
                            label = "搜索地址模板",
                            supportingText = "必填，使用 {query} 代表关键词；分页站点可加 {page}",
                            onValueChange = {
                                onHtmlDraftChange(state.htmlSourceDraft.copy(searchUrlTemplate = it))
                            },
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
                            supportingText = {
                                Text(
                                    when (state.addSourceAdapterId) {
                                        dev.readflow.extensions.api.SourceAdapterIds.CALIBRE ->
                                            "局域网通常使用 http://电脑IP:8080"
                                        dev.readflow.extensions.api.SourceAdapterIds.OPDS ->
                                            "填写服务提供的 OPDS / Atom 目录地址"
                                        else -> "填写兼容 LinReads 目录协议的 JSON 地址"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = state.addSourceName,
                        onValueChange = { onFormChange(it, null, null) },
                        label = { Text("显示名称（可选）") },
                        supportingText = {
                            Text("未填写时自动使用“${defaultSourceName(state.addSourceAdapterId)}”")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (
                        state.addSourceAdapterId ==
                        dev.readflow.extensions.api.SourceAdapterIds.HTML_RULES_V1
                    ) {
                        TextButton(
                            onClick = {
                                advancedSourceOptionsExpanded = !advancedSourceOptionsExpanded
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    stateDescription = if (advancedSourceOptionsExpanded) {
                                        "已展开"
                                    } else {
                                        "已收起"
                                    }
                                },
                        ) {
                            Text(if (advancedSourceOptionsExpanded) "收起高级设置" else "高级设置")
                            Icon(
                                imageVector = if (advancedSourceOptionsExpanded) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = null,
                            )
                        }
                        if (advancedSourceOptionsExpanded) {
                            HtmlSourceRuleFields(
                                draft = state.htmlSourceDraft,
                                onChange = onHtmlDraftChange,
                            )
                        }
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

private data class SourceEditorOption(
    val adapterId: String,
    val title: String,
    val description: String,
)

@Composable
private fun HtmlSourceRuleFields(
    draft: HtmlSourceDraft,
    onChange: (HtmlSourceDraft) -> Unit,
) {
    Text("搜索页规则", style = MaterialTheme.typography.labelLarge)
    SourceRuleField(
        value = draft.itemSelector,
        label = "结果条目选择器",
        supportingText = "默认：.bookbox, .book-item, li；网站结构不同再调整",
        onValueChange = { onChange(draft.copy(itemSelector = it)) },
    )
    SourceRuleField(
        value = draft.titleSelector,
        label = "书名选择器",
        supportingText = "默认匹配常见标题链接；预览无书名时调整",
        onValueChange = { onChange(draft.copy(titleSelector = it)) },
    )
    SourceRuleField(
        value = draft.authorSelector,
        label = "作者选择器",
        supportingText = "默认：.author, .writer；作者缺失时调整",
        onValueChange = { onChange(draft.copy(authorSelector = it)) },
    )
    SourceRuleField(
        value = draft.detailLinkSelector,
        label = "详情链接选择器",
        supportingText = "默认优先标题链接；无法打开目录时调整",
        onValueChange = { onChange(draft.copy(detailLinkSelector = it)) },
    )
    SourceRuleField(
        value = draft.seriesSelector,
        label = "系列选择器（可选）",
        supportingText = "默认留空；网站提供系列信息时填写",
        onValueChange = { onChange(draft.copy(seriesSelector = it)) },
    )
    Text("目录与正文规则", style = MaterialTheme.typography.labelLarge)
    SourceRuleField(
        value = draft.chapterItemSelector,
        label = "章节条目选择器",
        supportingText = "默认匹配常见章节列表；目录为空时调整",
        onValueChange = { onChange(draft.copy(chapterItemSelector = it)) },
    )
    SourceRuleField(
        value = draft.chapterLinkSelector,
        label = "章节链接选择器",
        supportingText = "默认：a；章节条目不是链接时调整",
        onValueChange = { onChange(draft.copy(chapterLinkSelector = it)) },
    )
    SourceRuleField(
        value = draft.chapterTitleSelector,
        label = "章节标题选择器（可选）",
        supportingText = "默认留空并使用目录标题；需要正文页标题时填写",
        onValueChange = { onChange(draft.copy(chapterTitleSelector = it)) },
    )
    SourceRuleField(
        value = draft.bodySelector,
        label = "正文选择器",
        supportingText = "默认匹配常见正文容器；正文为空或夹杂导航时调整",
        onValueChange = { onChange(draft.copy(bodySelector = it)) },
    )
    SourceRuleField(
        value = draft.nextPageSelector,
        label = "章节下一页选择器（可选）",
        supportingText = "默认留空；单章被拆成多页时填写",
        onValueChange = { onChange(draft.copy(nextPageSelector = it)) },
    )
    SourceRuleField(
        value = draft.additionalAllowedHosts,
        label = "额外允许域名（可选）",
        supportingText = "默认只允许搜索地址域名；章节或下载跨域时添加",
        onValueChange = { onChange(draft.copy(additionalAllowedHosts = it)) },
    )
    SourceRuleField(
        value = draft.charset,
        label = "字符编码",
        supportingText = "默认：UTF-8；页面出现乱码时改为站点实际编码",
        onValueChange = { onChange(draft.copy(charset = it)) },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("允许局域网 HTTP", style = MaterialTheme.typography.bodyLarge)
            Text(
                "默认关闭；仅局域网 HTTP 书源需要开启",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    supportingText: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
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
    var overflowExpanded by remember { mutableStateOf(false) }
    val hasOverflowActions = canDownload || canPreview
    val downloadLabel = if (isDownloading) "正在下载《${book.title}》" else "下载《${book.title}》"
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
                IconButton(
                    onClick = onDownload,
                    enabled = !isDownloading,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = downloadLabel },
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                }
            }
            if (hasOverflowActions) {
                Box {
                    IconButton(
                        onClick = { overflowExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "更多操作，${book.title}" },
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        if (canDownload) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (canBatchAcrossSource) "同作者全选" else "当前结果同作者",
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onAuthorBatch()
                                },
                            )
                            if (!entry.series.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (canBatchAcrossSource) "同系列全选" else "当前结果同系列",
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        onSeriesBatch()
                                    },
                                )
                            }
                        }
                        if (canPreview) {
                            DropdownMenuItem(
                                text = { Text("正文预览") },
                                onClick = {
                                    overflowExpanded = false
                                    onPreview()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
