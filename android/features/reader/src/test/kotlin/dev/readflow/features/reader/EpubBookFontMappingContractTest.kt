package dev.readflow.features.reader

import dev.readflow.core.model.FontChoice
import dev.readflow.core.prefs.canonicalBookIdentity
import dev.readflow.core.prefs.canonicalEpubFontFamilyKey
import dev.readflow.core.prefs.mergedEpubFontReplacements
import dev.readflow.render.api.EpubCssFontFamilyInfo
import dev.readflow.render.api.EpubCssFontMappingStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure contract tests for book-scoped EPUB font mapping (VM merge + UI status labels).
 * No Android runtime / disk IO — safe for unit filters.
 */
class EpubBookFontMappingContractTest {

    @Test
    fun `open book merge prefers book map over global and reuses on next open identity`() {
        val bookId = canonicalBookIdentity("  shelf:local:novel-1  ")!!
        val storedByBook = mapOf(
            bookId to mapOf("story" to "system_sans"),
        )
        val global = mapOf(
            "story" to "system_serif",
            "code" to "system_monospace",
        )
        val firstOpen = mergedEpubFontReplacements(
            global = global,
            bookScoped = storedByBook[bookId].orEmpty(),
        )
        val secondOpen = mergedEpubFontReplacements(
            global = global,
            bookScoped = storedByBook[canonicalBookIdentity("shelf:local:novel-1")].orEmpty(),
        )
        assertEquals(firstOpen, secondOpen)
        assertEquals("system_sans", firstOpen["story"])
        assertEquals("system_monospace", firstOpen["code"])
    }

    @Test
    fun `family key normalization matches quotes case and whitespace`() {
        assertEquals(
            canonicalEpubFontFamilyKey("\"Book Serif\""),
            canonicalEpubFontFamilyKey("  book   serif  "),
        )
        assertNull(canonicalEpubFontFamilyKey(" "))
    }

    @Test
    fun `clearing book entry falls back through merge without polluting global`() {
        val global = mapOf("story" to "system_serif")
        val afterClear = mergedEpubFontReplacements(global, bookScoped = emptyMap())
        assertEquals(global, afterClear)
    }

    @Test
    fun `status labels cover book global embedded and unresolved`() {
        val book = EpubCssFontFamilyInfo(
            family = "story",
            displayName = "Story",
            status = EpubCssFontMappingStatus.BOOK_MAPPED,
            mappedFontId = FontChoice.SystemSans.serialize(),
        )
        val global = EpubCssFontFamilyInfo(
            family = "code",
            displayName = "Code",
            status = EpubCssFontMappingStatus.GLOBAL_MAPPED,
            mappedFontId = FontChoice.SystemMonospace.serialize(),
        )
        val embedded = EpubCssFontFamilyInfo(
            family = "body",
            displayName = "Body",
            status = EpubCssFontMappingStatus.EMBEDDED,
            embeddedSrcPath = "fonts/body.ttf",
        )
        val unresolved = EpubCssFontFamilyInfo(
            family = "missing",
            displayName = "Missing",
            status = EpubCssFontMappingStatus.UNRESOLVED,
        )
        assertTrue(epubCssFontStatusLabel(book).contains("本书映射"))
        assertTrue(epubCssFontStatusLabel(global).contains("全局映射"))
        assertTrue(epubCssFontStatusLabel(embedded).contains("内嵌"))
        assertTrue(epubCssFontStatusLabel(unresolved).contains("未解析"))
        val missing = book.copy(mappedFontId = FontChoice.Custom("missing.ttf").serialize())
        assertTrue(
            epubCssFontStatusLabel(missing, availableFontIds = emptySet()).contains("目标缺失"),
        )
        assertTrue(readerBookFontStatusLabel(book).contains("本书使用"))
        assertTrue(readerBookFontStatusLabel(global).contains("跟随全局设置"))
        assertTrue(readerBookFontStatusLabel(embedded).contains("书籍自带字体"))
        assertTrue(readerBookFontStatusLabel(unresolved).contains("默认字体"))
        assertTrue(readerBookFontStatusLabel(missing).contains("暂不可用"))
    }

    @Test
    fun `ui state defaults keep empty catalog for non-epub`() {
        assertTrue(ReaderUiState().epubCssFontCatalog.isEmpty())
        assertTrue(ReaderUiState().epubBookFontReplacements.isEmpty())
    }

    @Test
    fun `reader intent surface exposes book font replacement actions`() {
        val set = ReaderIntent.SetEpubBookFontReplacement("Story", FontChoice.SystemSans)
        val clear = ReaderIntent.ClearEpubBookFontReplacement("Story")
        assertEquals("Story", set.family)
        assertEquals(FontChoice.SystemSans, set.choice)
        assertEquals("Story", clear.family)
    }

    @Test
    fun `typography panel exposes one font entry and unified management window owns both catalogs`() {
        val source = readerScreenSource()
        assertTrue(source.contains("字体与排版"))
        assertTrue(source.contains("FontManagementEntry("))
        assertTrue(source.contains("FontManagementWindow("))
        assertTrue(source.contains("我的字体"))
        assertTrue(source.contains("本书字体（\${catalog.size}）"))
        assertTrue(source.contains("PrimaryTabRow("))
        assertTrue(source.contains("Dialog("))
        assertTrue(source.contains("TopAppBar("))
        assertTrue(source.contains("BackHandler"))
        assertTrue(source.contains("usePlatformDefaultWidth = false"))
        assertTrue(source.contains("dismissOnClickOutside = false"))
        assertTrue(source.contains("LazyColumn("))
        assertTrue(source.contains("key = { it.family }"))
        assertTrue(source.contains("items = catalog"))
        assertTrue(source.contains("entry.excerpt"))
        assertTrue(source.contains("entry.occurrenceCount"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("epubCssFontPreviewTypeface"))
        assertTrue(source.contains("ComposeTypeface(typeface)"))
        assertTrue(source.contains("previewTypefaceResolver(entry)"))
        assertTrue(source.contains("更换 \${entry.displayName} 字体"))
        assertTrue(source.contains("恢复 \${entry.displayName} 为书籍字体"))
        assertTrue(source.contains("heightIn(min = 48.dp)"))
        assertTrue(source.contains("跟随书籍"))
        assertTrue(source.contains("导入字体"))
        assertTrue(source.contains("DeleteImportedFontDialog("))
        assertTrue(source.contains("Icons.Default.Delete"))
        assertTrue(source.contains("删除字体后会清除正文和书籍中的全部引用"))
        assertTrue(source.contains("liveRegion = LiveRegionMode.Polite"))
        assertTrue(source.contains("ActivityResultContracts.OpenDocument"))
        assertTrue(source.contains("pendingFontImportFamily"))
        assertTrue(source.contains("removeImportedFontReferences(choice)"))
        assertTrue(source.contains("recoverPendingImportedFontDeletions"))
        assertTrue(source.contains("completeImportedFontDeletion(choice)"))
        assertTrue(source.contains("scope.launch(start = CoroutineStart.UNDISPATCHED)"))
        assertTrue(source.contains("withContext(NonCancellable)"))
        assertTrue(source.contains("ReaderIntent.SetEpubBookFontReplacement(family, choice)"))
        val window = source.substringAfter("private fun FontManagementWindow(")
            .substringBefore("private fun BookFontUsageRow(")
        assertTrue(window.contains("fontActionError"))
        assertTrue(window.contains("字体操作错误：\$fontActionError"))
        assertTrue(!source.contains("private fun ReaderFontSelector("))
        assertTrue(!source.contains("private fun BookFontCatalogEntry("))
        assertTrue(!source.contains("private fun BookFontOverridesSection("))
        assertTrue(!source.contains("本书 CSS 字体"))
        assertTrue(!source.contains("本书有未解析字体"))
        assertTrue(!source.contains("书中 CSS 字体名"))
    }

    private fun readerScreenSource(): String {
        val workingDir = java.io.File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            java.io.File(workingDir, "src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
            java.io.File(workingDir, "features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
            java.io.File(workingDir, "android/features/reader/src/main/kotlin/dev/readflow/features/reader/ReaderScreen.kt"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("ReaderScreen.kt not found from ${workingDir.absolutePath}")
    }
}
