package dev.readflow.core.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.BookRemovalMode
import dev.readflow.core.model.DownloadStatus
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

private val LibraryItem.key: String
    get() = when (this) {
        is LibraryItem.Single -> "book:${book.id}"
        is LibraryItem.Bundle -> "bundle:${bundle.id}"
    }

internal fun libraryItemLabel(item: LibraryItem): String? = when (item) {
    is LibraryItem.Single -> null
    is LibraryItem.Bundle -> item.bundle.name
}

internal fun retainContextMenuAnchor(
    anchorKey: String?,
    currentItemKeys: Collection<String>,
): String? = anchorKey?.takeIf(currentItemKeys::contains)

private val BookMeta.canRemoveDownload: Boolean
    get() = id.startsWith("calibre-") &&
        downloadStatus == DownloadStatus.DOWNLOADED &&
        localUri != null

private val BookMeta.isOfflineReadable: Boolean
    get() = localUri != null &&
        (!id.startsWith("calibre-") || downloadStatus == DownloadStatus.DOWNLOADED)

private const val LibraryExpandedGapMinWidthDp = 1_020f

private fun libraryGridGapDp(widthDp: Float): Float = when {
    widthDp < 600f -> Dimens.gridGapCompact.value
    widthDp < LibraryExpandedGapMinWidthDp -> Dimens.gridGapMedium.value
    else -> Dimens.gridGapExpanded.value
}

private fun libraryCoverMinWidthDp(widthDp: Float): Float =
    if (widthDp < 600f) Dimens.coverMinWidthCompact.value else Dimens.coverMinWidth.value

internal data class LibraryGridLayout(
    val effectiveWidthDp: Float,
    val gapDp: Float,
    val columns: Int,
    val coverWidthDp: Float,
)

internal fun libraryGridLayout(widthDp: Float): LibraryGridLayout {
    val effectiveWidth = widthDp.coerceAtMost(Dimens.maxContentWidth.value)
    val gap = libraryGridGapDp(effectiveWidth)
    val usableWidth = (effectiveWidth - Dimens.screenEdge.value * 2f).coerceAtLeast(0f)
    val columns = floor((usableWidth + gap) / (libraryCoverMinWidthDp(effectiveWidth) + gap))
        .toInt()
        .coerceAtLeast(2)
    val coverWidth = (usableWidth - gap * (columns - 1)) / columns
    return LibraryGridLayout(
        effectiveWidthDp = effectiveWidth,
        gapDp = gap,
        columns = columns,
        coverWidthDp = coverWidth.coerceAtLeast(0f),
    )
}

internal fun libraryGridColumns(widthDp: Float): Int = libraryGridLayout(widthDp).columns

/**
 * 书架网格，支持点击、拖拽重排和封面重合建组预览。
 *
 * **手势设计 v3**：
 * - 点击 → 打开书
 * - ⋮ 菜单 → 改名/建组/删除
 * - 长按拖动越过目标中心 → 实时换位预览，松手生效
 * - 拖动封面与目标四边近乎重合 → 立即显示建组预览，松手提交
 * - 拖到上下边缘 → 逐帧连续自动滚动；手势取消则恢复原顺序
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGrid(
    items: List<LibraryItem>,
    onItemClick: (LibraryItem) -> Unit,
    onDelete: (String, BookRemovalMode) -> Unit = { _, _ -> },
    onDeleteBundle: (String, BookRemovalMode) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onMoveToGroup: (String, String, (Boolean) -> Unit) -> Unit = { _, _, onComplete -> onComplete(true) },
    onCreateGroup: (String, String, String) -> Unit = { _, _, _ -> },
    onReorder: (List<LibraryItem>, (Boolean) -> Unit) -> Unit = { _, onComplete -> onComplete(true) },
    onUngroup: (String, (Boolean) -> Unit) -> Unit = { _, onComplete -> onComplete(true) },
    onRenameBundle: (String, String, (Boolean) -> Unit) -> Unit = { _, _, onComplete -> onComplete(true) },
    onRemoveDownload: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val screenWidthDp = with(LocalDensity.current) { windowWidthPx.toDp().value }
    val gridLayout = libraryGridLayout(screenWidthDp)
    val density = LocalDensity.current
    var mutableItems by remember { mutableStateOf(items) }
    val gridState = rememberLazyGridState()

    // ── 拖动状态 ──
    var dragItemKey by remember { mutableStateOf("") }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartAbsPos by remember { mutableStateOf(Offset.Zero) }
    var dragInitialItemOffset by remember { mutableStateOf(Offset.Zero) }
    var dragCoverWidthPx by remember { mutableFloatStateOf(0f) }
    var dragStartOrder by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var mergeTargetKey by remember { mutableStateOf("") }
    var autoScrollIntent by remember { mutableFloatStateOf(0f) }
    var dragGeneration by remember { mutableIntStateOf(0) }
    var settlingItemKey by remember { mutableStateOf("") }
    var settlingOffset by remember { mutableStateOf(Offset.Zero) }
    var settlingStartScale by remember { mutableFloatStateOf(1f) }
    var settlingStartAlpha by remember { mutableFloatStateOf(1f) }
    var settlingStartShadowPx by remember { mutableFloatStateOf(0f) }
    var settlingTarget by remember { mutableFloatStateOf(0f) }
    var pendingItemsSnapshot by remember { mutableStateOf<List<LibraryItem>?>(null) }
    var latestItemsSnapshot by remember { mutableStateOf(items) }
    var itemsRevision by remember { mutableIntStateOf(0) }
    var shelfMutationInFlight by remember { mutableStateOf(false) }
    val settlingProgress by animateFloatAsState(
        targetValue = settlingTarget,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "shelf-drag-settle",
        finishedListener = { value ->
            if (value >= 1f) {
                settlingItemKey = ""
                settlingOffset = Offset.Zero
                settlingStartShadowPx = 0f
                settlingTarget = 0f
            }
        },
    )

    fun replaceMutableItems(snapshot: List<LibraryItem>) {
        if (mutableItems != snapshot) mutableItems = snapshot
    }

    LaunchedEffect(items) {
        latestItemsSnapshot = items
        itemsRevision++
        if (dragItemKey.isNotEmpty() || settlingItemKey.isNotEmpty() || shelfMutationInFlight) {
            pendingItemsSnapshot = items
        } else {
            replaceMutableItems(items)
        }
    }

    LaunchedEffect(dragItemKey, settlingItemKey, shelfMutationInFlight) {
        if (dragItemKey.isEmpty() && settlingItemKey.isEmpty() && !shelfMutationInFlight) {
            pendingItemsSnapshot?.let(::replaceMutableItems)
            pendingItemsSnapshot = null
        }
    }

    val mergeTolerancePx = with(density) { 20.dp.toPx() }
    val scrollEdgeThresholdPx = with(density) { 72.dp.toPx() }
    val maxAutoScrollPxPerSecond = with(density) { 960.dp.toPx() }

    // 菜单/对话框状态
    var contextItem by remember { mutableStateOf<LibraryItem?>(null) }
    var contextMenuAnchorKey by remember { mutableStateOf<String?>(null) }
    var renameItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var renameText by remember { mutableStateOf("") }
    var groupSourceId by remember { mutableStateOf("") }
    var groupTargetItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var groupName by remember { mutableStateOf("") }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmIsBundle by remember { mutableStateOf(false) }
    var deleteMode by remember { mutableStateOf(BookRemovalMode.REMOVE_FROM_SHELF) }
    var deleteConfirmLabel by remember { mutableStateOf("") }
    var ungroupConfirmId by remember { mutableStateOf<String?>(null) }
    var ungroupConfirmName by remember { mutableStateOf("") }
    var renameBundleId by remember { mutableStateOf<String?>(null) }
    var renameBundleName by remember { mutableStateOf("") }

    LaunchedEffect(mutableItems) {
        val retainedAnchor = retainContextMenuAnchor(
            anchorKey = contextMenuAnchorKey,
            currentItemKeys = mutableItems.map(LibraryItem::key),
        )
        if (retainedAnchor != contextMenuAnchorKey) {
            contextMenuAnchorKey = retainedAnchor
            contextItem = null
        }
    }

    fun visibleDropCandidates(): List<GridDropCandidate> =
        gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            if (info.index !in mutableItems.indices) return@mapNotNull null
            val left = info.offset.x.toFloat()
            val top = info.offset.y.toFloat()
            val width = info.size.width.toFloat()
            GridDropCandidate(
                index = info.index,
                rect = GridDragRect(
                    left = left,
                    top = top,
                    right = left + width,
                    bottom = top + width / Dimens.coverAspectRatio,
                ),
            )
        }

    fun currentDraggedRect(): GridDragRect? {
        if (dragItemKey.isEmpty() || dragCoverWidthPx <= 0f) return null
        val left = dragInitialItemOffset.x + dragOffset.x
        val top = dragInitialItemOffset.y + dragOffset.y
        return GridDragRect(
            left = left,
            top = top,
            right = left + dragCoverWidthPx,
            bottom = top + dragCoverWidthPx / Dimens.coverAspectRatio,
        )
    }

    /** Updates merge preview first, then live order only after the drag passes the target centre. */
    fun updateDragTargets(): Boolean {
        val draggedRect = currentDraggedRect() ?: return false
        val sourceIndex = mutableItems.indexOfFirst { it.key == dragItemKey }
        if (sourceIndex < 0) return false
        val candidates = visibleDropCandidates()
        val sourceItem = mutableItems[sourceIndex]
        val mergeIndex = if (sourceItem is LibraryItem.Single) {
            resolveMergeCandidate(
                sourceIndex = sourceIndex,
                draggedRect = draggedRect,
                candidates = candidates,
                tolerancePx = mergeTolerancePx,
            )
        } else {
            null
        }
        mergeTargetKey = mergeIndex?.let { mutableItems.getOrNull(it)?.key }.orEmpty()
        if (mergeTargetKey.isNotEmpty()) return false

        val insertionSlot = resolveReorderInsertionSlot(
            sourceIndex = sourceIndex,
            draggedRect = draggedRect,
            candidates = candidates,
            totalDragX = dragOffset.x,
            totalDragY = dragOffset.y,
            thresholdPx = mergeTolerancePx,
        ) ?: resolveTrailingInsertionSlot(
            sourceIndex = sourceIndex,
            draggedRect = draggedRect,
            candidates = candidates,
            itemCount = mutableItems.size,
            totalDragX = dragOffset.x,
            totalDragY = dragOffset.y,
        )
        if (insertionSlot == null) return false

        val reordered = moveItemToInsertionSlot(
            items = mutableItems.toList(),
            sourceIndex = sourceIndex,
            insertionSlot = insertionSlot,
        )
        if (reordered == mutableItems.toList()) return false
        mutableItems = reordered
        return true
    }

    fun resetDragState() {
        dragItemKey = ""
        dragOffset = Offset.Zero
        dragStartAbsPos = Offset.Zero
        dragInitialItemOffset = Offset.Zero
        dragCoverWidthPx = 0f
        dragStartOrder = emptyList()
        mergeTargetKey = ""
        autoScrollIntent = 0f
        dragGeneration++
    }

    fun beginSettling(itemKey: String, finalOrder: List<LibraryItem>) {
        val finalIndex = finalOrder.indexOfFirst { it.key == itemKey }
        if (finalIndex < 0) return
        val finalInfo = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == finalIndex }
            ?: return
        val visualTopLeft = dragInitialItemOffset + dragOffset
        val finalTopLeft = Offset(finalInfo.offset.x.toFloat(), finalInfo.offset.y.toFloat())
        settlingItemKey = itemKey
        settlingOffset = visualTopLeft - finalTopLeft
        settlingStartScale = if (mergeTargetKey.isNotEmpty()) 0.84f else 1.04f
        settlingStartAlpha = if (mergeTargetKey.isNotEmpty()) 0.82f else 0.96f
        settlingStartShadowPx = if (mergeTargetKey.isNotEmpty()) {
            0f
        } else {
            with(density) { 12.dp.toPx() }
        }
        settlingTarget = 1f
    }

    LaunchedEffect(dragItemKey) {
        if (dragItemKey.isEmpty()) return@LaunchedEffect
        var previousFrameNanos = withFrameNanos { it }
        var edgeStartNanos = previousFrameNanos
        var previousDirection = 0f
        while (currentCoroutineContext().isActive && dragItemKey.isNotEmpty()) {
            val frameNanos = withFrameNanos { it }
            val intent = autoScrollIntent
            val direction = intent.sign
            if (direction == 0f) {
                previousDirection = 0f
                edgeStartNanos = frameNanos
                previousFrameNanos = frameNanos
                continue
            }
            if (direction != previousDirection) {
                previousDirection = direction
                edgeStartNanos = frameNanos
            }
            val deltaSeconds = ((frameNanos - previousFrameNanos) / 1_000_000_000f)
                .coerceIn(0f, 0.05f)
            previousFrameNanos = frameNanos
            val ramp = ((frameNanos - edgeStartNanos) / 2_000_000_000f).coerceIn(0f, 1f)
            val rampedSpeed = 0.35f + 0.65f * (1f - (1f - ramp).pow(3f))
            val distanceFactor = abs(intent).pow(1.35f)
            val consumed = gridState.scrollBy(
                maxAutoScrollPxPerSecond * direction * distanceFactor * rampedSpeed * deltaSeconds,
            )
            if (abs(consumed) > 0.1f) updateDragTargets()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(gridLayout.columns),
            contentPadding = PaddingValues(
                start = Dimens.screenEdge,
                top = Dimens.spaceMd,
                end = Dimens.screenEdge,
                bottom = Dimens.spaceXl,
            ),
            horizontalArrangement = Arrangement.spacedBy(gridLayout.gapDp.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.gridRowGap),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Dimens.maxContentWidth)
                .fillMaxWidth(),
        ) {
            itemsIndexed(mutableItems, key = { _, item -> item.key }) { _, item ->
                val isDragging = dragItemKey == item.key
                val isMergeTarget = mergeTargetKey == item.key && dragItemKey.isNotEmpty() && dragItemKey != item.key
                val isSettling = settlingItemKey == item.key
                val draggedSingle = mutableItems.firstOrNull { it.key == dragItemKey } as? LibraryItem.Single
                val view = LocalView.current
                val targetScale by animateFloatAsState(
                    targetValue = if (isMergeTarget) 0.96f else 1f,
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                    label = "shelf-merge-target-scale",
                )
                val draggedScale by animateFloatAsState(
                    targetValue = when {
                        !isDragging -> 1f
                        mergeTargetKey.isNotEmpty() -> 0.84f
                        else -> 1.04f
                    },
                    animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                    label = "shelf-drag-scale",
                )
                val openDescription = when (item) {
                    is LibraryItem.Single -> "打开 ${item.book.title}"
                    is LibraryItem.Bundle -> "打开书组 ${item.bundle.name}，共 ${item.bundle.books.size} 本"
                }
                val itemStateDescription = when (item) {
                    is LibraryItem.Single -> buildList {
                        if (item.book.progress > 0f) {
                            add("阅读进度 ${(item.book.progress.coerceIn(0f, 1f) * 100f).roundToInt()}%")
                        }
                        if (item.book.isOfflineReadable) add("可离线")
                    }.joinToString("，").ifBlank { null }
                    is LibraryItem.Bundle -> null
                }
                val menuDescription = when (item) {
                    is LibraryItem.Single -> "${item.book.title} 的菜单"
                    is LibraryItem.Bundle -> "书组 ${item.bundle.name} 的菜单"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(when {
                            isDragging -> 3f
                            isSettling -> 2f
                            isMergeTarget -> 1f
                            else -> 0f
                        })
                        .animateItem(
                            fadeInSpec = null,
                            placementSpec = if (isDragging) {
                                snap<IntOffset>()
                            } else {
                                tween<IntOffset>(durationMillis = 240, easing = FastOutSlowInEasing)
                            },
                            fadeOutSpec = null,
                        )
                        .graphicsLayer {
                            if (isDragging) {
                                val dragInfo = gridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { mutableItems.getOrNull(it.index)?.key == dragItemKey }
                                val cur = if (dragInfo != null) {
                                    Offset(dragInfo.offset.x.toFloat(), dragInfo.offset.y.toFloat())
                                } else {
                                    dragInitialItemOffset
                                }
                                val vo = dragOffset + (dragInitialItemOffset - cur)
                                translationX = vo.x
                                translationY = vo.y
                                scaleX = draggedScale
                                scaleY = draggedScale
                                alpha = if (mergeTargetKey.isNotEmpty()) 0.82f else 0.96f
                                shadowElevation = if (mergeTargetKey.isNotEmpty()) 0f else 12.dp.toPx()
                            } else if (isSettling) {
                                val remaining = 1f - settlingProgress
                                translationX = settlingOffset.x * remaining
                                translationY = settlingOffset.y * remaining
                                scaleX = 1f + (settlingStartScale - 1f) * remaining
                                scaleY = 1f + (settlingStartScale - 1f) * remaining
                                alpha = 1f - (1f - settlingStartAlpha) * remaining
                                shadowElevation = settlingStartShadowPx * remaining
                            } else {
                                translationX = 0f
                                translationY = 0f
                                scaleX = targetScale
                                scaleY = targetScale
                                alpha = 1f
                                shadowElevation = if (isMergeTarget) 6.dp.toPx() else 0f
                            }
                        }
                        .semantics {
                            contentDescription = openDescription
                            itemStateDescription?.let { stateDescription = it }
                        }
                        .clickable(
                            enabled = dragItemKey.isEmpty() &&
                                settlingItemKey.isEmpty() &&
                                !shelfMutationInFlight,
                            role = Role.Button,
                        ) { onItemClick(item) }
                        .pointerInput(item.key, dragGeneration) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { localPos ->
                                    if (dragItemKey.isEmpty() && !shelfMutationInFlight) {
                                        val itemInfo = gridState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { info ->
                                                mutableItems.getOrNull(info.index)?.key == item.key
                                            } ?: return@detectDragGesturesAfterLongPress
                                        dragItemKey = item.key
                                        settlingItemKey = ""
                                        settlingTarget = 0f
                                        dragOffset = Offset.Zero
                                        dragStartOrder = mutableItems.toList()
                                        mergeTargetKey = ""
                                        autoScrollIntent = 0f
                                        contextMenuAnchorKey = null
                                        dragStartAbsPos = Offset(
                                            itemInfo.offset.x.toFloat() + localPos.x,
                                            itemInfo.offset.y.toFloat() + localPos.y,
                                        )
                                        dragInitialItemOffset = Offset(
                                            itemInfo.offset.x.toFloat(),
                                            itemInfo.offset.y.toFloat(),
                                        )
                                        dragCoverWidthPx = itemInfo.size.width.toFloat()
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }
                                },
                                onDrag = { change, delta ->
                                    if (dragItemKey != item.key) return@detectDragGesturesAfterLongPress
                                    dragOffset += delta
                                    change.consume()

                                    val absPos = dragStartAbsPos + dragOffset
                                    val viewportStart = gridState.layoutInfo.viewportStartOffset.toFloat()
                                    val viewportEnd = gridState.layoutInfo.viewportEndOffset.toFloat()
                                    autoScrollIntent = when {
                                        absPos.y < viewportStart + scrollEdgeThresholdPx ->
                                            -((viewportStart + scrollEdgeThresholdPx - absPos.y) /
                                                scrollEdgeThresholdPx).coerceIn(0f, 1f)
                                        absPos.y > viewportEnd - scrollEdgeThresholdPx ->
                                            ((absPos.y - (viewportEnd - scrollEdgeThresholdPx)) /
                                                scrollEdgeThresholdPx).coerceIn(0f, 1f)
                                        else -> 0f
                                    }

                                    val previousMergeTarget = mergeTargetKey
                                    val reordered = updateDragTargets()
                                    if ((mergeTargetKey.isNotEmpty() && mergeTargetKey != previousMergeTarget) || reordered) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                },
                                onDragEnd = {
                                    val startOrder = dragStartOrder.ifEmpty { items }
                                    val sourceKey = dragItemKey
                                    val source = mutableItems.firstOrNull { it.key == dragItemKey }
                                    val target = mutableItems.firstOrNull { it.key == mergeTargetKey }
                                    if (source is LibraryItem.Single && target != null) {
                                        mutableItems = startOrder
                                        when (target) {
                                            is LibraryItem.Bundle -> {
                                                val authoritativeBase = pendingItemsSnapshot ?: startOrder
                                                pendingItemsSnapshot = null
                                                val sourceIndex = authoritativeBase.indexOfFirst { it.key == source.key }
                                                val targetIndex = authoritativeBase.indexOfFirst { it.key == target.key }
                                                val latestSource = authoritativeBase.getOrNull(sourceIndex)
                                                    as? LibraryItem.Single
                                                val latestTarget = authoritativeBase.getOrNull(targetIndex)
                                                    as? LibraryItem.Bundle
                                                if (latestSource == null || latestTarget == null) {
                                                    replaceMutableItems(authoritativeBase)
                                                } else {
                                                    val optimisticItems = authoritativeBase.toMutableList()
                                                    optimisticItems[targetIndex] = latestTarget.copy(
                                                        bundle = latestTarget.bundle.copy(
                                                            books = latestTarget.bundle.books + latestSource.book,
                                                        ),
                                                    )
                                                    optimisticItems.removeAt(sourceIndex)
                                                    replaceMutableItems(optimisticItems)

                                                    val mutationStartRevision = itemsRevision
                                                    shelfMutationInFlight = true
                                                    onMoveToGroup(
                                                        latestSource.book.id,
                                                        latestTarget.bundle.id,
                                                    ) { succeeded ->
                                                        val authoritativeChanged = itemsRevision != mutationStartRevision
                                                        pendingItemsSnapshot = null
                                                        if (!succeeded || authoritativeChanged) {
                                                            replaceMutableItems(latestItemsSnapshot)
                                                        }
                                                        shelfMutationInFlight = false
                                                    }
                                                }
                                            }
                                            is LibraryItem.Single -> {
                                                beginSettling(sourceKey, startOrder)
                                                groupSourceId = source.book.id
                                                groupTargetItem = target
                                                groupName = target.book.title
                                            }
                                        }
                                    } else {
                                        val finalOrder = mutableItems.toList()
                                        if (shouldAnimateDropSettlement(startOrder, finalOrder)) {
                                            beginSettling(sourceKey, finalOrder)
                                        }
                                        if (finalOrder != startOrder) {
                                            val hadPendingSnapshot = pendingItemsSnapshot != null
                                            val mutationStartRevision = itemsRevision
                                            shelfMutationInFlight = true
                                            onReorder(finalOrder) { succeeded ->
                                                val authoritativeChanged = itemsRevision != mutationStartRevision
                                                pendingItemsSnapshot = null
                                                if (!succeeded || hadPendingSnapshot || authoritativeChanged) {
                                                    replaceMutableItems(latestItemsSnapshot)
                                                }
                                                shelfMutationInFlight = false
                                            }
                                        }
                                    }
                                    resetDragState()
                                },
                                onDragCancel = {
                                    val sourceKey = dragItemKey
                                    val startOrder = dragStartOrder.ifEmpty { items }
                                    mutableItems = startOrder
                                    beginSettling(sourceKey, startOrder)
                                    resetDragState()
                                },
                            )
                        },
                ) {
                    // ── 封面 + ⋮ 菜单按钮 ──
                    Box(
                        modifier = Modifier
                            .aspectRatio(Dimens.coverAspectRatio),
                    ) {
                        when (item) {
                            is LibraryItem.Single -> {
                                if (isMergeTarget && draggedSingle != null) {
                                    BundleStack(
                                        bundle = dev.readflow.core.model.BookBundle(
                                            id = "preview:${item.book.id}:${draggedSingle.book.id}",
                                            name = item.book.title,
                                            books = listOf(item.book, draggedSingle.book),
                                        ),
                                        modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
                                    )
                                } else {
                                    BookCover(
                                        book = item.book,
                                        modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
                                    )
                                }
                            }
                            is LibraryItem.Bundle -> {
                                if (isMergeTarget && draggedSingle != null) {
                                    BundleStack(
                                        bundle = item.bundle.copy(books = item.bundle.books + draggedSingle.book),
                                        modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
                                    )
                                } else {
                                    BundleStack(
                                        bundle = item.bundle,
                                        modifier = Modifier.fillMaxSize().clearAndSetSemantics {},
                                    )
                                }
                            }
                        }

                        if (isMergeTarget) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(Dimens.coverCorner),
                                    )
                                    .clearAndSetSemantics {},
                            )
                        }
                        if (isDragging && mergeTargetKey.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 10.dp)
                                    .clearAndSetSemantics {},
                            ) {
                                Text(
                                    text = "松手建组",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }

                        // 48dp 触摸目标；拖动期间统一隐藏，避免目标卡片仍像可点击控件。
                        if (dragItemKey.isEmpty() &&
                            settlingItemKey.isEmpty() &&
                            !shelfMutationInFlight
                        ) {
                            IconButton(
                                onClick = {
                                    contextItem = item
                                    contextMenuAnchorKey = item.key
                                },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .zIndex(2f)
                                    .size(Dimens.touchTarget),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.46f),
                                        shape = CircleShape,
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = menuDescription,
                                            tint = Color.White,
                                            modifier = Modifier.padding(5.dp),
                                        )
                                    }
                                }
                            }

                            // ── Context menu ──
                            if (contextMenuAnchorKey == item.key) {
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = {
                                        contextItem = null
                                        contextMenuAnchorKey = null
                                    },
                                ) {
                                    when (item) {
                                        is LibraryItem.Single -> {
                                            DropdownMenuItem(
                                                text = { Text("改名") },
                                                onClick = {
                                                    renameText = item.book.title
                                                    renameItem = item
                                                    contextItem = null
                                                    contextMenuAnchorKey = null
                                                },
                                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("建组") },
                                                onClick = {
                                                    groupSourceId = item.book.id
                                                    groupTargetItem = null
                                                    groupName = ""
                                                    contextItem = null
                                                    contextMenuAnchorKey = null
                                                },
                                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                            )
                                            if (item.book.canRemoveDownload) {
                                                DropdownMenuItem(
                                                    text = { Text("移除下载") },
                                                    onClick = {
                                                        onRemoveDownload(item.book.id)
                                                        contextItem = null
                                                        contextMenuAnchorKey = null
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Close, null) },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("删除") },
                                                onClick = {
                                                    deleteConfirmId = item.book.id
                                                    deleteConfirmIsBundle = false
                                                    deleteMode = BookRemovalMode.REMOVE_FROM_SHELF
                                                    deleteConfirmLabel = "《${item.book.title}》"
                                                    contextItem = null
                                                    contextMenuAnchorKey = null
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                },
                                            )
                                        }
                                        is LibraryItem.Bundle -> {
                                            DropdownMenuItem(
                                                text = { Text("改名") },
                                                onClick = {
                                                    renameText = item.bundle.name
                                                    renameBundleId = item.bundle.id
                                                    renameBundleName = item.bundle.name
                                                    contextItem = null; contextMenuAnchorKey = null
                                                },
                                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("拆组") },
                                                onClick = {
                                                    ungroupConfirmId = item.bundle.id
                                                    ungroupConfirmName = item.bundle.name
                                                    contextItem = null
                                                    contextMenuAnchorKey = null
                                                },
                                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("删除") },
                                                onClick = {
                                                    deleteConfirmId = item.bundle.id
                                                    deleteConfirmIsBundle = true
                                                    deleteMode = BookRemovalMode.REMOVE_FROM_SHELF
                                                    deleteConfirmLabel = "《${item.bundle.name}》(${item.bundle.books.size}本)"
                                                    contextItem = null
                                                    contextMenuAnchorKey = null
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    libraryItemLabel(item)?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.ink,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = Dimens.spaceXs,
                                    top = Dimens.spaceSm,
                                    end = Dimens.spaceXs,
                                )
                                .clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    deleteConfirmId?.let { did ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("确认删除") },
            text = {
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("请选择如何处理 ${deleteConfirmLabel}：")
                    val removeFromShelfSelected = deleteMode == BookRemovalMode.REMOVE_FROM_SHELF
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = removeFromShelfSelected,
                                role = Role.RadioButton,
                                onClick = { deleteMode = BookRemovalMode.REMOVE_FROM_SHELF },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = removeFromShelfSelected,
                            onClick = null,
                        )
                        Text("仅移出书架（保留文件和阅读数据）")
                    }
                    val deleteAllSelected = deleteMode == BookRemovalMode.DELETE_ALL
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = deleteAllSelected,
                                role = Role.RadioButton,
                                onClick = { deleteMode = BookRemovalMode.DELETE_ALL },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = deleteAllSelected,
                            onClick = null,
                        )
                        Text("全部删除（文件、进度、书签和笔记）")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (deleteConfirmIsBundle) {
                            onDeleteBundle(did, deleteMode)
                        } else {
                            onDelete(did, deleteMode)
                        }
                        deleteConfirmId = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("取消") }
            },
        )
    }

    ungroupConfirmId?.let { collectionId ->
        AlertDialog(
            onDismissRequest = { ungroupConfirmId = null },
            title = { Text("拆组") },
            text = { Text("确定要拆散《${ungroupConfirmName}》吗？\n组内书籍将恢复为单本显示。") },
            confirmButton = {
                TextButton(onClick = {
                    ungroupConfirmId = null
                    onUngroup(collectionId) { }
                }) { Text("拆组") }
            },
            dismissButton = {
                TextButton(onClick = { ungroupConfirmId = null }) { Text("取消") }
            },
        )
    }

    renameItem?.let { ri ->
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text("改名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("书名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val bookId = ri.book.id
                    // Optimistic: rename in UI immediately
                    mutableItems = mutableItems.map { item ->
                        if (item is LibraryItem.Single && item.book.id == bookId)
                            item.copy(book = item.book.copy(title = renameText))
                        else item
                    }
                    onRename(bookId, renameText); renameItem = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameItem = null }) { Text("取消") }
            },
        )
    }

    // Bundle rename dialog
    renameBundleId?.let { collectionId ->
        AlertDialog(
            onDismissRequest = { renameBundleId = null },
            title = { Text("重命名书组") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("组名") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanName = renameText.trim()
                        if (cleanName.isNotEmpty() && cleanName != renameBundleName) {
                            onRenameBundle(collectionId, cleanName) { }
                        }
                        renameBundleId = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameBundleId = null }) { Text("取消") }
            },
        )
    }

    if (groupSourceId.isNotEmpty()) {
        if (groupTargetItem == null) {
            AlertDialog(
                onDismissRequest = { groupSourceId = ""; groupName = "" },
                title = { Text("建组") },
                text = { Text("长按拖动本书并与目标封面对齐。\n出现“松手建组”预览后松手即可。") },
                confirmButton = { TextButton(onClick = { groupSourceId = "" }) { Text("知道了") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = {
                    groupSourceId = ""
                    groupTargetItem = null
                    groupName = ""
                },
                title = { Text("建组") },
                text = {
                    Column {
                        Text(
                            text = "将与《${groupTargetItem!!.book.title}》建组",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSoft,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            singleLine = true,
                            label = { Text("组名") },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = groupName.isNotBlank(),
                        onClick = {
                            val target = groupTargetItem
                            val cleanName = groupName.trim()
                            if (cleanName.isNotEmpty() && target != null) {
                                onCreateGroup(groupSourceId, target.book.id, cleanName)
                            }
                            groupSourceId = ""
                            groupTargetItem = null
                            groupName = ""
                        },
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        groupSourceId = ""
                        groupTargetItem = null
                        groupName = ""
                    }) { Text("取消") }
                },
            )
        }
    }
}
