package dev.readflow.core.database

/** Single book export input (title + filtered bookmarks/annotations, isDeleted already excluded). */
data class BookNotesExport(
    val title: String,
    val bookmarks: List<BookmarkEntity>,
    val annotations: List<TextAnnotationEntity>,
)

object NotesMarkdownExporter {
    /** Render to Markdown. Empty collections get a placeholder. Sorted by totalProgression asc. */
    fun render(books: List<BookNotesExport>, exportedAtMillis: Long): String {
        val sb = StringBuilder()
        sb.append("# LinReads 阅读笔记\n\n")
        sb.append("> 导出时间：").append(formatTime(exportedAtMillis)).append("\n\n")
        if (books.all { it.bookmarks.isEmpty() && it.annotations.isEmpty() }) {
            sb.append("_暂无书签或标注_\n")
            return sb.toString()
        }
        books.forEach { book ->
            if (book.bookmarks.isEmpty() && book.annotations.isEmpty()) return@forEach
            sb.append("## ").append(sanitizeTitle(book.title)).append("\n\n")
            book.annotations.sortedBy { it.totalProgression }.forEach { a ->
                sb.append("> ").append(a.selectedText.trim().replace("\n", " ")).append("\n")
                a.note?.takeIf { it.isNotBlank() }?.let { sb.append("\n📝 ").append(it.trim()).append("\n") }
                sb.append("\n— ").append(percent(a.totalProgression)).append("\n\n")
            }
            book.bookmarks.sortedBy { it.totalProgression }.forEach { b ->
                sb.append("- 🔖 ").append(percent(b.totalProgression))
                b.text.trim().takeIf { it.isNotEmpty() }?.let { sb.append("：").append(it.replace("\n", " ")) }
                sb.append("\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun sanitizeTitle(raw: String): String = raw.replace("#", "")
    private fun percent(p: Float): String = "${(p * 100).toInt()}%"
    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
