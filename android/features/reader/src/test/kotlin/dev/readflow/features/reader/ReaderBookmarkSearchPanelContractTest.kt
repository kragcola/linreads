package dev.readflow.features.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral source contracts for bookmark/search chrome: 48dp touch targets and Chinese
 * contentDescriptions, without a Compose instrumentation harness.
 */
class ReaderBookmarkSearchPanelContractTest {

    @Test
    fun `bookmark and search chrome keep 48dp targets and Chinese accessibility labels`() {
        val source = readerScreenSource()
        val bookmarkPanel = source
            .substringAfter("private fun BookmarkPanel(", missingDelimiterValue = "")
            .substringBefore("private fun AnnotationPanel(")
        val bookmarkDelete = bookmarkPanel
            .substringAfter("onClick = { onBookmarkRemove(bookmark) }", missingDelimiterValue = "")
            .substringBefore("Icons.Default.Clear")
        val searchPanel = source
            .substringAfter("private fun SearchPanel(", missingDelimiterValue = "")
            .substringBefore("private fun TocDrawer(")
        val searchLoading = searchPanel
            .substringAfter("searchState.isSearching", missingDelimiterValue = "")
            .substringBefore("searchState.message")
        val searchMessage = searchPanel
            .substringAfter("searchState.message", missingDelimiterValue = "")
            .substringBefore("if (searchState.results")

        assertTrue(
            "toolbar bookmark control must be >=48dp with Chinese add/remove labels",
            source.contains("onClick = { viewModel.onIntent(ReaderIntent.ToggleBookmark) }") &&
                source.contains("sizeIn(minWidth = 48.dp, minHeight = 48.dp)") &&
                source.contains("\"添加书签\"") &&
                source.contains("\"移除书签\"") &&
                source.contains("Icons.Default.Bookmark") &&
                source.contains("Icons.Default.BookmarkBorder"),
        )
        assertTrue(
            "BookmarkPanel rows must be whole-row navigable with min 48dp height",
            bookmarkPanel.contains("heightIn(min = 48.dp)") &&
                bookmarkPanel.contains("contentDescription = bookmark.accessibilityLabel(") &&
                bookmarkPanel.contains(".clickable { onBookmarkClick(bookmark) }"),
        )
        assertTrue(
            "current bookmark must expose non-color text marker and current TalkBack state",
            bookmarkPanel.contains("bookmark.id == bookmarkState.currentBookmarkId") &&
                bookmarkPanel.contains("text = \"当前\"") &&
                bookmarkPanel.contains("accessibilityLabel(isCurrent = isCurrent)") &&
                !bookmarkPanel.contains("FilterChip") &&
                !bookmarkPanel.contains("AssistChip"),
        )
        assertTrue(
            "bookmark delete control must use explicit 48dp sizeIn and Chinese contentDescription",
            bookmarkDelete.contains("sizeIn(minWidth = 48.dp, minHeight = 48.dp)") &&
                bookmarkPanel.contains("contentDescription = \"删除\${bookmark.label}\""),
        )
        assertTrue(
            "SearchPanel must gate IME search submit/clear with 48dp IconButtons",
            source.contains("private fun SearchPanel(") &&
                source.contains("contentDescription = \"执行搜索\"") &&
                source.contains("contentDescription = \"清空搜索\"") &&
                source.contains("sizeIn(minWidth = 48.dp, minHeight = 48.dp)") &&
                source.contains("KeyboardOptions(imeAction = ImeAction.Search)") &&
                source.contains("KeyboardActions(onSearch = { onSubmit() })"),
        )
        assertTrue(
            "search results must expose count, selected styling, query+selected a11y, and 48dp rows",
            searchPanel.contains("个结果") &&
                searchPanel.contains("result.index == searchState.selectedIndex") &&
                searchPanel.contains("result.readerAccessibilityLabel(") &&
                searchPanel.contains("query = searchState.query") &&
                searchPanel.contains("selected = selected") &&
                searchPanel.contains("heightIn(min = 48.dp)"),
        )
        assertTrue(
            "search loading must keep progress semantics and add a Chinese description",
            searchLoading.contains(".semantics {") &&
                searchLoading.contains("contentDescription = \"正在搜索\"") &&
                !searchLoading.contains("clearAndSetSemantics"),
        )
        assertTrue(
            "search status remains visible text without a duplicate content description",
            searchMessage.contains("Text(") &&
                searchMessage.contains("text = message") &&
                !searchMessage.contains("contentDescription"),
        )
        assertTrue(
            "empty bookmark state stays compact Chinese copy",
            bookmarkPanel.contains("\"暂无书签\"") &&
                bookmarkPanel.contains("padding(vertical = 12.dp)"),
        )
        assertTrue(
            "panels must consume ReaderBookmarkState / ReaderSearchState",
            source.contains("bookmarkState: ReaderBookmarkState") &&
                source.contains("searchState: ReaderSearchState"),
        )
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
