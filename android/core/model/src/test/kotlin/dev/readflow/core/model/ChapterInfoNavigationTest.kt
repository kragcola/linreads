package dev.readflow.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterInfoNavigationTest {

    @Test
    fun `chapter info from ordered toc maps progression to chapter and local progress`() {
        val toc = listOf(
            toc("开篇", 0f),
            toc("中章", 0.4f),
            toc("终章", 0.8f),
        )

        val mid = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 0.6f,
            documentTitleFallback = "书名",
        )
        assertEquals(ChapterInfo.Kind.CHAPTER, mid.kind)
        assertEquals(1, mid.currentIndex)
        assertEquals(3, mid.totalChapters)
        assertEquals("中章", mid.currentTitle)
        assertEquals(0.5f, mid.progressInChapter, 1e-4f)

        val tail = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 0.9f,
            documentTitleFallback = "书名",
        )
        assertEquals(2, tail.currentIndex)
        assertEquals(0.5f, tail.progressInChapter, 1e-4f)
    }

    @Test
    fun `chapter info from ordered toc clamps progress and uses fallback for blank titles`() {
        val toc = listOf(
            toc("  ", 0f),
            toc("二", 0.5f),
        )

        val over = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 1.8f,
            documentTitleFallback = "文档标题",
        )
        assertEquals(ChapterInfo.Kind.CHAPTER, over.kind)
        assertEquals(1, over.currentIndex)
        assertEquals(2, over.totalChapters)
        assertEquals("二", over.currentTitle)
        assertEquals(1f, over.progressInChapter, 1e-4f)

        val first = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = -0.3f,
            documentTitleFallback = "文档标题",
        )
        assertEquals(0, first.currentIndex)
        assertEquals("文档标题", first.currentTitle)
        assertTrue(first.progressInChapter in 0f..1f)
    }

    @Test
    fun `empty toc yields document kind without inventing chapter count`() {
        val info = chapterInfoFromOrderedToc(
            tocEntries = emptyList(),
            totalProgression = 0.42f,
            documentTitleFallback = "  ",
        )
        assertEquals(ChapterInfo.Kind.DOCUMENT, info.kind)
        assertEquals(0, info.currentIndex)
        assertEquals(0, info.totalChapters)
        assertEquals("正文", info.currentTitle)
        assertEquals(0f, info.progressInChapter)
    }

    @Test
    fun `missing toc progression does not default every entry to zero and pick last`() {
        val toc = listOf(
            tocMissing("甲"),
            tocMissing("乙"),
            tocMissing("丙"),
        )
        val info = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 0.55f,
            documentTitleFallback = "书名",
        )
        // None qualify with finite progression; fall back to first entry, not last.
        assertEquals(0, info.currentIndex)
        assertEquals("甲", info.currentTitle)
        assertEquals(0f, info.progressInChapter)
    }

    @Test
    fun `nan toc anchors are skipped for selection and local progress`() {
        val toc = listOf(
            toc("开篇", Float.NaN),
            toc("中章", 0.4f),
            toc("终章", Float.NaN),
        )

        val mid = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 0.55f,
            documentTitleFallback = "书名",
        )
        assertEquals(1, mid.currentIndex)
        assertEquals("中章", mid.currentTitle)
        // Next neighbor is NaN → no finite end anchor → local progress 0.
        assertEquals(0f, mid.progressInChapter)

        val beforeAnyFinite = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 0.1f,
            documentTitleFallback = "书名",
        )
        // 0.1 < 0.4 and first entry is NaN → none qualify → first entry.
        assertEquals(0, beforeAnyFinite.currentIndex)
        assertEquals("开篇", beforeAnyFinite.currentTitle)
        assertEquals(0f, beforeAnyFinite.progressInChapter)
    }

    @Test
    fun `non finite total progression is sanitized before selection`() {
        val toc = listOf(
            toc("开篇", 0f),
            toc("中章", 0.4f),
            toc("终章", 0.8f),
        )
        val fromNan = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = Float.NaN,
            documentTitleFallback = "书名",
        )
        assertEquals(0, fromNan.currentIndex)
        assertEquals("开篇", fromNan.currentTitle)
        assertTrue(fromNan.progressInChapter.isFinite())
        assertTrue(fromNan.progressInChapter in 0f..1f)

        val fromInf = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = Float.POSITIVE_INFINITY,
            documentTitleFallback = "书名",
        )
        // Non-finite total falls back to 0, not coerced via coerceIn into last chapter.
        assertEquals(0, fromInf.currentIndex)
    }

    @Test
    fun `finite neighbor anchors compute local progress when available`() {
        val toc = listOf(
            toc("开篇", 0f),
            toc("中章", 0.4f),
            toc("终章", 0.8f),
        )
        val mid = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 0.6f,
            documentTitleFallback = "书名",
        )
        assertEquals(0.5f, mid.progressInChapter, 1e-4f)

        val last = chapterInfoFromOrderedToc(
            tocEntries = toc,
            totalProgression = 0.9f,
            documentTitleFallback = "书名",
        )
        // Last chapter spans to document end 1f.
        assertEquals(0.5f, last.progressInChapter, 1e-4f)
    }

    @Test
    fun `page chapter info clamps index and never invents a page when count is zero`() {
        val mid = pageChapterInfo(
            pageIndex = 2,
            pageCount = 10,
            documentTitleFallback = "论文",
        )
        assertEquals(ChapterInfo.Kind.PAGE, mid.kind)
        assertEquals(2, mid.currentIndex)
        assertEquals(10, mid.totalChapters)
        assertEquals("论文", mid.currentTitle)
        assertEquals(0f, mid.progressInChapter)

        val clamped = pageChapterInfo(
            pageIndex = 99,
            pageCount = 5,
            documentTitleFallback = "  ",
        )
        assertEquals(4, clamped.currentIndex)
        assertEquals(5, clamped.totalChapters)
        assertEquals("正文", clamped.currentTitle)

        val empty = pageChapterInfo(
            pageIndex = -3,
            pageCount = 0,
            documentTitleFallback = "空",
        )
        assertEquals(ChapterInfo.Kind.PAGE, empty.kind)
        assertEquals(0, empty.currentIndex)
        assertEquals(0, empty.totalChapters)
        assertEquals("空", empty.currentTitle)
    }

    @Test
    fun `adjacent toc entry clamps base index and returns null at boundaries`() {
        val toc = listOf(toc("A", 0f), toc("B", 0.5f), toc("C", 0.9f))

        assertEquals("B", adjacentTocEntry(toc, currentIndex = 0, delta = 1)?.title)
        assertEquals("A", adjacentTocEntry(toc, currentIndex = 1, delta = -1)?.title)
        assertNull(adjacentTocEntry(toc, currentIndex = 0, delta = -1))
        assertNull(adjacentTocEntry(toc, currentIndex = 2, delta = 1))
        assertEquals("A", adjacentTocEntry(toc, currentIndex = -5, delta = 0)?.title)
        assertEquals("C", adjacentTocEntry(toc, currentIndex = 99, delta = 0)?.title)
        assertNull(adjacentTocEntry(emptyList(), currentIndex = 0, delta = 1))
    }

    @Test
    fun `chapter info kind defaults to chapter for existing constructors`() {
        val legacy = ChapterInfo(0, 1, "章", 0.2f)
        assertEquals(ChapterInfo.Kind.CHAPTER, legacy.kind)
    }

    private fun toc(title: String, progression: Float): TocEntry =
        TocEntry(
            title = title,
            locator = Locator(
                strategy = LocatorStrategy.Unknown,
                totalProgression = progression,
            ),
        )

    private fun tocMissing(title: String): TocEntry =
        TocEntry(
            title = title,
            locator = Locator(
                strategy = LocatorStrategy.Unknown,
                totalProgression = null,
            ),
        )
}
