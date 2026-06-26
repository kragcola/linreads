package dev.readflow.features.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderFontScaleTest {

    @Test
    fun `pinch out increases font size proportionally`() {
        assertEquals(21.6f, scaledReaderFontSize(startSp = 18f, scaleFactor = 1.2f), 0.001f)
    }

    @Test
    fun `pinch in decreases font size proportionally`() {
        assertEquals(14.4f, scaledReaderFontSize(startSp = 18f, scaleFactor = 0.8f), 0.001f)
    }

    @Test
    fun `font size is clamped to accessibility range`() {
        assertEquals(12f, scaledReaderFontSize(startSp = 18f, scaleFactor = 0.1f), 0.001f)
        assertEquals(32f, scaledReaderFontSize(startSp = 18f, scaleFactor = 10f), 0.001f)
    }

    @Test
    fun `invalid scale keeps current font size`() {
        assertEquals(18f, scaledReaderFontSize(startSp = 18f, scaleFactor = 0f), 0.001f)
        assertEquals(18f, scaledReaderFontSize(startSp = 18f, scaleFactor = Float.NaN), 0.001f)
    }
}
