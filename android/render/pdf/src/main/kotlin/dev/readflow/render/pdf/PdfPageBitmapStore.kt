package dev.readflow.render.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PdfPageBitmapStore<T : Any>(
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    maxEntries: Int,
    private val release: (T) -> Unit,
    private val render: suspend (Int) -> T?,
) {
    private val cache = PdfPageBitmapCache(maxEntries = maxEntries, release = release)
    private val pending = mutableMapOf<Int, Pending<T>>()

    val size: Int
        get() = cache.size

    fun cached(pageIndex: Int): T? = cache.get(pageIndex)

    fun put(pageIndex: Int, value: T) {
        cache.put(pageIndex, value)
    }

    fun load(pageIndex: Int, onReady: (T?) -> Unit) {
        cache.get(pageIndex)?.let {
            onReady(it)
            return
        }
        pending[pageIndex]?.let { active ->
            active.callbacks += onReady
            return
        }
        val callbacks = mutableListOf(onReady)
        pending[pageIndex] = Pending(
            callbacks = callbacks,
            job = scope.launch {
                val rendered = withContext(renderDispatcher) { render(pageIndex) }
                val delivery = pending.remove(pageIndex)?.callbacks.orEmpty()
                rendered?.let { cache.put(pageIndex, it) }
                delivery.forEach { it(rendered) }
            },
        )
    }

    fun prefetch(pageIndex: Int) {
        if (cache.get(pageIndex) != null || pending.containsKey(pageIndex)) return
        pending[pageIndex] = Pending(
            callbacks = mutableListOf(),
            job = scope.launch {
                val rendered = withContext(renderDispatcher) { render(pageIndex) }
                pending.remove(pageIndex)
                rendered?.let { cache.put(pageIndex, it) }
            },
        )
    }

    fun prefetchAround(pageIndex: Int, radius: Int, validPages: IntRange) {
        val safeRadius = radius.coerceAtLeast(0)
        for (distance in 1..safeRadius) {
            val next = pageIndex + distance
            if (next in validPages) prefetch(next)
            val previous = pageIndex - distance
            if (previous in validPages) prefetch(previous)
        }
    }

    fun retainAround(pageIndex: Int, radius: Int) {
        cache.retainAround(pageIndex, radius)
    }

    fun clear() {
        val activeJobs = pending.values.map { it.job }
        pending.clear()
        activeJobs.forEach { it.cancel() }
        cache.clear()
    }

    private data class Pending<T>(
        val callbacks: MutableList<(T?) -> Unit>,
        val job: Job,
    )
}
