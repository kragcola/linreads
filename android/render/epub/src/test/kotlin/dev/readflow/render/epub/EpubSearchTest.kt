package dev.readflow.render.epub

import dev.readflow.core.model.LocatorStrategy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

        val results = runBlocking { epubSearchLocators(paras, "needle") { paras[it] } }

        assertEquals(3, results.size)
        assertEquals(LocatorStrategy.Section(0, 0, "Alpha ".length), results[0].strategy)
        assertEquals(
            LocatorStrategy.Section(0, 1, "Alpha needle".length + "Second ".length),
            results[1].strategy,
        )
        assertEquals(LocatorStrategy.Section(1, 2, 0), results[2].strategy)
        assertTrue(results[0].totalProgression!! < results[2].totalProgression!!)
    }

    @Test
    fun `search does not complete when invoked from a cancelled undispatched coroutine`() = runTest {
        val spineParagraphs = (0 until 80).map { spine ->
            (0 until 8).map { para ->
                "spine $spine para $para shared-needle more text ".repeat(16)
            }
        }
        val paras = epubParasWithCharacterOffsets(spineParagraphs)

        var completed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            epubSearchLocators(paras, "shared-needle") { paras[it] }
            completed = true
        }

        assertFalse(completed, "cancelled search must not finish a full synchronous scan")
        assertTrue(job.isCancelled)
    }
}
