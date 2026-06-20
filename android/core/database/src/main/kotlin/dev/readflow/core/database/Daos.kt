package dev.readflow.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** DAO signatures for the 5 tables. Phase 1 scaffold: minimal CRUD surface, no complex queries. */

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    /**
     * Shelf rows: every book left-joined with its whole-book progress. Recent-first,
     * NULL lastReadAt (never opened) last. Powers the library grid (设计文档 §2.1).
     */
    @Query(
        """
        SELECT b.*, COALESCE(p.totalProgression, 0.0) AS progress
        FROM books b
        LEFT JOIN reading_progress p ON p.bookId = b.id
        ORDER BY b.lastReadAt IS NULL, b.lastReadAt DESC, b.title COLLATE NOCASE
        """,
    )
    fun observeShelf(): Flow<List<BookWithProgress>>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookEntity?

    @Query("UPDATE books SET lastReadAt = :ts WHERE id = :id")
    suspend fun updateLastReadAt(id: String, ts: Long)
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)
}

@Dao
interface TextAnnotationDao {
    @Query("SELECT * FROM text_annotations WHERE bookId = :bookId AND isDeleted = 0")
    fun observeForBook(bookId: String): Flow<List<TextAnnotationEntity>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)
}
