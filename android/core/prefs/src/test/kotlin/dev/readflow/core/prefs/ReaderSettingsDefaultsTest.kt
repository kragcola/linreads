package dev.readflow.core.prefs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderSettingsDefaultsTest {

    @Test
    fun `missing line spacing uses accessible fresh install default`() {
        assertEquals(1.6f, resolvedLineSpacingPreference(null), 0.001f)
    }

    @Test
    fun `persisted line spacing is never replaced by the new default`() {
        assertEquals(1.7f, resolvedLineSpacingPreference(1.7f), 0.001f)
        assertEquals(1.3f, resolvedLineSpacingPreference(1.3f), 0.001f)
    }

    @Test
    fun `legacy typography is replaced once by the current baseline`() {
        assertEquals(
            TypographyBaseline(
                fontSize = 18,
                lineSpacing = 1.6f,
                version = ReaderTypography.BASELINE_VERSION,
            ),
            resolvedTypographyBaseline(
                storedFontSize = 24,
                storedLineSpacing = 2.0f,
                storedVersion = null,
            ),
        )
    }

    @Test
    fun `current typography baseline preserves later user choices`() {
        assertEquals(
            TypographyBaseline(
                fontSize = 21,
                lineSpacing = 1.6f,
                version = ReaderTypography.BASELINE_VERSION,
            ),
            resolvedTypographyBaseline(
                storedFontSize = 21,
                storedLineSpacing = 1.6f,
                storedVersion = ReaderTypography.BASELINE_VERSION,
            ),
        )
    }

    @Test
    fun `baseline version preserves explicit version-1 line spacing of 1_3 with non-default font`() {
        assertEquals(
            TypographyBaseline(
                fontSize = 24,
                lineSpacing = 1.3f,
                version = ReaderTypography.BASELINE_VERSION,
            ),
            resolvedTypographyBaseline(
                storedFontSize = 24,
                storedLineSpacing = 1.3f,
                storedVersion = ReaderTypography.BASELINE_VERSION,
            ),
        )
    }
}
