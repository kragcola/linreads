package dev.readflow.features.reader

import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.ThemeProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class FontChoiceCompatibilityTest {

    @Test
    fun `legacy serif ids migrate to the canonical system serif choice`() {
        listOf(null, "system", "source_han", "system_serif").forEach { raw ->
            assertEquals(FontChoice.System, FontChoice.parse(raw))
        }
        assertEquals("system_serif", FontChoice.System.serialize())
        assertEquals("system_serif", FontChoice.SourceHan.serialize())
        assertEquals(
            "system_serif",
            ThemeProfile.validated(ThemeProfile(fontChoice = "source_han")).fontChoice,
        )
    }

    @Test
    fun `new system families and custom files round trip`() {
        assertEquals(FontChoice.SystemSans, FontChoice.parse("system_sans"))
        assertEquals("system_sans", FontChoice.SystemSans.serialize())
        assertEquals(FontChoice.SystemMonospace, FontChoice.parse("system_monospace"))
        assertEquals("system_monospace", FontChoice.SystemMonospace.serialize())
        assertEquals(FontChoice.Custom("Novel.otf"), FontChoice.parse("custom:Novel.otf"))
    }
}
