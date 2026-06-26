package dev.readflow.read05

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.readflow.MainActivity
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.database.TextAnnotationEntity
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class TxtRead05NoteSaveSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun savesTxtNoteAndShowsItInAnnotationPanel() {
        val anchorParagraph = "Readflow note smoke anchor paragraph keeps note-save validation stable."
        val note = "TXT-NOTE-${UUID.randomUUID()}"
        val readerUri = createTxtUri(
            listOf(
                anchorParagraph,
                "Second paragraph keeps the recycler view populated after the first note save.",
                "Third paragraph exists so the runtime keeps extra visible rows for selection cleanup.",
            ).joinToString("\n\n"),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use {
            dismissBlockingDialogs()
            waitForObject(By.desc("阅读内容，捏合调整字号"))
            waitForObject(By.text(anchorParagraph))

            onView(withText(equalTo(anchorParagraph))).perform(longClick())

            waitForObject(By.text("保存"))
            val noteField = waitForObject(By.clazz("android.widget.EditText"))
            noteField.text = note
            waitForCondition("expected note field text to update") {
                noteField.text == note
            }

            waitForObject(By.text("保存")).click()

            assertTrue(
                "expected selection actions to dismiss after save",
                device.wait(Until.gone(By.text("保存")), UI_TIMEOUT_MS),
            )

            val annotation = waitForAnnotation(note)
            assertNotNull(annotation)
            assertEquals(note, annotation?.note)
            assertTrue(
                "expected selectedText to be persisted for saved note",
                annotation?.selectedText?.isNotBlank() == true,
            )

            waitForObject(By.desc("阅读内容，捏合调整字号")).click()
            waitForObject(By.text("标注")).click()
            waitForObject(By.text(note))
        }
    }

    private fun readerIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("txt-note-smoke", uri)
        }

    private fun createTxtUri(content: String): Uri {
        val file = File(appContext.cacheDir, "txt-read05-note-smoke.txt")
        file.writeText(content)
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
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

    private fun waitForAnnotation(note: String): TextAnnotationEntity? {
        val deadline = System.currentTimeMillis() + DB_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val entity = latestAnnotation(note)
            if (entity != null) return entity
            Thread.sleep(250)
        }
        return latestAnnotation(note)
    }

    private fun latestAnnotation(note: String): TextAnnotationEntity? {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, "readflow.db")
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking {
                db.textAnnotationDao()
                    .allForBackup()
                    .sortedByDescending { it.updatedAt }
                    .firstOrNull { !it.isDeleted && it.note == note }
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        private val UI_TIMEOUT_MS = 10.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
