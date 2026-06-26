package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubSearchTest {

    @Test
    fun `search maps matches to section locators with spine character offsets`() {
        val paras = epubParasWithCharacterOffsets(
            listOf(
                listOf("Alpha needle", "Second Needle"),
                listOf("needle in next spine"),
            ),
        )

        val results = epubSearchLocators(paras, "needle") { paras[it] }

        assertEquals(3, results.size)
        assertEquals(LocatorStrategy.Section(0, 0, "Alpha ".length), results[0].strategy)
        assertEquals(
            LocatorStrategy.Section(0, 1, "Alpha needle".length + "Second ".length),
            results[1].strategy,
        )
        assertEquals(LocatorStrategy.Section(1, 2, 0), results[2].strategy)
        assertTrue(results[0].totalProgression!! < results[2].totalProgression!!)
    }
}
