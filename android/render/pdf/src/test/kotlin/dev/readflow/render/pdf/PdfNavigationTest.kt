package dev.readflow.render.pdf

import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TocEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PdfNavigationTest {

    @Test
    fun `pdfTargetPageIndex accepts Page and PageText and clamps`() {
        assertEquals(
            3,
            pdfTargetPageIndex(Locator(LocatorStrategy.Page(index = 3, total = 10)), pageCount = 10),
        )
        assertEquals(
            3,
            pdfTargetPageIndex(
                Locator(LocatorStrategy.PageText(index = 3, total = 10, charOffset = 42)),
                pageCount = 10,
            ),
        )
        assertEquals(
            9,
            pdfTargetPageIndex(
                Locator(LocatorStrategy.PageText(index = 99, total = 10, charOffset = 0)),
                pageCount = 10,
            ),
        )
        // Non-fixed strategies fall back to 0 (callers use totalProgression separately when needed).
        assertEquals(
            0,
            pdfTargetPageIndex(Locator(LocatorStrategy.Unknown, totalProgression = 0.5f), pageCount = 10),
        )
        assertEquals(0, pdfTargetPageIndex(Locator(LocatorStrategy.Page(1, 5)), pageCount = 0))
    }

    @Test
    fun `no outline page chrome uses current page title and indices`() {
        val info = pdfNavigationChapterInfo(
            realOutlineEntries = emptyList(),
            currentPageIndex = 2,
            pageCount = 10,
        )
        assertEquals(ChapterInfo.Kind.PAGE, info.kind)
        assertEquals("第 3 页", info.currentTitle)
        assertEquals(2, info.currentIndex)
        assertEquals(10, info.totalChapters)
    }

    @Test
    fun `zero page count yields DOCUMENT and null adjacent even with nonempty outline`() {
        val outline = listOf(
            tocPage("Intro", page = 0, total = 5),
            tocPage("Body", page = 2, total = 5),
        )
        val viaNav = pdfNavigationChapterInfo(
            realOutlineEntries = outline,
            currentPageIndex = 1,
            pageCount = 0,
        )
        val viaOutline = pdfOutlineChapterInfo(
            outlineEntries = outline,
            currentPageIndex = 1,
            pageCount = 0,
        )
        assertEquals(ChapterInfo.Kind.DOCUMENT, viaNav.kind)
        assertEquals(PDF_DOCUMENT_TITLE_FALLBACK, viaNav.currentTitle)
        assertEquals(0, viaNav.totalChapters)
        assertEquals(ChapterInfo.Kind.DOCUMENT, viaOutline.kind)
        assertEquals(PDF_DOCUMENT_TITLE_FALLBACK, viaOutline.currentTitle)

        assertNull(
            pdfAdjacentNavigationLocator(
                realOutlineEntries = outline,
                currentPageIndex = 1,
                pageCount = 0,
                delta = 1,
            ),
        )
        assertNull(
            pdfAdjacentNavigationLocator(
                realOutlineEntries = emptyList(),
                currentPageIndex = 0,
                pageCount = 0,
                delta = 1,
            ),
        )
    }

    @Test
    fun `real monotonic outline yields CHAPTER chrome`() {
        val outline = listOf(
            tocPage("第一章", page = 0, total = 10),
            tocPage("第二章", page = 4, total = 10),
            tocPage("第三章", page = 7, total = 10),
        )
        val mid = pdfNavigationChapterInfo(
            realOutlineEntries = outline,
            currentPageIndex = 5,
            pageCount = 10,
        )
        assertEquals(ChapterInfo.Kind.CHAPTER, mid.kind)
        assertEquals(1, mid.currentIndex)
        assertEquals(3, mid.totalChapters)
        assertEquals("第二章", mid.currentTitle)
        assertEquals(0f, mid.progressInChapter)
    }

    @Test
    fun `non-monotonic outline picks closest destination at or before current page`() {
        // Original order: A@5, B@1, C@3 — current page 4 → closest dest <= 4 is C@3 (index 2).
        val outline = listOf(
            tocPage("A", page = 5, total = 10),
            tocPage("B", page = 1, total = 10),
            tocPage("C", page = 3, total = 10),
        )
        val info = pdfNavigationChapterInfo(
            realOutlineEntries = outline,
            currentPageIndex = 4,
            pageCount = 10,
        )
        assertEquals(ChapterInfo.Kind.CHAPTER, info.kind)
        assertEquals(2, info.currentIndex)
        assertEquals("C", info.currentTitle)
        assertEquals(3, info.totalChapters)
    }

    @Test
    fun `real outline adjacent follows original list order and boundaries`() {
        val outline = listOf(
            tocPage("A", page = 0, total = 10),
            tocPage("B", page = 3, total = 10),
            tocPage("C", page = 6, total = 10),
        )
        // On chapter B (page 3): prev → A, next → C
        val prev = pdfAdjacentNavigationLocator(
            realOutlineEntries = outline,
            currentPageIndex = 3,
            pageCount = 10,
            delta = -1,
        )
        val next = pdfAdjacentNavigationLocator(
            realOutlineEntries = outline,
            currentPageIndex = 3,
            pageCount = 10,
            delta = 1,
        )
        assertEquals(0, (prev?.strategy as LocatorStrategy.Page).index)
        assertEquals(6, (next?.strategy as LocatorStrategy.Page).index)

        // First outline entry: prev out of bounds
        assertNull(
            pdfAdjacentNavigationLocator(
                realOutlineEntries = outline,
                currentPageIndex = 0,
                pageCount = 10,
                delta = -1,
            ),
        )
        // Last outline entry: next out of bounds
        assertNull(
            pdfAdjacentNavigationLocator(
                realOutlineEntries = outline,
                currentPageIndex = 9,
                pageCount = 10,
                delta = 1,
            ),
        )
    }

    @Test
    fun `no-outline adjacent moves by page and respects boundaries`() {
        val next = pdfAdjacentNavigationLocator(
            realOutlineEntries = emptyList(),
            currentPageIndex = 2,
            pageCount = 10,
            delta = 1,
        )
        val prev = pdfAdjacentNavigationLocator(
            realOutlineEntries = emptyList(),
            currentPageIndex = 2,
            pageCount = 10,
            delta = -1,
        )
        assertEquals(3, (next?.strategy as LocatorStrategy.Page).index)
        assertEquals(10, (next?.strategy as LocatorStrategy.Page).total)
        assertEquals(1, (prev?.strategy as LocatorStrategy.Page).index)

        assertNull(
            pdfAdjacentNavigationLocator(
                realOutlineEntries = emptyList(),
                currentPageIndex = 0,
                pageCount = 10,
                delta = -1,
            ),
        )
        assertNull(
            pdfAdjacentNavigationLocator(
                realOutlineEntries = emptyList(),
                currentPageIndex = 9,
                pageCount = 10,
                delta = 1,
            ),
        )
    }

    private fun tocPage(title: String, page: Int, total: Int): TocEntry =
        TocEntry(
            title = title,
            locator = Locator(
                strategy = LocatorStrategy.Page(page, total),
                totalProgression = if (total > 0) page.toFloat() / total else 0f,
            ),
        )
}
