package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubImageDecoderTest {

    @Test
    fun `sample size keeps decoded image inside side and pixel budgets`() {
        assertEquals(
            1,
            epubImageSampleSize(width = 1200, height = 900, maxSide = 1600, maxPixels = 4_000_000),
        )
        assertEquals(
            2,
            epubImageSampleSize(width = 3200, height = 1200, maxSide = 1600, maxPixels = 4_000_000),
        )
        assertEquals(
            4,
            epubImageSampleSize(width = 5000, height = 5000, maxSide = 1600, maxPixels = 4_000_000),
        )
    }
}
