package dev.readflow.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReadflowDatabaseMigration4To5Test {

    @Test
    fun `migration backfills stable collection ids and preserves every book field`() {
        val legacyBooks = listOf(
            BookRow(
                id = "reading-first",
                title = "第一本书",
                author = "作者甲",
                format = "EPUB",
                coverUrl = "https://example.test/covers/first.jpg",
                downloadStatus = "DOWNLOADED",
                localUri = "file:///books/first.epub",
                lastReadAt = 1_725_000_001L,
                collectionName = "阅读清单",
                sortOrder = 7,
            ),
            BookRow(
                id = "reading-second",
                title = "Second Book",
                author = "Author B",
                format = "PDF",
                coverUrl = null,
                downloadStatus = "NOT_DOWNLOADED",
                localUri = null,
                lastReadAt = null,
                collectionName = "阅读清单",
                sortOrder = 8,
            ),
            BookRow(
                id = "work-book",
                title = "Work Notes",
                author = "Author C",
                format = "TXT",
                coverUrl = "content://covers/work",
                downloadStatus = "DOWNLOADING",
                localUri = "content://books/work",
                lastReadAt = 42L,
                collectionName = "工作资料",
                sortOrder = 12,
            ),
            BookRow(
                id = "single-book",
                title = "Standalone",
                author = "Author D",
                format = "MOBI",
                coverUrl = null,
                downloadStatus = "FAILED",
                localUri = null,
                lastReadAt = 0L,
                collectionName = null,
                sortOrder = 99,
            ),
        )

        val firstMigration = migrate(legacyBooks).associateBy(BookRow::id)
        val repeatedMigration = migrate(legacyBooks).associateBy(BookRow::id)

        legacyBooks.forEach { expected ->
            val actual = firstMigration.getValue(expected.id)
            assertEquals(expected, actual.copy(collectionId = null))
        }

        val firstReadingId = firstMigration.getValue("reading-first").collectionId
        val secondReadingId = firstMigration.getValue("reading-second").collectionId
        val workId = firstMigration.getValue("work-book").collectionId

        assertNotNull(firstReadingId)
        assertNotNull(workId)
        assertEquals(firstReadingId, secondReadingId)
        assertNotEquals(firstReadingId, workId)
        assertEquals(
            firstReadingId,
            repeatedMigration.getValue("reading-first").collectionId,
        )
        assertEquals(
            workId,
            repeatedMigration.getValue("work-book").collectionId,
        )
        assertNull(firstMigration.getValue("single-book").collectionId)
    }

    private fun migrate(books: List<BookRow>): List<BookRow> {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "migration-4-5-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(CREATE_BOOKS_V4)
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

        return try {
            val database = helper.writableDatabase
            books.forEach { database.insert(it) }

            MIGRATION_4_5.migrate(database)

            database.query(
                """
                SELECT id, title, author, format, coverUrl, downloadStatus, localUri,
                       lastReadAt, collectionName, sortOrder, collectionId
                FROM books
                ORDER BY id
                """.trimIndent(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            BookRow(
                                id = cursor.getString(0),
                                title = cursor.getString(1),
                                author = cursor.getString(2),
                                format = cursor.getString(3),
                                coverUrl = cursor.nullableString(4),
                                downloadStatus = cursor.getString(5),
                                localUri = cursor.nullableString(6),
                                lastReadAt = cursor.nullableLong(7),
                                collectionName = cursor.nullableString(8),
                                sortOrder = cursor.getInt(9),
                                collectionId = cursor.nullableString(10),
                            ),
                        )
                    }
                }
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun SupportSQLiteDatabase.insert(book: BookRow) {
        execSQL(
            """
            INSERT INTO books (
                id, title, author, format, coverUrl, downloadStatus, localUri,
                lastReadAt, collectionName, sortOrder
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                book.id,
                book.title,
                book.author,
                book.format,
                book.coverUrl,
                book.downloadStatus,
                book.localUri,
                book.lastReadAt,
                book.collectionName,
                book.sortOrder,
            ),
        )
    }

    private fun android.database.Cursor.nullableString(columnIndex: Int): String? =
        if (isNull(columnIndex)) null else getString(columnIndex)

    private fun android.database.Cursor.nullableLong(columnIndex: Int): Long? =
        if (isNull(columnIndex)) null else getLong(columnIndex)

    private data class BookRow(
        val id: String,
        val title: String,
        val author: String,
        val format: String,
        val coverUrl: String?,
        val downloadStatus: String,
        val localUri: String?,
        val lastReadAt: Long?,
        val collectionName: String?,
        val sortOrder: Int,
        val collectionId: String? = null,
    )

    private companion object {
        const val CREATE_BOOKS_V4 = """
            CREATE TABLE IF NOT EXISTS books (
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                format TEXT NOT NULL,
                coverUrl TEXT,
                downloadStatus TEXT NOT NULL,
                localUri TEXT,
                lastReadAt INTEGER,
                collectionName TEXT,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id)
            )
        """
    }
}
