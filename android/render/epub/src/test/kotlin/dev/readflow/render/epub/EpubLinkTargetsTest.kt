package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubLinkTargetsTest {

    @Test
    fun `internal link targets resolve to the first paragraph of matching spine`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("one", "two"),
                listOf("three"),
            ),
        )

        assertEquals(
            mapOf(
                "OEBPS/ch1.xhtml" to EpubTargetPosition(0),
                "OEBPS/ch2.xhtml" to EpubTargetPosition(2),
            ),
            epubInternalLinkTargetIndexes(
                spinePaths = listOf("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml"),
                paras = paras,
            ),
        )
    }

    @Test
    fun `internal link targets resolve fragments to matching paragraph`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("start", "scene"),
            ),
        )

        assertEquals(
            mapOf(
                "OEBPS/ch1.xhtml" to EpubTargetPosition(0),
                "OEBPS/ch1.xhtml#start" to EpubTargetPosition(0),
                "OEBPS/ch1.xhtml#scene" to EpubTargetPosition(1),
            ),
            epubInternalLinkTargetIndexes(
                spinePaths = listOf("OEBPS/ch1.xhtml"),
                paras = paras,
                fragmentTargets = mapOf(
                    "OEBPS/ch1.xhtml#start" to EpubTargetPosition(0),
                    "OEBPS/ch1.xhtml#scene" to EpubTargetPosition(1),
                ),
            ),
        )
    }

    @Test
    fun `fragment targets preserve synthetic image anchors within one paragraph`() {
        val items = listOf(
            EpubReaderItem.Text(
                locator = EpubItemLocator(spineIndex = 0, elementIndex = 0),
                text = "Q",
                fragmentIds = listOf("start"),
            ),
            EpubReaderItem.Image(
                locator = EpubItemLocator(spineIndex = 0, elementIndex = 1),
                href = "scene-1.png",
                altText = "Scene 1",
                fragmentIds = listOf("scene-1"),
            ),
            EpubReaderItem.Image(
                locator = EpubItemLocator(spineIndex = 0, elementIndex = 2),
                href = "scene-2.png",
                altText = "Scene 2",
                fragmentIds = listOf("scene-2"),
            ),
        )

        assertEquals(
            mapOf(
                "OEBPS/ch1.xhtml#start" to EpubTargetPosition(paragraphIndex = 0, paragraphOffset = 0),
                "OEBPS/ch1.xhtml#scene-1" to EpubTargetPosition(paragraphIndex = 0, paragraphOffset = 1),
                "OEBPS/ch1.xhtml#scene-2" to EpubTargetPosition(paragraphIndex = 0, paragraphOffset = 2),
            ),
            epubFragmentTargetIndexes(
                spinePaths = listOf("OEBPS/ch1.xhtml"),
                items = items,
            ),
        )
    }

    @Test
    fun `fragment targets in image only spine use synthetic spine paragraph`() {
        val items = listOf(
            EpubReaderItem.Image(
                locator = EpubItemLocator(spineIndex = 0, elementIndex = 0),
                href = "cover.png",
                altText = "Cover",
                fragmentIds = listOf("cover"),
            ),
            EpubReaderItem.Text(
                locator = EpubItemLocator(spineIndex = 1, elementIndex = 1),
                text = "Chapter one",
                fragmentIds = listOf("start"),
            ),
        )

        assertEquals(
            mapOf(
                "OEBPS/cover.xhtml#cover" to EpubTargetPosition(paragraphIndex = 0, paragraphOffset = 0),
                "OEBPS/ch1.xhtml#start" to EpubTargetPosition(paragraphIndex = 1, paragraphOffset = 0),
            ),
            epubFragmentTargetIndexes(
                spinePaths = listOf("OEBPS/cover.xhtml", "OEBPS/ch1.xhtml"),
                items = items,
            ),
        )
    }

    @Test
    fun `internal link target key keeps fragments for runtime click navigation`() {
        assertEquals("OEBPS/ch1.xhtml#scene", epubInternalLinkTargetKey("OEBPS/chapters/../ch1.xhtml#scene"))
        assertEquals("OEBPS/ch1.xhtml", epubInternalLinkTargetKey("OEBPS/ch1.xhtml"))
    }
}
