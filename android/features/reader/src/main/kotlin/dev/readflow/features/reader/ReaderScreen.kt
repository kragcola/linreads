package dev.readflow.features.reader

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.TransitionType
import dev.readflow.render.api.PagingKind
import kotlinx.coroutines.launch

/**
 * 阅读器菜单功能项 — debug 阶段全部默认显示。
 * 后续通过 feature flag / 用户配置选择性显示。
 */
enum class ReaderFeature(val label: String) {
    TOC("目录"),
    PROGRESS("进度"),
    FONT("字体"),
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
        when (val loading = state.loadingState) {
            is LoadingState.Loading, LoadingState.Idle ->
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            is LoadingState.Error ->
                Text("打开失败：${loading.error.message}", color = MaterialTheme.colorScheme.onBackground)
            LoadingState.Loaded -> {
                val engine = state.engine
                if (engine == null) {
                    Text("无内容", color = MaterialTheme.colorScheme.onBackground)
                } else {
                    val host = remember(engine) {
                        when (engine.pagingKind.value) {
                            PagingKind.CONTINUOUS -> viewModel.hostFactory.continuous()
                            PagingKind.PAGED -> viewModel.hostFactory.paged(TransitionType.SLIDE)
                        }.also { it.bind(engine) }
                    }

                    // Document view — observe taps in dispatch, then pass events through to the engine view.
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            ReaderTapContainer(ctx) { yRatio ->
                                if (yRatio in 0.33f..0.66f) {
                                    viewModel.onIntent(ReaderIntent.ToggleChrome)
                                }
                            }.apply {
                                addView(host.hostView())
                            }
                        },
                    )

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
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 全书总进度细条
                            LinearProgressIndicator(
                                progress = { (locator.totalProgression ?: 0f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                                    Text(
                                        "←",
                                        modifier = Modifier
                                            .clickable(enabled = chapter.currentIndex > 0) {
                                                scope.launch { engine.goToAdjacentChapter(-1) }
                                            }
                                            .padding(8.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (chapter.currentIndex > 0)
                                            MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
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
                                    Text(
                                        "→",
                                        modifier = Modifier
                                            .clickable(enabled = chapter.currentIndex < chapter.totalChapters - 1) {
                                                scope.launch { engine.goToAdjacentChapter(+1) }
                                            }
                                            .padding(8.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (chapter.currentIndex < chapter.totalChapters - 1)
                                            MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    )
                                }
                            }
                            // ── 分隔线 ──
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
                                        ReaderMenuButton("目录", Icons.Default.Menu) { /* TODO: TOC */ }
                                    }
                                    if (ReaderFeature.FONT in features) {
                                        ReaderMenuButton("字体", Icons.Default.Edit) {
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
                }
            }
        }
    }
}

private class ReaderTapContainer(
    context: Context,
    private val onTap: (yRatio: Float) -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxTapDurationMs = ViewConfiguration.getLongPressTimeout()
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var trackingTap = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var tapYRatio: Float? = null
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingTap = true
                downTime = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dt = event.eventTime - downTime
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (trackingTap && dt <= maxTapDurationMs && dx <= touchSlop && dy <= touchSlop && height > 0) {
                    tapYRatio = event.y / height.toFloat()
                }
                trackingTap = false
            }
            MotionEvent.ACTION_MOVE -> {
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
            }
        }
        val handled = super.dispatchTouchEvent(event)
        tapYRatio?.let(onTap)
        return handled
    }
}

@Composable
private fun ReaderMenuButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onBackground)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground)
        }
    }
}
