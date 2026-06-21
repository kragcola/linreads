package dev.readflow.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import android.view.HapticFeedbackConstants
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

/**
 * 书架网格，支持点击、拖拽重排、dwell 悬停建组。
 *
 * **手势设计**（根因修复）：
 * - 点击 → 打开书
 * - ⋮ 菜单 → 改名/建组/删除（不再用长按弹菜单，消除与拖动的竞态）
 * - 长按拖动 → 重排（实时让位动画用 `Modifier.animateItem()`）
 * - 悬停 ~700ms → 高亮目标书 → 松手建组
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

    // ── 拖动状态（grid 绝对坐标系） ──
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartAbsPos by remember { mutableStateOf(Offset.Zero) }
    var currentHoverIndex by remember { mutableIntStateOf(-1) }
    var dwellTargetIndex by remember { mutableIntStateOf(-1) } // dwell 高亮的目标书

    // dwell 计时
    var dwellJob by remember { mutableStateOf<Job?>(null) }
    val dwellThresholdMs = 700L
    val dwellMoveTolerance = 12.dp // 允许轻微抖动

    // 自动滚动
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    val scrollEdgeThreshold = 80.dp

    // 菜单/对话框状态
    var contextItem by remember { mutableStateOf<LibraryItem?>(null) }
    var contextMenuAnchor by remember { mutableStateOf<Int?>(null) } // 菜单绑定的 item index
    var renameItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var renameText by remember { mutableStateOf("") }
    var groupSourceId by remember { mutableStateOf("") }
    var groupTargetItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var groupName by remember { mutableStateOf("") }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmLabel by remember { mutableStateOf("") }
    var ungroupConfirmName by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = Dimens.coverTargetWidthPhone),
            contentPadding = PaddingValues(horizontal = Dimens.screenEdge, vertical = Dimens.spaceMd),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gridGapCompact),
            verticalArrangement = Arrangement.spacedBy(Dimens.gridGapCompact),
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth),
        ) {
            itemsIndexed(mutableItems, key = { _, item -> item.key }) { index, item ->
                val isDragging = draggedIndex == index
                val isDwellTarget = dwellTargetIndex == index && draggedIndex >= 0 && draggedIndex != index
                val view = LocalView.current

                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (isDragging) dragOffset.x else 0f
                            translationY = if (isDragging) dragOffset.y else 0f
                            scaleX = if (isDragging) 1.15f else if (isDwellTarget) 0.94f else 1f
                            scaleY = if (isDragging) 1.15f else if (isDwellTarget) 0.94f else 1f
                            alpha = if (isDragging) 0.8f else 1f
                            shadowElevation = if (isDragging) 8f else 0f
                        }
                        .animateItem() // Compose 原生让位动画
                        .clickable { onItemClick(item) }
                        .pointerInput(item.key) {
                            var lastStablePos = Offset.Zero
                            var dwellStartTime = 0L

                            detectDragGesturesAfterLongPress(
                                onDragStart = { localPos ->
                                    draggedIndex = index
                                    dragOffset = Offset.Zero
                                    dwellTargetIndex = -1
                                    currentHoverIndex = -1
                                    dwellJob?.cancel()
                                    autoScrollJob?.cancel()

                                    // 触觉反馈：长按激活拖拽
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                                    // 记录起始绝对位置（grid 坐标系）
                                    val itemInfo = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == index }
                                    dragStartAbsPos = Offset(
                                        (itemInfo?.offset?.x ?: 0).toFloat() + localPos.x,
                                        (itemInfo?.offset?.y ?: 0).toFloat() + localPos.y,
                                    )
                                    lastStablePos = dragStartAbsPos
                                    dwellStartTime = System.currentTimeMillis()
                                },
                                onDrag = { change, delta ->
                                    dragOffset += delta
                                    change.consume()

                                    val absPos = dragStartAbsPos + dragOffset
                                    val visInfo = gridState.layoutInfo.visibleItemsInfo
                                    val newHoverIndex = visInfo
                                        .firstOrNull { info ->
                                            val rx = info.offset.x.toFloat()
                                            val ry = info.offset.y.toFloat()
                                            absPos.x in rx..(rx + info.size.width) &&
                                                absPos.y in ry..(ry + info.size.height)
                                        }
                                        ?.index?.coerceIn(0, mutableItems.lastIndex)
                                        ?: -1

                                    // ── dwell 检测 ──
                                    if (newHoverIndex != currentHoverIndex) {
                                        // 移动到新格子，重置 dwell
                                        dwellJob?.cancel()
                                        dwellTargetIndex = -1
                                        currentHoverIndex = newHoverIndex
                                        lastStablePos = absPos
                                        dwellStartTime = System.currentTimeMillis()

                                        // 如果新格子是有效目标，启动 dwell 计时
                                        if (newHoverIndex >= 0 && newHoverIndex != index) {
                                            val targetItem = mutableItems.getOrNull(newHoverIndex)
                                            if (item is LibraryItem.Single && targetItem is LibraryItem.Single) {
                                                dwellJob = scope.launch {
                                                    delay(dwellThresholdMs)
                                                    // 计时结束，高亮目标书
                                                    dwellTargetIndex = newHoverIndex
                                                }
                                            }
                                        }
                                    } else if (newHoverIndex >= 0 && newHoverIndex != index) {
                                        // 同一格子内，检测是否移动过多（抖动）
                                        val moveDistance = (absPos - lastStablePos).getDistance()
                                        if (moveDistance > dwellMoveTolerance.toPx()) {
                                            // 抖动过大，重置 dwell
                                            dwellJob?.cancel()
                                            dwellTargetIndex = -1
                                            lastStablePos = absPos
                                            dwellStartTime = System.currentTimeMillis()
                                        }
                                    }

                                    // ── 自动滚动 ──
                                    val viewportHeight = gridState.layoutInfo.viewportEndOffset -
                                        gridState.layoutInfo.viewportStartOffset
                                    val relativeY = absPos.y - gridState.layoutInfo.viewportStartOffset
                                    val edgeThresholdPx = scrollEdgeThreshold.toPx()

                                    autoScrollJob?.cancel()
                                    val scrollSpeed = when {
                                        relativeY < edgeThresholdPx -> -(edgeThresholdPx - relativeY) / 10f
                                        relativeY > viewportHeight - edgeThresholdPx ->
                                            (relativeY - (viewportHeight - edgeThresholdPx)) / 10f
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
                                    val target = currentHoverIndex
                                    val wasDwelling = dwellTargetIndex >= 0

                                    dwellJob?.cancel()
                                    autoScrollJob?.cancel()
                                    draggedIndex = -1
                                    dragOffset = Offset.Zero
                                    currentHoverIndex = -1
                                    dwellTargetIndex = -1

                                    if (target >= 0 && target != index) {
                                        val targetItem = mutableItems.getOrNull(target)
                                        if (item is LibraryItem.Single && targetItem is LibraryItem.Single) {
                                            if (wasDwelling) {
                                                // dwell 完成 → 建组
                                                groupSourceId = item.book.id
                                                groupTargetItem = targetItem
                                                groupName = ""
                                            } else {
                                                // 快速划过 → 重排
                                                val moved = mutableItems.removeAt(index)
                                                mutableItems.add(target.coerceIn(0, mutableItems.size), moved)
                                                onReorder(mutableItems.toList())
                                            }
                                        } else {
                                            // 非单本 → 重排
                                            val moved = mutableItems.removeAt(index)
                                            mutableItems.add(target.coerceIn(0, mutableItems.size), moved)
                                            onReorder(mutableItems.toList())
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dwellJob?.cancel()
                                    autoScrollJob?.cancel()
                                    draggedIndex = -1
                                    dragOffset = Offset.Zero
                                    currentHoverIndex = -1
                                    dwellTargetIndex = -1
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
                                if (isDwellTarget && draggedIndex >= 0) {
                                    // dwell 预览：显示即将建组的样子
                                    val dragged = mutableItems.getOrNull(draggedIndex)
                                    if (dragged is LibraryItem.Single) {
                                        BundleStack(
                                            bundle = dev.readflow.core.model.BookBundle(
                                                "新书组",
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
                            is LibraryItem.Bundle -> BundleStack(bundle = item.bundle)
                        }

                        // ⋮ 菜单按钮（右上角，视觉 28dp + padding 10dp = 48dp 有效触摸区）
                        IconButton(
                            onClick = {
                                contextItem = item
                                contextMenuAnchor = index
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(28.dp)
                                .padding(10.dp),  // 补偿触摸目标
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "菜单",
                                tint = palette.ink.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        // ── Context menu（锚定到本 item 的封面 Box）──
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
                    if (item is LibraryItem.Single) {
                        Text(
                            text = item.book.author,
                            style = ReadflowType.meta,
                            color = palette.inkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ShelfBoard(modifier = Modifier.padding(top = Dimens.spaceXs))
                }
            }
        }
    }

    // ── Delete confirmation dialog ─────────────────────────────────────────
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
                            // Delete all books in the bundle
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

    // ── Ungroup confirmation dialog ────────────────────────────────────────
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

    // ── Rename dialog ──────────────────────────────────────────────────────
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
                TextButton(
                    onClick = {
                        onRename(ri.book.id, renameText)
                        renameItem = null
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameItem = null }) {
                    Text("取消")
                }
            },
        )
    }

    // ── Create-group dialog ────────────────────────────────────────────────
    if (groupSourceId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                groupSourceId = ""
                groupTargetItem = null
            },
            title = { Text("建组") },
            text = {
                Column {
                    if (groupTargetItem != null) {
                        Text(
                            text = "将与《${groupTargetItem!!.book.title}》建组",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSoft,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
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
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        groupSourceId = ""
                        groupTargetItem = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

private val LibraryItem.key: String
    get() = when (this) {
        is LibraryItem.Single -> "book:${book.id}"
        is LibraryItem.Bundle -> "bundle:${bundle.name}"
    }
