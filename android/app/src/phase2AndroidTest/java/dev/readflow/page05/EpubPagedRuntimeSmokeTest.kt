package dev.readflow.page05

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.compose.ui.platform.ComposeView
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
import dev.readflow.render.epub.R as EpubR
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class EpubPagedRuntimeSmokeTest {

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
    fun epubPagedTextRuntimeStateRetiresWhenSwitchingBackToScroll() {
        val title = "page05-compose-${UUID.randomUUID().toString().take(8)}"
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Alpha selection sentinel keeps compose runtime visible while paged mode is active.</p>
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))
            switchReaderToPagedMode()
            waitForCondition("expected EPUB reader to remount into paged mode") {
                scenario.withActivity { activity ->
                    activity.findEpubViewPager() != null && activity.currentEpubComposePageRootOrNull() != null
                }
            }

            val pagedSummary = scenario.withActivity { activity ->
                val composePage = checkNotNull(activity.currentEpubComposePageRootOrNull()) {
                    "Unable to find current paged EPUB compose page"
                }
                val pageText = composePage.composeTextSurface().orEmpty()
                val selectionStart = pageText.indexOf("selection")
                check(selectionStart >= 0) { "Expected test text to contain selection sentinel" }
                val selectionEnd = selectionStart + "selection".length
                composePage.invokeSelectionCallback(selectionStart, selectionEnd)
                PagedComposeSummary(
                    pageText = pageText,
                    pageProgress = composePage.composePageProgressDescription(),
                    surfaceVisible = composePage.composeTextSurfaceVisible(),
                    selectionEnabled = composePage.composeSelectionEnabled(),
                    semanticsExposed = composePage.composeSemanticsExposed(),
                    selectionRange = composePage.composeSelectionRangeText(),
                    stateDescription = composePage.compatStateDescription(),
                ) to composePage
            }
            takeScreenshot("mode-switch-paged-baseline.png")
            dumpHierarchy("mode-switch-paged-baseline.xml")

            waitForObject(By.text("滚动")).click()
            waitForCondition("expected EPUB reader to return to scroll mode") {
                scenario.withActivity { activity ->
                    activity.findEpubScrollRecyclerView() != null && activity.findEpubViewPager() == null
                }
            }

            val retiredSummary = scenario.withActivity {
                RetiredComposeSummary(
                    pageProgress = pagedSummary.second.composePageProgressDescription(),
                    surfaceVisible = pagedSummary.second.composeTextSurfaceVisible(),
                    selectionEnabled = pagedSummary.second.composeSelectionEnabled(),
                    semanticsExposed = pagedSummary.second.composeSemanticsExposed(),
                    selectionRange = pagedSummary.second.composeSelectionRangeText(),
                    selectionHighlight = pagedSummary.second.composeSelectionHighlightRangeText(),
                    selectionCallbackPresent = pagedSummary.second.composeSelectionCallbackPresent(),
                    stateDescription = pagedSummary.second.compatStateDescription(),
                )
            }
            takeScreenshot("mode-switch-scroll-after.png")
            dumpHierarchy("mode-switch-scroll-after.xml")

            writeTextEvidence(
                "mode-switch-summary.txt",
                buildString {
                    appendLine("page_text=${pagedSummary.first.pageText}")
                    appendLine("paged_progress=${pagedSummary.first.pageProgress}")
                    appendLine("paged_surface_visible=${pagedSummary.first.surfaceVisible}")
                    appendLine("paged_selection_enabled=${pagedSummary.first.selectionEnabled}")
                    appendLine("paged_semantics_exposed=${pagedSummary.first.semanticsExposed}")
                    appendLine("paged_selection_range=${pagedSummary.first.selectionRange}")
                    appendLine("paged_state_description=${pagedSummary.first.stateDescription}")
                    appendLine("retired_progress=${retiredSummary.pageProgress}")
                    appendLine("retired_surface_visible=${retiredSummary.surfaceVisible}")
                    appendLine("retired_selection_enabled=${retiredSummary.selectionEnabled}")
                    appendLine("retired_semantics_exposed=${retiredSummary.semanticsExposed}")
                    appendLine("retired_selection_range=${retiredSummary.selectionRange}")
                    appendLine("retired_selection_highlight=${retiredSummary.selectionHighlight}")
                    appendLine("retired_selection_callback_present=${retiredSummary.selectionCallbackPresent}")
                    appendLine("retired_state_description=${retiredSummary.stateDescription}")
                },
            )

            assertEquals("selection", pagedSummary.first.pageText.substring(
                pagedSummary.first.pageText.indexOf("selection"),
                pagedSummary.first.pageText.indexOf("selection") + "selection".length,
            ))
            assertTrue(pagedSummary.first.surfaceVisible)
            assertTrue(pagedSummary.first.selectionEnabled)
            assertTrue(pagedSummary.first.semanticsExposed)
            assertEquals("第 1 页，共 1 页", pagedSummary.first.pageProgress)
            assertEquals("(6, 15)", pagedSummary.first.selectionRange)
            assertEquals("第 1 页，共 1 页", pagedSummary.first.stateDescription)
            assertFalse(retiredSummary.surfaceVisible)
            assertFalse(retiredSummary.selectionEnabled)
            assertFalse(retiredSummary.semanticsExposed)
            assertNull(retiredSummary.pageProgress)
            assertNull(retiredSummary.selectionRange)
            assertNull(retiredSummary.selectionHighlight)
            assertFalse(retiredSummary.selectionCallbackPresent)
            assertNull(retiredSummary.stateDescription)
        }
    }

    @Test
    fun epubPagedInternalLinkNavigatesToImageFragmentAndClearsSelectionRuntime() {
        val title = "page05-link-${UUID.randomUUID().toString().take(8)}"
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Read the <a href="notes.xhtml#scene">scene</a> after selection.</p>
                      </body>
                    </html>
                """.trimIndent(),
                "OEBPS/notes.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Q</p>
                        <img id="scene" src="scene.png" alt="Scene art"/>
                        <p>R</p>
                      </body>
                    </html>
                """.trimIndent(),
            ),
            binaryEntries = listOf(
                BinaryEntry("OEBPS/scene.png", tinyPngBytes(Color.rgb(0x25, 0x69, 0xBE)), "image/png"),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))
            val importedBook = waitForBookByTitle(title)

            switchReaderToPagedMode()
            waitForCondition("expected EPUB reader to expose a paged compose page before invoking link callback") {
                scenario.withActivity { activity -> activity.currentEpubComposePageRootOrNull() != null }
            }

            val linkNavigation = scenario.withActivity { activity ->
                val composePage = checkNotNull(activity.currentEpubComposePageRootOrNull()) {
                    "Unable to find current paged EPUB compose page"
                }
                val pageText = composePage.composeTextSurface().orEmpty()
                val selectionStart = pageText.indexOf("Read")
                check(selectionStart >= 0) { "Expected test text to contain link-page selection anchor" }
                val selectionEnd = selectionStart + "Read".length
                composePage.invokeSelectionCallback(selectionStart, selectionEnd)
                val links = composePage.composeLinks()
                check(links.isNotEmpty()) { "Expected current paged EPUB text page to expose inline links" }
                val beforeSelectionRange = composePage.composeSelectionRangeText()
                val firstLink = checkNotNull(links.firstOrNull()) {
                    "Expected current paged EPUB text page to expose at least one inline link"
                }
                composePage.invokeLinkCallback(firstLink)
                LinkNavigationBaseline(
                    pageText = pageText,
                    linksCount = links.size,
                    beforeSelectionRange = beforeSelectionRange,
                    oldComposePage = composePage,
                )
            }
            takeScreenshot("link-nav-before.png")
            dumpHierarchy("link-nav-before.xml")

            waitForCondition("expected paged internal link to navigate onto the image fragment page") {
                scenario.withActivity { activity ->
                    activity.currentEpubImagePageViewOrNull()?.contentDescription?.toString()?.contains("Scene art") == true
                }
            }
            waitForCondition(
                message = "expected reading_progress row to persist the image-fragment locator after link navigation",
                timeoutMs = DB_TIMEOUT_MS,
            ) {
                latestProgress(importedBook.id)?.locatorJson?.contains("\"spineIndex\":1") == true
            }

            val afterNavigation = scenario.withActivity { activity ->
                val imageView = checkNotNull(activity.currentEpubImagePageViewOrNull()) {
                    "Unable to find current image page after internal link navigation"
                }
                LinkNavigationResult(
                    currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1,
                    itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: -1,
                    imageDescription = imageView.contentDescription?.toString(),
                    oldSelectionRange = linkNavigation.oldComposePage.composeSelectionRangeText(),
                    oldSelectionHighlight = linkNavigation.oldComposePage.composeSelectionHighlightRangeText(),
                    dbLocator = latestProgress(importedBook.id)?.locatorJson,
                    dbTotalProgress = latestProgress(importedBook.id)?.totalProgression,
                )
            }
            copyDatabaseSnapshot("link-nav-image-fragment")
            takeScreenshot("link-nav-after-image.png")
            dumpHierarchy("link-nav-after-image.xml")

            writeTextEvidence(
                "link-navigation-summary.txt",
                buildString {
                    appendLine("page_text=${linkNavigation.pageText}")
                    appendLine("links_count=${linkNavigation.linksCount}")
                    appendLine("selection_before_link=${linkNavigation.beforeSelectionRange}")
                    appendLine("image_description=${afterNavigation.imageDescription}")
                    appendLine("pager_current_item=${afterNavigation.currentItem}")
                    appendLine("pager_item_count=${afterNavigation.itemCount}")
                    appendLine("old_selection_after_link=${afterNavigation.oldSelectionRange}")
                    appendLine("old_selection_highlight_after_link=${afterNavigation.oldSelectionHighlight}")
                    appendLine("db_locator=${afterNavigation.dbLocator}")
                    appendLine("db_total_progress=${afterNavigation.dbTotalProgress}")
                },
            )

            assertEquals(1, linkNavigation.linksCount)
            assertEquals("(0, 4)", linkNavigation.beforeSelectionRange)
            assertTrue(afterNavigation.currentItem > 0)
            assertTrue(afterNavigation.itemCount >= 2)
            assertTrue(afterNavigation.imageDescription?.contains("Scene art") == true)
            assertNull(afterNavigation.oldSelectionRange)
            assertNull(afterNavigation.oldSelectionHighlight)
            assertTrue(afterNavigation.dbLocator?.contains("\"spineIndex\":1") == true)
            assertTrue(afterNavigation.dbLocator?.contains("\"elementIndex\":1") == true)
        }
    }

    @Test
    fun epubPagedStartsOnImageOnlyCoverWithoutBlankTextPageRuntime() {
        val title = "page05-cover-${UUID.randomUUID().toString().take(8)}"
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
                        <p>Chapter one text starts immediately after the image-only cover.</p>
                      </body>
                    </html>
                """.trimIndent(),
            ),
            binaryEntries = listOf(
                BinaryEntry("OEBPS/cover.png", tinyPngBytes(Color.rgb(0xD6, 0x7C, 0x2F)), "image/png"),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected EPUB reader to enter paged mode on image-only cover") {
                scenario.withActivity { activity ->
                    activity.findEpubViewPager() != null && activity.currentEpubImagePageViewOrNull() != null
                }
            }

            val baseline = scenario.withActivity { activity ->
                val imageView = checkNotNull(activity.currentEpubImagePageViewOrNull()) {
                    "Unable to find image-only cover page"
                }
                CoverBaseline(
                    currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1,
                    itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: -1,
                    imageDescription = imageView.contentDescription?.toString(),
                    hasComposePage = activity.currentEpubComposePageRootOrNull() != null,
                )
            }
            takeScreenshot("cover-image-baseline.png")
            dumpHierarchy("cover-image-baseline.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCondition("expected image-only cover reader to advance onto the first text page") {
                scenario.withActivity { activity ->
                    val composePage = activity.currentEpubComposePageRootOrNull()
                    composePage?.composeTextSurface()?.contains("Chapter one text") == true
                }
            }

            val afterNext = scenario.withActivity { activity ->
                val composePage = checkNotNull(activity.currentEpubComposePageRootOrNull()) {
                    "Unable to find first text page after image-only cover"
                }
                CoverAfterNext(
                    currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1,
                    pageText = composePage.composeTextSurface(),
                    pageProgress = composePage.composePageProgressDescription(),
                    currentImageDescription = activity.currentEpubImagePageViewOrNull()?.contentDescription?.toString(),
                )
            }
            takeScreenshot("cover-after-next.png")
            dumpHierarchy("cover-after-next.xml")

            writeTextEvidence(
                "image-cover-summary.txt",
                buildString {
                    appendLine("baseline_current_item=${baseline.currentItem}")
                    appendLine("baseline_item_count=${baseline.itemCount}")
                    appendLine("baseline_image_description=${baseline.imageDescription}")
                    appendLine("baseline_has_compose_page=${baseline.hasComposePage}")
                    appendLine("after_next_current_item=${afterNext.currentItem}")
                    appendLine("after_next_page_text=${afterNext.pageText}")
                    appendLine("after_next_page_progress=${afterNext.pageProgress}")
                    appendLine("after_next_current_image_description=${afterNext.currentImageDescription}")
                },
            )

            assertEquals(0, baseline.currentItem)
            assertEquals(2, baseline.itemCount)
            assertTrue(baseline.imageDescription?.contains("Cover art") == true)
            assertFalse(baseline.hasComposePage)
            assertEquals(1, afterNext.currentItem)
            assertTrue(afterNext.pageText?.contains("Chapter one text") == true)
            assertEquals("第 2 页，共 2 页", afterNext.pageProgress)
            assertNull(afterNext.currentImageDescription)
        }
    }

    private fun readerIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, "application/epub+zip")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("page05-epub-runtime-smoke", uri)
        }

    private fun createEpubUri(
        fileName: String,
        spineEntries: List<Pair<String, String>>,
        binaryEntries: List<BinaryEntry> = emptyList(),
    ): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeEpub(file, spineEntries, binaryEntries)
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
                    appendLine("<package version=\"3.0\">")
                    appendLine("  <manifest>")
                    spineEntries.forEachIndexed { index, (path, _) ->
                        appendLine(
                            "    <item id=\"c$index\" href=\"${path.removePrefix("OEBPS/")}\" media-type=\"application/xhtml+xml\"/>",
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
                        appendLine("    <itemref idref=\"c$index\"/>")
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

    private fun switchReaderToPagedMode() {
        openBottomPanel(buttonText = "排版", expectedText = "阅读正文预览")
        waitForObject(By.text("分页")).click()
        waitForObject(By.text("滚动"))
    }

    private fun openBottomPanel(buttonText: String, expectedText: String) {
        if (device.wait(Until.findObject(By.text(buttonText)), 750) == null) {
            waitForObject(By.desc(EPUB_READER_DESC)).click()
            device.waitForIdle()
        }
        waitForObject(By.text(buttonText)).click()
        waitForObject(By.text(expectedText))
    }

    private fun performReaderAccessibilityAction(
        scenario: ActivityScenario<MainActivity>,
        action: Int,
    ) {
        val handled = scenario.withActivity { activity ->
            activity.findReaderSurface().performAccessibilityAction(action, null)
        }
        check(handled) { "Accessibility action $action was not handled by the reader surface" }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
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
        checkNotNull(appContext.getExternalFilesDir("page05-epub-runtime-smoke")) {
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

    private fun MainActivity.findEpubViewPager(): View? =
        findReaderSurface().findDescendant { view ->
            view.javaClass.name == VIEW_PAGER_CLASS_NAME
        }

    private fun MainActivity.findEpubScrollRecyclerView(): View? =
        findReaderSurface().findDescendant { view ->
            view.javaClass.name == RECYCLER_VIEW_CLASS_NAME &&
                view.adapterClassName() == EPUB_ADAPTER_CLASS_NAME
        }

    private fun MainActivity.currentEpubComposePageRootOrNull(): ComposeView? {
        val pager = findEpubViewPager() ?: return null
        val currentItem = pager.pagerCurrentItem()
        val total = pager.pagerAdapterItemCount()
        val expectedProgress = pageLabel(currentItem, total)
        return findReaderSurface().findDescendant { view ->
            view is ComposeView &&
                view.isShown &&
                view.getTag(EpubR.id.epub_compose_page_progress_description) == expectedProgress
        } as? ComposeView
    }

    private fun MainActivity.currentEpubImagePageViewOrNull(): ImageView? {
        val pager = findEpubViewPager() ?: return null
        val currentItem = pager.pagerCurrentItem()
        val total = pager.pagerAdapterItemCount()
        val expectedProgress = pageLabel(currentItem, total)
        return findReaderSurface().findDescendant { view ->
            view is ImageView &&
                view.isShown &&
                view.contentDescription?.toString()?.contains(expectedProgress) == true
        } as? ImageView
    }

    private fun ComposeView.composeTextSurface(): String? =
        getTag(EpubR.id.epub_compose_text_surface) as? String

    private fun ComposeView.composeTextSurfaceVisible(): Boolean =
        getTag(EpubR.id.epub_compose_text_surface_visible) == true

    private fun ComposeView.composeSelectionEnabled(): Boolean =
        getTag(EpubR.id.epub_compose_text_selection_enabled) == true

    private fun ComposeView.composeSemanticsExposed(): Boolean =
        getTag(EpubR.id.epub_compose_text_semantics_exposed) == true

    private fun ComposeView.composePageProgressDescription(): String? =
        getTag(EpubR.id.epub_compose_page_progress_description) as? String

    private fun ComposeView.composeSelectionRangeText(): String? =
        getTag(EpubR.id.epub_compose_text_selection_range)?.toString()

    private fun ComposeView.composeSelectionHighlightRangeText(): String? =
        getTag(EpubR.id.epub_compose_text_selection_highlight_range)?.toString()

    private fun ComposeView.composeSelectionCallbackPresent(): Boolean =
        getTag(EpubR.id.epub_compose_text_selection_callback) != null

    private fun ComposeView.composeLinks(): List<*> =
        getTag(EpubR.id.epub_compose_text_links) as? List<*> ?: emptyList<Any>()

    private fun ComposeView.invokeSelectionCallback(start: Int, end: Int) {
        @Suppress("UNCHECKED_CAST")
        val callback = getTag(EpubR.id.epub_compose_text_selection_callback) as? (Int, Int) -> Unit
        checkNotNull(callback) { "Expected compose selection callback to be present on paged EPUB text page" }
        callback.invoke(start, end)
    }

    private fun ComposeView.invokeLinkCallback(link: Any) {
        @Suppress("UNCHECKED_CAST")
        val callback = getTag(EpubR.id.epub_compose_text_link_callback) as? (Any) -> Unit
        checkNotNull(callback) { "Expected compose link callback to be present on paged EPUB text page" }
        callback.invoke(link)
    }

    private fun ComposeView.compatStateDescription(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription?.toString()
        } else {
            null
        }

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

    private fun View.pagerCurrentItem(): Int =
        runCatching {
            javaClass.getMethod("getCurrentItem").invoke(this) as Int
        }.getOrDefault(0)

    private fun View.pagerAdapterItemCount(): Int =
        runCatching {
            val adapter = javaClass.getMethod("getAdapter").invoke(this) ?: return@runCatching -1
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        }.getOrDefault(-1)

    private fun pageLabel(pageIndex: Int, totalPages: Int): String =
        "第 ${pageIndex + 1} 页，共 $totalPages 页"

    private data class BinaryEntry(
        val path: String,
        val bytes: ByteArray,
        val mediaType: String,
    )

    private data class PagedComposeSummary(
        val pageText: String,
        val pageProgress: String?,
        val surfaceVisible: Boolean,
        val selectionEnabled: Boolean,
        val semanticsExposed: Boolean,
        val selectionRange: String?,
        val stateDescription: String?,
    )

    private data class RetiredComposeSummary(
        val pageProgress: String?,
        val surfaceVisible: Boolean,
        val selectionEnabled: Boolean,
        val semanticsExposed: Boolean,
        val selectionRange: String?,
        val selectionHighlight: String?,
        val selectionCallbackPresent: Boolean,
        val stateDescription: String?,
    )

    private data class LinkNavigationBaseline(
        val pageText: String,
        val linksCount: Int,
        val beforeSelectionRange: String?,
        val oldComposePage: ComposeView,
    )

    private data class LinkNavigationResult(
        val currentItem: Int,
        val itemCount: Int,
        val imageDescription: String?,
        val oldSelectionRange: String?,
        val oldSelectionHighlight: String?,
        val dbLocator: String?,
        val dbTotalProgress: Float?,
    )

    private data class CoverBaseline(
        val currentItem: Int,
        val itemCount: Int,
        val imageDescription: String?,
        val hasComposePage: Boolean,
    )

    private data class CoverAfterNext(
        val currentItem: Int,
        val pageText: String?,
        val pageProgress: String?,
        val currentImageDescription: String?,
    )

    private companion object {
        private const val EPUB_READER_DESC = "阅读内容，捏合调整字号"
        private const val DB_NAME = "readflow.db"
        private const val VIEW_PAGER_CLASS_NAME = "androidx.viewpager2.widget.ViewPager2"
        private const val RECYCLER_VIEW_CLASS_NAME = "androidx.recyclerview.widget.RecyclerView"
        private const val EPUB_ADAPTER_CLASS_NAME = "dev.readflow.render.epub.EpubParaAdapter"
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
