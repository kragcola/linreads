package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubDisplayBlockTest {

    @Test
    fun `reader items become display blocks while preserving paragraph indexes`() {
        val blocks = epubDisplayBlocks(
            listOf(
                EpubReaderItem.Heading(EpubItemLocator(0, 0), level = 1, text = "Title"),
                EpubReaderItem.Image(EpubItemLocator(0, 1), href = "images/cover.jpg", altText = "Cover"),
                EpubReaderItem.Text(EpubItemLocator(0, 2), text = "Body"),
                EpubReaderItem.Break(EpubItemLocator(0, 3)),
            ),
        )

        assertEquals(
            listOf(
                EpubDisplayBlock.Text("Title", headingLevel = 1, paragraphIndex = 0),
                EpubDisplayBlock.Image("images/cover.jpg", altText = "Cover", paragraphIndex = 0),
                EpubDisplayBlock.Text("Body", headingLevel = null, paragraphIndex = 1),
                EpubDisplayBlock.Break(paragraphIndex = 1),
            ),
            blocks,
        )
    }

    @Test
    fun `display blocks preserve inline links on text items`() {
        val links = listOf(EpubTextLink(start = 0, end = 4, href = "https://example.com", isExternal = true))
        val blocks = epubDisplayBlocks(
            listOf(
                EpubReaderItem.Text(EpubItemLocator(0, 0), text = "Link", links = links),
            ),
        )

        assertEquals(
            listOf(EpubDisplayBlock.Text("Link", headingLevel = null, paragraphIndex = 0, links = links)),
            blocks,
        )
    }

    @Test
    fun `display blocks preserve computed css styles`() {
        val blockStyle = EpubBlockStyle(
            textAlign = EpubTextAlign.Justify,
            textIndent = EpubCssLength(2f, EpubCssUnit.Em),
            backgroundColor = 0xFFFAEBD7.toInt(),
        )
        val imageStyle = EpubImageStyle(
            width = EpubCssLength(60f, EpubCssUnit.Percent),
            alignment = EpubTextAlign.Center,
        )

        val blocks = epubDisplayBlocks(
            listOf(
                EpubReaderItem.Text(EpubItemLocator(0, 0), text = "Body", blockStyle = blockStyle),
                EpubReaderItem.Image(EpubItemLocator(0, 1), href = "plate.jpg", altText = null, style = imageStyle),
            ),
        )

        assertEquals(blockStyle, (blocks[0] as EpubDisplayBlock.Text).blockStyle)
        assertEquals(imageStyle, (blocks[1] as EpubDisplayBlock.Image).style)
    }
}
