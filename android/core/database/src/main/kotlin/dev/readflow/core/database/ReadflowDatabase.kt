package dev.readflow.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Room database aggregating library + reading tables (§7.8) and online source configs. */
@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        TextAnnotationEntity::class,
        InkStrokeEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        BookSourceEntity::class,
    ],
    version = 7,
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
    abstract fun bookSourceDao(): BookSourceDao
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

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `book_sources` (
                `id` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `baseUrl` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `sortOrder` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_sources ADD COLUMN adapterId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE book_sources ADD COLUMN configJson TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("ALTER TABLE book_sources ADD COLUMN configVersion INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE book_sources ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.query("SELECT id, kind, baseUrl, enabled, createdAt FROM book_sources").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val adapterId = cursor.getString(1).legacySourceAdapterId()
                val configJson = JsonObject(mapOf("baseUrl" to JsonPrimitive(cursor.getString(2)))).toString()
                val enabled = cursor.getInt(3) == 1 && !adapterId.startsWith("unknown:")
                val updatedAt = cursor.getLong(4).coerceAtLeast(0L)
                db.execSQL(
                    """
                    UPDATE book_sources
                    SET adapterId = ?, configJson = ?, configVersion = 1, updatedAt = ?, enabled = ?
                    WHERE id = ?
                    """.trimIndent(),
                    arrayOf(adapterId, configJson, updatedAt, if (enabled) 1 else 0, id),
                )
            }
        }
    }
}

private fun String.legacySourceAdapterId(): String = when (uppercase()) {
    "CALIBRE" -> "calibre"
    "OPDS" -> "opds"
    "JSON_HTTP" -> "json-http"
    "HTML_RULES_V1" -> "html-rules-v1"
    else -> "unknown:${lowercase()}"
}
