package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubTextMetricsTest {

    @Test
    fun `paragraphs get spine and document character offsets`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("abcd", "ef"),
                listOf("ghij"),
            ),
        )

        assertEquals(0, paras[0].spineCharStart)
        assertEquals(4, paras[0].spineCharEnd)
        assertEquals(0, paras[0].documentCharStart)
        assertEquals(4, paras[0].documentCharEnd)

        assertEquals(4, paras[1].spineCharStart)
        assertEquals(6, paras[1].spineCharEnd)
        assertEquals(4, paras[1].documentCharStart)
        assertEquals(6, paras[1].documentCharEnd)

        assertEquals(0, paras[2].spineCharStart)
        assertEquals(4, paras[2].spineCharEnd)
        assertEquals(6, paras[2].documentCharStart)
        assertEquals(10, paras[2].documentCharEnd)
    }

    @Test
    fun `spine and document character counts are derived from normalized text`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("abcd", "ef"),
                listOf("ghij"),
            ),
        )

        assertEquals(listOf(6, 4), epubSpineCharCounts(paras))
        assertEquals(10, epubTotalChars(paras))
    }
}
