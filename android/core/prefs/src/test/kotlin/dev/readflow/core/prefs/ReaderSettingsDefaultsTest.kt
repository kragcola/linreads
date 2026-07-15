package dev.readflow.core.prefs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderSettingsDefaultsTest {

    @Test
    fun `missing line spacing uses Moon fresh install default`() {
        assertEquals(1.0f, resolvedLineSpacingPreference(null), 0.001f)
    }

    @Test
    fun `persisted line spacing is never replaced by the new default`() {
        assertEquals(1.7f, resolvedLineSpacingPreference(1.7f), 0.001f)
    }
}
