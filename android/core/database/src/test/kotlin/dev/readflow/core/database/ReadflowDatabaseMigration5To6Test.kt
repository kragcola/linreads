package dev.readflow.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReadflowDatabaseMigration5To6Test {

    @Test
    fun migrationCreatesBookSourcesTable() {
        val databaseName = "migration-5-6-${UUID.randomUUID()}.db"
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // Minimal v5 stub: only room_master + empty books for migration path.
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS room_master_table (
                                    id INTEGER PRIMARY KEY,
                                    identity_hash TEXT
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS books (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    title TEXT NOT NULL,
                                    author TEXT NOT NULL,
                                    format TEXT NOT NULL,
                                    coverUrl TEXT,
                                    downloadStatus TEXT NOT NULL,
                                    localUri TEXT,
                                    lastReadAt INTEGER,
                                    collectionName TEXT,
                                    sortOrder INTEGER NOT NULL DEFAULT 0,
                                    collectionId TEXT
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { db ->
            assertEquals(5, db.version)
            MIGRATION_5_6.migrate(db)
            db.version = 6

            val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='book_sources'")
            cursor.use {
                assertTrue(it.moveToFirst())
                assertEquals("book_sources", it.getString(0))
            }

            db.execSQL(
                """
                INSERT INTO book_sources (id, kind, name, baseUrl, enabled, sortOrder, createdAt)
                VALUES ('source-1', 'JSON_HTTP', 'LAN JSON', 'http://192.168.1.5:8080/catalog.json', 1, 0, 1)
                """.trimIndent(),
            )
            val row = db.query("SELECT id, kind, name FROM book_sources WHERE id = 'source-1'")
            row.use {
                assertTrue(it.moveToFirst())
                assertEquals("source-1", it.getString(0))
                assertEquals("JSON_HTTP", it.getString(1))
                assertEquals("LAN JSON", it.getString(2))
            }
        }
        context.deleteDatabase(databaseName)
    }
}
