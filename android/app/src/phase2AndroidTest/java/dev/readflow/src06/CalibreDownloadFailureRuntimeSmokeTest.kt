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
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
    fun downloadFailureAfterSearchDoesNotCreateBrokenOfflineBook() {
        ActivityScenario.launch<MainActivity>(mainIntent()).use {
            dismissBlockingDialogs()
            waitForLibraryLoaded()
            connectCalibreThroughExplicitUrl()

            waitForObject(By.desc("导入书籍")).click()
            waitForObject(By.text("在线书库")).click()
            waitForObject(By.text("书源"))

            replaceSingleLineText("smoke")
            waitForObject(By.text("搜索")).click()
            waitForObject(By.text("Remote EPUB Smoke"))
            takeScreenshot("search-result-before-server-loss.png")

            shutdownFakeCalibreServer()
            waitForCalibreServerUnavailable()
            waitForObject(By.text("下载")).click()
            val errorText = waitForObject(By.descContains("在线书库错误："))
                .contentDescription
                .orEmpty()
                .removePrefix("在线书库错误：")
            assertTrue("download failure must expose a useful error", errorText.isNotBlank())
            waitForObject(By.text("下载"))
            takeScreenshot("download-failure-message.png")

            val failedBook = latestBook("calibre-42")
            val booksDir = File(appContext.filesDir, "books")
            val orphanFiles = booksDir.listFiles()
                ?.filter { it.name.startsWith("calibre-42.") }
                .orEmpty()
            assertNull("failed download must not create a shelf row", failedBook)
            assertTrue("failed download must not leave a partial calibre-42 file", orphanFiles.isEmpty())

            device.pressBack()
            waitForLibraryLoaded()
            waitForObject(By.text("还没有书"))
            waitForObject(By.text("从在线书库下载，或导入本地文件"))
            takeScreenshot("shelf-empty-after-download-failure.png")

            writeTextEvidence(
                "download-failure-summary.txt",
                buildString {
                    appendLine("serverBaseUrl=$calibreBaseUrl")
                    appendLine("searchedTitle=Remote EPUB Smoke")
                    appendLine("serverUnavailableBeforeDownload=true")
                    appendLine("downloadError=$errorText")
                    appendLine("bookRowAfterFailure=${failedBook?.id}")
                    appendLine("orphanFilesAfterFailure=${orphanFiles.joinToString(",") { file -> file.name }}")
                    appendLine("allShelfEmptyState=还没有书")
                    appendLine("allShelfEmptyHint=从在线书库下载，或导入本地文件")
                },
            )
            copyDatabaseSnapshot("after-download-failure")
        }
    }

    private fun mainIntent() =
        Intent(Intent.ACTION_MAIN).apply {
            setClass(appContext, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun connectCalibreThroughExplicitUrl() {
        waitForObject(By.desc("设置")).click()
        waitForObject(By.text("Calibre 服务器地址"))
        replaceSingleLineText(calibreBaseUrl)
        waitForObject(By.text("测试连接")).click()
        waitForObject(By.text("已连接到 Calibre，发现 1 本书"))
        device.pressBack()
        waitForLibraryLoaded()
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

    private fun shutdownFakeCalibreServer() {
        runCatching {
            val connection = URL("$calibreBaseUrl/__shutdown__").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
        }
    }

    private fun waitForCalibreServerUnavailable() {
        waitForCondition("expected fake Calibre server to be unavailable") {
            runCatching {
                val connection = URL("$calibreBaseUrl/ajax/search?query=&num=1").openConnection() as HttpURLConnection
                connection.connectTimeout = 250
                connection.readTimeout = 250
                connection.inputStream.use { it.readBytes() }
                connection.disconnect()
            }.isFailure
        }
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
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
