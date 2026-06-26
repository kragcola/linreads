package dev.readflow.render.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfPageBitmapStoreTest {

    @Test
    fun `load coalesces duplicate in flight renders and reuses cached bitmap`() = runTest {
        val renderCalls = mutableListOf<Int>()
        val callbacks = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PdfPageBitmapStore(
            scope = this,
            renderDispatcher = dispatcher,
            maxEntries = 3,
            release = {},
            render = { pageIndex ->
                renderCalls += pageIndex
                "page-$pageIndex"
            },
        )

        store.load(1) { bitmap -> callbacks += "first=$bitmap" }
        store.load(1) { bitmap -> callbacks += "second=$bitmap" }
        advanceUntilIdle()

        assertEquals(listOf(1), renderCalls)
        assertEquals(listOf("first=page-1", "second=page-1"), callbacks)
        assertEquals("page-1", store.cached(1))

        store.load(1) { bitmap -> callbacks += "cached=$bitmap" }

        assertEquals(listOf(1), renderCalls)
        assertEquals(
            listOf("first=page-1", "second=page-1", "cached=page-1"),
            callbacks,
        )
    }

    @Test
    fun `clear cancels in flight renders and releases cached pages`() = runTest {
        val released = mutableListOf<String>()
        val callbacks = mutableListOf<String?>()
        val gate = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PdfPageBitmapStore(
            scope = this,
            renderDispatcher = dispatcher,
            maxEntries = 3,
            release = released::add,
            render = { pageIndex ->
                if (pageIndex == 1) {
                    gate.await()
                }
                "page-$pageIndex"
            },
        )

        store.put(0, "page-0")
        val pending = async {
            store.load(1) { bitmap -> callbacks += bitmap }
        }
        advanceUntilIdle()

        store.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        pending.await()

        assertEquals(listOf("page-0"), released)
        assertTrue(callbacks.isEmpty())
        assertEquals(0, store.size)
    }

    @Test
    fun `prefetch around renders both directions through requested radius`() = runTest {
        val renderCalls = mutableListOf<Int>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PdfPageBitmapStore(
            scope = this,
            renderDispatcher = dispatcher,
            maxEntries = 5,
            release = {},
            render = { pageIndex ->
                renderCalls += pageIndex
                "page-$pageIndex"
            },
        )

        store.prefetchAround(pageIndex = 3, radius = 2, validPages = 0..6)
        advanceUntilIdle()

        assertEquals(listOf(4, 2, 5, 1), renderCalls)
        assertEquals("page-1", store.cached(1))
        assertEquals("page-2", store.cached(2))
        assertEquals("page-4", store.cached(4))
        assertEquals("page-5", store.cached(5))
    }
}
