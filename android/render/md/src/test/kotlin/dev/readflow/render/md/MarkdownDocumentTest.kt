package dev.readflow.render.md

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.ReaderTextAnnotation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `nonblank markdown without headings yields empty toc`() {
        val markdown = "Plain paragraph without any ATX heading.\n\nSecond block still body."
        val document = MarkdownDocument.parse(markdown)

        assertTrue(document.tableOfContents.isEmpty())
        assertTrue(document.lineCount >= 1)
    }

    @Test
    fun `blank markdown yields empty toc`() {
        assertTrue(MarkdownDocument.parse("").tableOfContents.isEmpty())
        assertTrue(MarkdownDocument.parse("   \n\n  ").tableOfContents.isEmpty())
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

        val results = runBlocking { document.search("intro") }

        assertEquals(2, results.size)
        assertEquals(LocatorStrategy.Section(0, 1, markdown.indexOf("Intro")), results[0].strategy)
        assertEquals(LocatorStrategy.Section(0, 4, markdown.lastIndexOf("intro")), results[1].strategy)
        assertTrue(results[0].totalProgression!! < results[1].totalProgression!!)
    }

    @Test
    fun `search does not complete when invoked from a cancelled undispatched coroutine`() = runTest {
        val markdown = buildString {
            repeat(200) { index ->
                append("line $index token-$index more text ")
                append("shared-needle ")
                appendLine("tail")
            }
        }
        val document = MarkdownDocument.parse(markdown)

        var completed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            document.search("shared-needle")
            completed = true
        }

        assertFalse(completed, "cancelled search must not finish a full synchronous scan")
        assertTrue(job.isCancelled)
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

    @Test
    fun `source to rendered offsets stay aligned when Markwon inserts extra heading blank lines`() {
        // Markwon often renders "## H\nBody" as "H\n\nBody" — greedy char indexOf used to jump
        // the extra rendered newline to a later source newline and drift by paragraphs.
        val markdown = buildString {
            append("# Title\n\n")
            repeat(5) { index ->
                append("## Section %03d\n".format(index))
                append(
                    "Paragraph %03d carries stable markdown content and TargetToken%03d keeps anchors.\n\n"
                        .format(index, index),
                )
            }
        }
        val document = MarkdownDocument.parse(markdown)
        val rendered = buildString {
            append("Title\n\n")
            repeat(5) { index ->
                append("Section %03d\n\n".format(index))
                append(
                    "Paragraph %03d carries stable markdown content and TargetToken%03d keeps anchors.\n\n"
                        .format(index, index),
                )
            }
        }
        for (index in listOf(0, 1, 3, 4)) {
            val token = "TargetToken%03d".format(index)
            val sourceOffset = markdown.indexOf(token)
            val expectedRendered = rendered.indexOf(token)
            assertTrue(sourceOffset >= 0 && expectedRendered >= 0, "token $token must exist")
            val mapped = document.renderedOffsetFor(
                Locator(LocatorStrategy.Section(0, 0, sourceOffset)),
                rendered,
            )
            assertEquals(expectedRendered, mapped, "token $token rendered mapping")
            val back = document.locatorForRenderedOffset(expectedRendered, rendered)
            assertEquals(sourceOffset, (back.strategy as LocatorStrategy.Section).charOffset)
        }
    }

    @Test
    fun `literal asterisk and snake_case are not treated as emphasis markers`() {
        val markdown = "Compute a * b and use snake_case_var in code.\n"
        val document = MarkdownDocument.parse(markdown)
        // Simulated Markwon plain output keeps the literal characters.
        val rendered = "Compute a * b and use snake_case_var in code.\n"
        val starSource = markdown.indexOf('*')
        val snakeSource = markdown.indexOf("snake_case_var")
        assertEquals(
            rendered.indexOf('*'),
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, starSource)), rendered),
        )
        assertEquals(
            rendered.indexOf("snake_case_var"),
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, snakeSource)), rendered),
        )
        val backStar = document.locatorForRenderedOffset(rendered.indexOf('*'), rendered)
        assertEquals(starSource, (backStar.strategy as LocatorStrategy.Section).charOffset)
        val backSnake = document.locatorForRenderedOffset(rendered.indexOf("snake_case_var"), rendered)
        assertEquals(snakeSource, (backSnake.strategy as LocatorStrategy.Section).charOffset)
    }

    @Test
    fun `escaped markers map to literal rendered characters`() {
        val markdown = "Use \\*star\\* and \\_under\\_ safely.\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "Use *star* and _under_ safely.\n"
        val starRendered = rendered.indexOf('*')
        val underRendered = rendered.indexOf('_')
        // Source pin for first rendered '*' should be the star after backslash, not a distant char.
        val back = document.locatorForRenderedOffset(starRendered, rendered)
        val sourceOffset = (back.strategy as LocatorStrategy.Section).charOffset
        assertTrue(sourceOffset in markdown.indices, "source offset in range")
        assertEquals('*', markdown[sourceOffset])
        val underBack = document.locatorForRenderedOffset(underRendered, rendered)
        assertEquals('_', markdown[(underBack.strategy as LocatorStrategy.Section).charOffset])
    }

    @Test
    fun `inline code and fenced code preserve content anchors`() {
        val markdown = "Call `foo_bar` then:\n\n```\nval x = 1\n```\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "Call foo_bar then:\n\nval x = 1\n"
        val token = "foo_bar"
        val sourceOffset = markdown.indexOf(token)
        val expectedRendered = rendered.indexOf(token)
        assertEquals(
            expectedRendered,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
        )
        val codeToken = "val x"
        val codeSource = markdown.indexOf(codeToken)
        val codeRendered = rendered.indexOf(codeToken)
        assertTrue(codeSource >= 0 && codeRendered >= 0)
        assertEquals(
            codeRendered,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, codeSource)), rendered),
        )
    }

    @Test
    fun `strikethrough and link text round-trip without discarding brackets content`() {
        val markdown = "See ~~old~~ and [label](https://example.com/path) end.\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "See old and label end.\n"
        for (token in listOf("old", "label", "end")) {
            val sourceOffset = markdown.indexOf(token)
            val expectedRendered = rendered.indexOf(token)
            assertTrue(sourceOffset >= 0 && expectedRendered >= 0, token)
            assertEquals(
                expectedRendered,
                document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
                "token $token",
            )
            val back = document.locatorForRenderedOffset(expectedRendered, rendered)
            assertEquals(sourceOffset, (back.strategy as LocatorStrategy.Section).charOffset, "back $token")
        }
    }

    @Test
    fun `mapping cache returns identical pins for repeated rendered string`() {
        val markdown = "# H\n\nBody **bold** text.\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "H\n\nBody bold text.\n"
        val first = document.renderedOffsetFor(
            Locator(LocatorStrategy.Section(0, 0, markdown.indexOf("bold"))),
            rendered,
        )
        val second = document.renderedOffsetFor(
            Locator(LocatorStrategy.Section(0, 0, markdown.indexOf("bold"))),
            rendered,
        )
        assertEquals(first, second)
        assertEquals(rendered.indexOf("bold"), first)
    }

    @Test
    fun `image keeps alt text and skips url so trailing tokens stay aligned`() {
        val markdown = "Before ![img_alt](https://example.com/a.png) after_token end.\n"
        val document = MarkdownDocument.parse(markdown)
        // Real Markwon keeps alt text and drops only ! / brackets / destination.
        val rendered = "Before img_alt after_token end.\n"
        for (token in listOf("img_alt", "after_token", "end")) {
            val sourceOffset = markdown.indexOf(token)
            val expectedRendered = rendered.indexOf(token)
            assertTrue(sourceOffset >= 0 && expectedRendered >= 0, token)
            assertEquals(
                expectedRendered,
                document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
                "token $token",
            )
            val back = document.locatorForRenderedOffset(expectedRendered, rendered)
            assertEquals(
                sourceOffset,
                (back.strategy as LocatorStrategy.Section).charOffset,
                "back $token",
            )
        }
    }

    @Test
    fun `table lines are skipped when rendered only has table placeholders`() {
        val markdown = "Lead\n\n| ColA | ColB |\n| --- | --- |\n| cell_a | cell_b |\n\nTail end_token.\n"
        val document = MarkdownDocument.parse(markdown)
        // TablePlugin plain text is typically nbsp placeholders, not cell text.
        val rendered = "Lead\n\n\u00A0\n\u00A0\n\nTail end_token.\n"
        val token = "end_token"
        val sourceOffset = markdown.indexOf(token)
        val expectedRendered = rendered.indexOf(token)
        assertEquals(
            expectedRendered,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
        )
        val back = document.locatorForRenderedOffset(expectedRendered, rendered)
        assertEquals(sourceOffset, (back.strategy as LocatorStrategy.Section).charOffset)
    }

    @Test
    fun `long link url over 256 chars keeps trailing end_token`() {
        val longUrl = "https://example.com/" + "u".repeat(280)
        val markdown = "Lead [label]($longUrl) end_token.\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "Lead label end_token.\n"
        assertTrue(longUrl.length > 256)
        val token = "end_token"
        val sourceOffset = markdown.indexOf(token)
        val expectedRendered = rendered.indexOf(token)
        assertEquals(
            expectedRendered,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
        )
        val back = document.locatorForRenderedOffset(expectedRendered, rendered)
        assertEquals(sourceOffset, (back.strategy as LocatorStrategy.Section).charOffset)
    }

    @Test
    fun `long image url over 256 chars keeps alt and trailing end_token`() {
        val longUrl = "https://example.com/img/" + "p".repeat(280) + ".png"
        val markdown = "Lead ![img_alt]($longUrl) end_token.\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = "Lead img_alt end_token.\n"
        assertTrue(longUrl.length > 256)
        for (token in listOf("img_alt", "end_token")) {
            val sourceOffset = markdown.indexOf(token)
            val expectedRendered = rendered.indexOf(token)
            assertEquals(
                expectedRendered,
                document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
                token,
            )
            val back = document.locatorForRenderedOffset(expectedRendered, rendered)
            assertEquals(sourceOffset, (back.strategy as LocatorStrategy.Section).charOffset, token)
        }
    }

    @Test
    fun `multi-row table with long row advances source for each placeholder`() {
        val longCell = "C".repeat(280)
        val markdown = buildString {
            append("Lead\n\n")
            append("| ColA | ColB |\n")
            append("| --- | --- |\n")
            append("| short | x |\n")
            append("| $longCell | y |\n")
            append("| row3 | z |\n\n")
            append("Tail end_token.\n")
        }
        val document = MarkdownDocument.parse(markdown)
        // One NBSP placeholder per table line (header + sep + 3 data rows).
        val rendered = "Lead\n\n\u00A0\n\u00A0\n\u00A0\n\u00A0\n\u00A0\n\nTail end_token.\n"
        assertTrue(longCell.length > 256)
        val token = "end_token"
        val sourceOffset = markdown.indexOf(token)
        val expectedRendered = rendered.indexOf(token)
        assertEquals(
            expectedRendered,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
        )
        val back = document.locatorForRenderedOffset(expectedRendered, rendered)
        assertEquals(sourceOffset, (back.strategy as LocatorStrategy.Section).charOffset)
        val selection = document.selectionForRenderedOffsets(
            expectedRendered,
            expectedRendered + token.length,
            rendered,
        )
        assertEquals(token, selection?.selectedText)
        assertEquals(sourceOffset, (selection?.start?.strategy as LocatorStrategy.Section).charOffset)
    }

    @Test
    fun `firstRenderedIndexForSource lower-bound handles duplicates exact gaps and end`() {
        // Monotonic pins with duplicate pins (Markwon blank lines), a gap (source 3 never pinned),
        // and trailing sources past the last pin.
        val positions = intArrayOf(0, 1, 1, 2, 4, 4, 5)
        assertEquals(0, MarkdownDocument.firstRenderedIndexForSource(positions, 0))
        assertEquals(1, MarkdownDocument.firstRenderedIndexForSource(positions, 1)) // first of duplicate
        assertEquals(3, MarkdownDocument.firstRenderedIndexForSource(positions, 2))
        assertEquals(4, MarkdownDocument.firstRenderedIndexForSource(positions, 3)) // gap → first ge
        assertEquals(4, MarkdownDocument.firstRenderedIndexForSource(positions, 4))
        assertEquals(6, MarkdownDocument.firstRenderedIndexForSource(positions, 5))
        assertEquals(positions.size, MarkdownDocument.firstRenderedIndexForSource(positions, 6))
        assertEquals(positions.size, MarkdownDocument.firstRenderedIndexForSource(positions, 100))
        assertEquals(0, MarkdownDocument.firstRenderedIndexForSource(intArrayOf(), 0))
    }

    @Test
    fun `sourceToRenderedOffset uses lower-bound for duplicate pins and gaps on large fixture`() {
        val body = buildString {
            append("# Title token\n\n")
            repeat(200) { i ->
                append("Paragraph $i with alpha_$i beta_$i.\n\n")
            }
            append("Tail end_token.\n")
        }
        val document = MarkdownDocument.parse(body)
        // Synthetic rendered ≈ source for plain paragraphs (no syntax strip) — mapping still built.
        val rendered = body
        val titleSource = body.indexOf("Title token")
        assertEquals(
            rendered.indexOf("Title token"),
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, titleSource)), rendered),
        )
        val midToken = "alpha_100"
        val midSource = body.indexOf(midToken)
        assertEquals(
            rendered.indexOf(midToken),
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, midSource)), rendered),
        )
        val endSource = body.indexOf("end_token")
        assertEquals(
            rendered.indexOf("end_token"),
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, endSource)), rendered),
        )
        // Past end → rendered length.
        assertEquals(
            rendered.length,
            document.renderedOffsetFor(
                Locator(LocatorStrategy.Section(0, 0, body.length + 50)),
                rendered,
            ),
        )
        // Gap-like: source offset on a character that exists — exact pin.
        val spaceSource = body.indexOf("Paragraph 0")
        assertEquals(
            rendered.indexOf("Paragraph 0"),
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, spaceSource)), rendered),
        )
        // rendered→source stays O(1) index into cache.
        val back = document.locatorForRenderedOffset(rendered.indexOf("end_token"), rendered)
        assertEquals(endSource, (back.strategy as LocatorStrategy.Section).charOffset)
    }

    @Test
    fun `mapping cache identity fast-path and content-equivalence fallback`() {
        val markdown = "Hello **world** end_token.\n"
        val document = MarkdownDocument.parse(markdown)
        val rendered = StringBuilder("Hello world end_token.\n")
        val token = "end_token"
        val sourceOffset = markdown.indexOf(token)
        val expected = rendered.indexOf(token)
        assertEquals(
            expected,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
        )
        // Same identity: still hits without requiring a new toString key rebuild path.
        assertEquals(
            expected,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), rendered),
        )
        // Different identity, same content (highlight Spannable equivalent): content fallback.
        val sameContent = StringBuilder(rendered.toString())
        assertEquals(
            expected,
            document.renderedOffsetFor(Locator(LocatorStrategy.Section(0, 0, sourceOffset)), sameContent),
        )
        assertTrue(rendered !== sameContent)
    }
}
