package dev.readflow.render.pdf

internal class PdfPageBitmapCache<T : Any>(
    private val maxEntries: Int,
    private val release: (T) -> Unit,
) {
    private val entries = LinkedHashMap<Int, T>(maxEntries.coerceAtLeast(1), 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    val size: Int
        get() = entries.size

    fun get(pageIndex: Int): T? = entries[pageIndex]

    fun put(pageIndex: Int, value: T) {
        val previous = entries.put(pageIndex, value)
        if (previous != null && previous !== value) {
            release(previous)
        }
        trimToMaxEntries()
    }

    fun getOrPut(pageIndex: Int, create: () -> T): T {
        entries[pageIndex]?.let { return it }
        return create().also { bitmap ->
            entries[pageIndex] = bitmap
            trimToMaxEntries()
        }
    }

    fun retainAround(pageIndex: Int, radius: Int) {
        val safeRadius = radius.coerceAtLeast(0)
        val minPage = pageIndex - safeRadius
        val maxPage = pageIndex + safeRadius
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in minPage..maxPage) {
                iterator.remove()
                release(entry.value)
            }
        }
        trimToMaxEntries()
    }

    fun clear() {
        val cached = entries.values.toList()
        entries.clear()
        cached.forEach(release)
    }

    private fun trimToMaxEntries() {
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries && iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            release(entry.value)
        }
    }
}
