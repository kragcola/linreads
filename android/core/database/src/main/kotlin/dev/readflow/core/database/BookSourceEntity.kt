package dev.readflow.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** User-configured online sources (OPDS / JSON HTTP). Builtin Calibre is settings-backed. */
@Entity(tableName = "book_sources")
data class BookSourceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
)

@Dao
interface BookSourceDao {
    @Query("SELECT * FROM book_sources WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt ASC")
    fun observeEnabled(): Flow<List<BookSourceEntity>>

    @Query("SELECT * FROM book_sources ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<BookSourceEntity>>

    @Query("SELECT * FROM book_sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookSourceEntity)

    @Query("DELETE FROM book_sources WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM book_sources")
    suspend fun maxSortOrder(): Int
}
