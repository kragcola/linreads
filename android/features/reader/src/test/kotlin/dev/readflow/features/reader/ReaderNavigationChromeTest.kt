package dev.readflow.features.reader

import dev.readflow.core.model.ChapterInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderNavigationChromeTest {

    @Test
    fun `chapter progress description keeps chapter wording`() {
        assertEquals(
            "序章，第 2 / 5 章，本章进度 40%",
            readerChapterProgressDescription(
                title = "序章",
                currentIndex = 1,
                totalChapters = 5,
                progressInChapter = 0.4f,
                kind = ChapterInfo.Kind.CHAPTER,
            ),
        )
    }

    @Test
    fun `page progress description uses page wording without fake local percent`() {
        // Unknown page count: title only — never "1 / 0页" or "1 / 1页", never "本页进度".
        assertEquals(
            "未命名章节",
            readerChapterProgressDescription(
                title = "  ",
                currentIndex = -1,
                totalChapters = 0,
                progressInChapter = 1.4f,
                kind = ChapterInfo.Kind.PAGE,
            ),
        )
        val zeroCountSpoken = readerChapterProgressDescription(
            title = "正文",
            currentIndex = 0,
            totalChapters = 0,
            progressInChapter = 0f,
            kind = ChapterInfo.Kind.PAGE,
        )
        assertEquals("正文", zeroCountSpoken)
        assertFalse(zeroCountSpoken.contains("页"))
        assertFalse(zeroCountSpoken.contains("本页进度"))

        assertEquals(
            "正文，第 3 / 10 页",
            readerChapterProgressDescription(
                title = "正文",
                currentIndex = 2,
                totalChapters = 10,
                progressInChapter = 0f,
                kind = ChapterInfo.Kind.PAGE,
            ),
        )
        val pageSpoken = readerChapterProgressDescription(
            title = "正文",
            currentIndex = 2,
            totalChapters = 10,
            progressInChapter = 0.5f,
            kind = ChapterInfo.Kind.PAGE,
        )
        assertFalse(pageSpoken.contains("本页进度"))
        assertTrue(pageSpoken.contains("第 3 / 10 页"))
    }

    @Test
    fun `document progress description is title only without inventing 1 of 1`() {
        assertEquals(
            "正文",
            readerChapterProgressDescription(
                title = "正文",
                currentIndex = 0,
                totalChapters = 0,
                progressInChapter = 0.5f,
                kind = ChapterInfo.Kind.DOCUMENT,
            ),
        )
        assertEquals(
            "未命名章节",
            readerChapterProgressDescription(
                title = "   ",
                currentIndex = 0,
                totalChapters = 1,
                progressInChapter = 0f,
                kind = ChapterInfo.Kind.DOCUMENT,
            ),
        )
        val spoken = readerChapterProgressDescription(
            title = "附录",
            currentIndex = 0,
            totalChapters = 1,
            progressInChapter = 0f,
            kind = ChapterInfo.Kind.DOCUMENT,
        )
        assertFalse(spoken.contains("1 / 1"))
        assertFalse(spoken.contains("章"))
        assertFalse(spoken.contains("页"))
    }

    @Test
    fun `navigation counter text matches kind and omits fake document or zero page counter`() {
        assertEquals(
            "2 / 5 章",
            readerNavigationCounterText(
                ChapterInfo(1, 5, "序章", 0.4f, ChapterInfo.Kind.CHAPTER),
            ),
        )
        assertEquals(
            "3 / 10 页",
            readerNavigationCounterText(
                ChapterInfo(2, 10, "正文", 0f, ChapterInfo.Kind.PAGE),
            ),
        )
        assertNull(
            readerNavigationCounterText(
                ChapterInfo(0, 0, "正文", 0f, ChapterInfo.Kind.PAGE),
            ),
        )
        assertNull(
            readerNavigationCounterText(
                ChapterInfo(0, 0, "正文", 0f, ChapterInfo.Kind.DOCUMENT),
            ),
        )
        assertNull(
            readerNavigationCounterText(
                ChapterInfo(0, 1, "正文", 0f, ChapterInfo.Kind.DOCUMENT),
            ),
        )
    }

    @Test
    fun `adjacent navigation labels follow kind`() {
        assertEquals("上一章", readerAdjacentNavLabel(ChapterInfo.Kind.CHAPTER, delta = -1))
        assertEquals("下一章", readerAdjacentNavLabel(ChapterInfo.Kind.CHAPTER, delta = +1))
        assertEquals("上一页", readerAdjacentNavLabel(ChapterInfo.Kind.PAGE, delta = -1))
        assertEquals("下一页", readerAdjacentNavLabel(ChapterInfo.Kind.PAGE, delta = +1))
        assertNull(readerAdjacentNavLabel(ChapterInfo.Kind.DOCUMENT, delta = -1))
        assertNull(readerAdjacentNavLabel(ChapterInfo.Kind.DOCUMENT, delta = +1))
    }

    @Test
    fun `document chrome uses fixed spacers instead of chapter buttons`() {
        assertTrue(readerShowsAdjacentNavButtons(ChapterInfo.Kind.CHAPTER))
        assertTrue(readerShowsAdjacentNavButtons(ChapterInfo.Kind.PAGE))
        assertFalse(readerShowsAdjacentNavButtons(ChapterInfo.Kind.DOCUMENT))
        assertEquals(48, readerDocumentNavSpacerDp)
    }
}
