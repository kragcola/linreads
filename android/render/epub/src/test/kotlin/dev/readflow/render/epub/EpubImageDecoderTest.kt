package dev.readflow.render.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubImageDecoderTest {

    @Test
    fun `motion quality caps both decode side and quadratic pixel work`() {
        val display = epubImageDecodeBudget(
            targetWidth = 1600,
            targetHeight = 2400,
            quality = EpubImageRenderQuality.DISPLAY,
        )
        val motion = epubImageDecodeBudget(
            targetWidth = 1600,
            targetHeight = 2400,
            quality = EpubImageRenderQuality.MOTION,
        )
        val rapid = epubImageDecodeBudget(
            targetWidth = 1600,
            targetHeight = 2400,
            quality = EpubImageRenderQuality.RAPID,
        )

        assertEquals(EpubImageDecodeBudget(maxSide = 2400, maxPixels = 3_840_000), display)
        assertEquals(EpubImageDecodeBudget(maxSide = 900, maxPixels = 2_160_000), motion)
        assertEquals(EpubImageDecodeBudget(maxSide = 600, maxPixels = 960_000), rapid)
    }

    @Test
    fun `quality policy reserves display pixels for the settled current page`() {
        val currentPage = listOf(100 until 200)

        assertEquals(
            EpubImageRenderQuality.MOTION,
            epubImageRenderQualityForOccurrence(150, currentPage, isCurrentChapter = true, visualMotionActive = false),
        )
        assertEquals(
            EpubImageRenderQuality.MOTION,
            epubImageRenderQualityForOccurrence(250, currentPage, isCurrentChapter = true, visualMotionActive = false),
        )
        assertEquals(
            EpubImageRenderQuality.RAPID,
            epubImageRenderQualityForOccurrence(150, currentPage, isCurrentChapter = true, visualMotionActive = true),
        )
        assertEquals(
            EpubImageRenderQuality.MOTION,
            epubImageRenderQualityForOccurrence(150, currentPage, isCurrentChapter = false, visualMotionActive = false),
        )
    }

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
