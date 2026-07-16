package dev.readflow.features.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source/UI contract for the compact Settings EPUB font-replacement surface:
 * section chrome, add/remove entry points, and Android 48dp minimum interactive sizing.
 */
class EpubFontReplacementSettingsUiContractTest {

    @Test
    fun `settings screen exposes compact epub font replacement controls`() {
        val screen = settingsScreenSource()
        assertTrue(
            "Reading category must expose the EPUB 字体替换 section",
            screen.contains("EPUB 字体替换") &&
                screen.contains("epubFontReplacements") &&
                screen.contains("添加替换") &&
                screen.contains("setEpubFontReplacement") &&
                screen.contains("removeEpubFontReplacement"),
        )
        assertTrue(
            "Delete control must expose a TalkBack label for the mapped family",
            screen.contains("Icons.Outlined.Delete") &&
                screen.contains("contentDescription") &&
                screen.contains("字体替换") &&
                screen.contains("\$family"),
        )
    }

    @Test
    fun `add and remove controls keep android 48dp minimum interactive size`() {
        val screen = settingsScreenSource()
        val replacementBlock = screen
            .substringAfter("EPUB 字体替换")
            .substringBefore("TXT 编码")

        // Compact list rows + icon delete + outline add must all meet 48dp.
        assertTrue(
            "Replacement rows must declare min 48dp height",
            replacementBlock.contains("heightIn(min = 48.dp)"),
        )
        assertTrue(
            "Remove IconButton must be explicitly 48dp",
            replacementBlock.contains("removeEpubFontReplacement") &&
                replacementBlock.contains("IconButton") &&
                replacementBlock.contains("size(48.dp)"),
        )
        assertTrue(
            "Add-replacement button must keep 48dp min height",
            replacementBlock.contains("添加替换") &&
                replacementBlock.contains("heightIn(min = 48.dp)"),
        )
        // Dialog save/cancel/chips also stay tappable.
        assertTrue(
            "Dialog interactive targets must declare 48dp min height",
            replacementBlock.contains("保存") &&
                replacementBlock.contains("取消") &&
                replacementBlock.contains("FilterChip") &&
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
