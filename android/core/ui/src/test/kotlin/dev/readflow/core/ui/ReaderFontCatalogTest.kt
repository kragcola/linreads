package dev.readflow.core.ui

import dev.readflow.core.model.FontChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderFontCatalogTest {

    @Test
    fun `catalog exposes three real system families before custom fonts`() {
        assertEquals(
            listOf(
                FontChoice.System,
                FontChoice.SystemSans,
                FontChoice.SystemMonospace,
                FontChoice.Custom("Novel.otf"),
            ),
            FontProvider.availableChoices(listOf("Novel.otf")),
        )
    }

    @Test
    fun `catalog labels describe actual system fonts and preserve custom names`() {
        assertEquals("系统衬线", FontProvider.label(FontChoice.System))
        assertEquals("系统无衬线", FontProvider.label(FontChoice.SystemSans))
        assertEquals("系统等宽", FontProvider.label(FontChoice.SystemMonospace))
        assertEquals("Novel.otf", FontProvider.label(FontChoice.Custom("Novel.otf")))
    }
}
