package dev.readflow.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookGridDragLogicTest {

    @Test
    fun `committed reorder uses the live preview without a second settlement animation`() {
        assertEquals(
            false,
            shouldAnimateDropSettlement(
                startOrder = listOf("A", "B", "C"),
                finalOrder = listOf("B", "A", "C"),
            ),
        )
        assertEquals(
            true,
            shouldAnimateDropSettlement(
                startOrder = listOf("A", "B", "C"),
                finalOrder = listOf("A", "B", "C"),
            ),
        )
    }

    @Test
    fun `moving penultimate item to terminal insertion slot makes it last`() {
        val items = listOf("A", "B", "C", "D")

        val moved = moveItemToInsertionSlot(
            items = items,
            sourceIndex = 2,
            insertionSlot = items.size,
        )

        assertEquals(listOf("A", "B", "D", "C"), moved)
    }

    @Test
    fun `moving first item to terminal insertion slot makes it last`() {
        val items = listOf("A", "B", "C", "D")

        val moved = moveItemToInsertionSlot(
            items = items,
            sourceIndex = 0,
            insertionSlot = items.size,
        )

        assertEquals(listOf("B", "C", "D", "A"), moved)
    }

    @Test
    fun `moving forward preserves the relative order of crossed items`() {
        val items = listOf("A", "B", "C", "D", "E")

        val moved = moveItemToInsertionSlot(
            items = items,
            sourceIndex = 1,
            insertionSlot = 4,
        )

        assertEquals(listOf("A", "C", "D", "B", "E"), moved)
    }

    @Test
    fun `moving backward preserves the relative order of crossed items`() {
        val items = listOf("A", "B", "C", "D", "E")

        val moved = moveItemToInsertionSlot(
            items = items,
            sourceIndex = 3,
            insertionSlot = 1,
        )

        assertEquals(listOf("A", "D", "B", "C", "E"), moved)
    }

    @Test
    fun `invalid source index leaves the list unchanged`() {
        val items = listOf("A", "B", "C")

        assertEquals(
            items,
            moveItemToInsertionSlot(items, sourceIndex = -1, insertionSlot = 1),
        )
        assertEquals(
            items,
            moveItemToInsertionSlot(items, sourceIndex = items.size, insertionSlot = 1),
        )
        assertEquals(
            emptyList<String>(),
            moveItemToInsertionSlot(emptyList<String>(), sourceIndex = 0, insertionSlot = 0),
        )
    }

    @Test
    fun `insertion slot is clamped to the safe range`() {
        val items = listOf("A", "B", "C", "D")

        assertEquals(
            listOf("C", "A", "B", "D"),
            moveItemToInsertionSlot(items, sourceIndex = 2, insertionSlot = -100),
        )
        assertEquals(
            listOf("A", "C", "D", "B"),
            moveItemToInsertionSlot(items, sourceIndex = 1, insertionSlot = 100),
        )
    }

    @Test
    fun `moving an item does not mutate the input list`() {
        val items = mutableListOf("A", "B", "C", "D")
        val original = items.toList()

        val moved = moveItemToInsertionSlot(items, sourceIndex = 1, insertionSlot = 4)

        assertEquals(listOf("A", "C", "D", "B"), moved)
        assertEquals(original, items)
    }

    @Test
    fun `merge candidate matches only when every edge is strictly within tolerance`() {
        val dragged = rect(left = 100f, top = 100f, right = 200f, bottom = 240f)
        val candidate = candidate(
            index = 7,
            rect = rect(left = 103f, top = 96f, right = 198f, bottom = 245f),
        )

        val resolved = resolveMergeCandidate(
            sourceIndex = 1,
            draggedRect = dragged,
            candidates = listOf(candidate),
            tolerancePx = 6f,
        )

        assertEquals(7, resolved)
    }

    @Test
    fun `merge candidate does not match when any edge equals tolerance`() {
        val dragged = rect(left = 100f, top = 100f, right = 200f, bottom = 200f)
        val candidatesAtBoundary = listOf(
            candidate(1, rect(left = 110f, top = 101f, right = 199f, bottom = 201f)),
            candidate(2, rect(left = 101f, top = 110f, right = 199f, bottom = 201f)),
            candidate(3, rect(left = 101f, top = 101f, right = 210f, bottom = 199f)),
            candidate(4, rect(left = 101f, top = 101f, right = 199f, bottom = 210f)),
        )

        candidatesAtBoundary.forEach { candidate ->
            assertNull(
                resolveMergeCandidate(
                    sourceIndex = 99,
                    draggedRect = dragged,
                    candidates = listOf(candidate),
                    tolerancePx = 10f,
                ),
                "Candidate ${candidate.index} has one edge exactly at tolerance",
            )
        }
    }

    @Test
    fun `merge resolution ignores the source candidate`() {
        val dragged = rect(left = 20f, top = 30f, right = 120f, bottom = 170f)
        val source = candidate(index = 3, rect = dragged)
        val other = candidate(
            index = 8,
            rect = rect(left = 21f, top = 29f, right = 121f, bottom = 169f),
        )

        val resolved = resolveMergeCandidate(
            sourceIndex = 3,
            draggedRect = dragged,
            candidates = listOf(source, other),
            tolerancePx = 3f,
        )

        assertEquals(8, resolved)
    }

    @Test
    fun `merge resolution chooses the hit with the smallest total edge difference`() {
        val dragged = rect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        val farther = candidate(
            index = 4,
            rect = rect(left = 3f, top = 3f, right = 103f, bottom = 103f),
        )
        val nearer = candidate(
            index = 9,
            rect = rect(left = 1f, top = 2f, right = 99f, bottom = 102f),
        )

        val resolved = resolveMergeCandidate(
            sourceIndex = 0,
            draggedRect = dragged,
            candidates = listOf(farther, nearer),
            tolerancePx = 5f,
        )

        assertEquals(9, resolved)
    }

    @Test
    fun `merge resolution returns null when geometry does not match`() {
        val resolved = resolveMergeCandidate(
            sourceIndex = 0,
            draggedRect = rect(left = 0f, top = 0f, right = 100f, bottom = 100f),
            candidates = listOf(
                candidate(2, rect(left = 6f, top = 1f, right = 101f, bottom = 101f)),
            ),
            tolerancePx = 5f,
        )

        assertNull(resolved)
    }

    @Test
    fun `rightward reorder returns the slot after the containing candidate`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 0,
            draggedRect = rectCenteredAt(centerX = 66f, centerY = 50f),
            candidates = listOf(
                candidate(2, rect(left = 120f, top = 0f, right = 220f, bottom = 100f)),
                candidate(4, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
            ),
            totalDragX = 80f,
            totalDragY = 10f,
            thresholdPx = 15f,
        )

        assertEquals(5, resolved)
    }

    @Test
    fun `leftward reorder returns the slot before the containing candidate`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 0,
            draggedRect = rectCenteredAt(centerX = 34f, centerY = 50f),
            candidates = listOf(
                candidate(4, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
            ),
            totalDragX = -80f,
            totalDragY = 10f,
            thresholdPx = 15f,
        )

        assertEquals(4, resolved)
    }

    @Test
    fun `downward reorder returns the slot after the containing candidate`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 0,
            draggedRect = rectCenteredAt(centerX = 50f, centerY = 66f),
            candidates = listOf(
                candidate(4, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
            ),
            totalDragX = 10f,
            totalDragY = 80f,
            thresholdPx = 15f,
        )

        assertEquals(5, resolved)
    }

    @Test
    fun `upward reorder returns the slot before the containing candidate`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 0,
            draggedRect = rectCenteredAt(centerX = 50f, centerY = 34f),
            candidates = listOf(
                candidate(4, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
            ),
            totalDragX = 10f,
            totalDragY = -80f,
            thresholdPx = 15f,
        )

        assertEquals(4, resolved)
    }

    @Test
    fun `reorder waits while dragged center is within or exactly at center threshold`() {
        val target = candidate(
            index = 4,
            rect = rect(left = 0f, top = 0f, right = 100f, bottom = 100f),
        )

        assertNull(
            resolveReorderInsertionSlot(
                sourceIndex = 0,
                draggedRect = rectCenteredAt(centerX = 64f, centerY = 50f),
                candidates = listOf(target),
                totalDragX = 80f,
                totalDragY = 10f,
                thresholdPx = 15f,
            ),
        )
        assertNull(
            resolveReorderInsertionSlot(
                sourceIndex = 0,
                draggedRect = rectCenteredAt(centerX = 65f, centerY = 50f),
                candidates = listOf(target),
                totalDragX = 80f,
                totalDragY = 10f,
                thresholdPx = 15f,
            ),
        )
    }

    @Test
    fun `reorder does not trigger past the threshold on the opposite side of travel`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 0,
            draggedRect = rectCenteredAt(centerX = 30f, centerY = 50f),
            candidates = listOf(
                candidate(4, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
            ),
            totalDragX = 80f,
            totalDragY = 10f,
            thresholdPx = 15f,
        )

        assertNull(resolved)
    }

    @Test
    fun `reorder ignores a containing source candidate`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 4,
            draggedRect = rectCenteredAt(centerX = 66f, centerY = 50f),
            candidates = listOf(
                candidate(4, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
            ),
            totalDragX = 80f,
            totalDragY = 10f,
            thresholdPx = 15f,
        )

        assertNull(resolved)
    }

    @Test
    fun `reorder considers only the candidate containing the dragged center`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 0,
            draggedRect = rectCenteredAt(centerX = 171f, centerY = 50f),
            candidates = listOf(
                candidate(2, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
                candidate(7, rect(left = 100f, top = 0f, right = 200f, bottom = 100f)),
            ),
            totalDragX = 100f,
            totalDragY = 10f,
            thresholdPx = 20f,
        )

        assertEquals(8, resolved)
    }

    @Test
    fun `reorder returns null when no non-source candidate contains the dragged center`() {
        val resolved = resolveReorderInsertionSlot(
            sourceIndex = 0,
            draggedRect = rectCenteredAt(centerX = 250f, centerY = 250f),
            candidates = listOf(
                candidate(3, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
            ),
            totalDragX = 100f,
            totalDragY = 10f,
            thresholdPx = 15f,
        )

        assertNull(resolved)
    }

    @Test
    fun `right edge beyond the final visible book resolves the terminal insertion slot`() {
        val resolved = resolveTrailingInsertionSlot(
            sourceIndex = 2,
            draggedRect = rectCenteredAt(centerX = 450f, centerY = 50f),
            candidates = listOf(
                candidate(0, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
                candidate(1, rect(left = 110f, top = 0f, right = 210f, bottom = 100f)),
                candidate(2, rect(left = 220f, top = 0f, right = 320f, bottom = 100f)),
                candidate(3, rect(left = 330f, top = 0f, right = 430f, bottom = 100f)),
            ),
            itemCount = 4,
            totalDragX = 200f,
            totalDragY = 0f,
        )

        assertEquals(4, resolved)
    }

    @Test
    fun `right edge of a partially visible row resolves after that row instead of shelf end`() {
        val resolved = resolveTrailingInsertionSlot(
            sourceIndex = 4,
            draggedRect = rectCenteredAt(centerX = 340f, centerY = 50f),
            candidates = listOf(
                candidate(5, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
                candidate(6, rect(left = 110f, top = 0f, right = 210f, bottom = 100f)),
                candidate(7, rect(left = 220f, top = 0f, right = 320f, bottom = 100f)),
            ),
            itemCount = 20,
            totalDragX = 200f,
            totalDragY = 0f,
        )

        assertEquals(8, resolved)
    }

    @Test
    fun `dragging above the first visible row resolves its first insertion slot`() {
        val resolved = resolveTrailingInsertionSlot(
            sourceIndex = 8,
            draggedRect = rectCenteredAt(centerX = 50f, centerY = -20f),
            candidates = listOf(
                candidate(5, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
                candidate(6, rect(left = 110f, top = 0f, right = 210f, bottom = 100f)),
            ),
            itemCount = 20,
            totalDragX = 0f,
            totalDragY = -100f,
        )

        assertEquals(5, resolved)
    }

    @Test
    fun `trailing slot ignores overshoot opposite to drag direction`() {
        val resolved = resolveTrailingInsertionSlot(
            sourceIndex = 1,
            draggedRect = rectCenteredAt(centerX = 340f, centerY = 50f),
            candidates = listOf(
                candidate(0, rect(left = 0f, top = 0f, right = 100f, bottom = 100f)),
                candidate(1, rect(left = 110f, top = 0f, right = 210f, bottom = 100f)),
                candidate(2, rect(left = 220f, top = 0f, right = 320f, bottom = 100f)),
            ),
            itemCount = 3,
            totalDragX = -200f,
            totalDragY = 0f,
        )

        assertNull(resolved)
    }

    private fun rect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): GridDragRect = GridDragRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    private fun rectCenteredAt(
        centerX: Float,
        centerY: Float,
        size: Float = 10f,
    ): GridDragRect {
        val halfSize = size / 2f
        return rect(
            left = centerX - halfSize,
            top = centerY - halfSize,
            right = centerX + halfSize,
            bottom = centerY + halfSize,
        )
    }

    private fun candidate(index: Int, rect: GridDragRect): GridDropCandidate =
        GridDropCandidate(index = index, rect = rect)
}
