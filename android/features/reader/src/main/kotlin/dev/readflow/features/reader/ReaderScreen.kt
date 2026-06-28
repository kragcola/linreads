package dev.readflow.features.reader

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.TransitionType
import dev.readflow.core.prefs.ReaderTypography
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ZoomableReaderEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 阅读器菜单功能项 — debug 阶段全部默认显示。
 * 后续通过 feature flag / 用户配置选择性显示。
 */
enum class ReaderFeature(val label: String) {
    TOC("目录"),
    SEARCH("搜索"),
    BOOKMARKS("书签"),
    ANNOTATIONS("标注"),
    PROGRESS("进度"),
    FONT("排版"),
    THEME("主题"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    features: Set<ReaderFeature> = ReaderFeature.entries.toSet(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        when (state.loadingState) {
            is LoadingState.Loading, LoadingState.Idle ->
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            is LoadingState.Error ->
                ReaderErrorContent(
                    onRetry = { viewModel.onIntent(ReaderIntent.Retry) },
                    onBack = onBack,
                )
            LoadingState.Loaded -> {
                val engine = state.engine
                if (engine == null) {
                    Text("无内容", color = MaterialTheme.colorScheme.onBackground)
                } else {
                    val pagingKind by engine.pagingKind.collectAsState()
                    val host = remember(engine, pagingKind) {
                        when (pagingKind) {
                            PagingKind.CONTINUOUS -> viewModel.hostFactory.continuous()
                            PagingKind.PAGED -> viewModel.hostFactory.paged(TransitionType.CURL)
                        }.also { it.bind(engine) }
                    }
                    DisposableEffect(host) {
                        onDispose { host.unbind() }
                    }

                    // Document view — observe taps in dispatch, then pass events through to the engine view.
                    val zoomableEngine = engine as? ZoomableReaderEngine
                    val zoomScaleFlow = remember(engine) {
                        zoomableEngine?.zoomScale ?: MutableStateFlow(1f)
                    }
                    val zoomScale by zoomScaleFlow.collectAsState()
                    val readerFocusRequester = remember { FocusRequester() }
                    fun handleReaderKey(nativeEvent: KeyEvent): Boolean {
                        if (state.activePanel != null) return false
                        val action = readerTapZoneForKey(nativeEvent.keyCode, nativeEvent.isShiftPressed) ?: return false
                        if (nativeEvent.action == KeyEvent.ACTION_UP) {
                            when (action) {
                                ReaderTapZone.PreviousPage -> scope.launch { host.previous() }
                                ReaderTapZone.ToggleChrome -> viewModel.onIntent(ReaderIntent.ToggleChrome)
                                ReaderTapZone.NextPage -> scope.launch { host.next() }
                            }
                        }
                        return true
                    }
                    LaunchedEffect(host, state.activePanel) {
                        if (state.activePanel == null) {
                            readerFocusRequester.requestFocus()
                        }
                    }
                    key(host) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.systemBars)
                                .onPreviewKeyEvent { handleReaderKey(it.nativeKeyEvent) }
                                .focusRequester(readerFocusRequester)
                                .focusable(),
                            factory = { ctx ->
                                ReaderTapContainer(
                                    context = ctx,
                                    onAction = { zone ->
                                        when (zone) {
                                            ReaderTapZone.PreviousPage -> scope.launch { host.previous() }
                                            ReaderTapZone.ToggleChrome -> viewModel.onIntent(ReaderIntent.ToggleChrome)
                                            ReaderTapZone.NextPage -> scope.launch { host.next() }
                                        }
                                    },
                                    onFontSizePreview = {
                                        viewModel.onIntent(ReaderIntent.PreviewFontSize(it))
                                    },
                                    onZoomPreview = {
                                        viewModel.onIntent(ReaderIntent.PreviewZoom(it))
                                    },
                                ).apply {
                                    setDocumentView(host.hostView())
                                    requestFocus()
                                }
                            },
                            update = { view ->
                                view.setDocumentView(host.hostView())
                                view.currentFontSizeSp = state.fontSizeSp
                                view.currentZoomScale = zoomScale
                                view.isZoomPinchEnabled = zoomableEngine != null
                                view.isPagedTapZonesEnabled = pagingKind == PagingKind.PAGED
                                view.isFontPinchEnabled = engine.format != BookFormat.PDF
                            },
                        )
                    }

                    state.textSelection?.let { selection ->
                        TextSelectionActions(
                            selectedText = selection.selectedText,
                            onHighlight = {
                                viewModel.onIntent(ReaderIntent.SaveTextAnnotation(note = null))
                            },
                            onSaveNote = {
                                viewModel.onIntent(ReaderIntent.SaveTextAnnotation(note = it))
                            },
                            onClear = {
                                viewModel.onIntent(ReaderIntent.ClearTextSelection)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 76.dp),
                        )
                    }

                    // ── Top chrome: 书名 + 返回 ──
                    AnimatedVisibility(
                        visible = state.isUiVisible,
                        modifier = Modifier.align(Alignment.TopStart),
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut(),
                    ) {
                        TopAppBar(
                            title = { Text(state.bookTitle, maxLines = 1) },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = { viewModel.onIntent(ReaderIntent.ToggleBookmark) },
                                    enabled = state.canBookmark,
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = if (state.bookmarks.isCurrentBookmarked) {
                                            "移除书签"
                                        } else {
                                            "添加书签"
                                        },
                                        tint = if (state.bookmarks.isCurrentBookmarked) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onBackground
                                        },
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            ),
                        )
                    }

                    // ── Bottom chrome: 章节进度栏 + 快捷菜单 ──
                    AnimatedVisibility(
                        visible = state.isUiVisible,
                        modifier = Modifier.align(Alignment.BottomStart),
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        val chapter by engine.chapterInfo.collectAsState()
                        val locator by engine.currentLocator.collectAsState()
                        val overallProgressDescription = readerProgressPercentText(locator.totalProgression)
                        val chapterProgressDescription = readerChapterProgressDescription(
                            title = chapter.currentTitle,
                            currentIndex = chapter.currentIndex,
                            totalChapters = chapter.totalChapters,
                            progressInChapter = chapter.progressInChapter,
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 全书总进度条（可拖动跳转）
                            ReaderProgressSeekBar(
                                progress = (locator.totalProgression ?: 0f).coerceIn(0f, 1f),
                                progressDescription = overallProgressDescription,
                                onSeek = { viewModel.onIntent(ReaderIntent.SeekToProgress(it)) },
                            )
                            // ── 章节导航栏（独立一行）──
                            Surface(
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val prevEnabled = chapter.currentIndex > 0
                                    IconButton(
                                        onClick = { scope.launch { engine.goToAdjacentChapter(-1) } },
                                        enabled = prevEnabled,
                                        modifier = Modifier.semantics { contentDescription = "上一章" },
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = null,
                                            tint = if (prevEnabled)
                                                MaterialTheme.colorScheme.onBackground
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        )
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = chapterProgressDescription
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = chapter.currentTitle,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = "${chapter.currentIndex + 1} / ${chapter.totalChapters}章 · ${(chapter.progressInChapter * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    }
                                    val nextEnabled = chapter.currentIndex < chapter.totalChapters - 1
                                    IconButton(
                                        onClick = { scope.launch { engine.goToAdjacentChapter(+1) } },
                                        enabled = nextEnabled,
                                        modifier = Modifier.semantics { contentDescription = "下一章" },
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = if (nextEnabled)
                                                MaterialTheme.colorScheme.onBackground
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        )
                                    }
                                }
                            }
                            // ── 分隔线 ──
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            ReaderControlPanel(
                                panel = state.activePanel,
                                fontSizeSp = state.fontSizeSp,
                                lineSpacing = state.lineSpacing,
                                readingMode = state.readingMode,
                                supportedModes = state.supportedModes,
                                themeMode = state.themeMode,
                                searchState = state.search,
                                bookmarkState = state.bookmarks,
                                annotationState = state.annotations,
                                onBookmarkClick = { viewModel.onIntent(ReaderIntent.GoToBookmark(it)) },
                                onBookmarkRemove = { viewModel.onIntent(ReaderIntent.RemoveBookmark(it)) },
                                onAnnotationClick = { viewModel.onIntent(ReaderIntent.GoToAnnotation(it)) },
                                onSearchQueryChange = { viewModel.onIntent(ReaderIntent.SetSearchQuery(it)) },
                                onSearchSubmit = { viewModel.onIntent(ReaderIntent.SubmitSearch) },
                                onSearchResultClick = { viewModel.onIntent(ReaderIntent.GoToSearchResult(it)) },
                                onSearchClear = { viewModel.onIntent(ReaderIntent.ClearSearch) },
                                onFontSizeChange = { viewModel.onIntent(ReaderIntent.SetFontSize(it)) },
                                onLineSpacingChange = { viewModel.onIntent(ReaderIntent.SetLineSpacing(it)) },
                                onModeChange = { viewModel.onIntent(ReaderIntent.SetMode(it)) },
                                onThemeChange = { viewModel.onIntent(ReaderIntent.SetTheme(it)) },
                            )
                            // ── 快捷功能按钮 ──
                            Surface(
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    if (ReaderFeature.TOC in features) {
                                        ReaderMenuButton("目录", Icons.Default.Menu) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.TOC))
                                        }
                                    }
                                    if (ReaderFeature.SEARCH in features) {
                                        ReaderMenuButton("搜索", Icons.Default.Search) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
                                        }
                                    }
                                    if (ReaderFeature.BOOKMARKS in features) {
                                        ReaderMenuButton("书签", Icons.Default.Edit) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.BOOKMARKS))
                                        }
                                    }
                                    if (ReaderFeature.ANNOTATIONS in features) {
                                        ReaderMenuButton("标注", Icons.Default.Edit) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.ANNOTATIONS))
                                        }
                                    }
                                    if (ReaderFeature.FONT in features) {
                                        ReaderMenuButton("排版", Icons.Default.Edit) {
                                            viewModel.onIntent(ReaderIntent.FontPanel)
                                        }
                                    }
                                    if (ReaderFeature.THEME in features) {
                                        ReaderMenuButton("主题", Icons.Default.MoreVert) {
                                            viewModel.onIntent(ReaderIntent.ThemePanel)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── 目录左半屏抽屉（覆盖在内容上，独立于底部 chrome）──
                    val tocLocator by engine.currentLocator.collectAsState()
                    TocDrawer(
                        visible = state.activePanel == ReaderPanel.TOC,
                        tocEntries = engine.tableOfContents.collectAsState().value,
                        currentProgression = tocLocator.totalProgression,
                        onTocClick = { viewModel.onIntent(ReaderIntent.GoToTocEntry(it)) },
                        onDismiss = { viewModel.onIntent(ReaderIntent.ClosePanel) },
                    )

                    // ── 首次手势引导浮层 ──
                    if (state.showGuide) {
                        ReaderGestureGuideOverlay(
                            pagedMode = pagingKind == PagingKind.PAGED,
                            onDismiss = { viewModel.onIntent(ReaderIntent.DismissGuide) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderControlPanel(
    panel: ReaderPanel?,
    fontSizeSp: Float,
    lineSpacing: Float,
    readingMode: ReadingMode,
    supportedModes: Set<ReadingMode>,
    themeMode: ThemeMode,
    searchState: ReaderSearchState,
    bookmarkState: ReaderBookmarkState,
    annotationState: ReaderAnnotationState,
    onBookmarkClick: (ReaderBookmarkItem) -> Unit,
    onBookmarkRemove: (ReaderBookmarkItem) -> Unit,
    onAnnotationClick: (ReaderAnnotationItem) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchResultClick: (ReaderSearchResult) -> Unit,
    onSearchClear: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onModeChange: (ReadingMode) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
) {
    // 目录改为左半屏抽屉（见 TocDrawer），底部面板不再承载 TOC。
    AnimatedVisibility(visible = panel != null && panel != ReaderPanel.TOC) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
        ) {
            when (panel) {
                ReaderPanel.TOC -> Unit
                ReaderPanel.BOOKMARKS -> BookmarkPanel(
                    bookmarkState = bookmarkState,
                    onBookmarkClick = onBookmarkClick,
                    onBookmarkRemove = onBookmarkRemove,
                )
                ReaderPanel.ANNOTATIONS -> AnnotationPanel(
                    annotationState = annotationState,
                    onAnnotationClick = onAnnotationClick,
                )
                ReaderPanel.SEARCH -> SearchPanel(
                    searchState = searchState,
                    onQueryChange = onSearchQueryChange,
                    onSubmit = onSearchSubmit,
                    onResultClick = onSearchResultClick,
                    onClear = onSearchClear,
                )
                ReaderPanel.FONT -> FontPanel(
                    fontSizeSp = fontSizeSp,
                    lineSpacing = lineSpacing,
                    readingMode = readingMode,
                    supportedModes = supportedModes,
                    onFontSizeChange = onFontSizeChange,
                    onLineSpacingChange = onLineSpacingChange,
                    onModeChange = onModeChange,
                )
                ReaderPanel.THEME -> ThemePanel(themeMode, onThemeChange)
                null -> Unit
            }
        }
    }
}

@Composable
private fun TextSelectionActions(
    selectedText: String,
    onHighlight: () -> Unit,
    onSaveNote: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var note by remember(selectedText) { mutableStateOf("") }
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = selectedText.trim().replace('\n', ' ').take(80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("笔记") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClear) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onHighlight) {
                    Text("高亮")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onSaveNote(note) }) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun BookmarkPanel(
    bookmarkState: ReaderBookmarkState,
    onBookmarkClick: (ReaderBookmarkItem) -> Unit,
    onBookmarkRemove: (ReaderBookmarkItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("书签", style = MaterialTheme.typography.titleSmall)
        if (bookmarkState.items.isEmpty()) {
            Text(
                "暂无书签",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn {
                items(bookmarkState.items) { bookmark ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = bookmark.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = bookmark.accessibilityLabel()
                                }
                                .clickable { onBookmarkClick(bookmark) }
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                        )
                        IconButton(onClick = { onBookmarkRemove(bookmark) }) {
                            Icon(Icons.Default.Clear, contentDescription = "删除${bookmark.label}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationPanel(
    annotationState: ReaderAnnotationState,
    onAnnotationClick: (ReaderAnnotationItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("标注", style = MaterialTheme.typography.titleSmall)
        if (annotationState.items.isEmpty()) {
            Text(
                "暂无标注",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn {
                items(annotationState.items) { annotation ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = annotation.accessibilityLabel()
                            }
                            .clickable { onAnnotationClick(annotation) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = annotation.selectedText.trim().replace('\n', ' '),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                        )
                        annotation.note?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(
    searchState: ReaderSearchState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResultClick: (ReaderSearchResult) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("搜索", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("关键词") },
            )
            IconButton(
                onClick = onSubmit,
                enabled = searchState.query.isNotBlank() && !searchState.isSearching,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = "执行搜索")
            }
            IconButton(
                onClick = onClear,
                enabled = searchState.query.isNotBlank() || searchState.results.isNotEmpty(),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(Icons.Default.Clear, contentDescription = "清空搜索")
            }
        }
        if (searchState.isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        searchState.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (searchState.results.isNotEmpty()) {
            LazyColumn {
                items(searchState.results) { result ->
                    Text(
                        text = result.readerLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = result.readerAccessibilityLabel()
                            }
                            .clickable { onResultClick(result) }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TocDrawer(
    visible: Boolean,
    tocEntries: List<TocEntry>,
    currentProgression: Float?,
    onTocClick: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    // 高亮当前所在章节：取 progression 不超过当前位置的最后一条目录项
    val currentIndex = remember(tocEntries, currentProgression) {
        readerCurrentTocIndex(tocEntries, currentProgression)
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 点击右侧遮罩关闭
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .fillMaxHeight()
                        // 吞掉抽屉内部点击，避免穿透到遮罩
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                ) {
                    TocDrawerContent(
                        tocEntries = tocEntries,
                        currentIndex = currentIndex,
                        onTocClick = onTocClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun TocDrawerContent(
    tocEntries: List<TocEntry>,
    currentIndex: Int,
    onTocClick: (TocEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    // 打开时滚动到当前章节
    LaunchedEffect(currentIndex, tocEntries.size) {
        if (currentIndex in tocEntries.indices) {
            listState.scrollToItem(currentIndex.coerceAtLeast(0))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        if (tocEntries.isEmpty()) {
            Text(
                "暂无目录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(tocEntries) { entry ->
                    val isCurrent = tocEntries.indexOf(entry) == currentIndex
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .then(
                                if (isCurrent) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .semantics {
                                contentDescription = readerTocAccessibilityLabel(entry)
                            }
                            .clickable { onTocClick(entry) }
                            .padding(
                                start = (12 + entry.level.coerceIn(0, 4) * 16).dp,
                                top = 14.dp,
                                end = 8.dp,
                                bottom = 14.dp,
                            ),
                    )
                }
            }
        }
    }
}

/** 当前阅读位置对应的目录项下标；取 progression 不超过当前位置的最后一条。 */
internal fun readerCurrentTocIndex(tocEntries: List<TocEntry>, currentProgression: Float?): Int {
    if (tocEntries.isEmpty()) return -1
    val progress = currentProgression ?: return 0
    var index = 0
    tocEntries.forEachIndexed { i, entry ->
        val entryProgress = entry.locator.totalProgression ?: return@forEachIndexed
        if (entryProgress <= progress + 1e-4f) index = i
    }
    return index
}

@Composable
private fun FontPanel(
    fontSizeSp: Float,
    lineSpacing: Float,
    readingMode: ReadingMode,
    supportedModes: Set<ReadingMode>,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onModeChange: (ReadingMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("排版", style = MaterialTheme.typography.titleSmall)
            Text("${fontSizeSp.toInt()}sp", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = ReaderTypography.clampFontSp(fontSizeSp),
            onValueChange = onFontSizeChange,
            valueRange = ReaderTypography.MIN_FONT_SP..ReaderTypography.MAX_FONT_SP,
            steps = ReaderTypography.FONT_SLIDER_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "字号"
                    stateDescription = "${fontSizeSp.toInt()}sp"
                },
        )
        // 固定字号的说明标签（不随阅读字号缩放），下方才是真正按字号缩放的正文样张
        Text(
            text = "正文预览",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "海上生明月，天涯共此时。",
            fontSize = ReaderTypography.clampFontSp(fontSizeSp).sp,
            lineHeight = (ReaderTypography.clampFontSp(fontSizeSp) * 1.7f).sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("行距", style = MaterialTheme.typography.labelLarge)
            Text("${"%.2f".format(lineSpacing)}x", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = ReaderTypography.clampLineSpacing(lineSpacing),
            onValueChange = onLineSpacingChange,
            valueRange = ReaderTypography.MIN_LINE_SPACING..ReaderTypography.MAX_LINE_SPACING,
            steps = ReaderTypography.LINE_SPACING_SLIDER_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "行距"
                    stateDescription = "${"%.2f".format(lineSpacing)}倍"
                },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReadingMode.entries.forEach { mode ->
                FilterChip(
                    selected = readingMode == mode,
                    enabled = mode in supportedModes,
                    onClick = { onModeChange(mode) },
                    label = { Text(mode.readerLabel(), maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun ReadingMode.readerLabel() = when (this) {
    ReadingMode.SCROLL -> "滚动"
    ReadingMode.PAGED -> "分页"
}

@Composable
private fun ThemePanel(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("主题", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.chunked(2).forEach { rowModes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowModes.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            label = { Text(mode.readerLabel(), maxLines = 1) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowModes.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun ThemeMode.readerLabel() = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "日间"
    ThemeMode.DARK -> "夜间"
    ThemeMode.SEPIA -> "护眼"
}

private class ReaderTapContainer(
    context: Context,
    private val onAction: (ReaderTapZone) -> Unit,
    private val onFontSizePreview: (Float) -> Unit,
    private val onZoomPreview: (Float) -> Unit,
) : FrameLayout(context) {
    private var documentView: View? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxTapDurationMs = ViewConfiguration.getLongPressTimeout()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                trackingTap = false
                activeScaleFactor = 1f
                activePinchMode = when {
                    isZoomPinchEnabled -> {
                        pinchStartZoomScale = currentZoomScale
                        PinchMode.ZOOM
                    }
                    isFontPinchEnabled -> {
                        pinchStartFontSizeSp = currentFontSizeSp
                        PinchMode.FONT
                    }
                    else -> PinchMode.NONE
                }
                return activePinchMode != PinchMode.NONE
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                activeScaleFactor *= detector.scaleFactor
                when (activePinchMode) {
                    PinchMode.FONT -> onFontSizePreview(
                        scaledReaderFontSize(pinchStartFontSizeSp, activeScaleFactor),
                    )
                    PinchMode.ZOOM -> onZoomPreview(
                        scaledReaderZoom(pinchStartZoomScale, activeScaleFactor),
                    )
                    PinchMode.NONE -> return false
                }
                return true
            }
        },
    )
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var trackingTap = false
    private var activePinchMode = PinchMode.NONE
    private var pinchStartFontSizeSp = 18f
    private var pinchStartZoomScale = 1f
    private var activeScaleFactor = 1f
    var currentFontSizeSp = 18f
    var currentZoomScale = 1f
    var isZoomPinchEnabled = false
        set(value) {
            field = value
            refreshContentDescription()
        }
    var isPagedTapZonesEnabled = false
    var isFontPinchEnabled = true
        set(value) {
            field = value
            refreshContentDescription()
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "阅读内容，捏合调整字号"
    }

    private fun refreshContentDescription() {
        contentDescription = when {
            isZoomPinchEnabled -> "阅读内容，捏合缩放页面"
            isFontPinchEnabled -> "阅读内容，捏合调整字号"
            else -> "阅读内容"
        }
    }

    fun setDocumentView(view: View) {
        if (documentView === view) return
        (view.parent as? ViewGroup)?.removeView(view)
        removeAllViews()
        addView(
            view,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
        documentView = view
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        var tapZoneRatio: Float? = null
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                documentView?.clearReaderInteractiveTapReports()
                trackingTap = true
                downTime = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dt = event.eventTime - downTime
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                val wasTrackingTap = trackingTap
                trackingTap = false
                activePinchMode = PinchMode.NONE
                if (wasTrackingTap && dt <= maxTapDurationMs && dx <= touchSlop && dy <= touchSlop && width > 0) {
                    tapZoneRatio = event.x / width.toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || scaleDetector.isInProgress) {
                    trackingTap = false
                }
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    trackingTap = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                trackingTap = false
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingTap = false
                activePinchMode = PinchMode.NONE
            }
        }
        val handled = super.dispatchTouchEvent(event)
        tapZoneRatio?.let { ratio ->
            val interactiveTapConsumed = documentView?.consumeReaderInteractiveTapReport() == true
            readerTapZoneForTap(
                xRatio = ratio,
                interactiveChildConsumedTap = interactiveTapConsumed,
                pagedTapZonesEnabled = isPagedTapZonesEnabled,
            )?.let(onAction)
        }
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = readerTapZoneForKey(event.keyCode, event.isShiftPressed) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            onAction(action)
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = FrameLayout::class.java.name
        if (isPagedTapZonesEnabled) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    "上一页",
                ),
            )
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    "下一页",
                ),
            )
        }
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_CLICK,
                "显示或隐藏阅读工具栏",
            ),
        )
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                if (!isPagedTapZonesEnabled) return super.performAccessibilityAction(action, arguments)
                onAction(ReaderTapZone.PreviousPage)
                true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                if (!isPagedTapZonesEnabled) return super.performAccessibilityAction(action, arguments)
                onAction(ReaderTapZone.NextPage)
                true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                onAction(ReaderTapZone.ToggleChrome)
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    private enum class PinchMode { NONE, FONT, ZOOM }
}

private fun View.clearReaderInteractiveTapReports() {
    setTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed, false)
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).clearReaderInteractiveTapReports()
        }
    }
}

private fun View.consumeReaderInteractiveTapReport(): Boolean {
    val tagId = dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed
    if (getTag(tagId) == true) {
        setTag(tagId, false)
        return true
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            if (getChildAt(index).consumeReaderInteractiveTapReport()) {
                return true
            }
        }
    }
    return false
}

@Composable
private fun ReaderErrorContent(
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "无法打开这本书",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "文件可能已被移动、删除或格式不受支持。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("返回书库")
            }
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun ReaderGestureGuideOverlay(
    pagedMode: Boolean,
    onDismiss: () -> Unit,
) {
    // 半透明遮罩 + 三区说明；点任意处关闭，仅首次展示
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss)
            .semantics(mergeDescendants = true) {
                contentDescription = "阅读手势引导，点击关闭"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            GestureGuideZone(
                weight = 1f,
                title = "←",
                desc = if (pagedMode) "上一页" else "上翻",
            )
            GestureGuideZone(
                weight = 1f,
                title = "☰",
                desc = "呼出菜单",
                emphasized = true,
            )
            GestureGuideZone(
                weight = 1f,
                title = "→",
                desc = if (pagedMode) "下一页" else "下翻",
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "捏合调字号 · 长按划选标注 · 拖底部进度条跳转",
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Text(
                "点击任意处开始阅读",
                style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RowScope.GestureGuideZone(
    weight: Float,
    title: String,
    desc: String,
    emphasized: Boolean = false,
) {
    Column(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            fontSize = if (emphasized) 40.sp else 48.sp,
            color = androidx.compose.ui.graphics.Color.White,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.titleMedium,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ReaderProgressSeekBar(
    progress: Float,
    progressDescription: String,
    onSeek: (Float) -> Unit,
) {
    // 拖动期间用本地值跟手，松手才提交跳转，避免每帧都触发引擎重排
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val displayValue = dragValue ?: progress
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = displayValue,
                onValueChange = { dragValue = it.coerceIn(0f, 1f) },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(it) }
                    dragValue = null
                },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "全书进度，拖动跳转"
                        stateDescription = if (dragValue != null) {
                            readerProgressPercentText(displayValue)
                        } else {
                            progressDescription
                        }
                    },
            )
            Text(
                text = readerProgressPercentText(displayValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .widthIn(min = 40.dp)
                    .padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ReaderMenuButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onBackground)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground)
        }
    }
}
