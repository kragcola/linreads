package dev.readflow.features.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visual contract: reader paper continues behind the top status bar.
 *
 * Document host (AndroidView / ReaderTapContainer paper) must not be inset away from
 * statusBars/systemBars by itself. Top chrome may still pad for status-bar safety.
 * Readable content safety lives on the document child (margins), not paper padding.
 *
 * Pattern matches [LibraryScreenVisualContractTest]: source-level contract when the module
 * has no Compose UI test host for layout assertions.
 */
class ReaderScreenPaperInsetsVisualContractTest {

    @Test
    fun `document host paper is not inset from status or system bars while top chrome stays status-bar safe`() {
        val source = readerScreenSource()

        val androidViewBlock = androidViewModifierChain(source)
        assertTrue(
            "ReaderScreen must host the loaded document through AndroidView",
            androidViewBlock.isNotEmpty(),
        )
        assertFalse(
            "document host paper must not use windowInsetsPadding(WindowInsets.systemBars); " +
                "paper continues behind the status bar while chrome remains inset-safe. " +
                "androidViewModifiers=$androidViewBlock",
            androidViewBlock.contains("windowInsetsPadding(WindowInsets.systemBars)"),
        )
        assertFalse(
            "document host paper must not use windowInsetsPadding(WindowInsets.statusBars); " +
                "status-bar safe spacing belongs on chrome, not the paper surface. " +
                "androidViewModifiers=$androidViewBlock",
            androidViewBlock.contains("windowInsetsPadding(WindowInsets.statusBars)"),
        )

        val topChromeSurface = topChromeSurfaceModifier(source)
        assertTrue(
            "top floating chrome must remain status-bar safe",
            topChromeSurface.contains("windowInsetsPadding(WindowInsets.statusBars)"),
        )

        val tapContainerBlock = readerTapContainerBlock(source)
        assertTrue(
            "ReaderTapContainer must listen for system-bar / cutout insets",
            tapContainerBlock.contains("ViewCompat.setOnApplyWindowInsetsListener(this)") &&
                tapContainerBlock.contains("WindowInsetsCompat.Type.systemBars()") &&
                tapContainerBlock.contains("WindowInsetsCompat.Type.displayCutout()"),
        )
        assertFalse(
            "document inset application must not consume bars needed by Compose chrome",
            tapContainerBlock.contains("WindowInsetsCompat.CONSUMED"),
        )
        assertFalse(
            "paper owner must not pad itself with system-bar top inset " +
                "(that pushes engine paper below the status bar)",
            tapContainerBlock.contains("view.setPadding(\n                    safeInsets.left"),
        )
        assertFalse(
            "paper owner must not assign safeInsets.top as its own paddingTop",
            tapContainerBlock.contains("safeInsets.top") &&
                tapContainerBlock.contains("view.setPadding(") &&
                !tapContainerBlock.contains("view.setPadding(0, 0, 0, 0)"),
        )
        assertTrue(
            "paper owner keeps zero padding so paper full-bleeds under status icons",
            tapContainerBlock.contains("view.setPadding(0, 0, 0, 0)"),
        )
        assertTrue(
            "content-layer safety must margin the document child, not the paper FrameLayout",
            tapContainerBlock.contains("applyDocumentContentInsets") &&
                tapContainerBlock.contains("lp.setMargins(left, top, right, bottom)"),
        )
        assertTrue(
            "the full-screen fallback must use the reader paper palette instead of Material background",
            source.contains("readerPaletteFor(state.themeMode, systemNight).paper") &&
                source.contains(".background(readerPaperColor)"),
        )
        assertTrue(
            "reader system-bar icon contrast must be palette-driven and restore prior window flags",
            source.contains("WindowCompat.getInsetsController") &&
                source.contains("isAppearanceLightStatusBars") &&
                source.contains("isAppearanceLightNavigationBars") &&
                source.contains("previousStatusBarAppearance") &&
                source.contains("previousNavigationBarAppearance") &&
                source.contains("onDispose"),
        )
        assertTrue(
            "reader-scoped status/navigation bar backgrounds must go transparent while icons stay visible",
            source.contains("statusBarColor") &&
                source.contains("navigationBarColor") &&
                source.contains("Color.TRANSPARENT") &&
                source.contains("previousStatusBarColor") &&
                source.contains("previousNavigationBarColor"),
        )
        assertTrue(
            "navigation-bar contrast enforcement must be disabled while reading and restored on leave",
            source.contains("isNavigationBarContrastEnforced") &&
                source.contains("previousNavigationBarContrastEnforced"),
        )
        // Restore path must re-apply prior colors/contrast/icons together on dispose.
        val appearanceBlock = readerSystemBarAppearanceBlock(source)
        assertTrue(
            "onDispose must restore prior statusBarColor",
            appearanceBlock.contains("statusBarColor = previousStatusBarColor") ||
                appearanceBlock.contains("window.statusBarColor = previousStatusBarColor"),
        )
        assertTrue(
            "onDispose must restore prior navigationBarColor",
            appearanceBlock.contains("navigationBarColor = previousNavigationBarColor") ||
                appearanceBlock.contains("window.navigationBarColor = previousNavigationBarColor"),
        )
        assertTrue(
            "onDispose must restore prior navigation-bar contrast enforcement",
            appearanceBlock.contains("isNavigationBarContrastEnforced = previousNavigationBarContrastEnforced"),
        )
    }

    private fun readerSystemBarAppearanceBlock(source: String): String {
        val marker = source.indexOf("private fun ReaderSystemBarAppearance")
        require(marker >= 0) { "ReaderSystemBarAppearance not found in ReaderScreen.kt" }
        // Body through the following private helper / ReaderScreen entry.
        val end = source.indexOf("@OptIn(ExperimentalMaterial3Api::class)", marker)
            .takeIf { it > marker }
            ?: source.indexOf("fun ReaderScreen(", marker).takeIf { it > marker }
            ?: (marker + 2500).coerceAtMost(source.length)
        return source.substring(marker, end)
    }

    private fun androidViewModifierChain(source: String): String {
        val androidViewIdx = source.indexOf("AndroidView(")
        require(androidViewIdx >= 0) { "AndroidView( not found in ReaderScreen.kt" }
        val modifierIdx = source.indexOf("modifier = Modifier", androidViewIdx)
        require(modifierIdx >= 0) { "AndroidView modifier = Modifier not found" }
        val factoryIdx = source.indexOf("factory =", modifierIdx)
        require(factoryIdx > modifierIdx) { "AndroidView factory = not found after modifier" }
        return source.substring(modifierIdx, factoryIdx)
    }

    private fun topChromeSurfaceModifier(source: String): String {
        // Compact floating top chrome: AnimatedVisibility(TopCenter) then Surface with statusBars pad.
        val chromeMarker = source.indexOf("// ── Compact floating top chrome")
        require(chromeMarker >= 0) { "top chrome marker not found in ReaderScreen.kt" }
        val surfaceIdx = source.indexOf("Surface(", chromeMarker)
        require(surfaceIdx >= 0) { "top chrome Surface( not found" }
        val modifierIdx = source.indexOf("modifier = Modifier", surfaceIdx)
        require(modifierIdx >= 0) { "top chrome Surface modifier not found" }
        val shapeIdx = source.indexOf("shape =", modifierIdx)
        require(shapeIdx > modifierIdx) { "top chrome Surface shape = not found after modifier" }
        return source.substring(modifierIdx, shapeIdx)
    }

    private fun readerTapContainerBlock(source: String): String {
        val classIdx = source.indexOf("private class ReaderTapContainer")
        require(classIdx >= 0) { "ReaderTapContainer class not found in ReaderScreen.kt" }
        // Bound the block to the class body start through a generous window covering init + helpers.
        return source.substring(classIdx)
    }

    private fun readerScreenSource(): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
            File(workingDir, "features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
            File(workingDir, "android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("ReaderScreen.kt not found from ${workingDir.absolutePath}")
    }
}
