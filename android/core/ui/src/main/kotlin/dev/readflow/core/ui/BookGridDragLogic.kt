package dev.readflow.core.ui

import kotlin.math.abs

internal data class GridDragRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom
}

internal data class GridDropCandidate(
    val index: Int,
    val rect: GridDragRect,
)

internal fun <T> moveItemToInsertionSlot(
    items: List<T>,
    sourceIndex: Int,
    insertionSlot: Int,
): List<T> {
    if (sourceIndex !in items.indices) return items

    val safeSlot = insertionSlot.coerceIn(0, items.size)
    val targetIndex = if (safeSlot > sourceIndex) safeSlot - 1 else safeSlot
    if (targetIndex == sourceIndex) return items

    return items.toMutableList().apply {
        val moved = removeAt(sourceIndex)
        add(targetIndex.coerceIn(0, size), moved)
    }
}

internal fun resolveMergeCandidate(
    sourceIndex: Int,
    draggedRect: GridDragRect,
    candidates: List<GridDropCandidate>,
    tolerancePx: Float,
): Int? {
    if (tolerancePx <= 0f) return null

    return candidates.asSequence()
        .filter { it.index != sourceIndex }
        .mapNotNull { candidate ->
            val edgeDifference = listOf(
                abs(draggedRect.left - candidate.rect.left),
                abs(draggedRect.top - candidate.rect.top),
                abs(draggedRect.right - candidate.rect.right),
                abs(draggedRect.bottom - candidate.rect.bottom),
            )
            if (edgeDifference.all { it < tolerancePx }) {
                candidate.index to edgeDifference.sum()
            } else {
                null
            }
        }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

internal fun resolveReorderInsertionSlot(
    sourceIndex: Int,
    draggedRect: GridDragRect,
    candidates: List<GridDropCandidate>,
    totalDragX: Float,
    totalDragY: Float,
    thresholdPx: Float,
): Int? {
    val candidate = candidates.firstOrNull {
        it.index != sourceIndex && it.rect.contains(draggedRect.centerX, draggedRect.centerY)
    } ?: return null
    val safeThreshold = thresholdPx.coerceAtLeast(0f)

    return if (abs(totalDragX) >= abs(totalDragY)) {
        when {
            totalDragX > 0f && draggedRect.centerX - candidate.rect.centerX > safeThreshold ->
                candidate.index + 1
            totalDragX < 0f && candidate.rect.centerX - draggedRect.centerX > safeThreshold ->
                candidate.index
            else -> null
        }
    } else {
        when {
            totalDragY > 0f && draggedRect.centerY - candidate.rect.centerY > safeThreshold ->
                candidate.index + 1
            totalDragY < 0f && candidate.rect.centerY - draggedRect.centerY > safeThreshold ->
                candidate.index
            else -> null
        }
    }
}

internal fun resolveTrailingInsertionSlot(
    sourceIndex: Int,
    draggedRect: GridDragRect,
    candidates: List<GridDropCandidate>,
    itemCount: Int,
    totalDragX: Float,
    totalDragY: Float,
): Int? {
    if (itemCount <= 0) return null
    val targets = candidates.filter { it.index != sourceIndex && it.index in 0 until itemCount }
    if (targets.isEmpty()) return null
    val centerX = draggedRect.centerX
    val centerY = draggedRect.centerY
    val first = targets.minBy(GridDropCandidate::index)
    val last = targets.maxBy(GridDropCandidate::index)

    if (totalDragY < 0f && centerY < targets.minOf { it.rect.top }) return first.index
    if (totalDragY > 0f && centerY > targets.maxOf { it.rect.bottom }) {
        return if (last.index >= itemCount - 1) itemCount else last.index + 1
    }

    val sameRow = targets.filter { centerY in it.rect.top..it.rect.bottom }
    if (sameRow.isEmpty()) return null
    val leftmost = sameRow.minBy { it.rect.left }
    val rightmost = sameRow.maxBy { it.rect.right }
    return when {
        totalDragX < 0f && centerX < leftmost.rect.left -> leftmost.index
        totalDragX > 0f && centerX > rightmost.rect.right ->
            (rightmost.index + 1).coerceAtMost(itemCount)
        else -> null
    }
}
