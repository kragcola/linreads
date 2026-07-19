package dev.readflow.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocatorPageTextTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `PageText JSON round trip preserves index total and charOffset`() {
        val original = Locator(
            strategy = LocatorStrategy.PageText(index = 4, total = 20, charOffset = 128),
            progression = 0.2f,
            totalProgression = 0.2f,
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Locator>(encoded)
        assertEquals(original, decoded)
        val pageText = decoded.strategy as LocatorStrategy.PageText
        assertEquals(4, pageText.index)
        assertEquals(20, pageText.total)
        assertEquals(128, pageText.charOffset)
    }

    @Test
    fun `legacy bare Page JSON still decodes and equals unchanged`() {
        val expected = Locator(
            strategy = LocatorStrategy.Page(index = 7, total = 42),
            progression = 0.16f,
            totalProgression = 0.16f,
        )
        // Encode first so the classDiscriminator matches kotlinx.serialization's sealed format.
        val encoded = json.encodeToString(expected)
        val decoded = json.decodeFromString<Locator>(encoded)
        assertEquals(expected, decoded)
        // Hard-coded payload fields (index/total) remain stable; subtype discriminator may be FQCN.
        assert(encoded.contains("\"index\":7")) { encoded }
        assert(encoded.contains("\"total\":42")) { encoded }
        assertEquals(expected, json.decodeFromString<Locator>(json.encodeToString(expected)))
    }

    @Test
    fun `Page and PageText with same index are not equal`() {
        val page = LocatorStrategy.Page(index = 3, total = 10)
        val pageText = LocatorStrategy.PageText(index = 3, total = 10, charOffset = 0)
        assertNotEquals(page, pageText)
        assertEquals(3, fixedPageIndex(page))
        assertEquals(3, fixedPageIndex(pageText))
    }

    @Test
    fun `fixedPageIndex returns index for Page and PageText only`() {
        assertEquals(5, fixedPageIndex(LocatorStrategy.Page(5, 100)))
        assertEquals(5, fixedPageIndex(LocatorStrategy.PageText(5, 100, charOffset = 9)))
        assertEquals(
            5,
            fixedPageIndex(Locator(LocatorStrategy.PageText(5, 100, 9), totalProgression = 0.05f)),
        )
        assertNull(fixedPageIndex(LocatorStrategy.Section(0, 5, 0)))
        assertNull(fixedPageIndex(LocatorStrategy.ByteOffset(100L, 10)))
        assertNull(fixedPageIndex(LocatorStrategy.Unknown))
        assertNull(fixedPageIndex(Locator(LocatorStrategy.Unknown, totalProgression = 0.5f)))
    }
}
