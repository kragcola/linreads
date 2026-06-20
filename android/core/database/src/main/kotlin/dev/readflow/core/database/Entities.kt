package dev.readflow.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entities for the 5 tables in §7.8. Phase 1 scaffold: schema only, no business queries. */

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val format: String,
    val coverUrl: String? = null,
    val downloadStatus: String,
    val localUri: String? = null,
    val lastReadAt: Long? = null,
    val collectionName: String? = null,
)

/** Projection for the shelf query: a book row plus its joined whole-book progress. */
data class BookWithProgress(
    @androidx.room.Embedded val book: BookEntity,
    val progress: Float,
)

@Entity(
    tableName = "reading_progress",
    indices = [Index("bookId"), Index(value = ["bookId", "updatedAt"])],
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val locatorJson: String,
    val totalProgression: Float,
    val progressPercent: Float,
    val updatedAt: Long,
    val deviceId: String,
)

@Entity(
    tableName = "text_annotations",
    indices = [Index("bookId"), Index(value = ["bookId", "updatedAt"]), Index(value = ["bookId", "isDeleted"])],
)
data class TextAnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val totalProgression: Float,
    val anchorType: String,
    val anchorJson: String,
    val selectedText: String,
    val note: String? = null,
    val color: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "ink_strokes",
    indices = [Index("bookId"), Index(value = ["bookId", "updatedAt"]), Index(value = ["bookId", "isDeleted"])],
)
data class InkStrokeEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val pageIndex: Int,
    val anchorType: String,
    val anchorJson: String,
    val strokeData: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val isDeleted: Boolean = false,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId"), Index(value = ["bookId", "updatedAt"]), Index(value = ["bookId", "isDeleted"])],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val totalProgression: Float,
    val locatorJson: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val isDeleted: Boolean = false,
)
