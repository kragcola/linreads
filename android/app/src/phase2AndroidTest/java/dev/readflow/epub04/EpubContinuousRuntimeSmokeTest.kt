package dev.readflow.epub04

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.core.model.ThemeMode
import dev.readflow.render.api.R as RenderApiR
import dev.readflow.render.api.SelectionAwareTextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class EpubContinuousRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        settings.setFontSize(18)
        settings.setLineSpacing(1.75f)
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
    fun epubContinuousRendersCoverAndInlineImagesWithAltDescriptionsRuntime() {
        val title = "epub04-image-${UUID.randomUUID().toString().take(8)}"
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/cover.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <img id="cover-art" src="cover.png" alt="Cover art"/>
                      </body>
                    </html>
                """.trimIndent(),
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Chapter one text starts after the image-only cover in continuous mode.</p>
                        <img id="scene-art" src="scene.png" alt="Scene art"/>
                        <p>After scene image the reading flow continues as regular text.</p>
                      </body>
                    </html>
                """.trimIndent(),
            ),
            binaryEntries = listOf(
                BinaryEntry("OEBPS/cover.png", tinyPngBytes(Color.rgb(0xD6, 0x7C, 0x2F)), "image/png"),
                BinaryEntry("OEBPS/scene.png", tinyPngBytes(Color.rgb(0x25, 0x69, 0xBE)), "image/png"),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))
            waitForCondition("expected continuous EPUB recycler to mount") {
                scenario.withActivity { activity -> activity.findEpubScrollRecyclerView() != null }
            }

            val baseline = scenario.withActivity { activity ->
                val recycler = checkNotNull(activity.findEpubScrollRecyclerView()) {
                    "Unable to find continuous EPUB recycler"
                }
                scrollRecyclerToPosition(recycler, 0)
                val coverImage = checkNotNull(recycler.imageViewAtPosition(0)) {
                    "Expected cover image block at adapter position 0"
                }
                val chapterText = checkNotNull(recycler.textViewAtPosition(1)) {
                    "Expected chapter text block at adapter position 1"
                }
                scrollRecyclerToPosition(recycler, 2)
                val inlineImage = checkNotNull(recycler.imageViewAtPosition(2)) {
                    "Expected inline image block at adapter position 2"
                }
                scrollRecyclerToPosition(recycler, 3)
                val trailingText = checkNotNull(recycler.textViewAtPosition(3)) {
                    "Expected trailing text block at adapter position 3"
                }
                ImageRuntimeSummary(
                    itemCount = recycler.adapterItemCount(),
                    coverDescription = coverImage.contentDescription?.toString(),
                    coverDrawablePresent = coverImage.drawable != null,
                    coverHasTextSibling = recycler.findViewHolderItemViewAtPosition(0)
                        ?.findDescendant { view -> view is SelectionAwareTextView } != null,
                    chapterText = chapterText.text?.toString(),
                    inlineImageDescription = inlineImage.contentDescription?.toString(),
                    inlineImageDrawablePresent = inlineImage.drawable != null,
                    trailingText = trailingText.text?.toString(),
                )
            }
            takeScreenshot("epub04-images-runtime.png")
            dumpHierarchy("epub04-images-runtime.xml")

            writeTextEvidence(
                "epub04-images-summary.txt",
                buildString {
                    appendLine("item_count=${baseline.itemCount}")
                    appendLine("cover_description=${baseline.coverDescription}")
                    appendLine("cover_drawable_present=${baseline.coverDrawablePresent}")
                    appendLine("cover_has_text_sibling=${baseline.coverHasTextSibling}")
                    appendLine("chapter_text=${baseline.chapterText}")
                    appendLine("inline_image_description=${baseline.inlineImageDescription}")
                    appendLine("inline_image_drawable_present=${baseline.inlineImageDrawablePresent}")
                    appendLine("trailing_text=${baseline.trailingText}")
                },
            )

            assertTrue(baseline.itemCount >= 4)
            assertEquals("Cover art", baseline.coverDescription)
            assertTrue(baseline.coverDrawablePresent)
            assertFalse(baseline.coverHasTextSibling)
            assertTrue(baseline.chapterText?.contains("Chapter one text starts after the image-only cover") == true)
            assertEquals("Scene art", baseline.inlineImageDescription)
            assertTrue(baseline.inlineImageDrawablePresent)
            assertTrue(baseline.trailingText?.contains("After scene image") == true)
        }
    }

    @Test
    fun epubContinuousInlineLinksNavigateInternallyAndLaunchExternalIntentRuntime() {
        val title = "epub05-link-${UUID.randomUUID().toString().take(8)}"
        val externalHref = "https://example.com/linreads-epub-link-smoke"
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Open <a href="$externalHref">website</a> or jump to <a href="notes.xhtml#target">target section</a>.</p>
                      </body>
                    </html>
                """.trimIndent(),
                "OEBPS/notes.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Filler paragraph 01.</p>
                        <p>Filler paragraph 02.</p>
                        <p id="target">Target section body proves internal link navigation in continuous mode.</p>
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))
            val importedBook = waitForBookByTitle(title)
            val externalMonitor = instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") },
                Instrumentation.ActivityResult(Activity.RESULT_OK, null),
                true,
            )
            try {
                val beforeInternalNavigation = scenario.withActivity { activity ->
                    val recycler = checkNotNull(activity.findEpubScrollRecyclerView()) {
                        "Unable to find continuous EPUB recycler"
                    }
                    scrollRecyclerToPosition(recycler, 0)
                    val linkView = checkNotNull(recycler.textViewAtPosition(0)) {
                        "Expected link paragraph at adapter position 0"
                    }
                    val clickableSpans = (linkView.text as? Spanned)
                        ?.getSpans(0, linkView.text.length, ClickableSpan::class.java)
                        ?.size ?: 0
                    dispatchLinkTap(linkView, "website")
                    val externalTapConsumed = linkView.wasInteractiveTapConsumed()
                    LinkRuntimeBaseline(
                        clickableSpanCount = clickableSpans,
                        linksClickable = linkView.linksClickable,
                        externalTapConsumed = externalTapConsumed,
                        internalTapConsumed = false,
                    )
                }
                instrumentation.waitForIdleSync()
                device.waitForIdle()
                waitForCondition("expected external ACTION_VIEW intent to be intercepted") {
                    externalMonitor.hits >= 1
                }
                val internalTapConsumed = scenario.withActivity { activity ->
                    val recycler = checkNotNull(activity.findEpubScrollRecyclerView()) {
                        "Unable to find continuous EPUB recycler before internal navigation"
                    }
                    scrollRecyclerToPosition(recycler, 0)
                    val linkView = checkNotNull(recycler.textViewAtPosition(0)) {
                        "Expected link paragraph at adapter position 0 before internal navigation"
                    }
                    dispatchLinkTap(linkView, "target section")
                    linkView.wasInteractiveTapConsumed()
                }
                instrumentation.waitForIdleSync()
                device.waitForIdle()
                waitForCondition("expected internal link to persist a notes.xhtml locator in reading_progress") {
                    latestProgress(importedBook.id)?.locatorJson?.contains("\"spineIndex\":1") == true
                }
                val afterInternalNavigation = scenario.withActivity { activity ->
                    val recycler = checkNotNull(activity.findEpubScrollRecyclerView()) {
                        "Unable to find continuous EPUB recycler after internal navigation"
                    }
                    scrollRecyclerToPosition(recycler, 3)
                    val targetView = checkNotNull(recycler.textViewAtPosition(3)) {
                        "Expected internal link target paragraph at adapter position 3"
                    }
                    LinkRuntimeResult(
                        externalMonitorHits = externalMonitor.hits,
                        targetText = targetView.text?.toString(),
                        dbLocator = latestProgress(importedBook.id)?.locatorJson,
                        dbTotalProgress = latestProgress(importedBook.id)?.totalProgression,
                    )
                }
                copyDatabaseSnapshot("epub05-link-runtime")
                takeScreenshot("epub05-links-runtime.png")
                dumpHierarchy("epub05-links-runtime.xml")

                writeTextEvidence(
                    "epub05-links-summary.txt",
                    buildString {
                        appendLine("clickable_span_count=${beforeInternalNavigation.clickableSpanCount}")
                        appendLine("links_clickable=${beforeInternalNavigation.linksClickable}")
                        appendLine("external_tap_consumed=${beforeInternalNavigation.externalTapConsumed}")
                        appendLine("internal_tap_consumed=$internalTapConsumed")
                        appendLine("external_monitor_hits=${afterInternalNavigation.externalMonitorHits}")
                        appendLine("target_text=${afterInternalNavigation.targetText}")
                        appendLine("db_locator=${afterInternalNavigation.dbLocator}")
                        appendLine("db_total_progress=${afterInternalNavigation.dbTotalProgress}")
                    },
                )

                assertEquals(2, beforeInternalNavigation.clickableSpanCount)
                assertTrue(beforeInternalNavigation.linksClickable)
                assertTrue(beforeInternalNavigation.externalTapConsumed)
                assertTrue(internalTapConsumed)
                assertTrue(afterInternalNavigation.externalMonitorHits >= 1)
                assertTrue(afterInternalNavigation.targetText?.contains("Target section body proves internal link navigation") == true)
                assertTrue(afterInternalNavigation.dbLocator?.contains("\"spineIndex\":1") == true)
            } finally {
                instrumentation.removeMonitor(externalMonitor)
            }
        }
    }

    @Test
    fun epubContinuousFixedLayoutFallbackPreservesReadableDegradedBlocksRuntime() {
        val title = "epub06-fixed-${UUID.randomUUID().toString().take(8)}"
        val longPreLine = "LONGCODE0123456789".repeat(18)
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            fixedLayout = true,
            spineEntries = listOf(
                "OEBPS/page.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <h1>Runtime Heading</h1>
                        <table>
                          <tr><td>A</td><td>B</td></tr>
                          <tr><td>C</td><td>D</td></tr>
                        </table>
                        <pre>$longPreLine
    indented second line</pre>
                        <blockquote><p>Quoted runtime text</p></blockquote>
                        <ul>
                          <li>First bullet<ul><li>Nested bullet</li></ul></li>
                        </ul>
                        <aside>Unknown runtime body</aside>
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))
            waitForCondition("expected continuous EPUB recycler to mount for fixed-layout fallback") {
                scenario.withActivity { activity -> activity.findEpubScrollRecyclerView() != null }
            }

            val summary = scenario.withActivity { activity ->
                val recycler = checkNotNull(activity.findEpubScrollRecyclerView()) {
                    "Unable to find continuous EPUB recycler for fixed-layout fallback"
                }
                scrollRecyclerToPosition(recycler, 0)
                val headingView = checkNotNull(recycler.textViewAtPosition(0)) {
                    "Expected heading block at adapter position 0"
                }
                scrollRecyclerToPosition(recycler, 1)
                val tableView = checkNotNull(recycler.textViewAtPosition(1)) {
                    "Expected table block at adapter position 1"
                }
                scrollRecyclerToPosition(recycler, 2)
                val preView = checkNotNull(recycler.textViewAtPosition(2)) {
                    "Expected preformatted block at adapter position 2"
                }
                scrollRecyclerToPosition(recycler, 3)
                val quoteView = checkNotNull(recycler.textViewAtPosition(3)) {
                    "Expected blockquote at adapter position 3"
                }
                scrollRecyclerToPosition(recycler, 4)
                val listView = checkNotNull(recycler.textViewAtPosition(4)) {
                    "Expected first list block at adapter position 4"
                }
                scrollRecyclerToPosition(recycler, 5)
                val nestedListView = checkNotNull(recycler.textViewAtPosition(5)) {
                    "Expected nested list block at adapter position 5"
                }
                scrollRecyclerToPosition(recycler, 6)
                val unknownView = checkNotNull(recycler.textViewAtPosition(6)) {
                    "Expected unknown fallback block at adapter position 6"
                }
                DegradationRuntimeSummary(
                    itemCount = recycler.adapterItemCount(),
                    headingText = headingView.text?.toString(),
                    headingTextSizePx = headingView.textSize,
                    headingBold = headingView.typeface?.isBold == true,
                    tableText = tableView.text?.toString(),
                    tableUsesMonospace = tableView.typeface == android.graphics.Typeface.MONOSPACE,
                    preText = preView.text?.toString(),
                    preUsesMonospace = preView.typeface == android.graphics.Typeface.MONOSPACE,
                    preCanScrollHorizontally = preView.canScrollHorizontally(1),
                    quoteText = quoteView.text?.toString(),
                    quotePaddingLeft = quoteView.paddingLeft,
                    tablePaddingLeft = tableView.paddingLeft,
                    listText = listView.text?.toString(),
                    listPaddingLeft = listView.paddingLeft,
                    nestedListText = nestedListView.text?.toString(),
                    nestedListPaddingLeft = nestedListView.paddingLeft,
                    unknownText = unknownView.text?.toString(),
                )
            }
            takeScreenshot("epub06-fixed-runtime.png")
            dumpHierarchy("epub06-fixed-runtime.xml")

            writeTextEvidence(
                "epub06-fixed-summary.txt",
                buildString {
                    appendLine("item_count=${summary.itemCount}")
                    appendLine("heading_text=${summary.headingText}")
                    appendLine("heading_text_size_px=${summary.headingTextSizePx}")
                    appendLine("heading_bold=${summary.headingBold}")
                    appendLine("table_text=${summary.tableText}")
                    appendLine("table_uses_monospace=${summary.tableUsesMonospace}")
                    appendLine("pre_text=${summary.preText}")
                    appendLine("pre_uses_monospace=${summary.preUsesMonospace}")
                    appendLine("pre_can_scroll_horizontally=${summary.preCanScrollHorizontally}")
                    appendLine("quote_text=${summary.quoteText}")
                    appendLine("quote_padding_left=${summary.quotePaddingLeft}")
                    appendLine("table_padding_left=${summary.tablePaddingLeft}")
                    appendLine("list_text=${summary.listText}")
                    appendLine("list_padding_left=${summary.listPaddingLeft}")
                    appendLine("nested_list_text=${summary.nestedListText}")
                    appendLine("nested_list_padding_left=${summary.nestedListPaddingLeft}")
                    appendLine("unknown_text=${summary.unknownText}")
                },
            )

            assertTrue(summary.itemCount >= 7)
            assertEquals("Runtime Heading", summary.headingText)
            assertTrue(summary.headingBold)
            assertEquals("A, B\nC, D", summary.tableText)
            assertTrue(summary.tableUsesMonospace)
            assertTrue(summary.preText?.contains(longPreLine) == true)
            assertTrue(summary.preUsesMonospace)
            assertTrue(summary.preCanScrollHorizontally)
            assertEquals("Quoted runtime text", summary.quoteText)
            assertTrue(summary.quotePaddingLeft > summary.tablePaddingLeft)
            assertEquals("• First bullet", summary.listText)
            assertEquals("• Nested bullet", summary.nestedListText)
            assertTrue(summary.nestedListPaddingLeft > summary.listPaddingLeft)
            assertEquals("Unknown runtime body", summary.unknownText)
        }
    }

    private fun readerIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, "application/epub+zip")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("epub04-06-runtime-smoke", uri)
        }

    private fun createEpubUri(
        fileName: String,
        spineEntries: List<Pair<String, String>>,
        binaryEntries: List<BinaryEntry> = emptyList(),
        fixedLayout: Boolean = false,
    ): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeEpub(file, spineEntries, binaryEntries, fixedLayout)
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }

    private fun writeEpub(
        file: File,
        spineEntries: List<Pair<String, String>>,
        binaryEntries: List<BinaryEntry>,
        fixedLayout: Boolean,
    ) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun addText(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            fun addBinary(entry: BinaryEntry) {
                zip.putNextEntry(ZipEntry(entry.path))
                zip.write(entry.bytes)
                zip.closeEntry()
            }

            addText(
                "META-INF/container.xml",
                """
                    <container>
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent(),
            )
            addText(
                "OEBPS/content.opf",
                buildString {
                    appendLine("<package version=\"3.0\" xmlns:rendition=\"http://www.idpf.org/vocab/rendition/#\">")
                    if (fixedLayout) {
                        appendLine("  <metadata>")
                        appendLine("    <meta property=\"rendition:layout\">pre-paginated</meta>")
                        appendLine("  </metadata>")
                    }
                    appendLine("  <manifest>")
                    spineEntries.forEachIndexed { index, (path, _) ->
                        val properties = if (fixedLayout) " properties=\"rendition:layout-pre-paginated\"" else ""
                        appendLine(
                            "    <item id=\"c$index\" href=\"${path.removePrefix("OEBPS/")}\" media-type=\"application/xhtml+xml\"$properties/>",
                        )
                    }
                    binaryEntries.forEachIndexed { index, entry ->
                        appendLine(
                            "    <item id=\"b$index\" href=\"${entry.path.removePrefix("OEBPS/")}\" media-type=\"${entry.mediaType}\"/>",
                        )
                    }
                    appendLine("  </manifest>")
                    appendLine("  <spine>")
                    spineEntries.forEachIndexed { index, _ ->
                        val properties = if (fixedLayout) " properties=\"rendition:layout-pre-paginated\"" else ""
                        appendLine("    <itemref idref=\"c$index\"$properties/>")
                    }
                    appendLine("  </spine>")
                    appendLine("</package>")
                },
            )
            spineEntries.forEach { (path, content) -> addText(path, content) }
            binaryEntries.forEach(::addBinary)
        }
    }

    private fun tinyPngBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun dispatchLinkTap(textView: SelectionAwareTextView, targetText: String) {
        val fullText = textView.text?.toString().orEmpty()
        val start = fullText.indexOf(targetText)
        check(start >= 0) { "Expected text view to contain tappable text: $targetText" }
        val offset = (start + (targetText.length / 2).coerceAtLeast(0)).coerceAtMost(fullText.length - 1)
        val layout = checkNotNull(textView.layout) { "TextView layout unavailable for link tap dispatch" }
        val line = layout.getLineForOffset(offset)
        val x = textView.totalPaddingLeft + layout.getPrimaryHorizontal(offset) - textView.scrollX
        val y = textView.totalPaddingTop + ((layout.getLineTop(line) + layout.getLineBottom(line)) / 2f) - textView.scrollY
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 48, MotionEvent.ACTION_UP, x, y, 0)
        try {
            textView.dispatchTouchEvent(down)
            textView.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
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

    private fun evidenceDir(): File =
        checkNotNull(appContext.getExternalFilesDir("epub04-06-runtime-smoke")) {
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

    private fun MainActivity.findReaderSurface(): View =
        checkNotNull(window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }) {
            "Unable to find reader surface"
        }

    private fun MainActivity.findEpubScrollRecyclerView(): View? =
        findReaderSurface().findDescendant { view ->
            view.adapterClassName() == EPUB_ADAPTER_CLASS_NAME
        }

    private fun scrollRecyclerToPosition(recyclerView: View, position: Int) {
        runCatching {
            val layoutManager = recyclerView.javaClass.getMethod("getLayoutManager").invoke(recyclerView)
            layoutManager?.javaClass?.getMethod(
                "scrollToPositionWithOffset",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )?.invoke(layoutManager, position, 0)
        }.recoverCatching {
            recyclerView.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType)
                .invoke(recyclerView, position)
        }
    }

    private fun View.textViewAtPosition(position: Int): SelectionAwareTextView? =
        findViewHolderItemViewAtPosition(position)
            ?.findDescendant { view -> view is SelectionAwareTextView } as? SelectionAwareTextView

    private fun View.imageViewAtPosition(position: Int): ImageView? =
        findViewHolderItemViewAtPosition(position)
            ?.findDescendant { view -> view is ImageView } as? ImageView

    private fun View.findViewHolderItemViewAtPosition(position: Int): View? =
        runCatching {
            val holder = javaClass.getMethod("findViewHolderForAdapterPosition", Int::class.javaPrimitiveType)
                .invoke(this, position) ?: return@runCatching null
            holder.javaClass.getField("itemView").get(holder) as? View
        }.getOrNull()

    private fun View.adapterItemCount(): Int =
        runCatching {
            val adapter = javaClass.getMethod("getAdapter").invoke(this) ?: return@runCatching -1
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        }.getOrDefault(-1)

    private fun SelectionAwareTextView.wasInteractiveTapConsumed(): Boolean =
        getTag(RenderApiR.id.selection_aware_interactive_tap_consumed) == true

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun View.adapterClassName(): String? =
        runCatching {
            javaClass.getMethod("getAdapter").invoke(this)?.javaClass?.name
        }.getOrNull()

    private data class BinaryEntry(
        val path: String,
        val bytes: ByteArray,
        val mediaType: String,
    )

    private data class ImageRuntimeSummary(
        val itemCount: Int,
        val coverDescription: String?,
        val coverDrawablePresent: Boolean,
        val coverHasTextSibling: Boolean,
        val chapterText: String?,
        val inlineImageDescription: String?,
        val inlineImageDrawablePresent: Boolean,
        val trailingText: String?,
    )

    private data class LinkRuntimeBaseline(
        val clickableSpanCount: Int,
        val linksClickable: Boolean,
        val externalTapConsumed: Boolean,
        val internalTapConsumed: Boolean,
    )

    private data class LinkRuntimeResult(
        val externalMonitorHits: Int,
        val targetText: String?,
        val dbLocator: String?,
        val dbTotalProgress: Float?,
    )

    private data class DegradationRuntimeSummary(
        val itemCount: Int,
        val headingText: String?,
        val headingTextSizePx: Float,
        val headingBold: Boolean,
        val tableText: String?,
        val tableUsesMonospace: Boolean,
        val preText: String?,
        val preUsesMonospace: Boolean,
        val preCanScrollHorizontally: Boolean,
        val quoteText: String?,
        val quotePaddingLeft: Int,
        val tablePaddingLeft: Int,
        val listText: String?,
        val listPaddingLeft: Int,
        val nestedListText: String?,
        val nestedListPaddingLeft: Int,
        val unknownText: String?,
    )

    private companion object {
        private const val EPUB_READER_DESC = "阅读内容，捏合调整字号"
        private const val DB_NAME = "readflow.db"
        private const val EPUB_ADAPTER_CLASS_NAME = "dev.readflow.render.epub.EpubParaAdapter"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
