package dev.readflow.render.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfPanStateTest {

    @Test
    fun `zoomed page exposes both axes and clamps all four edges`() {
        val bounds = pdfPanBounds(
            contentWidth = 1_000f,
            contentHeight = 800f,
            fittedPageWidth = 600f,
            fittedPageHeight = 800f,
            zoomScale = 4f,
        )

        assertEquals(700f, bounds.maxX, 0.001f)
        assertEquals(1_200f, bounds.maxY, 0.001f)
        assertEquals(PdfPanOffset(700f, -1_200f), bounds.clamp(PdfPanOffset(9_000f, -9_000f)))
        assertTrue(bounds.canPanHorizontally(PdfPanOffset.Zero, direction = 1))
        assertTrue(bounds.canPanVertically(PdfPanOffset.Zero, direction = -1))
        assertFalse(bounds.canPanHorizontally(PdfPanOffset(700f, 0f), direction = 1))
        assertFalse(bounds.canPanVertically(PdfPanOffset(0f, -1_200f), direction = -1))
    }

    @Test
    fun `fit scale has no stale translation and rotation reclamps old pan`() {
        val zoomed = pdfPanBounds(800f, 1_200f, 800f, 1_000f, 3f)
        val oldPan = zoomed.clamp(PdfPanOffset(500f, -700f))
        val fit = pdfPanBounds(1_200f, 800f, 640f, 800f, 1f)

        assertEquals(PdfPanOffset.Zero, fit.clamp(oldPan))
        assertFalse(fit.canPanHorizontally(PdfPanOffset.Zero, direction = 1))
        assertFalse(fit.canPanVertically(PdfPanOffset.Zero, direction = 1))
    }
}
