package dev.readflow.render.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 tests for the chapter-flow text + offset map (locator round-trips). */
class EpubChapterFlowTest {

    private fun text(p: Int, s: String) =
        EpubDisplayBlock.Text(text = s, headingLevel = null, paragraphIndex = p)

    private fun heading(p: Int, s: String) =
        EpubDisplayBlock.Text(text = s, headingLevel = 1, paragraphIndex = p)

    private fun image(p: Int, href: String) =
        EpubDisplayBlock.Image(href = href, altText = null, paragraphIndex = p)

    @Test
    fun `concatenates blocks with paragraph separators and records segments`() {
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(heading(0, "标题"), text(1, "正文"), image(2, "a.png")),
        )
        assertEquals("标题${EPUB_FLOW_PARAGRAPH_SEPARATOR}正文${EPUB_FLOW_PARAGRAPH_SEPARATOR}$EPUB_FLOW_IMAGE_CHAR", flow.text)
        assertEquals(3, flow.segments.size)
        assertEquals(0 to 2, flow.segments[0].layoutStart to flow.segments[0].layoutEnd)
        assertTrue(flow.segments[2].isImage)
    }

    @Test
    fun `maps a layout offset inside a paragraph back to paragraph-local offset`() {
        val flow = epubBuildChapterFlow(0, listOf(text(0, "abcde"), text(1, "fghij")))
        // "abcde\n\nfghij" — offset 8 is 'h' = index 1 of the second paragraph.
        assertEquals(1 to 1, flow.paragraphAtOffset(8))
        assertEquals(0 to 0, flow.paragraphAtOffset(0))
        assertEquals(0 to 4, flow.paragraphAtOffset(4))
    }

    @Test
    fun `offset in a separator gap snaps to next content paragraph`() {
        val flow = epubBuildChapterFlow(0, listOf(text(0, "abcde"), text(1, "fghij")))
        // offsets 5,6 are the "\n\n" gap → next content = paragraph 1 offset 0.
        assertEquals(1 to 0, flow.paragraphAtOffset(5))
        assertEquals(1 to 0, flow.paragraphAtOffset(6))
    }

    @Test
    fun `offsetForParagraph is the inverse of paragraphAtOffset`() {
        val flow = epubBuildChapterFlow(0, listOf(text(0, "abcde"), text(1, "fghij")))
        val offset = flow.offsetForParagraph(1, 2)
        assertEquals(1 to 2, flow.paragraphAtOffset(offset))
    }

    @Test
    fun `image paragraph maps to offset zero`() {
        val flow = epubBuildChapterFlow(0, listOf(text(0, "abc"), image(1, "x.png")))
        val imgSeg = flow.segments.first { it.isImage }
        assertEquals(1 to 0, flow.paragraphAtOffset(imgSeg.layoutStart))
    }

    @Test
    fun `empty flow returns null`() {
        assertNull(epubBuildChapterFlow(0, emptyList()).paragraphAtOffset(0))
    }

    @Test
    fun `consecutive empty breaks and blank text collapse so body keeps one short gap`() {
        val raw = listOf(
            text(0, "第一段"),
            EpubDisplayBlock.Break(paragraphIndex = 1),
            EpubDisplayBlock.Break(paragraphIndex = 2),
            EpubDisplayBlock.Break(paragraphIndex = 3),
            EpubDisplayBlock.Text(text = "   ", headingLevel = null, paragraphIndex = 4),
            text(5, "第二段"),
            image(6, "a.png"),
        )
        val compressed = epubCompressEmptyDisplayBlocks(raw)
        assertEquals(
            listOf(0, 5, 6),
            compressed.map { it.paragraphIndex },
        )
        // Meaningful paragraphs keep their locator indices; empties are gone.
        assertEquals(2, compressed.count { it is EpubDisplayBlock.Text })
        assertTrue(compressed.none { it.isEmptyVisualBlock() })

        val flow = epubBuildChapterFlow(0, raw)
        // Only one paragraph separator between the two body blocks (bounded short gap).
        assertEquals(
            "第一段${EPUB_FLOW_PARAGRAPH_SEPARATOR}第二段${EPUB_FLOW_PARAGRAPH_SEPARATOR}$EPUB_FLOW_IMAGE_CHAR",
            flow.text,
        )
        // Anchors still map via original paragraphIndex on retained segments.
        assertEquals(0 to 0, flow.paragraphAtOffset(0))
        assertEquals(5 to 0, flow.paragraphAtOffset(flow.offsetForParagraph(5, 0)))
        assertEquals(6 to 0, flow.paragraphAtOffset(flow.offsetForParagraph(6, 0)))
    }

    @Test
    fun `content-only blocks are not dropped by empty compression`() {
        val blocks = listOf(heading(0, "章"), text(1, "正文"), image(2, "c.png"))
        assertEquals(blocks, epubCompressEmptyDisplayBlocks(blocks))
    }

    @Test
    fun `horizontal rule survives empty compression while line break is removed`() {
        val blocks = epubDisplayBlocks(
            parseReaderItemsFromHtml(
                spineIndex = 0,
                html = "<html><body><p>上文</p><br/><hr/><br/><p>下文</p></body></html>",
            ),
        )

        val flow = epubBuildChapterFlow(spineIndex = 0, blocks = blocks)

        assertEquals(3, flow.segments.size)
        assertEquals("上文", (flow.segments.first().block as EpubDisplayBlock.Text).text)
        assertTrue(flow.segments[1].block is EpubDisplayBlock.Break)
        assertTrue(flow.segments[1].layoutEnd > flow.segments[1].layoutStart)
        assertEquals("下文", (flow.segments.last().block as EpubDisplayBlock.Text).text)
    }

    @Test
    fun `japanese light novel corpus compresses accidental blanks while preserving structure`() {
        // Mixed JP paragraphs, intentional multi-blank scene beat (still collapses to one
        // separator between retained content), heading, HR, image, and inline-styled body.
        val raw = listOf(
            heading(0, "第一章　朝焼け"),
            text(1, "「……おはよう」彼は呟いた。"),
            EpubDisplayBlock.Break(paragraphIndex = 2, kind = EpubBreakKind.LineBreak),
            EpubDisplayBlock.Break(paragraphIndex = 3, kind = EpubBreakKind.LineBreak),
            EpubDisplayBlock.Text(text = "\n\n", headingLevel = null, paragraphIndex = 4),
            text(5, "窓の外では、鳥が鳴いていた。"),
            EpubDisplayBlock.Break(paragraphIndex = 6, kind = EpubBreakKind.HorizontalRule),
            EpubDisplayBlock.Break(paragraphIndex = 7, kind = EpubBreakKind.LineBreak),
            text(8, "　　——場面が変わる——"),
            EpubDisplayBlock.Text(text = "   ", headingLevel = null, paragraphIndex = 9),
            image(10, "scene-break.png"),
            text(11, "彼女は微笑んだ。<i>静かに</i>。"),
        )

        val compressed = epubCompressEmptyDisplayBlocks(raw)
        // Line breaks + blank text drop; heading, body, HR, image, and styled body remain.
        assertEquals(
            listOf(0, 1, 5, 6, 8, 10, 11),
            compressed.map { it.paragraphIndex },
        )
        assertTrue(compressed.any { it is EpubDisplayBlock.Text && it.headingLevel != null })
        assertTrue(
            compressed.any {
                it is EpubDisplayBlock.Break && it.kind == EpubBreakKind.HorizontalRule
            },
        )
        assertTrue(compressed.any { it is EpubDisplayBlock.Image })
        assertTrue(compressed.none { it.isEmptyVisualBlock() })

        val flow = epubBuildChapterFlow(0, raw)
        // Bounded single separator between retained blocks — no stacked empty pages.
        assertEquals(
            "第一章　朝焼け${EPUB_FLOW_PARAGRAPH_SEPARATOR}" +
                "「……おはよう」彼は呟いた。${EPUB_FLOW_PARAGRAPH_SEPARATOR}" +
                "窓の外では、鳥が鳴いていた。${EPUB_FLOW_PARAGRAPH_SEPARATOR}" +
                "$EPUB_FLOW_HORIZONTAL_RULE_CHAR${EPUB_FLOW_PARAGRAPH_SEPARATOR}" +
                "　　——場面が変わる——${EPUB_FLOW_PARAGRAPH_SEPARATOR}" +
                "$EPUB_FLOW_IMAGE_CHAR${EPUB_FLOW_PARAGRAPH_SEPARATOR}" +
                "彼女は微笑んだ。<i>静かに</i>。",
            flow.text,
        )
        // Locator anchors still use original paragraph indices on retained segments.
        assertEquals(0 to 0, flow.paragraphAtOffset(flow.offsetForParagraph(0, 0)))
        assertEquals(10 to 0, flow.paragraphAtOffset(flow.offsetForParagraph(10, 0)))
        assertEquals(11 to 0, flow.paragraphAtOffset(flow.offsetForParagraph(11, 0)))
    }
}
