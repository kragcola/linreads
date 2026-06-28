package dev.readflow.core.database

import java.io.OutputStream

interface NotesMarkdownExportStore {
    /** Export all books' bookmarks & annotations as Markdown. Returns count of books with content. */
    suspend fun export(output: OutputStream): Int
}

class NotesMarkdownFileExporter(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val textAnnotationDao: TextAnnotationDao,
) : NotesMarkdownExportStore {
    override suspend fun export(output: OutputStream): Int {
        val bookmarks = bookmarkDao.allForBackup().filter { !it.isDeleted }.groupBy { it.bookId }
        val annotations = textAnnotationDao.allForBackup().filter { !it.isDeleted }.groupBy { it.bookId }
        val bookIds = (bookmarks.keys + annotations.keys).distinct()
        val books = bookIds.mapNotNull { id ->
            val title = bookDao.getById(id)?.title ?: return@mapNotNull null
            BookNotesExport(title, bookmarks[id].orEmpty(), annotations[id].orEmpty())
        }
        val md = NotesMarkdownExporter.render(books, System.currentTimeMillis())
        output.use { it.write(md.toByteArray(Charsets.UTF_8)) }
        return books.count { it.bookmarks.isNotEmpty() || it.annotations.isNotEmpty() }
    }
}
