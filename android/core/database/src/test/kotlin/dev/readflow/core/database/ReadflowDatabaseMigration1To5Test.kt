package dev.readflow.core.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReadflowDatabaseMigration1To6Test {

    @Test
    fun `official version 1 schema migrates through Room to version 6`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "migration-1-6-${UUID.randomUUID()}.db"
        createVersion1Database(databaseName)
        val database = Room.databaseBuilder(
            context,
            ReadflowDatabase::class.java,
            databaseName,
        )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        try {
            assertEquals(
                BookEntity(
                    id = "legacy-book",
                    title = "Legacy Title",
                    author = "Legacy Author",
                    format = "EPUB",
                    coverUrl = "https://example.test/legacy-cover.jpg",
                    downloadStatus = "DOWNLOADED",
                    localUri = "file:///books/legacy.epub",
                    lastReadAt = 1_725_000_001L,
                    collectionName = null,
                    sortOrder = 0,
                    collectionId = null,
                ),
                database.bookDao().getById("legacy-book"),
            )

            // v6: book_sources must exist and be queryable after 1→6 migration path.
            val master = database.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='book_sources'",
            )
            master.use {
                assertTrue("book_sources table missing after 1→6 migration", it.moveToFirst())
                assertEquals("book_sources", it.getString(0))
            }
            val sources = database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM book_sources",
            )
            sources.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createVersion1Database(databaseName: String) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            VERSION_1_SCHEMA.forEach(db::execSQL)
                            db.execSQL(
                                """
                                INSERT OR REPLACE INTO room_master_table (id, identity_hash)
                                VALUES (42, '$VERSION_1_IDENTITY_HASH')
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO books (
                                    id, title, author, format, coverUrl, downloadStatus,
                                    localUri, lastReadAt
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                arrayOf<Any?>(
                                    "legacy-book",
                                    "Legacy Title",
                                    "Legacy Author",
                                    "EPUB",
                                    "https://example.test/legacy-cover.jpg",
                                    "DOWNLOADED",
                                    "file:///books/legacy.epub",
                                    1_725_000_001L,
                                ),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = error("Unexpected framework upgrade from $oldVersion to $newVersion")
                    },
                )
                .build(),
        )

        helper.writableDatabase
        helper.close()
    }

    private companion object {
        const val VERSION_1_IDENTITY_HASH = "92418302451280bb5f84231bb1c12e46"

        val VERSION_1_SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS books (
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                format TEXT NOT NULL,
                coverUrl TEXT,
                downloadStatus TEXT NOT NULL,
                localUri TEXT,
                lastReadAt INTEGER,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS reading_progress (
                bookId TEXT NOT NULL,
                locatorJson TEXT NOT NULL,
                totalProgression REAL NOT NULL,
                progressPercent REAL NOT NULL,
                updatedAt INTEGER NOT NULL,
                deviceId TEXT NOT NULL,
                PRIMARY KEY(bookId)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_reading_progress_bookId ON reading_progress (bookId)",
            """
            CREATE INDEX IF NOT EXISTS index_reading_progress_bookId_updatedAt
            ON reading_progress (bookId, updatedAt)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS text_annotations (
                id TEXT NOT NULL,
                bookId TEXT NOT NULL,
                totalProgression REAL NOT NULL,
                anchorType TEXT NOT NULL,
                anchorJson TEXT NOT NULL,
                selectedText TEXT NOT NULL,
                note TEXT,
                color INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deviceId TEXT NOT NULL,
                isDeleted INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_text_annotations_bookId ON text_annotations (bookId)",
            """
            CREATE INDEX IF NOT EXISTS index_text_annotations_bookId_updatedAt
            ON text_annotations (bookId, updatedAt)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS index_text_annotations_bookId_isDeleted
            ON text_annotations (bookId, isDeleted)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS ink_strokes (
                id TEXT NOT NULL,
                bookId TEXT NOT NULL,
                pageIndex INTEGER NOT NULL,
                anchorType TEXT NOT NULL,
                anchorJson TEXT NOT NULL,
                strokeData BLOB NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deviceId TEXT NOT NULL,
                isDeleted INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_ink_strokes_bookId ON ink_strokes (bookId)",
            """
            CREATE INDEX IF NOT EXISTS index_ink_strokes_bookId_updatedAt
            ON ink_strokes (bookId, updatedAt)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS index_ink_strokes_bookId_isDeleted
            ON ink_strokes (bookId, isDeleted)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                id TEXT NOT NULL,
                bookId TEXT NOT NULL,
                totalProgression REAL NOT NULL,
                locatorJson TEXT NOT NULL,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deviceId TEXT NOT NULL,
                isDeleted INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks (bookId)",
            """
            CREATE INDEX IF NOT EXISTS index_bookmarks_bookId_updatedAt
            ON bookmarks (bookId, updatedAt)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS index_bookmarks_bookId_isDeleted
            ON bookmarks (bookId, isDeleted)
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS room_master_table (
                id INTEGER PRIMARY KEY,
                identity_hash TEXT
            )
            """.trimIndent(),
        )
    }
}
