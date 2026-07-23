package dev.readflow.core.ui

import dev.readflow.core.model.FontChoice
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `reader display names hide storage extensions and separators`() {
        assertEquals("系统衬线", FontProvider.displayName(FontChoice.System))
        assertEquals("Novel Serif", FontProvider.displayName(FontChoice.Custom("Novel_Serif.otf")))
        assertEquals("方正 楷体", FontProvider.displayName(FontChoice.Custom("方正-楷体.ttf")))
    }

    @Test
    fun `font import copy rejects content beyond its byte budget`() {
        val input = ByteArrayInputStream(ByteArray(5) { it.toByte() })
        val output = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            FontProvider.copyFontWithLimit(input, output, maxBytes = 4L)
        }
    }
}
