package dev.readflow.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReadflowDatabaseMigration6To7Test {

    @Test
    fun migrationPreservesLibraryDataAndConvertsLegacySources() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "migration-6-7-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE books (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    title TEXT NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE reading_progress (
                                    bookId TEXT NOT NULL PRIMARY KEY,
                                    locatorJson TEXT NOT NULL,
                                    progressPercent REAL NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE book_sources (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    kind TEXT NOT NULL,
                                    name TEXT NOT NULL,
                                    baseUrl TEXT NOT NULL,
                                    enabled INTEGER NOT NULL,
                                    sortOrder INTEGER NOT NULL,
                                    createdAt INTEGER NOT NULL
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

        try {
            helper.writableDatabase.use { db ->
                db.execSQL("INSERT INTO books (id, title) VALUES ('book-1', '保留的书')")
                db.execSQL(
                    """
                    INSERT INTO reading_progress (bookId, locatorJson, progressPercent)
                    VALUES ('book-1', '{"chapter":2}', 37.5)
                    """.trimIndent(),
                )
                db.insertSource(
                    id = "source-opds",
                    kind = "OPDS",
                    baseUrl = "https://catalog.example/opds?q=\"quoted\"&path=\\books",
                    enabled = true,
                    createdAt = 101L,
                )
                db.insertSource(
                    id = "source-json",
                    kind = "JSON_HTTP",
                    baseUrl = "http://192.168.1.8:8080/catalog.json",
                    enabled = true,
                    createdAt = 202L,
                )
                db.insertSource(
                    id = "source-unknown",
                    kind = "CUSTOM_PLUGIN",
                    baseUrl = "https://unknown.example/catalog",
                    enabled = true,
                    createdAt = 303L,
                )

                MIGRATION_6_7.migrate(db)
                db.version = 7

                db.query("SELECT title FROM books WHERE id = 'book-1'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("保留的书", cursor.getString(0))
                }
                db.query(
                    "SELECT locatorJson, progressPercent FROM reading_progress WHERE bookId = 'book-1'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("{\"chapter\":2}", cursor.getString(0))
                    assertEquals(37.5, cursor.getDouble(1), 0.0)
                }

                val sources = db.query(
                    """
                    SELECT id, adapterId, configJson, configVersion, enabled, updatedAt
                    FROM book_sources
                    ORDER BY id
                    """.trimIndent(),
                ).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            put(
                                cursor.getString(0),
                                MigratedSource(
                                    adapterId = cursor.getString(1),
                                    configJson = cursor.getString(2),
                                    configVersion = cursor.getInt(3),
                                    enabled = cursor.getInt(4) == 1,
                                    updatedAt = cursor.getLong(5),
                                ),
                            )
                        }
                    }
                }

                assertEquals("opds", sources.getValue("source-opds").adapterId)
                assertEquals(
                    "https://catalog.example/opds?q=\"quoted\"&path=\\books",
                    sources.getValue("source-opds").baseUrl(),
                )
                assertEquals("json-http", sources.getValue("source-json").adapterId)
                assertEquals(
                    "http://192.168.1.8:8080/catalog.json",
                    sources.getValue("source-json").baseUrl(),
                )
                assertEquals("unknown:custom_plugin", sources.getValue("source-unknown").adapterId)
                assertEquals(false, sources.getValue("source-unknown").enabled)
                assertEquals(1, sources.getValue("source-opds").configVersion)
                assertEquals(101L, sources.getValue("source-opds").updatedAt)
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun SupportSQLiteDatabase.insertSource(
        id: String,
        kind: String,
        baseUrl: String,
        enabled: Boolean,
        createdAt: Long,
    ) {
        execSQL(
            """
            INSERT INTO book_sources (id, kind, name, baseUrl, enabled, sortOrder, createdAt)
            VALUES (?, ?, ?, ?, ?, 0, ?)
            """.trimIndent(),
            arrayOf(id, kind, id, baseUrl, if (enabled) 1 else 0, createdAt),
        )
    }

    private fun MigratedSource.baseUrl(): String =
        Json.parseToJsonElement(configJson).jsonObject.getValue("baseUrl").jsonPrimitive.content

    private data class MigratedSource(
        val adapterId: String,
        val configJson: String,
        val configVersion: Int,
        val enabled: Boolean,
        val updatedAt: Long,
    )
}
