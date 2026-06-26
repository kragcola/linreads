package dev.readflow.src05

import android.content.Context
import android.content.Intent
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
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CalibreCacheLruRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        resetTargetAppState()
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun downloadsSixRemoteBooksAndEvictsOldestUnreadCacheEntry() {
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            waitForLibraryLoaded()
            connectCalibreThroughProbe()

            openCalibreSearchSheet()
            val downloaded101 = downloadRemoteBook("cache-01", "LRU Remote 01", "calibre-101")
            val downloaded102 = downloadRemoteBook("cache-02", "LRU Remote 02", "calibre-102")
            val downloaded103 = downloadRemoteBook("cache-03", "LRU Remote 03", "calibre-103")
            val downloaded104 = downloadRemoteBook("cache-04", "LRU Remote 04", "calibre-104")
            val downloaded105 = downloadRemoteBook("cache-05", "LRU Remote 05", "calibre-105")
            val evictedFile = checkNotNull(downloaded105.localUri).let(::fileFromUri)
            assertTrue("expected LRU candidate file to exist before trim", evictedFile.isFile)
            waitForObject(By.text("关闭")).click()

            val read101 = openBookAndCaptureLastReadAt(
                title = "LRU Remote 01",
                paragraph = "LRU runtime paragraph 01 proves cached remote reading after download.",
                screenshotName = "read-01.png",
            )
            val read102 = openBookAndCaptureLastReadAt(
                title = "LRU Remote 02",
                paragraph = "LRU runtime paragraph 02 proves cached remote reading after download.",
                screenshotName = "read-02.png",
            )
            val read103 = openBookAndCaptureLastReadAt(
                title = "LRU Remote 03",
                paragraph = "LRU runtime paragraph 03 proves cached remote reading after download.",
                screenshotName = "read-03.png",
            )
            val read104 = openBookAndCaptureLastReadAt(
                title = "LRU Remote 04",
                paragraph = "LRU runtime paragraph 04 proves cached remote reading after download.",
                screenshotName = "read-04.png",
            )
            assertTrue("expected read timestamps to increase", read101 < read102 && read102 < read103 && read103 < read104)

            openCalibreSearchSheet()
            val downloaded106 = downloadRemoteBook("cache-06", "LRU Remote 06", "calibre-106")
            assertNotNull(downloaded106.localUri)
            takeScreenshot("after-download-06.png")
            waitForObject(By.text("关闭")).click()

            val evictedBook = waitForBookRow("calibre-105") { book ->
                book.downloadStatus == NOT_DOWNLOADED && book.localUri == null
            }
            val protectedBook = waitForBookRow("calibre-106") { book ->
                book.downloadStatus == DOWNLOADED && !book.localUri.isNullOrBlank()
            }
            val kept101 = waitForBookRow("calibre-101") { book ->
                book.downloadStatus == DOWNLOADED && !book.localUri.isNullOrBlank()
            }
            val kept102 = waitForBookRow("calibre-102") { book ->
                book.downloadStatus == DOWNLOADED && !book.localUri.isNullOrBlank()
            }
            val kept103 = waitForBookRow("calibre-103") { book ->
                book.downloadStatus == DOWNLOADED && !book.localUri.isNullOrBlank()
            }
            val kept104 = waitForBookRow("calibre-104") { book ->
                book.downloadStatus == DOWNLOADED && !book.localUri.isNullOrBlank()
            }

            assertFalse("expected evicted file to be deleted", evictedFile.exists())
            listOf(kept101, kept102, kept103, kept104, protectedBook).forEach { book ->
                val file = checkNotNull(book.localUri).let(::fileFromUri)
                assertTrue("expected kept file to exist for ${book.id}", file.isFile)
            }

            copyDatabaseSnapshot("after-lru-trim")
            shutdownFakeCalibreServer()

            waitForObject(By.text("离线可读 5")).click()
            val protectedTitle = scrollToObject(By.text("LRU Remote 06"))
            takeScreenshot("offline-filter-after-trim.png")

            clickObjectCenter(protectedTitle)
            waitForObject(By.desc("阅读内容，捏合调整字号"))
            waitForObject(By.text("LRU runtime paragraph 06 proves cached remote reading after download."))
            takeScreenshot("offline-open-protected.png")
            device.pressBack()
            waitForObject(By.text("书架"))

            writeTextEvidence(
                "lru-summary.txt",
                buildString {
                    appendLine("serverBaseUrl=$SERVER_BASE_URL")
                    appendLine("downloadedBeforeTrim=calibre-101,calibre-102,calibre-103,calibre-104,calibre-105,calibre-106")
                    appendLine("readOrder=calibre-101<$read101,calibre-102<$read102,calibre-103<$read103,calibre-104<$read104")
                    appendLine("evictedBookId=${evictedBook.id}")
                    appendLine("evictedStatus=${evictedBook.downloadStatus}")
                    appendLine("evictedLocalUri=${evictedBook.localUri}")
                    appendLine("evictedFileExistsAfterTrim=${evictedFile.exists()}")
                    appendLine("protectedBookId=${protectedBook.id}")
                    appendLine("protectedStatus=${protectedBook.downloadStatus}")
                    appendLine("protectedLocalUri=${protectedBook.localUri}")
                    appendLine("keptBooks=${listOf(kept101, kept102, kept103, kept104, protectedBook).joinToString(",") { it.id }}")
                    appendLine("offlineFilterLabel=离线可读 5")
                    appendLine("offlineProtectedParagraph=LRU runtime paragraph 06 proves cached remote reading after download.")
                },
            )
        }
    }

    private fun connectCalibreThroughProbe() {
        waitForObject(By.desc("设置")).click()
        waitForObject(By.text("Calibre 服务器地址"))
        replaceSingleLineText(HOST_ONLY_HINT)
        waitForObject(By.text("探测常用端口")).click()
        waitForObject(By.text("已发现 Calibre：10.0.2.2:8081，发现 1 本书"))
        device.pressBack()
        waitForLibraryLoaded()
    }

    private fun openCalibreSearchSheet() {
        waitForObject(By.desc("导入书籍")).click()
        waitForObject(By.text("Calibre 搜索")).click()
        waitForObject(By.text("Calibre 书源"))
    }

    private fun downloadRemoteBook(
        query: String,
        title: String,
        bookId: String,
    ): BookEntity {
        replaceSingleLineText(query)
        waitForObject(By.text("搜索")).click()
        waitForObject(By.text(title))
        waitForObject(By.text("下载")).click()
        waitForObject(By.text("已下载《$title》"))
        return waitForBookRow(bookId) { book ->
            book.downloadStatus == DOWNLOADED && !book.localUri.isNullOrBlank()
        }
    }

    private fun openBookAndCaptureLastReadAt(
        title: String,
        paragraph: String,
        screenshotName: String,
    ): Long {
        val bookId = titleToBookId(title)
        clickObjectCenter(waitForObject(By.text(title)))
        waitForObject(By.desc("阅读内容，捏合调整字号"))
        waitForObject(By.text(paragraph))
        takeScreenshot(screenshotName)
        waitForCondition("expected progress persistence for $bookId") {
            latestBook(bookId)?.lastReadAt != null && latestProgressUpdatedAt(bookId) != null
        }
        device.pressBack()
        waitForLibraryLoaded()
        val book = waitForBookRow(bookId) { row -> row.lastReadAt != null }
        return checkNotNull(book.lastReadAt)
    }

    private fun titleToBookId(title: String): String = when (title) {
        "LRU Remote 01" -> "calibre-101"
        "LRU Remote 02" -> "calibre-102"
        "LRU Remote 03" -> "calibre-103"
        "LRU Remote 04" -> "calibre-104"
        "LRU Remote 05" -> "calibre-105"
        "LRU Remote 06" -> "calibre-106"
        else -> error("Unknown title $title")
    }

    private fun mainIntent() =
        Intent(Intent.ACTION_MAIN).apply {
            setClass(appContext, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun resetTargetAppState() {
        appContext.deleteDatabase(DB_NAME)
        deleteIfExists(appContext.getDatabasePath(DB_NAME))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-wal"))
        deleteIfExists(File(appContext.getDatabasePath(DB_NAME).path + "-shm"))
        deleteRecursively(File(appContext.filesDir, "books"))
        deleteRecursively(File(appContext.filesDir, "covers"))
        deleteIfExists(File(appContext.filesDir, "datastore/readflow_settings.preferences_pb"))
        evidenceDir().deleteRecursively()
        evidenceDir().mkdirs()
        markSeedBooksAsAlreadyImported()
    }

    private fun markSeedBooksAsAlreadyImported() {
        val seeded = appContext.assets.list("sample_books")?.toSet().orEmpty()
        appContext.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("seeded_files", seeded)
            .commit()
    }

    private fun replaceSingleLineText(value: String) {
        val editText = waitForObject(By.clazz("android.widget.EditText"))
        editText.text = value
        waitForCondition("expected edit text to update to $value") {
            waitForObject(By.clazz("android.widget.EditText")).text == value
        }
    }

    private fun waitForLibraryLoaded() {
        waitForObject(By.text("书架"))
    }

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun waitForBookRow(
        bookId: String,
        predicate: (BookEntity) -> Boolean,
    ): BookEntity {
        val deadline = System.currentTimeMillis() + DB_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            latestBook(bookId)?.takeIf(predicate)?.let { return it }
            Thread.sleep(250)
        }
        return checkNotNull(latestBook(bookId)) { "Timed out waiting for book row $bookId" }
    }

    private fun latestBook(bookId: String): BookEntity? {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking { db.bookDao().getById(bookId) }
        } finally {
            db.close()
        }
    }

    private fun latestProgressUpdatedAt(bookId: String): Long? {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking { db.readingProgressDao().get(bookId)?.updatedAt }
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

    private fun takeScreenshot(name: String) {
        val screenshot = File(evidenceDir(), name)
        device.takeScreenshot(screenshot)
    }

    private fun writeTextEvidence(name: String, text: String) {
        File(evidenceDir(), name).writeText(text)
    }

    private fun clickObjectCenter(target: UiObject2) {
        val bounds = target.visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        device.waitForIdle()
    }

    private fun scrollToObject(selector: BySelector, maxSwipes: Int = 6): UiObject2 {
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

    private fun shutdownFakeCalibreServer() {
        runCatching {
            val connection = URL("$SERVER_BASE_URL/__shutdown__").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
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

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("calibre-lru-runtime-smoke")) {
            "external files dir unavailable"
        }

    private fun fileFromUri(localUri: String): File {
        val uri = URI(localUri)
        check(uri.scheme == "file") { "expected file URI, got $localUri" }
        return File(uri)
    }

    private fun deleteIfExists(file: File) {
        if (file.exists() && !file.delete()) {
            throw IOException("Failed to delete ${file.absolutePath}")
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.exists() && !file.deleteRecursively()) {
            throw IOException("Failed to delete ${file.absolutePath}")
        }
    }

    private fun copyIfExists(source: File, destination: File) {
        if (!source.exists()) return
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    private companion object {
        const val DB_NAME = "readflow.db"
        const val HOST_ONLY_HINT = "10.0.2.2"
        const val SERVER_BASE_URL = "http://10.0.2.2:8081"
        const val DOWNLOADED = "DOWNLOADED"
        const val NOT_DOWNLOADED = "NOT_DOWNLOADED"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 8.seconds.inWholeMilliseconds
    }
}
