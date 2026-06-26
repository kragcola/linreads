package dev.readflow.src08

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.database.LinReadsBackupExporter
import dev.readflow.core.database.LinReadsBackupRestorer
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.TextAnnotationEntity
import dev.readflow.core.model.DownloadStatus
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@LargeTest
@RunWith(AndroidJUnit4::class)
class BackupExportRestoreRuntimeSmokeTest {

    private val appContext = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var sourceDb: ReadflowDatabase
    private lateinit var targetDb: ReadflowDatabase

    @Before
    fun setUp() {
        deleteDatabase(SOURCE_DB_NAME)
        deleteDatabase(TARGET_DB_NAME)
        evidenceDir().mkdirs()
        sourceDb = openDatabase(SOURCE_DB_NAME)
        targetDb = openDatabase(TARGET_DB_NAME)
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    @Test
    fun exportsBackupManifestAndRestoresRuntimeDataRoundTrip() = runBlocking {
        seedSourceDatabase()
        seedTargetDatabase()

        val exportFile = File(evidenceDir(), EXPORT_ZIP_NAME)
        val exporter = LinReadsBackupExporter(
            sourceDb.readingProgressDao(),
            sourceDb.bookmarkDao(),
            sourceDb.textAnnotationDao(),
        )

        val exportResult = exportFile.outputStream().use { exporter.export(it) }

        assertEquals(2, exportResult.progressCount)
        assertEquals(2, exportResult.bookmarkCount)
        assertEquals(1, exportResult.textAnnotationCount)
        assertTrue(exportFile.isFile)
        assertTrue(exportFile.length() > 0L)

        val manifestJson = checkNotNull(exportFile.readZipEntry("manifest.json")) {
            "expected exported backup zip to contain manifest.json"
        }
        val manifest = JSONObject(manifestJson)
        assertEquals("linreads-backup", manifest.getString("format"))
        assertEquals(1, manifest.getInt("schema_version"))
        assertEquals(2, manifest.getJSONArray("reading_progress").length())
        assertEquals(2, manifest.getJSONArray("bookmarks").length())
        assertEquals(1, manifest.getJSONArray("text_annotations").length())
        assertEquals(0, manifest.getJSONArray("assets").length())

        val restorer = LinReadsBackupRestorer(
            targetDb.readingProgressDao(),
            targetDb.bookmarkDao(),
            targetDb.textAnnotationDao(),
        )

        val restoreResult = exportFile.inputStream().use { restorer.restore(it) }

        assertEquals(1, restoreResult.progressCount)
        assertEquals(1, restoreResult.bookmarkCount)
        assertEquals(1, restoreResult.textAnnotationCount)

        val newProgress = targetDb.readingProgressDao().get("new-book")
        assertNotNull(newProgress)
        assertEquals(300L, newProgress?.updatedAt)
        assertEquals("""{"type":"byte","offset":128}""", newProgress?.locatorJson)

        val existingProgress = targetDb.readingProgressDao().get("existing-book")
        assertNotNull(existingProgress)
        assertEquals(400L, existingProgress?.updatedAt)
        assertEquals("""{"type":"byte","offset":999}""", existingProgress?.locatorJson)

        val deletedBookmark = targetDb.bookmarkDao().getById("new-bookmark")
        assertNotNull(deletedBookmark)
        assertEquals(true, deletedBookmark?.isDeleted)

        val existingBookmark = targetDb.bookmarkDao().getById("existing-bookmark")
        assertNotNull(existingBookmark)
        assertEquals("newer local bookmark", existingBookmark?.text)
        assertEquals(400L, existingBookmark?.updatedAt)

        val restoredAnnotation = targetDb.textAnnotationDao().getById("new-annotation")
        assertNotNull(restoredAnnotation)
        assertEquals("restored", restoredAnnotation?.note)
        assertEquals("Readflow", restoredAnnotation?.selectedText)

        val shelfBook = targetDb.bookDao().getById("local-shelf-book")
        assertNotNull(shelfBook)
        assertEquals("Local Shelf Book", shelfBook?.title)
        assertEquals("file:///books/local-shelf-book.txt", shelfBook?.localUri)
    }

    @Test
    fun restoreRejectsMissingSchemaWithoutMutatingRuntimeDatabase() = runBlocking {
        seedTargetDatabase()

        val invalidZip = File(evidenceDir(), INVALID_ZIP_NAME)
        invalidZip.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(
                    """
                    {
                      "format": "linreads-backup",
                      "exported_at": 1,
                      "reading_progress": [
                        {
                          "book_id": "missing-schema-book",
                          "locator_json": "{\"type\":\"byte\",\"offset\":1}",
                          "total_progression": 0.1,
                          "progress_percent": 10.0,
                          "updated_at": 10,
                          "device_id": "backup-device"
                        }
                      ],
                      "bookmarks": [],
                      "text_annotations": [],
                      "assets": []
                    }
                    """.trimIndent().encodeToByteArray(),
                )
                zip.closeEntry()
            }
        }

        val restorer = LinReadsBackupRestorer(
            targetDb.readingProgressDao(),
            targetDb.bookmarkDao(),
            targetDb.textAnnotationDao(),
        )

        val error = runCatching {
            invalidZip.inputStream().use { restorer.restore(it) }
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error?.message?.contains("schema_version") == true)
        assertEquals(null, targetDb.readingProgressDao().get("missing-schema-book"))

        val shelfBook = targetDb.bookDao().getById("local-shelf-book")
        assertNotNull(shelfBook)
        assertEquals("Local Shelf Book", shelfBook?.title)
    }

    private suspend fun seedSourceDatabase() {
        sourceDb.readingProgressDao().upsert(
            ReadingProgressEntity(
                bookId = "new-book",
                locatorJson = """{"type":"byte","offset":128}""",
                totalProgression = 0.42f,
                progressPercent = 42f,
                updatedAt = 300,
                deviceId = "backup-device",
            ),
        )
        sourceDb.readingProgressDao().upsert(
            ReadingProgressEntity(
                bookId = "existing-book",
                locatorJson = """{"type":"byte","offset":10}""",
                totalProgression = 0.1f,
                progressPercent = 10f,
                updatedAt = 100,
                deviceId = "old-device",
            ),
        )
        sourceDb.bookmarkDao().upsert(
            BookmarkEntity(
                id = "new-bookmark",
                bookId = "new-book",
                totalProgression = 0.4f,
                locatorJson = """{"type":"byte","offset":120}""",
                text = "deleted remote bookmark",
                createdAt = 250,
                updatedAt = 350,
                deviceId = "backup-device",
                isDeleted = true,
            ),
        )
        sourceDb.bookmarkDao().upsert(
            BookmarkEntity(
                id = "existing-bookmark",
                bookId = "existing-book",
                totalProgression = 0.1f,
                locatorJson = """{"type":"byte","offset":10}""",
                text = "old bookmark",
                createdAt = 90,
                updatedAt = 100,
                deviceId = "old-device",
            ),
        )
        sourceDb.textAnnotationDao().upsert(
            TextAnnotationEntity(
                id = "new-annotation",
                bookId = "new-book",
                totalProgression = 0.41f,
                anchorType = "text-selection-range",
                anchorJson = """{"start":1,"end":9}""",
                selectedText = "Readflow",
                note = "restored",
                color = 0x67000000,
                createdAt = 240,
                updatedAt = 360,
                deviceId = "backup-device",
            ),
        )
    }

    private suspend fun seedTargetDatabase() {
        targetDb.bookDao().upsert(
            BookEntity(
                id = "local-shelf-book",
                title = "Local Shelf Book",
                author = "LinReads",
                format = "TXT",
                coverUrl = null,
                downloadStatus = DownloadStatus.DOWNLOADED.name,
                localUri = "file:///books/local-shelf-book.txt",
                lastReadAt = 1234L,
                collectionName = null,
                sortOrder = 0,
            ),
        )
        targetDb.readingProgressDao().upsert(
            ReadingProgressEntity(
                bookId = "existing-book",
                locatorJson = """{"type":"byte","offset":999}""",
                totalProgression = 0.9f,
                progressPercent = 90f,
                updatedAt = 400,
                deviceId = "local-device",
            ),
        )
        targetDb.bookmarkDao().upsert(
            BookmarkEntity(
                id = "existing-bookmark",
                bookId = "existing-book",
                totalProgression = 0.9f,
                locatorJson = """{"type":"byte","offset":999}""",
                text = "newer local bookmark",
                createdAt = 80,
                updatedAt = 400,
                deviceId = "local-device",
                isDeleted = false,
            ),
        )
    }

    private fun openDatabase(name: String): ReadflowDatabase =
        Room.databaseBuilder(appContext, ReadflowDatabase::class.java, name)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    private fun deleteDatabase(name: String) {
        appContext.deleteDatabase(name)
        File(appContext.getDatabasePath(name).absolutePath + "-wal").delete()
        File(appContext.getDatabasePath(name).absolutePath + "-shm").delete()
    }

    private fun evidenceDir(): File {
        val dir = File(appContext.cacheDir, EVIDENCE_DIR_NAME)
        dir.toPath().createDirectories()
        return dir
    }

    private fun File.readZipEntry(entryName: String): String? {
        ZipInputStream(inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name == entryName) {
                    return zip.readBytes().decodeToString()
                }
            }
        }
    }

    private companion object {
        const val SOURCE_DB_NAME = "backup-runtime-source.db"
        const val TARGET_DB_NAME = "backup-runtime-target.db"
        const val EVIDENCE_DIR_NAME = "backup-runtime-smoke"
        const val EXPORT_ZIP_NAME = "linreads-backup-runtime.zip"
        const val INVALID_ZIP_NAME = "linreads-backup-invalid-missing-schema.zip"
    }
}
