package dev.readflow.features.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.CircleShape
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.core.model.TransitionType
import dev.readflow.core.model.readerPaletteFor
import dev.readflow.core.model.readerThemeLabel
import dev.readflow.core.prefs.ReaderTypography
import dev.readflow.core.ui.AccessibleSlider
import dev.readflow.core.ui.FontProvider
import dev.readflow.core.ui.ReadflowColors
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelfPagingReaderEngine
import dev.readflow.render.api.ZoomableReaderEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

private val ReaderBottomChromeOuterPadding = 8.dp
private val ReaderPanelBaseOverlap = 12.dp

internal enum class ReaderHostKind { CONTINUOUS, PAGED }

internal fun readerHostKindFor(engine: ReaderEngine, pagingKind: PagingKind): ReaderHostKind =
    if ((engine as? SelfPagingReaderEngine)?.selfPagingActive == true) {
        ReaderHostKind.PAGED
    } else {
        when (pagingKind) {
            PagingKind.CONTINUOUS -> ReaderHostKind.CONTINUOUS
            PagingKind.PAGED -> ReaderHostKind.PAGED
        }
    }

@Composable
private fun ReaderSystemBarAppearance(themeMode: ThemeMode, systemNight: Boolean) {
    val view = LocalView.current
    val lightBars = !readerPaletteFor(themeMode, systemNight).isNight
    DisposableEffect(view, lightBars) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousStatusBarAppearance = controller.isAppearanceLightStatusBars
            val previousNavigationBarAppearance = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatusBarAppearance
                controller.isAppearanceLightNavigationBars = previousNavigationBarAppearance
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    features: Set<ReaderFeature> = ReaderFeature.entries.toSet(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val systemNight = isSystemInDarkTheme()
    val readerPaperColor = Color(readerPaletteFor(state.themeMode, systemNight).paper)
    ReaderSystemBarAppearance(state.themeMode, systemNight)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(readerPaperColor),
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
                    val hostKind = readerHostKindFor(engine, pagingKind)
                    val host = remember(engine, hostKind) {
                        when (hostKind) {
                            ReaderHostKind.CONTINUOUS -> viewModel.hostFactory.continuous()
                            ReaderHostKind.PAGED -> viewModel.hostFactory.paged(TransitionType.CURL)
                        }.also { it.bind(engine) }
                    }
                    DisposableEffect(host) {
                        onDispose { host.unbind() }
                    }
                    var bottomChromeHeightPx by remember { mutableIntStateOf(0) }
                    val bottomChromeHeightDp = with(LocalDensity.current) {
                        bottomChromeHeightPx.toDp()
                    }
                    val chromeOffsetPx = with(LocalDensity.current) {
                        ReaderMenuMotionPolicy.panelTranslationDp.dp.roundToPx()
                    }
                    val panelBottomPadding = (
                        bottomChromeHeightDp - ReaderBottomChromeOuterPadding - ReaderPanelBaseOverlap
                    ).coerceAtLeast(0.dp)

                    // Document view — observe taps in dispatch, then pass events through to the engine view.
                    val zoomableEngine = engine as? ZoomableReaderEngine
                    val zoomScaleFlow = remember(engine) {
                        zoomableEngine?.zoomScale ?: MutableStateFlow(1f)
                    }
                    val zoomScale by zoomScaleFlow.collectAsState()
                    val readerFocusRequester = remember { FocusRequester() }
                    fun handleReaderAction(action: ReaderTapZone) {
                        viewModel.onIntent(ReaderIntent.DismissGuide)
                        when (action) {
                            ReaderTapZone.PreviousPage -> scope.launch { host.previous() }
                            ReaderTapZone.ToggleChrome -> viewModel.onIntent(ReaderIntent.ToggleChrome)
                            ReaderTapZone.NextPage -> scope.launch { host.next() }
                        }
                    }
                    fun handleReaderKey(nativeEvent: KeyEvent): Boolean {
                        if (state.activePanel != null) return false
                        val action = readerTapZoneForKey(nativeEvent.keyCode, nativeEvent.isShiftPressed) ?: return false
                        if (nativeEvent.action == KeyEvent.ACTION_UP) {
                            handleReaderAction(action)
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
                                .onPreviewKeyEvent { handleReaderKey(it.nativeKeyEvent) }
                                .focusRequester(readerFocusRequester)
                                .focusable(),
                            factory = { ctx ->
                                ReaderTapContainer(
                                    context = ctx,
                                    onAction = ::handleReaderAction,
                                    onDocumentTapConsumed = {
                                        viewModel.onIntent(ReaderIntent.DismissGuide)
                                    },
                                    onFontSizePreview = {
                                        viewModel.onIntent(ReaderIntent.PreviewFontSize(it))
                                    },
                                    onZoomPreview = {
                                        viewModel.onIntent(ReaderIntent.PreviewZoom(it))
                                    },
                                ).apply {
                                    setReaderPaperBackground(state.themeMode, systemNight)
                                    setDocumentView(host.hostView())
                                    requestFocus()
                                }
                            },
                            update = { view ->
                                view.onAction = ::handleReaderAction
                                view.onDocumentTapConsumed = {
                                    viewModel.onIntent(ReaderIntent.DismissGuide)
                                }
                                view.setReaderPaperBackground(state.themeMode, systemNight)
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

                    // ── Compact floating top chrome ──
                    AnimatedVisibility(
                        visible = state.isUiVisible,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = slideInVertically(
                            animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
                            initialOffsetY = { -chromeOffsetPx },
                        ) + fadeIn(tween(ReaderMenuMotionPolicy.durationMillis)),
                        exit = slideOutVertically(
                            animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
                            targetOffsetY = { -chromeOffsetPx },
                        ) + fadeOut(tween(ReaderMenuMotionPolicy.durationMillis)),
                    ) {
                        Surface(
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp,
                            tonalElevation = 2.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                                }
                                Text(
                                    text = state.bookTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
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
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Keep the variable-height panel independent from the fixed base chrome.
                    ReaderControlPanel(
                        panel = state.activePanel.takeIf { state.isUiVisible },
                        fontSizeSp = state.fontSizeSp,
                        lineSpacing = state.lineSpacing,
                        fontChoice = state.fontChoice,
                        readingMode = state.readingMode,
                        supportedModes = state.supportedModes,
                        pageFlipStyle = state.pageFlipStyle,
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
                        onFontChoiceChange = { viewModel.onIntent(ReaderIntent.SetFontChoice(it)) },
                        onModeChange = { viewModel.onIntent(ReaderIntent.SetMode(it)) },
                        onPageFlipStyleChange = { viewModel.onIntent(ReaderIntent.SetPageFlipStyle(it)) },
                        onThemeChange = { viewModel.onIntent(ReaderIntent.SetTheme(it)) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(
                                WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal),
                            )
                            .padding(horizontal = 10.dp)
                            .padding(bottom = panelBottomPadding),
                    )

                    // ── Bottom chrome: stable compact control surface ──
                    AnimatedVisibility(
                        visible = state.isUiVisible,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { bottomChromeHeightPx = it.height },
                        enter = slideInVertically(
                            animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
                            initialOffsetY = { chromeOffsetPx },
                        ) + fadeIn(tween(ReaderMenuMotionPolicy.durationMillis)),
                        exit = slideOutVertically(
                            animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
                            targetOffsetY = { chromeOffsetPx },
                        ) + fadeOut(tween(ReaderMenuMotionPolicy.durationMillis)),
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
                        Surface(
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(
                                    horizontal = 10.dp,
                                    vertical = ReaderBottomChromeOuterPadding,
                                )
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 10.dp,
                            tonalElevation = 3.dp,
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = "${chapter.currentIndex + 1} / ${chapter.totalChapters} 章",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                ReaderProgressSeekBar(
                                    progress = (locator.totalProgression ?: 0f).coerceIn(0f, 1f),
                                    progressDescription = overallProgressDescription,
                                    onSeek = { viewModel.onIntent(ReaderIntent.SeekToProgress(it)) },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    if (ReaderFeature.TOC in features) {
                                        ReaderMenuButton("目录", Icons.Default.Menu, state.activePanel == ReaderPanel.TOC) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.TOC))
                                        }
                                    }
                                    if (ReaderFeature.SEARCH in features) {
                                        ReaderMenuButton("搜索", Icons.Default.Search, state.activePanel == ReaderPanel.SEARCH) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH))
                                        }
                                    }
                                    if (ReaderFeature.BOOKMARKS in features) {
                                        ReaderMenuButton("书签", Icons.Default.Edit, state.activePanel == ReaderPanel.BOOKMARKS) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.BOOKMARKS))
                                        }
                                    }
                                    if (ReaderFeature.ANNOTATIONS in features) {
                                        ReaderMenuButton("标注", Icons.Default.Edit, state.activePanel == ReaderPanel.ANNOTATIONS) {
                                            viewModel.onIntent(ReaderIntent.OpenPanel(ReaderPanel.ANNOTATIONS))
                                        }
                                    }
                                    if (ReaderFeature.FONT in features) {
                                        ReaderMenuButton("排版", Icons.Default.Edit, state.activePanel == ReaderPanel.FONT) {
                                            viewModel.onIntent(ReaderIntent.FontPanel)
                                        }
                                    }
                                    if (ReaderFeature.THEME in features) {
                                        ReaderMenuButton("主题", Icons.Default.MoreVert, state.activePanel == ReaderPanel.THEME) {
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
    fontChoice: FontChoice,
    readingMode: ReadingMode,
    supportedModes: Set<ReadingMode>,
    pageFlipStyle: dev.readflow.core.model.PageFlipStyle,
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
    onFontChoiceChange: (FontChoice) -> Unit,
    onModeChange: (ReadingMode) -> Unit,
    onPageFlipStyleChange: (dev.readflow.core.model.PageFlipStyle) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 目录改为左半屏抽屉（见 TocDrawer），底部面板不再承载 TOC。
    val panelOffsetPx = with(LocalDensity.current) {
        ReaderMenuMotionPolicy.panelTranslationDp.dp.roundToPx()
    }
    val motionState = remember { ReaderPanelMotionState() }
    val contentPanel = motionState.contentFor(panel)
    SideEffect {
        motionState.commit(panel)
    }
    AnimatedVisibility(
        visible = panel != null && panel != ReaderPanel.TOC,
        modifier = modifier,
        enter = fadeIn(tween(ReaderMenuMotionPolicy.durationMillis)) + slideInVertically(
            animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
            initialOffsetY = { panelOffsetPx },
        ),
        exit = fadeOut(tween(ReaderMenuMotionPolicy.durationMillis)) + slideOutVertically(
            animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
            targetOffsetY = { panelOffsetPx },
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                },
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
                    .padding(top = 4.dp),
            ) {
                when (contentPanel) {
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
                    ReaderPanel.FONT -> ReaderTypographyPanel(
                        fontSizeSp = fontSizeSp,
                        lineSpacing = lineSpacing,
                        fontChoice = fontChoice,
                        readingMode = readingMode,
                        supportedModes = supportedModes,
                        pageFlipStyle = pageFlipStyle,
                        onFontSizeChange = onFontSizeChange,
                        onLineSpacingChange = onLineSpacingChange,
                        onFontChoiceChange = onFontChoiceChange,
                        onModeChange = onModeChange,
                        onPageFlipStyleChange = onPageFlipStyleChange,
                    )
                    ReaderPanel.THEME -> ThemePanel(themeMode, onThemeChange)
                    null -> Unit
                }
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
        enter = fadeIn(tween(ReaderMenuMotionPolicy.durationMillis)),
        exit = fadeOut(tween(ReaderMenuMotionPolicy.durationMillis)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            // 独立遮罩层：TalkBack 与物理点击共享同一“关闭目录”动作，不包裹抽屉内容。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = "关闭目录"
                        onClick(label = "关闭目录") {
                            onDismiss()
                            true
                        }
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss,
                    ),
            )
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(
                    animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
                ) { -it } + fadeIn(tween(ReaderMenuMotionPolicy.durationMillis)),
                exit = slideOutHorizontally(
                    animationSpec = tween(ReaderMenuMotionPolicy.durationMillis),
                ) { -it } + fadeOut(tween(ReaderMenuMotionPolicy.durationMillis)),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .fillMaxHeight()
                        // 吞掉抽屉空白处点击，避免穿透；pointerInput 不生成无标签语义节点。
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {})
                        },
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
private fun ReaderTypographyPanel(
    fontSizeSp: Float,
    lineSpacing: Float,
    fontChoice: FontChoice,
    readingMode: ReadingMode,
    supportedModes: Set<ReadingMode>,
    pageFlipStyle: dev.readflow.core.model.PageFlipStyle,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onFontChoiceChange: (FontChoice) -> Unit,
    onModeChange: (ReadingMode) -> Unit,
    onPageFlipStyleChange: (dev.readflow.core.model.PageFlipStyle) -> Unit,
) {
    val clampedFontSize = ReaderTypography.clampFontSp(fontSizeSp)
    val clampedLineSpacing = ReaderTypography.clampLineSpacing(lineSpacing)
    val fontValue = readerFontSizeText(clampedFontSize)
    val lineSpacingValue = readerLineSpacingText(clampedLineSpacing)
    val context = LocalContext.current
    val availableFonts = remember(fontChoice) {
        FontProvider.availableChoices(FontProvider.listCustomFonts(context.applicationContext))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val useExpandedLayout = maxWidth >= 600.dp
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .heightIn(max = maxHeight)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (useExpandedLayout) 24.dp else 16.dp,
                    vertical = 10.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("排版", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "$fontValue · ${lineSpacingValue.removeSuffix("倍")}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (useExpandedLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    TypographyControls(
                        fontSizeSp = clampedFontSize,
                        lineSpacing = clampedLineSpacing,
                        fontChoice = fontChoice,
                        availableFonts = availableFonts,
                        onFontSizeChange = onFontSizeChange,
                        onLineSpacingChange = onLineSpacingChange,
                        onFontChoiceChange = onFontChoiceChange,
                        modifier = Modifier.weight(1.3f),
                    )
                    TypographyPreview(
                        fontSizeSp = clampedFontSize,
                        lineSpacing = clampedLineSpacing,
                        fontChoice = fontChoice,
                        modifier = Modifier.weight(0.7f),
                    )
                }
            } else {
                TypographyControls(
                    fontSizeSp = clampedFontSize,
                    lineSpacing = clampedLineSpacing,
                    fontChoice = fontChoice,
                    availableFonts = availableFonts,
                    onFontSizeChange = onFontSizeChange,
                    onLineSpacingChange = onLineSpacingChange,
                    onFontChoiceChange = onFontChoiceChange,
                )
                TypographyPreview(
                    fontSizeSp = clampedFontSize,
                    lineSpacing = clampedLineSpacing,
                    fontChoice = fontChoice,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            ReaderOptionRow(label = "阅读模式") {
                ReadingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = readingMode == mode,
                        enabled = mode in supportedModes,
                        onClick = { onModeChange(mode) },
                        label = { Text(mode.readerLabel(), maxLines = 1) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    )
                }
            }

            if (readingMode == ReadingMode.PAGED) {
                ReaderOptionRow(label = "翻页动画") {
                    dev.readflow.core.model.PageFlipStyle.entries.forEach { style ->
                        FilterChip(
                            selected = pageFlipStyle == style,
                            onClick = { onPageFlipStyleChange(style) },
                            label = { Text(style.readerLabel(), maxLines = 1) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypographyControls(
    fontSizeSp: Float,
    lineSpacing: Float,
    fontChoice: FontChoice,
    availableFonts: List<FontChoice>,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onFontChoiceChange: (FontChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TypographyValueControl(
            label = "字号",
            value = fontSizeSp,
            valueText = readerFontSizeText(fontSizeSp),
            valueDescription = readerFontSizeText(fontSizeSp),
            valueRange = ReaderTypography.MIN_FONT_SP..ReaderTypography.MAX_FONT_SP,
            steps = ReaderTypography.FONT_SLIDER_STEPS,
            onValueChange = onFontSizeChange,
            onDecrease = { onFontSizeChange(steppedReaderFontSize(fontSizeSp, -1)) },
            onIncrease = { onFontSizeChange(steppedReaderFontSize(fontSizeSp, 1)) },
            decreaseEnabled = fontSizeSp > ReaderTypography.MIN_FONT_SP,
            increaseEnabled = fontSizeSp < ReaderTypography.MAX_FONT_SP,
        )
        TypographyValueControl(
            label = "行距",
            value = lineSpacing,
            valueText = "${readerLineSpacingText(lineSpacing).removeSuffix("倍")}x",
            valueDescription = readerLineSpacingText(lineSpacing),
            valueRange = ReaderTypography.MIN_LINE_SPACING..ReaderTypography.MAX_LINE_SPACING,
            steps = ReaderTypography.LINE_SPACING_SLIDER_STEPS,
            onValueChange = onLineSpacingChange,
            onDecrease = { onLineSpacingChange(steppedReaderLineSpacing(lineSpacing, -1)) },
            onIncrease = { onLineSpacingChange(steppedReaderLineSpacing(lineSpacing, 1)) },
            decreaseEnabled = lineSpacing > ReaderTypography.MIN_LINE_SPACING,
            increaseEnabled = lineSpacing < ReaderTypography.MAX_LINE_SPACING,
        )
        ReaderFontSelector(
            fontChoice = fontChoice,
            availableFonts = availableFonts,
            onFontChoiceChange = onFontChoiceChange,
        )
    }
}

@Composable
private fun ReaderFontSelector(
    fontChoice: FontChoice,
    availableFonts: List<FontChoice>,
    onFontChoiceChange: (FontChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("字体", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            availableFonts.forEach { choice ->
                val label = FontProvider.label(choice)
                val selected = fontChoice == choice
                FilterChip(
                    selected = selected,
                    onClick = { onFontChoiceChange(choice) },
                    label = {
                        Text(
                            text = label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "正文字体，$label"
                            stateDescription = if (selected) "已选择" else "未选择"
                        },
                )
            }
        }
    }
}

@Composable
private fun TypographyValueControl(
    label: String,
    value: Float,
    valueText: String,
    valueDescription: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            TypographyStepper(
                label = label,
                valueText = valueText,
                valueDescription = valueDescription,
                onDecrease = onDecrease,
                onIncrease = onIncrease,
                decreaseEnabled = decreaseEnabled,
                increaseEnabled = increaseEnabled,
            )
        }
        AccessibleSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            label = label,
            valueDescription = valueDescription,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TypographyStepper(
    label: String,
    valueText: String,
    valueDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onDecrease,
            enabled = decreaseEnabled,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = readerTypographyStepDescription(label, valueDescription, increase = false)
                },
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .widthIn(min = 56.dp)
                .heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically)
                .clearAndSetSemantics {
                    contentDescription = "$label，当前 $valueDescription"
                    stateDescription = valueDescription
                },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
        IconButton(
            onClick = onIncrease,
            enabled = increaseEnabled,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = readerTypographyStepDescription(label, valueDescription, increase = true)
                },
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
    }
}

@Composable
private fun TypographyPreview(
    fontSizeSp: Float,
    lineSpacing: Float,
    fontChoice: FontChoice,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fontFamily = remember(fontChoice, context) {
        FontProvider.fontFamilyFor(context.applicationContext, fontChoice.serialize())
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "正文预览",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "海上生明月，天涯共此时。\nReading begins in the details.",
            fontSize = fontSizeSp.sp,
            lineHeight = readerTypographyPreviewLineHeightSp(fontSizeSp, lineSpacing).sp,
            fontFamily = fontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

@Composable
private fun ReaderOptionRow(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

internal fun steppedReaderFontSize(currentSp: Float, direction: Int): Float {
    val currentStep = ReaderTypography.clampFontSp(currentSp).roundToInt()
    return ReaderTypography.clampFontSp((currentStep + direction.coerceIn(-1, 1)).toFloat())
}

internal fun steppedReaderLineSpacing(current: Float, direction: Int): Float {
    val currentStep = (ReaderTypography.clampLineSpacing(current) * 10f).roundToInt()
    return ReaderTypography.clampLineSpacing((currentStep + direction.coerceIn(-1, 1)) / 10f)
}

internal fun readerTypographyPreviewLineHeightSp(fontSizeSp: Float, lineSpacing: Float): Float =
    ReaderTypography.clampFontSp(fontSizeSp) * ReaderTypography.clampLineSpacing(lineSpacing)

internal fun readerTypographyStepDescription(label: String, value: String, increase: Boolean): String =
    "${if (increase) "增大" else "减小"}$label，当前 $value"

private fun readerFontSizeText(fontSizeSp: Float): String =
    "${ReaderTypography.clampFontSp(fontSizeSp).roundToInt()}sp"

private fun readerLineSpacingText(lineSpacing: Float): String {
    val tenths = (ReaderTypography.clampLineSpacing(lineSpacing) * 10f).roundToInt()
    return "${tenths / 10}.${tenths % 10}倍"
}

private fun dev.readflow.core.model.PageFlipStyle.readerLabel() = when (this) {
    dev.readflow.core.model.PageFlipStyle.SLIDE -> "滑动"
    dev.readflow.core.model.PageFlipStyle.SIMULATION -> "仿真"
    dev.readflow.core.model.PageFlipStyle.NONE -> "无"
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
                            label = { Text(mode.readerThemeLabel(), maxLines = 1) },
                            leadingIcon = { ThemeSwatch(mode) },
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

/** A small filled circle showing the preset's page colour, so 10 presets stay distinguishable. */
@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val systemNight = isSystemInDarkTheme()
    val palette = remember(mode, systemNight) { readerPaletteFor(mode, systemNight) }
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(Color(palette.paper))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape),
    )
}

private class ReaderTapContainer(
    context: Context,
    var onAction: (ReaderTapZone) -> Unit,
    var onDocumentTapConsumed: () -> Unit,
    private val onFontSizePreview: (Float) -> Unit,
    private val onZoomPreview: (Float) -> Unit,
) : FrameLayout(context) {
    private var documentView: View? = null
    private var paperBackgroundKey: Pair<ThemeMode, Boolean>? = null
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
        // Paper surface full-bleeds behind status/nav bars. Readable content safety is applied
        // to the document child (margins), never as padding on this paper owner.
        clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            if (
                view.paddingLeft != 0 ||
                view.paddingTop != 0 ||
                view.paddingRight != 0 ||
                view.paddingBottom != 0
            ) {
                view.setPadding(0, 0, 0, 0)
            }
            applyDocumentContentInsets(
                left = safeInsets.left,
                top = safeInsets.top,
                right = safeInsets.right,
                bottom = safeInsets.bottom,
            )
            insets
        }
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "阅读内容，捏合调整字号"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    /**
     * Inset only the engine document host so first-line / edge content clears system icons.
     * Paper stays on this FrameLayout and continues edge-to-edge under the bars.
     */
    private fun applyDocumentContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val child = documentView ?: return
        val lp = child.layoutParams as? LayoutParams ?: return
        if (
            lp.leftMargin != left ||
            lp.topMargin != top ||
            lp.rightMargin != right ||
            lp.bottomMargin != bottom
        ) {
            lp.setMargins(left, top, right, bottom)
            child.layoutParams = lp
        }
    }

    private fun refreshContentDescription() {
        contentDescription = when {
            isZoomPinchEnabled -> "阅读内容，捏合缩放页面"
            isFontPinchEnabled -> "阅读内容，捏合调整字号"
            else -> "阅读内容"
        }
    }

    fun setReaderPaperBackground(themeMode: ThemeMode, systemNight: Boolean) {
        val key = themeMode to systemNight
        if (paperBackgroundKey == key) return
        val palette = readerPaletteFor(themeMode, systemNight)
        background = readerPaperBackground(context, palette.paper, palette.ink, palette.isNight)
        paperBackgroundKey = key
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
        // Document host changed — re-apply content-layer insets without padding the paper.
        ViewCompat.requestApplyInsets(this)
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
            if (interactiveTapConsumed) {
                onDocumentTapConsumed()
            }
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
    // 半透明遮罩 + 三区说明；不拦截触摸，第一次真实阅读动作会同时关闭引导并执行。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "阅读手势引导，点击开始阅读"
                onClick(label = "开始阅读") {
                    onDismiss()
                    true
                }
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
                color = ReadflowColors.InkNight,
            )
            Text(
                "点击任意处开始阅读",
                style = MaterialTheme.typography.labelMedium,
                color = ReadflowColors.InkNight.copy(alpha = 0.72f),
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
            color = ReadflowColors.InkNight,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.titleMedium,
            color = ReadflowColors.InkNight.copy(alpha = 0.86f),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            AccessibleSlider(
                value = displayValue,
                onValueChange = { dragValue = it.coerceIn(0f, 1f) },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(it) }
                    dragValue = null
                },
                valueRange = 0f..1f,
                steps = 0,
                label = "全书进度，拖动跳转",
                valueDescription = if (dragValue != null) {
                    readerProgressPercentText(displayValue)
                } else {
                    progressDescription
                },
                modifier = Modifier.weight(1f),
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

@Composable
private fun ReaderMenuButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (selected) stateDescription = "已打开"
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface)
        }
    }
}
