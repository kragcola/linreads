package dev.readflow.ux03

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
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
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.core.model.ThemeMode
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.txt.TxtParagraphAdapter
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderModeControlsRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        settings.setFontSize(16)
        settings.setLineSpacing(1.75f)
        settings.setReadingMode(ReaderReadingMode.SCROLL)
        settings.setThemeMode(ThemeMode.LIGHT)
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
    fun txtModeSwitchKeepsScrolledAnchorVisibleAndPersistsLocator() {
        val targetIndex = 24
        val title = "ux03-mode-${UUID.randomUUID().toString().take(8)}"
        val targetParagraph = corpusParagraph("UX03", targetIndex)
        val readerUri = createTxtUri(
            fileName = "$title.txt",
            content = buildTxtCorpus("UX03", 64),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "text/plain")).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(TXT_READER_DESC))
            waitForVisibleTxtParagraph(scenario, corpusParagraph("UX03", 0))

            val importedBook = waitForBookByTitle(title)

            scrollReaderSurfaceUntilVisible(scenario, targetParagraph)
            waitForCondition("expected scrolled TXT anchor paragraph to become visible") {
                scenario.withActivity { activity ->
                    activity.visibleTxtTexts().any { targetParagraph in it }
                }
            }

            val scrollVisibleTexts = scenario.withActivity { activity ->
                activity.visibleTxtTexts()
            }
            val scrollVisibleIndexes = scrollVisibleTexts.map(::paragraphIndexFromText)
            val scrollVisibleIndexMin = scrollVisibleIndexes.minOrNull()
                ?: error("Unable to determine visible TXT paragraph range before mode switch")
            val scrollVisibleIndexMax = scrollVisibleIndexes.maxOrNull()
                ?: error("Unable to determine visible TXT paragraph range before mode switch")
            openBottomPanel(buttonText = "排版", expectedText = "正文预览")
            takeScreenshot("ux03-font-panel-before-switch.png")
            dumpHierarchy("ux03-font-panel-before-switch.xml")
            waitForObject(By.text("分页")).click()

            waitForCondition("expected TXT reader to remount into paged mode") {
                scenario.withActivity { activity ->
                    activity.findTxtViewPager() != null
                }
            }
            waitForCondition("expected paged TXT view to stay within the previously visible TXT range") {
                scenario.withActivity { activity ->
                    val textView = activity.findVisiblePagedTxtTextView()
                    val pageIndex = textView?.tag as? Int
                    pageIndex != null && pageIndex in scrollVisibleIndexMin..scrollVisibleIndexMax
                }
            }

            val pagedState = scenario.withActivity { activity ->
                val textView = activity.findVisiblePagedTxtTextView()
                PagedState(
                    tagIndex = textView?.tag as? Int,
                    visibleText = textView?.text?.toString().orEmpty(),
                )
            }
            takeScreenshot("ux03-paged-mode.png")
            dumpHierarchy("ux03-paged-mode.xml")

            waitForObject(By.text("滚动")).click()
            waitForCondition("expected TXT reader to return to scroll mode") {
                scenario.withActivity { activity ->
                    activity.findTxtScrollRecyclerView() != null && activity.findTxtViewPager() == null
                }
            }
            waitForCondition("expected scroll mode to keep the same anchor paragraph visible after returning") {
                scenario.withActivity { activity ->
                    activity.visibleTxtTexts().map(::paragraphIndexFromText).any { it == pagedState.tagIndex }
                }
            }

            val scrollReturnTexts = scenario.withActivity { activity ->
                activity.visibleTxtTexts()
            }
            waitForCondition(
                message = "expected reading_progress row to stay non-zero after switching back to scroll mode",
                timeoutMs = DB_TIMEOUT_MS,
            ) {
                latestProgress(importedBook.id)?.totalProgression?.let { it > 0f } == true
            }
            val persistedAfterReturn = checkNotNull(latestProgress(importedBook.id))
            copyDatabaseSnapshot("ux03-mode-switch")
            writeTextEvidence(
                "ux03-mode-switch-summary.txt",
                buildString {
                    appendLine("book_id=${importedBook.id}")
                    appendLine("title=$title")
                    appendLine("target_index=$targetIndex")
                    appendLine("target_paragraph=$targetParagraph")
                    appendLine("scroll_visible_before_switch=${scrollVisibleTexts.joinToString(" | ")}")
                    appendLine("scroll_visible_indexes=${scrollVisibleIndexes.joinToString(",")}")
                    appendLine("scroll_visible_index_min=$scrollVisibleIndexMin")
                    appendLine("scroll_visible_index_max=$scrollVisibleIndexMax")
                    appendLine("paged_tag_index=${pagedState.tagIndex}")
                    appendLine("paged_visible_text=${pagedState.visibleText}")
                    appendLine("scroll_visible_after_return=${scrollReturnTexts.joinToString(" | ")}")
                    appendLine("persisted_after_return=${persistedAfterReturn.locatorJson}")
                    appendLine("persisted_after_return_total=${persistedAfterReturn.totalProgression}")
                },
            )

            assertTrue(
                "expected target paragraph to be visible before switching modes",
                scrollVisibleTexts.any { targetParagraph in it },
            )
            assertTrue(
                "expected paged TXT view to expose a stable page index",
                pagedState.tagIndex != null,
            )
            assertTrue(
                "expected paged TXT page index to stay within the previously visible scroll range",
                checkNotNull(pagedState.tagIndex) in scrollVisibleIndexMin..scrollVisibleIndexMax,
            )
            assertTrue(
                "expected the paged TXT paragraph to remain visible after returning to scroll mode",
                scrollReturnTexts.map(::paragraphIndexFromText).any { it == pagedState.tagIndex },
            )
            assertTrue(
                "expected persisted TXT progression to stay non-zero after the round trip",
                persistedAfterReturn.totalProgression > 0f,
            )
        }
    }

    @Test
    fun repeatedExternalActionViewRestoresPersistedTxtAnchor() {
        val targetIndex = 48
        val fileName = "s4-offline-${UUID.randomUUID().toString().take(8)}.txt"
        val title = fileName.substringBeforeLast('.')
        val targetParagraph = corpusParagraph("S4", targetIndex)
        val readerUri = externalBookUri(fileName)
        var expectedRestoreIndex = -1

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "text/plain")).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(TXT_READER_DESC))
            waitForVisibleTxtParagraph(scenario, corpusParagraph("S4", 0))

            val importedBook = waitForBookByTitle(title)
            scrollTxtRecyclerToParagraph(scenario, targetIndex)
            waitForCondition("expected scrolled TXT anchor paragraph to become visible before reopen") {
                scenario.withActivity { activity ->
                    activity.visibleTxtTexts().any { targetParagraph in it }
                }
            }
            val visibleBeforeReopen = scenario.withActivity { activity ->
                activity.visibleTxtTexts()
            }
            val visibleIndexesBeforeReopen = visibleBeforeReopen.map(::paragraphIndexFromText)
            val visibleIndexRangeBeforeReopen = checkNotNull(visibleIndexesBeforeReopen.minOrNull()) {
                "expected visible S4 indexes before reopen"
            }..checkNotNull(visibleIndexesBeforeReopen.maxOrNull()) {
                "expected visible S4 indexes before reopen"
            }
            waitForCondition(
                message = "expected reading_progress to persist the visible S4 anchor before reopen",
                timeoutMs = DB_TIMEOUT_MS,
            ) {
                latestProgress(importedBook.id)
                    ?.locatorParagraphIndex()
                    ?.let { it in visibleIndexRangeBeforeReopen } == true
            }
            val persistedBeforeReopen = checkNotNull(latestProgress(importedBook.id))
            expectedRestoreIndex = persistedBeforeReopen.locatorParagraphIndex()
            takeScreenshot("ux03-s4-before-reopen.png")
            dumpHierarchy("ux03-s4-before-reopen.xml")
            copyDatabaseSnapshot("ux03-s4-before-reopen")
            writeTextEvidence(
                "ux03-s4-before-reopen-summary.txt",
                buildString {
                    appendLine("book_id=${importedBook.id}")
                    appendLine("title=$title")
                    appendLine("target_index=$targetIndex")
                    appendLine("visible_before_reopen=${visibleBeforeReopen.joinToString(" | ")}")
                    appendLine("visible_indexes_before_reopen=${visibleIndexesBeforeReopen.joinToString(",")}")
                    appendLine("expected_restore_index=$expectedRestoreIndex")
                    appendLine("persisted_before_reopen=${persistedBeforeReopen.locatorJson}")
                    appendLine("persisted_before_reopen_total=${persistedBeforeReopen.totalProgression}")
                },
            )
        }

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "text/plain")).use { reopened ->
            dismissBlockingDialogs()
            waitForObject(By.desc(TXT_READER_DESC))
            waitForCondition("expected repeated ACTION_VIEW to restore the S4 mid-book anchor") {
                reopened.withActivity { activity ->
                    val visibleIndexes = activity.visibleTxtTexts().map(::paragraphIndexFromText)
                    visibleIndexes.any { it in (expectedRestoreIndex - 2)..(expectedRestoreIndex + 8) } &&
                        visibleIndexes.none { it == 0 }
                }
            }

            val visibleAfterReopen = reopened.withActivity { activity ->
                activity.visibleTxtTexts()
            }
            val visibleIndexesAfterReopen = visibleAfterReopen.map(::paragraphIndexFromText)
            val rows = booksByTitle(title)
            takeScreenshot("ux03-s4-after-reopen.png")
            dumpHierarchy("ux03-s4-after-reopen.xml")
            copyDatabaseSnapshot("ux03-s4-after-reopen")
            writeTextEvidence(
                "ux03-s4-after-reopen-summary.txt",
                buildString {
                    appendLine("title=$title")
                    appendLine("matching_rows=${rows.size}")
                    appendLine("visible_after_reopen=${visibleAfterReopen.joinToString(" | ")}")
                    appendLine("visible_indexes_after_reopen=${visibleIndexesAfterReopen.joinToString(",")}")
                },
            )

            assertEquals("expected repeated external import to reuse one stable book row", 1, rows.size)
            assertTrue(
                "expected repeated ACTION_VIEW to restore near persisted paragraph $expectedRestoreIndex, got $visibleIndexesAfterReopen",
                visibleIndexesAfterReopen.any { it in (expectedRestoreIndex - 2)..(expectedRestoreIndex + 8) },
            )
            assertTrue(
                "expected repeated ACTION_VIEW not to reopen at the first paragraph",
                visibleIndexesAfterReopen.none { it == 0 },
            )
        }
    }

    @Test
    fun userFacingControlsStayVisibleAndNightThemeAppliesAtRuntime() {
        val title = "ux04-controls-${UUID.randomUUID().toString().take(8)}"
        val readerUri = createTxtUri(
            fileName = "$title.txt",
            content = buildTxtCorpus("UX04", 12),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri, "text/plain")).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(TXT_READER_DESC))
            waitForVisibleTxtParagraph(scenario, corpusParagraph("UX04", 0))
            waitForBookByTitle(title)

            openBottomPanel(buttonText = "排版", expectedText = "正文预览")
            waitForObject(By.text("排版"))
            val fontLabel = waitForObject(By.textContains("sp")).text
            val lineSpacingLabel = waitForObject(By.textContains("1.75")).text
            waitForObject(By.text("行距"))
            waitForObject(By.text("滚动"))
            waitForObject(By.text("分页"))
            takeScreenshot("ux04-font-panel.png")
            dumpHierarchy("ux04-font-panel.xml")

            val baselineColors = scenario.withActivity { activity ->
                activity.currentScrollPalette()
            }

            waitForObject(By.text("主题")).click()
            waitForObject(By.text("跟随系统"))
            waitForObject(By.text("日间"))
            waitForObject(By.text("夜间"))
            waitForObject(By.text("护眼"))
            takeScreenshot("ux04-theme-panel-before-night.png")
            dumpHierarchy("ux04-theme-panel-before-night.xml")

            waitForObject(By.text("夜间")).click()
            waitForCondition("expected theme preference to persist as night mode") {
                runBlocking { settings.themeMode.first() == ThemeMode.DARK }
            }
            waitForCondition("expected TXT runtime palette to switch to the night theme") {
                scenario.withActivity { activity ->
                    activity.currentScrollPalette() == TxtPalette(
                        background = NIGHT_PAPER,
                        text = TxtParagraphAdapter.INK_NIGHT,
                    )
                }
            }

            val afterNightColors = scenario.withActivity { activity ->
                activity.currentScrollPalette()
            }
            takeScreenshot("ux04-theme-panel-after-night.png")
            dumpHierarchy("ux04-theme-panel-after-night.xml")
            writeTextEvidence(
                "ux04-controls-theme-summary.txt",
                buildString {
                    appendLine("title=$title")
                    appendLine("font_label=$fontLabel")
                    appendLine("line_spacing_label=$lineSpacingLabel")
                    appendLine("baseline_background=${baselineColors.background}")
                    appendLine("baseline_text=${baselineColors.text}")
                    appendLine("night_background=${afterNightColors.background}")
                    appendLine("night_text=${afterNightColors.text}")
                    appendLine("persisted_theme=${runBlocking { settings.themeMode.first() }}")
                },
            )

            assertEquals("16sp", fontLabel)
            assertEquals("1.75x", lineSpacingLabel)
            assertEquals(ThemeMode.DARK, runBlocking { settings.themeMode.first() })
            assertNotEquals(
                "expected the background color to change after switching to night theme",
                baselineColors.background,
                afterNightColors.background,
            )
            assertNotEquals(
                "expected the text color to change after switching to night theme",
                baselineColors.text,
                afterNightColors.text,
            )
            assertEquals(NIGHT_PAPER, afterNightColors.background)
            assertEquals(TxtParagraphAdapter.INK_NIGHT, afterNightColors.text)
        }
    }

    private fun externalBookUri(fileName: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(PROVIDER_AUTHORITY)
            .appendPath(fileName)
            .build()

    private fun readerIntent(uri: Uri, mimeType: String) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("ux03-reader-smoke", uri)
        }

    private fun createTxtUri(fileName: String, content: String): Uri {
        val file = File(appContext.cacheDir, fileName)
        file.writeText(content)
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    private fun buildTxtCorpus(prefix: String, paragraphCount: Int): String =
        (0 until paragraphCount).joinToString("\n\n") { index ->
            corpusParagraph(prefix, index)
        }

    private fun corpusParagraph(prefix: String, index: Int): String =
        "$prefix anchor paragraph ${index.toString().padStart(3, '0')} keeps grouped runtime reading evidence stable."

    private fun paragraphIndexFromText(paragraph: String): Int =
        Regex("""paragraph (\d{3})""").find(paragraph)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: error("Unable to parse paragraph index from: $paragraph")

    private fun openBottomPanel(buttonText: String, expectedText: String) {
        if (device.wait(Until.findObject(By.text(buttonText)), 750) == null) {
            waitForObject(By.desc(TXT_READER_DESC)).click()
            device.waitForIdle()
        }
        waitForObject(By.text(buttonText)).click()
        waitForObject(By.text(expectedText))
    }

    private fun dismissBlockingDialogs() {
        val dismissTexts = listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow")
        dismissTexts.forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun waitForBookByTitle(title: String): BookEntity =
        waitForConditionResult("expected imported book row for title $title", DB_TIMEOUT_MS) {
            latestBookByTitle(title)
        }

    private fun booksByTitle(title: String): List<BookEntity> {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking {
                db.bookDao().observeAll().first().filter { it.title == title }
            }
        } finally {
            db.close()
        }
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

    private fun latestProgress(bookId: String): ReadingProgressEntity? {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking { db.readingProgressDao().get(bookId) }
        } finally {
            db.close()
        }
    }

    private fun ReadingProgressEntity.locatorParagraphIndex(): Int =
        (totalProgression.coerceIn(0f, 1f) * S4_PARAGRAPH_COUNT).toInt()

    private fun copyDatabaseSnapshot(label: String) {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        copyIfExists(dbFile, File(evidenceDir(), "$label-readflow.db"))
        copyIfExists(File(dbFile.path + "-wal"), File(evidenceDir(), "$label-readflow.db-wal"))
        copyIfExists(File(dbFile.path + "-shm"), File(evidenceDir(), "$label-readflow.db-shm"))
    }

    private fun copyIfExists(source: File, destination: File) {
        if (source.exists()) {
            source.copyTo(destination, overwrite = true)
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

    private fun scrollReaderSurfaceUntilVisible(
        scenario: ActivityScenario<MainActivity>,
        targetText: String,
        maxSwipes: Int = 10,
    ) {
        repeat(maxSwipes) {
            if (scenario.withActivity { activity ->
                    activity.visibleTxtTexts().any { targetText in it }
                }
            ) {
                return
            }
            swipeReaderSurfaceUp()
        }
        check(
            scenario.withActivity { activity ->
                activity.visibleTxtTexts().any { targetText in it }
            }
        ) {
            "Unable to scroll target paragraph into view: $targetText"
        }
    }

    private fun scrollTxtRecyclerToParagraph(
        scenario: ActivityScenario<MainActivity>,
        paragraphIndex: Int,
    ) {
        scenario.withActivity { activity ->
            val recyclerView = activity.findTxtScrollRecyclerView()
                ?: error("Unable to find scroll-mode TXT RecyclerView")
            val layoutManager = recyclerView.javaClass
                .getMethod("getLayoutManager")
                .invoke(recyclerView)
                ?: error("TXT RecyclerView does not have a layout manager")
            layoutManager.javaClass
                .getMethod(
                    "scrollToPositionWithOffset",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(layoutManager, paragraphIndex, 0)
            recyclerView.post { recyclerView.requestLayout() }
        }
        device.waitForIdle()
    }

    private fun waitForVisibleTxtParagraph(
        scenario: ActivityScenario<MainActivity>,
        paragraph: String,
    ) {
        waitForCondition("expected TXT paragraph to become visible: $paragraph") {
            scenario.withActivity { activity ->
                activity.visibleTxtTexts().any { paragraph in it }
            }
        }
    }

    private fun swipeReaderSurfaceUp() {
        val bounds = waitForObject(By.desc(TXT_READER_DESC)).visibleBounds
        val centerX = bounds.centerX()
        val startY = (bounds.bottom - 120).coerceAtLeast(bounds.top + 160)
        val endY = (bounds.top + 120).coerceAtMost(bounds.bottom - 160)
        device.swipe(centerX, startY, centerX, endY, 18)
        device.waitForIdle()
    }

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("ux03-ux04-runtime-smoke")) {
            "external files dir unavailable"
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

    private fun <T> ActivityScenario<MainActivity>.withActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { activity ->
            result = runCatching { block(activity) }
        }
        return checkNotNull(result) { "activity callback did not return a result" }.getOrThrow()
    }

    private fun MainActivity.findReaderSurface() =
        checkNotNull(window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }) {
            "Unable to find reader surface"
        }

    private fun MainActivity.findTxtScrollRecyclerView(): View? =
        findReaderSurface().findDescendant { view ->
            view.javaClass.name == RECYCLER_VIEW_CLASS_NAME &&
                runCatching { view.adapterClassName() }.getOrNull() == TxtParagraphAdapter::class.java.name
        }

    private fun MainActivity.findTxtViewPager(): View? =
        findReaderSurface().findDescendant { view -> view.javaClass.name == VIEW_PAGER_CLASS_NAME }

    private fun MainActivity.findVisiblePagedTxtTextView(): SelectionAwareTextView? =
        findReaderSurface().findDescendant { view ->
            view is SelectionAwareTextView && view.isShown && view.tag is Int && view.text?.isNotBlank() == true
        } as? SelectionAwareTextView

    private fun MainActivity.visibleTxtTexts(): List<String> =
        findReaderSurface()
            .findDescendants(predicate = { view ->
                view is SelectionAwareTextView && view.isShown && view.text?.isNotBlank() == true
            })
            .map { (it as SelectionAwareTextView).text.toString() }

    private fun MainActivity.currentScrollPalette(): TxtPalette {
        val recyclerView = checkNotNull(findTxtScrollRecyclerView()) {
            "Unable to find scroll-mode TXT recycler view for palette check"
        }
        val textView = checkNotNull(
            recyclerView.findDescendant { view ->
                view is SelectionAwareTextView && view.isShown && view.text?.isNotBlank() == true
            } as? SelectionAwareTextView
        ) {
            "Unable to find visible TXT text view for palette check"
        }
        return TxtPalette(
            background = (recyclerView.background as? ColorDrawable)?.color ?: Color.TRANSPARENT,
            text = textView.currentTextColor,
        )
    }

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun View.findDescendants(
        predicate: (View) -> Boolean,
        output: MutableList<View> = mutableListOf(),
    ): List<View> {
        if (predicate(this)) {
            output += this
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findDescendants(predicate, output)
            }
        }
        return output
    }

    private fun View.adapterClassName(): String? =
        runCatching {
            javaClass.getMethod("getAdapter").invoke(this)?.javaClass?.name
        }.getOrNull()

    private data class TxtPalette(
        val background: Int,
        val text: Int,
    )

    private data class PagedState(
        val tagIndex: Int?,
        val visibleText: String,
    )

    private companion object {
        private const val TXT_READER_DESC = "阅读内容，捏合调整字号"
        private const val DB_NAME = "readflow.db"
        private const val PROVIDER_AUTHORITY = "dev.readflow.test.a01bookprovider"
        private const val S4_PARAGRAPH_COUNT = 128
        private val NIGHT_PAPER = Color.rgb(0x2A, 0x26, 0x20)
        private const val RECYCLER_VIEW_CLASS_NAME = "androidx.recyclerview.widget.RecyclerView"
        private const val VIEW_PAGER_CLASS_NAME = "androidx.viewpager2.widget.ViewPager2"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
