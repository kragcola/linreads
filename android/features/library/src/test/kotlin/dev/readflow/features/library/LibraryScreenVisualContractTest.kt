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
    fun `online library is a peer full page bookshelf instead of a search sheet`() {
        val source = libraryScreenSource()
        val page = onlineLibraryPageSource()
        val card = page.substringAfter("private fun OnlineCatalogBookCard(")

        assertTrue(
            "local and online libraries must be peer destinations in the library screen",
            source.contains("enum class LibraryPage") &&
                source.contains("PrimaryTabRow(") &&
                source.contains("Text(\"本地书架\"") &&
                source.contains("Text(\"在线书库\""),
        )
        assertFalse(
            "the online destination itself must not be presented as a modal bottom sheet",
            source.contains("private fun OnlineLibrarySheet("),
        )
        assertFalse(
            "online catalog must not be constrained to the old 280dp search-result viewport",
            page.contains("heightIn(max = 280.dp)"),
        )
        assertTrue(
            "the online destination must own the remaining page and render an adaptive shelf grid",
            page.contains("Modifier.fillMaxSize()") &&
                page.contains("LazyVerticalGrid(") &&
                page.contains("OnlineCatalogBookCard("),
        )
        assertTrue(
            "entering the online page must load its catalog without a search submission",
            source.contains("selectedPage == LibraryPage.ONLINE") &&
                source.contains("onlineLibraryState.results.isEmpty()") &&
                source.contains("onlineLibraryState.catalogRevision") &&
                source.contains("viewModel.searchOnlineLibrary()"),
        )
        assertTrue(
            "online books must reuse the established shelf cover presentation",
            card.contains("BookCover(") &&
                card.contains("aspectRatio(Dimens.coverAspectRatio)") &&
                card.contains("book.title") &&
                card.contains("book.author"),
        )
        assertTrue(
            "search and filtering must remain auxiliary collapsed tools",
            page.contains("searchExpanded") &&
                page.contains("filtersExpanded") &&
                page.contains("contentDescription = \"搜索在线书库\"") &&
                page.contains("contentDescription = \"筛选在线书库\""),
        )
        assertFalse(
            "the default online page must not instruct users to search before books appear",
            page.contains("输入书名或作者开始搜索") || page.contains("选择书源后即可搜索"),
        )
    }

    @Test
    fun `online library keeps source management downloads and accessible selection`() {
        val source = libraryScreenSource()
        val page = onlineLibraryPageSource()
        val card = page.substringAfter("private fun OnlineCatalogBookCard(")

        assertTrue(
            "online library must expose exactly one ExposedDropdownMenuBox source selector",
            page.contains("ExposedDropdownMenuBox(") &&
                page.split("ExposedDropdownMenuBox(").size - 1 == 1,
        )
        assertTrue(
            "source selector must be labeled for assistive tech",
            page.contains("contentDescription = \"书源选择器\""),
        )

        assertTrue(
            "add, import, and delete must live in one 48dp source-management menu",
            page.contains("sourceActionsExpanded") &&
                page.contains("contentDescription = \"管理书源\"") &&
                page.contains("text = { Text(\"添加书源\") }") &&
                page.contains("text = { Text(\"导入 JSON\") }") &&
                page.contains("text = { Text(\"删除当前书源\") }") &&
                page.contains(".size(Dimens.touchTarget)"),
        )

        assertTrue(
            "document picker must accept JSON source-config MIME types",
            source.contains("SOURCE_CONFIG_MIMES") &&
                source.contains("application/json") &&
                source.contains("ActivityResultContracts.OpenDocument()"),
        )
        assertTrue(
            "online page must offer a JSON import action wired to the document launcher",
            page.contains("onImportSourceConfig") && page.contains("导入 JSON"),
        )
        assertTrue(
            "picked config files must go through ViewModel import, never raw open",
            source.contains("viewModel.importSourceConfigFromUri(context, it)"),
        )

        assertTrue(
            "multi-select must be an explicit secondary mode with an accessible state",
            page.contains("selectionMode") &&
                page.contains("contentDescription = \"多选书籍\"") &&
                page.contains("stateDescription = if (selectionMode) \"已开启\" else \"已关闭\""),
        )
        assertTrue(
            "selection controls must remain 48dp and expose the selected state",
            card.contains("Checkbox(") &&
                card.contains(".size(Dimens.touchTarget)") &&
                card.contains(".toggleable(") &&
                card.contains("stateDescription = if (selected) \"已选择\" else \"未选择\"") &&
                card.contains("onCheckedChange = null"),
        )
        assertTrue(
            "metadata filters must scroll in a separate sheet instead of shrinking the bookshelf",
            page.contains("ModalBottomSheet(") &&
                page.contains("LazyColumn(") &&
                page.contains("Text(\"筛选书目\"") &&
                !page.substringBefore("OnlineLibraryStatus(state)").contains("OnlineMetadataFacetRow("),
        )
        assertTrue(
            "author and series batch actions must reveal the selection state immediately",
            page.contains("onAuthorBatch = { author ->") &&
                page.contains("onSeriesBatch = {") &&
                page.split("selectionMode = true").size - 1 >= 2,
        )
        assertTrue(
            "book metadata must reflow instead of clipping title and author in a fixed height",
            card.contains("heightIn(min = 64.dp)") && !card.contains("height(58.dp)"),
        )
        assertTrue(
            "download remains available from every downloadable book card",
            card.contains("contentDescription = downloadLabel") &&
                card.contains("enabled = downloadEnabled && !isDownloading"),
        )
        assertTrue(
            "batch download must remain available without replacing the bookshelf",
            page.contains("onDownloadSelected") &&
                page.contains("已选 ${'$'}{state.selectedEntryKeys.size} 本") &&
                page.contains("下载所选"),
        )

        assertTrue(
            "source deletion must require AlertDialog confirmation",
            page.contains("AlertDialog(") &&
                page.contains("pendingDeleteSourceId") &&
                page.contains("删除书源") &&
                page.contains("此操作不可撤销"),
        )
        assertTrue(
            "application-owned text preview must use a separate dismissible full-window surface",
            source.contains("private fun OnlineBookPreviewWindow(") &&
                source.contains("usePlatformDefaultWidth = false") &&
                source.contains("退出正文预览"),
        )
        assertTrue(
            "download actions must remain gated by adapter capability",
            page.contains("canDownload = selectedSource?.capabilities?.canDownload == true"),
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
            "the source editor must close only after the registry accepts the source",
            source.contains("viewModel.saveOnlineSource { showSourceEditor = false }"),
        )
        assertTrue(
            "online-library errors must expose a stable accessible semantic label",
            page.contains("在线书库错误："),
        )
        assertTrue(
            "batch download must expose and honor one stable in-progress state",
            page.contains("enabled = !state.isDownloadingBatch") &&
                page.contains("if (state.isDownloadingBatch) \"下载中\" else \"下载所选\""),
        )
        assertTrue(
            "single-download actions must remain visible but disabled during a batch",
            page.contains("downloadEnabled = !state.isDownloadingBatch") &&
                card.contains("enabled = downloadEnabled && !isDownloading"),
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
        assertTrue(
            "Calibre setup must cover same-Wi-Fi and Tailscale without requiring Serve",
            editor.contains("同一 Wi-Fi") && editor.contains("Tailscale") && editor.contains("无需 Serve"),
        )
        assertTrue(
            "credential read failure must offer retry separately from destructive reset",
            editor.contains("onRetryCredentials") &&
                editor.contains("重试读取") &&
                editor.contains("重置凭据"),
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

    private fun onlineLibraryPageSource(): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/dev/readflow/features/library/OnlineLibraryPage.kt"),
            File(workingDir, "features/library/src/main/kotlin/dev/readflow/features/library/OnlineLibraryPage.kt"),
            File(workingDir, "android/features/library/src/main/kotlin/dev/readflow/features/library/OnlineLibraryPage.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("OnlineLibraryPage.kt not found from ${workingDir.absolutePath}")
    }
}
