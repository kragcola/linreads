package dev.readflow.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room database aggregating the 5 tables (§7.8). */
@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        TextAnnotationEntity::class,
        InkStrokeEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class ReadflowDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookDeletionDao(): BookDeletionDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun textAnnotationDao(): TextAnnotationDao
    abstract fun inkStrokeDao(): InkStrokeDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN collectionId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_books_collectionId ON books(collectionId)")
        db.execSQL(
            """
            UPDATE books
            SET collectionId = 'legacy:' || lower(hex(collectionName))
            WHERE collectionName IS NOT NULL
            """.trimIndent(),
        )
    }
}
