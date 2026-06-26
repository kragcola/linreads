package dev.readflow.page05

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import kotlin.math.max
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
        runCatching { device.setOrientationNatural() }
        runCatching { device.unfreezeRotation() }
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

    @Test
    fun epubPagedPacksMicroParagraphsWithoutOneSentencePagesRuntime() {
        val title = "page05-packed-${UUID.randomUUID().toString().take(8)}"
        val lines = (1..36).map { index -> "Line ${index.toString().padStart(3, '0')}." }
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to lines.joinToString(
                    prefix = """
                        <html xmlns="http://www.w3.org/1999/xhtml">
                          <body>
                    """.trimIndent(),
                    separator = "\n",
                    postfix = """
                          </body>
                        </html>
                    """.trimIndent(),
                ) { line -> "<p>$line</p>" },
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected packed micro-paragraph EPUB reader to enter paged mode") {
                scenario.withActivity { activity ->
                    activity.findEpubViewPager() != null && activity.currentEpubComposePageRootOrNull() != null
                }
            }

            val baseline = scenario.withActivity { activity ->
                val composePage = checkNotNull(activity.currentEpubComposePageRootOrNull()) {
                    "Unable to find packed micro-paragraph Compose page"
                }
                PackedMicroBaseline(
                    currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1,
                    itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: -1,
                    pageText = composePage.composeTextSurface().orEmpty(),
                    pageProgress = composePage.composePageProgressDescription(),
                )
            }
            takeScreenshot("packed-micro-baseline.png")
            dumpHierarchy("packed-micro-baseline.xml")

            dispatchPinchOut(scenario.withActivity { activity -> activity.findReaderSurface() })
            waitForCondition("expected EPUB font size preference to increase after pinch preview") {
                runBlocking { settings.fontSize.first() > 18 }
            }
            waitForCondition("expected packed micro-paragraph EPUB page count to stay below paragraph count after pinch") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    itemCount in 1 until lines.size
                }
            }

            val afterPinch = scenario.withActivity { activity ->
                val composePage = checkNotNull(activity.currentEpubComposePageRootOrNull()) {
                    "Unable to find packed micro-paragraph page after pinch"
                }
                PackedMicroAfterPinch(
                    currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1,
                    itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: -1,
                    pageText = composePage.composeTextSurface().orEmpty(),
                    pageProgress = composePage.composePageProgressDescription(),
                    persistedFontSize = runBlocking { settings.fontSize.first() },
                )
            }
            takeScreenshot("packed-micro-after-pinch.png")
            dumpHierarchy("packed-micro-after-pinch.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCondition("expected packed micro-paragraph EPUB reader to advance to the next packed page") {
                scenario.withActivity { activity ->
                    (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > afterPinch.currentItem &&
                        activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                            ?.let { it != afterPinch.pageText } == true
                }
            }

            val afterNext = scenario.withActivity { activity ->
                val composePage = checkNotNull(activity.currentEpubComposePageRootOrNull()) {
                    "Unable to find packed micro-paragraph page after next"
                }
                PackedMicroAfterNext(
                    currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1,
                    pageText = composePage.composeTextSurface().orEmpty(),
                    pageProgress = composePage.composePageProgressDescription(),
                )
            }
            takeScreenshot("packed-micro-after-next.png")
            dumpHierarchy("packed-micro-after-next.xml")

            dragLineSpacingSliderToMax()
            waitForCondition("expected EPUB line spacing preference to increase after dragging the visible slider") {
                runBlocking { settings.lineSpacing.first() > 1.85f }
            }
            waitForCondition("expected packed micro-paragraph EPUB page count to stay below paragraph count after line spacing") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    itemCount in 1 until lines.size
                }
            }

            val afterLineSpacing = scenario.withActivity { activity ->
                val composePage = checkNotNull(activity.currentEpubComposePageRootOrNull()) {
                    "Unable to find packed micro-paragraph page after line spacing"
                }
                PackedMicroAfterLineSpacing(
                    currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1,
                    itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: -1,
                    pageText = composePage.composeTextSurface().orEmpty(),
                    pageProgress = composePage.composePageProgressDescription(),
                    persistedLineSpacing = runBlocking { settings.lineSpacing.first() },
                )
            }
            takeScreenshot("packed-micro-after-line-spacing.png")
            dumpHierarchy("packed-micro-after-line-spacing.xml")

            val firstPageLineCount = lines.count { line -> baseline.pageText.contains(line) }
            val afterPinchLineCount = lines.count { line -> afterPinch.pageText.contains(line) }
            val secondPageLineCount = lines.count { line -> afterNext.pageText.contains(line) }
            val afterLineSpacingLineCount = lines.count { line -> afterLineSpacing.pageText.contains(line) }
            writeTextEvidence(
                "packed-micro-summary.txt",
                buildString {
                    appendLine("paragraph_count=${lines.size}")
                    appendLine("page_count=${baseline.itemCount}")
                    appendLine("baseline_current_item=${baseline.currentItem}")
                    appendLine("baseline_page_progress=${baseline.pageProgress}")
                    appendLine("baseline_line_count=$firstPageLineCount")
                    appendLine("baseline_page_text=${baseline.pageText}")
                    appendLine("after_pinch_current_item=${afterPinch.currentItem}")
                    appendLine("after_pinch_page_count=${afterPinch.itemCount}")
                    appendLine("after_pinch_page_progress=${afterPinch.pageProgress}")
                    appendLine("after_pinch_line_count=$afterPinchLineCount")
                    appendLine("after_pinch_persisted_font_size_sp=${afterPinch.persistedFontSize}")
                    appendLine("after_pinch_page_text=${afterPinch.pageText}")
                    appendLine("after_next_current_item=${afterNext.currentItem}")
                    appendLine("after_next_page_progress=${afterNext.pageProgress}")
                    appendLine("after_next_line_count=$secondPageLineCount")
                    appendLine("after_next_page_text=${afterNext.pageText}")
                    appendLine("after_line_spacing_current_item=${afterLineSpacing.currentItem}")
                    appendLine("after_line_spacing_page_count=${afterLineSpacing.itemCount}")
                    appendLine("after_line_spacing_page_progress=${afterLineSpacing.pageProgress}")
                    appendLine("after_line_spacing_line_count=$afterLineSpacingLineCount")
                    appendLine("after_line_spacing_persisted=${afterLineSpacing.persistedLineSpacing}")
                    appendLine("after_line_spacing_page_text=${afterLineSpacing.pageText}")
                },
            )

            assertEquals(0, baseline.currentItem)
            assertTrue("expected at least one paged EPUB page", baseline.itemCount > 0)
            assertTrue(
                "expected packed paging to avoid one page per paragraph",
                baseline.itemCount < lines.size,
            )
            assertTrue(
                "expected the first paged Compose page to contain multiple short paragraphs",
                firstPageLineCount >= 2,
            )
            assertTrue(
                "expected pinch to increase the persisted EPUB font size",
                afterPinch.persistedFontSize > 18,
            )
            assertTrue(
                "expected packed paging to stay below paragraph count after typography changes",
                afterPinch.itemCount in 1 until lines.size,
            )
            assertTrue(
                "expected repaginated page to keep readable packed content",
                afterPinchLineCount >= 1,
            )
            assertTrue(afterNext.currentItem > afterPinch.currentItem)
            assertTrue(
                "expected the next packed page to contain readable paragraph text",
                secondPageLineCount >= 1,
            )
            assertTrue(
                "expected line spacing change to persist above the default",
                afterLineSpacing.persistedLineSpacing > 1.85f,
            )
            assertTrue(
                "expected packed paging to stay below paragraph count after line spacing changes",
                afterLineSpacing.itemCount in 1 until lines.size,
            )
            assertTrue(
                "expected line spacing repagination to avoid one short paragraph per page",
                afterLineSpacingLineCount >= 2,
            )
        }
    }

    @Test
    fun epubPagedPacksMicroParagraphsAfterOrientationChangeRuntime() {
        val title = "page05-rotate-${UUID.randomUUID().toString().take(8)}"
        val lines = (1..48).map { index -> "Rotate line ${index.toString().padStart(3, '0')}." }
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to lines.joinToString(
                    prefix = """
                        <html xmlns="http://www.w3.org/1999/xhtml">
                          <body>
                    """.trimIndent(),
                    separator = "\n",
                    postfix = """
                          </body>
                        </html>
                    """.trimIndent(),
                ) { line -> "<p>$line</p>" },
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected rotate EPUB reader to enter paged mode") {
                scenario.withActivity { activity ->
                    activity.findEpubViewPager() != null && activity.currentEpubComposePageRootOrNull() != null
                }
            }

            val baseline = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-rotate-portrait.png")
            dumpHierarchy("packed-rotate-portrait.xml")

            device.setOrientationLeft()
            device.waitForIdle()
            waitForCondition("expected emulator to report landscape orientation after rotation") {
                scenario.withActivity { activity ->
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                }
            }
            waitForCondition("expected packed EPUB reader to rebuild into a paged compose page after rotation") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    itemCount in 1 until lines.size && lines.any { line -> pageText.contains(line) }
                }
            }

            val landscape = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-rotate-landscape.png")
            dumpHierarchy("packed-rotate-landscape.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCondition("expected landscape packed EPUB reader to advance to another packed page") {
                scenario.withActivity { activity ->
                    (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > landscape.currentItem &&
                        activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                            ?.let { it != landscape.pageText } == true
                }
            }

            val landscapeAfterNext = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-rotate-landscape-after-next.png")
            dumpHierarchy("packed-rotate-landscape-after-next.xml")

            val baselineLineCount = lines.count { line -> baseline.pageText.contains(line) }
            val landscapeLineCount = lines.count { line -> landscape.pageText.contains(line) }
            val landscapeNextLineCount = lines.count { line -> landscapeAfterNext.pageText.contains(line) }
            writeTextEvidence(
                "packed-rotation-summary.txt",
                buildString {
                    appendLine("paragraph_count=${lines.size}")
                    appendLine("portrait_orientation=${baseline.orientation}")
                    appendLine("portrait_surface_width=${baseline.surfaceWidth}")
                    appendLine("portrait_surface_height=${baseline.surfaceHeight}")
                    appendLine("portrait_page_count=${baseline.itemCount}")
                    appendLine("portrait_current_item=${baseline.currentItem}")
                    appendLine("portrait_page_progress=${baseline.pageProgress}")
                    appendLine("portrait_line_count=$baselineLineCount")
                    appendLine("portrait_page_text=${baseline.pageText}")
                    appendLine("landscape_orientation=${landscape.orientation}")
                    appendLine("landscape_surface_width=${landscape.surfaceWidth}")
                    appendLine("landscape_surface_height=${landscape.surfaceHeight}")
                    appendLine("landscape_page_count=${landscape.itemCount}")
                    appendLine("landscape_current_item=${landscape.currentItem}")
                    appendLine("landscape_page_progress=${landscape.pageProgress}")
                    appendLine("landscape_line_count=$landscapeLineCount")
                    appendLine("landscape_page_text=${landscape.pageText}")
                    appendLine("landscape_next_current_item=${landscapeAfterNext.currentItem}")
                    appendLine("landscape_next_page_progress=${landscapeAfterNext.pageProgress}")
                    appendLine("landscape_next_line_count=$landscapeNextLineCount")
                    appendLine("landscape_next_page_text=${landscapeAfterNext.pageText}")
                },
            )

            assertEquals(Configuration.ORIENTATION_PORTRAIT, baseline.orientation)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, landscape.orientation)
            assertTrue("expected portrait reader surface to be taller than wide", baseline.surfaceHeight > baseline.surfaceWidth)
            assertTrue("expected landscape reader surface to be wider than tall", landscape.surfaceWidth > landscape.surfaceHeight)
            assertTrue("expected portrait packed paging to avoid one page per paragraph", baseline.itemCount in 1 until lines.size)
            assertTrue("expected landscape packed paging to avoid one page per paragraph", landscape.itemCount in 1 until lines.size)
            assertTrue("expected portrait page to contain multiple short paragraphs", baselineLineCount >= 2)
            assertTrue("expected landscape page to contain multiple short paragraphs", landscapeLineCount >= 2)
            assertTrue(landscapeAfterNext.currentItem > landscape.currentItem)
            assertTrue("expected landscape next page to contain readable paragraph text", landscapeNextLineCount >= 1)
        }
    }

    @Test
    fun epubPagedPacksMicroParagraphsWithMaxTypographyInLandscapeRuntime() = runBlocking {
        settings.setFontSize(32)
        settings.setLineSpacing(2.2f)
        device.setOrientationLeft()
        device.waitForIdle()

        val title = "page05-a11y-max-${UUID.randomUUID().toString().take(8)}"
        val lines = (1..72).map { index -> "A11y line ${index.toString().padStart(3, '0')}." }
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to lines.joinToString(
                    prefix = """
                        <html xmlns="http://www.w3.org/1999/xhtml">
                          <body>
                    """.trimIndent(),
                    separator = "\n",
                    postfix = """
                          </body>
                        </html>
                    """.trimIndent(),
                ) { line -> "<p>$line</p>" },
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))
            waitForCondition("expected max-typography reader to launch in landscape") {
                scenario.withActivity { activity ->
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                }
            }

            switchReaderToPagedMode()
            waitForCondition("expected max-typography EPUB reader to enter paged mode") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    itemCount in 1 until lines.size && lines.any { line -> pageText.contains(line) }
                }
            }

            val baseline = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-max-typography-landscape.png")
            dumpHierarchy("packed-max-typography-landscape.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCondition("expected max-typography landscape EPUB reader to advance to another packed page") {
                scenario.withActivity { activity ->
                    (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > baseline.currentItem &&
                        activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                            ?.let { it != baseline.pageText } == true
                }
            }

            val afterNext = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-max-typography-landscape-after-next.png")
            dumpHierarchy("packed-max-typography-landscape-after-next.xml")

            val baselineLineCount = lines.count { line -> baseline.pageText.contains(line) }
            val nextLineCount = lines.count { line -> afterNext.pageText.contains(line) }
            val persistedFontSize = settings.fontSize.first()
            val persistedLineSpacing = settings.lineSpacing.first()
            writeTextEvidence(
                "packed-max-typography-summary.txt",
                buildString {
                    appendLine("paragraph_count=${lines.size}")
                    appendLine("persisted_font_size_sp=$persistedFontSize")
                    appendLine("persisted_line_spacing=$persistedLineSpacing")
                    appendLine("landscape_orientation=${baseline.orientation}")
                    appendLine("landscape_surface_width=${baseline.surfaceWidth}")
                    appendLine("landscape_surface_height=${baseline.surfaceHeight}")
                    appendLine("landscape_page_count=${baseline.itemCount}")
                    appendLine("landscape_current_item=${baseline.currentItem}")
                    appendLine("landscape_page_progress=${baseline.pageProgress}")
                    appendLine("landscape_line_count=$baselineLineCount")
                    appendLine("landscape_page_text=${baseline.pageText}")
                    appendLine("landscape_next_current_item=${afterNext.currentItem}")
                    appendLine("landscape_next_page_progress=${afterNext.pageProgress}")
                    appendLine("landscape_next_line_count=$nextLineCount")
                    appendLine("landscape_next_page_text=${afterNext.pageText}")
                },
            )

            assertEquals(32, persistedFontSize)
            assertEquals(2.2f, persistedLineSpacing)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, baseline.orientation)
            assertTrue("expected max-typography reader surface to be wider than tall", baseline.surfaceWidth > baseline.surfaceHeight)
            assertTrue("expected max-typography packed paging to avoid one page per paragraph", baseline.itemCount in 1 until lines.size)
            assertTrue("expected max-typography page to contain multiple short paragraphs", baselineLineCount >= 2)
            assertTrue(afterNext.currentItem > baseline.currentItem)
            assertTrue("expected max-typography next page to contain readable paragraph text", nextLineCount >= 1)
        }
    }

    @Test
    fun epubPagedPacksCjkDialogueAndListItemsRuntime() {
        val title = "page05-cjk-packed-${UUID.randomUUID().toString().take(8)}"
        val chapterTitle = "第一章 雨声"
        val entries = (1..60).map { index ->
            when (index % 6) {
                0 -> "清单${index.toString().padStart(3, '0')}：旧伞。"
                1 -> "对话${index.toString().padStart(3, '0')}：“醒了吗？”"
                2 -> "对话${index.toString().padStart(3, '0')}：“嗯。”"
                3 -> "旁白${index.toString().padStart(3, '0')}：雨声很轻。"
                4 -> "旁白${index.toString().padStart(3, '0')}：灯被按暗。"
                else -> "引文${index.toString().padStart(3, '0')}：“别回头。”"
            }
        }
        val blankParagraphCount = entries.indices.count { index -> index > 0 && index % 10 == 0 }
        val bodyBlocks = entries.mapIndexed { index, text ->
            val spacer = if (index > 0 && index % 10 == 0) "<p> </p>" else ""
            val block = when {
                text.startsWith("清单") -> "<ul><li>$text</li></ul>"
                text.startsWith("引文") -> "<blockquote><p>$text</p></blockquote>"
                else -> "<p>$text</p>"
            }
            spacer + block
        }.joinToString(separator = "\n")
        val body = "<h1>$chapterTitle</h1>\n$bodyBlocks"
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        $body
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected CJK dialogue/list EPUB reader to enter packed paged mode") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    itemCount in 1 until entries.size &&
                        (pageText.contains(chapterTitle) || entries.any { entry -> pageText.contains(entry) })
                }
            }

            val baseline = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-cjk-dialogue-baseline.png")
            dumpHierarchy("packed-cjk-dialogue-baseline.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCondition("expected CJK dialogue/list packed reader to advance without landing on a blank page") {
                scenario.withActivity { activity ->
                    (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > baseline.currentItem &&
                        activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                            ?.let { pageText -> pageText.isNotBlank() && pageText != baseline.pageText } == true
                }
            }

            val afterNext = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-cjk-dialogue-after-next.png")
            dumpHierarchy("packed-cjk-dialogue-after-next.xml")

            val baselineEntryCount = entries.count { entry -> baseline.pageText.contains(entry) }
            val nextEntryCount = entries.count { entry -> afterNext.pageText.contains(entry) }
            writeTextEvidence(
                "packed-cjk-dialogue-summary.txt",
                buildString {
                    appendLine("chapter_title=$chapterTitle")
                    appendLine("content_entry_count=${entries.size}")
                    appendLine("blank_paragraph_count=$blankParagraphCount")
                    appendLine("page_count=${baseline.itemCount}")
                    appendLine("baseline_current_item=${baseline.currentItem}")
                    appendLine("baseline_page_progress=${baseline.pageProgress}")
                    appendLine("baseline_entry_count=$baselineEntryCount")
                    appendLine("baseline_page_text=${baseline.pageText}")
                    appendLine("next_current_item=${afterNext.currentItem}")
                    appendLine("next_page_progress=${afterNext.pageProgress}")
                    appendLine("next_entry_count=$nextEntryCount")
                    appendLine("next_page_text=${afterNext.pageText}")
                },
            )

            assertEquals(0, baseline.currentItem)
            assertTrue("expected CJK mixed content page to avoid blank first page", baseline.pageText.isNotBlank())
            assertTrue("expected CJK mixed content to start on the chapter title page", baseline.pageText.contains(chapterTitle))
            assertTrue("expected CJK mixed content paging to avoid one page per text block", baseline.itemCount in 1 until entries.size)
            assertTrue(afterNext.currentItem > baseline.currentItem)
            assertTrue("expected next CJK packed page to contain readable text", afterNext.pageText.isNotBlank())
            assertTrue("expected next CJK packed page to contain multiple tracked content blocks", nextEntryCount >= 2)
        }
    }

    @Test
    fun epubPagedSlicesLongUnspacedCjkParagraphAndPacksTailRuntime() {
        val title = "page05-cjk-long-${UUID.randomUUID().toString().take(8)}"
        val longSegments = (1..96).map { index ->
            "长段${index.toString().padStart(3, '0')}雨声贴着窗格落下灯影慢慢移过书页"
        }
        val longParagraph = longSegments.joinToString(separator = "")
        val tailEntries = (1..18).map { index ->
            "尾句${index.toString().padStart(3, '0')}：茶还温着。"
        }
        val maxPackedPageBudget = (longSegments.size + tailEntries.size) / 4
        val body = buildString {
            appendLine("<p>$longParagraph</p>")
            tailEntries.forEach { entry -> appendLine("<p>$entry</p>") }
        }
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        $body
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected long unspaced CJK EPUB reader to enter paged mode with readable text") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    itemCount in 2..maxPackedPageBudget &&
                        longSegments.any { segment -> pageText.contains(segment) }
                }
            }

            val baseline = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-cjk-long-baseline.png")
            dumpHierarchy("packed-cjk-long-baseline.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCondition("expected long unspaced CJK paragraph to advance to another readable page") {
                scenario.withActivity { activity ->
                    (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > baseline.currentItem &&
                        activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                            ?.let { pageText -> pageText.isNotBlank() && pageText != baseline.pageText } == true
                }
            }

            val afterNext = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-cjk-long-after-next.png")
            dumpHierarchy("packed-cjk-long-after-next.xml")

            var tailPage = afterNext
            var tailTurns = 0
            while (
                tailEntries.none { entry -> tailPage.pageText.contains(entry) } &&
                tailPage.currentItem < tailPage.itemCount - 1 &&
                tailTurns < tailPage.itemCount
            ) {
                val previousItem = tailPage.currentItem
                performReaderAccessibilityAction(
                    scenario = scenario,
                    action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                )
                waitForCondition("expected long CJK pagination to keep advancing toward tail entries") {
                    scenario.withActivity { activity ->
                        (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > previousItem
                    }
                }
                tailPage = scenario.withActivity { activity ->
                    activity.orientationPackedSummary()
                }
                tailTurns += 1
            }
            takeScreenshot("packed-cjk-long-tail.png")
            dumpHierarchy("packed-cjk-long-tail.xml")

            val baselineSegmentCount = longSegments.count { segment -> baseline.pageText.contains(segment) }
            val nextSegmentCount = longSegments.count { segment -> afterNext.pageText.contains(segment) }
            val tailEntryCount = tailEntries.count { entry -> tailPage.pageText.contains(entry) }
            writeTextEvidence(
                "packed-cjk-long-summary.txt",
                buildString {
                    appendLine("long_segment_count=${longSegments.size}")
                    appendLine("tail_entry_count=${tailEntries.size}")
                    appendLine("long_paragraph_has_space=${longParagraph.contains(' ')}")
                    appendLine("max_packed_page_budget=$maxPackedPageBudget")
                    appendLine("page_count=${baseline.itemCount}")
                    appendLine("baseline_current_item=${baseline.currentItem}")
                    appendLine("baseline_page_progress=${baseline.pageProgress}")
                    appendLine("baseline_long_segment_count=$baselineSegmentCount")
                    appendLine("baseline_page_text=${baseline.pageText}")
                    appendLine("next_current_item=${afterNext.currentItem}")
                    appendLine("next_page_progress=${afterNext.pageProgress}")
                    appendLine("next_long_segment_count=$nextSegmentCount")
                    appendLine("next_page_text=${afterNext.pageText}")
                    appendLine("tail_turns=$tailTurns")
                    appendLine("tail_current_item=${tailPage.currentItem}")
                    appendLine("tail_page_progress=${tailPage.pageProgress}")
                    appendLine("tail_entry_count=$tailEntryCount")
                    appendLine("tail_page_text=${tailPage.pageText}")
                },
            )

            assertEquals(0, baseline.currentItem)
            assertFalse("expected test paragraph to contain no ASCII spaces", longParagraph.contains(' '))
            assertTrue("expected long CJK page count to stay well below tracked segment count", baseline.itemCount in 2..maxPackedPageBudget)
            assertTrue("expected first long CJK page to contain many long segments", baselineSegmentCount >= 8)
            assertTrue(afterNext.currentItem > baseline.currentItem)
            assertTrue("expected next long CJK page to contain readable continuation text", afterNext.pageText.isNotBlank())
            assertTrue("expected next long CJK page to contain many long segments", nextSegmentCount >= 8)
            assertTrue("expected short tail entries to remain packed instead of one per page", tailEntryCount >= 2)
        }
    }

    @Test
    fun epubPagedPacksCjkPoemLinesSeparatedByBreaksRuntime() {
        val title = "page05-cjk-br-${UUID.randomUUID().toString().take(8)}"
        val poemLines = (1..84).map { index ->
            "诗行${index.toString().padStart(3, '0')} 雨停在旧屋檐"
        }
        val maxPackedPageBudget = poemLines.size / 3
        val body = poemLines.joinToString(
            prefix = "<p>",
            separator = "<br/>",
            postfix = "</p>",
        )
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        $body
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected CJK break-separated poem EPUB reader to enter packed paged mode") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    itemCount in 2..maxPackedPageBudget &&
                        poemLines.any { line -> pageText.contains(line) }
                }
            }

            val baseline = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-cjk-br-baseline.png")
            dumpHierarchy("packed-cjk-br-baseline.xml")

            performReaderAccessibilityAction(
                scenario = scenario,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            waitForCondition("expected CJK break-separated poem reader to advance to another packed page") {
                scenario.withActivity { activity ->
                    (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > baseline.currentItem &&
                        activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                            ?.let { pageText -> pageText.isNotBlank() && pageText != baseline.pageText } == true
                }
            }

            val afterNext = scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-cjk-br-after-next.png")
            dumpHierarchy("packed-cjk-br-after-next.xml")

            val baselineLineCount = poemLines.count { line -> baseline.pageText.contains(line) }
            val nextLineCount = poemLines.count { line -> afterNext.pageText.contains(line) }
            writeTextEvidence(
                "packed-cjk-br-summary.txt",
                buildString {
                    appendLine("poem_line_count=${poemLines.size}")
                    appendLine("max_packed_page_budget=$maxPackedPageBudget")
                    appendLine("page_count=${baseline.itemCount}")
                    appendLine("baseline_current_item=${baseline.currentItem}")
                    appendLine("baseline_page_progress=${baseline.pageProgress}")
                    appendLine("baseline_line_count=$baselineLineCount")
                    appendLine("baseline_page_text=${baseline.pageText}")
                    appendLine("next_current_item=${afterNext.currentItem}")
                    appendLine("next_page_progress=${afterNext.pageProgress}")
                    appendLine("next_line_count=$nextLineCount")
                    appendLine("next_page_text=${afterNext.pageText}")
                },
            )

            assertEquals(0, baseline.currentItem)
            assertTrue("expected break-separated CJK page count to stay well below line count", baseline.itemCount in 2..maxPackedPageBudget)
            assertTrue("expected first break-separated CJK page to contain several poem lines", baselineLineCount >= 4)
            assertTrue(afterNext.currentItem > baseline.currentItem)
            assertTrue("expected next break-separated CJK page to contain readable poem lines", afterNext.pageText.isNotBlank())
            assertTrue("expected next break-separated CJK page to contain several poem lines", nextLineCount >= 4)
        }
    }

    @Test
    fun epubPagedSkipsDecorativeSeparatorsAndPacksTinyDialogueRuntime() {
        val title = "page05-decorative-${UUID.randomUUID().toString().take(8)}"
        val fragments = (1..64).map { index ->
            when (index % 8) {
                0 -> "碎句${index.toString().padStart(3, '0')}：——"
                1 -> "碎句${index.toString().padStart(3, '0')}：“嗯。”"
                2 -> "碎句${index.toString().padStart(3, '0')}：“不。”"
                3 -> "碎句${index.toString().padStart(3, '0')}：……"
                4 -> "碎句${index.toString().padStart(3, '0')}：“等等。”"
                5 -> "碎句${index.toString().padStart(3, '0')}：灯灭。"
                6 -> "碎句${index.toString().padStart(3, '0')}：雨停。"
                else -> "碎句${index.toString().padStart(3, '0')}：“走。”"
            }
        }
        var blankParagraphCount = 0
        var separatorCount = 0
        val body = buildString {
            appendLine("<p> </p>")
            blankParagraphCount += 1
            appendLine("<hr/>")
            separatorCount += 1
            fragments.forEachIndexed { index, fragment ->
                if (index > 0 && index % 8 == 0) {
                    appendLine("<hr/>")
                    separatorCount += 1
                }
                if (index % 10 == 0) {
                    appendLine("<p><span> </span></p>")
                    blankParagraphCount += 1
                }
                appendLine(
                    when (index % 5) {
                        0 -> "<p><span>$fragment</span></p>"
                        1 -> "<p><em>$fragment</em></p>"
                        2 -> "<p><strong>$fragment</strong></p>"
                        3 -> "<p>$fragment<ruby>雨<rt>yu</rt></ruby></p>"
                        else -> "<p><span><em>$fragment</em></span></p>"
                    },
                )
                if (index % 13 == 0) {
                    appendLine("<p>   </p>")
                    blankParagraphCount += 1
                }
            }
            appendLine("<hr/>")
            separatorCount += 1
            appendLine("<p> </p>")
            blankParagraphCount += 1
        }
        val maxPackedPageBudget = fragments.size / 2
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        $body
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected decorative separator EPUB reader to enter packed paged mode") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    itemCount in 2..maxPackedPageBudget &&
                        fragments.any { fragment -> pageText.contains(fragment) }
                }
            }

            val samples = mutableListOf<OrientationPackedSummary>()
            samples += scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-decorative-page-0.png")
            dumpHierarchy("packed-decorative-page-0.xml")

            repeat(3) { sampleIndex ->
                val previous = samples.last()
                if (previous.currentItem >= previous.itemCount - 1) return@repeat
                performReaderAccessibilityAction(
                    scenario = scenario,
                    action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                )
                waitForCondition("expected decorative separator reader to advance to a non-blank packed page") {
                    scenario.withActivity { activity ->
                        (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > previous.currentItem &&
                            activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                                ?.let { pageText -> pageText.isNotBlank() && pageText != previous.pageText } == true
                    }
                }
                samples += scenario.withActivity { activity ->
                    activity.orientationPackedSummary()
                }
                takeScreenshot("packed-decorative-page-${sampleIndex + 1}.png")
                dumpHierarchy("packed-decorative-page-${sampleIndex + 1}.xml")
            }

            val sampleFragmentCounts = samples.map { sample ->
                fragments.count { fragment -> sample.pageText.contains(fragment) }
            }
            writeTextEvidence(
                "packed-decorative-summary.txt",
                buildString {
                    appendLine("fragment_count=${fragments.size}")
                    appendLine("blank_paragraph_count=$blankParagraphCount")
                    appendLine("separator_count=$separatorCount")
                    appendLine("max_packed_page_budget=$maxPackedPageBudget")
                    appendLine("page_count=${samples.first().itemCount}")
                    samples.forEachIndexed { index, sample ->
                        appendLine("sample_${index}_current_item=${sample.currentItem}")
                        appendLine("sample_${index}_page_progress=${sample.pageProgress}")
                        appendLine("sample_${index}_fragment_count=${sampleFragmentCounts[index]}")
                        appendLine("sample_${index}_page_text=${sample.pageText}")
                    }
                },
            )

            assertEquals(0, samples.first().currentItem)
            assertTrue("expected multiple sampled decorative separator pages", samples.size >= 2)
            assertTrue(
                "expected decorative separator page count to stay well below one page per tiny fragment",
                samples.first().itemCount in 2..maxPackedPageBudget,
            )
            assertTrue(
                "expected sampled decorative separator pages to never be blank",
                samples.all { sample -> sample.pageText.isNotBlank() },
            )
            assertTrue(
                "expected every sampled decorative separator page to contain multiple real dialogue fragments",
                sampleFragmentCounts.all { count -> count >= 2 },
            )
        }
    }

    @Test
    fun epubPagedPacksNoisyExportedCjkBlocksRuntime() {
        val title = "page05-export-noise-${UUID.randomUUID().toString().take(8)}"
        val heading = "导出版混排压力"
        val dialogueMarkers = (1..72).map { index ->
            "噪声${index.toString().padStart(3, '0')}"
        }
        val longSegments = (1..48).map { index ->
            "长句${index.toString().padStart(3, '0')}窗外雨声贴着玻璃往下滑灯影落在旧书页"
        }
        val longParagraph = longSegments.joinToString(separator = "")
        val tailEntries = (1..16).map { index ->
            "尾声${index.toString().padStart(3, '0')}：还亮着。"
        }
        var blankParagraphCount = 0
        var separatorCount = 0
        val body = buildString {
            appendLine("<h2>$heading</h2>")
            dialogueMarkers.forEachIndexed { index, marker ->
                if (index > 0 && index % 9 == 0) {
                    appendLine("<p><span> </span></p>")
                    blankParagraphCount += 1
                    appendLine("<hr/>")
                    separatorCount += 1
                }
                appendLine(
                    when (index % 6) {
                        0 -> "<p><span>$marker</span><br/><span>“嗯。”</span></p>"
                        1 -> "<p><em>$marker：“不行。”</em></p>"
                        2 -> "<blockquote><p><strong>$marker：门响。</strong></p></blockquote>"
                        3 -> "<ul><li><span>$marker：钥匙。</span></li></ul>"
                        4 -> "<p><span></span><ruby>$marker<rt>noise</rt></ruby>：雨落。</p>"
                        else -> "<p><span><em>$marker：等等。</em></span><br/><span>尾音。</span></p>"
                    },
                )
                if (index % 14 == 0) {
                    appendLine("<p>   </p>")
                    blankParagraphCount += 1
                }
            }
            appendLine("<hr/>")
            separatorCount += 1
            appendLine("<p>$longParagraph</p>")
            appendLine("<hr/>")
            separatorCount += 1
            tailEntries.forEachIndexed { index, entry ->
                appendLine(
                    if (index % 4 == 0) {
                        "<p><span></span><strong>$entry</strong></p>"
                    } else {
                        "<p><span>$entry</span></p>"
                    },
                )
            }
        }
        val trackedContentCount = dialogueMarkers.size + longSegments.size + tailEntries.size
        val maxPackedPageBudget = trackedContentCount / 3
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = listOf(
                "OEBPS/ch1.xhtml" to """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        $body
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected noisy exported CJK EPUB reader to enter packed paged mode") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    itemCount in 2..maxPackedPageBudget &&
                        (pageText.contains(heading) || dialogueMarkers.any { marker -> pageText.contains(marker) })
                }
            }

            val samples = mutableListOf<OrientationPackedSummary>()
            samples += scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-noisy-export-page-0.png")
            dumpHierarchy("packed-noisy-export-page-0.xml")

            repeat(4) { sampleIndex ->
                val previous = samples.last()
                if (previous.currentItem >= previous.itemCount - 1) return@repeat
                performReaderAccessibilityAction(
                    scenario = scenario,
                    action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                )
                waitForCondition("expected noisy exported reader to advance to a non-blank packed page") {
                    scenario.withActivity { activity ->
                        (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > previous.currentItem &&
                            activity.currentEpubComposePageRootOrNull()?.composeTextSurface()
                                ?.let { pageText -> pageText.isNotBlank() && pageText != previous.pageText } == true
                    }
                }
                samples += scenario.withActivity { activity ->
                    activity.orientationPackedSummary()
                }
                takeScreenshot("packed-noisy-export-page-${sampleIndex + 1}.png")
                dumpHierarchy("packed-noisy-export-page-${sampleIndex + 1}.xml")
            }

            var longPage: OrientationPackedSummary? = samples.firstOrNull { sample ->
                longSegments.any { segment -> sample.pageText.contains(segment) }
            }
            var tailPage = samples.last()
            var tailTurns = 0
            while (
                tailEntries.none { entry -> tailPage.pageText.contains(entry) } &&
                tailPage.currentItem < tailPage.itemCount - 1 &&
                tailTurns < tailPage.itemCount
            ) {
                val previousItem = tailPage.currentItem
                performReaderAccessibilityAction(
                    scenario = scenario,
                    action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                )
                waitForCondition("expected noisy exported pagination to keep advancing toward tail entries") {
                    scenario.withActivity { activity ->
                        (activity.findEpubViewPager()?.pagerCurrentItem() ?: -1) > previousItem
                    }
                }
                tailPage = scenario.withActivity { activity ->
                    activity.orientationPackedSummary()
                }
                if (longPage == null && longSegments.any { segment -> tailPage.pageText.contains(segment) }) {
                    longPage = tailPage
                }
                tailTurns += 1
            }
            takeScreenshot("packed-noisy-export-tail.png")
            dumpHierarchy("packed-noisy-export-tail.xml")

            val sampleDialogueCounts = samples.map { sample ->
                dialogueMarkers.count { marker -> sample.pageText.contains(marker) }
            }
            val sampleLongSegmentCounts = samples.map { sample ->
                longSegments.count { segment -> sample.pageText.contains(segment) }
            }
            val capturedLongPage = checkNotNull(longPage) {
                "Expected noisy exported EPUB pagination to expose the long unspaced CJK section before tail entries"
            }
            val longPageSegmentCount = longSegments.count { segment -> capturedLongPage.pageText.contains(segment) }
            val tailEntryCount = tailEntries.count { entry -> tailPage.pageText.contains(entry) }
            writeTextEvidence(
                "packed-noisy-export-summary.txt",
                buildString {
                    appendLine("heading=$heading")
                    appendLine("dialogue_marker_count=${dialogueMarkers.size}")
                    appendLine("long_segment_count=${longSegments.size}")
                    appendLine("tail_entry_count=${tailEntries.size}")
                    appendLine("tracked_content_count=$trackedContentCount")
                    appendLine("blank_paragraph_count=$blankParagraphCount")
                    appendLine("separator_count=$separatorCount")
                    appendLine("long_paragraph_has_space=${longParagraph.contains(' ')}")
                    appendLine("max_packed_page_budget=$maxPackedPageBudget")
                    appendLine("page_count=${samples.first().itemCount}")
                    samples.forEachIndexed { index, sample ->
                        appendLine("sample_${index}_current_item=${sample.currentItem}")
                        appendLine("sample_${index}_page_progress=${sample.pageProgress}")
                        appendLine("sample_${index}_dialogue_count=${sampleDialogueCounts[index]}")
                        appendLine("sample_${index}_long_segment_count=${sampleLongSegmentCounts[index]}")
                        appendLine("sample_${index}_page_text=${sample.pageText}")
                    }
                    appendLine("long_current_item=${capturedLongPage.currentItem}")
                    appendLine("long_page_progress=${capturedLongPage.pageProgress}")
                    appendLine("long_segment_count_on_page=$longPageSegmentCount")
                    appendLine("long_page_text=${capturedLongPage.pageText}")
                    appendLine("tail_turns=$tailTurns")
                    appendLine("tail_current_item=${tailPage.currentItem}")
                    appendLine("tail_page_progress=${tailPage.pageProgress}")
                    appendLine("tail_entry_count=$tailEntryCount")
                    appendLine("tail_page_text=${tailPage.pageText}")
                },
            )

            assertEquals(0, samples.first().currentItem)
            assertTrue("expected noisy exported CJK EPUB to produce multiple sampled pages", samples.size >= 2)
            assertFalse("expected long paragraph to contain no ASCII spaces", longParagraph.contains(' '))
            assertTrue(
                "expected noisy exported page count to stay well below tracked content count",
                samples.first().itemCount in 2..maxPackedPageBudget,
            )
            assertTrue(
                "expected sampled noisy exported pages to never be blank",
                samples.all { sample -> sample.pageText.isNotBlank() },
            )
            assertTrue(
                "expected the first page to expose heading or multiple dialogue blocks",
                samples.first().pageText.contains(heading) || sampleDialogueCounts.first() >= 2,
            )
            assertTrue(
                "expected at least one sampled noisy exported page to pack multiple dialogue blocks",
                sampleDialogueCounts.any { count -> count >= 2 },
            )
            assertTrue(
                "expected long unspaced section to remain readable and packed when reached",
                longPageSegmentCount >= 4,
            )
            assertTrue(
                "expected short tail entries to remain packed instead of one per page",
                tailEntryCount >= 2,
            )
        }
    }

    @Test
    fun epubPagedPacksShortChapterSpinesRuntime() {
        val title = "page05-short-spines-${UUID.randomUUID().toString().take(8)}"
        val chapters = (1..6).map { chapterIndex ->
            val chapter = chapterIndex.toString().padStart(2, '0')
            val heading = "短章$chapter 夜航"
            val markers = (1..10).map { markerIndex ->
                "短章$chapter-${markerIndex.toString().padStart(2, '0')}"
            }
            ShortSpineChapter(heading = heading, markers = markers)
        }
        val spineEntries = chapters.mapIndexed { chapterIndex, chapter ->
            val body = buildString {
                appendLine("<h2>${chapter.heading}</h2>")
                chapter.markers.forEachIndexed { index, marker ->
                    appendLine(
                        when (index % 5) {
                            0 -> "<p><span>$marker：灯还亮。</span><br/><span>脚步很轻。</span></p>"
                            1 -> "<ul><li><span>$marker：门缝有风。</span></li></ul>"
                            2 -> "<blockquote><p><strong>$marker：有人回头。</strong></p></blockquote>"
                            3 -> "<p><ruby>$marker<rt>spine</rt></ruby>：雨停。</p>"
                            else -> "<p><em>$marker：继续读。</em></p>"
                        },
                    )
                }
            }
            "OEBPS/chapter-${chapterIndex + 1}.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <body>
                    $body
                  </body>
                </html>
            """.trimIndent()
        }
        val allMarkers = chapters.flatMap { chapter -> chapter.markers }
        val trackedContentCount = allMarkers.size
        val maxPackedPageBudget = trackedContentCount / 2
        val readerUri = createEpubUri(
            fileName = "$title.epub",
            spineEntries = spineEntries,
        )

        ActivityScenario.launch<MainActivity>(readerIntent(readerUri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.desc(EPUB_READER_DESC))

            switchReaderToPagedMode()
            waitForCondition("expected short-spine EPUB reader to enter paged mode with readable text") {
                scenario.withActivity { activity ->
                    val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: Int.MAX_VALUE
                    val pageText = activity.currentEpubComposePageRootOrNull()?.composeTextSurface().orEmpty()
                    writeTextEvidence(
                        "packed-short-spines-enter-debug.txt",
                        buildString {
                            appendLine("item_count=$itemCount")
                            appendLine("max_packed_page_budget=$maxPackedPageBudget")
                            appendLine("compose_page_present=${activity.currentEpubComposePageRootOrNull() != null}")
                            appendLine("page_text=$pageText")
                        },
                    )
                    itemCount >= chapters.size &&
                        (chapters.any { chapter -> pageText.contains(chapter.heading) } ||
                            allMarkers.any { marker -> pageText.contains(marker) })
                }
            }
            val pages = mutableListOf<OrientationPackedSummary>()
            pages += scenario.withActivity { activity ->
                activity.orientationPackedSummary()
            }
            takeScreenshot("packed-short-spines-page-0.png")
            dumpHierarchy("packed-short-spines-page-0.xml")

            while (
                pages.last().currentItem < pages.last().itemCount - 1 &&
                pages.size < pages.last().itemCount
            ) {
                val previous = pages.last()
                performReaderAccessibilityAction(
                    scenario = scenario,
                    action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                )
                waitForCondition("expected short-spine pagination to advance to the next readable page") {
                    scenario.withActivity { activity ->
                        val currentItem = activity.findEpubViewPager()?.pagerCurrentItem() ?: -1
                        val itemCount = activity.findEpubViewPager()?.pagerAdapterItemCount() ?: -1
                        val composePage = activity.currentEpubComposePageRootOrNull()
                        val pageText = composePage?.composeTextSurface().orEmpty()
                        writeTextEvidence(
                            "packed-short-spines-advance-debug.txt",
                            buildString {
                                appendLine("previous_current_item=${previous.currentItem}")
                                appendLine("current_item=$currentItem")
                                appendLine("item_count=$itemCount")
                                appendLine("compose_page_present=${composePage != null}")
                                appendLine("page_progress=${composePage?.composePageProgressDescription()}")
                                appendLine("page_text=$pageText")
                            },
                        )
                        currentItem > previous.currentItem && pageText.isNotBlank()
                    }
                }
                pages += scenario.withActivity { activity ->
                    activity.orientationPackedSummary()
                }
                if (pages.size <= 3) {
                    takeScreenshot("packed-short-spines-page-${pages.lastIndex}.png")
                    dumpHierarchy("packed-short-spines-page-${pages.lastIndex}.xml")
                }
            }
            takeScreenshot("packed-short-spines-final.png")
            dumpHierarchy("packed-short-spines-final.xml")

            val allPageText = pages.joinToString(separator = "\n\n") { page -> page.pageText }
            val headingSeen = chapters.map { chapter ->
                pages.any { page -> page.pageText.contains(chapter.heading) }
            }
            val markerSeenCounts = chapters.map { chapter ->
                chapter.markers.count { marker -> allPageText.contains(marker) }
            }
            val maxMarkersPerChapterPage = chapters.map { chapter ->
                pages.maxOf { page ->
                    chapter.markers.count { marker -> page.pageText.contains(marker) }
                }
            }
            val markerCountsPerPage = pages.map { page ->
                allMarkers.count { marker -> page.pageText.contains(marker) }
            }
            writeTextEvidence(
                "packed-short-spines-summary.txt",
                buildString {
                    appendLine("chapter_count=${chapters.size}")
                    appendLine("markers_per_chapter=${chapters.first().markers.size}")
                    appendLine("tracked_content_count=$trackedContentCount")
                    appendLine("max_packed_page_budget=$maxPackedPageBudget")
                    appendLine("page_count=${pages.first().itemCount}")
                    appendLine("traversed_page_count=${pages.size}")
                    appendLine("last_current_item=${pages.last().currentItem}")
                    appendLine("all_headings_seen=${headingSeen.all { it }}")
                    appendLine("all_markers_seen=${markerSeenCounts.sum() == trackedContentCount}")
                    chapters.forEachIndexed { index, chapter ->
                        appendLine("chapter_${index + 1}_heading=${chapter.heading}")
                        appendLine("chapter_${index + 1}_heading_seen=${headingSeen[index]}")
                        appendLine("chapter_${index + 1}_marker_seen_count=${markerSeenCounts[index]}")
                        appendLine("chapter_${index + 1}_max_markers_on_page=${maxMarkersPerChapterPage[index]}")
                    }
                    pages.forEachIndexed { index, page ->
                        appendLine("page_${index}_current_item=${page.currentItem}")
                        appendLine("page_${index}_page_progress=${page.pageProgress}")
                        appendLine("page_${index}_marker_count=${markerCountsPerPage[index]}")
                        appendLine("page_${index}_text=${page.pageText}")
                    }
                },
            )

            assertEquals(0, pages.first().currentItem)
            assertEquals(pages.first().itemCount - 1, pages.last().currentItem)
            assertTrue("expected short-spine EPUB to traverse multiple pages", pages.size >= chapters.size)
            assertTrue(
                "expected short-spine page count to stay well below one page per short block",
                pages.first().itemCount in chapters.size..maxPackedPageBudget,
            )
            assertTrue(
                "expected every short-spine page to contain readable text",
                pages.all { page -> page.pageText.isNotBlank() },
            )
            assertTrue("expected every short chapter heading to be reachable", headingSeen.all { it })
            assertEquals(
                "expected every short-spine marker to be reachable while paging through the EPUB",
                trackedContentCount,
                markerSeenCounts.sum(),
            )
            assertTrue(
                "expected every short chapter to pack multiple blocks onto at least one page",
                maxMarkersPerChapterPage.all { count -> count >= 3 },
            )
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

    private fun dragLineSpacingSliderToMax() {
        if (device.wait(Until.findObject(By.desc("行距")), 750) == null) {
            openBottomPanel(buttonText = "排版", expectedText = "阅读正文预览")
        }
        val slider = waitForObject(By.desc("行距"))
        val bounds = slider.visibleBounds
        device.swipe(
            bounds.centerX(),
            bounds.centerY(),
            bounds.right - 24,
            bounds.centerY(),
            24,
        )
        device.waitForIdle()
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

    private fun dispatchPinchOut(target: View) {
        val width = target.width.toFloat().coerceAtLeast(1f)
        val height = target.height.toFloat().coerceAtLeast(1f)
        val centerX = width / 2f
        val centerY = max(height * 0.35f, 120f)
        val maxHalfSpan = (width / 2f) - 32f
        val startHalfSpan = max(width * 0.04f, 32f).coerceAtMost(maxHalfSpan)
        val endHalfSpan = max(width * 0.32f, 180f).coerceAtMost(maxHalfSpan)
        dispatchPinchGesture(
            target = target,
            pointerOneStart = Point(centerX - startHalfSpan, centerY),
            pointerTwoStart = Point(centerX + startHalfSpan, centerY),
            pointerOneEnd = Point(centerX - endHalfSpan, centerY),
            pointerTwoEnd = Point(centerX + endHalfSpan, centerY),
        )
    }

    private fun dispatchPinchGesture(
        target: View,
        pointerOneStart: Point,
        pointerTwoStart: Point,
        pointerOneEnd: Point,
        pointerTwoEnd: Point,
        steps: Int = 6,
    ) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime

        dispatchTouchEvent(
            target,
            motionEvent(downTime, eventTime, MotionEvent.ACTION_DOWN, listOf(pointerOneStart)),
        )
        eventTime += EVENT_STEP_MS
        dispatchTouchEvent(
            target,
            motionEvent(
                downTime,
                eventTime,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(pointerOneStart, pointerTwoStart),
            ),
        )
        repeat(steps) { step ->
            val progress = (step + 1) / steps.toFloat()
            eventTime += EVENT_STEP_MS
            dispatchTouchEvent(
                target,
                motionEvent(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    listOf(
                        pointerOneStart.interpolateTo(pointerOneEnd, progress),
                        pointerTwoStart.interpolateTo(pointerTwoEnd, progress),
                    ),
                ),
            )
        }
        eventTime += EVENT_STEP_MS
        dispatchTouchEvent(
            target,
            motionEvent(
                downTime,
                eventTime,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(pointerOneEnd, pointerTwoEnd),
            ),
        )
        eventTime += EVENT_STEP_MS
        dispatchTouchEvent(
            target,
            motionEvent(downTime, eventTime, MotionEvent.ACTION_UP, listOf(pointerOneEnd)),
        )
    }

    private fun dispatchTouchEvent(target: View, event: MotionEvent) {
        try {
            instrumentation.runOnMainSync {
                target.dispatchTouchEvent(event)
            }
        } finally {
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        coordinates: List<Point>,
    ): MotionEvent {
        val properties = Array(coordinates.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val pointerCoords = Array(coordinates.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = coordinates[index].x
                y = coordinates[index].y
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            coordinates.size,
            properties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
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
        checkNotNull(findReaderSurfaceOrNull()) {
            "Unable to find reader surface"
        }

    private fun MainActivity.findReaderSurfaceOrNull(): View? =
        window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }

    private fun MainActivity.findEpubViewPager(): View? =
        findReaderSurfaceOrNull()?.findDescendant { view ->
            view.javaClass.name == VIEW_PAGER_CLASS_NAME
        }

    private fun MainActivity.findEpubScrollRecyclerView(): View? =
        findReaderSurfaceOrNull()?.findDescendant { view ->
            view.javaClass.name == RECYCLER_VIEW_CLASS_NAME &&
                view.adapterClassName() == EPUB_ADAPTER_CLASS_NAME
        }

    private fun MainActivity.currentEpubComposePageRootOrNull(): ComposeView? {
        val surface = findReaderSurfaceOrNull() ?: return null
        val pager = surface.findDescendant { view ->
            view.javaClass.name == VIEW_PAGER_CLASS_NAME
        } ?: return null
        val currentItem = pager.pagerCurrentItem()
        val total = pager.pagerAdapterItemCount()
        val expectedProgress = pageLabel(currentItem, total)
        return surface.findDescendant { view ->
            view is ComposeView &&
                view.isShown &&
                view.getTag(EpubR.id.epub_compose_page_progress_description) == expectedProgress
        } as? ComposeView
    }

    private fun MainActivity.currentEpubImagePageViewOrNull(): ImageView? {
        val surface = findReaderSurfaceOrNull() ?: return null
        val pager = surface.findDescendant { view ->
            view.javaClass.name == VIEW_PAGER_CLASS_NAME
        } ?: return null
        val currentItem = pager.pagerCurrentItem()
        val total = pager.pagerAdapterItemCount()
        val expectedProgress = pageLabel(currentItem, total)
        return surface.findDescendant { view ->
            view is ImageView &&
                view.isShown &&
                view.contentDescription?.toString()?.contains(expectedProgress) == true
        } as? ImageView
    }

    private fun MainActivity.orientationPackedSummary(): OrientationPackedSummary {
        val surface = findReaderSurface()
        val composePage = checkNotNull(currentEpubComposePageRootOrNull()) {
            "Unable to find current packed EPUB compose page"
        }
        return OrientationPackedSummary(
            orientation = resources.configuration.orientation,
            surfaceWidth = surface.width,
            surfaceHeight = surface.height,
            currentItem = findEpubViewPager()?.pagerCurrentItem() ?: -1,
            itemCount = findEpubViewPager()?.pagerAdapterItemCount() ?: -1,
            pageText = composePage.composeTextSurface().orEmpty(),
            pageProgress = composePage.composePageProgressDescription(),
        )
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

    private data class PackedMicroBaseline(
        val currentItem: Int,
        val itemCount: Int,
        val pageText: String,
        val pageProgress: String?,
    )

    private data class PackedMicroAfterPinch(
        val currentItem: Int,
        val itemCount: Int,
        val pageText: String,
        val pageProgress: String?,
        val persistedFontSize: Int,
    )

    private data class PackedMicroAfterNext(
        val currentItem: Int,
        val pageText: String,
        val pageProgress: String?,
    )

    private data class PackedMicroAfterLineSpacing(
        val currentItem: Int,
        val itemCount: Int,
        val pageText: String,
        val pageProgress: String?,
        val persistedLineSpacing: Float,
    )

    private data class OrientationPackedSummary(
        val orientation: Int,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
        val currentItem: Int,
        val itemCount: Int,
        val pageText: String,
        val pageProgress: String?,
    )

    private data class ShortSpineChapter(
        val heading: String,
        val markers: List<String>,
    )

    private data class Point(val x: Float, val y: Float) {
        fun interpolateTo(other: Point, progress: Float): Point =
            Point(
                x = x + (other.x - x) * progress,
                y = y + (other.y - y) * progress,
            )
    }

    private companion object {
        private const val EPUB_READER_DESC = "阅读内容，捏合调整字号"
        private const val DB_NAME = "readflow.db"
        private const val VIEW_PAGER_CLASS_NAME = "androidx.viewpager2.widget.ViewPager2"
        private const val RECYCLER_VIEW_CLASS_NAME = "androidx.recyclerview.widget.RecyclerView"
        private const val EPUB_ADAPTER_CLASS_NAME = "dev.readflow.render.epub.EpubParaAdapter"
        private const val EVENT_STEP_MS = 16L
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
    }
}
