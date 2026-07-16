package dev.readflow.features.settings

import dev.readflow.core.model.ReaderCommandId
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderMenuEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral contracts for the settings-side reader-menu selection surface:
 * label mapping, visibility mutation, order editing, and default recovery.
 */
class ReaderMenuSettingsUiContractTest {

    @Test
    fun `command labels match the reader bottom bar catalog language`() {
        assertEquals("目录", readerMenuCommandLabel(ReaderCommandId.TOC))
        assertEquals("搜索", readerMenuCommandLabel(ReaderCommandId.SEARCH))
        assertEquals("书签", readerMenuCommandLabel(ReaderCommandId.BOOKMARKS))
        assertEquals("标注", readerMenuCommandLabel(ReaderCommandId.ANNOTATIONS))
        assertEquals("排版", readerMenuCommandLabel(ReaderCommandId.FONT))
        assertEquals("主题", readerMenuCommandLabel(ReaderCommandId.THEME))
    }

    @Test
    fun `toggling visibility preserves order and resolves missing catalog ids`() {
        val hiddenSearch = ReaderMenuConfig.resolve(
            ReaderMenuConfig(
                entries = listOf(
                    ReaderMenuEntry(ReaderCommandId.SEARCH, visible = false),
                    ReaderMenuEntry(ReaderCommandId.TOC, visible = true),
                ),
            ),
        )
        assertEquals(ReaderCommandId.SEARCH, hiddenSearch.entries.first().id)
        assertFalse(hiddenSearch.entries.first { it.id == ReaderCommandId.SEARCH }.visible)
        assertTrue(hiddenSearch.entries.first { it.id == ReaderCommandId.TOC }.visible)
        // All catalog IDs remain present after resolve (migration / defaults).
        assertEquals(
            ReaderCommandId.entries.toSet(),
            hiddenSearch.entries.map { it.id }.toSet(),
        )
    }

    @Test
    fun `settings screen source exposes compact entry and editor with reorder controls`() {
        val screen = settingsScreenSource()
        val vm = settingsViewModelSource()
        assertTrue(
            "Settings reading category must expose compact 阅读菜单命令 entry",
            screen.contains("阅读菜单命令") &&
                screen.contains("showReaderMenuEditor") &&
                screen.contains("ReaderMenuConfigEditorDialog"),
        )
        assertFalse(
            "Compact entry must not keep instructional prose bloating Settings",
            screen.contains("选择底部菜单中显示的命令"),
        )
        assertTrue(
            "Editor rows must keep 48dp targets, switch toggle, up/down arrows, and reset",
            screen.contains("heightIn(min = 48.dp)") &&
                screen.contains(".toggleable(") &&
                screen.contains("onCheckedChange = null") &&
                screen.contains("KeyboardArrowUp") &&
                screen.contains("KeyboardArrowDown") &&
                screen.contains("上移") &&
                screen.contains("下移") &&
                screen.contains("恢复默认") &&
                screen.contains("readerMenuConfig"),
        )
        assertTrue(
            "ViewModel must persist visibility, move, and reset under mutex",
            vm.contains("fun setReaderMenuCommandVisible") &&
                vm.contains("fun moveReaderMenuCommandUp") &&
                vm.contains("fun moveReaderMenuCommandDown") &&
                vm.contains("fun resetReaderMenuConfig") &&
                vm.contains("settings.setReaderMenuConfig") &&
                vm.contains("readerMenuConfigMutex") &&
                vm.contains("readerMenuConfig"),
        )
    }

    private fun settingsScreenSource(): String {
        val workingDir = java.io.File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            java.io.File(workingDir, "src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt"),
            java.io.File(workingDir, "features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt"),
            java.io.File(workingDir, "android/features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsScreen.kt"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("SettingsScreen.kt not found from ${workingDir.absolutePath}")
    }

    private fun settingsViewModelSource(): String {
        val workingDir = java.io.File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            java.io.File(workingDir, "src/main/kotlin/dev/readflow/features/settings/SettingsViewModel.kt"),
            java.io.File(workingDir, "features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsViewModel.kt"),
            java.io.File(workingDir, "android/features/settings/src/main/kotlin/dev/readflow/features/settings/SettingsViewModel.kt"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("SettingsViewModel.kt not found from ${workingDir.absolutePath}")
    }
}
