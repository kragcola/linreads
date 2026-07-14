package dev.readflow.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query(
        """
        SELECT b.*, COALESCE(p.totalProgression, 0.0) AS progress
        FROM books b
        LEFT JOIN reading_progress p ON p.bookId = b.id
        ORDER BY b.sortOrder ASC, b.title COLLATE NOCASE, b.id COLLATE NOCASE
        """,
    )
    fun observeShelf(): Flow<List<BookWithProgress>>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT MAX(sortOrder) FROM books")
    suspend fun maxSortOrder(): Int?

    @Query(
        """
        SELECT * FROM books
        ORDER BY sortOrder ASC, lastReadAt IS NULL, lastReadAt DESC,
            title COLLATE NOCASE, id COLLATE NOCASE
        """,
    )
    suspend fun shelfRowsForOrderNormalization(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookEntity?

    @Query("UPDATE books SET lastReadAt = :ts WHERE id = :id")
    suspend fun setLastReadAt(id: String, ts: Long)

    @Query(
        """
        SELECT id, localUri, lastReadAt
        FROM books
        WHERE id LIKE :remotePrefix || '%'
            AND downloadStatus = :downloadedStatus
            AND localUri IS NOT NULL
        """,
    )
    suspend fun downloadedRemoteCacheBooks(
        remotePrefix: String,
        downloadedStatus: String,
    ): List<DownloadedCacheBook>

    @Query("UPDATE books SET downloadStatus = :downloadStatus, localUri = NULL WHERE id = :id")
    suspend fun clearDownloadedAsset(id: String, downloadStatus: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE books SET collectionId = :collectionId, collectionName = :name WHERE id = :id")
    suspend fun updateCollection(
        id: String,
        collectionId: String?,
        name: String?,
    ): Int

    @Query(
        """
        UPDATE books
        SET collectionId = :targetCollectionId,
            collectionName = (
                SELECT collectionName
                FROM books
                WHERE collectionId = :targetCollectionId
                LIMIT 1
            )
        WHERE id = :sourceId
          AND collectionId IS NULL
          AND EXISTS (
              SELECT 1
              FROM books
              WHERE collectionId = :targetCollectionId
                AND id != :sourceId
          )
        """,
    )
    suspend fun moveToGroup(sourceId: String, targetCollectionId: String): Int

    @Query(
        """
        UPDATE books
        SET collectionId = :collectionId,
            collectionName = :name
        WHERE id IN (:sourceId, :targetId)
          AND collectionId IS NULL
          AND (
              SELECT COUNT(*)
              FROM books
              WHERE id IN (:sourceId, :targetId)
                AND collectionId IS NULL
          ) = 2
        """,
    )
    suspend fun createGroup(
        sourceId: String,
        targetId: String,
        collectionId: String,
        name: String,
    ): Int

    @Query("UPDATE books SET collectionName = :newName WHERE collectionId = :collectionId")
    suspend fun renameCollection(collectionId: String, newName: String): Int

    @Query("UPDATE books SET collectionId = NULL, collectionName = NULL WHERE collectionId = :collectionId")
    suspend fun clearCollection(collectionId: String): Int

    @Query("UPDATE books SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    @Transaction
    suspend fun normalizeShelfOrderIfNeeded() {
        val rows = shelfRowsForOrderNormalization()
        if (rows.map(BookEntity::sortOrder).distinct().size == rows.size) return
        rows.forEachIndexed { order, book -> updateSortOrder(book.id, order) }
    }

    @Transaction
    suspend fun updateLastReadAt(id: String, ts: Long) {
        normalizeShelfOrderIfNeeded()
        setLastReadAt(id, ts)
    }

    @Transaction
    suspend fun replaceShelfOrder(ids: List<String>) {
        ids.forEachIndexed { order, id -> updateSortOrder(id, order) }
    }
}

@Dao
interface BookDeletionDao {
    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteProgress(bookId: String)

    @Query("DELETE FROM text_annotations WHERE bookId = :bookId")
    suspend fun deleteAnnotations(bookId: String)

    @Query("DELETE FROM ink_strokes WHERE bookId = :bookId")
    suspend fun deleteInkStrokes(bookId: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarks(bookId: String)

    @Query("DELETE FROM reading_sessions WHERE bookId = :bookId")
    suspend fun deleteSessions(bookId: String)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC, bookId COLLATE NOCASE")
    suspend fun allForBackup(): List<ReadingProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)
}

@Dao
interface TextAnnotationDao {
    @Query("SELECT * FROM text_annotations WHERE bookId = :bookId AND isDeleted = 0")
    fun observeForBook(bookId: String): Flow<List<TextAnnotationEntity>>

    @Query("SELECT * FROM text_annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TextAnnotationEntity?

    @Query("SELECT * FROM text_annotations ORDER BY updatedAt DESC, id COLLATE NOCASE")
    suspend fun allForBackup(): List<TextAnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(annotation: TextAnnotationEntity)
}

@Dao
interface InkStrokeDao {
    @Query("SELECT * FROM ink_strokes WHERE bookId = :bookId AND pageIndex = :pageIndex AND isDeleted = 0")
    suspend fun forPage(bookId: String, pageIndex: Int): List<InkStrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stroke: InkStrokeEntity)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND isDeleted = 0 ORDER BY totalProgression")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC, id COLLATE NOCASE")
    suspend fun allForBackup(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET isDeleted = 1, updatedAt = :updatedAt, deviceId = :deviceId WHERE id = :id")
    suspend fun markDeleted(id: String, updatedAt: Long, deviceId: String)
}

@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ReadingSessionEntity)

    @Query("SELECT COALESCE(SUM(durationMs),0) FROM reading_sessions WHERE startedAt >= :sinceMillis")
    suspend fun totalDurationSince(sinceMillis: Long): Long

    @Query("SELECT COALESCE(SUM(durationMs),0) FROM reading_sessions WHERE bookId = :bookId")
    suspend fun totalDurationForBook(bookId: String): Long

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC")
    suspend fun allForBackup(): List<ReadingSessionEntity>
}
