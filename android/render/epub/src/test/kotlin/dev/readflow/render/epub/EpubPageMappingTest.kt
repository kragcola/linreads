package dev.readflow.render.epub

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubPageMappingTest {

    @Test
    fun `page index can be restored from page or section locator`() {
        assertEquals(
            3,
            epubIndexFromLocator(Locator(LocatorStrategy.Page(index = 3, total = 10)), totalItems = 10),
        )
        assertEquals(
            5,
            epubIndexFromLocator(Locator(LocatorStrategy.Section(spineIndex = 2, elementIndex = 5, charOffset = 12)), totalItems = 10),
        )
    }

    @Test
    fun `unknown locator falls back to total progression and clamps`() {
        assertEquals(
            6,
            epubIndexFromLocator(Locator(LocatorStrategy.Unknown, totalProgression = 0.6f), totalItems = 10),
        )
        assertEquals(
            9,
            epubIndexFromLocator(Locator(LocatorStrategy.Page(index = 42, total = 10)), totalItems = 10),
        )
    }

    @Test
    fun `index maps back to section locator with original spine index`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("intro"),
                emptyList(),
                listOf("chapter"),
            ),
        )
        val locator = epubLocatorForIndex(paras, index = 1)

        assertEquals(LocatorStrategy.Section(spineIndex = 2, elementIndex = 1, charOffset = 0), locator.strategy)
        assertEquals(5f / 12f, locator.totalProgression)
    }

    @Test
    fun `section locator stores paragraph start offset inside spine`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("abcd", "ef")))
        val locator = epubLocatorForIndex(paras, index = 1)

        assertEquals(LocatorStrategy.Section(spineIndex = 0, elementIndex = 1, charOffset = 4), locator.strategy)
    }

    @Test
    fun `paged layout splits long paragraphs into page slices`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("abcdefghijkl", "tail")))

        val pages = epubPagedLayout(
            paras = paras,
            textProvider = { index -> paras[index].text },
            charsPerPage = 5,
        )

        assertEquals(4, pages.size)
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 5), pages[0])
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 5, endOffset = 10), pages[1])
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 10, endOffset = 12), pages[2])
        assertEquals(EpubPageSlice(paragraphIndex = 1, startOffset = 0, endOffset = 4), pages[3])
    }

    @Test
    fun `paged layout uses indexed character ranges when lazy text is empty`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("abcdefghijkl")))
            .map { it.copy(text = "") }

        val pages = epubPagedLayout(
            paras = paras,
            textProvider = { "" },
            charsPerPage = 5,
        )

        assertEquals(3, pages.size)
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 5), pages[0])
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 5, endOffset = 10), pages[1])
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 10, endOffset = 12), pages[2])
    }

    @Test
    fun `paged layout maps section locator to containing page slice`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("abcdefghijkl", "tail")))
        val pages = epubPagedLayout(
            paras = paras,
            textProvider = { index -> paras[index].text },
            charsPerPage = 5,
        )

        assertEquals(
            1,
            epubPageIndexFromLocator(
                locator = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 7)),
                pages = pages,
                paras = paras,
            ),
        )
        assertEquals(
            3,
            epubPageIndexFromLocator(
                locator = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 1, charOffset = 12)),
                pages = pages,
                paras = paras,
            ),
        )
    }

    @Test
    fun `paged layout maps page locator directly to page slice`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("abcdefghijkl", "tail")))
        val pages = epubPagedLayout(
            paras = paras,
            textProvider = { index -> paras[index].text },
            charsPerPage = 5,
        )

        assertEquals(
            2,
            epubPageIndexFromLocator(
                locator = Locator(LocatorStrategy.Page(index = 2, total = pages.size)),
                pages = pages,
                paras = paras,
            ),
        )
    }

    @Test
    fun `page slice maps back to section locator with char offset inside paragraph`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("abcdefghijkl")))
        val pages = epubPagedLayout(
            paras = paras,
            textProvider = { index -> paras[index].text },
            charsPerPage = 5,
        )

        assertEquals(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = 5),
            epubLocatorForPageSlice(paras, pages[1]).strategy,
        )
    }

    @Test
    fun `image page slice maps to a distinct section offset after preceding text`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("Intro text", "Body text")))
        val imagePage = EpubPageSlice(
            paragraphIndex = 0,
            startOffset = "Intro text".length,
            endOffset = "Intro text".length,
            kind = EpubPageSliceKind.Image("images/cover.png", altText = "Cover"),
        )

        val locator = epubLocatorForPageSlice(paras, imagePage)

        assertEquals(
            LocatorStrategy.Section(spineIndex = 0, elementIndex = 0, charOffset = "Intro text".length),
            locator.strategy,
        )
        assertEquals(
            1,
            epubPageIndexFromLocator(
                locator = locator,
                pages = listOf(
                    EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = "Intro text".length),
                    imagePage,
                    EpubPageSlice(paragraphIndex = 1, startOffset = 0, endOffset = "Body text".length),
                ),
                paras = paras,
            ),
        )
    }

    @Test
    fun `multiple image page slices in one paragraph round trip to distinct pages`() {
        val intro = "Intro text"
        val paras = epubParasWithCharacterOffsets(listOf(listOf(intro, "Body text")))

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { index -> paras[index].text },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Text(intro, headingLevel = null, paragraphIndex = 0),
                    EpubDisplayBlock.Image("images/cover.png", altText = "Cover", paragraphIndex = 0),
                    EpubDisplayBlock.Image("images/map.png", altText = "Map", paragraphIndex = 0),
                    EpubDisplayBlock.Text("Body text", headingLevel = null, paragraphIndex = 1),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 72,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { text, _, _ -> listOf(0 to text.length) },
        )

        val firstImageLocator = epubLocatorForPageSlice(paras, pages[1])
        val secondImageLocator = epubLocatorForPageSlice(paras, pages[2])

        assertEquals(
            1,
            epubPageIndexFromLocator(firstImageLocator, pages, paras),
        )
        assertEquals(
            2,
            epubPageIndexFromLocator(secondImageLocator, pages, paras),
        )
    }

    @Test
    fun `paged layout does not emit blank synthetic text page before image only spine`() {
        val paras = listOf(
            EpubPara(spineIndex = 0, text = "", spineCharStart = 0, spineCharEnd = 0, documentCharStart = 0, documentCharEnd = 0),
            EpubPara(spineIndex = 1, text = "Chapter one", spineCharStart = 0, spineCharEnd = 11, documentCharStart = 0, documentCharEnd = 11),
        )

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { index -> paras[index].text },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Image("images/cover.png", altText = "Cover", paragraphIndex = 0),
                    EpubDisplayBlock.Text("Chapter one", headingLevel = null, paragraphIndex = 1),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 72,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { text, _, _ -> listOf(0 to text.length) },
        )

        assertEquals(2, pages.size)
        assertEquals(EpubPageSliceKind.Image("images/cover.png", altText = "Cover"), pages[0].kind)
        assertEquals(0, pages[0].paragraphIndex)
        assertEquals(EpubPageSliceKind.Text, pages[1].kind)
        assertEquals(1, pages[1].paragraphIndex)
    }

    @Test
    fun `paged layout keeps text page when leading break shares the first paragraph`() {
        val items = listOf(
            EpubReaderItem.Break(EpubItemLocator(spineIndex = 0, elementIndex = 0)),
            EpubReaderItem.Text(EpubItemLocator(spineIndex = 0, elementIndex = 1), text = "Chapter one"),
        )
        val paras = epubParasFromReaderItems(items)
        val blocks = epubDisplayBlocks(items)

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { index -> paras[index].text },
            blockProvider = { blocks },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 72,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { text, _, _ -> listOf(0 to text.length) },
        )

        assertEquals(
            listOf(
                EpubDisplayBlock.Break(paragraphIndex = 0),
                EpubDisplayBlock.Text("Chapter one", headingLevel = null, paragraphIndex = 0),
            ),
            blocks,
        )
        assertEquals(
            listOf(
                EpubPageSlice(
                    paragraphIndex = 0,
                    startOffset = 0,
                    endOffset = "Chapter one".length,
                ),
            ),
            pages,
        )
    }

    @Test
    fun `viewport paged layout derives page size from measured line capacity`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("abcdefghijklmnopqrstuvwxyz")))

        val narrowPages = epubViewportPagedLayout(
            paras = paras,
            textProvider = { index -> paras[index].text },
            metrics = EpubPageMetrics(
                viewportWidthPx = 100,
                viewportHeightPx = 120,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 100,
                averageCharacterWidthPx = 10f,
                lineHeightPx = 20f,
            ),
        )
        val widePages = epubViewportPagedLayout(
            paras = paras,
            textProvider = { index -> paras[index].text },
            metrics = EpubPageMetrics(
                viewportWidthPx = 180,
                viewportHeightPx = 120,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 100,
                averageCharacterWidthPx = 10f,
                lineHeightPx = 20f,
            ),
        )

        assertEquals(4, narrowPages.size)
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 8), narrowPages[0])
        assertEquals(2, widePages.size)
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 16), widePages[0])
    }

    @Test
    fun `viewport paged layout prefers word boundary near page end`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("alpha beta gamma")))

        val pages = epubViewportPagedLayout(
            paras = paras,
            textProvider = { index -> paras[index].text },
            metrics = EpubPageMetrics(
                viewportWidthPx = 120,
                viewportHeightPx = 80,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 60,
                averageCharacterWidthPx = 10f,
                lineHeightPx = 20f,
            ),
        )

        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 10), pages[0])
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 10, endOffset = 16), pages[1])
    }

    @Test
    fun `viewport paged layout can use lazy paragraph text for word boundaries`() {
        val indexedParas = epubParasWithCharacterOffsets(listOf(listOf("alpha beta gamma")))
            .map { it.copy(text = "") }

        val pages = epubViewportPagedLayout(
            paras = indexedParas,
            textProvider = { "alpha beta gamma" },
            metrics = EpubPageMetrics(
                viewportWidthPx = 120,
                viewportHeightPx = 80,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 60,
                averageCharacterWidthPx = 10f,
                lineHeightPx = 20f,
            ),
        )

        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 10), pages[0])
        assertEquals(EpubPageSlice(paragraphIndex = 0, startOffset = 10, endOffset = 16), pages[1])
    }

    @Test
    fun `static layout paged layout follows measured visual line breaks`() {
        val text = "Readflow measures real wrapped EPUB pagination."
        val paras = epubParasWithCharacterOffsets(listOf(listOf(text)))

        val pages = epubMeasuredPagedLayout(
            paras = paras,
            textProvider = { text },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 72,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { _, contentWidth, _ ->
                assertEquals(120, contentWidth)
                listOf(0 to 9, 9 to 19, 19 to 31, 31 to 43, 43 to 47)
            },
        )

        assertEquals(
            listOf(
                EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 31),
                EpubPageSlice(paragraphIndex = 0, startOffset = 31, endOffset = 47),
            ),
            pages,
        )
    }

    @Test
    fun `paged layout inserts cached image blocks as standalone slices`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("Intro text", "Body text")))

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { index -> paras[index].text },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Text("Intro text", headingLevel = null, paragraphIndex = 0),
                    EpubDisplayBlock.Image("images/cover.png", altText = "Cover", paragraphIndex = 0),
                    EpubDisplayBlock.Text("Body text", headingLevel = null, paragraphIndex = 1),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 72,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { text, _, _ -> listOf(0 to text.length) },
        )

        assertEquals(
            listOf(
                EpubPageSlice(paragraphIndex = 0, startOffset = 0, endOffset = 10),
                EpubPageSlice(
                    paragraphIndex = 0,
                    startOffset = 10,
                    endOffset = 10,
                    kind = EpubPageSliceKind.Image("images/cover.png", altText = "Cover"),
                ),
                EpubPageSlice(paragraphIndex = 1, startOffset = 0, endOffset = 9),
            ),
            pages,
        )
    }

    @Test
    fun `paged layout preserves cached text block style metadata`() {
        val paras = epubParasWithCharacterOffsets(listOf(listOf("Chapter title", "code block")))

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { index -> paras[index].text },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Text(
                        text = "Chapter title",
                        headingLevel = 2,
                        paragraphIndex = 0,
                        kind = EpubTextKind.Body,
                    ),
                    EpubDisplayBlock.Text(
                        text = "code block",
                        headingLevel = null,
                        paragraphIndex = 1,
                        kind = EpubTextKind.Preformatted,
                        indentLevel = 1,
                    ),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 72,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { text, _, _ -> listOf(0 to text.length) },
        )

        assertEquals(
            EpubPageTextStyle(headingLevel = 2),
            pages[0].textStyle,
        )
        assertEquals(
            EpubPageTextStyle(kind = EpubTextKind.Preformatted, indentLevel = 1),
            pages[1].textStyle,
        )
    }

    @Test
    fun `paged layout preserves page local link metadata`() {
        val text = "Alpha Beta link"
        val paras = epubParasWithCharacterOffsets(listOf(listOf(text)))
        val link = EpubTextLink(
            start = text.indexOf("link"),
            end = text.length,
            href = "OEBPS/notes.xhtml#note",
            isExternal = false,
        )

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { index -> paras[index].text },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Text(
                        text = text,
                        headingLevel = null,
                        paragraphIndex = 0,
                        links = listOf(link),
                    ),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 24,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { _, _, _ -> listOf(0 to 10, 10 to text.length) },
        )

        assertEquals(emptyList<EpubTextLink>(), pages[0].links)
        assertEquals(
            listOf(link.copy(start = 1, end = 5)),
            pages[1].links,
        )
    }

    @Test
    fun `paged layout measures cached text blocks with their style metadata`() {
        val heading = "Heading text"
        val body = "Body text"
        val paras = epubParasWithCharacterOffsets(listOf(listOf(heading, body)))

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { index -> paras[index].text },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Text(
                        text = heading,
                        headingLevel = 1,
                        paragraphIndex = 0,
                    ),
                    EpubDisplayBlock.Text(
                        text = body,
                        headingLevel = null,
                        paragraphIndex = 1,
                    ),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 140,
                viewportHeightPx = 24,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { text, _, style ->
                if (style.headingLevel == 1) {
                    listOf(0 to 7, 7 to text.length)
                } else {
                    listOf(0 to text.length)
                }
            },
        )

        assertEquals(
            listOf(
                EpubPageSlice(
                    paragraphIndex = 0,
                    startOffset = 0,
                    endOffset = 7,
                    textStyle = EpubPageTextStyle(headingLevel = 1),
                ),
                EpubPageSlice(
                    paragraphIndex = 0,
                    startOffset = 7,
                    endOffset = heading.length,
                    textStyle = EpubPageTextStyle(headingLevel = 1),
                ),
                EpubPageSlice(paragraphIndex = 1, startOffset = 0, endOffset = body.length),
            ),
            pages,
        )
    }

    @Test
    fun `compose text layout result line ranges are preserved as compose measured page slices`() {
        val text = "Compose pagination follows TextLayoutResult visual lines."
        val paras = epubParasWithCharacterOffsets(listOf(listOf(text)))

        val pages = epubComposeMeasuredPagedLayout(
            paras = paras,
            textProvider = { text },
            metrics = EpubPageMetrics(
                viewportWidthPx = 180,
                viewportHeightPx = 48,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            textLayoutLines = { _, contentWidth, style ->
                assertEquals(160, contentWidth)
                assertEquals(EpubPageTextStyle(), style)
                listOf(
                    EpubTextLayoutLineRange(start = 0, end = 8),
                    EpubTextLayoutLineRange(start = 8, end = 20),
                    EpubTextLayoutLineRange(start = 20, end = 38),
                    EpubTextLayoutLineRange(start = 38, end = text.length),
                )
            },
        )

        assertEquals(
            listOf(
                EpubPageSlice(
                    paragraphIndex = 0,
                    startOffset = 0,
                    endOffset = 20,
                    measurement = EpubPageMeasurement.ComposeTextLayoutResult,
                ),
                EpubPageSlice(
                    paragraphIndex = 0,
                    startOffset = 20,
                    endOffset = text.length,
                    measurement = EpubPageMeasurement.ComposeTextLayoutResult,
                ),
            ),
            pages,
        )
    }

    @Test
    fun `compose measured pagination keeps measurement and style when line ranges are empty`() {
        val text = "Compose fallback page"
        val style = EpubPageTextStyle(headingLevel = 2, kind = EpubTextKind.Blockquote, indentLevel = 1)
        val paras = epubParasWithCharacterOffsets(listOf(listOf(text)))

        val pages = epubComposeMeasuredPagedLayout(
            paras = paras,
            textProvider = { text },
            metrics = EpubPageMetrics(
                viewportWidthPx = 180,
                viewportHeightPx = 48,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            textLayoutLines = { _, _, _ -> emptyList() },
            textStyleProvider = { style },
        )

        assertEquals(
            listOf(
                EpubPageSlice(
                    paragraphIndex = 0,
                    startOffset = 0,
                    endOffset = text.length,
                    textStyle = style,
                    measurement = EpubPageMeasurement.ComposeTextLayoutResult,
                ),
            ),
            pages,
        )
    }

    @Test
    fun `block paged layout can preserve compose measurement source`() {
        val text = "Styled compose block"
        val paras = epubParasWithCharacterOffsets(listOf(listOf(text)))

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { text },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Text(
                        text = text,
                        headingLevel = 2,
                        paragraphIndex = 0,
                    ),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 180,
                viewportHeightPx = 48,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 1f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { _, _, _ -> listOf(0 to text.length) },
            measurement = EpubPageMeasurement.ComposeTextLayoutResult,
        )

        assertEquals(EpubPageMeasurement.ComposeTextLayoutResult, pages.single().measurement)
        assertEquals(EpubPageTextStyle(headingLevel = 2), pages.single().textStyle)
    }

    @Test
    fun `block paged layout preserves compose measurement and style when cached text is empty`() {
        val text = "Styled cold compose block"
        val paras = epubParasWithCharacterOffsets(listOf(listOf(text)))
            .map { it.copy(text = "") }

        val pages = epubPagedLayoutWithBlocks(
            paras = paras,
            textProvider = { "" },
            blockProvider = {
                listOf(
                    EpubDisplayBlock.Text(
                        text = text,
                        headingLevel = 2,
                        kind = EpubTextKind.Blockquote,
                        indentLevel = 1,
                        paragraphIndex = 0,
                    ),
                )
            },
            metrics = EpubPageMetrics(
                viewportWidthPx = 180,
                viewportHeightPx = 48,
                horizontalPaddingPx = 20,
                verticalPaddingPx = 0,
                averageCharacterWidthPx = 10f,
                lineHeightPx = 24f,
            ),
            lineBreaker = { _, _, _ -> error("cold text should not be measured") },
            measurement = EpubPageMeasurement.ComposeTextLayoutResult,
        )

        assertEquals(
            List(pages.size) { EpubPageMeasurement.ComposeTextLayoutResult },
            pages.map { it.measurement },
        )
        assertEquals(
            List(pages.size) {
                EpubPageTextStyle(headingLevel = 2, kind = EpubTextKind.Blockquote, indentLevel = 1)
            },
            pages.map { it.textStyle },
        )
    }
}
