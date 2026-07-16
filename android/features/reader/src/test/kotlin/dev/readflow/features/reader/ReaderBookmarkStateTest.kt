package dev.readflow.features.reader

import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderBookmarkStateTest {

    @Test
    fun `maps active bookmarks in progression order and detects current locator`() {
        val earlyLocator = Locator(
            strategy = LocatorStrategy.Page(index = 2, total = 10),
            totalProgression = 0.2f,
        )
        val lateLocator = Locator(
            strategy = LocatorStrategy.Page(index = 8, total = 10),
            totalProgression = 0.8f,
        )
        val deletedLocator = Locator(
            strategy = LocatorStrategy.Page(index = 5, total = 10),
            totalProgression = 0.5f,
        )

        val state = readerBookmarkStateFor(
            entities = listOf(
                bookmarkEntity("late", lateLocator, totalProgression = 0.8f, createdAt = 300L),
                bookmarkEntity("deleted", deletedLocator, totalProgression = 0.5f, isDeleted = true),
                bookmarkEntity("early", earlyLocator, totalProgression = 0.2f, createdAt = 100L),
            ),
            currentLocator = earlyLocator,
        )

        assertEquals(listOf("early", "late"), state.items.map { it.id })
        assertEquals("early", state.currentBookmarkId)
        assertTrue(state.isCurrentBookmarked)
        assertEquals("书签 20%", state.items.first().label)
    }

    @Test
    fun `same strategy matches regardless of progression distance`() {
        val stored = Locator(
            strategy = LocatorStrategy.Page(index = 4, total = 20),
            totalProgression = 0.2f,
        )
        val current = Locator(
            strategy = LocatorStrategy.Page(index = 4, total = 20),
            totalProgression = 0.99f,
        )
        val state = readerBookmarkStateFor(
            entities = listOf(bookmarkEntity("same-strategy", stored, totalProgression = 0.2f)),
            currentLocator = current,
        )
        assertEquals("same-strategy", state.currentBookmarkId)
        assertTrue(state.isCurrentBookmarked)
    }

    @Test
    fun `cross strategy progression epsilon matches inside boundary and rejects outside`() {
        val pageBookmark = Locator(
            strategy = LocatorStrategy.Page(index = 5, total = 10),
            totalProgression = 0.5f,
        )
        // Production epsilon is 0.0005f. Prefer explicit deltas that stay inside/outside
        // after float32 encoding (0.5f+0.0005f itself can exceed the bound by ulp).
        val epsilon = 0.0005f
        val sectionExact = Locator(
            strategy = LocatorStrategy.Section(spineIndex = 0, elementIndex = 5, charOffset = 0),
            totalProgression = 0.5f,
        )
        val sectionInside = Locator(
            strategy = LocatorStrategy.Section(spineIndex = 0, elementIndex = 5, charOffset = 0),
            totalProgression = 0.5f + epsilon * 0.5f, // 0.00025 — strictly inside
        )
        val sectionAtBoundary = Locator(
            strategy = LocatorStrategy.Section(spineIndex = 0, elementIndex = 5, charOffset = 0),
            // Construct progression so |delta| is exactly epsilon when compared as Float.
            totalProgression = 0.5f + epsilon,
        )
        val sectionOutside = Locator(
            strategy = LocatorStrategy.Section(spineIndex = 0, elementIndex = 5, charOffset = 0),
            totalProgression = 0.5f + epsilon * 2f, // 0.001 — clearly outside
        )
        val entities = listOf(
            bookmarkEntity("bm", pageBookmark, totalProgression = 0.5f),
        )

        assertEquals("bm", readerBookmarkStateFor(entities, sectionExact).currentBookmarkId)
        assertEquals("bm", readerBookmarkStateFor(entities, sectionInside).currentBookmarkId)

        // Boundary: |Δ| <= epsilon must match. If float addition exceeds epsilon by ulp, treat as outside.
        val boundaryDelta = kotlin.math.abs(
            requireNotNull(sectionAtBoundary.totalProgression) - 0.5f,
        )
        val boundaryState = readerBookmarkStateFor(entities, sectionAtBoundary)
        if (boundaryDelta <= epsilon) {
            assertEquals("bm", boundaryState.currentBookmarkId)
        } else {
            assertNull(boundaryState.currentBookmarkId)
        }

        val outside = readerBookmarkStateFor(entities, sectionOutside)
        assertNull(outside.currentBookmarkId)
        assertFalse(outside.isCurrentBookmarked)
    }

    @Test
    fun `tombstones malformed locators and deleted rows stay excluded with stable sort`() {
        val a = Locator(LocatorStrategy.Page(index = 1, total = 10), totalProgression = 0.1f)
        val b = Locator(LocatorStrategy.Page(index = 2, total = 10), totalProgression = 0.1f)
        val c = Locator(LocatorStrategy.Page(index = 3, total = 10), totalProgression = 0.3f)

        val state = readerBookmarkStateFor(
            entities = listOf(
                bookmarkEntity("c", c, totalProgression = 0.3f, createdAt = 30L),
                bookmarkEntity(
                    id = "malformed",
                    locator = a,
                    totalProgression = 0.05f,
                    createdAt = 5L,
                    locatorJson = "{not-json",
                ),
                bookmarkEntity("deleted", b, totalProgression = 0.2f, createdAt = 20L, isDeleted = true),
                bookmarkEntity("b-later", b, totalProgression = 0.1f, createdAt = 20L),
                bookmarkEntity("a-earlier", a, totalProgression = 0.1f, createdAt = 10L),
            ),
            currentLocator = null,
        )

        assertEquals(listOf("a-earlier", "b-later", "c"), state.items.map { it.id })
        assertNull(state.currentBookmarkId)
        assertFalse(state.items.any { it.id == "malformed" || it.id == "deleted" })
    }

    @Test
    fun `builds bookmark entity from current locator and device id`() {
        val locator = Locator(
            strategy = LocatorStrategy.Section(spineIndex = 1, elementIndex = 12, charOffset = 40),
            totalProgression = 0.42f,
        )

        val entity = readerBookmarkEntityFor(
            bookId = "book-1",
            locator = locator,
            deviceId = "device-uuid",
            now = 1234L,
            id = "bookmark-id",
        )

        assertEquals("bookmark-id", entity.id)
        assertEquals("book-1", entity.bookId)
        assertEquals(0.42f, entity.totalProgression)
        assertEquals("device-uuid", entity.deviceId)
        assertEquals(1234L, entity.createdAt)
        assertEquals(1234L, entity.updatedAt)
        assertEquals("书签 42%", entity.text)
        assertEquals(locator, Json.decodeFromString<Locator>(entity.locatorJson))
    }

    private fun bookmarkEntity(
        id: String,
        locator: Locator,
        totalProgression: Float,
        createdAt: Long = 0L,
        isDeleted: Boolean = false,
        locatorJson: String = Json.encodeToString(locator),
    ): BookmarkEntity =
        BookmarkEntity(
            id = id,
            bookId = "book-1",
            totalProgression = totalProgression,
            locatorJson = locatorJson,
            text = "old text",
            createdAt = createdAt,
            updatedAt = createdAt,
            deviceId = "device",
            isDeleted = isDeleted,
        )
}
