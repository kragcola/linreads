package dev.readflow.src08

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.readflow.MainActivity
import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.TextAnnotationEntity
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BackupSafUiRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() = runBlocking {
        resetTargetAppState()
        resetSafDocuments()
        evidenceDir().deleteRecursively()
        evidenceDir().mkdirs()
        seedBackupSourceRows()
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun settingsBackupExportAndRestoreUseSafDocumentIntentsRuntime() = runBlocking {
        val exportUri = safDocumentUri(EXPORT_FILE_NAME)

        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            openSettings()
            scrollToObject(By.text("导出备份"))

            val exportMonitor = addDocumentMonitor(Intent.ACTION_CREATE_DOCUMENT, "application/zip", exportUri)
            try {
                clickObject(By.text("导出备份"))
                waitForObject(By.text("已导出：进度 1 条，书签 1 条，标注 1 条"))
                waitForCondition("expected ACTION_CREATE_DOCUMENT monitor to be hit") {
                    exportMonitor.hits >= 1
                }
            } finally {
                instrumentation.removeMonitor(exportMonitor)
            }
            takeScreenshot("settings-export-success.png")
            dumpHierarchy("settings-export-success.xml")

            val exportedBytes = readSafDocumentBytes(exportUri)
            assertTrue("expected exported SAF zip to be non-empty", exportedBytes.isNotEmpty())
            File(evidenceDir(), EXPORT_FILE_NAME).writeBytes(exportedBytes)

            val manifest = JSONObject(checkNotNull(exportedBytes.readZipEntry("manifest.json")))
            assertEquals("linreads-backup", manifest.getString("format"))
            assertEquals(1, manifest.getInt("schema_version"))
            assertEquals(1, manifest.getJSONArray("reading_progress").length())
            assertEquals(1, manifest.getJSONArray("bookmarks").length())
            assertEquals(1, manifest.getJSONArray("text_annotations").length())

            overwriteRowsWithOlderLocalData()

            val restoreMonitor = addDocumentMonitor(Intent.ACTION_OPEN_DOCUMENT, "*/*", exportUri)
            try {
                clickObject(By.text("恢复备份"))
                waitForObject(By.text("已恢复：进度 1 条，书签 1 条，标注 1 条"))
                waitForCondition("expected ACTION_OPEN_DOCUMENT monitor to be hit") {
                    restoreMonitor.hits >= 1
                }
            } finally {
                instrumentation.removeMonitor(restoreMonitor)
            }
            takeScreenshot("settings-restore-success.png")
            dumpHierarchy("settings-restore-success.xml")

            val restored = readRestoredRows()
            assertEquals("""{"type":"byte","offset":128}""", restored.progress.locatorJson)
            assertEquals(300L, restored.progress.updatedAt)
            assertEquals("exported bookmark", restored.bookmark.text)
            assertEquals(true, restored.bookmark.isDeleted)
            assertEquals("restored note", restored.annotation.note)
            assertEquals("Readflow", restored.annotation.selectedText)

            copyDatabaseSnapshot("after-saf-restore")
            writeTextEvidence(
                "backup-saf-ui-summary.txt",
                buildString {
                    appendLine("export_intent_action=${Intent.ACTION_CREATE_DOCUMENT}")
                    appendLine("restore_intent_action=${Intent.ACTION_OPEN_DOCUMENT}")
                    appendLine("saf_uri=$exportUri")
                    appendLine("export_monitor_hits=${exportMonitor.hits}")
                    appendLine("restore_monitor_hits=${restoreMonitor.hits}")
                    appendLine("exported_zip_size=${exportedBytes.size}")
                    appendLine("manifest_format=${manifest.getString("format")}")
                    appendLine("manifest_schema=${manifest.getInt("schema_version")}")
                    appendLine("manifest_counts=progress:1,bookmarks:1,annotations:1")
                    appendLine("export_success=已导出：进度 1 条，书签 1 条，标注 1 条")
                    appendLine("restore_success=已恢复：进度 1 条，书签 1 条，标注 1 条")
                    appendLine("restored_progress=${restored.progress.locatorJson} updatedAt=${restored.progress.updatedAt}")
                    appendLine("restored_bookmark=${restored.bookmark.text} deleted=${restored.bookmark.isDeleted}")
                    appendLine("restored_annotation=${restored.annotation.selectedText} note=${restored.annotation.note}")
                    appendLine("evidence_boundary=AVD instrumentation intercepts SAF intents and returns a test ContentProvider Uri; no physical DocumentsUI/manual file picker")
                },
            )
        }
    }

    private fun mainIntent() =
        Intent(Intent.ACTION_MAIN).apply {
            setClass(appContext, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun addDocumentMonitor(
        action: String,
        mimeType: String,
        uri: Uri,
    ): Instrumentation.ActivityMonitor {
        val result = Intent().apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val filter = IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType(mimeType)
        }
        return instrumentation.addMonitor(
            filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, result),
            true,
        )
    }

    private suspend fun seedBackupSourceRows() {
        val db = openAppDatabase()
        try {
            db.readingProgressDao().upsert(
                ReadingProgressEntity(
                    bookId = BOOK_ID,
                    locatorJson = """{"type":"byte","offset":128}""",
                    totalProgression = 0.42f,
                    progressPercent = 42f,
                    updatedAt = 300,
                    deviceId = "backup-device",
                ),
            )
            db.bookmarkDao().upsert(
                BookmarkEntity(
                    id = BOOKMARK_ID,
                    bookId = BOOK_ID,
                    totalProgression = 0.4f,
                    locatorJson = """{"type":"byte","offset":120}""",
                    text = "exported bookmark",
                    createdAt = 250,
                    updatedAt = 350,
                    deviceId = "backup-device",
                    isDeleted = true,
                ),
            )
            db.textAnnotationDao().upsert(
                TextAnnotationEntity(
                    id = ANNOTATION_ID,
                    bookId = BOOK_ID,
                    totalProgression = 0.41f,
                    anchorType = "text-selection-range",
                    anchorJson = """{"start":1,"end":9}""",
                    selectedText = "Readflow",
                    note = "restored note",
                    color = 0x67000000,
                    createdAt = 240,
                    updatedAt = 360,
                    deviceId = "backup-device",
                ),
            )
        } finally {
            db.close()
        }
    }

    private suspend fun overwriteRowsWithOlderLocalData() {
        val db = openAppDatabase()
        try {
            db.readingProgressDao().upsert(
                ReadingProgressEntity(
                    bookId = BOOK_ID,
                    locatorJson = """{"type":"byte","offset":1}""",
                    totalProgression = 0.01f,
                    progressPercent = 1f,
                    updatedAt = 10,
                    deviceId = "local-old-device",
                ),
            )
            db.bookmarkDao().upsert(
                BookmarkEntity(
                    id = BOOKMARK_ID,
                    bookId = BOOK_ID,
                    totalProgression = 0.01f,
                    locatorJson = """{"type":"byte","offset":1}""",
                    text = "stale local bookmark",
                    createdAt = 9,
                    updatedAt = 10,
                    deviceId = "local-old-device",
                    isDeleted = false,
                ),
            )
            db.textAnnotationDao().upsert(
                TextAnnotationEntity(
                    id = ANNOTATION_ID,
                    bookId = BOOK_ID,
                    totalProgression = 0.01f,
                    anchorType = "text-selection-range",
                    anchorJson = """{"start":0,"end":1}""",
                    selectedText = "Old",
                    note = "stale note",
                    color = 0x12000000,
                    createdAt = 9,
                    updatedAt = 10,
                    deviceId = "local-old-device",
                ),
            )
        } finally {
            db.close()
        }
    }

    private suspend fun readRestoredRows(): RestoredRows {
        val db = openAppDatabase()
        return try {
            RestoredRows(
                progress = checkNotNull(db.readingProgressDao().get(BOOK_ID)),
                bookmark = checkNotNull(db.bookmarkDao().getById(BOOKMARK_ID)),
                annotation = checkNotNull(db.textAnnotationDao().getById(ANNOTATION_ID)),
            )
        } finally {
            db.close()
        }
    }

    private fun openAppDatabase(): ReadflowDatabase =
        Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

    private fun resetTargetAppState() {
        appContext.deleteDatabase(DB_NAME)
        deleteIfExists(appContext.getDatabasePath(DB_NAME))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-wal"))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-shm"))
        deleteRecursively(File(appContext.filesDir, "books"))
        deleteRecursively(File(appContext.filesDir, "covers"))
        deleteIfExists(File(appContext.filesDir, "datastore/readflow_settings.preferences_pb"))
        markSeedBooksAsAlreadyImported()
    }

    private fun resetSafDocuments() {
        deleteRecursively(File(testContext.filesDir, "saf-documents"))
    }

    private fun markSeedBooksAsAlreadyImported() {
        val seeded = appContext.assets.list("sample_books")?.toSet().orEmpty()
        appContext.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("seeded_files", seeded)
            .commit()
    }

    private fun openSettings() {
        waitForObject(By.text("书架"))
        clickObject(By.desc("设置"))
        waitForObject(By.text("设置"))
    }

    private fun clickObject(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) {
        waitForObject(selector, timeoutMs).click()
        device.waitForIdle()
    }

    private fun scrollToObject(selector: BySelector, maxSwipes: Int = 8): UiObject2 {
        repeat(maxSwipes + 1) { attempt ->
            device.wait(Until.findObject(selector), 750)?.let { return it }
            if (attempt < maxSwipes) {
                val centerX = device.displayWidth / 2
                val startY = (device.displayHeight * 3) / 4
                val endY = device.displayHeight / 3
                device.swipe(centerX, startY, centerX, endY, 24)
                device.waitForIdle()
            }
        }
        error("Timed out scrolling to selector: $selector")
    }

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun dumpHierarchy(name: String) {
        device.dumpWindowHierarchy(File(evidenceDir(), name))
    }

    private fun takeScreenshot(name: String) {
        device.takeScreenshot(File(evidenceDir(), name))
    }

    private fun writeTextEvidence(name: String, text: String) {
        File(evidenceDir(), name).writeText(text)
    }

    private fun copyDatabaseSnapshot(label: String) {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        copyIfExists(dbFile, File(evidenceDir(), "$label-readflow.db"))
        copyIfExists(File(dbFile.path + "-wal"), File(evidenceDir(), "$label-readflow.db-wal"))
        copyIfExists(File(dbFile.path + "-shm"), File(evidenceDir(), "$label-readflow.db-shm"))
    }

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("backup-saf-ui-runtime-smoke")) {
            "external files dir unavailable"
        }

    private fun safDocumentUri(fileName: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(SAF_PROVIDER_AUTHORITY)
            .appendPath(fileName)
            .build()

    private fun readSafDocumentBytes(uri: Uri): ByteArray =
        checkNotNull(testContext.contentResolver.openInputStream(uri)) {
            "Unable to open SAF document for reading: $uri"
        }.use { it.readBytes() }

    private fun ByteArray.readZipEntry(entryName: String): String? {
        ZipInputStream(inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name == entryName) {
                    return zip.readBytes().decodeToString()
                }
            }
        }
    }

    private fun waitForObject(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
            "Timed out waiting for selector: $selector"
        }

    private fun waitForCondition(
        message: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        check(condition()) { message }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) file.delete()
    }

    private fun deleteRecursively(file: File) {
        if (file.exists()) file.deleteRecursively()
    }

    private fun copyIfExists(source: File, destination: File) {
        if (source.exists()) {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }

    private data class RestoredRows(
        val progress: ReadingProgressEntity,
        val bookmark: BookmarkEntity,
        val annotation: TextAnnotationEntity,
    )

    private companion object {
        private const val DB_NAME = "readflow.db"
        private const val SAF_PROVIDER_AUTHORITY = "dev.readflow.test.safdocumentprovider"
        private const val EXPORT_FILE_NAME = "src08-src09-saf-ui-backup.zip"
        private const val BOOK_ID = "saf-ui-book"
        private const val BOOKMARK_ID = "saf-ui-bookmark"
        private const val ANNOTATION_ID = "saf-ui-annotation"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
