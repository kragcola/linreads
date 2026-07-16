package dev.readflow.render.md

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownPageWindowTest {

    @Test
    fun `paginates into complete line windows without half-line clipping`() {
        // 4 lines of 20px each → page height 45 fits 2 complete lines (40px), not 2.25.
        val geometry = FakeMarkdownGeometry(
            lineTops = intArrayOf(0, 20, 40, 60),
            lineBottoms = intArrayOf(20, 40, 60, 80),
            lineStarts = intArrayOf(0, 10, 20, 30),
            lineEnds = intArrayOf(10, 20, 30, 40),
        )

        val pages = markdownPaginate(geometry, pageHeightPx = 45)

        assertEquals(2, pages.size)
        assertEquals(MarkdownPageWindow(0, 2, 0, 20), pages[0])
        assertEquals(MarkdownPageWindow(2, 4, 20, 40), pages[1])
        pages.forEach { page ->
            assertTrue(page.endLineExclusive > page.startLine, "page must contain whole lines")
            val height = geometry.getLineBottom(page.endLineExclusive - 1) -
                geometry.getLineTop(page.startLine)
            // First page must fit; last page may be shorter.
            if (page !== pages.last()) {
                assertTrue(height <= 45, "non-final page height $height must not exceed viewport")
            }
        }
    }

    @Test
    fun `oversized first line becomes a single page without looping`() {
        // Line 0 is 120px tall; viewport is 50px → one page for that line alone.
        // Lines 1–2 are 20px each and fit together on the next page (40 <= 50).
        val geometry = FakeMarkdownGeometry(
            lineTops = intArrayOf(0, 120, 140),
            lineBottoms = intArrayOf(120, 140, 160),
            lineStarts = intArrayOf(0, 50, 80),
            lineEnds = intArrayOf(50, 80, 100),
        )

        val pages = markdownPaginate(geometry, pageHeightPx = 50)

        assertEquals(2, pages.size)
        assertEquals(MarkdownPageWindow(0, 1, 0, 50), pages[0])
        assertEquals(MarkdownPageWindow(1, 3, 50, 100), pages[1])
        // Progress is strict — no infinite loop on oversized line.
        assertTrue(pages.zipWithNext().all { (a, b) -> b.startLine == a.endLineExclusive })
        assertTrue(pages[0].endLineExclusive > pages[0].startLine)
    }

    @Test
    fun `empty geometry yields a single empty page`() {
        val geometry = FakeMarkdownGeometry(
            lineTops = intArrayOf(),
            lineBottoms = intArrayOf(),
            lineStarts = intArrayOf(),
            lineEnds = intArrayOf(),
        )

        val pages = markdownPaginate(geometry, pageHeightPx = 100)

        assertEquals(listOf(MarkdownPageWindow(0, 0, 0, 0)), pages)
    }

    @Test
    fun `single short document fits on one page`() {
        val geometry = FakeMarkdownGeometry(
            lineTops = intArrayOf(0, 18, 36),
            lineBottoms = intArrayOf(18, 36, 54),
            lineStarts = intArrayOf(0, 12, 24),
            lineEnds = intArrayOf(12, 24, 36),
        )

        val pages = markdownPaginate(geometry, pageHeightPx = 200)

        assertEquals(1, pages.size)
        assertEquals(MarkdownPageWindow(0, 3, 0, 36), pages[0])
    }

    @Test
    fun `pageIndexForRenderedOffset maps to containing page`() {
        val windows = listOf(
            MarkdownPageWindow(0, 2, 0, 20),
            MarkdownPageWindow(2, 4, 20, 40),
            MarkdownPageWindow(4, 5, 40, 55),
        )

        assertEquals(0, pageIndexForRenderedOffset(windows, 0))
        assertEquals(0, pageIndexForRenderedOffset(windows, 19))
        assertEquals(1, pageIndexForRenderedOffset(windows, 20))
        assertEquals(1, pageIndexForRenderedOffset(windows, 39))
        assertEquals(2, pageIndexForRenderedOffset(windows, 40))
        assertEquals(2, pageIndexForRenderedOffset(windows, 999))
    }

    /**
     * Fake geometry with uniform or irregular line heights.
     * [getLineForVertical] walks tops/bottoms like android.text.Layout.
     */
    private class FakeMarkdownGeometry(
        private val lineTops: IntArray,
        private val lineBottoms: IntArray,
        private val lineStarts: IntArray,
        private val lineEnds: IntArray,
    ) : MarkdownLineGeometry {
        override val lineCount: Int = lineTops.size

        override fun getLineTop(line: Int): Int = lineTops[line.coerceIn(0, lineTops.lastIndex.coerceAtLeast(0))]

        override fun getLineBottom(line: Int): Int =
            lineBottoms[line.coerceIn(0, lineBottoms.lastIndex.coerceAtLeast(0))]

        override fun getLineStart(line: Int): Int =
            lineStarts[line.coerceIn(0, lineStarts.lastIndex.coerceAtLeast(0))]

        override fun getLineEnd(line: Int): Int =
            lineEnds[line.coerceIn(0, lineEnds.lastIndex.coerceAtLeast(0))]

        override fun getLineForVertical(y: Int): Int {
            if (lineCount <= 0) return 0
            for (i in 0 until lineCount) {
                if (y < lineBottoms[i]) return i
            }
            return lineCount - 1
        }
    }
}
