package dev.readflow.core.database

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinReadsBackupExporterTest {

    private val directTransactionRunner = BackupRestoreTransactionRunner { restore -> restore() }

    @Test
    fun exportsProgressBookmarksAndTextAnnotationsAsManifestZip() = runTest {
        val output = ByteArrayOutputStream()
        val progress = FakeProgressDao(
            ReadingProgressEntity(
                bookId = "book-1",
                locatorJson = """{"type":"byte","offset":128}""",
                totalProgression = 0.42f,
                progressPercent = 42f,
                updatedAt = 1_700_000_000,
                deviceId = "device-a",
            ),
        )
        val bookmarks = FakeBookmarkDao(
            BookmarkEntity(
                id = "bookmark-1",
                bookId = "book-1",
                totalProgression = 0.4f,
                locatorJson = """{"type":"byte","offset":120}""",
                text = "书签 40%",
                createdAt = 1_699_999_900,
                updatedAt = 1_700_000_001,
                deviceId = "device-a",
                isDeleted = true,
            ),
        )
        val annotations = FakeTextAnnotationDao(
            TextAnnotationEntity(
                id = "annotation-1",
                bookId = "book-1",
                totalProgression = 0.41f,
                anchorType = "text-selection-range",
                anchorJson = """{"start":1,"end":9}""",
                selectedText = "Readflow",
                note = "important",
                color = 0x67000000,
                createdAt = 1_699_999_800,
                updatedAt = 1_700_000_002,
                deviceId = "device-a",
                isDeleted = false,
            ),
        )
        val exporter = LinReadsBackupExporter(progress, bookmarks, annotations)

        val result = exporter.export(output)

        assertEquals(1, result.progressCount)
        assertEquals(1, result.bookmarkCount)
        assertEquals(1, result.textAnnotationCount)
        val manifestJson = output.toByteArray().zipEntry("manifest.json")
        assertNotNull(manifestJson)
        val manifest = Json.parseToJsonElement(manifestJson!!).jsonObject
        assertEquals("linreads-backup", manifest.getValue("format").jsonPrimitive.content)
        assertEquals("1", manifest.getValue("schema_version").jsonPrimitive.content)
        assertEquals(
            "book-1",
            manifest.getValue("reading_progress").jsonArray.single().jsonObject.getValue("book_id").jsonPrimitive.content,
        )
        assertEquals(
            "bookmark-1",
            manifest.getValue("bookmarks").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content,
        )
        assertEquals(
            "annotation-1",
            manifest.getValue("text_annotations").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content,
        )
    }

    @Test
    fun exportRejectsManifestLargerThanRestoreLimit() = runTest {
        val output = ByteArrayOutputStream()
        val exporter = LinReadsBackupExporter(
            progressDao = FakeProgressDao(),
            bookmarkDao = FakeBookmarkDao(),
            textAnnotationDao = FakeTextAnnotationDao(
                TextAnnotationEntity(
                    id = "oversized-annotation",
                    bookId = "book-1",
                    totalProgression = 0.5f,
                    anchorType = "text-selection-range",
                    anchorJson = "{}",
                    selectedText = "x".repeat(16 * 1024 * 1024 + 1),
                    note = null,
                    color = 0,
                    createdAt = 1,
                    updatedAt = 1,
                    deviceId = "device-a",
                    isDeleted = false,
                ),
            ),
        )

        val exportAttempt = runCatching { exporter.export(output) }

        assertTrue(
            "export must fail instead of reporting success for an unrestorable manifest",
            exportAttempt.isFailure,
        )
        assertEquals("备份 manifest.json 过大", exportAttempt.exceptionOrNull()?.message)
        assertEquals("oversized export must not write a partial ZIP", 0, output.size())
    }

    @Test
    fun restoresBackupWithoutOverwritingNewerLocalReadingData() = runTest {
        val backupBytes = ByteArrayOutputStream().also { output ->
            LinReadsBackupExporter(
                progressDao = FakeProgressDao(
                    ReadingProgressEntity(
                        bookId = "new-book",
                        locatorJson = """{"type":"byte","offset":128}""",
                        totalProgression = 0.42f,
                        progressPercent = 42f,
                        updatedAt = 300,
                        deviceId = "backup-device",
                    ),
                    ReadingProgressEntity(
                        bookId = "existing-book",
                        locatorJson = """{"type":"byte","offset":10}""",
                        totalProgression = 0.1f,
                        progressPercent = 10f,
                        updatedAt = 100,
                        deviceId = "old-device",
                    ),
                ),
                bookmarkDao = FakeBookmarkDao(
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
                ),
                textAnnotationDao = FakeTextAnnotationDao(
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
                ),
            ).export(output)
        }.toByteArray()
        val localProgress = FakeProgressDao(
            ReadingProgressEntity(
                bookId = "existing-book",
                locatorJson = """{"type":"byte","offset":999}""",
                totalProgression = 0.9f,
                progressPercent = 90f,
                updatedAt = 400,
                deviceId = "local-device",
            ),
        )
        val localBookmarks = FakeBookmarkDao(
            BookmarkEntity(
                id = "existing-bookmark",
                bookId = "existing-book",
                totalProgression = 0.9f,
                locatorJson = """{"type":"byte","offset":999}""",
                text = "newer local bookmark",
                createdAt = 80,
                updatedAt = 400,
                deviceId = "local-device",
            ),
        )
        val localAnnotations = FakeTextAnnotationDao()
        val restorer = LinReadsBackupRestorer(
            localProgress,
            localBookmarks,
            localAnnotations,
            directTransactionRunner,
        )

        val result = restorer.restore(ByteArrayInputStream(backupBytes))

        assertEquals(1, result.progressCount)
        assertEquals(1, result.bookmarkCount)
        assertEquals(1, result.textAnnotationCount)
        assertEquals(300L, localProgress.row("new-book")?.updatedAt)
        assertEquals(400L, localProgress.row("existing-book")?.updatedAt)
        assertEquals(true, localBookmarks.row("new-bookmark")?.isDeleted)
        assertEquals("newer local bookmark", localBookmarks.row("existing-bookmark")?.text)
        assertEquals("restored", localAnnotations.row("new-annotation")?.note)
    }

    @Test
    fun restoreRunsAllDatabaseWritesInsideOneTransactionBoundary() = runTest {
        val backupBytes = ByteArrayOutputStream().also { output ->
            LinReadsBackupExporter(
                progressDao = FakeProgressDao(
                    ReadingProgressEntity(
                        bookId = "book-1",
                        locatorJson = """{"type":"byte","offset":128}""",
                        totalProgression = 0.42f,
                        progressPercent = 42f,
                        updatedAt = 300,
                        deviceId = "backup-device",
                    ),
                ),
                bookmarkDao = FakeBookmarkDao(),
                textAnnotationDao = FakeTextAnnotationDao(),
            ).export(output)
        }.toByteArray()
        var transactionCount = 0
        val restorer = LinReadsBackupRestorer(
            progressDao = FakeProgressDao(),
            bookmarkDao = FakeBookmarkDao(),
            textAnnotationDao = FakeTextAnnotationDao(),
            transactionRunner = BackupRestoreTransactionRunner { restore ->
                transactionCount += 1
                restore()
            },
        )

        val result = restorer.restore(ByteArrayInputStream(backupBytes))

        assertEquals(1, transactionCount)
        assertEquals(1, result.progressCount)
    }

    @Test
    fun restoreRejectsSchemaVersionsBeforeOneWithoutMutatingLocalData() = runTest {
        val backupBytes = manifestZip(
            """
            {
              "format": "linreads-backup",
              "schema_version": 0,
              "exported_at": 1,
              "reading_progress": [
                {
                  "book_id": "schema-zero-book",
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
            """.trimIndent(),
        )
        val localProgress = FakeProgressDao()
        val restorer = LinReadsBackupRestorer(
            localProgress,
            FakeBookmarkDao(),
            FakeTextAnnotationDao(),
            directTransactionRunner,
        )

        val error = runCatching {
            restorer.restore(ByteArrayInputStream(backupBytes))
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("不支持的备份版本：0", error?.message)
        assertEquals(null, localProgress.row("schema-zero-book"))
    }

    @Test
    fun restoreRejectsMissingSchemaVersionWithoutMutatingLocalData() = runTest {
        val backupBytes = manifestZip(
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
            """.trimIndent(),
        )
        val localProgress = FakeProgressDao()
        val restorer = LinReadsBackupRestorer(
            localProgress,
            FakeBookmarkDao(),
            FakeTextAnnotationDao(),
            directTransactionRunner,
        )

        val error = runCatching {
            restorer.restore(ByteArrayInputStream(backupBytes))
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(true, error?.message?.contains("schema_version"))
        assertEquals(null, localProgress.row("missing-schema-book"))
    }

    @Test
    fun restoreRejectsCompressedEntryBeforeManifestWithoutMutatingLocalData() = runTest {
        val manifestJson =
            """
            {
              "format": "linreads-backup",
              "schema_version": 1,
              "exported_at": 1,
              "reading_progress": [
                {
                  "book_id": "preceded-manifest-book",
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
            """.trimIndent()
        val backupBytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("compressed-bomb.bin"))
                val zeroes = ByteArray(DEFAULT_BUFFER_SIZE)
                repeat((16 * 1024 * 1024) / zeroes.size + 1) {
                    zip.write(zeroes)
                }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson.encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val localProgress = FakeProgressDao()
        val restorer = LinReadsBackupRestorer(
            localProgress,
            FakeBookmarkDao(),
            FakeTextAnnotationDao(),
            directTransactionRunner,
        )

        val error = runCatching {
            restorer.restore(ByteArrayInputStream(backupBytes))
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("备份首个条目必须是 manifest.json", error?.message)
        assertEquals(null, localProgress.row("preceded-manifest-book"))
    }

    private fun manifestZip(manifestJson: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson.encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun ByteArray.zipEntry(name: String): String? {
        ZipInputStream(ByteArrayInputStream(this)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == name) {
                    return zip.readBytes().decodeToString()
                }
            }
        }
    }

    private class FakeProgressDao(
        vararg rows: ReadingProgressEntity,
    ) : ReadingProgressDao {
        private val rows = rows.associateByTo(linkedMapOf()) { it.bookId }

        override suspend fun get(bookId: String): ReadingProgressEntity? = rows[bookId]
        override suspend fun allForBackup(): List<ReadingProgressEntity> = rows.values.toList()
        override suspend fun upsert(progress: ReadingProgressEntity) {
            rows[progress.bookId] = progress
        }
        fun row(bookId: String): ReadingProgressEntity? = rows[bookId]
    }

    private class FakeBookmarkDao(
        vararg rows: BookmarkEntity,
    ) : BookmarkDao {
        private val rows = rows.associateByTo(linkedMapOf()) { it.id }

        override fun observeForBook(bookId: String): Flow<List<BookmarkEntity>> =
            MutableStateFlow(rows.values.filter { it.bookId == bookId && !it.isDeleted })

        override suspend fun getById(id: String): BookmarkEntity? = rows[id]
        override suspend fun allForBackup(): List<BookmarkEntity> = rows.values.toList()
        override suspend fun upsert(bookmark: BookmarkEntity) {
            rows[bookmark.id] = bookmark
        }
        override suspend fun markDeleted(id: String, updatedAt: Long, deviceId: String) = Unit
        fun row(id: String): BookmarkEntity? = rows[id]
    }

    private class FakeTextAnnotationDao(
        vararg rows: TextAnnotationEntity,
    ) : TextAnnotationDao {
        private val rows = rows.associateByTo(linkedMapOf()) { it.id }

        override fun observeForBook(bookId: String): Flow<List<TextAnnotationEntity>> =
            MutableStateFlow(rows.values.filter { it.bookId == bookId && !it.isDeleted })

        override suspend fun getById(id: String): TextAnnotationEntity? = rows[id]
        override suspend fun allForBackup(): List<TextAnnotationEntity> = rows.values.toList()
        override suspend fun upsert(annotation: TextAnnotationEntity) {
            rows[annotation.id] = annotation
        }
        fun row(id: String): TextAnnotationEntity? = rows[id]
    }
}
