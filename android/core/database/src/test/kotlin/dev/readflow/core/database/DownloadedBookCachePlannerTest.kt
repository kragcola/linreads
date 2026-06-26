package dev.readflow.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedBookCachePlannerTest {

    @Test
    fun evictsOldestBooksBeyondLimit() {
        val planner = DownloadedBookCachePlanner(cacheLimit = 3)
        val books = listOf(
            cachedBook("calibre-1", lastReadAt = 100),
            cachedBook("calibre-2", lastReadAt = 400),
            cachedBook("calibre-3", lastReadAt = 300),
            cachedBook("calibre-4", lastReadAt = 200),
            cachedBook("calibre-5", lastReadAt = 500),
        )

        val evictions = planner.evictions(books)

        assertEquals(listOf("calibre-1", "calibre-4"), evictions.map { it.id })
    }

    @Test
    fun treatsNeverReadBooksAsOldest() {
        val planner = DownloadedBookCachePlanner(cacheLimit = 2)
        val books = listOf(
            cachedBook("calibre-unread", lastReadAt = null),
            cachedBook("calibre-old", lastReadAt = 100),
            cachedBook("calibre-new", lastReadAt = 200),
        )

        val evictions = planner.evictions(books)

        assertEquals(listOf("calibre-unread"), evictions.map { it.id })
    }

    @Test
    fun keepsProtectedBookEvenWhenItIsOldest() {
        val planner = DownloadedBookCachePlanner(cacheLimit = 2)
        val books = listOf(
            cachedBook("calibre-current", lastReadAt = 100),
            cachedBook("calibre-old", lastReadAt = 200),
            cachedBook("calibre-new", lastReadAt = 300),
        )

        val evictions = planner.evictions(books, protectedBookId = "calibre-current")

        assertEquals(listOf("calibre-old"), evictions.map { it.id })
    }

    private fun cachedBook(id: String, lastReadAt: Long?) = DownloadedCacheBook(
        id = id,
        localUri = "file:///books/$id.epub",
        lastReadAt = lastReadAt,
    )
}
