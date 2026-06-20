package dev.readflow.core.ui

import androidx.compose.foundation.clickable
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
import kotlin.math.roundToInt

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
    // Mutable list that gets reordered during drag; synced when items changes.
    val mutableItems = remember { mutableStateListOf(*items.toTypedArray()) }
    LaunchedEffect(items) {
        if (items != mutableItems.toList()) { mutableItems.clear(); mutableItems.addAll(items) }
    }

    val gridState = rememberLazyGridState()

    // Drag state
    var draggedIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropIndex by remember { mutableStateOf(-1) }

    // Menus / dialogs
    var contextItem by remember { mutableStateOf<LibraryItem?>(null) }
    var renameItem by remember { mutableStateOf<LibraryItem.Single?>(null) }
    var renameText by remember { mutableStateOf("") }
    var groupTargetItem by remember { mutableStateOf<LibraryItem.Single?>(null) }  // item being grouped with
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
                        .clickable { onItemClick(item) }
                        .pointerInput(item.key) {
                            var totalDrag = Offset.Zero
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    totalDrag = Offset.Zero
                                    draggedIndex = index
                                    dragOffset = Offset.Zero
                                },
                                onDrag = { change, delta ->
                                    totalDrag += delta
                                    dragOffset += delta
                                    change.consume()
                                    // Compute drop target from grid item positions
                                    val absX = (change.position.x + dragOffset.x)
                                    val absY = (change.position.y + dragOffset.y)
                                    val visInfo = gridState.layoutInfo.visibleItemsInfo
                                    dropIndex = visInfo
                                        .firstOrNull { info ->
                                            val r = info.offset
                                            val s = info.size
                                            absX in r.x.toFloat()..(r.x + s.width).toFloat() &&
                                                absY in r.y.toFloat()..(r.y + s.height).toFloat()
                                        }
                                        ?.index?.coerceIn(0, mutableItems.lastIndex)
                                        ?: dropIndex
                                },
                                onDragEnd = {
                                    val movedFar = totalDrag.getDistance() > 20.dp.toPx()
                                    val target = dropIndex
                                    draggedIndex = -1
                                    dragOffset = Offset.Zero
                                    dropIndex = -1
                                    if (!movedFar) {
                                        contextItem = item
                                    } else if (target >= 0 && target != index) {
                                        val targetItem = mutableItems.getOrNull(target)
                                        if (item is LibraryItem.Single && targetItem is LibraryItem.Single) {
                                            // Drag onto another single book → create group
                                            groupSourceId = item.book.id
                                            groupTargetItem = targetItem
                                            groupName = ""
                                        } else {
                                            // Reorder
                                            val moved = mutableItems.removeAt(index)
                                            mutableItems.add(target.coerceIn(0, mutableItems.size), moved)
                                            onReorder(mutableItems.toList())
                                        }
                                    }
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
                                // Show group-merge preview when this item is a drop target
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

    // ── Context menu ─────────────────────────────────────────────────────────
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

    // ── Rename dialog ─────────────────────────────────────────────────────────
    renameItem?.let { ri ->
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text("改名") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    singleLine = true, label = { Text("书名") })
            },
            confirmButton = {
                TextButton(onClick = { onRename(ri.book.id, renameText); renameItem = null }) {
                    Text("确定")
                }
            },
            dismissButton = { TextButton(onClick = { renameItem = null }) { Text("取消") } },
        )
    }

    // ── Create-group dialog (drag or menu) ────────────────────────────────────
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
