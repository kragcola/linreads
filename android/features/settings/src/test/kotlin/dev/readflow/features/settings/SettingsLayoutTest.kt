package dev.readflow.features.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsLayoutTest {

    @Test
    fun `online sources are managed in the library instead of a calibre-only settings form`() {
        val source = settingsScreenSource()
        assertFalse(source.contains("title = \"Calibre 服务器\""))
        assertFalse(source.contains("label = { Text(\"Calibre 服务器地址\") }"))
    }

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

    @Test
    fun ordersSettingsByDailyUseBeforeMaintenance() {
        assertEquals(
            listOf(
                SettingsCategory.READING,
                SettingsCategory.SOURCE,
                SettingsCategory.DATA,
                SettingsCategory.ABOUT,
            ),
            settingsCategoryOrder(),
        )
    }

    private fun settingsScreenSource(): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt"),
            File(workingDir, "features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt"),
            File(workingDir, "android/features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("SettingsScreen.kt not found from ${workingDir.absolutePath}")
    }
}
