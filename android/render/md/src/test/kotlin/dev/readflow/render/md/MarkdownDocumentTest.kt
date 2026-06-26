package dev.readflow.render.md

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownDocumentTest {

    @Test
    fun `builds heading toc with stable line and character offsets`() {
        val markdown = "# Title\nIntro line\n\n## Deep\nBody"
        val document = MarkdownDocument.parse(markdown)
        val titleOffset = markdown.indexOf("# Title")
        val deepOffset = markdown.indexOf("## Deep")

        assertEquals(5, document.lineCount)
        assertEquals(listOf("Title", "Deep"), document.tableOfContents.map { it.title })
        assertEquals(listOf(0, 1), document.tableOfContents.map { it.level })
        assertEquals(LocatorStrategy.Section(0, 0, titleOffset), document.tableOfContents[0].locator.strategy)
        assertEquals(LocatorStrategy.Section(0, 3, deepOffset), document.tableOfContents[1].locator.strategy)
        assertEquals(deepOffset.toFloat() / markdown.length, document.tableOfContents[1].locator.totalProgression)
    }

    @Test
    fun `maps visible character offset to section locator`() {
        val markdown = "# Title\nIntro line\n\n## Deep\nBody"
        val document = MarkdownDocument.parse(markdown)
        val offset = markdown.indexOf("Intro")

        val locator = document.locatorForOffset(offset)

        assertEquals(LocatorStrategy.Section(0, 1, offset), locator.strategy)
        assertEquals(offset.toFloat() / markdown.length, locator.totalProgression)
    }

    @Test
    fun `resolves section byte offset and total progression locators to character offsets`() {
        val markdown = "# Title\nIntro line\n\n## Deep\nBody"
        val document = MarkdownDocument.parse(markdown)
        val deepOffset = markdown.indexOf("## Deep")
        val bodyOffset = markdown.indexOf("Body")

        assertEquals(deepOffset, document.offsetFor(Locator(LocatorStrategy.Section(0, 3, 0))))
        assertEquals(bodyOffset, document.offsetFor(Locator(LocatorStrategy.ByteOffset(bodyOffset.toLong(), 0))))
        assertEquals(deepOffset, document.offsetFor(Locator(LocatorStrategy.Unknown, totalProgression = deepOffset.toFloat() / markdown.length)))
    }

    @Test
    fun `search returns section locators at matched character offsets`() {
        val markdown = "# Title\nIntro line\n\n## Deep\nAnother intro"
        val document = MarkdownDocument.parse(markdown)

        val results = document.search("intro")

        assertEquals(2, results.size)
        assertEquals(LocatorStrategy.Section(0, 1, markdown.indexOf("Intro")), results[0].strategy)
        assertEquals(LocatorStrategy.Section(0, 4, markdown.lastIndexOf("intro")), results[1].strategy)
        assertTrue(results[0].totalProgression!! < results[1].totalProgression!!)
    }

    @Test
    fun `selection maps source offsets to section anchors and selected text`() {
        val markdown = "# Title\nIntro line\n\n## Deep\nBody"
        val document = MarkdownDocument.parse(markdown)
        val start = markdown.indexOf("Intro")
        val end = markdown.indexOf("line") + "line".length

        val selection = document.selectionForOffsets(start, end)!!

        assertEquals("Intro line", selection.selectedText)
        assertEquals(LocatorStrategy.Section(0, 1, start), selection.start.strategy)
        assertEquals(LocatorStrategy.Section(0, 1, end), selection.end.strategy)
    }

    @Test
    fun `selection maps rendered markdown offsets back to source anchors`() {
        val markdown = "# Title\nIntro **bold** and [link](https://example.com)\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "Title\nIntro bold and link\n"
        val start = rendered.indexOf("bold")
        val end = rendered.indexOf("link") + "link".length

        val selection = document.selectionForRenderedOffsets(start, end, rendered)!!

        assertEquals("bold and link", selection.selectedText)
        assertEquals(LocatorStrategy.Section(0, 1, markdown.indexOf("bold")), selection.start.strategy)
        assertEquals(LocatorStrategy.Section(0, 1, markdown.indexOf("](https://example.com)")), selection.end.strategy)
    }

    @Test
    fun `annotation anchors map to highlight ranges`() {
        val markdown = "# Title\nIntro line\n\n## Deep\nBody"
        val document = MarkdownDocument.parse(markdown)
        val start = markdown.indexOf("Intro")
        val end = markdown.indexOf("line") + "line".length

        val ranges = document.highlightRanges(
            listOf(
                ReaderTextAnnotation(
                    id = "a1",
                    start = Locator(LocatorStrategy.Section(0, 1, start)),
                    end = Locator(LocatorStrategy.Section(0, 1, end)),
                    selectedText = "Intro line",
                    note = "memo",
                    color = 0x66FFE082,
                ),
            ),
        )

        assertEquals(1, ranges.size)
        assertEquals(start, ranges.single().start)
        assertEquals(end, ranges.single().end)
        assertEquals(0x66FFE082, ranges.single().color)
    }

    @Test
    fun `annotation source anchors map to rendered markdown highlight ranges`() {
        val markdown = "# Title\nIntro **bold** and [link](https://example.com)\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "Title\nIntro bold and link\n"
        val start = markdown.indexOf("bold")
        val end = markdown.indexOf("](https://example.com)")

        val ranges = document.highlightRanges(
            annotations = listOf(
                ReaderTextAnnotation(
                    id = "a1",
                    start = Locator(LocatorStrategy.Section(0, 1, start)),
                    end = Locator(LocatorStrategy.Section(0, 1, end)),
                    selectedText = "bold and link",
                    note = null,
                    color = 0x66FFE082,
                ),
            ),
            renderedText = rendered,
        )

        assertEquals(1, ranges.size)
        assertEquals(rendered.indexOf("bold"), ranges.single().start)
        assertEquals(rendered.indexOf("link") + "link".length, ranges.single().end)
        assertEquals(0x66FFE082, ranges.single().color)
    }
}
