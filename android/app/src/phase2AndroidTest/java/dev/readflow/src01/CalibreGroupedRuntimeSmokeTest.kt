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
import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.prefs.DataStoreSettingsRepository
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
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
import org.koin.core.context.GlobalContext

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
    fun step01_onlineLibraryConfiguresCalibreAndLoadsCatalogWithoutSearch() {
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            waitForLibraryLoaded()

            resetFakeCalibreEvents()
            openCalibreSourceEditor()
            replaceSingleLineText(editableField("书源地址输入"), "10.0.2.2:8090")
            waitForObject(By.text("保存").enabled(true)).click()
            waitForObject(By.text("地址缺少协议，请以 http:// 或 https:// 开头"))

            replaceSingleLineText(editableField("书源地址输入"), calibreBaseUrl)
            saveCalibreSourceAndWaitForCatalog()
            val automaticCoverEvent = waitForFakeCalibreEvent(kind = "cover", bookId = 42)
            takeScreenshot("online-library-auto-catalog.png")
            writeTextEvidence(
                "online-library-setup-summary.txt",
                buildString {
                    appendLine("serverBaseUrl=$calibreBaseUrl")
                    appendLine("invalidAddress=10.0.2.2:8090")
                    appendLine("validationMessage=地址缺少协议，请以 http:// 或 https:// 开头")
                    appendLine("catalogLoadedWithoutSearch=true")
                    appendLine("catalogTitle=Remote EPUB Smoke")
                    appendLine("automaticCoverEvent=$automaticCoverEvent")
                },
            )
        }
    }

    @Test
    fun step02_downloadsRemoteEpubThenOpensOfflineAndRemovesDownloadedAsset() {
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            waitForLibraryLoaded()

            openCalibreSourceEditor()
            replaceSingleLineText(editableField("书源地址输入"), calibreBaseUrl)
            saveCalibreSourceAndWaitForCatalog()

            resetFakeCalibreEvents()
            waitForObject(By.desc("搜索在线书库")).click()

            replaceSingleLineText(editableField("在线书库搜索词"), "smoke")
            waitForObject(By.desc("执行搜索")).click()
            waitForObject(By.text("Remote EPUB Smoke"))
            val searchCoverDescription = waitForObject(By.descContains("Remote EPUB Smoke")).contentDescription
            dumpHierarchy("search-results.xml")
            takeScreenshot("search-results.png")
            waitForObject(By.desc("下载《Remote EPUB Smoke》")).click()
            val downloadEvent = waitForFakeCalibreEvent(kind = "download", bookId = 42)
            waitForObject(By.text("已下载《Remote EPUB Smoke》"))

            val downloadedBook = waitForBookRowByTitle("Remote EPUB Smoke") { book ->
                book.downloadStatus == "DOWNLOADED" && !book.localUri.isNullOrBlank()
            }
            assertTrue(
                downloadedBook.coverUrl?.startsWith("$calibreBaseUrl/get/cover/42/calibre-library") == true,
            )
            assertTrue(downloadedBook.coverUrl?.contains("__readflow_calibre_source=") == true)
            val downloadedFile = checkNotNull(downloadedBook.localUri).let(::fileFromUri)
            assertTrue("expected downloaded file to exist", downloadedFile.isFile)
            val downloadedFileExistsBeforeRemove = downloadedFile.isFile
            val calibreEventsBeforeOffline = fakeCalibreEventsJson()
            copyDatabaseSnapshot("downloaded-state")
            dumpHierarchy("search-downloaded.xml")
            takeScreenshot("search-downloaded.png")

            waitForObject(By.text("本地书架")).click()
            val shelfCard = waitForObject(By.desc("打开 Remote EPUB Smoke"))
            val shelfCardDescription = shelfCard.contentDescription
            dumpHierarchy("shelf-after-download.xml")

            shutdownFakeCalibreServer()

            waitForObject(By.desc("书架筛选，当前全部书籍")).click()
            waitForObject(By.text("离线可读")).click()
            waitForObject(By.desc("打开 Remote EPUB Smoke"))
            takeScreenshot("offline-filter.png")

            clickObjectCenter(waitForObject(By.desc("打开 Remote EPUB Smoke")))
            device.wait(
                Until.findObject(By.desc("阅读手势引导，点击开始阅读")),
                2_000,
            )?.click()
            waitForObject(By.descStartsWith("阅读内容"))
            waitForObject(By.textContains("Calibre smoke paragraph proves offline reader opening after download."))
            takeScreenshot("offline-reader-open.png")
            device.pressBack()
            waitForObject(By.desc("打开 Remote EPUB Smoke"))

            clickObjectCenter(waitForObject(By.desc("Remote EPUB Smoke 的菜单")))
            waitForObject(By.text("移除下载")).click()
            waitForObject(By.text("没有离线可读的书"))
            takeScreenshot("after-remove-download.png")

            val removedBook = waitForBookRow(downloadedBook.id) { book ->
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
        markSeedBooksAsAlreadyImported()
        runBlocking {
            val koin = GlobalContext.get()
            koin.get<BookAssetOperationCoordinator>().delete(TEST_RESET_OPERATION_ID) {
                koin.get<ReadflowDatabase>().clearAllTables()
                deleteRecursively(File(appContext.filesDir, "books"))
                deleteRecursively(File(appContext.filesDir, "covers"))
                deleteChildrenRecursively(appContext.cacheDir)
            }
            DataStoreSettingsRepository(appContext).clearCalibreBaseUrl()
        }
        check(
            appContext.getSharedPreferences(SOURCE_CREDENTIAL_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
        ) { "Failed to clear source credentials" }
        evidenceDir().deleteRecursively()
        evidenceDir().mkdirs()
    }

    private fun markSeedBooksAsAlreadyImported() {
        val seeded = appContext.assets.list("sample_books")?.toSet().orEmpty()
        appContext.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("seeded_files", seeded)
            .commit()
    }

    private fun replaceSingleLineText(selector: BySelector, value: String) {
        val editText = waitForObject(selector)
        editText.text = value
        waitForCondition("expected edit text to update to $value") {
            waitForObject(selector).text == value
        }
    }

    private fun editableField(description: String): BySelector =
        By.clazz("android.widget.EditText").hasDescendant(By.desc(description))

    private fun waitForLibraryLoaded() {
        waitForObject(By.text("书库"))
    }

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun openCalibreSourceEditor() {
        waitForObject(By.text("在线书库")).click()
        waitForObject(By.desc("管理书源")).click()
        val editCurrent = device.wait(Until.findObject(By.text("编辑当前书源")), 1_000)
        if (editCurrent != null) {
            editCurrent.click()
        } else {
            waitForObject(By.text("添加书源")).click()
            waitForObject(By.text("Calibre")).click()
        }
        waitForObject(By.text("Calibre 服务器地址"))
    }

    private fun saveCalibreSourceAndWaitForCatalog() {
        waitForObject(By.text("保存").enabled(true)).click()
        waitForObject(By.desc("书源选择器"))
        waitForObject(By.text("Remote EPUB Smoke"))
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

    private fun waitForBookRowByTitle(
        title: String,
        predicate: (BookEntity) -> Boolean,
    ): BookEntity {
        val deadline = System.currentTimeMillis() + DB_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            latestBookByTitle(title)?.takeIf(predicate)?.let { return it }
            Thread.sleep(250)
        }
        return checkNotNull(latestBookByTitle(title)) { "Timed out waiting for book titled $title" }
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

    private fun latestBookByTitle(title: String): BookEntity? {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking { db.bookDao().observeAll().first().firstOrNull { it.title == title } }
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
        const val DEFAULT_SERVER_BASE_URL = "http://10.0.2.2:8081"
        const val SOURCE_CREDENTIAL_PREFERENCES = "source_credentials_v1"
        const val TEST_RESET_OPERATION_ID = "calibre-runtime-smoke-reset"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 8.seconds.inWholeMilliseconds
        @Volatile private var hasInitializedProcessState = false
    }
}
