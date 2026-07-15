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

    private class OffsetGeometry(
        private val starts: IntArray,
        private val ends: IntArray,
        private val lineHeight: Int = 10,
    ) : LineGeometry {
        override val lineCount: Int = starts.size
        override fun getLineTop(line: Int) = line.coerceIn(0, lineCount) * lineHeight
        override fun getLineBottom(line: Int) = (line + 1).coerceIn(0, lineCount) * lineHeight
        override fun getLineStart(line: Int) = starts[line.coerceIn(starts.indices)]
        override fun getLineEnd(line: Int) = ends[line.coerceIn(ends.indices)]
        override fun getLineForVertical(y: Int) = (y / lineHeight).coerceIn(0, lineCount - 1)
        override fun getLineForOffset(offset: Int): Int =
            ends.indexOfFirst { end -> offset < end }
                .takeIf { it >= 0 }
                ?: ends.lastIndex
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
    fun `page from an arbitrary start line fills forward and preserves break policy`() {
        val page = epubPageFromStartLine(
            geometry = UniformGeometry(lineCount = 8),
            startLine = 2,
            pageHeightPx = 30,
            isHeadingLine = { it == 4 },
            paragraphLineRange = { line -> if (line == 4) 4..5 else line..line },
        )

        assertEquals(
            EpubFlowPage(
                startLine = 2,
                endLineExclusive = 4,
                startOffset = 10,
                endOffset = 20,
                topPx = 20,
                bottomPx = 40,
            ),
            page,
        )
    }

    @Test
    fun `page ending at a fixed line takes the earliest complete lines that fit`() {
        val page = epubPageEndingAtLine(
            geometry = UniformGeometry(lineCount = 8),
            endLineExclusive = 6,
            pageHeightPx = 25,
        )

        assertEquals(
            EpubFlowPage(
                startLine = 4,
                endLineExclusive = 6,
                startOffset = 20,
                endOffset = 30,
                topPx = 40,
                bottomPx = 60,
            ),
            page,
        )
    }

    @Test
    fun `measured vertical padding is excluded before computing full line page bounds`() {
        val measuredViewportHeight = 45
        val measuredPaddingTop = 7
        val measuredPaddingBottom = 8
        val measuredContentHeight = measuredViewportHeight - measuredPaddingTop - measuredPaddingBottom

        val pages = epubPaginateFlow(
            geometry = UniformGeometry(lineCount = 7),
            pageHeightPx = measuredContentHeight,
        )

        assertEquals("three complete 10px lines fit inside the measured 30px content band", 3, pages[0].endLineExclusive)
        assertEquals(3, pages[1].startLine)
        assertTrue(
            "every ordinary page must stay inside the measured content band",
            pages.all { page -> page.bottomPx - page.topPx <= measuredContentHeight },
        )
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
    fun `heading and following image move together when a separator reaches the page bottom`() {
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Text("AB", headingLevel = null, paragraphIndex = 0),
                EpubDisplayBlock.Text("Illustration", headingLevel = 2, paragraphIndex = 1),
                EpubDisplayBlock.Image("images/plate.png", altText = "Plate", paragraphIndex = 2),
            ),
        )
        val body = flow.segments[0]
        val heading = flow.segments[1]
        val image = flow.segments[2]
        val geometry = OffsetGeometry(
            starts = intArrayOf(
                body.layoutStart,
                body.layoutStart + 1,
                heading.layoutStart,
                heading.layoutEnd,
                image.layoutStart,
            ),
            ends = intArrayOf(
                body.layoutStart + 1,
                heading.layoutStart,
                heading.layoutEnd,
                image.layoutStart,
                image.layoutEnd,
            ),
        )

        val pages = epubPaginateFlow(
            geometry = geometry,
            pageHeightPx = 40,
            isHeadingLine = { line ->
                geometry.getLineStart(line) < heading.layoutEnd &&
                    geometry.getLineEnd(line) > heading.layoutStart
            },
            keepTogetherLineRange = { line ->
                if (line in 2..4) 2..5 else null
            },
        )

        assertEquals(
            "the separator must not strand the heading on the previous page",
            2,
            pages[0].endLineExclusive,
        )
        assertEquals(2, pages[1].startLine)
        assertEquals(5, pages[1].endLineExclusive)
        assertTrue("the complete heading-image group must fit without clipping", pages[1].bottomPx - pages[1].topPx <= 40)
        assertTrue(heading.layoutStart >= pages[1].startOffset && heading.layoutEnd <= pages[1].endOffset)
        assertTrue(image.layoutStart >= pages[1].startOffset && image.layoutEnd <= pages[1].endOffset)
        assertEquals("the image locator must remain mapped to its source block", 2 to 0, flow.paragraphAtOffset(image.layoutStart))
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
