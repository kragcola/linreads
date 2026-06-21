package dev.readflow.core.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 拖拽时手指命中的区域类型 */
private enum class DragZone { GAP_ABOVE, BOOK, GAP_BELOW, CANCEL }

/**
 * 书架网格，支持点击、拖拽重排、dwell 悬停建组。
 *
 * **手势设计 v2**：
 * - 点击 → 打开书
 * - ⋮ 菜单 → 改名/建组/删除
 * - 长按拖动到间隙区（上下30%）→ 实时换位预览，松手生效
 * - 长按拖动到书本区（中间40%）→ 停留700ms建组，环形进度倒计时
 * - 拖到屏幕底部取消区 → 红色提示，松手回到原位
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGrid(
    items: List<LibraryItem>,
    onItemClick: (LibraryItem) -> Unit,
    onDelete: (String) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onMoveToGroup: (String, String) -> Unit = { _, _ -> },
    onReorder: (List<LibraryItem>) -> Unit = {},
    onUngroup: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    val mutableItems = remember { mutableStateListOf(*items.toTypedArray()) }
    LaunchedEffect(items) {
        if (items != mutableItems.toList()) {
            mutableItems.clear()
            mutableItems.addAll(items)
        }
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // ── 拖动状态 ──
    var dragItemKey by remember { mutableStateOf("") }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartAbsPos by remember { mutableStateOf(Offset.Zero) }
    var currentInsertIndex by remember { mutableIntStateOf(-1) }
    var currentHoverKey by remember { mutableStateOf("") }
    var currentZone by remember { mutableStateOf(DragZone.GAP_BELOW) }
    var dwellTargetKey by remember { mutableStateOf("") }
    var isInCancelZone by remember { mutableStateOf(false) }
    var reorderPreviewActive by remember { mutableStateOf(false) }

    // dwell 计时
    var dwellJob by remember { mutableStateOf<Job?>(null) }
    var dwellStartTime by remember { mutableStateOf(0L) }
    var dwellProgress by remember { mutableStateOf(0f) }
    val dwellThresholdMs = 700L
    val dwellMoveTolerance = 12.dp

    // 自动滚动
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    val scrollEdgeThreshold = 80.dp

    // 菜单/对话框状态
    var contextItem by remember { mutableStateOf<LibraryItem?>(null) }
    var contextMenuAnchor by remember { mutableStateOf<Int?>(null) }
    var renameItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var renameText by remember { mutableStateOf("") }
    var groupSourceId by remember { mutableStateOf("") }
    var groupTargetItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var groupName by remember { mutableStateOf("") }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmLabel by remember { mutableStateOf("") }
    var ungroupConfirmName by remember { mutableStateOf<String?>(null) }

    // 取消区高度
    val cancelZoneHeight = 64.dp

    /** 根据手指绝对位置和 item info 计算命中区域 */
    fun hitZone(absPos: Offset, info: LazyGridItemInfo): DragZone {
        val itemTop = info.offset.y.toFloat()
        val itemH = info.size.height.toFloat()
        val zoneH = itemH * 0.30f
        val relY = absPos.y - itemTop
        return when {
            relY < zoneH -> DragZone.GAP_ABOVE
            relY < itemH - zoneH -> DragZone.BOOK
            else -> DragZone.GAP_BELOW
        }
    }

    /** 根据手指位置找到插入目标 index 和所在 zone */
    fun findDropTarget(absPos: Offset): Pair<Int, DragZone> {
        val visInfo = gridState.layoutInfo.visibleItemsInfo
        if (visInfo.isEmpty()) return -1 to DragZone.GAP_BELOW

        // 手指在最后一个 item 下方 → 插入到末尾
        val last = visInfo.last()
        if (absPos.y > last.offset.y + last.size.height) {
            return mutableItems.lastIndex to DragZone.GAP_BELOW
        }
        // 手指在第一个 item 上方 → 插入到开头
        val first = visInfo.first()
        if (absPos.y < first.offset.y) {
            return 0 to DragZone.GAP_ABOVE
        }

        // 找到覆盖手指的 item
        val hitInfo = visInfo.firstOrNull { info ->
            val rx = info.offset.x.toFloat()
            val ry = info.offset.y.toFloat()
            absPos.x in rx..(rx + info.size.width) &&
                absPos.y in ry..(ry + info.size.height)
        }
        if (hitInfo != null) {
            val zone = hitZone(absPos, hitInfo)
            val insertIdx = when (zone) {
                DragZone.GAP_ABOVE -> hitInfo.index
                DragZone.BOOK -> hitInfo.index // 书本区返回自身index，由调用方处理
                DragZone.GAP_BELOW -> (hitInfo.index + 1).coerceAtMost(mutableItems.lastIndex)
                DragZone.CANCEL -> currentInsertIndex
            }
            return insertIdx to zone
        }

        // 没有直接命中 — 找最近的 item
        val nearest = visInfo.minByOrNull { abs(absPos.y - (it.offset.y + it.size.height / 2f)) }
        return nearest?.let {
            val z = hitZone(absPos.copy(y = (it.offset.y + it.size.height / 2f).toFloat()), it)
            val idx = if (z == DragZone.GAP_ABOVE) it.index else (it.index + 1).coerceAtMost(mutableItems.lastIndex)
            idx to z
        } ?: (-1 to DragZone.GAP_BELOW)
    }

    /** 拖拽中实时重排：移除被拖拽的 item 并插入到新位置 */
    fun performLiveReorder(insertIdx: Int) {
        if (!reorderPreviewActive) return
        val dragIdx = mutableItems.indexOfFirst { it.key == dragItemKey }
        if (dragIdx < 0) return
        val targetIdx = if (insertIdx > dragIdx) insertIdx - 1 else insertIdx
        if (targetIdx == dragIdx) return
        val moved = mutableItems.removeAt(dragIdx)
        mutableItems.add(targetIdx.coerceIn(0, mutableItems.size), moved)
    }

    /** 根据 item key 找它的 info */
    fun infoForKey(key: String) = gridState.layoutInfo.visibleItemsInfo
        .firstOrNull { mutableItems.getOrNull(it.index)?.key == key }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = Dimens.coverTargetWidthPhone),
            contentPadding = PaddingValues(
                horizontal = Dimens.screenEdge,
                vertical = Dimens.spaceMd,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gridGapCompact),
            verticalArrangement = Arrangement.spacedBy(Dimens.gridGapCompact),
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth),
        ) {
            itemsIndexed(mutableItems, key = { _, item -> item.key }) { index, item ->
                val isDragging = dragItemKey == item.key
                val isDwellTarget = dwellTargetKey == item.key && dragItemKey.isNotEmpty() && dragItemKey != item.key
                val isInsertPlaceholder = reorderPreviewActive && currentInsertIndex == index && dragItemKey != item.key && currentZone != DragZone.BOOK
                val view = LocalView.current

                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (isDragging) dragOffset.x else 0f
                            translationY = if (isDragging) dragOffset.y else 0f
                            scaleX = if (isDragging) 1.10f else if (isDwellTarget) 0.94f else 1f
                            scaleY = if (isDragging) 1.10f else if (isDwellTarget) 0.94f else 1f
                            alpha = if (isDragging) 0.7f else 1f
                            shadowElevation = if (isDragging) 8f else 0f
                        }
                        .animateItem()
                        .clickable { onItemClick(item) }
                        .pointerInput(item.key, index) {
                            var dwellLocalStart = 0L

                            detectDragGesturesAfterLongPress(
                                onDragStart = { localPos ->
                                    if (dragItemKey.isEmpty()) {
                                        dragItemKey = item.key
                                        dragOffset = Offset.Zero
                                        dwellTargetKey = ""
                                        currentHoverKey = ""
                                        currentInsertIndex = -1
                                        currentZone = DragZone.GAP_BELOW
                                        reorderPreviewActive = false
                                        isInCancelZone = false
                                        dwellJob?.cancel()
                                        autoScrollJob?.cancel()
                                        dwellProgress = 0f

                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                                        val itemInfo = gridState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == index }
                                        dragStartAbsPos = Offset(
                                            (itemInfo?.offset?.x ?: 0).toFloat() + localPos.x,
                                            (itemInfo?.offset?.y ?: 0).toFloat() + localPos.y,
                                        )
                                        dwellLocalStart = System.currentTimeMillis()
                                        dwellStartTime = dwellLocalStart
                                    }
                                },
                                onDrag = { change, delta ->
                                    if (dragItemKey != item.key) return@detectDragGesturesAfterLongPress
                                    dragOffset += delta
                                    change.consume()

                                    val absPos = dragStartAbsPos + dragOffset

                                    // ── 取消区检测 ──
                                    val viewportHeight = gridState.layoutInfo.viewportEndOffset -
                                        gridState.layoutInfo.viewportStartOffset
                                    val cancelTop = viewportHeight - cancelZoneHeight.toPx()
                                    val newInCancel = absPos.y > cancelTop
                                    if (newInCancel != isInCancelZone) {
                                        isInCancelZone = newInCancel
                                        if (newInCancel) {
                                            // 进入取消区：回退所有实时换位
                                            dwellJob?.cancel()
                                            dwellTargetKey = ""
                                            dwellProgress = 0f
                                            reorderPreviewActive = false
                                            currentInsertIndex = -1
                                            // 恢复原始顺序
                                            mutableItems.clear()
                                            mutableItems.addAll(items)
                                        }
                                    }

                                    if (isInCancelZone) return@detectDragGesturesAfterLongPress

                                    // ── 正常区：命中检测 ──
                                    val (insertIdx, zone) = findDropTarget(absPos)
                                    val hitInfo = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { absPos.y in it.offset.y.toFloat()..(it.offset.y + it.size.height).toFloat() }
                                    val hoverKey = hitInfo?.let { mutableItems.getOrNull(it.index)?.key } ?: ""

                                    if (zone != currentZone || hoverKey != currentHoverKey) {
                                        dwellJob?.cancel()
                                        dwellTargetKey = ""
                                        dwellProgress = 0f
                                        currentZone = zone
                                        currentHoverKey = hoverKey
                                        dwellLocalStart = System.currentTimeMillis()
                                        dwellStartTime = dwellLocalStart

                                        when (zone) {
                                            DragZone.GAP_ABOVE, DragZone.GAP_BELOW -> {
                                                // 间隙区 → 实时换位
                                                reorderPreviewActive = true
                                                currentInsertIndex = insertIdx
                                                performLiveReorder(insertIdx)
                                            }
                                            DragZone.BOOK -> {
                                                // 书本区 → 停止实时换位（恢复原位）+ 启动 dwell
                                                reorderPreviewActive = false
                                                currentInsertIndex = -1
                                                // 恢复原始顺序
                                                val orig = items.toList()
                                                if (mutableItems.toList() != orig) {
                                                    mutableItems.clear()
                                                    mutableItems.addAll(orig)
                                                }

                                                val targetItem = mutableItems.getOrNull(insertIdx)
                                                if (targetItem != null && hoverKey != dragItemKey) {
                                                    val dragItem = mutableItems.firstOrNull { it.key == dragItemKey }
                                                    if (dragItem is LibraryItem.Single &&
                                                        (targetItem is LibraryItem.Single || targetItem is LibraryItem.Bundle)) {
                                                        dwellJob = scope.launch {
                                                            while (true) {
                                                                delay(50)
                                                                val elapsed = System.currentTimeMillis() - dwellLocalStart
                                                                dwellProgress = (elapsed.toFloat() / dwellThresholdMs).coerceIn(0f, 1f)
                                                                if (elapsed >= dwellThresholdMs) {
                                                                    dwellTargetKey = hoverKey
                                                                    dwellProgress = 1f
                                                                    break
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            DragZone.CANCEL -> {}
                                        }
                                    } else if (zone == DragZone.BOOK && hoverKey.isNotEmpty() && hoverKey != dragItemKey) {
                                        // 同一书本区内，更新 dwell 进度
                                        dwellProgress = ((System.currentTimeMillis() - dwellLocalStart).toFloat() / dwellThresholdMs).coerceIn(0f, 1f)
                                    }

                                    // ── 自动滚动 ──
                                    val relativeY = absPos.y - gridState.layoutInfo.viewportStartOffset
                                    autoScrollJob?.cancel()
                                    val scrollSpeed = when {
                                        relativeY < scrollEdgeThreshold.toPx() -> -(scrollEdgeThreshold.toPx() - relativeY) / 10f
                                        relativeY > viewportHeight - scrollEdgeThreshold.toPx() ->
                                            (relativeY - (viewportHeight - scrollEdgeThreshold.toPx())) / 10f
                                        else -> 0f
                                    }
                                    if (abs(scrollSpeed) > 1f) {
                                        autoScrollJob = scope.launch {
                                            while (true) {
                                                gridState.scrollToItem(
                                                    index = (gridState.firstVisibleItemIndex + if (scrollSpeed > 0) 1 else -1)
                                                        .coerceIn(0, mutableItems.lastIndex)
                                                )
                                                delay(100)
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    dwellJob?.cancel()
                                    autoScrollJob?.cancel()

                                    if (isInCancelZone) {
                                        // 取消：恢复原始顺序
                                        mutableItems.clear()
                                        mutableItems.addAll(items)
                                    } else if (reorderPreviewActive && currentInsertIndex >= 0) {
                                        // 换位生效：恢复到最终位置（已在实时重排中完成）并持久化
                                        onReorder(mutableItems.toList())
                                    } else if (dwellTargetKey.isNotEmpty()) {
                                        // 建组
                                        val dragItem = mutableItems.firstOrNull { it.key == dragItemKey }
                                        val targetItem = mutableItems.firstOrNull { it.key == dwellTargetKey }
                                        if (dragItem is LibraryItem.Single) {
                                            when (targetItem) {
                                                is LibraryItem.Bundle -> onMoveToGroup(dragItem.book.id, targetItem.bundle.name)
                                                is LibraryItem.Single -> {
                                                    groupSourceId = dragItem.book.id
                                                    groupTargetItem = targetItem
                                                    groupName = ""
                                                }
                                                else -> {}
                                            }
                                        }
                                    }

                                    // 重置
                                    dragItemKey = ""
                                    dragOffset = Offset.Zero
                                    currentHoverKey = ""
                                    currentInsertIndex = -1
                                    currentZone = DragZone.GAP_BELOW
                                    dwellTargetKey = ""
                                    dwellProgress = 0f
                                    reorderPreviewActive = false
                                    isInCancelZone = false
                                },
                                onDragCancel = {
                                    dwellJob?.cancel()
                                    autoScrollJob?.cancel()
                                    // 恢复原始顺序
                                    mutableItems.clear()
                                    mutableItems.addAll(items)
                                    dragItemKey = ""
                                    dragOffset = Offset.Zero
                                    currentHoverKey = ""
                                    currentInsertIndex = -1
                                    currentZone = DragZone.GAP_BELOW
                                    dwellTargetKey = ""
                                    dwellProgress = 0f
                                    reorderPreviewActive = false
                                    isInCancelZone = false
                                },
                            )
                        },
                ) {
                    // ── 封面 + ⋮ 菜单按钮 ──
                    Box(
                        modifier = Modifier
                            .aspectRatio(Dimens.coverAspectRatio)
                            .padding(bottom = Dimens.spaceXs),
                    ) {
                        when (item) {
                            is LibraryItem.Single -> {
                                if (isInsertPlaceholder) {
                                    // 虚化占位框（间隙区拖入时的插入预览）
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .border(2.dp, MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = MaterialTheme.shapes.medium),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("← 插入此处", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                    }
                                } else if (isDwellTarget && dragItemKey.isNotEmpty()) {
                                    val dragged = mutableItems.firstOrNull { it.key == dragItemKey }
                                    if (dragged is LibraryItem.Single) {
                                        BundleStack(
                                            bundle = dev.readflow.core.model.BookBundle(
                                                item.book.title,
                                                listOf(dragged.book, item.book),
                                            ),
                                        )
                                    } else {
                                        BookCover(book = item.book, modifier = Modifier.fillMaxSize())
                                    }
                                } else {
                                    BookCover(book = item.book, modifier = Modifier.fillMaxSize())
                                    if (item.book.lastReadAt != null && item.book.progress > 0f) {
                                        PaperBookmark(
                                            book = item.book,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(end = 8.dp),
                                        )
                                    }
                                }
                            }
                            is LibraryItem.Bundle -> {
                                if (isInsertPlaceholder) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .border(2.dp, MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = MaterialTheme.shapes.medium),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("← 插入此处", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) }
                                } else if (isDwellTarget && dragItemKey.isNotEmpty()) {
                                    val dragged = mutableItems.firstOrNull { it.key == dragItemKey }
                                    if (dragged is LibraryItem.Single) {
                                        BundleStack(bundle = item.bundle.copy(books = item.bundle.books + dragged.book))
                                    } else {
                                        BundleStack(bundle = item.bundle)
                                    }
                                } else {
                                    BundleStack(bundle = item.bundle)
                                }
                            }
                        }

                        // dwell 进度环（书本区停留计时）
                        if (currentHoverKey == item.key && currentZone == DragZone.BOOK &&
                            dragItemKey.isNotEmpty() && dragItemKey != item.key && dwellTargetKey.isEmpty()) {
                            val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                                val stroke = 6.dp.toPx()
                                val arcSize = Size(size.width - stroke, size.height - stroke)
                                val topLeft = Offset(stroke / 2, stroke / 2)
                                drawArc(
                                    color = ringColor,
                                    startAngle = -90f,
                                    sweepAngle = dwellProgress * 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                )
                            }
                        }

                        // ⋮ 菜单按钮
                        if (!isDragging && !isInsertPlaceholder) {
                            IconButton(
                                onClick = {
                                    contextItem = item
                                    contextMenuAnchor = index
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "菜单",
                                    tint = palette.ink.copy(alpha = 0.45f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            // ── Context menu ──
                            if (contextMenuAnchor == index) {
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = {
                                        contextItem = null
                                        contextMenuAnchor = null
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
                                                    contextMenuAnchor = null
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
                                                    contextMenuAnchor = null
                                                },
                                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("删除") },
                                                onClick = {
                                                    deleteConfirmId = item.book.id
                                                    deleteConfirmLabel = "《${item.book.title}》"
                                                    contextItem = null
                                                    contextMenuAnchor = null
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                                },
                                            )
                                        }
                                        is LibraryItem.Bundle -> {
                                            DropdownMenuItem(
                                                text = { Text("拆组") },
                                                onClick = {
                                                    ungroupConfirmName = item.bundle.name
                                                    contextItem = null
                                                    contextMenuAnchor = null
                                                },
                                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("删除") },
                                                onClick = {
                                                    deleteConfirmId = "bundle:${item.bundle.name}"
                                                    deleteConfirmLabel = "《${item.bundle.name}》(${item.bundle.books.size}本)"
                                                    contextItem = null
                                                    contextMenuAnchor = null
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

                    val title = when (item) {
                        is LibraryItem.Single -> item.book.title
                        is LibraryItem.Bundle -> item.bundle.name
                    }
                    Text(
                        text = title,
                        style = ReadflowType.bookTitle,
                        color = palette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item is LibraryItem.Single) { /* 作者识别不到，已移除 */ }
                    ShelfBoard(modifier = Modifier.padding(top = Dimens.spaceXs))
                }
            }
        }

        // ── 取消区 Overlay ──
        if (dragItemKey.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(cancelZoneHeight)
                    .background(
                        if (isInCancelZone) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isInCancelZone) "松手取消移位" else "拖到此处取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isInCancelZone) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ── Dialogs (unchanged) ─────────────────────────────────────────────────

    deleteConfirmId?.let { did ->
        val isBundleDelete = did.startsWith("bundle:")
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("确认删除") },
            text = {
                Text("确定要删除 ${deleteConfirmLabel} 吗？\n${if (isBundleDelete) "组内全部书籍将被移出书架。" else "阅读进度将一并删除。"}")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBundleDelete) {
                            val bundleName = did.removePrefix("bundle:")
                            val bundleBooks = mutableItems.filterIsInstance<LibraryItem.Bundle>()
                                .firstOrNull { it.bundle.name == bundleName }?.bundle?.books ?: emptyList()
                            bundleBooks.forEach { onDelete(it.id) }
                        } else {
                            onDelete(did)
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

    ungroupConfirmName?.let { name ->
        AlertDialog(
            onDismissRequest = { ungroupConfirmName = null },
            title = { Text("拆组") },
            text = { Text("确定要拆散《${name}》吗？\n组内书籍将恢复为单本显示。") },
            confirmButton = {
                TextButton(onClick = { onUngroup(name); ungroupConfirmName = null }) { Text("拆组") }
            },
            dismissButton = {
                TextButton(onClick = { ungroupConfirmName = null }) { Text("取消") }
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
                TextButton(onClick = { onRename(ri.book.id, renameText); renameItem = null }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameItem = null }) { Text("取消") }
            },
        )
    }

    if (groupSourceId.isNotEmpty()) {
        if (groupTargetItem == null) {
            AlertDialog(
                onDismissRequest = { groupSourceId = "" },
                title = { Text("建组") },
                text = { Text("建组需要两本书。\n请长按拖动本书到目标书上，\n停留片刻即可建组。") },
                confirmButton = { TextButton(onClick = { groupSourceId = "" }) { Text("知道了") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { groupSourceId = ""; groupTargetItem = null },
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
                        onClick = {
                            if (groupName.isNotBlank()) {
                                onMoveToGroup(groupSourceId, groupName)
                                groupTargetItem?.let { onMoveToGroup(it.book.id, groupName) }
                            }
                            groupSourceId = ""
                            groupTargetItem = null
                        },
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { groupSourceId = ""; groupTargetItem = null }) { Text("取消") }
                },
            )
        }
    }
}

private val LibraryItem.key: String
    get() = when (this) {
        is LibraryItem.Single -> "book:${book.id}"
        is LibraryItem.Bundle -> "bundle:${bundle.name}"
    }
