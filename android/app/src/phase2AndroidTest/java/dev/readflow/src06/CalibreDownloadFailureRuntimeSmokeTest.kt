package dev.readflow.src06

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
import dev.readflow.core.model.BookAssetOperationCoordinator
import dev.readflow.core.prefs.DataStoreSettingsRepository
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@LargeTest
@RunWith(AndroidJUnit4::class)
class CalibreDownloadFailureRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val calibreBaseUrl: String =
        arguments.getString(ARG_CALIBRE_BASE_URL) ?: DEFAULT_SERVER_BASE_URL

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
    fun partialDownloadFailureAfterSearchDoesNotCreateBrokenOfflineBook() {
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            waitForLibraryLoaded()
            connectCalibreThroughExplicitUrl()

            waitForObject(By.text("在线书库")).click()
            waitForObject(By.desc("书源选择器"))
            waitForObject(By.desc("搜索在线书库")).click()

            replaceSingleLineText(editableField("在线书库搜索词"), "smoke")
            waitForObject(By.desc("执行搜索")).click()
            waitForObject(By.text("Remote EPUB Smoke"))
            takeScreenshot("search-result-before-server-loss.png")

            enablePartialFakeCalibreDownload()
            waitForObject(By.desc("下载《Remote EPUB Smoke》")).click()
            val partialDownloadEvent = waitForFakeCalibreEvent(kind = "partial_download", bookId = 42)
            val errorText = waitForObject(By.descContains("在线书库错误："))
                .contentDescription
                .orEmpty()
                .removePrefix("在线书库错误：")
            assertTrue("download failure must expose a useful error", errorText.isNotBlank())
            waitForObject(By.desc("下载《Remote EPUB Smoke》"))
            takeScreenshot("download-failure-message.png")

            val failedBook = latestBookByTitle("Remote EPUB Smoke")
            val booksDir = File(appContext.filesDir, "books")
            val orphanFiles = booksDir.listFiles()
                ?.filter(File::isFile)
                .orEmpty()
            assertNull("failed download must not create a shelf row", failedBook)
            assertTrue("failed download must not leave a final or staging asset", orphanFiles.isEmpty())

            waitForObject(By.text("本地书架")).click()
            waitForLibraryLoaded()
            waitForObject(By.text("还没有书"))
            waitForObject(By.text("从在线书库下载，或导入本地文件"))
            takeScreenshot("shelf-empty-after-download-failure.png")

            writeTextEvidence(
                "download-failure-summary.txt",
                buildString {
                    appendLine("serverBaseUrl=$calibreBaseUrl")
                    appendLine("searchedTitle=Remote EPUB Smoke")
                    appendLine("partialDownloadEvent=$partialDownloadEvent")
                    appendLine("downloadError=$errorText")
                    appendLine("bookRowAfterFailure=${failedBook?.id}")
                    appendLine("orphanFilesAfterFailure=${orphanFiles.joinToString(",") { file -> file.name }}")
                    appendLine("allShelfEmptyState=还没有书")
                    appendLine("allShelfEmptyHint=从在线书库下载，或导入本地文件")
                },
            )
            copyDatabaseSnapshot("after-download-failure")
            shutdownFakeCalibreServer()
        }
    }

    private fun mainIntent() =
        Intent(Intent.ACTION_MAIN).apply {
            setClass(appContext, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun connectCalibreThroughExplicitUrl() {
        waitForObject(By.text("在线书库")).click()
        waitForObject(By.desc("管理书源")).click()
        waitForObject(By.text("添加书源")).click()
        waitForObject(By.text("Calibre")).click()
        waitForObject(By.text("Calibre 服务器地址"))
        replaceSingleLineText(editableField("书源地址输入"), calibreBaseUrl)
        waitForObject(By.text("保存").enabled(true)).click()
        waitForObject(By.desc("书源选择器"))
        waitForObject(By.text("Remote EPUB Smoke"))
    }

    private fun resetTargetAppState() {
        markSeedBooksAsAlreadyImported()
        runBlocking {
            val koin = GlobalContext.get()
            koin.get<BookAssetOperationCoordinator>().delete(TEST_RESET_OPERATION_ID) {
                koin.get<ReadflowDatabase>().clearAllTables()
                deleteRecursively(File(appContext.filesDir, "books"))
                deleteRecursively(File(appContext.filesDir, "covers"))
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

    private fun shutdownFakeCalibreServer() {
        runCatching {
            val connection = URL("$calibreBaseUrl/__shutdown__").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
        }
    }

    private fun enablePartialFakeCalibreDownload() {
        val connection = URL("$calibreBaseUrl/__download_mode__?mode=partial").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "failed to enable fake partial-download mode: HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
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
        error("Timed out waiting for fake Calibre event $kind/$bookId")
    }

    private fun fakeCalibreEventsJson(): String {
        val connection = URL("$calibreBaseUrl/__events__").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
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

    private fun writeTextEvidence(name: String, text: String) {
        File(evidenceDir(), name).writeText(text)
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
        checkNotNull(appContext.getExternalFilesDir("calibre-download-failure-runtime-smoke")) {
            "external files dir unavailable"
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
        const val ARG_CALIBRE_BASE_URL = "calibreBaseUrl"
        const val DEFAULT_SERVER_BASE_URL = "http://10.0.2.2:18081"
        const val SOURCE_CREDENTIAL_PREFERENCES = "source_credentials_v1"
        const val TEST_RESET_OPERATION_ID = "calibre-download-failure-smoke-reset"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
