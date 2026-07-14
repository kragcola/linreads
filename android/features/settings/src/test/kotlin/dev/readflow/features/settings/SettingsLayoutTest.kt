package dev.readflow.features.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLayoutTest {
    @Test
    fun usesSingleColumnBelowTabletBreakpoint() {
        assertEquals(SettingsLayoutMode.SINGLE_COLUMN, settingsLayoutMode(779f))
    }

    @Test
    fun usesTwoColumnsAtTabletBreakpoint() {
        assertEquals(SettingsLayoutMode.TWO_COLUMNS, settingsLayoutMode(780f))
    }

    @Test
    fun formatsDevelopmentBuildNumberForCompactDisplay() {
        assertEquals("构建 #123", "dev-123-feature".displayBuildLabel())
    }

    @Test
    fun leavesMissingBuildTagBlank() {
        assertEquals("", "".displayBuildLabel())
    }
}
