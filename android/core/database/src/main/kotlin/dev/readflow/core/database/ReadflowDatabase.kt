package dev.readflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/** Room database aggregating the 5 tables (§7.8). Phase 1 scaffold. */
@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        TextAnnotationEntity::class,
        InkStrokeEntity::class,
        BookmarkEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ReadflowDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun textAnnotationDao(): TextAnnotationDao
    abstract fun inkStrokeDao(): InkStrokeDao
    abstract fun bookmarkDao(): BookmarkDao
}
