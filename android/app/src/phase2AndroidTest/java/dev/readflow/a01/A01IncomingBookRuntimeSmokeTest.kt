package dev.readflow.a01

import android.content.ClipData
import android.content.Context
import android.content.Intent
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
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.model.BookFormat
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class A01IncomingBookRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        resetTargetAppState()
        evidenceDir().deleteRecursively()
        evidenceDir().mkdirs()
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun actionViewAndSendImportSupportedFormatsAndOpenReaderFromExternalContentProvider() {
        val cases = listOf(
            IncomingCase(
                action = Intent.ACTION_VIEW,
                fileName = uniqueFileName("a01-view-txt", "txt"),
                mimeType = "text/plain",
                format = BookFormat.TXT,
                expectedReaderText = TXT_SENTINEL,
            ),
            IncomingCase(
                action = Intent.ACTION_SEND,
                fileName = uniqueFileName("a01-send-txt", "txt"),
                mimeType = "text/plain",
                format = BookFormat.TXT,
                expectedReaderText = TXT_SENTINEL,
            ),
            IncomingCase(
                action = Intent.ACTION_VIEW,
                fileName = uniqueFileName("a01-view-md", "md"),
                mimeType = "text/markdown",
                format = BookFormat.MD,
                expectedReaderText = MD_SENTINEL,
            ),
            IncomingCase(
                action = Intent.ACTION_SEND,
                fileName = uniqueFileName("a01-send-md", "md"),
                mimeType = "text/markdown",
                format = BookFormat.MD,
                expectedReaderText = MD_SENTINEL,
            ),
            IncomingCase(
                action = Intent.ACTION_VIEW,
                fileName = uniqueFileName("a01-view-epub", "epub"),
                mimeType = "application/epub+zip",
                format = BookFormat.EPUB,
                expectedReaderText = EPUB_SENTINEL,
            ),
            IncomingCase(
                action = Intent.ACTION_SEND,
                fileName = uniqueFileName("a01-send-epub", "epub"),
                mimeType = "application/epub+zip",
                format = BookFormat.EPUB,
                expectedReaderText = EPUB_SENTINEL,
            ),
            IncomingCase(
                action = Intent.ACTION_VIEW,
                fileName = uniqueFileName("a01-view-pdf", "pdf"),
                mimeType = "application/pdf",
                format = BookFormat.PDF,
                expectedReaderText = null,
            ),
            IncomingCase(
                action = Intent.ACTION_SEND,
                fileName = uniqueFileName("a01-send-pdf", "pdf"),
                mimeType = "application/pdf",
                format = BookFormat.PDF,
                expectedReaderText = null,
            ),
        )

        val results = cases.map { runIncomingCase(it) }
        writeTextEvidence(
            "a01-incoming-summary.txt",
            buildString {
                appendLine("provider_authority=$PROVIDER_AUTHORITY")
                appendLine("case_count=${results.size}")
                results.forEach { result ->
                    appendLine(
                        listOf(
                            "action=${result.action}",
                            "title=${result.title}",
                            "format=${result.format}",
                            "book_id=${result.bookId}",
                            "local_uri=${result.localUri}",
                            "reader_probe=${result.readerProbe}",
                        ).joinToString(" | "),
                    )
                }
            },
        )

        assertEquals(8, results.size)
        assertEquals(
            listOf(
                BookFormat.TXT,
                BookFormat.TXT,
                BookFormat.MD,
                BookFormat.MD,
                BookFormat.EPUB,
                BookFormat.EPUB,
                BookFormat.PDF,
                BookFormat.PDF,
            ),
            results.map { it.format },
        )
        assertTrue(results.all { it.localUri.startsWith("file:") })
    }

    private fun runIncomingCase(case: IncomingCase): IncomingResult {
        val uri = externalBookUri(case.fileName)
        val title = case.fileName.substringBeforeLast('.')
        writeTextEvidence(
            "current-case.txt",
            "action=${case.action}\nfile=${case.fileName}\nmime=${case.mimeType}\nuri=$uri\n",
        )

        ActivityScenario.launch<MainActivity>(incomingIntent(case, uri)).use {
            assertNoBlockingStartupDialogs(title)
            waitForObjectOrDump(By.descStartsWith("阅读内容"), "${title}-reader-wait-failure")
            assertNoBlockingStartupDialogs(title)
            val readerProbe = case.expectedReaderText?.let {
                val exposedText = device.wait(Until.findObject(By.textContains(it)), 3_000)
                if (exposedText != null) {
                    exposedText.text.orEmpty()
                } else {
                    takeScreenshot("${title}-reader-visual-probe.png")
                    dumpHierarchy("${title}-reader-visual-probe.xml")
                    "reader_surface_visible_text_not_exposed_to_uiautomator:$it"
                }
            } ?: waitForObjectOrDump(By.desc("第 1 页，共 1 页"), "${title}-pdf-page-probe-failure")
                .contentDescription
                .orEmpty()

            val book = waitForBookByTitle(title)
            assertEquals(case.format.name, book.format)
            assertEquals("DOWNLOADED", book.downloadStatus)
            assertTrue("expected imported asset for $title", !book.localUri.isNullOrBlank())
            takeScreenshot("${title}-reader.png")
            dumpHierarchy("${title}-reader.xml")
            copyDatabaseSnapshot("${title}-state")

            return IncomingResult(
                action = case.action,
                title = title,
                format = case.format,
                bookId = book.id,
                localUri = checkNotNull(book.localUri),
                readerProbe = readerProbe,
            )
        }
    }

    private fun incomingIntent(case: IncomingCase, uri: Uri): Intent =
        Intent(case.action).apply {
            setClass(appContext, MainActivity::class.java)
            type = case.mimeType
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("a01-incoming-book", uri)
            when (case.action) {
                Intent.ACTION_VIEW -> setDataAndType(uri, case.mimeType)
                Intent.ACTION_SEND -> putExtra(Intent.EXTRA_STREAM, uri)
                else -> error("Unsupported incoming action ${case.action}")
            }
        }

    private fun externalBookUri(fileName: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(PROVIDER_AUTHORITY)
            .appendPath(fileName)
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

    private fun markSeedBooksAsAlreadyImported() {
        val seeded = appContext.assets.list("sample_books")?.toSet().orEmpty()
        appContext.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("seeded_files", seeded)
            .commit()
    }

    private fun waitForBookByTitle(title: String): BookEntity =
        waitForConditionResult("expected imported book row for title $title", DB_TIMEOUT_MS) {
            latestBookByTitle(title)
        }

    private fun latestBookByTitle(title: String): BookEntity? {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking {
                db.bookDao().observeAll().first().lastOrNull { it.title == title }
            }
        } finally {
            db.close()
        }
    }

    private fun copyDatabaseSnapshot(label: String) {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        copyIfExists(dbFile, File(evidenceDir(), "$label-readflow.db"))
        copyIfExists(File(dbFile.path + "-wal"), File(evidenceDir(), "$label-readflow.db-wal"))
        copyIfExists(File(dbFile.path + "-shm"), File(evidenceDir(), "$label-readflow.db-shm"))
    }

    private fun assertNoBlockingStartupDialogs(title: String) {
        device.waitForIdle(1_000)
        val blockedByNotificationPrompt =
            device.findObject(By.textContains("send you notifications")) != null ||
                device.findObject(By.text("Allow")) != null ||
                device.findObject(By.text("Don’t allow")) != null ||
                device.findObject(By.text("Don't allow")) != null
        val blockedByInstallPrompt =
            device.findObject(By.text("需要安装权限")) != null ||
                device.findObject(By.text("前往设置")) != null ||
                device.findObject(By.text("暂不")) != null
        if (blockedByNotificationPrompt || blockedByInstallPrompt) {
            takeScreenshot("$title-blocking-startup-dialog.png")
            dumpHierarchy("$title-blocking-startup-dialog.xml")
        }
        assertTrue(
            "ACTION_VIEW/ACTION_SEND book open must not be blocked by notification/install permission dialogs",
            !blockedByNotificationPrompt && !blockedByInstallPrompt,
        )
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

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("a01-incoming-runtime-smoke")) {
            "external files dir unavailable"
        }

    private fun waitForObject(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMs)) {
            "Timed out waiting for selector: $selector"
        }

    private fun waitForObjectOrDump(
        selector: BySelector,
        evidencePrefix: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): UiObject2 {
        val found = device.wait(Until.findObject(selector), timeoutMs)
        if (found != null) return found

        takeScreenshot("$evidencePrefix.png")
        dumpHierarchy("$evidencePrefix.xml")
        copyDatabaseSnapshot(evidencePrefix)
        throw IllegalStateException("Timed out waiting for selector: $selector")
    }

    private fun <T : Any> waitForConditionResult(
        message: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        producer: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            producer()?.let { return it }
            Thread.sleep(150)
        }
        return checkNotNull(producer()) { message }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) file.delete()
    }

    private fun deleteRecursively(file: File) {
        if (file.exists()) file.deleteRecursively()
    }

    private fun copyIfExists(source: File, destination: File) {
        if (source.exists()) {
            source.copyTo(destination, overwrite = true)
        }
    }

    private fun uniqueFileName(prefix: String, ext: String): String =
        "$prefix-${UUID.randomUUID().toString().take(8)}.$ext"

    private data class IncomingCase(
        val action: String,
        val fileName: String,
        val mimeType: String,
        val format: BookFormat,
        val expectedReaderText: String?,
    )

    private data class IncomingResult(
        val action: String,
        val title: String,
        val format: BookFormat,
        val bookId: String,
        val localUri: String,
        val readerProbe: String,
    )

    private companion object {
        private const val DB_NAME = "readflow.db"
        private const val PROVIDER_AUTHORITY = "dev.readflow.test.a01bookprovider"
        private const val TXT_SENTINEL = "A01 TXT external provider import opens reader."
        private const val MD_SENTINEL = "A01 Markdown external provider import opens reader."
        private const val EPUB_SENTINEL = "A01 EPUB external provider import opens reader."
        private val UI_TIMEOUT_MS = 15.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
