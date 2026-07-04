package dev.readflow.page05

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.graphics.PointF
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.readflow.MainActivity
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.render.api.R as RenderApiR
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class EpubFlowAnchorRuntimeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = DataStoreSettingsRepository(appContext)

    @Before
    fun setUp() = runBlocking {
        settings.setFontSize(18)
        settings.setLineSpacing(1.75f)
        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setReadingMode(ReaderReadingMode.PAGED)
        settings.setPageFlipStyle(PageFlipStyle.NONE)
        settings.setReaderGuideShown(true)
        device.pressHome()
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        device.pressHome()
        device.waitForIdle()
    }

    @Test
    fun epubFlowTurnAfterTemporaryScrollUsesNearestCanonicalAnchorRuntime() {
        val title = "flow-anchor-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val baseline = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4) return@withActivity null
                    val pageOneTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    val pageTwoTop = view.reflectNullableInt("pageTopPxAt", 2) ?: return@withActivity null
                    val pageThreeTop = view.reflectNullableInt("pageTopPxAt", 3) ?: return@withActivity null
                    FlowAnchorBaseline(view, pageCount, pageOneTop, pageTwoTop, pageThreeTop)
                }
            }
            assertTrue("expected at least 5 pages, got ${baseline.pageCount}", baseline.pageCount > 4)
            val nearPageTwo = baseline.pageOneTop + ((baseline.pageTwoTop - baseline.pageOneTop) * 3 / 4)

            val result = scenario.withActivity {
                baseline.view.scrollTo(0, nearPageTwo)
                baseline.view.reflectBoolean("goToAdjacentPage", 1)
                FlowAnchorResult(
                    currentPage = baseline.view.reflectInt("currentPageIndex"),
                    scrollY = baseline.view.scrollY,
                )
            }

            assertEquals(3, result.currentPage)
            assertEquals(baseline.pageThreeTop, result.scrollY)
        }
    }

    @Test
    fun epubFlowMoonReaderCenterZonesRuntime() {
        val title = "flow-zones-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    view
                }
            }

            val downOnlyResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val down = PointF(view.width * 0.85f, view.height * 0.50f)
                val downTime = SystemClock.uptimeMillis()
                dispatchTouchEvent(view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
                val result = FlowZonePageResult(
                    startY = 0,
                    endY = view.scrollY,
                    currentPage = view.reflectInt("currentPageIndex"),
                )
                dispatchTouchEvent(view, motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_CANCEL, down))
                result
            }
            assertEquals("ACTION_DOWN alone should not scroll", downOnlyResult.startY, downOnlyResult.endY)
            assertEquals("ACTION_DOWN alone should not flip", 0, downOnlyResult.currentPage)

            val ringResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val startY = view.scrollY
                val down = PointF(view.width * 0.37f, view.height * 0.50f)
                val up = PointF(down.x, view.height * 0.05f)
                dispatchDrag(view, down, up)
                FlowZoneResult(startY = startY, endY = view.scrollY)
            }
            assertEquals("center ring should consume without scrolling", ringResult.startY, ringResult.endY)

            val innerResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val down = PointF(view.width * 0.50f, view.height * 0.50f)
                dispatchTemporaryScrollAndRelease(view, down)
            }
            assertTrue(
                "inner center should temporary-scroll while dragging: " +
                    "start=${innerResult.startY} during=${innerResult.duringDragY}",
                innerResult.duringDragY > innerResult.startY,
            )
            assertEquals("inner center temporary-scroll should disable clip while dragging", false, innerResult.duringDragClip)
            assertEquals("inner center temporary-scroll should re-settle to page 0", 0, innerResult.currentPage)
            assertEquals("inner center temporary-scroll release should normalize to the page anchor", 0, innerResult.afterReleaseY)

            val cancelResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val down = PointF(view.width * 0.50f, view.height * 0.50f)
                val armMove = PointF(down.x, view.height * 0.30f)
                val scrolledMove = PointF(down.x, view.height * 0.10f)
                val downTime = SystemClock.uptimeMillis()
                dispatchTouchEvent(view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
                dispatchTouchEvent(view, motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_MOVE, armMove))
                dispatchTouchEvent(
                    view,
                    motionEvent(downTime, downTime + EVENT_STEP_MS * 2, MotionEvent.ACTION_MOVE, scrolledMove),
                )
                val duringDragClip = view.reflectPrivateBoolean("pageClipActive")
                val duringDragY = view.scrollY
                dispatchTouchEvent(view, motionEvent(downTime, downTime + EVENT_STEP_MS * 3, MotionEvent.ACTION_CANCEL, scrolledMove))
                FlowCancelResult(
                    duringDragY = duringDragY,
                    duringDragClip = duringDragClip,
                    afterCancelClip = view.reflectPrivateBoolean("pageClipActive"),
                    currentPage = view.reflectInt("currentPageIndex"),
                )
            }
            assertTrue("temporary scroll should move before cancel", cancelResult.duringDragY > 0)
            assertEquals("temporary scroll should disable clip while dragging", false, cancelResult.duringDragClip)
            assertEquals("temporary scroll cancel should re-arm clip", true, cancelResult.afterCancelClip)
            assertEquals("temporary scroll cancel should not flip", 0, cancelResult.currentPage)

            val innerBoundaryResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val down = PointF(view.width * 0.40f, view.height * 0.50f)
                dispatchTemporaryScrollAndRelease(view, down)
            }
            assertTrue(
                "inner center boundary should temporary-scroll while dragging: " +
                    "start=${innerBoundaryResult.startY} during=${innerBoundaryResult.duringDragY}",
                innerBoundaryResult.duringDragY > innerBoundaryResult.startY,
            )
            assertEquals(
                "inner center boundary temporary-scroll should disable clip while dragging",
                false,
                innerBoundaryResult.duringDragClip,
            )
            assertEquals("inner center boundary should re-settle to page 0", 0, innerBoundaryResult.currentPage)
            assertEquals(
                "inner center boundary release should normalize to the page anchor",
                0,
                innerBoundaryResult.afterReleaseY,
            )

            val innerHorizontalResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val down = PointF(view.width * 0.50f, view.height * 0.50f)
                val up = PointF(view.width * 0.10f, down.y)
                dispatchDrag(view, down, up)
                FlowZonePageResult(
                    startY = 0,
                    endY = view.scrollY,
                    currentPage = view.reflectInt("currentPageIndex"),
                )
            }
            assertEquals("inner center horizontal drag should not scroll", innerHorizontalResult.startY, innerHorizontalResult.endY)
            assertEquals("inner center horizontal drag should not flip", 0, innerHorizontalResult.currentPage)

            val centerBoundaryResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val startY = view.scrollY
                val down = PointF(view.width / 3f, view.height * 0.50f)
                val up = PointF(down.x, view.height * 0.05f)
                dispatchDrag(view, down, up)
                FlowZonePageResult(
                    startY = startY,
                    endY = view.scrollY,
                    currentPage = view.reflectInt("currentPageIndex"),
                )
            }
            assertEquals("center-third boundary should not scroll", centerBoundaryResult.startY, centerBoundaryResult.endY)
            assertEquals("center-third boundary should not flip", 0, centerBoundaryResult.currentPage)

            val edgeTurnResult = scenario.withActivity {
                view.scrollTo(0, 0)
                val nextPageTop = checkNotNull(view.reflectNullableInt("pageTopPxAt", 1)) {
                    "expected next page top"
                }
                val down = PointF(view.width * 0.85f, view.height * 0.50f)
                val up = PointF(view.width * 0.15f, down.y)
                dispatchDrag(view, down, up)
                FlowEdgeTurnResult(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                    expectedTop = nextPageTop,
                )
            }
            assertEquals("edge swipe should turn one page", 1, edgeTurnResult.currentPage)
            assertEquals(
                "edge swipe should land on next canonical page top",
                edgeTurnResult.expectedTop,
                edgeTurnResult.scrollY,
            )
        }
    }

    @Test
    fun epubFlowRightTapTurnsExactlyOnePageRuntime() {
        val title = "flow-tap-once-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            val readerSurface = waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val nextPageTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    FlowCleanTapTarget(view = view, nextPageTop = nextPageTop)
                }
            }

            scenario.withActivity { target.view.scrollTo(0, 0) }
            val bounds = readerSurface.visibleBounds
            device.click(bounds.left + (bounds.width() * 0.85f).toInt(), bounds.centerY())
            device.waitForIdle()

            val result = scenario.withActivity {
                FlowCleanTapResult(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                )
            }
            assertEquals("right tap should turn exactly one page", 1, result.currentPage)
            assertEquals("right tap should land on the next canonical top", target.nextPageTop, result.scrollY)
        }
    }

    @Test
    fun epubFlowRepeatedActionViewReopensWithoutVisibleBookStartRuntime() {
        val title = "flow-reopen-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")
        var bookId: String
        var expectedPageTop: Int

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected flow view to paginate before saving reopen anchor") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val pageThreeTop = view.reflectNullableInt("pageTopPxAt", 3) ?: return@withActivity null
                    FlowReopenTarget(view = view, pageTop = pageThreeTop)
                }
            }

            val parked = scenario.withActivity {
                target.view.scrollTo(0, 0)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                FlowReopenFrame(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                    contentAlpha = target.view.flowContentAlpha(),
                    pageCount = target.view.reflectInt("pageCount"),
                )
            }
            assertEquals("setup should park on page 3", 3, parked.currentPage)
            assertEquals("setup should land on the page-3 canonical top", target.pageTop, parked.scrollY)
            expectedPageTop = target.pageTop

            bookId = waitForBookByTitle(title).id
            waitForConditionResult("expected EPUB progress to persist page-3 anchor before reopen", DB_TIMEOUT_MS) {
                latestProgress(bookId)?.takeIf { it.totalProgression > 0.02f }
            }
        }

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { reopened ->
            dismissBlockingDialogs()
            val firstFrame = waitForFirstFlowFrame(reopened)
            assertTrue(
                "reopened EPUB must not expose a visible book-start frame before restore: $firstFrame",
                firstFrame.contentAlpha < 1f || firstFrame.scrollY != 0 || firstFrame.currentPage != 0,
            )

            val restored = waitForConditionResult("expected repeated ACTION_VIEW to restore EPUB page-3 anchor") {
                reopened.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val currentPage = view.reflectInt("currentPageIndex")
                    val scrollY = view.scrollY
                    val alpha = view.flowContentAlpha()
                    if (currentPage == 3 && scrollY == expectedPageTop && alpha >= 1f) {
                        FlowReopenFrame(
                            currentPage = currentPage,
                            scrollY = scrollY,
                            contentAlpha = alpha,
                            pageCount = view.reflectInt("pageCount"),
                        )
                    } else {
                        null
                    }
                }
            }

            assertEquals("reopened EPUB should restore page 3", 3, restored.currentPage)
            assertEquals("reopened EPUB should restore the canonical page-3 top", expectedPageTop, restored.scrollY)
            assertEquals("repeated external import should reuse one stable EPUB book row", 1, booksByTitle(title).size)
        }
    }

    @Test
    fun epubFlowShelfOpenByIdReopensWithoutVisibleBookStartRuntime() {
        val title = "flow-shelf-reopen-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")
        var bookId: String
        var expectedPageTop: Int

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected flow view to paginate before saving shelf-open anchor") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val pageThreeTop = view.reflectNullableInt("pageTopPxAt", 3) ?: return@withActivity null
                    FlowReopenTarget(view = view, pageTop = pageThreeTop)
                }
            }

            val parked = scenario.withActivity {
                target.view.scrollTo(0, 0)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                FlowReopenFrame(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                    contentAlpha = target.view.flowContentAlpha(),
                    pageCount = target.view.reflectInt("pageCount"),
                )
            }
            assertEquals("setup should park on page 3", 3, parked.currentPage)
            assertEquals("setup should land on the page-3 canonical top", target.pageTop, parked.scrollY)
            expectedPageTop = target.pageTop

            bookId = waitForBookByTitle(title).id
            waitForConditionResult("expected EPUB progress to persist page-3 anchor before shelf open", DB_TIMEOUT_MS) {
                latestProgress(bookId)?.takeIf { it.totalProgression > 0.02f }
            }
        }

        ActivityScenario.launch<MainActivity>(mainIntent()).use { shelf ->
            dismissBlockingDialogs()
            val shelfItem = waitForObject(By.desc("打开 $title"))
            val bounds = shelfItem.visibleBounds
            injectScreenTap(bounds.centerX(), bounds.centerY())

            val trace = traceFlowFramesUntilRestored(
                scenario = shelf,
                expectedPage = 3,
                expectedScrollY = expectedPageTop,
                message = "expected shelf OpenById to restore EPUB page-3 anchor",
            )
            val exposedLowFrame = trace.frames.firstOrNull { frame ->
                frame.contentAlpha >= 1f &&
                    frame.currentPage == 0 &&
                    frame.scrollY < expectedPageTop / 2
            }
            assertTrue(
                "shelf OpenById must not expose any fully-visible low-scroll frame while restoring; " +
                    "bad=$exposedLowFrame trace=${trace.frames}",
                exposedLowFrame == null,
            )

            assertEquals("shelf OpenById should restore page 3", 3, trace.restored.currentPage)
            assertEquals("shelf OpenById should restore the canonical page-3 top", expectedPageTop, trace.restored.scrollY)
            assertEquals("shelf OpenById should reuse one stable EPUB book row", 1, booksByTitle(title).size)
        }
    }

    @Test
    fun epubFlowShelfRestoredFirstRightTapStartsSlideAnimationRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.NONE)
        val title = "flow-shelf-first-slide-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")
        var bookId: String
        var expectedPageTop: Int

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected flow view to paginate before saving shelf first-tap anchor") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 5 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val pageThreeTop = view.reflectNullableInt("pageTopPxAt", 3) ?: return@withActivity null
                    FlowReopenTarget(view = view, pageTop = pageThreeTop)
                }
            }

            val parked = scenario.withActivity {
                target.view.scrollTo(0, 0)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                target.view.reflectBoolean("goToAdjacentPage", 1)
                FlowReopenFrame(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                    contentAlpha = target.view.flowContentAlpha(),
                    pageCount = target.view.reflectInt("pageCount"),
                )
            }
            assertEquals("setup should park on page 3", 3, parked.currentPage)
            assertEquals("setup should land on the page-3 canonical top", target.pageTop, parked.scrollY)
            expectedPageTop = target.pageTop

            bookId = waitForBookByTitle(title).id
            waitForConditionResult("expected EPUB progress to persist page-3 anchor before shelf first tap", DB_TIMEOUT_MS) {
                latestProgress(bookId)?.takeIf { it.totalProgression > 0.02f }
            }
        }

        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        ActivityScenario.launch<MainActivity>(mainIntent()).use { shelf ->
            dismissBlockingDialogs()
            val shelfItem = waitForObject(By.desc("打开 $title"))
            val shelfBounds = shelfItem.visibleBounds
            device.click(shelfBounds.centerX(), shelfBounds.centerY())

            val target = waitForConditionResult("expected shelf OpenById to park restored page before first tap") {
                shelf.withActivity { activity ->
                    val surface = activity.findReaderSurfaceOrNull() ?: return@withActivity null
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val currentPage = view.reflectInt("currentPageIndex")
                    val scrollY = view.scrollY
                    if (
                        currentPage != 3 ||
                        scrollY != expectedPageTop ||
                        surface.width <= 0 ||
                        surface.height <= 0 ||
                        view.width <= 0 ||
                        view.height <= 0
                    ) {
                        return@withActivity null
                    }
                    val pageFourTop = view.reflectNullableInt("pageTopPxAt", 4) ?: return@withActivity null
                    FlowSurfaceTapTarget(surface = surface, view = view, nextPageTop = pageFourTop)
                }
            }

            val result = shelf.withActivity {
                val point = PointF(target.surface.width * 0.85f, target.surface.height * 0.50f)
                val downTime = SystemClock.uptimeMillis()
                target.surface.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point))
                target.surface.dispatchTouchEvent(
                    motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_UP, point),
                )
                FlowFirstSlideTapResult(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                    slideDrawablePresent = target.view.reflectPrivateAny("slideDrawable") != null,
                    flipAnimatorPresent = target.view.reflectPrivateAny("flipAnimator") != null,
                )
            }

            assertEquals("first shelf-restored slide tap should advance one page", 4, result.currentPage)
            assertEquals(
                "first shelf-restored slide tap should park the live content on page 4",
                target.nextPageTop,
                result.scrollY,
            )
            assertTrue(
                "first shelf-restored tap must start the normal slide animation instead of cutting instantly",
                result.slideDrawablePresent && result.flipAnimatorPresent,
            )
        }
    }

    @Test
    fun epubFlowScrollToPagedModeSwitchKeepsFrozenViewportCoverRuntime(): Unit = runBlocking {
        settings.setReadingMode(ReaderReadingMode.SCROLL)
        val title = "flow-conversion-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected scroll-mode flow view with measurable content") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val textView = view.reflectTextView()
                    val layout = textView.layout ?: return@withActivity null
                    if (view.width <= 0 || view.height <= 0 || layout.height <= view.height * 3) {
                        return@withActivity null
                    }
                    if (view.reflectPrivateAny("modeValue")?.toString() != "SCROLL") {
                        return@withActivity null
                    }
                    view
                }
            }

            val before = scenario.withActivity {
                view.scrollTo(0, view.height * 2)
                FlowConversionBefore(
                    scrollY = view.scrollY,
                    layoutOffset = view.reflectInt("topLayoutOffset"),
                    contentAlpha = view.flowContentAlpha(),
                    frame = view.drawRuntimeBitmap(),
                )
            }
            assertTrue("scroll-mode setup should start below the book beginning", before.scrollY > 0)
            assertEquals("scroll-mode live content should be visible before switching", 1f, before.contentAlpha)

            try {
                val during = scenario.withActivity {
                    view.invokeSetModeAnchoredPaged(before.layoutOffset)
                    val cover = checkNotNull(view.reflectPrivateAny("conversionSnapshotDrawable")) {
                        "expected SCROLL->PAGED conversion cover through the flow conversion path"
                    }
                    val bitmap = cover.reflectPrivateBitmap("bitmap")
                    check(!bitmap.isRecycled) { "conversion cover bitmap should be alive during the covered frame" }
                    FlowConversionDuring(
                        currentPage = view.reflectInt("currentPageIndex"),
                        scrollY = view.scrollY,
                        contentAlpha = view.flowContentAlpha(),
                        coverAlpha = cover.reflectPrivateInt("alphaValue"),
                        coverBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    )
                }
                try {
                    assertEquals(
                        "frozen conversion cover must stay fully opaque; no scroll/page crossfade",
                        255,
                        during.coverAlpha,
                    )
                    assertEquals("conversion cover width should match the visible viewport", before.frame.width, during.coverBitmap.width)
                    assertEquals("conversion cover height should match the visible viewport", before.frame.height, during.coverBitmap.height)
                    assertTrue(
                        "SCROLL->PAGED should park on a paged canonical page behind the cover",
                        during.currentPage > 0 || during.scrollY > 0,
                    )
                    val coveredFrame = scenario.withActivity { view.drawRuntimeBitmap() }
                    try {
                        assertSampledPixelsEqual(
                            "SCROLL->PAGED conversion should visually show only the frozen viewport cover",
                            during.coverBitmap,
                            coveredFrame,
                        )
                    } finally {
                        coveredFrame.recycle()
                    }
                } finally {
                    during.coverBitmap.recycle()
                }

                waitForConditionResult("expected conversion cover to clear after stable paged reveal") {
                    scenario.withActivity {
                        val coverGone = view.reflectPrivateAny("conversionSnapshotDrawable") == null
                        val alpha = view.flowContentAlpha()
                        if (coverGone && alpha >= 1f) {
                            FlowConversionAfter(
                                currentPage = view.reflectInt("currentPageIndex"),
                                scrollY = view.scrollY,
                                contentAlpha = alpha,
                            )
                        } else {
                            null
                        }
                    }
                }
            } finally {
                before.frame.recycle()
            }
        }
    }

    @Test
    fun epubFlowComplexScrollToPagedModeSwitchUsesExactFrozenViewportRuntime(): Unit = runBlocking {
        settings.setReadingMode(ReaderReadingMode.SCROLL)
        val title = "flow-complex-conversion-${UUID.randomUUID().toString().take(8)}"
        val uri = createComplexEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected complex scroll-mode flow view with measurable content") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val textView = view.reflectTextView()
                    val layout = textView.layout ?: return@withActivity null
                    val text = textView.text?.toString().orEmpty()
                    if (view.width <= 0 || view.height <= 0 || layout.height <= view.height * 4) {
                        return@withActivity null
                    }
                    if (!text.contains("COMPLEX-FLOW-050")) {
                        return@withActivity null
                    }
                    if (view.reflectPrivateAny("modeValue")?.toString() != "SCROLL") {
                        return@withActivity null
                    }
                    view
                }
            }

            val (before, during) = scenario.withActivity {
                val layoutHeight = checkNotNull(view.reflectTextView().layout).height
                val targetScrollY = (layoutHeight * 45 / 100)
                    .coerceAtLeast(1)
                    .coerceAtMost(layoutHeight - view.height)
                view.scrollTo(0, targetScrollY)
                val before = FlowConversionBefore(
                    scrollY = view.scrollY,
                    layoutOffset = view.reflectInt("topLayoutOffset"),
                    contentAlpha = view.flowContentAlpha(),
                    frame = view.drawRuntimeBitmap(),
                )
                view.invokeSetModeAnchoredPaged(before.layoutOffset)
                val cover = checkNotNull(view.reflectPrivateAny("conversionSnapshotDrawable")) {
                    "expected complex SCROLL->PAGED conversion cover through the flow conversion path"
                }
                val bitmap = cover.reflectPrivateBitmap("bitmap")
                check(!bitmap.isRecycled) { "complex conversion cover bitmap should be alive during the covered frame" }
                val during = FlowConversionDuring(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                    contentAlpha = view.flowContentAlpha(),
                    coverAlpha = cover.reflectPrivateInt("alphaValue"),
                    coverBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                )
                before to during
            }
            assertTrue("complex scroll-mode setup should start below the book beginning", before.scrollY > 0)
            assertEquals("complex scroll-mode live content should be visible before switching", 1f, before.contentAlpha)

            try {
                try {
                    assertEquals(
                        "complex conversion cover must stay fully opaque; no scroll/page crossfade",
                        255,
                        during.coverAlpha,
                    )
                    assertEquals("complex conversion cover width should match the visible viewport", before.frame.width, during.coverBitmap.width)
                    assertEquals("complex conversion cover height should match the visible viewport", before.frame.height, during.coverBitmap.height)
                    assertTrue(
                        "complex SCROLL->PAGED should park on a paged canonical page behind the cover",
                        during.currentPage > 0 || during.scrollY > 0,
                    )
                    assertSampledPixelsEqual(
                        "complex SCROLL->PAGED cover must be the exact pre-conversion viewport page shot",
                        before.frame,
                        during.coverBitmap,
                    )
                    val coveredFrame = scenario.withActivity { view.drawRuntimeBitmap() }
                    try {
                        assertSampledPixelsEqual(
                            "complex SCROLL->PAGED conversion should visually show only the frozen viewport cover",
                            during.coverBitmap,
                            coveredFrame,
                        )
                    } finally {
                        coveredFrame.recycle()
                    }
                } finally {
                    during.coverBitmap.recycle()
                }

                waitForConditionResult("expected complex conversion cover to clear after stable paged reveal") {
                    scenario.withActivity {
                        val coverGone = view.reflectPrivateAny("conversionSnapshotDrawable") == null
                        val alpha = view.flowContentAlpha()
                        if (coverGone && alpha >= 1f) {
                            FlowConversionAfter(
                                currentPage = view.reflectInt("currentPageIndex"),
                                scrollY = view.scrollY,
                                contentAlpha = alpha,
                            )
                        } else {
                            null
                        }
                    }
                }.also { after ->
                    assertTrue(
                        "complex converted paged view should remain parked after the frozen cover clears",
                        after.currentPage > 0 || after.scrollY > 0,
                    )
                    assertEquals("complex converted live content should finish fully visible", 1f, after.contentAlpha)
                }
            } finally {
                before.frame.recycle()
            }
        }
    }

    @Test
    fun epubFlowUiModeChipScrollToPagedKeepsFrozenViewportCoverRuntime(): Unit = runBlocking {
        settings.setReadingMode(ReaderReadingMode.SCROLL)
        val title = "flow-ui-conversion-${UUID.randomUUID().toString().take(8)}"
        val uri = createComplexEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            val activity = scenario.withActivity { it }
            val readerSurface = waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected complex scroll-mode flow view before UI mode switch") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val textView = view.reflectTextView()
                    val layout = textView.layout ?: return@withActivity null
                    val text = textView.text?.toString().orEmpty()
                    if (view.width <= 0 || view.height <= 0 || layout.height <= view.height * 4) {
                        return@withActivity null
                    }
                    if (!text.contains("COMPLEX-FLOW-050")) {
                        return@withActivity null
                    }
                    if (view.reflectPrivateAny("modeValue")?.toString() != "SCROLL") {
                        return@withActivity null
                    }
                    view
                }
            }

            scenario.withActivity {
                val layoutHeight = checkNotNull(view.reflectTextView().layout).height
                val targetScrollY = (layoutHeight * 45 / 100)
                    .coerceAtLeast(1)
                    .coerceAtMost(layoutHeight - view.height)
                view.scrollTo(0, targetScrollY)
            }

            val surfaceBounds = readerSurface.visibleBounds
            injectScreenTap(surfaceBounds.centerX(), surfaceBounds.centerY())
            val fontButton = waitForObject(By.text("排版"))
            val fontBounds = fontButton.visibleBounds
            injectScreenTap(fontBounds.centerX(), fontBounds.centerY())
            waitForObject(By.text("滚动"))
            val pagedChip = waitForObject(By.text("分页"))

            val before = scenario.withActivity {
                FlowConversionBefore(
                    scrollY = view.scrollY,
                    layoutOffset = view.reflectInt("topLayoutOffset"),
                    contentAlpha = view.flowContentAlpha(),
                    frame = view.drawRuntimeBitmap(),
                )
            }
            assertTrue("UI mode switch setup should start below the book beginning", before.scrollY > 0)
            assertEquals("UI mode switch should start from visible scroll content", 1f, before.contentAlpha)

            try {
                val pagedBounds = pagedChip.visibleBounds
                injectScreenTap(pagedBounds.centerX(), pagedBounds.centerY())
                val during = waitForConversionCoverFrame(
                    activity = activity,
                    message = "expected UI 分页 chip to route through the frozen SCROLL->PAGED cover",
                )
                try {
                    assertEquals(
                        "UI SCROLL->PAGED conversion cover must stay fully opaque",
                        255,
                        during.coverAlpha,
                    )
                    assertEquals("UI conversion cover width should match the visible viewport", before.frame.width, during.coverBitmap.width)
                    assertEquals("UI conversion cover height should match the visible viewport", before.frame.height, during.coverBitmap.height)
                    assertTrue(
                        "UI SCROLL->PAGED should park on a paged canonical page behind the cover",
                        during.currentPage > 0 || during.scrollY > 0,
                    )
                    assertSampledPixelsEqual(
                        "UI SCROLL->PAGED cover must be the exact pre-chip viewport page shot",
                        before.frame,
                        during.coverBitmap,
                    )
                    val coveredFrame = checkNotNull(during.drawnFrame) {
                        "UI conversion helper should capture the covered frame atomically with the cover"
                    }
                    try {
                        assertSampledPixelsEqual(
                            "UI SCROLL->PAGED conversion should visually show only the frozen viewport cover",
                            during.coverBitmap,
                            coveredFrame,
                        )
                    } finally {
                        coveredFrame.recycle()
                    }
                } finally {
                    during.coverBitmap.recycle()
                }

                waitForConditionResult("expected UI conversion cover to clear after stable paged reveal") {
                    scenario.withActivity {
                        val coverGone = view.reflectPrivateAny("conversionSnapshotDrawable") == null
                        val alpha = view.flowContentAlpha()
                        val mode = view.reflectPrivateAny("modeValue")?.toString()
                        if (coverGone && mode == "PAGED" && alpha >= 1f) {
                            FlowConversionAfter(
                                currentPage = view.reflectInt("currentPageIndex"),
                                scrollY = view.scrollY,
                                contentAlpha = alpha,
                            )
                        } else {
                            null
                        }
                    }
                }.also { after ->
                    assertTrue(
                        "UI converted paged view should remain parked after the frozen cover clears",
                        after.currentPage > 0 || after.scrollY > 0,
                    )
                    assertEquals("UI converted live content should finish fully visible", 1f, after.contentAlpha)
                }
            } finally {
                before.frame.recycle()
            }
        }
    }

    @Test
    fun epubFlowFirstRightTapStartsSlideAnimationRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-first-slide-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected flow view to paginate for first slide tap") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val nextPageTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    FlowCleanTapTarget(view = view, nextPageTop = nextPageTop)
                }
            }

            val result = scenario.withActivity {
                target.view.scrollTo(0, 0)
                val point = PointF(target.view.width * 0.85f, target.view.height * 0.50f)
                val downTime = SystemClock.uptimeMillis()
                target.view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point))
                target.view.dispatchTouchEvent(
                    motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_UP, point),
                )
                FlowFirstSlideTapResult(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                    slideDrawablePresent = target.view.reflectPrivateAny("slideDrawable") != null,
                    flipAnimatorPresent = target.view.reflectPrivateAny("flipAnimator") != null,
                )
            }

            assertEquals("first slide tap should advance one page", 1, result.currentPage)
            assertEquals("first slide tap should park the live content on the next page", target.nextPageTop, result.scrollY)
            assertTrue(
                "first slide tap must start the normal slide animation instead of cutting instantly",
                result.slideDrawablePresent && result.flipAnimatorPresent,
            )
        }
    }

    @Test
    fun epubFlowReaderSurfaceFirstRightTapStartsSlideAnimationRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-first-surface-slide-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected reader surface and flow view for first slide tap") {
                scenario.withActivity { activity ->
                    val surface = activity.findReaderSurface()
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || surface.width <= 0 || surface.height <= 0 || view.width <= 0 || view.height <= 0) {
                        return@withActivity null
                    }
                    val nextPageTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    FlowSurfaceTapTarget(surface = surface, view = view, nextPageTop = nextPageTop)
                }
            }

            val result = scenario.withActivity {
                target.view.scrollTo(0, 0)
                val point = PointF(target.surface.width * 0.85f, target.surface.height * 0.50f)
                val downTime = SystemClock.uptimeMillis()
                target.surface.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point))
                target.surface.dispatchTouchEvent(
                    motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_UP, point),
                )
                FlowFirstSlideTapResult(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                    slideDrawablePresent = target.view.reflectPrivateAny("slideDrawable") != null,
                    flipAnimatorPresent = target.view.reflectPrivateAny("flipAnimator") != null,
                )
            }

            assertEquals("first reader-surface tap should advance one page", 1, result.currentPage)
            assertEquals("first reader-surface tap should park the live content on the next page", target.nextPageTop, result.scrollY)
            assertTrue(
                "first reader-surface tap must start the normal slide animation instead of cutting instantly",
                result.slideDrawablePresent && result.flipAnimatorPresent,
            )
        }
    }

    @Test
    fun epubFlowFirstRightTapWithGuideVisibleTurnsPageRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        settings.setReaderGuideShown(false)
        val title = "flow-guide-first-tap-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读手势引导"))

            val target = waitForConditionResult("expected flow view to paginate below guide") {
                scenario.withActivity { activity ->
                    val surface = activity.findReaderSurface()
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || surface.width <= 0 || surface.height <= 0 || view.width <= 0 || view.height <= 0) {
                        return@withActivity null
                    }
                    val nextPageTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    FlowSurfaceTapTarget(surface = surface, view = view, nextPageTop = nextPageTop)
                }
            }

            scenario.withActivity { target.view.scrollTo(0, 0) }
            val location = IntArray(2)
            scenario.withActivity { target.surface.getLocationOnScreen(location) }
            device.click(
                location[0] + (target.surface.width * 0.85f).toInt(),
                location[1] + target.surface.height / 2,
            )

            waitForFlowPage(
                scenario = scenario,
                view = target.view,
                pageIndex = 1,
                scrollY = target.nextPageTop,
                message = "first right tap should turn the page even while the guide is visible",
            )
            assertTrue(
                "the guide should be dismissed by the first real reading action",
                device.wait(Until.gone(By.descStartsWith("阅读手势引导")), UI_TIMEOUT_MS),
            )
        }
    }

    @Test
    fun epubFlowActionDownKeepsPaperTextureRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-down-paper-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected flow view to paginate for down paper test") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    view
                }
            }

            val frames = scenario.withActivity {
                view.scrollTo(0, 0)
                val staticFrame = view.drawRuntimeBitmap()
                val point = PointF(view.width * 0.85f, view.height * 0.50f)
                val downTime = SystemClock.uptimeMillis()
                view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point))
                val pressedFrame = view.drawRuntimeBitmap()
                view.dispatchTouchEvent(motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_CANCEL, point))
                staticFrame to pressedFrame
            }

            try {
                assertSampledPixelsEqual(
                    "ACTION_DOWN must not tint or shift the EPUB paper texture",
                    frames.first,
                    frames.second,
                )
            } finally {
                frames.first.recycle()
                frames.second.recycle()
            }
        }
    }

    @Test
    fun epubFlowReaderSurfaceActionDownKeepsPaperTextureRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-surface-down-paper-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected reader surface and flow view for surface paper test") {
                scenario.withActivity { activity ->
                    val surface = activity.findReaderSurface()
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || surface.width <= 0 || surface.height <= 0 || view.width <= 0 || view.height <= 0) {
                        return@withActivity null
                    }
                    FlowSurfaceTapTarget(
                        surface = surface,
                        view = view,
                        nextPageTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null,
                    )
                }
            }

            val frames = scenario.withActivity {
                target.view.scrollTo(0, 0)
                val staticFrame = target.surface.drawRuntimeBitmap()
                val point = PointF(target.surface.width * 0.85f, target.surface.height * 0.50f)
                val downTime = SystemClock.uptimeMillis()
                target.surface.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point))
                val pressedFrame = target.surface.drawRuntimeBitmap()
                target.surface.dispatchTouchEvent(
                    motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_CANCEL, point),
                )
                staticFrame to pressedFrame
            }

            try {
                assertSampledPixelsEqual(
                    "Reader surface ACTION_DOWN must not tint or shift the EPUB paper texture",
                    frames.first,
                    frames.second,
                )
            } finally {
                frames.first.recycle()
                frames.second.recycle()
            }
        }
    }

    @Test
    fun epubFlowCenterTapTogglesChromeExactlyOnceRuntime() {
        val title = "flow-center-tap-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            val readerSurface = waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    view.scrollTo(0, 0)
                    FlowCenterTapTarget(
                        view = view,
                        startPage = view.reflectInt("currentPageIndex"),
                        startScrollY = view.scrollY,
                    )
                }
            }
            assertEquals("center tap test should start on page 0", 0, target.startPage)
            assertEquals("center tap test should start at the first canonical top", 0, target.startScrollY)
            assertTrue(
                "chrome should start hidden for a single-toggle assertion",
                device.findObject(By.text("排版")) == null,
            )

            val bounds = readerSurface.visibleBounds
            device.click(bounds.centerX(), bounds.centerY())
            device.waitForIdle()
            waitForObject(By.text("排版"))
            waitForObject(By.text("主题"))

            val result = scenario.withActivity {
                FlowCenterTapResult(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                )
            }
            assertEquals("center tap should not turn the page", target.startPage, result.currentPage)
            assertEquals("center tap should not move the canonical anchor", target.startScrollY, result.scrollY)
        }
    }

    @Test
    fun epubFlowReaderSurfaceAccessibilityAndKeyboardActionsRuntime() {
        val title = "flow-a11y-key-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val pageOneTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    FlowAccessibilityTarget(
                        view = view,
                        pageOneTop = pageOneTop,
                        actions = activity.readerSurfaceAccessibilityActions(),
                    )
                }
            }
            assertTrue(
                "reader surface should expose previous-page accessibility action",
                target.actions.contains("上一页"),
            )
            assertTrue(
                "reader surface should expose next-page accessibility action",
                target.actions.contains("下一页"),
            )
            assertTrue(
                "reader surface should expose toolbar toggle accessibility action",
                target.actions.contains("显示或隐藏阅读工具栏"),
            )

            performReaderAccessibilityAction(scenario, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            waitForFlowPage(
                scenario = scenario,
                view = target.view,
                pageIndex = 1,
                scrollY = target.pageOneTop,
                message = "expected accessibility scroll forward to advance one EPUB page",
            )

            performReaderAccessibilityAction(scenario, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            waitForFlowPage(
                scenario = scenario,
                view = target.view,
                pageIndex = 0,
                scrollY = 0,
                message = "expected accessibility scroll backward to return to page 0",
            )

            sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitForFlowPage(
                scenario = scenario,
                view = target.view,
                pageIndex = 1,
                scrollY = target.pageOneTop,
                message = "expected DPAD_RIGHT to advance one EPUB page",
            )

            sendKey(KeyEvent.KEYCODE_DPAD_CENTER)
            waitForObject(By.text("排版"))
            waitForObject(By.text("主题"))
        }
    }

    @Test
    fun epubFlowMoonReaderGestureThresholdsRuntime() {
        val title = "flow-thresholds-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    view
                }
            }

            val thresholdResult = scenario.withActivity {
                val density = view.resources.displayMetrics.density
                val nextPageTop = checkNotNull(view.reflectNullableInt("pageTopPxAt", 1)) {
                    "expected next page top"
                }

                view.scrollTo(0, 0)
                val edge = PointF(view.width * 0.85f, view.height * 0.50f)
                dispatchDrag(
                    view,
                    edge,
                    PointF(edge.x - 18f * density, edge.y),
                )
                val afterSmallHorizontal = FlowThresholdSnapshot(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                )

                view.scrollTo(0, 0)
                val driftStart = PointF(view.width * 0.85f, view.height * 0.40f)
                dispatchDrag(
                    view,
                    driftStart,
                    PointF(driftStart.x - 80f * density, driftStart.y + 45f * density),
                    steps = 1,
                )
                val afterCrossAxisDrift = FlowThresholdSnapshot(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                )

                view.scrollTo(0, 0)
                val verticalStart = PointF(view.width * 0.85f, view.height * 0.50f)
                dispatchDrag(
                    view,
                    verticalStart,
                    PointF(verticalStart.x, verticalStart.y - 18f * density),
                )
                val afterSmallVertical = FlowThresholdSnapshot(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                )

                view.scrollTo(0, 0)
                val clearVerticalStart = PointF(view.width * 0.85f, view.height * 0.50f)
                dispatchDrag(
                    view,
                    clearVerticalStart,
                    PointF(clearVerticalStart.x + 10f * density, clearVerticalStart.y - 60f * density),
                )
                val afterClearVertical = FlowThresholdSnapshot(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                )

                FlowThresholdResult(
                    nextPageTop = nextPageTop,
                    afterSmallHorizontal = afterSmallHorizontal,
                    afterCrossAxisDrift = afterCrossAxisDrift,
                    afterSmallVertical = afterSmallVertical,
                    afterClearVertical = afterClearVertical,
                )
            }

            assertEquals("horizontal movement under 20dp should not turn", 0, thresholdResult.afterSmallHorizontal.currentPage)
            assertEquals("horizontal movement under 20dp should not scroll", 0, thresholdResult.afterSmallHorizontal.scrollY)
            assertEquals("horizontal movement with over-40dp vertical drift should not turn", 0, thresholdResult.afterCrossAxisDrift.currentPage)
            assertEquals("horizontal movement with over-40dp vertical drift should not scroll", 0, thresholdResult.afterCrossAxisDrift.scrollY)
            assertEquals("vertical movement under 20dp should not turn", 0, thresholdResult.afterSmallVertical.currentPage)
            assertEquals("vertical movement under 20dp should not scroll", 0, thresholdResult.afterSmallVertical.scrollY)
            assertEquals("clear side-column vertical swipe should turn one page", 1, thresholdResult.afterClearVertical.currentPage)
            assertEquals(
                "clear side-column vertical swipe should land on next canonical page top",
                thresholdResult.nextPageTop,
                thresholdResult.afterClearVertical.scrollY,
            )
        }
    }

    @Test
    fun epubFlowLongPressDragDoesNotTriggerPagedFlipRuntime() {
        val title = "flow-longpress-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    view
                }
            }

            val points = scenario.withActivity {
                view.scrollTo(0, 0)
                val down = PointF(view.width * 0.85f, view.height * 0.50f)
                down to PointF(view.width * 0.15f, down.y)
            }
            dispatchLongPressDrag(view, points.first, points.second)
            val result = scenario.withActivity {
                FlowLongPressDragResult(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                )
            }

            assertEquals("long-press drag should not trigger NEXT/PREV", 0, result.currentPage)
            assertEquals("long-press drag should not scroll the paged anchor", 0, result.scrollY)

            val centerDeadPoints = scenario.withActivity {
                view.scrollTo(0, 0)
                val down = PointF(view.width * 0.37f, view.height * 0.50f)
                down to PointF(down.x, view.height * 0.10f)
            }
            dispatchLongPressDrag(view, centerDeadPoints.first, centerDeadPoints.second)
            val centerDeadResult = scenario.withActivity {
                FlowLongPressDragResult(
                    currentPage = view.reflectInt("currentPageIndex"),
                    scrollY = view.scrollY,
                )
            }

            assertEquals("center dead-zone long-press drag should not trigger NEXT/PREV", 0, centerDeadResult.currentPage)
            assertEquals("center dead-zone long-press drag should not scroll", 0, centerDeadResult.scrollY)
        }
    }

    @Test
    fun epubFlowLinkTapInPageTurnZoneDoesNotTriggerPagedFlipRuntime() {
        val title = "flow-link-${UUID.randomUUID().toString().take(8)}"
        val uri = createLinkedEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected visible right-side link in flow view") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 1 || view.width <= 0 || view.height <= 0) return@withActivity null
                    view.scrollTo(0, 0)
                    val textView = view.reflectTextView()
                    val point = view.visibleRightSideClickablePoint(textView) ?: return@withActivity null
                    FlowLinkTarget(view = view, point = point)
                }
            }

            dispatchTap(target.view, target.point)

            val result = scenario.withActivity {
                FlowLinkTapResult(
                    currentPage = target.view.reflectInt("currentPageIndex"),
                    scrollY = target.view.scrollY,
                    linkTapConsumed = target.view.reflectTextView()
                        .getTag(RenderApiR.id.selection_aware_interactive_tap_consumed) == true,
                )
            }

            assertTrue("link tap should be consumed by the child text view", result.linkTapConsumed)
            assertEquals("link tap in NEXT zone must not turn the page", 0, result.currentPage)
            assertEquals("link tap should stay on the linked top anchor", 0, result.scrollY)
        }
    }

    @Test
    fun epubFlowSimulationCurlCreatesRealOverlayAndCommitsRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-gl-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val start = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val nextPageTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    FlowGlCurlStart(view = view, nextPageTop = nextPageTop)
                }
            }

            val started = scenario.withActivity { activity ->
                start.view.scrollTo(0, 0)
                start.view.reflectBoolean("goToAdjacentPage", 1)
                FlowGlCurlStarted(
                    overlayCreated = activity.findEpubCurlOverlay() != null,
                    currentPage = start.view.reflectInt("currentPageIndex"),
                    scrollY = start.view.scrollY,
                )
            }
            assertTrue("SIMULATION should create the real EpubCurlOverlay", started.overlayCreated)

            val settled = waitForConditionResult("expected real GL curl to commit to page 1") {
                scenario.withActivity {
                    val currentPage = start.view.reflectInt("currentPageIndex")
                    val scrollY = start.view.scrollY
                    if (currentPage == 1 && scrollY == start.nextPageTop) {
                        FlowGlCurlSettled(currentPage = currentPage, scrollY = scrollY)
                    } else {
                        null
                    }
                }
            }
            assertEquals(1, settled.currentPage)
            assertEquals(start.nextPageTop, settled.scrollY)
        }
    }

    @Test
    fun epubFlowSimulationCurlIgnoresRapidSecondTurnRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-gl-rapid-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val start = waitForConditionResult("expected flow view to paginate") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (pageCount <= 4 || view.width <= 0 || view.height <= 0) return@withActivity null
                    val nextPageTop = view.reflectNullableInt("pageTopPxAt", 1) ?: return@withActivity null
                    FlowGlCurlStart(view = view, nextPageTop = nextPageTop)
                }
            }

            val rapid = scenario.withActivity { activity ->
                start.view.scrollTo(0, 0)
                start.view.reflectBoolean("goToAdjacentPage", 1)
                start.view.reflectBoolean("goToAdjacentPage", 1)
                FlowGlCurlStarted(
                    overlayCreated = activity.findEpubCurlOverlay() != null,
                    currentPage = start.view.reflectInt("currentPageIndex"),
                    scrollY = start.view.scrollY,
                )
            }
            assertTrue("SIMULATION should create the real EpubCurlOverlay", rapid.overlayCreated)
            assertEquals("second rapid turn must not pre-advance while GL curl is active", 0, rapid.currentPage)
            assertEquals("second rapid turn must not move the live anchor while GL curl is active", 0, rapid.scrollY)

            val settled = waitForConditionResult("expected rapid GL curl sequence to settle to page 1 only") {
                scenario.withActivity {
                    val currentPage = start.view.reflectInt("currentPageIndex")
                    val scrollY = start.view.scrollY
                    if (currentPage == 1 && scrollY == start.nextPageTop) {
                        FlowGlCurlSettled(currentPage = currentPage, scrollY = scrollY)
                    } else {
                        null
                    }
                }
            }
            assertEquals(1, settled.currentPage)
            assertEquals(start.nextPageTop, settled.scrollY)

            SystemClock.sleep(650)
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            val final = scenario.withActivity {
                FlowGlCurlSettled(
                    currentPage = start.view.reflectInt("currentPageIndex"),
                    scrollY = start.view.scrollY,
                )
            }
            assertEquals("rapid second turn must not queue a later page-2 commit", 1, final.currentPage)
            assertEquals(start.nextPageTop, final.scrollY)
        }
    }

    private fun readerIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(appContext, MainActivity::class.java)
            setDataAndType(uri, "application/epub+zip")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("epub-flow-anchor-runtime-smoke", uri)
        }

    private fun mainIntent() =
        Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun createEpubUri(fileName: String): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeEpub(file, linked = false)
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    private fun createLinkedEpubUri(fileName: String): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeEpub(file, linked = true)
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    private fun createComplexEpubUri(fileName: String): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeComplexEpub(file)
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    private fun writeEpub(file: File, linked: Boolean) {
        val body = buildString {
            appendLine("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>")
            appendLine("<h1 id=\"top\">Flow Anchor Runtime</h1>")
            if (linked) {
                repeat(8) { row ->
                    append("<p>")
                    repeat(18) { index ->
                        append("word$row-$index ")
                        append("<a href=\"#top\">LINK$row-$index</a> ")
                    }
                    appendLine("</p>")
                }
            }
            repeat(140) { index ->
                appendLine("<p>RFLOW-ANCHOR-${index.toString().padStart(3, '0')} marker text fills the page for runtime anchor validation.</p>")
            }
            appendLine("</body></html>")
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun addText(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
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
                """
                    <package version="3.0">
                      <manifest>
                        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                      </spine>
                    </package>
                """.trimIndent(),
            )
            addText("OEBPS/ch1.xhtml", body)
        }
    }

    private fun writeComplexEpub(file: File) {
        val body = buildString {
            appendLine("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>")
            appendLine("<h1 id=\"top\">Flow Complex Anchor Runtime</h1>")
            appendLine("<p><img src=\"scene.png\" alt=\"blue scene\"/> Opening image keeps bitmap layout in the scroll viewport.</p>")
            repeat(96) { index ->
                val marker = "COMPLEX-FLOW-${index.toString().padStart(3, '0')}"
                when (index % 8) {
                    0 -> appendLine("<p><strong>$marker</strong> 图文混排段落，带 <a href=\"#top\">internal link</a> and inline emphasis.</p>")
                    1 -> appendLine("<blockquote><p><em>$marker</em> 引用块需要和正文一起冻结。</p></blockquote>")
                    2 -> appendLine("<ul><li>$marker list item one</li><li>列表第二项保留缩进和行距。</li></ul>")
                    3 -> appendLine("<table><tr><td>$marker</td><td>cell-${index}</td></tr></table>")
                    4 -> appendLine("<pre>$marker  preformatted    spacing\nline two for viewport snapshot</pre>")
                    5 -> appendLine("<p><ruby>$marker<rt>rt</rt></ruby>：雨声、对话、标点都在同一页。</p>")
                    6 -> appendLine("<p><span>$marker</span><br/><span>second visual line after a break.</span></p>")
                    else -> appendLine("<p>$marker plain paragraph pads the runtime document for mid-scroll conversion.</p>")
                }
            }
            appendLine("</body></html>")
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun addText(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            fun addBinary(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
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
                """
                    <package version="3.0">
                      <manifest>
                        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="scene" href="scene.png" media-type="image/png"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                      </spine>
                    </package>
                """.trimIndent(),
            )
            addText("OEBPS/ch1.xhtml", body)
            addBinary("OEBPS/scene.png", tinyPngBytes(Color.rgb(0x25, 0x69, 0xBE)))
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

    private fun dismissBlockingDialogs() {
        listOf("暂不", "Not now", "不允许", "Don't allow", "Don’t allow").forEach { text ->
            device.wait(Until.findObject(By.text(text)), 1_000)?.click()
            device.waitForIdle()
        }
    }

    private fun waitForObject(selector: androidx.test.uiautomator.BySelector) =
        checkNotNull(device.wait(Until.findObject(selector), UI_TIMEOUT_MS)) {
            "Timed out waiting for $selector"
        }

    private fun waitForBookByTitle(title: String): BookEntity =
        waitForConditionResult("expected imported EPUB book row for title $title", DB_TIMEOUT_MS) {
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

    private fun <T> waitForConditionResult(
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
        onActivity { activity -> result = runCatching { block(activity) } }
        return checkNotNull(result) { "activity callback did not return a result" }.getOrThrow()
    }

    private fun waitForFirstFlowFrame(scenario: ActivityScenario<MainActivity>): FlowReopenFrame {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = scenario.withActivity { activity ->
                val view = activity.findEpubFlowView() ?: return@withActivity null
                FlowReopenFrame(
                    currentPage = runCatching { view.reflectInt("currentPageIndex") }.getOrDefault(0),
                    scrollY = view.scrollY,
                    contentAlpha = view.flowContentAlpha(),
                    pageCount = runCatching { view.reflectInt("pageCount") }.getOrDefault(0),
                )
            }
            if (frame != null) return frame
            Thread.sleep(FRAME_POLL_MS)
        }
        return checkNotNull(
            scenario.withActivity { activity ->
                val view = activity.findEpubFlowView() ?: return@withActivity null
                FlowReopenFrame(
                    currentPage = runCatching { view.reflectInt("currentPageIndex") }.getOrDefault(0),
                    scrollY = view.scrollY,
                    contentAlpha = view.flowContentAlpha(),
                    pageCount = runCatching { view.reflectInt("pageCount") }.getOrDefault(0),
                )
            },
        ) {
            "expected first EPUB flow frame after reopen"
        }
    }

    private fun traceFlowFramesUntilRestored(
        scenario: ActivityScenario<MainActivity>,
        expectedPage: Int,
        expectedScrollY: Int,
        message: String,
    ): FlowReopenTrace {
        val frames = mutableListOf<FlowReopenFrame>()
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = scenario.withActivity { activity ->
                val view = activity.findEpubFlowView() ?: return@withActivity null
                FlowReopenFrame(
                    currentPage = runCatching { view.reflectInt("currentPageIndex") }.getOrDefault(0),
                    scrollY = view.scrollY,
                    contentAlpha = view.flowContentAlpha(),
                    pageCount = runCatching { view.reflectInt("pageCount") }.getOrDefault(0),
                )
            }
            if (frame != null) {
                frames += frame
                if (frame.currentPage == expectedPage && frame.scrollY == expectedScrollY && frame.contentAlpha >= 1f) {
                    return FlowReopenTrace(frames = frames, restored = frame)
                }
            }
            Thread.sleep(FRAME_POLL_MS)
        }
        error("$message; trace=$frames")
    }

    private fun waitForConversionCoverFrame(activity: MainActivity, message: String): FlowConversionDuring {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        var firstPagedWithoutCover: FlowConversionAfter? = null
        while (System.currentTimeMillis() < deadline) {
            val frame = activity.runOnMainForTest {
                val view = activity.findEpubFlowView()
                if (view == null) {
                    null
                } else {
                    val cover = view.reflectPrivateAny("conversionSnapshotDrawable")
                    if (cover != null) {
                        val bitmap = cover.reflectPrivateBitmap("bitmap")
                        check(!bitmap.isRecycled) { "conversion cover bitmap should be alive during UI conversion" }
                        FlowConversionDuring(
                            currentPage = view.reflectInt("currentPageIndex"),
                            scrollY = view.scrollY,
                            contentAlpha = view.flowContentAlpha(),
                            coverAlpha = cover.reflectPrivateInt("alphaValue"),
                            coverBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                            drawnFrame = view.drawRuntimeBitmap(),
                        )
                    } else {
                        val mode = view.reflectPrivateAny("modeValue")?.toString()
                        val alpha = view.flowContentAlpha()
                        if (mode == "PAGED" && alpha >= 1f && firstPagedWithoutCover == null) {
                            firstPagedWithoutCover = FlowConversionAfter(
                                currentPage = view.reflectInt("currentPageIndex"),
                                scrollY = view.scrollY,
                                contentAlpha = alpha,
                            )
                        }
                        null
                    }
                }
            }
            if (frame != null) return frame
            Thread.sleep(FRAME_POLL_MS)
        }
        error("$message; firstPagedWithoutCover=$firstPagedWithoutCover")
    }

    private fun <T> MainActivity.runOnMainForTest(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        instrumentation.runOnMainSync {
            result = runCatching(block)
        }
        return checkNotNull(result) { "main-thread callback did not return a result" }.getOrThrow()
    }

    private fun MainActivity.findEpubFlowView(): View? =
        window.decorView.findDescendant { it.javaClass.name == EPUB_FLOW_VIEW_CLASS_NAME }

    private fun MainActivity.findEpubCurlOverlay(): View? =
        window.decorView.findDescendant { it.javaClass.name == EPUB_CURL_OVERLAY_CLASS_NAME }

    private fun MainActivity.findReaderSurface(): View =
        checkNotNull(findReaderSurfaceOrNull()) {
            "Unable to find reader surface"
        }

    private fun MainActivity.findReaderSurfaceOrNull(): View? =
        window.decorView.findDescendant { view ->
            view.contentDescription?.toString()?.startsWith("阅读内容") == true
        }

    private fun MainActivity.readerSurfaceAccessibilityActions(): List<String> {
        val node = checkNotNull(findReaderSurface().createAccessibilityNodeInfo()) {
            "Unable to create accessibility node info for the reader surface"
        }
        return try {
            node.actionList.mapNotNull { it.label?.toString() }
        } finally {
            node.recycle()
        }
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

    private fun sendKey(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    private fun waitForFlowPage(
        scenario: ActivityScenario<MainActivity>,
        view: View,
        pageIndex: Int,
        scrollY: Int,
        message: String,
    ) {
        waitForConditionResult(message) {
            scenario.withActivity {
                val currentPage = view.reflectInt("currentPageIndex")
                if (currentPage == pageIndex && view.scrollY == scrollY) {
                    FlowCleanTapResult(currentPage = currentPage, scrollY = view.scrollY)
                } else {
                    null
                }
            }
        }
    }

    private fun View.findDescendant(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findDescendant(predicate)?.let { return it }
        }
        return null
    }

    private fun View.reflectInt(name: String): Int =
        javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this) as Int

    private fun View.reflectNullableInt(name: String, value: Int): Int? =
        javaClass.getDeclaredMethod(name, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(this, value) as? Int

    private fun View.reflectBoolean(name: String, value: Int): Boolean =
        javaClass.getDeclaredMethod(name, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(this, value) as Boolean

    private fun View.reflectPrivateBoolean(name: String): Boolean =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Boolean

    private fun View.reflectPrivateAny(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun View.reflectTextView(): TextView =
        javaClass.getDeclaredField("textView").apply { isAccessible = true }.get(this) as TextView

    private fun View.invokeSetModeAnchoredPaged(layoutOffset: Int) {
        val modeClass = Class.forName("$EPUB_FLOW_VIEW_CLASS_NAME\$Mode")
        val pagedMode = checkNotNull(modeClass.enumConstants) { "EpubFlowView.Mode enum constants unavailable" }
            .first { (it as Enum<*>).name == "PAGED" }
        javaClass.getDeclaredMethod("setModeAnchored", modeClass, Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(this, pagedMode, layoutOffset)
    }

    private fun Any.reflectPrivateInt(name: String): Int =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Int

    private fun Any.reflectPrivateBitmap(name: String): Bitmap =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Bitmap

    private fun View.flowContentAlpha(): Float =
        ((this as? ViewGroup)?.getChildAt(0) as? View)?.alpha ?: alpha

    private fun View.visibleRightSideClickablePoint(textView: TextView): PointF? {
        val layout = textView.layout ?: return null
        val spanned = textView.text as? Spanned ?: return null
        val spans = spanned.getSpans(0, spanned.length, ClickableSpan::class.java)
        val rightZoneStart = width * 2f / 3f
        spans.forEach { span ->
            val spanStart = spanned.getSpanStart(span)
            val spanEnd = spanned.getSpanEnd(span)
            if (spanStart < 0 || spanEnd <= spanStart) return@forEach
            val offset = (spanStart + 1).coerceAtMost(spanEnd - 1)
            val line = layout.getLineForOffset(offset)
            val x = layout.getPrimaryHorizontal(offset) + textView.totalPaddingLeft - textView.scrollX
            val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f +
                textView.totalPaddingTop -
                scrollY
            if (x > rightZoneStart && y in 0f..height.toFloat()) {
                return PointF(x, y)
            }
        }
        return null
    }

    private fun dispatchTap(target: View, point: PointF) {
        val downTime = SystemClock.uptimeMillis()
        dispatchTouchEvent(target, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point))
        dispatchTouchEvent(target, motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_UP, point))
    }

    private fun dispatchTemporaryScrollAndRelease(target: View, down: PointF): FlowTemporaryScrollResult {
        val startY = target.scrollY
        val armMove = PointF(down.x, target.height * 0.30f)
        val scrolledMove = PointF(down.x, target.height * 0.10f)
        val downTime = SystemClock.uptimeMillis()
        dispatchTouchEvent(target, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
        dispatchTouchEvent(target, motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_MOVE, armMove))
        dispatchTouchEvent(
            target,
            motionEvent(downTime, downTime + EVENT_STEP_MS * 2, MotionEvent.ACTION_MOVE, scrolledMove),
        )
        val duringDragY = target.scrollY
        val duringDragClip = target.reflectPrivateBoolean("pageClipActive")
        dispatchTouchEvent(target, motionEvent(downTime, downTime + EVENT_STEP_MS * 3, MotionEvent.ACTION_UP, scrolledMove))
        return FlowTemporaryScrollResult(
            startY = startY,
            duringDragY = duringDragY,
            afterReleaseY = target.scrollY,
            duringDragClip = duringDragClip,
            currentPage = target.reflectInt("currentPageIndex"),
        )
    }

    private fun dispatchDrag(target: View, start: PointF, end: PointF, steps: Int = 6) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime
        dispatchTouchEvent(target, motionEvent(downTime, eventTime, MotionEvent.ACTION_DOWN, start))
        repeat(steps) { step ->
            val progress = (step + 1) / steps.toFloat()
            eventTime += EVENT_STEP_MS
            dispatchTouchEvent(
                target,
                motionEvent(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    PointF(
                        start.x + (end.x - start.x) * progress,
                        start.y + (end.y - start.y) * progress,
                    ),
                ),
            )
        }
        eventTime += EVENT_STEP_MS
        dispatchTouchEvent(target, motionEvent(downTime, eventTime, MotionEvent.ACTION_UP, end))
    }

    private fun dispatchLongPressDrag(target: View, start: PointF, end: PointF, steps: Int = 6) {
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime
        dispatchTouchEvent(target, motionEvent(downTime, eventTime, MotionEvent.ACTION_DOWN, start))
        SystemClock.sleep((ViewConfiguration.getLongPressTimeout() + 80).toLong())
        instrumentation.waitForIdleSync()
        device.waitForIdle()
        repeat(steps) { step ->
            val progress = (step + 1) / steps.toFloat()
            eventTime = SystemClock.uptimeMillis()
            dispatchTouchEvent(
                target,
                motionEvent(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    PointF(
                        start.x + (end.x - start.x) * progress,
                        start.y + (end.y - start.y) * progress,
                    ),
                ),
            )
        }
        eventTime = SystemClock.uptimeMillis()
        dispatchTouchEvent(target, motionEvent(downTime, eventTime, MotionEvent.ACTION_UP, end))
    }

    private fun dispatchTouchEvent(target: View, event: MotionEvent) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                target.dispatchTouchEvent(event)
            } else {
                instrumentation.runOnMainSync {
                    target.dispatchTouchEvent(event)
                }
            }
        } finally {
            event.recycle()
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            instrumentation.waitForIdleSync()
            device.waitForIdle()
        }
    }

    private fun injectScreenTap(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(
            downTime,
            downTime + EVENT_STEP_MS,
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat(),
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            instrumentation.uiAutomation.injectInputEvent(down, true)
            instrumentation.uiAutomation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun View.drawRuntimeBitmap(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            draw(Canvas(bitmap))
        }

    private fun assertSampledPixelsEqual(message: String, expected: Bitmap, actual: Bitmap) {
        assertEquals("$message: width", expected.width, actual.width)
        assertEquals("$message: height", expected.height, actual.height)
        val stepX = (expected.width / 6).coerceAtLeast(1)
        val stepY = (expected.height / 6).coerceAtLeast(1)
        var y = 0
        while (y < expected.height) {
            var x = 0
            while (x < expected.width) {
                assertEquals("$message at ($x,$y)", expected.getPixel(x, y), actual.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        point: PointF,
    ): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, point.x, point.y, 0)

    private data class FlowAnchorBaseline(
        val view: View,
        val pageCount: Int,
        val pageOneTop: Int,
        val pageTwoTop: Int,
        val pageThreeTop: Int,
    )

    private data class FlowAnchorResult(
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowZoneResult(
        val startY: Int,
        val endY: Int,
    )

    private data class FlowZonePageResult(
        val startY: Int,
        val endY: Int,
        val currentPage: Int,
    )

    private data class FlowTemporaryScrollResult(
        val startY: Int,
        val duringDragY: Int,
        val afterReleaseY: Int,
        val duringDragClip: Boolean,
        val currentPage: Int,
    )

    private data class FlowCancelResult(
        val duringDragY: Int,
        val duringDragClip: Boolean,
        val afterCancelClip: Boolean,
        val currentPage: Int,
    )

    private data class FlowEdgeTurnResult(
        val currentPage: Int,
        val scrollY: Int,
        val expectedTop: Int,
    )

    private data class FlowCleanTapTarget(
        val view: View,
        val nextPageTop: Int,
    )

    private data class FlowCleanTapResult(
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowReopenTarget(
        val view: View,
        val pageTop: Int,
    )

    private data class FlowReopenFrame(
        val currentPage: Int,
        val scrollY: Int,
        val contentAlpha: Float,
        val pageCount: Int,
    )

    private data class FlowReopenTrace(
        val frames: List<FlowReopenFrame>,
        val restored: FlowReopenFrame,
    )

    private data class FlowConversionBefore(
        val scrollY: Int,
        val layoutOffset: Int,
        val contentAlpha: Float,
        val frame: Bitmap,
    )

    private data class FlowConversionDuring(
        val currentPage: Int,
        val scrollY: Int,
        val contentAlpha: Float,
        val coverAlpha: Int,
        val coverBitmap: Bitmap,
        val drawnFrame: Bitmap? = null,
    )

    private data class FlowConversionAfter(
        val currentPage: Int,
        val scrollY: Int,
        val contentAlpha: Float,
    )

    private data class FlowSurfaceTapTarget(
        val surface: View,
        val view: View,
        val nextPageTop: Int,
    )

    private data class FlowFirstSlideTapResult(
        val currentPage: Int,
        val scrollY: Int,
        val slideDrawablePresent: Boolean,
        val flipAnimatorPresent: Boolean,
    )

    private data class FlowCenterTapTarget(
        val view: View,
        val startPage: Int,
        val startScrollY: Int,
    )

    private data class FlowCenterTapResult(
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowAccessibilityTarget(
        val view: View,
        val pageOneTop: Int,
        val actions: List<String>,
    )

    private data class FlowGlCurlStart(
        val view: View,
        val nextPageTop: Int,
    )

    private data class FlowGlCurlStarted(
        val overlayCreated: Boolean,
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowGlCurlSettled(
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowThresholdSnapshot(
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowThresholdResult(
        val nextPageTop: Int,
        val afterSmallHorizontal: FlowThresholdSnapshot,
        val afterCrossAxisDrift: FlowThresholdSnapshot,
        val afterSmallVertical: FlowThresholdSnapshot,
        val afterClearVertical: FlowThresholdSnapshot,
    )

    private data class FlowLongPressDragResult(
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowLinkTarget(
        val view: View,
        val point: PointF,
    )

    private data class FlowLinkTapResult(
        val currentPage: Int,
        val scrollY: Int,
        val linkTapConsumed: Boolean,
    )

    private companion object {
        private const val EPUB_FLOW_VIEW_CLASS_NAME = "dev.readflow.render.epub.EpubFlowView"
        private const val EPUB_CURL_OVERLAY_CLASS_NAME = "dev.readflow.render.epub.EpubCurlOverlay"
        private const val DB_NAME = "readflow.db"
        private const val EVENT_STEP_MS = 24L
        private const val FRAME_POLL_MS = 16L
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
