package dev.readflow.core.model

import kotlinx.serialization.Serializable

/** Supported book formats, in priority order (EPUB highest). */
enum class BookFormat {
    EPUB, AZW3, MOBI, PDF, TXT, MD, DOCX, CBZ, UNKNOWN;

    companion object {
        /** Map a (lower/upper-case) file extension to a format; UNKNOWN if unrecognized. */
        fun fromExtension(ext: String): BookFormat = when (ext.lowercase()) {
            "epub" -> EPUB
            "azw3" -> AZW3
            "mobi" -> MOBI
            "pdf" -> PDF
            "txt" -> TXT
            "md", "markdown" -> MD
            "docx" -> DOCX
            "cbz" -> CBZ
            else -> UNKNOWN
        }
    }
}

/** Download / availability state of a book's local asset. */
enum class DownloadStatus { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED }

enum class BookRemovalMode { REMOVE_FROM_SHELF, DELETE_ALL }

/**
 * Reading theme preference. SYSTEM follows the OS day/night; the rest are flat reading-colour
 * presets ported from 静读天下 (Moon+ Reader)'s built-in themes (see [ReaderPalette]). LIGHT/DARK/SEPIA
 * are the historical day/night/eye-care defaults; WHITE/GREEN/GREY/BLACK/SLATE/NAVY are additional
 * Moon+ swatches. Enum names are persisted (DataStore + ThemeProfile), so existing values must never
 * be renamed or reordered — only appended.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, SEPIA, WHITE, GREEN, GREY, BLACK, SLATE, NAVY }

/**
 * Catalog-level book metadata (Layer 0, pure data).
 * Source-agnostic: populated from Calibre, OPDS, or local import.
 */
@Serializable
data class BookMeta(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val coverUrl: String? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val localUri: String? = null,
    val lastReadAt: Long? = null,
    /** Optional collection/folder grouping name; books sharing one form a [BookBundle]. */
    val collectionName: String? = null,
    /** Whole-book reading progress [0,1], mirrored from reading_progress for shelf display. */
    val progress: Float = 0f,
)

/**
 * A shelf entry: either a single [BookMeta] or a [BookBundle] (a stack of books
 * sharing one collection name). The library lays these out in one aligned grid
 * (设计文档 §2.1 / §2.1.2).
 */
sealed interface LibraryItem {
    data class Single(val book: BookMeta) : LibraryItem
    data class Bundle(val bundle: BookBundle) : LibraryItem
}

/**
 * A group of books combined under one collection name, shown as a stack of covers
 * (设计文档 §2.1.2). [topBooks] is ordered most-recent-first; the UI reveals up to
 * the first 4 covers' edges, no "N 本" text — stack thickness conveys count.
 */
data class BookBundle(
    val name: String,
    val books: List<BookMeta>,
) {
    val count: Int get() = books.size
    /** Covers whose edges are stacked, capped at 4 (min(count, 4)). */
    val topBooks: List<BookMeta> get() = books.take(4)
}

/** A 2D offset in render-surface pixels (pure data, used by ink/selection/pan). */
@Serializable
data class Offset(val x: Float = 0f, val y: Float = 0f) {
    companion object { val Zero = Offset() }
}
