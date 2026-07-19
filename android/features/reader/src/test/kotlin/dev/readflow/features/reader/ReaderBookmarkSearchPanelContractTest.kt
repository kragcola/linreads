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
        val bookmarkRail = source
            .substringAfter("private fun ReaderBookmarkRail(", missingDelimiterValue = "")
            .substringBefore("private fun ReaderControlPanel(")
            .ifEmpty {
                source
                    .substringAfter("private fun ReaderBookmarkRail(", missingDelimiterValue = "")
                    .substringBefore("private fun BookmarkPanel(")
            }

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
            "BookmarkPanel header shows total count and LazyColumn uses stable item keys",
            (
                bookmarkPanel.contains("bookmarkState.items.size") ||
                    bookmarkPanel.contains("items.size")
                ) &&
                (
                    bookmarkPanel.contains("\"书签（") ||
                        bookmarkPanel.contains("书签（") ||
                        bookmarkPanel.contains("个书签")
                    ) &&
                (
                    bookmarkPanel.contains("key = { it.id }") ||
                        bookmarkPanel.contains("key = { bookmark -> bookmark.id }") ||
                        bookmarkPanel.contains("key = { bookmark.id }")
                    ),
        )
        assertTrue(
            "BookmarkPanel primary label is single-line with optional strategy-aware detail line",
            bookmarkPanel.contains("bookmark.label") &&
                bookmarkPanel.contains("maxLines = 1") &&
                bookmarkPanel.contains("readerBookmarkDetailLabel") &&
                !bookmarkPanel.contains("ByteOffset"),
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
            "search prev/next must be 48dp IconButtons with Chinese descriptions and current/total counter",
            searchPanel.contains("contentDescription = \"上一个搜索结果\"") &&
                searchPanel.contains("contentDescription = \"下一个搜索结果\"") &&
                searchPanel.contains("sizeIn(minWidth = 48.dp, minHeight = 48.dp)") &&
                searchPanel.contains("canNavigateToPreviousSearchResult()") &&
                searchPanel.contains("canNavigateToNextSearchResult()") &&
                searchPanel.contains("onPreviousResult") &&
                searchPanel.contains("onNextResult") &&
                // current is 0 when selectedIndex is null: (selectedIndex?.plus(1) ?: 0)
                searchPanel.contains("selectedIndex?.plus(1) ?: 0") &&
                searchPanel.contains("results.size") &&
                (
                    searchPanel.contains("KeyboardArrowLeft") ||
                        searchPanel.contains("KeyboardArrowUp") ||
                        searchPanel.contains("ExpandLess")
                    ) &&
                (
                    searchPanel.contains("KeyboardArrowRight") ||
                        searchPanel.contains("KeyboardArrowDown") ||
                        searchPanel.contains("ExpandMore")
                    ),
        )
        assertTrue(
            "search rows show position label plus up to two lines of snippet with graceful blank fallback",
            searchPanel.contains("result.readerLabel()") &&
                searchPanel.contains("result.snippet") &&
                searchPanel.contains("maxLines = 2") &&
                searchPanel.contains("snippetText.isNotEmpty()"),
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
        assertTrue(
            "host-level ReaderBookmarkRail is trailing, gated on non-empty items, with 48dp targets",
            source.contains("private fun ReaderBookmarkRail(") &&
                (
                    source.contains("bookmarkState.items.isNotEmpty()") ||
                        source.contains("state.bookmarks.items.isNotEmpty()")
                    ) &&
                (
                    source.contains("Alignment.CenterEnd") ||
                        source.contains("Alignment.TopEnd") ||
                        source.contains("Alignment.BottomEnd")
                    ) &&
                (
                    bookmarkRail.contains("size(48.dp)") ||
                        bookmarkRail.contains("sizeIn(minWidth = 48.dp, minHeight = 48.dp)") ||
                        bookmarkRail.contains("Modifier.size(48.dp)")
                    ) &&
                bookmarkRail.contains("contentDescription = bookmark.accessibilityLabel(") &&
                bookmarkRail.contains("onBookmarkClick(bookmark)") &&
                (
                    bookmarkRail.contains("Icons.Default.Bookmark") ||
                        bookmarkRail.contains("Icons.Filled.Bookmark")
                    ),
        )
        assertTrue(
            "rail current bookmark uses tint plus non-color icon/semantics cue",
            bookmarkRail.contains("currentBookmarkId") &&
                bookmarkRail.contains("accessibilityLabel(isCurrent = isCurrent)") &&
                (
                    bookmarkRail.contains("BookmarkBorder") ||
                        bookmarkRail.contains("text = \"当前\"")
                    ),
        )
        assertTrue(
            "rail stays narrow and does not use a full-width card surface",
            (
                bookmarkRail.contains("width(48.dp)") ||
                    bookmarkRail.contains("widthIn(max = 48.dp)") ||
                    bookmarkRail.contains(".width(48.dp)")
                ) &&
                !bookmarkRail.contains("fillMaxWidth()") &&
                !bookmarkRail.contains("Card("),
        )
        assertTrue(
            "bookmark chrome must not introduce database migration or entity schema edits",
            !source.contains("Migration(") &&
                !source.contains("@Database") &&
                !source.contains("autoMigrations") &&
                !source.contains("BookmarkEntity(") &&
                !bookmarkPanel.contains("Room") &&
                !bookmarkRail.contains("Room"),
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
