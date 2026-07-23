package dev.readflow.features.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScreenVisualContractTest {

    @Test
    fun `library masthead stays compact tactile and accessible`() {
        val source = libraryScreenSource()

        assertFalse(
            "the compact masthead must not use display-scale typography",
            source.contains("style = ReadflowType.display"),
        )
        assertTrue(
            "the masthead must use a restrained title style",
            source.contains("style = ReadflowType.title"),
        )
        assertTrue(
            "the masthead must add low-cost ruled paper texture",
            source.contains("drawLine("),
        )
        assertFalse(
            "the masthead texture must not use gradients",
            source.contains("Gradient"),
        )
        assertTrue(
            "all three masthead controls must keep the 48dp touch target",
            source.split("size(Dimens.touchTarget)").size - 1 >= 3,
        )
    }

    @Test
    fun `online library uses dedicated preview and structured source editor`() {
        val source = libraryScreenSource()
        val sheet = source.substringAfter("private fun OnlineLibrarySheet(")
            .substringBefore("private fun OnlineCatalogResultRow(")

        assertTrue(
            "the tall online-library sheet must scroll vertically",
            sheet.contains(".verticalScroll(rememberScrollState())"),
        )
        assertTrue(
            "the source picker must scroll horizontally when many sources are configured",
            sheet.contains(".horizontalScroll(rememberScrollState())"),
        )
        assertFalse(
            "online preview must stay inside LinReads instead of launching an external browser",
            source.contains("Intent.ACTION_VIEW"),
        )
        assertTrue(
            "application-owned text preview must use a separate dismissible full-window surface",
            source.contains("private fun OnlineBookPreviewWindow(") &&
                source.contains("usePlatformDefaultWidth = false") &&
                source.contains("退出正文预览"),
        )
        assertTrue(
            "download actions must remain gated by adapter capability",
            sheet.contains("canDownload = selected?.capabilities?.canDownload == true"),
        )
        assertTrue(
            "source configuration must use a separate structured editor",
            source.contains("private fun SourceEditorWindow(") &&
                source.contains("搜索地址模板") &&
                source.contains("正文选择器"),
        )
        assertFalse(
            "users must never edit raw HTML-rule JSON in the UI",
            source.contains("HTML 规则 JSON"),
        )
        assertFalse(
            "the empty shelf must not keep Calibre as the primary online-library action",
            source.contains("onConnectCalibre = onSettings"),
        )
        assertTrue(
            "the empty shelf must open the generic online library directly",
            source.contains("onOpenOnlineLibrary = { showOnlineLibrary = true }"),
        )
        assertTrue(
            "the source editor must close only after the registry accepts the source",
            source.contains("viewModel.addOnlineSource { showSourceEditor = false }"),
        )
        assertFalse(
            "saving must not hide validation errors by closing the editor immediately",
            source.contains("viewModel.addOnlineSource()\n                    showSourceEditor = false"),
        )
        assertTrue(
            "online-library errors must expose a stable accessible semantic label",
            source.contains("在线书库错误：\$statusError"),
        )
        val resultRow = source.substringAfter("private fun OnlineCatalogResultRow(")
        assertTrue(
            "catalog actions must wrap instead of overflowing narrow screens",
            resultRow.contains("FlowRow("),
        )
        assertTrue(
            "catalog multi-select must use a compact binary control",
            resultRow.contains("Checkbox("),
        )
        assertFalse(
            "catalog selection must not consume row width with a text button",
            resultRow.contains("OutlinedButton(onClick = onToggleSelect)"),
        )
    }

    private fun libraryScreenSource(): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/dev/readflow/features/library/LibraryScreen.kt"),
            File(workingDir, "features/library/src/main/kotlin/dev/readflow/features/library/LibraryScreen.kt"),
            File(workingDir, "android/features/library/src/main/kotlin/dev/readflow/features/library/LibraryScreen.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("LibraryScreen.kt not found from ${workingDir.absolutePath}")
    }
}
