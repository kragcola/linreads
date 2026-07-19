package dev.readflow.render.epub

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.READER_SEARCH_HIGHLIGHT_COLOR
import dev.readflow.render.api.ReaderSearchHit
import dev.readflow.render.api.ReaderTextAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubAnnotationsTest {

    @Test
    fun `annotation ranges map to paragraph highlight ranges`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("Alpha beta", "Needle text"),
            ),
        )
        val start = "Alpha beta".length + "Needle text".indexOf("Needle")
        val end = "Alpha beta".length + "Needle text".indexOf("text") + "text".length

        val ranges = epubHighlightRanges(
            indexedParas = paras,
            paragraphIndex = 1,
            annotations = listOf(
                ReaderTextAnnotation(
                    id = "a1",
                    start = Locator(LocatorStrategy.Section(0, 1, start)),
                    end = Locator(LocatorStrategy.Section(0, 1, end)),
                    selectedText = "Needle text",
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )

        assertEquals(1, ranges.size)
        assertEquals("Needle text".indexOf("Needle"), ranges.single().start)
        assertEquals("Needle text".indexOf("text") + "text".length, ranges.single().end)
        assertEquals(0x66FFE082, ranges.single().color)
    }

    @Test
    fun `search highlight maps Section spine char plus matchLength to paragraph local range`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("Alpha beta", "Needle text here"),
            ),
        )
        val spineStart = "Alpha beta".length + "Needle text here".indexOf("Needle")
        val matchLength = "Needle".length

        val range = epubSearchHighlightRange(
            indexedParas = paras,
            paragraphIndex = 1,
            hit = ReaderSearchHit(
                locator = Locator(LocatorStrategy.Section(0, 1, spineStart)),
                snippet = "Needle",
                matchLength = matchLength,
            ),
        )!!

        assertEquals("Needle text here".indexOf("Needle"), range.start)
        assertEquals("Needle text here".indexOf("Needle") + matchLength, range.end)
        assertEquals(READER_SEARCH_HIGHLIGHT_COLOR, range.color)
        assertNull(
            epubSearchHighlightRange(
                indexedParas = paras,
                paragraphIndex = 0,
                hit = ReaderSearchHit(
                    locator = Locator(LocatorStrategy.Section(0, 1, spineStart)),
                    snippet = "Needle",
                    matchLength = matchLength,
                ),
            ),
        )
    }

    @Test
    fun `search and annotation ranges coexist for same paragraph`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("Alpha beta", "Needle text here"),
            ),
        )
        val paraText = "Needle text here"
        val annSpineStart = "Alpha beta".length + paraText.indexOf("text")
        val annSpineEnd = annSpineStart + "text".length
        val searchSpineStart = "Alpha beta".length + paraText.indexOf("Needle")

        val annotations = epubHighlightRanges(
            indexedParas = paras,
            paragraphIndex = 1,
            annotations = listOf(
                ReaderTextAnnotation(
                    id = "a1",
                    start = Locator(LocatorStrategy.Section(0, 1, annSpineStart)),
                    end = Locator(LocatorStrategy.Section(0, 1, annSpineEnd)),
                    selectedText = "text",
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
        )
        val search = epubSearchHighlightRange(
            indexedParas = paras,
            paragraphIndex = 1,
            hit = ReaderSearchHit(
                locator = Locator(LocatorStrategy.Section(0, 1, searchSpineStart)),
                snippet = "Needle",
                matchLength = 6,
            ),
        )!!

        assertEquals(1, annotations.size)
        assertEquals(0x66FFE082, annotations.single().color)
        assertEquals(READER_SEARCH_HIGHLIGHT_COLOR, search.color)
        assertTrue(annotations.single().color != search.color)
    }
}
