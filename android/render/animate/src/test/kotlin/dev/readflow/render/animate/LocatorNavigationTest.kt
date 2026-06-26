package dev.readflow.render.animate

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocatorNavigationTest {

    @Test
    fun `page locator moves by one page`() {
        val current = Locator(LocatorStrategy.Page(index = 2, total = 10))
        val next = moveLocatorBy(current, totalItems = 10, delta = 1)

        assertEquals(LocatorStrategy.Page(index = 3, total = 10), next?.strategy)
        assertEquals(0.3f, next?.totalProgression)
    }

    @Test
    fun `section locator moves by one element`() {
        val current = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 4, charOffset = 12))
        val previous = moveLocatorBy(current, totalItems = 10, delta = -1)

        assertEquals(LocatorStrategy.Section(spineIndex = 0, elementIndex = 3, charOffset = 0), previous?.strategy)
        assertEquals(0.3f, previous?.totalProgression)
    }

    @Test
    fun `movement clamps at document bounds`() {
        val first = Locator(LocatorStrategy.Page(index = 0, total = 4))
        val last = Locator(LocatorStrategy.Section(spineIndex = 0, elementIndex = 3, charOffset = 0))

        assertNull(moveLocatorBy(first, totalItems = 4, delta = -1))
        assertNull(moveLocatorBy(last, totalItems = 4, delta = 1))
    }

    @Test
    fun `byte offset and unknown locators are not guessed`() {
        assertNull(moveLocatorBy(Locator(LocatorStrategy.ByteOffset(128L, 64)), totalItems = 10, delta = 1))
        assertNull(moveLocatorBy(Locator(LocatorStrategy.Unknown), totalItems = 10, delta = 1))
    }

    @Test
    fun `page index can be restored from page or section locator`() {
        assertEquals(3, pageIndexFromLocator(Locator(LocatorStrategy.Page(index = 3, total = 10)), totalItems = 10))
        assertEquals(4, pageIndexFromLocator(Locator(LocatorStrategy.Section(0, 4, 20)), totalItems = 10))
    }

    @Test
    fun `page index falls back to total progression and clamps`() {
        assertEquals(5, pageIndexFromLocator(Locator(LocatorStrategy.Unknown, totalProgression = 0.5f), totalItems = 10))
        assertEquals(9, pageIndexFromLocator(Locator(LocatorStrategy.Page(index = 20, total = 10)), totalItems = 10))
    }
}
