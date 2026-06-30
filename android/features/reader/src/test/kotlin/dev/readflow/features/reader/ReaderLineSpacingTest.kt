package dev.readflow.features.reader

import dev.readflow.core.prefs.ReaderTypography
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderLineSpacingTest {

    @Test
    fun `line spacing is clamped to readable range`() {
        // Floor lowered to 1.0 to reach Moon+ 静读天下's 1.3 default (and tighter).
        assertEquals(ReaderTypography.MIN_LINE_SPACING, clampedReaderLineSpacing(0.5f), 0.001f)
        assertEquals(ReaderTypography.MAX_LINE_SPACING, clampedReaderLineSpacing(3f), 0.001f)
    }

    @Test
    fun `valid line spacing is kept`() {
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
