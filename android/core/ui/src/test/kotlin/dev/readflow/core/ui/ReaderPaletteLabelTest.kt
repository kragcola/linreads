package dev.readflow.core.ui

import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.readerThemeLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPaletteLabelTest {

    @Test
    fun `black preset keeps the existing automation label`() {
        assertEquals("纯黑", ThemeMode.BLACK.readerThemeLabel())
    }
}
