package dev.readflow.render.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PdfPageBitmapStore<T : Any>(
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    maxEntries: Int,
    private val release: (T) -> Unit,
    private val render: suspend (Int) -> T?,
) {
    private val stateLock = Any()
    private val cache = PdfPageBitmapCache(maxEntries = maxEntries, release = release)
    private val pending = mutableMapOf<Int, Pending<T>>()
    private var retainedPages: IntRange? = null
    private var acceptsRequests = true

    val size: Int
        get() = synchronized(stateLock) { cache.size }

    fun cached(pageIndex: Int): T? = synchronized(stateLock) { cache.get(pageIndex) }

    fun put(pageIndex: Int, value: T) {
        synchronized(stateLock) {
            if (acceptsRequests) {
                cache.put(pageIndex, value)
            } else {
                release(value)
            }
        }
    }

    fun load(pageIndex: Int, onReady: (T?) -> Unit) {
        var immediate: T? = null
        var notifyEmpty = false
        synchronized(stateLock) {
            if (!isRetainedLocked(pageIndex)) {
                notifyEmpty = true
            } else {
                cache.get(pageIndex)?.let {
                    immediate = it
                    return@synchronized
                }
                pending[pageIndex]?.let { active ->
                    active.callbacks += onReady
                    return@synchronized
                }
                enqueueLocked(pageIndex, mutableListOf(onReady))
            }
        }
        when {
            notifyEmpty -> onReady(null)
            immediate != null -> onReady(immediate)
        }
    }

    fun prefetch(pageIndex: Int) {
        synchronized(stateLock) {
            if (!isRetainedLocked(pageIndex) || cache.get(pageIndex) != null || pending.containsKey(pageIndex)) {
                return
            }
            enqueueLocked(pageIndex, mutableListOf())
        }
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
        synchronized(stateLock) {
            if (!acceptsRequests) return
            val safeRadius = radius.coerceAtLeast(0)
            retainedPages = (pageIndex - safeRadius)..(pageIndex + safeRadius)
            cache.retainAround(pageIndex, safeRadius)
        }
    }

    fun isRetained(pageIndex: Int): Boolean =
        synchronized(stateLock) { isRetainedLocked(pageIndex) }

    suspend fun clear() {
        val activeJobs = synchronized(stateLock) {
            acceptsRequests = false
            val jobs = pending.values.map { it.job }
            pending.clear()
            jobs
        }
        activeJobs.forEach { it.cancel() }
        activeJobs.joinAll()
        synchronized(stateLock) {
            cache.clear()
            retainedPages = null
        }
    }

    private fun isRetainedLocked(pageIndex: Int): Boolean =
        acceptsRequests && (retainedPages?.let { pageIndex in it } ?: true)

    private fun enqueueLocked(pageIndex: Int, callbacks: MutableList<(T?) -> Unit>) {
        val request = Pending(callbacks)
        request.job = scope.launch(start = CoroutineStart.LAZY) {
            var unownedValue: T? = null
            try {
                withContext(renderDispatcher) {
                    unownedValue = render(pageIndex)
                }
                val delivery = takeCallbacks(pageIndex, request)
                val value = unownedValue
                val retained = synchronized(stateLock) {
                    value?.takeIf { isRetainedLocked(pageIndex) }?.also {
                        cache.put(pageIndex, it)
                    }
                }
                if (retained != null) {
                    unownedValue = null
                } else if (value != null) {
                    unownedValue = null
                    release(value)
                }
                delivery.forEach { it(retained) }
            } finally {
                removePending(pageIndex, request)
                unownedValue?.let(release)
            }
        }
        pending[pageIndex] = request
        request.job.start()
    }

    private fun takeCallbacks(pageIndex: Int, request: Pending<T>): List<(T?) -> Unit> {
        synchronized(stateLock) {
            if (pending[pageIndex] !== request) return emptyList()
            pending.remove(pageIndex)
            return request.callbacks.toList()
        }
    }

    private fun removePending(pageIndex: Int, request: Pending<T>) {
        synchronized(stateLock) {
            if (pending[pageIndex] === request) pending.remove(pageIndex)
        }
    }

    private class Pending<T>(
        val callbacks: MutableList<(T?) -> Unit>,
    ) {
        lateinit var job: Job
    }
}
