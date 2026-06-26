package dev.readflow.render.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PdfPageBitmapCacheTest {

    @Test
    fun `evicts least recently used page when max entries is exceeded`() {
        val released = mutableListOf<FakeBitmap>()
        val cache = PdfPageBitmapCache(maxEntries = 3, release = released::add)

        val page0 = cache.getOrPut(0) { FakeBitmap(0) }
        val page1 = cache.getOrPut(1) { FakeBitmap(1) }
        cache.getOrPut(2) { FakeBitmap(2) }
        cache.get(0)
        cache.getOrPut(3) { FakeBitmap(3) }

        assertEquals(page0, cache.get(0))
        assertNull(cache.get(1))
        assertEquals(listOf(page1), released)
        assertEquals(3, cache.size)
    }

    @Test
    fun `retains only pages inside the current page window`() {
        val released = mutableListOf<FakeBitmap>()
        val cache = PdfPageBitmapCache(maxEntries = 5, release = released::add)
        val pages = (0..4).associateWith { page -> cache.getOrPut(page) { FakeBitmap(page) } }

        cache.retainAround(pageIndex = 3, radius = 1)

        assertNull(cache.get(0))
        assertNull(cache.get(1))
        assertEquals(pages.getValue(2), cache.get(2))
        assertEquals(pages.getValue(3), cache.get(3))
        assertEquals(pages.getValue(4), cache.get(4))
        assertEquals(listOf(pages.getValue(0), pages.getValue(1)), released)
        assertEquals(3, cache.size)
    }

    @Test
    fun `clear releases all cached pages exactly once`() {
        val released = mutableListOf<FakeBitmap>()
        val cache = PdfPageBitmapCache(maxEntries = 3, release = released::add)
        val pages = (0..2).map { page -> cache.getOrPut(page) { FakeBitmap(page) } }

        cache.clear()
        cache.clear()

        assertEquals(pages, released)
        assertEquals(0, cache.size)
    }

    private data class FakeBitmap(val page: Int)
}
