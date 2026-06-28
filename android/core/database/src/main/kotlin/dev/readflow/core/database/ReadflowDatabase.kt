package dev.readflow.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

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
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class ReadflowDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun textAnnotationDao(): TextAnnotationDao
    abstract fun inkStrokeDao(): InkStrokeDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao
}
