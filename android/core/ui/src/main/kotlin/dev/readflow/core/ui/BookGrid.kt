package dev.readflow.core.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
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

/** 拖拽时手指命中的区域类型 */
private enum class DragZone { GAP_ABOVE, BOOK, GAP_BELOW, CANCEL }

private val LibraryItem.key: String
    get() = when (this) {
        is LibraryItem.Single -> "book:${book.id}"
        is LibraryItem.Bundle -> "bundle:${bundle.name}"
    }

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
    onRenameBundle: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    val mutableItems = remember { mutableStateListOf(*items.toTypedArray()) }
    LaunchedEffect(items) {
        if (mutableItems.toList() != items) {
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
    var dragInitialItemOffset by remember { mutableStateOf(Offset.Zero) }
    var currentInsertIndex by remember { mutableIntStateOf(-1) }
    var currentHoverKey by remember { mutableStateOf("") }
    var currentZone by remember { mutableStateOf(DragZone.GAP_BELOW) }
    var dwellTargetKey by remember { mutableStateOf("") }
    var isInCancelZone by remember { mutableStateOf(false) }
    var reorderPreviewActive by remember { mutableStateOf(false) }
    var dragGeneration by remember { mutableIntStateOf(0) }

    // dwell 计时
    var dwellJob by remember { mutableStateOf<Job?>(null) }
    var dwellStartTime by remember { mutableStateOf(0L) }
    var dwellProgress by remember { mutableStateOf(0f) }
    val dwellThresholdMs = 700L

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
    var renameBundleOldName by remember { mutableStateOf<String?>(null) }

    // 取消区高度
    val cancelZoneHeight = 64.dp

    /** 根据手指绝对位置和 item info 计算命中区域 */
    fun hitZone(absPos: Offset, info: LazyGridItemInfo): DragZone {
        val itemTop = info.offset.y.toFloat()
        val itemH = info.size.height.toFloat()
        val zoneH = itemH * 0.40f  // 上下各40% = 间隙，中间20% = 书本
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

        // 找到 X 轴和 Y 轴都覆盖手指的 item
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
                DragZone.BOOK -> hitInfo.index
                DragZone.GAP_BELOW -> (hitInfo.index + 1).coerceAtMost(mutableItems.lastIndex)
                DragZone.CANCEL -> currentInsertIndex
            }
            return insertIdx to zone
        }

        // 没有直接 XY 命中 → 手指在行列间隙中
        // 找到 Y 轴覆盖的 item（同一行），用实际手指位置判断 zone
        val sameRow = visInfo.filter { info ->
            val ry = info.offset.y.toFloat()
            absPos.y in ry..(ry + info.size.height)
        }
        if (sameRow.isNotEmpty()) {
            // 手指在行内但 X 轴未覆盖 → X 间隙 → 找到最近 item，用实际 Y 判断上下间隙
            val nearest = sameRow.minByOrNull {
                val cx = it.offset.x + it.size.width / 2f
                abs(absPos.x - cx)
            }!!
            val zone = hitZone(absPos, nearest) // 用实际手指 Y
            val insertIdx = when {
                zone == DragZone.GAP_ABOVE -> nearest.index
                absPos.x < (nearest.offset.x + nearest.size.width / 2f) -> nearest.index
                else -> (nearest.index + 1).coerceAtMost(mutableItems.lastIndex)
            }
            return insertIdx to zone
        }

        // 完全不在任何行内 → 用二维距离找最近 item，强制视为间隙
        val nearest = visInfo.minByOrNull { info ->
            val cx = info.offset.x + info.size.width / 2f
            val cy = info.offset.y + info.size.height / 2f
            val dx = absPos.x - cx
            val dy = absPos.y - cy
            dx * dx + dy * dy
        } ?: return -1 to DragZone.GAP_BELOW

        // 手指不在 item 上 → 强制间隙区，禁止触发 dwell
        val zone = if (absPos.y < nearest.offset.y + nearest.size.height / 2f) DragZone.GAP_ABOVE else DragZone.GAP_BELOW
        val insertIdx = if (zone == DragZone.GAP_ABOVE) nearest.index
            else (nearest.index + 1).coerceAtMost(mutableItems.lastIndex)
        return insertIdx to zone
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 整行联通书架隔板（先渲染 = 在网格后面）──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val visInfo = gridState.layoutInfo.visibleItemsInfo
            val processedRows = mutableSetOf<Int>()
            for (info in visInfo) {
                // 用 top edge 做行标识 — 同行 item 的 offset.y 一致
                val rowY = info.offset.y
                if (processedRows.add(rowY)) {
                    // 整行底板：暖木色 + 上下投影
                    val boardY = rowY.toFloat()
                    val boardH = 4.dp.toPx()
                    val shadowH = 5.dp.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0x1A000000), Color.Transparent),
                            startY = boardY - shadowH,
                            endY = boardY,
                        ),
                        topLeft = Offset(0f, boardY - shadowH),
                        size = Size(size.width, shadowH),
                    )
                    drawRect(
                        color = Color(0xFFD4C9AE),
                        topLeft = Offset(0f, boardY),
                        size = Size(size.width, boardH),
                    )
                    drawRect(
                        color = Color(0x12000000),
                        topLeft = Offset(0f, boardY + boardH),
                        size = Size(size.width, 0.5.dp.toPx()),
                    )
                }
            }
        }

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
                val view = LocalView.current

                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            // Calvin 公式补偿实时换位导致的 item 位移
                            if (isDragging) {
                                val dragInfo = gridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { mutableItems.getOrNull(it.index)?.key == dragItemKey }
                                val cur = if (dragInfo != null) Offset(dragInfo.offset.x.toFloat(), dragInfo.offset.y.toFloat()) else Offset.Zero
                                val vo = dragOffset + (dragInitialItemOffset - cur)
                                translationX = vo.x; translationY = vo.y
                                scaleX = 1.10f; scaleY = 1.10f; alpha = 0.7f; shadowElevation = 8f
                            } else {
                                translationX = 0f; translationY = 0f
                                scaleX = if (isDwellTarget) 0.94f else 1f
                                scaleY = if (isDwellTarget) 0.94f else 1f
                                alpha = 1f; shadowElevation = 0f
                            }
                        }
                        .then(if (!isDragging) Modifier.animateItem() else Modifier)
                        .clickable { onItemClick(item) }
                        .pointerInput(item.key, dragGeneration) {
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
                                        dragInitialItemOffset = Offset(
                                            (itemInfo?.offset?.x ?: 0).toFloat(),
                                            (itemInfo?.offset?.y ?: 0).toFloat(),
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
                                            dwellJob?.cancel()
                                            dwellTargetKey = ""
                                            dwellProgress = 0f
                                            reorderPreviewActive = false
                                            currentInsertIndex = -1
                                        }
                                    }

                                    if (isInCancelZone) return@detectDragGesturesAfterLongPress

                                    // ── 命中检测 ──
                                    val (insertIdx, zone) = findDropTarget(absPos)
                                    val hoverHit = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { info ->
                                            val rx = info.offset.x.toFloat()
                                            val ry = info.offset.y.toFloat()
                                            absPos.x in rx..(rx + info.size.width) &&
                                                absPos.y in ry..(ry + info.size.height)
                                        }
                                    val hoverKey = hoverHit?.let { mutableItems.getOrNull(it.index)?.key } ?: ""

                                    // ── 状态迁移 ──
                                    val bookChanged = zone == DragZone.BOOK && (zone != currentZone || hoverKey != currentHoverKey)
                                    val gapChanged = zone != DragZone.BOOK && (zone != currentZone || insertIdx != currentInsertIndex)
                                    val sameBook = zone == DragZone.BOOK && zone == currentZone && hoverKey == currentHoverKey

                                    when {
                                        bookChanged -> {
                                            // 进入/切换到书本区 → 暂停换位 + 启动 dwell
                                            dwellJob?.cancel()
                                            dwellTargetKey = ""
                                            dwellProgress = 0f
                                            reorderPreviewActive = false
                                            currentInsertIndex = -1
                                            currentZone = zone
                                            currentHoverKey = hoverKey
                                            dwellLocalStart = System.currentTimeMillis()
                                            dwellStartTime = dwellLocalStart

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
                                        sameBook -> {
                                            // 同一书本区内 → 更新进度
                                            dwellProgress = ((System.currentTimeMillis() - dwellLocalStart).toFloat() / dwellThresholdMs).coerceIn(0f, 1f)
                                        }
                                        gapChanged -> {
                                            // 进入/切换到间隙区 → 实时换位
                                            dwellJob?.cancel()
                                            dwellTargetKey = ""
                                            dwellProgress = 0f
                                            currentZone = zone
                                            currentHoverKey = ""
                                            currentInsertIndex = insertIdx
                                            reorderPreviewActive = true

                                            val dragIdx = mutableItems.indexOfFirst { it.key == dragItemKey }
                                            if (dragIdx >= 0 && insertIdx >= 0) {
                                                val targetIdx = if (insertIdx > dragIdx) insertIdx - 1 else insertIdx
                                                if (targetIdx != dragIdx && targetIdx in 0 until mutableItems.size) {
                                                    val moved = mutableItems.removeAt(dragIdx)
                                                    mutableItems.add(targetIdx, moved)
                                                }
                                            }
                                        }
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

                                }, // close onDrag lambda
                                onDragEnd = {
                                    dwellJob?.cancel()
                                    autoScrollJob?.cancel()

                                    when {
                                        isInCancelZone -> {
                                            // 取消 → 恢复拖拽前顺序
                                            mutableItems.clear()
                                            mutableItems.addAll(items)
                                        }
                                        dwellTargetKey.isNotEmpty() -> {
                                            // dwell 完成 → 建组
                                            val dragItem = mutableItems.firstOrNull { it.key == dragItemKey }
                                            val targetItem = mutableItems.firstOrNull { it.key == dwellTargetKey }
                                            if (dragItem is LibraryItem.Single) {
                                                when (targetItem) {
                                                    is LibraryItem.Bundle -> onMoveToGroup(dragItem.book.id, targetItem.bundle.name)
                                                    is LibraryItem.Single -> {
                                                        // 自动以第一本书名建组，不弹对话框
                                                        onMoveToGroup(dragItem.book.id, dragItem.book.title)
                                                        onMoveToGroup(targetItem.book.id, dragItem.book.title)
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        }
                                        reorderPreviewActive -> {
                                            // 换位预览中松手 → 提交当前顺序
                                            onReorder(mutableItems.toList())
                                        }
                                        currentInsertIndex >= 0 -> {
                                            // 兜底：手指在间隙区但 preview 未激活 → 仍提交
                                            onReorder(mutableItems.toList())
                                        }
                                    }

                                    // 重置
                                    dragItemKey = ""
                                    dragOffset = Offset.Zero
                                    dragInitialItemOffset = Offset.Zero
                                    currentHoverKey = ""
                                    currentInsertIndex = -1
                                    currentZone = DragZone.GAP_BELOW
                                    dwellTargetKey = ""
                                    dwellProgress = 0f
                                    reorderPreviewActive = false
                                    isInCancelZone = false
                                    dragGeneration++
                                },
                                onDragCancel = {
                                    dwellJob?.cancel()
                                    autoScrollJob?.cancel()
                                    // 恢复拖拽前顺序
                                    mutableItems.clear()
                                    mutableItems.addAll(items)
                                    dragItemKey = ""
                                    dragOffset = Offset.Zero
                                    dragInitialItemOffset = Offset.Zero
                                    currentHoverKey = ""
                                    currentInsertIndex = -1
                                    currentZone = DragZone.GAP_BELOW
                                    dwellTargetKey = ""
                                    dwellProgress = 0f
                                    reorderPreviewActive = false
                                    isInCancelZone = false
                                    dragGeneration++
                                },  // onDragCancel
                            )  // detectDragGesturesAfterLongPress
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
                                if (isDwellTarget && dragItemKey.isNotEmpty()) {
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
                                if (isDwellTarget && dragItemKey.isNotEmpty()) {
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

                        // ⋮ 菜单按钮（双层描边图标：暗底描边 + 白顶，任何封面可见）
                        if (!isDragging) {
                            IconButton(
                                onClick = {
                                    contextItem = item
                                    contextMenuAnchor = index
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .zIndex(2f)
                                    .size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    // 底层：暗色羽化描边（稍大）
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = null,
                                        tint = Color.Black.copy(alpha = 0.35f),
                                        modifier = Modifier
                                            .size(22.dp)
                                            .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f },
                                    )
                                    // 顶层：白色三点
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "菜单",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
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
                                                text = { Text("改名") },
                                                onClick = {
                                                    renameText = item.bundle.name
                                                    renameBundleOldName = item.bundle.name
                                                    contextItem = null; contextMenuAnchor = null
                                                },
                                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            )
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
                }
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
                        // Save bundle books before removal
                        val bundleBooksForDelete = if (isBundleDelete) {
                            val bundleName = did.removePrefix("bundle:")
                            items.filterIsInstance<LibraryItem.Bundle>()
                                .firstOrNull { it.bundle.name == bundleName }?.bundle?.books ?: emptyList()
                        } else emptyList()
                        // Optimistic: remove from UI immediately
                        mutableItems.removeAll {
                            when {
                                isBundleDelete -> it is LibraryItem.Bundle && it.bundle.name == did.removePrefix("bundle:")
                                else -> it is LibraryItem.Single && it.book.id == did
                            }
                        }
                        if (isBundleDelete) {
                            bundleBooksForDelete.forEach { onDelete(it.id) }
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
                TextButton(onClick = {
                    // Optimistic: expand bundle to singles immediately
                    val idx = mutableItems.indexOfFirst { it is LibraryItem.Bundle && it.bundle.name == name }
                    if (idx >= 0) {
                        val bundle = mutableItems[idx] as LibraryItem.Bundle
                        mutableItems.removeAt(idx)
                        bundle.bundle.books.forEachIndexed { offset, book ->
                            mutableItems.add(idx + offset, LibraryItem.Single(book))
                        }
                    }
                    onUngroup(name); ungroupConfirmName = null
                }) { Text("拆组") }
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
                TextButton(onClick = {
                    val bookId = ri.book.id
                    // Optimistic: rename in UI immediately
                    mutableItems.replaceAll { item ->
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
    renameBundleOldName?.let { old ->
        AlertDialog(
            onDismissRequest = { renameBundleOldName = null },
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
                        if (renameText.isNotBlank()) {
                            // Optimistic: rename in UI immediately
                            mutableItems.replaceAll { item ->
                                if (item is LibraryItem.Bundle && item.bundle.name == old)
                                    item.copy(bundle = item.bundle.copy(name = renameText))
                                else item
                            }
                            onRenameBundle(old, renameText)
                        }
                        renameBundleOldName = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameBundleOldName = null }) { Text("取消") }
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
