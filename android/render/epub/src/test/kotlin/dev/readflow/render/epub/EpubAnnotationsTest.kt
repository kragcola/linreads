package dev.readflow.render.epub

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
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
}
