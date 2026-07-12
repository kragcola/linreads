package dev.readflow.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryGridLayoutTest {

    @Test
    fun `compact phones keep two readable book columns`() {
        assertEquals(2, libraryGridColumns(360f))
        assertEquals(2, libraryGridColumns(479f))
    }

    @Test
    fun `medium screens add columns without shrinking covers`() {
        assertEquals(3, libraryGridColumns(480f))
        assertEquals(3, libraryGridColumns(719f))
    }

    @Test
    fun `tablet shelf uses four generous columns`() {
        assertEquals(4, libraryGridColumns(720f))
        assertEquals(4, libraryGridColumns(999f))
    }

    @Test
    fun `expanded shelf caps density at five columns`() {
        assertEquals(5, libraryGridColumns(1_000f))
        assertEquals(5, libraryGridColumns(1_600f))
    }
}
