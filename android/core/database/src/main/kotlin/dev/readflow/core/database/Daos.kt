package dev.readflow.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
        ORDER BY b.sortOrder ASC, b.lastReadAt IS NULL, b.lastReadAt DESC, b.title COLLATE NOCASE
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

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE books SET collectionName = :name WHERE id = :id")
    suspend fun updateCollectionName(id: String, name: String?)

    @Query("UPDATE books SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)
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
