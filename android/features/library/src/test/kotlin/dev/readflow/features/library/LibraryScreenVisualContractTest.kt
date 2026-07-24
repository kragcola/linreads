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
        assertTrue(
            "a shelf load failure must not be rendered as an empty library",
            source.contains("state.error != null && state.items.isEmpty()") &&
                source.contains("加载失败：\${state.error}") &&
                source.contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    @Test
    fun `online library uses dedicated preview and structured source editor`() {
        val source = libraryScreenSource()
        val sheet = source.substringAfter("private fun OnlineLibrarySheet(")
            .substringBefore("private fun OnlineBookPreviewWindow(")
        val resultRow = source.substringAfter("private fun OnlineCatalogResultRow(")

        // --- Reject obsolete chrome ---
        assertFalse(
            "online library must not keep a horizontal source chip strip",
            sheet.contains("horizontalScroll(rememberScrollState())"),
        )
        assertFalse(
            "catalog result actions must not use a FlowRow action strip",
            resultRow.contains("FlowRow("),
        )
        assertFalse(
            "online preview must stay inside LinReads instead of launching an external browser",
            source.contains("Intent.ACTION_VIEW"),
        )
        assertFalse(
            "users must never edit raw HTML-rule JSON in the UI",
            source.contains("HTML 规则 JSON"),
        )
        assertFalse(
            "the empty shelf must not keep Calibre as the primary online-library action",
            source.contains("onConnectCalibre = onSettings"),
        )
        assertFalse(
            "saving must not hide validation errors by closing the editor immediately",
            source.contains("viewModel.addOnlineSource()\n                    showSourceEditor = false"),
        )

        // --- Single source selector ---
        assertTrue(
            "online library must expose exactly one ExposedDropdownMenuBox source selector",
            sheet.contains("ExposedDropdownMenuBox(") &&
                sheet.split("ExposedDropdownMenuBox(").size - 1 == 1,
        )
        assertTrue(
            "source selector must be labeled for assistive tech",
            sheet.contains("contentDescription = \"书源选择器\""),
        )
        assertFalse(
            "selected source name must not be repeated above the labeled selector",
            sheet.contains("selected?.let { Text(it.name, style = MaterialTheme.typography.titleSmall) }"),
        )

        // --- Compact source management ---
        assertTrue(
            "add, import, and delete must live in one 48dp source-management menu",
            sheet.contains("sourceActionsExpanded") &&
                sheet.contains("contentDescription = \"管理书源\"") &&
                sheet.contains("text = { Text(\"添加书源\") }") &&
                sheet.contains("text = { Text(\"导入 JSON\") }") &&
                sheet.contains("text = { Text(\"删除当前书源\") }") &&
                sheet.contains(".size(48.dp)"),
        )
        assertFalse(
            "source management must not consume the sheet footer with parallel action buttons",
            sheet.contains("modifier = Modifier.weight(1f)") &&
                sheet.contains("Text(\"添加书源\")") &&
                sheet.contains("Text(\"导入 JSON\")"),
        )

        // --- JSON document import ---
        assertTrue(
            "document picker must accept JSON source-config MIME types",
            source.contains("SOURCE_CONFIG_MIMES") &&
                source.contains("application/json") &&
                source.contains("ActivityResultContracts.OpenDocument()"),
        )
        assertTrue(
            "online sheet must offer a JSON import action wired to the document launcher",
            sheet.contains("onImportSourceConfig") &&
                sheet.contains("导入 JSON") &&
                sheet.contains("contentDescription = \"导入书源配置\""),
        )
        assertTrue(
            "picked config files must go through ViewModel import, never raw open",
            source.contains("viewModel.importSourceConfigFromUri(context, it)"),
        )

        // --- 48dp accessible trailing search icon ---
        assertTrue(
            "search field must host a trailing IconButton with 48dp touch target",
            sheet.contains("trailingIcon = {") &&
                sheet.contains(".size(48.dp)") &&
                sheet.contains("Icons.Default.Search") &&
                sheet.contains("contentDescription = \"搜索\""),
        )
        assertTrue(
            "the inner search icon must not duplicate the IconButton announcement",
            sheet.substringAfter("imageVector = Icons.Default.Search")
                .substringBefore(")")
                .contains("contentDescription = null"),
        )

        // --- Collapsible secondary filters ---
        assertTrue(
            "secondary filters must be collapsible behind an expand control",
            sheet.contains("filtersExpanded") &&
                sheet.contains("筛选条件") &&
                sheet.contains("收起筛选"),
        )
        assertTrue(
            "filter fields only render when expanded",
            sheet.contains("if (filtersExpanded)"),
        )

        // --- Checkbox selection + row overflow menu ---
        assertTrue(
            "catalog multi-select must use Checkbox",
            resultRow.contains("Checkbox("),
        )
        assertFalse(
            "catalog selection must not consume row width with a text toggle button",
            resultRow.contains("OutlinedButton(onClick = onToggleSelect)"),
        )
        assertTrue(
            "each result row must expose a 48dp overflow menu for secondary actions",
            resultRow.contains("overflowExpanded") &&
                resultRow.contains("Icons.Default.MoreVert") &&
                resultRow.contains("DropdownMenu(") &&
                resultRow.contains(".size(48.dp)"),
        )
        assertTrue(
            "single-book download must use a fixed 48dp icon control on narrow screens",
            resultRow.contains("Icons.Default.KeyboardArrowDown") &&
                resultRow.contains("contentDescription = downloadLabel") &&
                resultRow.contains(".size(48.dp)"),
        )
        assertFalse(
            "single-book download must not reserve row width for a text button",
            resultRow.contains("Text(if (isDownloading) \"下载中\" else \"下载\")"),
        )

        // --- Delete confirmation ---
        assertTrue(
            "source deletion must require AlertDialog confirmation",
            sheet.contains("AlertDialog(") &&
                sheet.contains("pendingDeleteSourceId") &&
                sheet.contains("删除书源") &&
                sheet.contains("此操作不可撤销"),
        )

        // --- Preview / editor / scroll / capability gates (kept from prior contract) ---
        assertTrue(
            "the tall online-library sheet must scroll vertically",
            sheet.contains(".verticalScroll(rememberScrollState())"),
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
        assertTrue(
            "source types must be informative vertical rows instead of another horizontal chip strip",
            source.contains("网页小说站") &&
                source.contains("自定义搜索、目录与正文规则") &&
                source.contains("开放目录与公共电子书库") &&
                source.contains("自托管 Calibre 内容服务器") &&
                !source.substringAfter("private fun SourceEditorWindow(")
                    .substringBefore("private fun HtmlSourceRuleFields(")
                    .contains("horizontalScroll(rememberScrollState())"),
        )
        assertTrue(
            "the empty shelf must open the generic online library directly",
            source.contains("onOpenOnlineLibrary = { showOnlineLibrary = true }"),
        )
        assertTrue(
            "the source editor must close only after the registry accepts the source",
            source.contains("viewModel.addOnlineSource { showSourceEditor = false }"),
        )
        assertTrue(
            "online-library errors must expose a stable accessible semantic label",
            source.contains("在线书库错误：\$statusError"),
        )
    }

    @Test
    fun `source editor keeps defaults documented behind advanced settings`() {
        val source = libraryScreenSource()
        val editor = source.substringAfter("private fun SourceEditorWindow(")
            .substringBefore("private data class SourceEditorOption(")
        val htmlFields = source.substringAfter("private fun HtmlSourceRuleFields(")
            .substringBefore("private fun SourceRuleField(")

        assertTrue(
            "the common path must explain that only the address is required",
            editor.contains("只需填写地址") && editor.contains("通用默认值"),
        )
        assertTrue(
            "source name must be optional because the selected type supplies a default",
            editor.contains("显示名称（可选）") && editor.contains("未填写时自动使用"),
        )
        assertTrue(
            "HTML selectors and transport details must stay collapsed initially",
            editor.contains("advancedSourceOptionsExpanded") &&
                editor.contains("高级设置") &&
                editor.contains("if (advancedSourceOptionsExpanded)"),
        )
        assertTrue(
            "every adjustable HTML rule must carry supporting guidance",
            htmlFields.contains("supportingText =") &&
                htmlFields.contains("默认：") &&
                htmlFields.contains("网站结构不同") &&
                htmlFields.contains("仅局域网 HTTP 书源需要开启"),
        )
    }

    @Test
    fun `online library empty states stay compact and immediately dismissible`() {
        val sheet = libraryScreenSource()
            .substringAfter("private fun OnlineLibrarySheet(")
            .substringBefore("private fun OnlineBookPreviewWindow(")

        assertFalse(
            "an empty catalog must not reserve a fixed 280dp result viewport",
            sheet.contains(".height(280.dp)"),
        )
        assertTrue(
            "the sheet title bar must expose an always-visible 48dp close action",
            sheet.contains("contentDescription = \"关闭在线书库\"") &&
                sheet.substringBefore("Text(\"书源\"")
                    .contains(".size(48.dp)"),
        )
        assertTrue(
            "a source-less catalog must show a compact primary action",
            sheet.contains("if (state.sources.isEmpty())") &&
                sheet.contains("Text(\"添加第一个书源\")"),
        )
        assertTrue(
            "the bounded result list must only exist when results are available",
            sheet.contains("if (state.results.isNotEmpty())") &&
                sheet.contains(".heightIn(max = 280.dp)"),
        )
        assertFalse(
            "dismiss must not be pushed below the catalog as a footer text button",
            sheet.contains("TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End))"),
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
