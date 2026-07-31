package dev.readflow.render.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `late non cooperative load result after clear is released exactly once`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val renderStarted = CountDownLatch(1)
        val allowRenderToReturn = CountDownLatch(1)
        val released = CopyOnWriteArrayList<String>()
        val callbacks = CopyOnWriteArrayList<String?>()
        val store = PdfPageBitmapStore(
            scope = this,
            renderDispatcher = dispatcher,
            maxEntries = 3,
            release = released::add,
            render = { pageIndex ->
                renderStarted.countDown()
                allowRenderToReturn.awaitIgnoringInterrupts()
                "page-$pageIndex"
            },
        )

        try {
            store.load(1, callbacks::add)
            runCurrent()
            assertTrue(
                renderStarted.await(5, TimeUnit.SECONDS),
                "fixture render did not start",
            )

            val clearing = async(start = CoroutineStart.UNDISPATCHED) {
                store.clear()
            }
            allowRenderToReturn.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            advanceUntilIdle()
            clearing.await()

            assertEquals(
                listOf("page-1"),
                released,
                "a value produced after cancellation still has exactly one release owner",
            )
            assertEquals(emptyList<String?>(), callbacks)
            assertEquals(0, store.size)
        } finally {
            allowRenderToReturn.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `late non cooperative prefetch result after clear is released exactly once`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val renderStarted = CountDownLatch(1)
        val allowRenderToReturn = CountDownLatch(1)
        val released = CopyOnWriteArrayList<String>()
        val store = PdfPageBitmapStore(
            scope = this,
            renderDispatcher = dispatcher,
            maxEntries = 3,
            release = released::add,
            render = { pageIndex ->
                renderStarted.countDown()
                allowRenderToReturn.awaitIgnoringInterrupts()
                "page-$pageIndex"
            },
        )

        try {
            store.prefetch(1)
            runCurrent()
            assertTrue(
                renderStarted.await(5, TimeUnit.SECONDS),
                "fixture render did not start",
            )

            val clearing = async(start = CoroutineStart.UNDISPATCHED) {
                store.clear()
            }
            allowRenderToReturn.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            advanceUntilIdle()
            clearing.await()

            assertEquals(
                listOf("page-1"),
                released,
                "a prefetched value produced after cancellation still has exactly one release owner",
            )
            assertNull(store.cached(1))
            assertEquals(0, store.size)
        } finally {
            allowRenderToReturn.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `clear does not complete while a non cooperative render is active`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val renderStarted = CountDownLatch(1)
        val allowRenderToReturn = CountDownLatch(1)
        val renderActive = AtomicBoolean(false)
        val clearReturnedWhileRendering = AtomicBoolean(false)
        val store = PdfPageBitmapStore(
            scope = this,
            renderDispatcher = dispatcher,
            maxEntries = 3,
            release = {},
            render = { pageIndex ->
                renderActive.set(true)
                renderStarted.countDown()
                try {
                    allowRenderToReturn.awaitIgnoringInterrupts()
                    "page-$pageIndex"
                } finally {
                    renderActive.set(false)
                }
            },
        )

        try {
            store.load(1) {}
            runCurrent()
            assertTrue(
                renderStarted.await(5, TimeUnit.SECONDS),
                "fixture render did not start",
            )

            val clearing = async(start = CoroutineStart.UNDISPATCHED) {
                store.clear()
                clearReturnedWhileRendering.set(renderActive.get())
            }
            allowRenderToReturn.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            advanceUntilIdle()
            clearing.await()

            assertFalse(
                clearReturnedWhileRendering.get(),
                "clear must cancel and join renderer work before it completes",
            )
        } finally {
            allowRenderToReturn.countDown()
            dispatcher.close()
        }
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

    @Test
    fun `late render outside the retained window is released instead of evicting current pages`() =
        runTest {
            val released = mutableListOf<String>()
            val callbacks = mutableListOf<String?>()
            val gate = CompletableDeferred<Unit>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = PdfPageBitmapStore(
                scope = this,
                renderDispatcher = dispatcher,
                maxEntries = 1,
                release = released::add,
                render = { pageIndex ->
                    gate.await()
                    "page-$pageIndex"
                },
            )
            store.put(2, "page-2")
            store.retainAround(pageIndex = 2, radius = 1)
            store.load(3) { callbacks += it }
            advanceUntilIdle()

            store.retainAround(pageIndex = 0, radius = 1)
            store.put(0, "page-0")
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("page-0", store.cached(0))
            assertEquals(null, store.cached(3))
            assertEquals(listOf("page-2", "page-3"), released)
            assertEquals(listOf<String?>(null), callbacks)
        }

    private fun CountDownLatch.awaitIgnoringInterrupts() {
        var interrupted = false
        while (true) {
            try {
                await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
