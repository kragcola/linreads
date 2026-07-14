package dev.readflow.s7

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
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
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.BookmarkEntity
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.TextAnnotationEntity
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.render.api.SelectionAwareTextView
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
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
class TxtS7SearchBookmarkAnnotationRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        resetTargetAppState()
        settings.setFontSize(16)
        settings.setLineSpacing(1.75f)
        settings.setReadingMode(ReaderReadingMode.SCROLL)
        settings.setThemeMode(ThemeMode.LIGHT)
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
    fun txtSearchBookmarkAndAnnotationPersistAcrossReopenRuntime() {
        val title = "s7-offline-${UUID.randomUUID().toString().take(8)}"
        val selectedText = "S7SelectedText${title.replace("-", "")}"
        val searchMarker = selectedText
        val note = "S7 note ${UUID.randomUUID().toString().take(8)}"
        val readerUri = externalBookUri(fileName = "$title.txt")

        lateinit var importedBook: BookEntity
        lateinit var progressAfterSearch: ReadingProgressEntity
        lateinit var bookmark: BookmarkEntity
        lateinit var annotation: TextAnnotationEntity
        lateinit var searchResultDescription: String
        lateinit var bookmarkJumpDescription: String
        lateinit var annotationJumpDescription: String

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(TXT_READER_DESC))
            waitForVisibleTxtText(scenario, "S7 paragraph 000")
            importedBook = waitForImportedLocalTxtBook(title)

            openBottomPanel("搜索", "关键词")
            waitForObject(By.clazz("android.widget.EditText")).text = searchMarker
            waitForObject(By.desc("执行搜索")).click()
            val result = waitForObject(By.descContains("搜索结果 1"))
            searchResultDescription = result.contentDescription.orEmpty()
            result.click()
            waitForVisibleTxtText(scenario, searchMarker)
            progressAfterSearch = waitForProgress(importedBook.id) { progress ->
                progress.totalProgression > 0.15f
            }
            takeScreenshot("s7-after-search-jump.png")
            dumpHierarchy("s7-after-search-jump.xml")

            ensureChromeVisible()
            waitForObject(By.desc("添加书签")).click()
            bookmark = waitForActiveBookmark(importedBook.id)
            ensureChromeVisible()
            openBottomPanel("书签", "书签")
            bookmarkJumpDescription = waitForObject(By.descContains("跳转到书签")).contentDescription.orEmpty()
            takeScreenshot("s7-bookmark-panel.png")
            dumpHierarchy("s7-bookmark-panel.xml")

            closeChromeForBodySelection()
            val selectedViewText = scenario.withActivity { activity ->
                activity.visibleTxtTexts().firstOrNull { selectedText in it }
            } ?: error("Expected selected text paragraph to be visible after search jump")
            onView(withText(equalTo(selectedViewText))).perform(longClick())
            waitForObject(
                selector = By.text("保存"),
                onTimeout = {
                    writeTextEvidence(
                        "s7-note-actions-timeout.txt",
                        buildString {
                            appendLine("selected_view_text=$selectedViewText")
                            appendLine("visible_texts=")
                            scenario.withActivity { activity ->
                                activity.visibleTxtTexts().forEach { appendLine(it) }
                            }
                        },
                    )
                    takeScreenshot("s7-note-actions-timeout.png")
                    dumpHierarchy("s7-note-actions-timeout.xml")
                    copyDatabaseSnapshot("s7-note-actions-timeout")
                },
            )
            val noteField = waitForObject(By.clazz("android.widget.EditText"))
            noteField.text = note
            waitForCondition("expected note field to contain S7 note") {
                noteField.text == note
            }
            waitForObject(By.text("保存")).click()
            assertTrue(
                "expected selection action panel to dismiss after saving the S7 note",
                device.wait(Until.gone(By.text("保存")), UI_TIMEOUT_MS),
            )
            annotation = waitForAnnotation(note)
            assertTrue(
                "expected selected text to be persisted with the S7 note",
                annotation.selectedText.contains(selectedText),
            )
            ensureChromeVisible()
            openBottomPanel("标注", "标注")
            annotationJumpDescription = waitForObject(By.descContains("跳转到标注")).contentDescription.orEmpty()
            waitForObject(By.text(note))
            takeScreenshot("s7-annotation-panel-before-reopen.png")
            dumpHierarchy("s7-annotation-panel-before-reopen.xml")
        }

        ActivityScenario.launch<MainActivity>(launcherIntent()).use { reopened ->
            waitForObject(By.desc("打开 $title")).click()
            waitForObject(By.desc(TXT_READER_DESC))
            ensureChromeVisible()
            openBottomPanel("书签", "书签")
            waitForObject(By.descContains("跳转到书签"))
            openBottomPanel("标注", "标注")
            waitForObject(By.text(note))
            waitForObject(By.descContains("跳转到标注")).click()
            waitForVisibleTxtText(reopened, selectedText)
            val progressAfterAnnotationJump = waitForProgress(importedBook.id) { progress ->
                progress.totalProgression > 0.15f
            }
            val activeBookmarksAfterReopen = activeBookmarks(importedBook.id)
            val activeAnnotationsAfterReopen = activeAnnotations(importedBook.id)
            copyDatabaseSnapshot("s7-offline")
            takeScreenshot("s7-after-reopen-annotation-jump.png")
            dumpHierarchy("s7-after-reopen-annotation-jump.xml")
            writeTextEvidence(
                "s7-offline-summary.txt",
                buildString {
                    appendLine("book_id=${importedBook.id}")
                    appendLine("title=$title")
                    appendLine("search_marker=$searchMarker")
                    appendLine("search_result_description=$searchResultDescription")
                    appendLine("progress_after_search=${progressAfterSearch.locatorJson}")
                    appendLine("progress_after_search_total=${progressAfterSearch.totalProgression}")
                    appendLine("bookmark_id=${bookmark.id}")
                    appendLine("bookmark_progress=${bookmark.totalProgression}")
                    appendLine("bookmark_jump_description=$bookmarkJumpDescription")
                    appendLine("annotation_id=${annotation.id}")
                    appendLine("annotation_selected=${annotation.selectedText}")
                    appendLine("annotation_note=${annotation.note}")
                    appendLine("annotation_jump_description=$annotationJumpDescription")
                    appendLine("active_bookmarks_after_reopen=${activeBookmarksAfterReopen.size}")
                    appendLine("active_annotations_after_reopen=${activeAnnotationsAfterReopen.size}")
                    appendLine("progress_after_annotation_jump=${progressAfterAnnotationJump.locatorJson}")
                    appendLine("progress_after_annotation_jump_total=${progressAfterAnnotationJump.totalProgression}")
                    appendLine("evidence_boundary=AVD instrumentation + TXT offline corpus; no physical tablet or human TalkBack speech")
                },
            )

            assertTrue(progressAfterSearch.totalProgression > 0.15f)
            assertTrue(bookmark.totalProgression > 0.15f)
            assertEquals(1, activeBookmarksAfterReopen.size)
            assertEquals(1, activeAnnotationsAfterReopen.size)
            assertEquals(note, activeAnnotationsAfterReopen.single().note)
            assertTrue(progressAfterAnnotationJump.totalProgression > 0.15f)
        }
    }

    private fun readerIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("s7-offline-smoke", uri)
        }

    private fun launcherIntent() =
        Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun externalBookUri(fileName: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(PROVIDER_AUTHORITY)
            .appendPath(fileName)
            .build()

    private fun openBottomPanel(buttonText: String, expectedText: String) {
        ensureChromeVisible()
        clickBottomControl(buttonText)
        waitForObject(By.text(expectedText))
    }

    private fun ensureChromeVisible() {
        if (device.wait(Until.findObject(By.text("搜索")), 750) == null) {
            waitForObject(By.desc(TXT_READER_DESC)).click()
            device.waitForIdle()
        }
        waitForObject(By.text("搜索"))
    }

    private fun closeChromeForBodySelection() {
        if (device.wait(Until.findObject(By.text("搜索")), 750) != null) {
            waitForObject(By.desc(TXT_READER_DESC)).click()
            device.wait(Until.gone(By.text("搜索")), UI_TIMEOUT_MS)
            device.waitForIdle()
        }
    }

    private fun clickBottomControl(label: String) {
        val objectToClick = device.findObjects(By.text(label))
            .maxByOrNull { it.visibleBounds.centerY() }
            ?: device.findObjects(By.desc(label)).maxByOrNull { it.visibleBounds.centerY() }
            ?: error("Unable to find bottom control: $label")
        objectToClick.click()
        device.waitForIdle()
    }

    private fun resetTargetAppState() {
        clearDatabaseTables()
        deleteRecursively(File(appContext.filesDir, "books"))
        deleteRecursively(File(appContext.filesDir, "covers"))
        deleteIfExists(File(appContext.filesDir, "datastore/readflow_settings.preferences_pb"))
        markSeedBooksAsAlreadyImported()
    }

    private fun clearDatabaseTables() {
        val db = openDb()
        try {
            db.clearAllTables()
        } finally {
            db.close()
        }
    }

    private fun markSeedBooksAsAlreadyImported() {
        val seeded = appContext.assets.list("sample_books")?.toSet().orEmpty()
        appContext.getSharedPreferences("seed_state", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("seeded_files", seeded)
            .commit()
    }

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun waitForImportedLocalTxtBook(title: String): BookEntity =
        waitForConditionResult(
            message = "expected imported local TXT book row for title $title",
            timeoutMs = DB_TIMEOUT_MS,
            onTimeout = {
                writeTextEvidence("s7-books-timeout.txt", allBooksSummary())
                takeScreenshot("s7-books-timeout.png")
                dumpHierarchy("s7-books-timeout.xml")
                copyDatabaseSnapshot("s7-books-timeout")
            },
        ) {
            latestBookByTitle(title) ?: latestBookByTitlePrefix("s7-offline-")
        }

    private fun latestBookByTitle(title: String): BookEntity? {
        val db = openDb()
        return try {
            runBlocking {
                db.bookDao().observeAll().first().lastOrNull { it.title == title }
            }
        } finally {
            db.close()
        }
    }

    private fun latestBookByTitlePrefix(prefix: String): BookEntity? {
        val db = openDb()
        return try {
            runBlocking {
                db.bookDao().observeAll().first().firstOrNull { book ->
                    book.id.startsWith("local-txt-") &&
                        book.title.startsWith(prefix) &&
                        book.format == BookFormat.TXT.name &&
                        book.localUri?.endsWith(".txt") == true
                }
            }
        } finally {
            db.close()
        }
    }

    private fun bookById(bookId: String): BookEntity? {
        val db = openDb()
        return try {
            runBlocking { db.bookDao().getById(bookId) }
        } finally {
            db.close()
        }
    }

    private fun waitForProgress(
        bookId: String,
        predicate: (ReadingProgressEntity) -> Boolean,
    ): ReadingProgressEntity =
        waitForConditionResult("expected reading progress for $bookId", DB_TIMEOUT_MS) {
            latestProgress(bookId)?.takeIf(predicate)
        }

    private fun latestProgress(bookId: String): ReadingProgressEntity? {
        val db = openDb()
        return try {
            runBlocking { db.readingProgressDao().get(bookId) }
        } finally {
            db.close()
        }
    }

    private fun waitForActiveBookmark(bookId: String): BookmarkEntity =
        waitForConditionResult("expected active bookmark for $bookId", DB_TIMEOUT_MS) {
            activeBookmarks(bookId).singleOrNull()
        }

    private fun activeBookmarks(bookId: String): List<BookmarkEntity> {
        val db = openDb()
        return try {
            runBlocking {
                db.bookmarkDao().allForBackup().filter { it.bookId == bookId && !it.isDeleted }
            }
        } finally {
            db.close()
        }
    }

    private fun waitForAnnotation(note: String): TextAnnotationEntity =
        waitForConditionResult("expected annotation note $note", DB_TIMEOUT_MS) {
            activeAnnotations().singleOrNull { it.note == note }
        }

    private fun activeAnnotations(bookId: String? = null): List<TextAnnotationEntity> {
        val db = openDb()
        return try {
            runBlocking {
                db.textAnnotationDao().allForBackup().filter { annotation ->
                    !annotation.isDeleted && (bookId == null || annotation.bookId == bookId)
                }
            }
        } finally {
            db.close()
        }
    }

    private fun openDb(): ReadflowDatabase =
        Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()

    private fun allBooksSummary(): String {
        val db = openDb()
        return try {
            runBlocking {
                db.bookDao().observeAll().first().joinToString(separator = "\n") { book ->
                    listOf(
                        "id=${book.id}",
                        "title=${book.title}",
                        "format=${book.format}",
                        "localUri=${book.localUri}",
                        "lastReadAt=${book.lastReadAt}",
                    ).joinToString(" ")
                }
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

    private fun waitForVisibleTxtText(
        scenario: ActivityScenario<MainActivity>,
        expectedText: String,
    ) {
        waitForCondition("expected visible TXT text containing $expectedText") {
            scenario.withActivity { activity ->
                activity.visibleTxtTexts().any { expectedText in it }
            }
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

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("s7-offline-runtime-smoke")) {
            "external files dir unavailable"
        }

    private fun waitForObject(
        selector: BySelector,
        timeoutMs: Long = UI_TIMEOUT_MS,
        onTimeout: () -> Unit = {},
    ): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeoutMs) ?: run {
            onTimeout()
            null
        }) {
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

    private fun <T : Any> waitForConditionResult(
        message: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        onTimeout: () -> Unit = {},
        producer: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            producer()?.let { return it }
            Thread.sleep(150)
        }
        onTimeout()
        return checkNotNull(producer()) { message }
    }

    private fun <T> ActivityScenario<MainActivity>.withActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { activity ->
            result = runCatching { block(activity) }
        }
        return checkNotNull(result) { "activity callback did not return a result" }.getOrThrow()
    }

    private fun MainActivity.findReaderSurface(): View =
        checkNotNull(window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }) {
            "Unable to find reader surface"
        }

    private fun MainActivity.visibleTxtTexts(): List<String> =
        findReaderSurface()
            .findDescendants(predicate = { view ->
                view is SelectionAwareTextView && view.isShown && view.text?.isNotBlank() == true
            })
            .map { (it as SelectionAwareTextView).text.toString() }

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun View.findDescendants(predicate: (View) -> Boolean): List<View> {
        val matches = mutableListOf<View>()
        fun visit(view: View) {
            if (predicate(view)) matches += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index))
                }
            }
        }
        visit(this)
        return matches
    }

    private fun copyIfExists(source: File, destination: File) {
        if (source.exists()) {
            source.copyTo(destination, overwrite = true)
        }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    private companion object {
        private const val DB_NAME = "readflow.db"
        private const val TXT_READER_DESC = "阅读内容，捏合调整字号"
        private const val PROVIDER_AUTHORITY = "dev.readflow.test.a01bookprovider"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 8.seconds.inWholeMilliseconds
    }
}
