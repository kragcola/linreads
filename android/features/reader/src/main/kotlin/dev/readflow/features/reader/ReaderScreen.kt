package dev.readflow.features.reader

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.readflow.core.model.LoadingState
import dev.readflow.core.model.TransitionType
import dev.readflow.render.api.PagingKind

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

                    // Document view — tap toggles chrome
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { viewModel.onIntent(ReaderIntent.ToggleChrome) }
                            },
                        factory = { ctx -> FrameLayout(ctx).apply { addView(host.hostView()) } },
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

                    // ── Bottom chrome: 进度条 + 功能菜单 ──
                    AnimatedVisibility(
                        visible = state.isUiVisible,
                        modifier = Modifier.align(Alignment.BottomStart),
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        val locator by engine.currentLocator.collectAsState()
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 细进度条
                            LinearProgressIndicator(
                                progress = { (locator.totalProgression ?: 0f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            // 功能菜单栏
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                if (ReaderFeature.TOC in features) {
                                    ReaderMenuButton("目录", Icons.Default.List) {
                                        // TODO: TOC panel
                                    }
                                }
                                if (ReaderFeature.PROGRESS in features) {
                                    val pct = ((locator.totalProgression ?: 0f) * 100).toInt()
                                    ReaderMenuButton("${pct}%", Icons.Default.Timeline) {
                                        // TODO: Go to position
                                    }
                                }
                                if (ReaderFeature.FONT in features) {
                                    ReaderMenuButton("字体", Icons.Default.FormatSize) {
                                        viewModel.onIntent(ReaderIntent.FontPanel)
                                    }
                                }
                                if (ReaderFeature.THEME in features) {
                                    ReaderMenuButton("主题", Icons.Default.Menu) {
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
