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

        val results = runBlocking { epubSearchHits(paras, "needle") { paras[it] } }

        assertEquals(3, results.size)
        assertEquals(LocatorStrategy.Section(0, 0, "Alpha ".length), results[0].locator.strategy)
        assertEquals(
            LocatorStrategy.Section(0, 1, "Alpha needle".length + "Second ".length),
            results[1].locator.strategy,
        )
        assertEquals(LocatorStrategy.Section(1, 2, 0), results[2].locator.strategy)
        assertTrue(results[0].locator.totalProgression!! < results[2].locator.totalProgression!!)
        assertTrue(results[0].snippet.contains("needle", ignoreCase = true), results[0].snippet)
        assertTrue(results[1].snippet.contains("Needle", ignoreCase = true), results[1].snippet)
        assertTrue(results[2].snippet.contains("needle", ignoreCase = true), results[2].snippet)
        assertFalse(results[0].snippet.contains('\n'), results[0].snippet)
        // Character-space match length equals needle length (not byte length).
        assertEquals("needle".length, results[0].matchLength)
        assertEquals("needle".length, results[1].matchLength)
        assertEquals("needle".length, results[2].matchLength)
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
            epubSearchHits(paras, "shared-needle") { paras[it] }
            completed = true
        }

        assertFalse(completed, "cancelled search must not finish a full synchronous scan")
        assertTrue(job.isCancelled)
    }
}
