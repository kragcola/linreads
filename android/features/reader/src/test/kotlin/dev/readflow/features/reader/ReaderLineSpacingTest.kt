package dev.readflow.features.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderLineSpacingTest {

    @Test
    fun `line spacing is clamped to readable range`() {
        assertEquals(1.4f, clampedReaderLineSpacing(0.8f), 0.001f)
        assertEquals(2.2f, clampedReaderLineSpacing(3f), 0.001f)
    }

    @Test
    fun `valid line spacing is kept`() {
        assertEquals(1.6f, clampedReaderLineSpacing(1.6f), 0.001f)
        assertEquals(1.9f, clampedReaderLineSpacing(1.9f), 0.001f)
    }

    @Test
    fun `invalid line spacing falls back to default reading rhythm`() {
        assertEquals(1.75f, clampedReaderLineSpacing(Float.NaN), 0.001f)
        assertEquals(1.75f, clampedReaderLineSpacing(Float.POSITIVE_INFINITY), 0.001f)
    }
}
