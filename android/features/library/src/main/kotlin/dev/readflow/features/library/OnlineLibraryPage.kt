package dev.readflow.features.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.readflow.core.ui.BookCover
import dev.readflow.core.ui.Dimens
import dev.readflow.core.ui.libraryGridColumns
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineLibraryPage(
    state: OnlineLibraryUiState,
    onSelectSource: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onToggleSelect: (OnlineCatalogEntry) -> Unit,
    onToggleAllCurrent: () -> Unit,
    onApplyFacet: (OnlineCatalogFilter) -> Unit,
    onSelectAuthor: (String) -> Unit,
    onSelectSeries: (String) -> Unit,
    onDownloadEntry: (OnlineCatalogEntry) -> Unit,
    onDownloadSelected: () -> Unit,
    onPreview: (OnlineCatalogEntry) -> Unit,
    onOpenSourceEditor: (String?) -> Unit,
    onImportSourceConfig: () -> Unit,
    onRemoveSource: (String) -> Unit,
) {
    val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var sourceActionsExpanded by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteSourceId by remember { mutableStateOf<String?>(null) }

    val canFilterAuthor = selectedSource?.capabilities?.canFilterByAuthor == true
    val canFilterSeries = selectedSource?.capabilities?.canFilterBySeries == true
    val canFilterFormat = selectedSource?.capabilities?.canFilterByFormat == true
    val canFilterTag = selectedSource?.capabilities?.canFilterByTag == true
    val hasSecondaryFilters = canFilterAuthor || canFilterSeries || canFilterFormat || canFilterTag
    val activeFilterCount = listOf(
        state.filter.author,
        state.filter.series,
        state.filter.format,
        state.filter.tag,
    ).count(String::isNotBlank)

    LaunchedEffect(state.selectedSourceId) {
        selectionMode = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.sources.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = sourceMenuExpanded,
                    onExpandedChange = { sourceMenuExpanded = it },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "书源选择器" },
                ) {
                    OutlinedTextField(
                        value = selectedSource?.name ?: "选择书源",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "当前书源：${selectedSource?.name ?: "未选择"}"
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
            } else {
                Text(
                    text = "在线书源",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }

            Box {
                IconButton(
                    onClick = { sourceActionsExpanded = true },
                    modifier = Modifier
                        .size(Dimens.touchTarget)
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
                            onOpenSourceEditor(null)
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
                        leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                    )
                    selectedSource?.let { source ->
                        DropdownMenuItem(
                            text = { Text("编辑当前书源") },
                            onClick = {
                                sourceActionsExpanded = false
                                onOpenSourceEditor(source.id)
                            },
                            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("删除当前书源") },
                            onClick = {
                                sourceActionsExpanded = false
                                pendingDeleteSourceId = source.id
                            },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { searchExpanded = !searchExpanded },
                enabled = state.sources.isNotEmpty(),
                modifier = Modifier
                    .size(Dimens.touchTarget)
                    .semantics {
                        contentDescription = "搜索在线书库"
                        stateDescription = if (searchExpanded) "已展开" else "已收起"
                    },
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
            }
            if (hasSecondaryFilters) {
                IconButton(
                    onClick = { filtersExpanded = true },
                    modifier = Modifier
                        .size(Dimens.touchTarget)
                        .semantics {
                            contentDescription = "筛选在线书库"
                            stateDescription = if (activeFilterCount > 0) {
                                "已应用 $activeFilterCount 项筛选"
                            } else {
                                "未应用筛选"
                            }
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        tint = if (activeFilterCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(
                onClick = { selectionMode = !selectionMode },
                enabled = state.results.isNotEmpty() && selectedSource?.capabilities?.canDownload == true,
                modifier = Modifier
                    .size(Dimens.touchTarget)
                    .semantics {
                        contentDescription = "多选书籍"
                        stateDescription = if (selectionMode) "已开启" else "已关闭"
                    },
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (selectionMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (searchExpanded && state.sources.isNotEmpty()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("书名或作者") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (!state.isSearching) onSearch()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { contentDescription = "在线书库搜索词" },
                trailingIcon = {
                    IconButton(
                        onClick = onSearch,
                        enabled = !state.isSearching,
                        modifier = Modifier
                            .size(Dimens.touchTarget)
                            .semantics { contentDescription = "执行搜索" },
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                    }
                },
            )
        }

        OnlineLibraryStatus(state)

        if (selectionMode && state.results.isNotEmpty() && selectedSource?.capabilities?.canDownload == true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.touchTarget)
                        .toggleable(
                            value = state.allCurrentResultsSelected,
                            role = Role.Checkbox,
                            onValueChange = { onToggleAllCurrent() },
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (state.allCurrentResultsSelected) {
                                "取消全选当前结果"
                            } else {
                                "全选当前结果"
                            }
                            stateDescription = if (state.allCurrentResultsSelected) "已全选" else "未全选"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.allCurrentResultsSelected, onCheckedChange = null)
                    Text("已选 ${state.selectedEntryKeys.size} 本（共 ${state.results.size} 本）")
                }
                if (state.selectedEntryKeys.isNotEmpty()) {
                    Button(
                        onClick = onDownloadSelected,
                        enabled = !state.isDownloadingBatch,
                        modifier = Modifier.widthIn(min = 96.dp).heightIn(min = Dimens.touchTarget),
                    ) {
                        Text(if (state.isDownloadingBatch) "下载中" else "下载所选")
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when {
                state.sources.isEmpty() -> OnlineSourceEmptyState(
                    onAdd = { onOpenSourceEditor(null) },
                    onImport = onImportSourceConfig,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.isSearching && state.results.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                state.error != null && state.results.isEmpty() -> OnlineCatalogErrorState(
                    message = state.error,
                    onRetry = onSearch,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.results.isEmpty() -> OnlineCatalogEmptyState(
                    onRetry = onSearch,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val windowWidthPx = LocalWindowInfo.current.containerSize.width
                    val screenWidthDp = with(LocalDensity.current) { windowWidthPx.toDp().value }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(libraryGridColumns(screenWidthDp)),
                        contentPadding = PaddingValues(
                            start = Dimens.screenEdge,
                            top = Dimens.spaceMd,
                            end = Dimens.screenEdge,
                            bottom = Dimens.spaceXl,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gridGapHorizontal),
                        verticalArrangement = Arrangement.spacedBy(Dimens.gridGapVertical),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = Dimens.maxContentWidth)
                            .fillMaxWidth(),
                    ) {
                        items(state.results, key = { it.selectionKey() }) { entry ->
                            OnlineCatalogBookCard(
                                entry = entry,
                                selectionMode = selectionMode,
                                selected = entry.selectionKey() in state.selectedEntryKeys,
                                isDownloading = entry.selectionKey() in state.downloadingKeys,
                                canDownload = selectedSource?.capabilities?.canDownload == true,
                                downloadEnabled = !state.isDownloadingBatch,
                                canPreview = selectedSource?.capabilities?.canPreviewText == true,
                                canBatchAcrossSource = selectedSource?.capabilities?.canBatchAcrossSource == true,
                                onToggleSelect = { onToggleSelect(entry) },
                                onDownload = { onDownloadEntry(entry) },
                                onPreview = { onPreview(entry) },
                                onAuthorBatch = { author ->
                                    selectionMode = true
                                    onSelectAuthor(author)
                                },
                                onSeriesBatch = {
                                    selectionMode = true
                                    entry.series?.let(onSelectSeries)
                                },
                            )
                        }
                        if (state.hasMore) {
                            item(
                                key = "load-more",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                OnlineCatalogLoadMore(
                                    loading = state.isLoadingMore,
                                    failed = state.error != null,
                                    nextOffset = state.nextOffset,
                                    onLoadMore = onLoadMore,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (filtersExpanded && hasSecondaryFilters) {
        ModalBottomSheet(onDismissRequest = { filtersExpanded = false }) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "filter-title") {
                    Text("筛选书目", style = MaterialTheme.typography.titleMedium)
                }
                if (canFilterAuthor) {
                    item(key = "filter-author") {
                        OnlineMetadataFacetRow(
                            title = "作者",
                            facets = state.metadataFacets.authors,
                            selectedValue = state.filter.author,
                            onSelect = { value ->
                                onApplyFacet(
                                    state.filter.copy(
                                        author = value.takeUnless {
                                            it.equals(state.filter.author, ignoreCase = true)
                                        }.orEmpty(),
                                    ),
                                )
                            },
                        )
                    }
                }
                if (canFilterSeries) {
                    item(key = "filter-series") {
                        OnlineMetadataFacetRow(
                            title = "系列",
                            facets = state.metadataFacets.series,
                            selectedValue = state.filter.series,
                            onSelect = { value ->
                                onApplyFacet(
                                    state.filter.copy(
                                        series = value.takeUnless {
                                            it.equals(state.filter.series, ignoreCase = true)
                                        }.orEmpty(),
                                    ),
                                )
                            },
                        )
                    }
                }
                if (canFilterFormat) {
                    item(key = "filter-format") {
                        OnlineMetadataFacetRow(
                            title = "格式",
                            facets = state.metadataFacets.formats,
                            selectedValue = state.filter.format,
                            onSelect = { value ->
                                onApplyFacet(
                                    state.filter.copy(
                                        format = value.takeUnless {
                                            it.equals(state.filter.format, ignoreCase = true)
                                        }.orEmpty(),
                                    ),
                                )
                            },
                        )
                    }
                }
                if (canFilterTag) {
                    item(key = "filter-tag") {
                        OnlineMetadataFacetRow(
                            title = "标签",
                            facets = state.metadataFacets.tags,
                            selectedValue = state.filter.tag,
                            onSelect = { value ->
                                onApplyFacet(
                                    state.filter.copy(
                                        tag = value.takeUnless {
                                            it.equals(state.filter.tag, ignoreCase = true)
                                        }.orEmpty(),
                                    ),
                                )
                            },
                        )
                    }
                }
                if (state.filter.author.isNotBlank() && selectedSource?.capabilities?.canDownload == true) {
                    item(key = "select-author") {
                        OutlinedButton(
                            onClick = {
                                selectionMode = true
                                filtersExpanded = false
                                onSelectAuthor(state.filter.author)
                            },
                            enabled = !state.isSelectingBatch,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
                        ) {
                            Text("选择该作者全部书籍")
                        }
                    }
                }
                if (state.filter.series.isNotBlank() && selectedSource?.capabilities?.canDownload == true) {
                    item(key = "select-series") {
                        OutlinedButton(
                            onClick = {
                                selectionMode = true
                                filtersExpanded = false
                                onSelectSeries(state.filter.series)
                            },
                            enabled = !state.isSelectingBatch,
                            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
                        ) {
                            Text("选择该系列全部书籍")
                        }
                    }
                }
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

@Composable
private fun OnlineCatalogLoadMore(
    loading: Boolean,
    failed: Boolean,
    nextOffset: Int,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(nextOffset) {
        if (!loading) onLoadMore()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget),
        contentAlignment = Alignment.Center,
    ) {
        if (loading || !failed) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = Dimens.touchTarget)) {
                Text("继续加载")
            }
        }
    }
}

@Composable
private fun OnlineLibraryStatus(state: OnlineLibraryUiState) {
    when {
        (state.isSearching && state.results.isNotEmpty()) || state.isSelectingBatch || state.isDownloadingBatch -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    when {
                        state.isDownloadingBatch -> "正在批量下载"
                        state.isSelectingBatch -> "正在汇总匹配书籍"
                        else -> "正在刷新书目"
                    },
                )
            }
        }
        state.error != null && state.results.isNotEmpty() -> Text(
            text = state.error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .semantics {
                    contentDescription = "在线书库错误：${state.error}"
                    liveRegion = LiveRegionMode.Polite
                },
        )
        state.message != null -> Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun OnlineSourceEmptyState(
    onAdd: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("还没有书源", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "添加或导入书源后，这里会直接展示书目。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = Dimens.touchTarget)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("添加第一个书源")
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = Dimens.touchTarget)) {
            Text("导入 JSON")
        }
    }
}

@Composable
private fun OnlineCatalogErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics {
                contentDescription = "在线书库错误：$message"
                liveRegion = LiveRegionMode.Polite
            },
        )
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = Dimens.touchTarget)) {
            Text("重新加载")
        }
    }
}

@Composable
private fun OnlineCatalogEmptyState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("该书源暂无可浏览的书籍", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = Dimens.touchTarget)) {
            Text("重新加载")
        }
    }
}

@Composable
private fun OnlineMetadataFacetRow(
    title: String,
    facets: List<MetadataFacet>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    val visibleFacets = if (
        selectedValue.isNotBlank() && facets.none { it.value.equals(selectedValue, ignoreCase = true) }
    ) {
        listOf(MetadataFacet(selectedValue, 0)) + facets
    } else {
        facets
    }
    if (visibleFacets.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleFacets, key = { "${title}:${it.value.lowercase()}" }) { facet ->
                val isSelected = facet.value.equals(selectedValue, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(facet.value) },
                    label = { Text("${facet.value} · ${facet.count}") },
                    modifier = Modifier
                        .heightIn(min = Dimens.touchTarget)
                        .semantics {
                            contentDescription = "$title${facet.value}，${facet.count} 本"
                            stateDescription = if (isSelected) "已选择" else "未选择"
                        },
                )
            }
        }
    }
}

@Composable
private fun OnlineCatalogBookCard(
    entry: OnlineCatalogEntry,
    selectionMode: Boolean,
    selected: Boolean,
    isDownloading: Boolean,
    canDownload: Boolean,
    downloadEnabled: Boolean,
    canPreview: Boolean,
    canBatchAcrossSource: Boolean,
    onToggleSelect: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onAuthorBatch: (String) -> Unit,
    onSeriesBatch: () -> Unit,
) {
    val book = entry.meta
    val batchAuthors = entry.individualAuthors()
    var overflowExpanded by remember { mutableStateOf(false) }
    val downloadLabel = if (isDownloading) "正在下载《${book.title}》" else "下载《${book.title}》"
    val cardAction = if (selectionMode) onToggleSelect else if (canPreview) onPreview else ({ overflowExpanded = true })

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${book.title}，${book.author}"
                if (selectionMode) stateDescription = if (selected) "已选择" else "未选择"
            }
            .clickable(role = Role.Button, onClick = cardAction),
    ) {
        Box(modifier = Modifier.aspectRatio(Dimens.coverAspectRatio)) {
            BookCover(
                book = book,
                showProgress = false,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
            )

            if (selectionMode && canDownload) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(Dimens.touchTarget)
                        .toggleable(
                            value = selected,
                            role = Role.Checkbox,
                            onValueChange = { onToggleSelect() },
                        )
                        .semantics {
                            contentDescription = if (selected) {
                                "取消选择《${book.title}》"
                            } else {
                                "选择《${book.title}》"
                            }
                            stateDescription = if (selected) "已选择" else "未选择"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.align(Alignment.TopStart)) {
                    IconButton(
                        onClick = { overflowExpanded = true },
                        modifier = Modifier
                            .size(Dimens.touchTarget)
                            .semantics { contentDescription = "更多操作，${book.title}" },
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f),
                            shape = CircleShape,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        if (canDownload) {
                            batchAuthors.forEach { author ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (canBatchAcrossSource) "全选作者：$author" else "当前结果：$author")
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        onAuthorBatch(author)
                                    },
                                )
                            }
                            if (!entry.series.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (canBatchAcrossSource) "同系列全选" else "当前结果同系列")
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

            if (canDownload && !selectionMode) {
                IconButton(
                    onClick = onDownload,
                    enabled = downloadEnabled && !isDownloading,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(Dimens.touchTarget)
                        .semantics { contentDescription = downloadLabel },
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f),
                        shape = CircleShape,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(top = 6.dp, bottom = 4.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
