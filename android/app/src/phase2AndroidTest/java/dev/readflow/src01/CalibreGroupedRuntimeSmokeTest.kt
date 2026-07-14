package dev.readflow.src01

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
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@LargeTest
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CalibreGroupedRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val calibreBaseUrl: String =
        arguments.getString(ARG_CALIBRE_BASE_URL) ?: DEFAULT_SERVER_BASE_URL
    private val calibreHostHint: String =
        arguments.getString(ARG_CALIBRE_HOST_HINT) ?: DEFAULT_HOST_ONLY_HINT
    private val calibreUsesDefaultProbe: Boolean =
        calibreBaseUrl == DEFAULT_SERVER_BASE_URL && calibreHostHint == DEFAULT_HOST_ONLY_HINT
    private val calibreDisplayAddress: String =
        calibreBaseUrl.removePrefix("http://").removePrefix("https://")

    @Before
    fun setUp() {
        if (!hasInitializedProcessState) {
            resetTargetAppState()
            hasInitializedProcessState = true
        }
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun step01_settingsShowsFailureGuidanceThenProbeFindsHostCalibre() {
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            waitForLibraryLoaded()

            waitForObject(By.desc("设置")).click()
            waitForObject(By.text("Calibre 服务器地址"))

            replaceSingleLineText("http://10.0.2.2:8090")
            waitForObject(By.text("测试连接")).click()
            waitForObject(By.text("无法连接到服务器"))
            waitForObject(By.text("确认手机和 Calibre 在同一局域网，并检查端口是否为 8080"))
            takeScreenshot("settings-failure.png")

            connectCalibre()
            takeScreenshot("settings-probe-success.png")
            writeTextEvidence(
                "settings-probe-summary.txt",
                buildString {
                    appendLine("serverBaseUrl=$calibreBaseUrl")
                    appendLine("failureUrl=http://10.0.2.2:8090")
                    appendLine("failureMessage=无法连接到服务器")
                    appendLine("failureNextStep=确认手机和 Calibre 在同一局域网，并检查端口是否为 8080")
                    appendLine("probeHint=$calibreHostHint")
                    appendLine("connectionMode=${if (calibreUsesDefaultProbe) "probe" else "explicit-url"}")
                    appendLine("connectionSuccess=${expectedConnectionSuccessText()}")
                },
            )
        }
    }

    @Test
    fun step02_downloadsRemoteEpubThenOpensOfflineAndRemovesDownloadedAsset() {
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            waitForLibraryLoaded()

            waitForObject(By.desc("设置")).click()
            waitForObject(By.text("Calibre 服务器地址"))
            connectCalibre()
            device.pressBack()
            waitForLibraryLoaded()

            resetFakeCalibreEvents()
            waitForObject(By.desc("导入书籍")).click()
            waitForObject(By.text("Calibre 搜索")).click()
            waitForObject(By.text("Calibre 书源"))

            replaceSingleLineText("smoke")
            waitForObject(By.text("搜索")).click()
            waitForObject(By.text("Remote EPUB Smoke"))
            val searchCoverDescription = waitForObject(By.desc("Remote EPUB Smoke 封面")).contentDescription
            val coverEvent = waitForFakeCalibreEvent(kind = "cover", bookId = 42)
            dumpHierarchy("search-results.xml")
            takeScreenshot("search-results.png")
            waitForObject(By.text("下载")).click()
            val downloadEvent = waitForFakeCalibreEvent(kind = "download", bookId = 42)
            waitForObject(By.text("已下载《Remote EPUB Smoke》"))

            val downloadedBook = waitForBookRow("calibre-42") { book ->
                book.downloadStatus == "DOWNLOADED" && !book.localUri.isNullOrBlank()
            }
            assertEquals("$calibreBaseUrl/get/cover/42/calibre-library", downloadedBook.coverUrl)
            val downloadedFile = checkNotNull(downloadedBook.localUri).let(::fileFromUri)
            assertTrue("expected downloaded file to exist", downloadedFile.isFile)
            val downloadedFileExistsBeforeRemove = downloadedFile.isFile
            val calibreEventsBeforeOffline = fakeCalibreEventsJson()
            copyDatabaseSnapshot("downloaded-state")
            dumpHierarchy("search-downloaded.xml")
            takeScreenshot("search-downloaded.png")

            waitForObject(By.text("关闭")).click()
            waitForObject(By.text("Remote EPUB Smoke"))
            val shelfCardDescription =
                waitForObject(By.desc("打开 Remote EPUB Smoke，Calibre")).contentDescription
            dumpHierarchy("shelf-after-download.xml")

            shutdownFakeCalibreServer()

            waitForObject(By.text("离线")).click()
            waitForObject(By.text("Remote EPUB Smoke"))
            takeScreenshot("offline-filter.png")

            clickObjectCenter(waitForObject(By.text("Remote EPUB Smoke")))
            waitForObject(By.desc("阅读内容，捏合调整字号"))
            waitForObject(By.text("Calibre smoke paragraph proves offline reader opening after download."))
            takeScreenshot("offline-reader-open.png")
            device.pressBack()
            waitForObject(By.text("Remote EPUB Smoke"))

            clickObjectCenter(waitForObject(By.desc("Remote EPUB Smoke 的菜单")))
            waitForObject(By.text("移除下载")).click()
            waitForObject(By.text("没有离线可读的书"))
            takeScreenshot("after-remove-download.png")

            val removedBook = waitForBookRow("calibre-42") { book ->
                book.downloadStatus == "NOT_DOWNLOADED" && book.localUri == null
            }
            assertNotNull(removedBook)
            assertTrue("expected downloaded file to be deleted", !downloadedFile.exists())
            copyDatabaseSnapshot("removed-state")
            writeTextEvidence(
                "download-offline-remove-summary.txt",
                buildString {
                    appendLine("serverBaseUrl=$calibreBaseUrl")
                    appendLine("searchCoverDescription=$searchCoverDescription")
                    appendLine("searchCoverEvent=$coverEvent")
                    appendLine("downloadEvent=$downloadEvent")
                    appendLine("downloadedCoverUrlBeforeRemove=${downloadedBook.coverUrl}")
                    appendLine("downloadedBookId=${downloadedBook.id}")
                    appendLine("downloadedTitle=${downloadedBook.title}")
                    appendLine("downloadedStatusBeforeRemove=${downloadedBook.downloadStatus}")
                    appendLine("downloadedLocalUriBeforeRemove=${downloadedBook.localUri}")
                    appendLine("downloadedFileExistsBeforeRemove=$downloadedFileExistsBeforeRemove")
                    appendLine("shelfCardDescription=$shelfCardDescription")
                    appendLine("fakeCalibreEventsBeforeOffline=$calibreEventsBeforeOffline")
                    appendLine("offlineOpenParagraph=Calibre smoke paragraph proves offline reader opening after download.")
                    appendLine("removedStatusAfterRemove=${removedBook.downloadStatus}")
                    appendLine("removedLocalUriAfterRemove=${removedBook.localUri}")
                    appendLine("downloadedFileExistsAfterRemove=${downloadedFile.exists()}")
                    appendLine("offlineEmptyState=没有离线可读的书")
                },
            )
        }
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
        deleteChildrenRecursively(appContext.cacheDir)
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

    private fun connectCalibre() {
        if (calibreUsesDefaultProbe) {
            replaceSingleLineText(calibreHostHint)
            waitForObject(By.text("探测常用端口")).click()
        } else {
            replaceSingleLineText(calibreBaseUrl)
            waitForObject(By.text("测试连接")).click()
        }
        waitForObject(By.text(expectedConnectionSuccessText()))
        waitForObject(By.text(expectedConnectionNextStepText()))
    }

    private fun expectedConnectionSuccessText(): String =
        if (calibreUsesDefaultProbe) {
            "已发现 Calibre：$calibreDisplayAddress，发现 1 本书"
        } else {
            "已连接到 Calibre，发现 1 本书"
        }

    private fun expectedConnectionNextStepText(): String =
        if (calibreUsesDefaultProbe) {
            "已保存该地址，返回书架后可以搜索并下载书籍"
        } else {
            "返回书架后可以搜索并下载书籍"
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

    private fun dumpHierarchy(name: String) {
        device.dumpWindowHierarchy(File(evidenceDir(), name))
    }

    private fun writeTextEvidence(name: String, text: String) {
        File(evidenceDir(), name).writeText(text)
    }

    private fun clickObjectCenter(target: UiObject2) {
        val bounds = target.visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
        device.waitForIdle()
    }

    private fun shutdownFakeCalibreServer() {
        runCatching {
            val connection = URL("$calibreBaseUrl/__shutdown__").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
        }
    }

    private fun resetFakeCalibreEvents() {
        val connection = URL("$calibreBaseUrl/__reset_events__").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
    }

    private fun waitForFakeCalibreEvent(kind: String, bookId: Int): String {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val events = JSONObject(fakeCalibreEventsJson()).getJSONArray("events")
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                if (event.optString("kind") == kind && event.optInt("book_id", -1) == bookId) {
                    return event.toString()
                }
            }
            Thread.sleep(100)
        }
        error("Timed out waiting for fake Calibre event kind=$kind bookId=$bookId")
    }

    private fun fakeCalibreEventsJson(): String {
        val connection = URL("$calibreBaseUrl/__events__").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
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
        checkNotNull(appContext.getExternalFilesDir("calibre-runtime-smoke")) {
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

    private fun deleteChildrenRecursively(directory: File) {
        directory.listFiles()?.forEach(::deleteRecursively)
    }

    private fun copyIfExists(source: File, destination: File) {
        if (!source.exists()) return
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    private companion object {
        const val DB_NAME = "readflow.db"
        const val ARG_CALIBRE_BASE_URL = "calibreBaseUrl"
        const val ARG_CALIBRE_HOST_HINT = "calibreHostHint"
        const val DEFAULT_HOST_ONLY_HINT = "10.0.2.2"
        const val DEFAULT_SERVER_BASE_URL = "http://10.0.2.2:8081"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 8.seconds.inWholeMilliseconds
        @Volatile private var hasInitializedProcessState = false
    }
}
