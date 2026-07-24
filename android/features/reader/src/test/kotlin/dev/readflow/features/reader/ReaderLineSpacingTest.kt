package dev.readflow.features.reader

import dev.readflow.core.prefs.ReaderTypography
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderLineSpacingTest {

    @Test
    fun `line spacing is clamped to readable range`() {
        // Keep the recommended 1.6 default while allowing intentionally dense page layouts.
        assertEquals(ReaderTypography.MIN_LINE_SPACING, clampedReaderLineSpacing(0f), 0.001f)
        assertEquals(ReaderTypography.MAX_LINE_SPACING, clampedReaderLineSpacing(3.5f), 0.001f)
        assertEquals(0.1f, ReaderTypography.MIN_LINE_SPACING, 0.001f)
        assertEquals(3.0f, ReaderTypography.MAX_LINE_SPACING, 0.001f)
        assertEquals(28, ReaderTypography.LINE_SPACING_SLIDER_STEPS)
    }

    @Test
    fun `valid line spacing is kept`() {
        assertEquals(0.1f, clampedReaderLineSpacing(0.1f), 0.001f)
        assertEquals(1.6f, clampedReaderLineSpacing(1.6f), 0.001f)
        assertEquals(1.9f, clampedReaderLineSpacing(1.9f), 0.001f)
    }

    @Test
    fun `invalid line spacing falls back to default reading rhythm`() {
        assertEquals(ReaderTypography.DEFAULT_LINE_SPACING, clampedReaderLineSpacing(Float.NaN), 0.001f)
        assertEquals(
            ReaderTypography.DEFAULT_LINE_SPACING,
            clampedReaderLineSpacing(Float.POSITIVE_INFINITY),
            0.001f,
        )
    }
}
