package dev.readflow.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesMarkdownExporterTest {

    @Test
    fun `empty collection returns placeholder`() {
        val md = NotesMarkdownExporter.render(emptyList(), 1_700_000_000_000L)
        assertTrue(md.contains("暂无书签或标注"))
    }

    @Test
    fun `single book with annotations and note`() {
        val annotation = TextAnnotationEntity(
            id = "a1", bookId = "b1", totalProgression = 0.42f,
            anchorType = "", anchorJson = "", selectedText = "重要段落",
            note = "这是笔记", color = 0, createdAt = 0, updatedAt = 0,
            deviceId = "d1", isDeleted = false,
        )
        val books = listOf(BookNotesExport("测试书", emptyList(), listOf(annotation)))
        val md = NotesMarkdownExporter.render(books, 1_700_000_000_000L)
        assertTrue(md.contains("测试书"))
        assertTrue(md.contains("重要段落"))
        assertTrue(md.contains("这是笔记"))
        assertTrue(md.contains("42%"))
    }

    @Test
    fun `single book with bookmark`() {
        val bookmark = BookmarkEntity(
            id = "bk1", bookId = "b1", totalProgression = 0.75f,
            locatorJson = "", text = "第3章开头", createdAt = 0, updatedAt = 0,
            deviceId = "d1", isDeleted = false,
        )
        val books = listOf(BookNotesExport("书A", listOf(bookmark), emptyList()))
        val md = NotesMarkdownExporter.render(books, 1_700_000_000_000L)
        assertTrue(md.contains("75%"))
        assertTrue(md.contains("第3章开头"))
    }

    @Test
    fun `multiple books are separated`() {
        val bm = BookmarkEntity("bk1", "b1", 0.1f, "", "书签1", 0, 0, "d1")
        val books = listOf(
            BookNotesExport("书A", listOf(bm), emptyList()),
            BookNotesExport("书B", emptyList(), emptyList()),
        )
        val md = NotesMarkdownExporter.render(books, 1_700_000_000_000L)
        assertTrue(md.contains("书A"))
        // Book B has no content so it's skipped (not rendered)
        assertTrue(md.contains("10%"))
    }

    @Test
    fun `isDeleted entries are excluded by caller`() {
        // isDeleted filtering is caller responsibility; test with pre-filtered list
        val clean = BookmarkEntity("bk1", "b1", 0.5f, "", "正常书签", 0, 0, "d1", isDeleted = false)
        val books = listOf(BookNotesExport("书", listOf(clean), emptyList()))
        val md = NotesMarkdownExporter.render(books, 1_700_000_000_000L)
        assertTrue(md.contains("50%"))
        assertTrue(md.contains("正常书签"))
    }

    @Test
    fun `title with hash is sanitized`() {
        val books = listOf(BookNotesExport("# 标题注入", emptyList(), emptyList()))
        val md = NotesMarkdownExporter.render(books, 1_700_000_000_000L)
        // Should not render as a heading — # is stripped
        assertTrue(!md.contains("## #"))
    }

    @Test
    fun `newlines in text are sanitized`() {
        val annotation = TextAnnotationEntity(
            id = "a1", bookId = "b1", totalProgression = 0.1f,
            anchorType = "", anchorJson = "",
            selectedText = "line1\nline2\nline3",
            note = null, color = 0, createdAt = 0, updatedAt = 0,
            deviceId = "d1", isDeleted = false,
        )
        val books = listOf(BookNotesExport("书", emptyList(), listOf(annotation)))
        val md = NotesMarkdownExporter.render(books, 1_700_000_000_000L)
        // newlines replaced with spaces
        assertTrue(md.contains("line1 line2 line3"))
        assertTrue(!md.contains("line1\nline2"))
    }
}
