package dev.readflow.features.reader

import dev.readflow.core.prefs.ReaderTypography
import dev.readflow.core.model.ReaderState
import dev.readflow.core.model.ThemeProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderDefaultTypographyTest {

    @Test
    fun `reader ui state uses the shared eighteen sp default`() {
        assertEquals(ReaderTypography.DEFAULT_FONT_SP.toFloat(), ReaderUiState().fontSizeSp, 0.001f)
    }

    @Test
    fun `reader ui state uses the shared Moon fresh install line spacing default`() {
        assertEquals(1.3f, ReaderTypography.DEFAULT_LINE_SPACING, 0.001f)
        assertEquals(ReaderTypography.DEFAULT_LINE_SPACING, ReaderUiState().lineSpacing, 0.001f)
        assertEquals(ReaderTypography.DEFAULT_LINE_SPACING, ReaderState(bookId = "book").lineSpacing, 0.001f)
        assertEquals(ReaderTypography.DEFAULT_LINE_SPACING, ThemeProfile().lineSpacing, 0.001f)
    }
}
