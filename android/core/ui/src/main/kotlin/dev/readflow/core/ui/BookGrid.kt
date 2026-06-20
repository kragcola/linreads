package dev.readflow.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.readflow.core.model.LibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGrid(
    items: List<LibraryItem>,
    onItemClick: (LibraryItem) -> Unit,
    onDelete: (String) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onMoveToGroup: (String, String) -> Unit = { _, _ -> },
    onReorder: (List<LibraryItem>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette
    val mutableItems = remember { mutableStateListOf(*items.toTypedArray()) }
    LaunchedEffect(items) {
        if (items != mutableItems.toList()) { mutableItems.clear(); mutableItems.addAll(items) }
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var draggedIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartAbsPos by remember { mutableStateOf(Offset.Zero) }
    var dropIndex by remember { mutableStateOf(-1) }

    var contextItem by remember { mutableStateOf<LibraryItem?>(null) }
    var renameItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var renameText by remember { mutableStateOf("") }
    var groupTargetItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var groupSourceId by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }

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
                val isDropTarget = dropIndex == index && draggedIndex >= 0 && draggedIndex != index

                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (isDragging) dragOffset.x else 0f
                            translationY = if (isDragging) dragOffset.y else 0f
                            scaleX = if (isDragging) 1.06f else if (isDropTarget) 0.94f else 1f
                            scaleY = if (isDragging) 1.06f else if (isDropTarget) 0.94f else 1f
                            alpha = if (isDragging) 0.85f else 1f
                        }
                        // 长按→菜单；点击→打开。与下方拖动手势互斥：拖动一旦开始 draggedIndex==index，
                        // onLongClick 里延迟 80ms 后检测到此状态则不弹菜单。
                        .combinedClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = {
                                scope.launch {
                                    delay(80)
                                    if (draggedIndex != index) contextItem = item
                                }
                            },
                        )
                        .pointerInput(item.key) {
                            var totalDrag = Offset.Zero
                            detectDragGesturesAfterLongPress(
                                onDragStart = { localPos ->
                                    totalDrag = Offset.Zero
                                    draggedIndex = index
                                    dragOffset = Offset.Zero
                                    // 记录拖动起始点在 grid 坐标系内的绝对位置
                                    val itemInfo = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == index }
                                    dragStartAbsPos = Offset(
                                        (itemInfo?.offset?.x ?: 0).toFloat() + localPos.x,
                                        (itemInfo?.offset?.y ?: 0).toFloat() + localPos.y,
                                    )
                                },
                                onDrag = { change, delta ->
                                    totalDrag += delta
                                    dragOffset += delta
                                    change.consume()
                                    // 用 grid 坐标系绝对位置命中目标
                                    val absPos = dragStartAbsPos + dragOffset
                                    val visInfo = gridState.layoutInfo.visibleItemsInfo
                                    dropIndex = visInfo
                                        .firstOrNull { info ->
                                            val rx = info.offset.x.toFloat()
                                            val ry = info.offset.y.toFloat()
                                            absPos.x in rx..(rx + info.size.width) &&
                                                absPos.y in ry..(ry + info.size.height)
                                        }
                                        ?.index?.coerceIn(0, mutableItems.lastIndex)
                                        ?: dropIndex
                                },
                                onDragEnd = {
                                    val target = dropIndex
                                    draggedIndex = -1
                                    dragOffset = Offset.Zero
                                    dropIndex = -1
                                    if (totalDrag.getDistance() > 20.dp.toPx() && target >= 0 && target != index) {
                                        val targetItem = mutableItems.getOrNull(target)
                                        if (item is LibraryItem.Single && targetItem is LibraryItem.Single) {
                                            groupSourceId = item.book.id
                                            groupTargetItem = targetItem
                                            groupName = ""
                                        } else {
                                            val moved = mutableItems.removeAt(index)
                                            mutableItems.add(target.coerceIn(0, mutableItems.size), moved)
                                            onReorder(mutableItems.toList())
                                        }
                                    }
                                    // 注意：不在此处触发菜单，菜单由 combinedClickable.onLongClick 负责
                                },
                                onDragCancel = {
                                    draggedIndex = -1
                                    dragOffset = Offset.Zero
                                    dropIndex = -1
                                },
                            )
                        },
                ) {
                    Box(
                        modifier = Modifier.aspectRatio(Dimens.coverAspectRatio).padding(bottom = Dimens.spaceXs),
                    ) {
                        when (item) {
                            is LibraryItem.Single -> {
                                if (isDropTarget && draggedIndex >= 0) {
                                    val dragged = mutableItems.getOrNull(draggedIndex)
                                    if (dragged is LibraryItem.Single) {
                                        BundleStack(
                                            bundle = dev.readflow.core.model.BookBundle(
                                                "新书组", listOf(dragged.book, item.book)
                                            ),
                                        )
                                    } else {
                                        BookCover(book = item.book, modifier = Modifier.fillMaxSize())
                                    }
                                } else {
                                    BookCover(book = item.book, modifier = Modifier.fillMaxSize())
                                    if (item.book.lastReadAt != null && item.book.progress > 0f) {
                                        PaperBookmark(book = item.book,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp))
                                    }
                                }
                            }
                            is LibraryItem.Bundle -> BundleStack(bundle = item.bundle)
                        }
                    }
                    val title = when (item) {
                        is LibraryItem.Single -> item.book.title
                        is LibraryItem.Bundle -> item.bundle.name
                    }
                    Text(text = title, style = ReadflowType.bookTitle, color = palette.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item is LibraryItem.Single)
                        Text(text = item.book.author, style = ReadflowType.meta, color = palette.inkSoft,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ShelfBoard(modifier = Modifier.padding(top = Dimens.spaceXs))
                }
            }
        }
    }

    // ── Context menu ──────────────────────────────────────────────────────────
    contextItem?.let { ci ->
        val id = when (ci) {
            is LibraryItem.Single -> ci.book.id
            is LibraryItem.Bundle -> null
        }
        DropdownMenu(expanded = true, onDismissRequest = { contextItem = null }) {
            if (ci is LibraryItem.Single) {
                DropdownMenuItem(text = { Text("改名") }, onClick = {
                    renameText = ci.book.title; renameItem = ci; contextItem = null
                })
                DropdownMenuItem(text = { Text("建组") }, onClick = {
                    groupSourceId = ci.book.id; groupTargetItem = null
                    groupName = ""; contextItem = null
                })
            }
            if (id != null) {
                DropdownMenuItem(text = { Text("删除") }, onClick = {
                    onDelete(id); contextItem = null
                })
            }
        }
    }

    // ── Rename dialog ──────────────────────────────────────────────────────────
    renameItem?.let { ri ->
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text("改名") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    singleLine = true, label = { Text("书名") })
            },
            confirmButton = {
                TextButton(onClick = { onRename(ri.book.id, renameText); renameItem = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameItem = null }) { Text("取消") } },
        )
    }

    // ── Create-group dialog ────────────────────────────────────────────────────
    if (groupSourceId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { groupSourceId = ""; groupTargetItem = null },
            title = { Text("建组") },
            text = {
                OutlinedTextField(value = groupName, onValueChange = { groupName = it },
                    singleLine = true, label = { Text("组名") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (groupName.isNotBlank()) {
                        onMoveToGroup(groupSourceId, groupName)
                        groupTargetItem?.let { onMoveToGroup(it.book.id, groupName) }
                    }
                    groupSourceId = ""; groupTargetItem = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { groupSourceId = ""; groupTargetItem = null }) { Text("取消") }
            },
        )
    }
}

private val LibraryItem.key: String get() = when (this) {
    is LibraryItem.Single -> "book:${book.id}"
    is LibraryItem.Bundle -> "bundle:${bundle.name}"
}
