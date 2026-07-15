package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubProvisionalIndexTest {

    @Test
    fun `deep initial spine keeps its saved global paragraph base`() {
        val index = EpubProvisionalIndex(spineEntryWeights = listOf(10L, 20L, 30L, 40L, 50L))
        val content = content(spine = 3, paragraphCount = 3)

        assertTrue(index.installInitial(content, globalParagraphBase = 80))

        assertEquals(80, index.globalBase(3))
        assertEquals(81, index.globalParagraphIndex(3, localParagraphIndex = 1))
        assertEquals(3, index.parasSnapshot()[81].spineIndex)
        assertEquals(listOf(80, 81, 82), index.blocksForSpine(3).map(EpubDisplayBlock::paragraphIndex))
    }

    @Test
    fun `adjacent chapters propagate exact bases in both directions`() {
        val index = EpubProvisionalIndex(spineEntryWeights = List(5) { 1L })
        assertTrue(index.installInitial(content(spine = 2, paragraphCount = 4), globalParagraphBase = 20))

        assertEquals(24, index.installAdjacent(content(spine = 3, paragraphCount = 2), sourceSpineIndex = 2))
        assertEquals(17, index.installAdjacent(content(spine = 1, paragraphCount = 3), sourceSpineIndex = 2))

        assertEquals(24, index.globalBase(3))
        assertEquals(17, index.globalBase(1))
        assertEquals(19, index.globalParagraphIndex(1, localParagraphIndex = 2))
    }

    @Test
    fun `non adjacent or overlapping install is rejected`() {
        val index = EpubProvisionalIndex(spineEntryWeights = List(4) { 1L })
        assertTrue(index.installInitial(content(spine = 1, paragraphCount = 3), globalParagraphBase = 5))

        assertNull(index.installAdjacent(content(spine = 3, paragraphCount = 2), sourceSpineIndex = 1))
        assertTrue(index.installInitial(content(spine = 2, paragraphCount = 2), globalParagraphBase = 8))
        assertNull(index.installAdjacent(content(spine = 2, paragraphCount = 4), sourceSpineIndex = 1))
    }

    @Test
    fun `entry weight progression is monotonic before the full index is ready`() {
        val index = EpubProvisionalIndex(spineEntryWeights = listOf(10L, 30L, 60L))

        assertEquals(0.10f, index.approximateProgression(spineIndex = 1, spineFraction = 0f), 0.001f)
        assertEquals(0.25f, index.approximateProgression(spineIndex = 1, spineFraction = 0.5f), 0.001f)
        assertEquals(0.40f, index.approximateProgression(spineIndex = 2, spineFraction = 0f), 0.001f)
        assertEquals(1f, index.approximateProgression(spineIndex = 2, spineFraction = 1f), 0.001f)
    }

    private fun content(spine: Int, paragraphCount: Int): EpubProvisionalSpine =
        EpubProvisionalSpine(
            spineIndex = spine,
            paras = List(paragraphCount) { local ->
                EpubPara(
                    spineIndex = spine,
                    text = "",
                    spineCharStart = local * 10,
                    spineCharEnd = (local + 1) * 10,
                    documentCharStart = local * 10,
                    documentCharEnd = (local + 1) * 10,
                )
            },
            blocks = List(paragraphCount) { local ->
                EpubDisplayBlock.Text(
                    text = "spine $spine paragraph $local",
                    headingLevel = null,
                    paragraphIndex = local,
                )
            },
        )
}
