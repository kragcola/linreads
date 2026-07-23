package dev.readflow.features.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source/UI contract for the reader-first font workflow. Settings may explain or clear legacy
 * rules, but must never ask a reader to type an EPUB/CSS family name.
 */
class EpubFontReplacementSettingsUiContractTest {

    @Test
    fun `settings points readers to the in-book font menu without technical input`() {
        val screen = settingsScreenSource()
        assertTrue(
            "Reading category must point to the in-book font menu",
            screen.contains("书籍字体") &&
                screen.contains("epubFontReplacements") &&
                screen.contains("在阅读菜单中设置") &&
                screen.contains("字体与排版") &&
                screen.contains("clearEpubFontReplacements"),
        )
        assertTrue(
            "Settings must not expose the retired manual family-name form",
            !screen.contains("书中 CSS 字体名") &&
                !screen.contains("showFontReplacementDialog") &&
                !screen.contains("fontReplacementFamily"),
        )
        val importBlock = screen
            .substringAfter("val fontImportLauncher")
            .substringBefore("// Poll DownloadManager progress")
        assertTrue(
            "Settings font import must share the validated background importer",
            importBlock.contains("withContext(Dispatchers.IO)") &&
                importBlock.contains("FontProvider.importFont") &&
                !importBlock.contains("vm.importFont"),
        )
    }

    @Test
    fun `legacy reset control keeps android 48dp minimum interactive size`() {
        val screen = settingsScreenSource()
        val replacementBlock = screen
            .substringAfter("书籍字体")
            .substringBefore("TXT 编码")

        assertTrue(
            "Legacy reset button must remain accessible and explicit",
            replacementBlock.contains("清除旧版字体规则") &&
                replacementBlock.contains("clearEpubFontReplacements") &&
                replacementBlock.contains("heightIn(min = 48.dp)"),
        )
    }

    @Test
    fun `viewmodel writers serialize epub font replacement updates`() {
        val vm = settingsViewModelSource()
        assertTrue(
            "ViewModel must expose add/remove writers backed by repository persistence",
            vm.contains("fun setEpubFontReplacement") &&
                vm.contains("fun removeEpubFontReplacement") &&
                vm.contains("fun clearEpubFontReplacements") &&
                vm.contains("settings.setEpubFontReplacements") &&
                vm.contains("epubFontReplacements"),
        )
        assertTrue(
            "Rapid add/remove must share a mutex so read-modify-write cannot drop mappings",
            vm.contains("epubFontReplacementMutex") &&
                vm.contains("withLock"),
        )
        assertTrue(
            "Family names must be normalized before persist",
            vm.contains("normalizedEpubFontFamily"),
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
