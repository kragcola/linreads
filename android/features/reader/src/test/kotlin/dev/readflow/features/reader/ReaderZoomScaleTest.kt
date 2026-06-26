package dev.readflow.features.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderZoomScaleTest {

    @Test
    fun `pinch out increases zoom proportionally`() {
        assertEquals(1.5f, scaledReaderZoom(startScale = 1f, scaleFactor = 1.5f), 0.001f)
    }

    @Test
    fun `pinch in never goes below fit scale`() {
        assertEquals(1f, scaledReaderZoom(startScale = 2f, scaleFactor = 0.2f), 0.001f)
    }

    @Test
    fun `zoom is capped to avoid runaway bitmap scaling`() {
        assertEquals(4f, scaledReaderZoom(startScale = 2f, scaleFactor = 10f), 0.001f)
    }

    @Test
    fun `invalid scale keeps current zoom`() {
        assertEquals(2f, scaledReaderZoom(startScale = 2f, scaleFactor = 0f), 0.001f)
        assertEquals(2f, scaledReaderZoom(startScale = 2f, scaleFactor = Float.NaN), 0.001f)
    }
}
