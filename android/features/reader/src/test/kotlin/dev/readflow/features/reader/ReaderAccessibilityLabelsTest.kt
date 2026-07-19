package dev.readflow.features.reader

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.TocEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderAccessibilityLabelsTest {

    @Test
    fun `chapter progress description clamps values and falls back for blank title`() {
        assertEquals(
            "未命名章节，第 1 / 1 章，本章进度 100%",
            readerChapterProgressDescription(
                title = "  ",
                currentIndex = -1,
                totalChapters = 0,
                progressInChapter = 1.4f,
            ),
        )
    }

    @Test
    fun `overall progress percent text is rounded and clamped`() {
        assertEquals("0%", readerProgressPercentText(null))
        assertEquals("63%", readerProgressPercentText(0.625f))
        assertEquals("100%", readerProgressPercentText(2f))
        assertEquals("0%", readerProgressPercentText(Float.NaN))
        assertEquals(0f, readerProgressValue(Float.POSITIVE_INFINITY))
        assertEquals(0f, readerProgressValue(Float.NEGATIVE_INFINITY))
        assertEquals(1f, readerProgressValue(2f))
    }

    @Test
    fun `search result accessibility label uses progression when available`() {
        val withProgress = ReaderSearchResult(
            index = 1,
            locator = Locator(
                strategy = LocatorStrategy.Page(index = 12, total = 40),
                totalProgression = 0.625f,
            ),
            matchLength = 0,
        )
        val withoutProgress = ReaderSearchResult(
            index = 0,
            locator = Locator(
                strategy = LocatorStrategy.Page(index = 0, total = 40),
                totalProgression = null,
            ),
            matchLength = 0,
        )

        assertEquals("搜索结果 2，位置 63%", withProgress.readerAccessibilityLabel())
        assertEquals("搜索结果 1", withoutProgress.readerAccessibilityLabel())
    }

    @Test
    fun `search result accessibility label includes normalized snippet after position`() {
        val result = ReaderSearchResult(
            index = 1,
            locator = Locator(
                strategy = LocatorStrategy.Page(index = 12, total = 40),
                totalProgression = 0.625f,
            ),
            snippet = "  前文  \n  关键词  后文  ",
            matchLength = 3,
        )
        val blankSnippet = ReaderSearchResult(
            index = 0,
            locator = Locator(
                strategy = LocatorStrategy.Page(index = 0, total = 40),
                totalProgression = 0.1f,
            ),
            snippet = "   ",
            matchLength = 0,
        )

        assertEquals(
            "搜索「关键词」结果 2，位置 63%，前文 关键词 后文",
            result.readerAccessibilityLabel(query = "关键词", selected = false),
        )
        assertEquals(
            "搜索「关键词」结果 2，位置 63%，前文 关键词 后文，已选中",
            result.readerAccessibilityLabel(query = "关键词", selected = true),
        )
        assertEquals(
            "搜索结果 1，位置 10%",
            blankSnippet.readerAccessibilityLabel(query = "", selected = false),
        )
    }

    @Test
    fun `search result accessibility label includes normalized query and selected state`() {
        val result = ReaderSearchResult(
            index = 1,
            locator = Locator(
                strategy = LocatorStrategy.Page(index = 12, total = 40),
                totalProgression = 0.625f,
            ),
            matchLength = 0,
        )

        assertEquals(
            "搜索「关键词」结果 2，位置 63%",
            result.readerAccessibilityLabel(query = "  关键词  ", selected = false),
        )
        assertEquals(
            "搜索「关键词」结果 2，位置 63%，已选中",
            result.readerAccessibilityLabel(query = "关键词", selected = true),
        )
        assertEquals(
            "搜索结果 2，位置 63%，已选中",
            result.readerAccessibilityLabel(query = "   ", selected = true),
        )
        assertEquals(
            "搜索结果 1",
            ReaderSearchResult(
                index = 0,
                locator = Locator(
                    strategy = LocatorStrategy.Page(index = 0, total = 40),
                    totalProgression = null,
                ),
                matchLength = 0,
            ).readerAccessibilityLabel(query = "", selected = false),
        )
    }

    @Test
    fun `annotation accessibility label includes note and trims long text`() {
        val item = ReaderAnnotationItem(
            id = "annotation-1",
            start = Locator(LocatorStrategy.ByteOffset(offset = 10L, length = 5), totalProgression = 0.2f),
            end = Locator(LocatorStrategy.ByteOffset(offset = 15L, length = 0), totalProgression = 0.21f),
            selectedText = "  第一行\n第二行  第三行  ",
            note = "  这里要回看这段内容  ",
            color = 0,
            totalProgression = 0.2f,
        )

        assertEquals(
            "跳转到标注：第一行 第二行 第三行，笔记：这里要回看这段内容",
            item.accessibilityLabel(),
        )
    }

    @Test
    fun `annotation delete accessibility label is concise Chinese snippet`() {
        val item = ReaderAnnotationItem(
            id = "annotation-delete-1",
            start = Locator(LocatorStrategy.ByteOffset(offset = 10L, length = 5), totalProgression = 0.2f),
            end = Locator(LocatorStrategy.ByteOffset(offset = 15L, length = 0), totalProgression = 0.21f),
            selectedText = "  第一行\n第二行  第三行  ",
            note = "  这里要回看这段内容  ",
            color = 0,
            totalProgression = 0.2f,
        )
        val longText = "甲".repeat(50)
        val longItem = item.copy(selectedText = longText)

        assertEquals("删除标注：第一行 第二行 第三行", item.deleteAccessibilityLabel())
        assertEquals(
            "删除标注：${"甲".repeat(39)}…",
            longItem.deleteAccessibilityLabel(),
        )
        assertFalse(item.deleteAccessibilityLabel().contains("笔记"))
        assertFalse(item.deleteAccessibilityLabel().contains("button", ignoreCase = true))
    }

    @Test
    fun `bookmark and toc accessibility labels stay human readable`() {
        val bookmark = ReaderBookmarkItem(
            id = "bookmark-1",
            locator = Locator(LocatorStrategy.Page(index = 1, total = 10), totalProgression = 0.2f),
            label = "书签 20%",
            totalProgression = 0.2f,
            createdAt = 0L,
        )
        val toc = TocEntry(
            title = "Start",
            locator = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 0), totalProgression = 0f),
            level = 1,
        )

        assertEquals("跳转到书签 20%", bookmark.accessibilityLabel())
        assertEquals("跳转到书签 20%", bookmark.accessibilityLabel(isCurrent = false))
        assertEquals("跳转到书签 20%，当前", bookmark.accessibilityLabel(isCurrent = true))
        assertFalse(bookmark.accessibilityLabel(isCurrent = false).contains("当前"))
        assertTrue(bookmark.accessibilityLabel(isCurrent = true).endsWith("，当前"))
        assertEquals("2 级目录，Start", readerTocAccessibilityLabel(toc))
    }
}
