package dev.readflow.features.reader

import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
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
    ): BookmarkEntity =
        BookmarkEntity(
            id = id,
            bookId = "book-1",
            totalProgression = totalProgression,
            locatorJson = Json.encodeToString(locator),
            text = "old text",
            createdAt = createdAt,
            updatedAt = createdAt,
            deviceId = "device",
            isDeleted = isDeleted,
        )
}
