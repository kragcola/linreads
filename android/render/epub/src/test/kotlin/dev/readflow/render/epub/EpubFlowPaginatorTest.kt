package dev.readflow.render.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 tests for the continuous-flow chapter model + paginator. Pure logic, no Android layout:
 * line geometry is faked so windowing/fragmentation rules are verified deterministically.
 */
class EpubFlowPaginatorTest {

    /** Uniform-height lines, each line spans [charsPerLine] chars. */
    private class UniformGeometry(
        override val lineCount: Int,
        val lineHeight: Int = 10,
        val charsPerLine: Int = 5,
    ) : LineGeometry {
        override fun getLineTop(line: Int) = line.coerceIn(0, lineCount) * lineHeight
        override fun getLineBottom(line: Int) = (line + 1).coerceIn(0, lineCount) * lineHeight
        override fun getLineStart(line: Int) = line.coerceIn(0, lineCount) * charsPerLine
        override fun getLineEnd(line: Int) = (line + 1).coerceIn(0, lineCount) * charsPerLine
        override fun getLineForVertical(y: Int) = (y / lineHeight).coerceIn(0, lineCount - 1)
        override fun getLineForOffset(offset: Int) = (offset / charsPerLine).coerceIn(0, lineCount - 1)
    }

    /** Variable-height lines (e.g. an oversized image line). */
    private class VariableGeometry(val heights: IntArray) : LineGeometry {
        private val tops = IntArray(heights.size + 1).also {
            for (i in heights.indices) it[i + 1] = it[i] + heights[i]
        }
        override val lineCount = heights.size
        override fun getLineTop(line: Int) = tops[line.coerceIn(0, heights.size)]
        override fun getLineBottom(line: Int) = tops[(line + 1).coerceIn(0, heights.size)]
        override fun getLineStart(line: Int) = line.coerceIn(0, heights.size)
        override fun getLineEnd(line: Int) = (line + 1).coerceIn(0, heights.size)
        override fun getLineForVertical(y: Int): Int {
            for (i in heights.indices) if (y < tops[i + 1]) return i
            return heights.lastIndex.coerceAtLeast(0)
        }
        override fun getLineForOffset(offset: Int) = offset.coerceIn(0, heights.lastIndex.coerceAtLeast(0))
    }

    @Test
    fun `fills each page to the viewport then breaks`() {
        // 10 lines @ 10px, page 30px → 3 lines per page → pages [0,3) [3,6) [6,9) [9,10).
        val pages = epubPaginateFlow(UniformGeometry(lineCount = 10), pageHeightPx = 30)
        assertEquals(4, pages.size)
        assertEquals(0, pages[0].startLine)
        assertEquals(3, pages[0].endLineExclusive)
        assertEquals(3, pages[1].startLine)
        assertEquals(9, pages[3].startLine)
        assertEquals(10, pages[3].endLineExclusive)
    }

    @Test
    fun `backs off a line clipped by the page bottom`() {
        // page 25px, lines 10px: getLineForVertical(24)=line2 whose bottom 30>25 → drop to line1.
        val pages = epubPaginateFlow(UniformGeometry(lineCount = 6), pageHeightPx = 25)
        assertEquals(2, pages[0].endLineExclusive) // lines 0..1 only, not the clipped line 2
        assertEquals(2, pages[1].startLine)
    }

    @Test
    fun `oversized image line still occupies its own page`() {
        // line1 is 100px tall, page is 30px. It cannot fit but must not loop forever.
        val pages = epubPaginateFlow(VariableGeometry(intArrayOf(10, 100, 10)), pageHeightPx = 30)
        // page0 = line0; page1 = the oversized line1 alone; page2 = line2
        assertTrue(pages.any { it.startLine == 1 && it.endLineExclusive == 2 })
        assertEquals(3, pages.size)
    }

    @Test
    fun `heading at page bottom is pushed to next page with its body`() {
        // 6 lines, page = 30px (3 lines). Line 2 is a heading, line 3 its body → keep-with-next
        // should pull the break up so the heading leaves the first page.
        val pages = epubPaginateFlow(
            geometry = UniformGeometry(lineCount = 6),
            pageHeightPx = 30,
            isHeadingLine = { it == 2 },
            paragraphLineRange = { line -> if (line == 2) 2..3 else line..(line + 1) },
        )
        assertEquals(2, pages[0].endLineExclusive) // heading (line2) NOT on page0
        assertEquals(2, pages[1].startLine) // heading starts page1
    }

    @Test
    fun `orphans rule does not strand a single first line at page bottom`() {
        // page = 30px (3 lines). A paragraph occupies lines 2..5; only line2 (1<ORPHANS=2) would sit
        // at the bottom of page0 → push the whole paragraph start to the next page.
        val pages = epubPaginateFlow(
            geometry = UniformGeometry(lineCount = 6),
            pageHeightPx = 30,
            paragraphLineRange = { line -> if (line in 2..5) 2..6 else line..(line + 1) },
        )
        assertEquals(2, pages[0].endLineExclusive)
    }

    @Test
    fun `widows rule does not carry a single last line to the next page`() {
        // page = 30px (3 lines). A paragraph occupies lines 0..3 (range 0..4 half-open). The raw cut
        // at line 2 would carry only line 3 (1 < WIDOWS=2) to page1 → pull the break up to line 1 so
        // 2 lines carry over.
        val pages = epubPaginateFlow(
            geometry = UniformGeometry(lineCount = 4),
            pageHeightPx = 30,
            paragraphLineRange = { _ -> 0..4 },
        )
        // page0 should end before the widow: lines 0..1 only (endExclusive=2), carrying 2..3 over.
        assertEquals(2, pages[0].endLineExclusive)
        assertEquals(2, pages[1].startLine)
    }
}
