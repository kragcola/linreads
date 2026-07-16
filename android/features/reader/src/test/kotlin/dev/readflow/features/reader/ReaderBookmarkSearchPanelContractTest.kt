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
            source.contains("private fun BookmarkPanel(") &&
                source.contains("heightIn(min = 48.dp)") &&
                source.contains("contentDescription = bookmark.accessibilityLabel()") &&
                source.contains(".clickable { onBookmarkClick(bookmark) }"),
        )
        assertTrue(
            "bookmark delete control must have a Chinese contentDescription",
            source.contains("contentDescription = \"删除\${bookmark.label}\"") ||
                source.contains("contentDescription = \"删除"),
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
            "search results must expose count, selected styling, and 48dp rows",
            source.contains("个结果") &&
                source.contains("result.index == searchState.selectedIndex") &&
                source.contains("contentDescription = result.readerAccessibilityLabel()") &&
                source.contains("heightIn(min = 48.dp)"),
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
