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
        assertTrue(
            "ReaderTapContainer must apply system-bar insets to its document content",
            source.contains("ViewCompat.setOnApplyWindowInsetsListener(this)") &&
                source.contains("WindowInsetsCompat.Type.systemBars()") &&
                source.contains("WindowInsetsCompat.Type.displayCutout()") &&
                source.contains("view.setPadding("),
        )
        assertFalse(
            "document inset application must not consume bars needed by Compose chrome",
            source.contains("WindowInsetsCompat.CONSUMED"),
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
