package dev.readflow.page05

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.graphics.PointF
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.InputDevice
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.readflow.MainActivity
import dev.readflow.core.database.BookEntity
import dev.readflow.core.database.ReadflowDatabase
import dev.readflow.core.database.ReadingProgressEntity
import dev.readflow.core.database.ReadingSessionEntity
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.DataStoreSettingsRepository
import dev.readflow.render.api.R as RenderApiR
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun epubImportExtractsCoverWithAndroidXmlParserRuntime() {
        val title = "epub-cover-${UUID.randomUUID().toString().take(8)}"
        val uri = createCoverEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use {
            dismissBlockingDialogs()
            val book = waitForBookByTitle(title)
            val coverUrl = checkNotNull(book.coverUrl) {
                "Android EPUB import must persist the extracted cover URL"
            }
            val coverFile = File(checkNotNull(Uri.parse(coverUrl).path))
            assertTrue("extracted EPUB cover must exist", coverFile.isFile && coverFile.length() > 0L)
            val bitmap = checkNotNull(BitmapFactory.decodeFile(coverFile.path)) {
                "extracted EPUB cover must be a decodable image"
            }
            try {
                assertEquals(
                    "extracted EPUB cover should preserve the source image",
                    Color.rgb(0x25, 0x69, 0xBE),
                    bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun epubFlowTurnAfterFreeRestContinuesAtTheNextFullLineRuntime() {
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
                    FlowAnchorBaseline(view, pageCount, pageOneTop, pageTwoTop)
                }
            }
            assertTrue("expected at least 5 pages, got ${baseline.pageCount}", baseline.pageCount > 4)

            val result = scenario.withActivity {
                val freeRest = baseline.view.nonLineTopBetween(
                    baseline.pageOneTop,
                    baseline.pageTwoTop,
                    fraction = 0.75f,
                )
                baseline.view.scrollTo(0, freeRest)
                val before = baseline.view.fullLineRangeInViewport()
                baseline.view.reflectBoolean("goToAdjacentPage", 1)
                val after = baseline.view.fullLineRangeInViewport()
                val layout = checkNotNull(baseline.view.reflectTextView().layout)
                FlowAnchorResult(
                    outgoingLastFullLine = before.last,
                    incomingFirstFullLine = after.first,
                    incomingLineTop = layout.getLineTop(after.first),
                    scrollY = baseline.view.scrollY,
                )
            }

            assertEquals(
                "runtime forward turn must neither repeat nor skip a complete line",
                result.outgoingLastFullLine + 1,
                result.incomingFirstFullLine,
            )
            assertEquals("runtime turned page must park on the incoming full-line top", result.incomingLineTop, result.scrollY)
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
            assertEquals(
                "slow inner-center UP should retain the arbitrary FREE_REST viewport",
                innerResult.duringDragY,
                innerResult.afterReleaseY,
            )
            assertEquals("slow inner-center UP should re-arm full-line clipping", true, innerResult.afterReleaseClip)

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
            assertEquals(
                "slow inner-center boundary UP should retain the arbitrary FREE_REST viewport",
                innerBoundaryResult.duringDragY,
                innerBoundaryResult.afterReleaseY,
            )
            assertEquals("slow inner-center boundary UP should re-arm full-line clipping", true, innerBoundaryResult.afterReleaseClip)

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
    fun epubFlowSystemBackFlushesLatestLocatorAndReadingSessionRuntime() {
        val title = "flow-system-back-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val book = waitForBookByTitle(title)
            val initialProgress = waitForConditionResult(
                message = "expected initial EPUB progress before exercising system Back",
                timeoutMs = DB_TIMEOUT_MS,
            ) {
                latestProgress(book.id)
            }
            val target = waitForConditionResult("expected flow view to paginate before system Back") {
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
            assertEquals("setup should park on page 3 before system Back", 3, parked.currentPage)
            assertEquals("setup should land on the page-3 canonical top", target.pageTop, parked.scrollY)

            val expectedProgress = waitForConditionResult(
                message = "expected page-3 locator to persist before seeding the stale Back sentinel",
                timeoutMs = DB_TIMEOUT_MS,
            ) {
                latestProgress(book.id)?.takeIf { progress ->
                    progress.locatorJson != initialProgress.locatorJson &&
                        progress.totalProgression > initialProgress.totalProgression
                }
            }
            SystemClock.sleep(1_200)
            val staleProgress = initialProgress.copy(
                updatedAt = System.currentTimeMillis(),
                deviceId = "system-back-sentinel",
            )
            upsertProgress(staleProgress)
            assertEquals(staleProgress.locatorJson, latestProgress(book.id)?.locatorJson)
            assertTrue("reading session must be written by close, not before Back", readingSessions(book.id).isEmpty())

            device.pressBack()
            device.waitForIdle()
            assertTrue(
                "system Back should return from Reader to the shelf",
                device.wait(Until.findObject(By.desc("打开 $title")), UI_TIMEOUT_MS) != null,
            )

            var persistedProgress = latestProgress(book.id)
            var sessions = readingSessions(book.id)
            val deadline = System.currentTimeMillis() + DB_TIMEOUT_MS
            while (
                System.currentTimeMillis() < deadline &&
                (persistedProgress?.locatorJson != expectedProgress.locatorJson || sessions.size != 1)
            ) {
                Thread.sleep(150)
                persistedProgress = latestProgress(book.id)
                sessions = readingSessions(book.id)
            }

            assertEquals(
                "system Back must flush the latest locator before leaving Reader; sessions=$sessions",
                expectedProgress.locatorJson,
                persistedProgress?.locatorJson,
            )
            assertEquals(
                "system Back must flush the latest total progression",
                expectedProgress.totalProgression.toDouble(),
                persistedProgress?.totalProgression?.toDouble() ?: -1.0,
                0.0001,
            )
            assertEquals("system Back must write exactly one reading session", 1, sessions.size)
            assertTrue("system Back session must include meaningful reading time", sessions.single().durationMs >= 1_000)
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
            val activity = shelf.withActivity { it }
            val shelfItem = waitForObject(By.desc("打开 $title"))
            val bounds = shelfItem.visibleBounds
            injectScreenTap(bounds.centerX(), bounds.centerY())

            val trace = traceFlowFramesUntilRestored(
                activity = activity,
                expectedPage = 3,
                expectedScrollY = expectedPageTop,
                message = "expected shelf OpenById to restore EPUB page-3 anchor",
            )
            val exposedLowFrame = trace.frames.firstOrNull { frame ->
                frame.contentAlpha > VISIBLE_CONTENT_ALPHA_EPSILON &&
                    frame.currentPage == 0 &&
                    frame.scrollY < expectedPageTop / 2
            }
            assertTrue(
                "shelf OpenById must not expose any visibly faded low-scroll frame while restoring; " +
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
                        drawnFrame = view.drawRuntimeBitmap(),
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
                    val coveredFrame = checkNotNull(during.drawnFrame) {
                        "expected covered conversion frame captured while the cover was still installed"
                    }
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
                    drawnFrame = view.drawRuntimeBitmap(),
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
                    val coveredFrame = checkNotNull(during.drawnFrame) {
                        "expected complex covered conversion frame captured while the cover was still installed"
                    }
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
                    assertTrue(
                        "UI SCROLL->PAGED conversion cover should still contribute to the visible frame when observed, " +
                            "alpha=${during.coverAlpha}",
                        during.coverAlpha > 0,
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
                    during.drawnFrame?.let { coveredFrame ->
                        try {
                            if (during.coverAlpha == 255) {
                                assertSampledPixelsEqual(
                                    "UI SCROLL->PAGED conversion should visually show only the frozen viewport cover before fade starts",
                                    during.coverBitmap,
                                    coveredFrame,
                                )
                            }
                        } finally {
                            coveredFrame.recycle()
                        }
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
    fun epubFlowShortChapterBoundaryRightTapStartsSlideAnimationRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-short-boundary-${UUID.randomUUID().toString().take(8)}"
        val uri = createShortChapterBoundaryEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val target = waitForConditionResult("expected first short flow chapter to settle") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    val text = view.reflectTextView().text.toString()
                    if (
                        pageCount != 1 ||
                        view.width <= 0 ||
                        view.height <= 0 ||
                        view.flowContentAlpha() < 1f ||
                        !text.contains("Chapter one end.")
                    ) {
                        return@withActivity null
                    }
                    FlowBoundaryTapTarget(view = view)
                }
            }

            scenario.withActivity {
                val point = PointF(target.view.width * 0.85f, target.view.height * 0.50f)
                dispatchTap(target.view, point)
            }

            val result = waitForConditionResult("expected short-chapter boundary tap to animate into chapter two") {
                scenario.withActivity {
                    val text = target.view.reflectTextView().text.toString()
                    val animatorPresent = target.view.reflectPrivateAny("flipAnimator") != null
                    if (!text.contains("Chapter two start.") || !animatorPresent) return@withActivity null
                    FlowBoundaryTapResult(
                        currentPage = target.view.reflectInt("currentPageIndex"),
                        scrollY = target.view.scrollY,
                        pendingConsumed = target.view.reflectPrivateAny("pendingBoundaryPageTurn") == null,
                        flipAnimatorPresent = animatorPresent,
                        text = text,
                    )
                }
            }

            assertEquals("short boundary turn should park on the first page of chapter two", 0, result.currentPage)
            assertEquals("short boundary turn should land at the top of chapter two", 0, result.scrollY)
            assertTrue("chapter two text should be visible after the boundary turn", result.text.contains("Chapter two start."))
            assertTrue("boundary page shot should be consumed by the target chapter", result.pendingConsumed)
            assertTrue("short chapter boundary tap must start the slide animator", result.flipAnimatorPresent)
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
    fun epubFlowSimulationDiscreteTurnUsesA2DrawableAndCommitsRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-a2-discrete-${UUID.randomUUID().toString().take(8)}"
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
                    FlowSimulationTurnStart(view = view, nextPageTop = nextPageTop)
                }
            }

            val started = scenario.withActivity { activity ->
                start.view.scrollTo(0, 0)
                check(start.view.reflectBoolean("goToAdjacentPage", 1))
                check(activity.findLegacyEpubCurlOverlay() == null) {
                    "SIMULATION discrete turn must not create the removed GL overlay child"
                }
                FlowSimulationTurnStarted(
                    drawableName = start.view.reflectPrivateAny("curlDrawable")?.javaClass?.simpleName,
                    animatorRunning = (start.view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true,
                    currentPage = start.view.reflectInt("currentPageIndex"),
                    scrollY = start.view.scrollY,
                )
            }
            assertEquals("PageCurlDrawable", started.drawableName)
            assertTrue("A2 discrete turn must animate in the local View overlay", started.animatorRunning)
            assertEquals("the target page should be parked silently beneath the A2 overlay", 1, started.currentPage)
            assertEquals(start.nextPageTop, started.scrollY)

            val settled = waitForConditionResult("expected A2 discrete turn to settle on page 1") {
                scenario.withActivity {
                    val animatorRunning =
                        (start.view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true
                    val currentPage = start.view.reflectInt("currentPageIndex")
                    val scrollY = start.view.scrollY
                    if (
                        currentPage == 1 &&
                        scrollY == start.nextPageTop &&
                        start.view.reflectPrivateAny("curlDrawable") == null &&
                        !animatorRunning
                    ) {
                        FlowTurnSettled(currentPage = currentPage, scrollY = scrollY)
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
    fun epubFlowSimulationA2TurnQueuesRapidSecondTurnRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-a2-rapid-${UUID.randomUUID().toString().take(8)}"
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
                    FlowSimulationTurnStart(view = view, nextPageTop = nextPageTop)
                }
            }

            val rapid = scenario.withActivity { activity ->
                start.view.scrollTo(0, 0)
                check(start.view.reflectBoolean("goToAdjacentPage", 1))
                val firstAnimator = start.view.reflectPrivateAny("flipAnimator")
                check(start.view.reflectBoolean("goToAdjacentPage", 1))
                check(activity.findLegacyEpubCurlOverlay() == null)
                check(start.view.reflectPrivateAny("flipAnimator") === firstAnimator) {
                    "rapid second request must not replace or restart the active A2 animator"
                }
                FlowSimulationTurnStarted(
                    drawableName = start.view.reflectPrivateAny("curlDrawable")?.javaClass?.simpleName,
                    animatorRunning = (firstAnimator as? ValueAnimator)?.isRunning == true,
                    currentPage = start.view.reflectInt("currentPageIndex"),
                    scrollY = start.view.scrollY,
                )
            }
            assertEquals("PageCurlDrawable", rapid.drawableName)
            assertTrue(rapid.animatorRunning)
            assertEquals("second rapid request must not park page 2", 1, rapid.currentPage)
            assertEquals(start.nextPageTop, rapid.scrollY)

            waitForConditionResult("expected rapid A2 turn sequence to drain to page 2") {
                scenario.withActivity {
                    val animatorRunning =
                        (start.view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true
                    if (
                        start.view.reflectInt("currentPageIndex") == 2 &&
                        start.view.reflectPrivateAny("curlDrawable") == null &&
                        !animatorRunning
                    ) true else null
                }
            }

            SystemClock.sleep(650)
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            val final = scenario.withActivity {
                FlowTurnSettled(
                    currentPage = start.view.reflectInt("currentPageIndex"),
                    scrollY = start.view.scrollY,
                )
            }
            assertEquals("rapid second turn must drain to page 2", 2, final.currentPage)
            assertEquals(
                "the queued turn must settle at page 2",
                start.view.reflectNullableInt("pageTopPxAt", 2),
                final.scrollY,
            )
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowSlideWindowFramesTrackFirstMoveAndCancelRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-window-frames-${UUID.randomUUID().toString().take(8)}"
        val uri = createComplexEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected stable paged flow view for window-frame capture") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    val cover = view.reflectPrivateAny("conversionSnapshotDrawable")
                    if (
                        pageCount <= 4 ||
                        view.width <= 0 ||
                        view.height <= 0 ||
                        cover != null ||
                        view.flowContentAlpha() < 1f
                    ) {
                        return@withActivity null
                    }
                    view.scrollTo(0, 0)
                    view
                }
            }
            waitForConditionResult("expected reader activity window focus before committed-frame capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            val touchSize = scenario.withActivity { view.width to view.height }

            awaitCommittedFrame(scenario, view)
            val stableA = captureWindowRegion(scenario, view)
            awaitCommittedFrame(scenario, view)
            val stable = captureWindowRegion(scenario, view)
            val downTime = SystemClock.uptimeMillis()
            val down = PointF(touchSize.first * 0.85f, touchSize.second * 0.10f)
            val move = PointF(touchSize.first * 0.30f, down.y)
            var downFrame: Bitmap? = null
            var moveFrame: Bitmap? = null
            var cancelFrame: Bitmap? = null
            var settledFrame: Bitmap? = null
            var gestureActive = false

            try {
                assertVisualDiffAtMost(
                    "stable reader window frames must already be visually settled",
                    bitmapDiff(stableA, stable),
                    maxRgbMae = 0.25,
                    maxBadPixelRatio = 0.001,
                )

                scenario.withActivity {
                    dispatchTouchEvent(view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
                }
                gestureActive = true
                awaitCommittedFrame(scenario, view)
                downFrame = captureWindowRegion(scenario, view)
                assertVisualDiffAtMost(
                    "ACTION_DOWN must not change the real composed reader window",
                    bitmapDiff(stable, checkNotNull(downFrame)),
                    maxRgbMae = 0.35,
                    maxBadPixelRatio = 0.002,
                )
                scenario.withActivity {
                    dispatchTouchEvent(
                        view,
                        motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_MOVE, move),
                    )
                }
                awaitCommittedFrame(scenario, view)
                moveFrame = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("slide-stable.png", stable)
                writeWindowFrameEvidence("slide-down.png", checkNotNull(downFrame))
                writeWindowFrameEvidence("slide-move.png", checkNotNull(moveFrame))
                val shiftPx = abs(down.x - move.x).roundToInt()
                val comparedWidth = (touchSize.first - shiftPx - 2).coerceAtLeast(1)
                val shifted = bitmapRegionDiff(
                    expected = stable,
                    actual = checkNotNull(moveFrame),
                    expectedLeft = shiftPx,
                    actualLeft = 0,
                    width = comparedWidth,
                    height = touchSize.second,
                )
                val stuckAtDown = bitmapRegionDiff(
                    expected = stable,
                    actual = checkNotNull(moveFrame),
                    expectedLeft = 0,
                    actualLeft = 0,
                    width = comparedWidth,
                    height = touchSize.second,
                )
                assertVisualDiffAtMost(
                    "the first presented MOVE must contain the full DOWN-to-MOVE outgoing-page displacement",
                    shifted,
                    maxRgbMae = 3.0,
                    maxBadPixelRatio = 0.03,
                )
                assertTrue(
                    "the first MOVE must match the shifted outgoing frame better than a stuck DOWN frame; " +
                        "shifted=$shifted stuck=$stuckAtDown",
                    shifted.rgbMae < stuckAtDown.rgbMae * 0.70,
                )

                scenario.withActivity {
                    dispatchTouchEvent(
                        view,
                        motionEvent(
                            downTime,
                            downTime + EVENT_STEP_MS * 2,
                            MotionEvent.ACTION_CANCEL,
                            move,
                        ),
                    )
                }
                gestureActive = false
                awaitCommittedFrame(scenario, view)
                cancelFrame = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("slide-cancel.png", checkNotNull(cancelFrame))
                val cancelShiftPx = bestHorizontalShiftPx(
                    expected = stable,
                    actual = checkNotNull(cancelFrame),
                    maxShiftPx = shiftPx,
                )
                assertTrue(
                    "the first CANCEL frame must visibly spring toward the outgoing frame; " +
                        "moveShiftPx=$shiftPx cancelShiftPx=$cancelShiftPx",
                    cancelShiftPx < shiftPx,
                )

                settledFrame = captureUntilVisuallyStable(scenario, view)
                writeWindowFrameEvidence("slide-settled.png", checkNotNull(settledFrame))
                assertVisualDiffAtMost(
                    "CANCEL settle must restore the exact pre-touch composed reader frame",
                    bitmapDiff(stable, checkNotNull(settledFrame)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
                val settledPage = scenario.withActivity { view.reflectInt("currentPageIndex") }
                assertEquals("CANCEL must leave the reader on the outgoing page", 0, settledPage)
            } finally {
                if (gestureActive) {
                    runCatching {
                        scenario.withActivity {
                            dispatchTouchEvent(
                                view,
                                motionEvent(
                                    downTime,
                                    SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_CANCEL,
                                    move,
                                ),
                            )
                        }
                    }
                }
                stableA.recycle()
                stable.recycle()
                downFrame?.recycle()
                moveFrame?.recycle()
                cancelFrame?.recycle()
                settledFrame?.recycle()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowBoundaryCoverOwnsCommittedWaitingWindowRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-boundary-window-${UUID.randomUUID().toString().take(8)}"
        val uri = createComplexEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected stable multi-page flow view for boundary cover capture") {
                scenario.withActivity { activity ->
                    val candidate = activity.findEpubFlowView() ?: return@withActivity null
                    if (
                        candidate.reflectInt("pageCount") <= 4 ||
                        candidate.width <= 0 ||
                        candidate.height <= 0 ||
                        candidate.flowContentAlpha() < 1f ||
                        candidate.reflectPrivateAny("conversionSnapshotDrawable") != null
                    ) {
                        return@withActivity null
                    }
                    candidate.javaClass.getDeclaredMethod("goToLastPage").apply { isAccessible = true }.invoke(candidate)
                    candidate
                }
            }
            waitForConditionResult("expected flow view to park on its final page") {
                scenario.withActivity {
                    val lastPage = view.reflectInt("pageCount") - 1
                    if (view.reflectInt("currentPageIndex") == lastPage) true else null
                }
            }
            waitForConditionResult("expected reader activity window focus before boundary capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            awaitCommittedFrame(scenario, view)
            val stableA = captureWindowRegion(scenario, view)
            awaitCommittedFrame(scenario, view)
            val stable = captureWindowRegion(scenario, view)
            var preparedCover: Bitmap? = null
            var waiting: Bitmap? = null
            try {
                assertVisualDiffAtMost(
                    "boundary test must begin from a visually settled Window",
                    bitmapDiff(stableA, stable),
                    maxRgbMae = 0.25,
                    maxBadPixelRatio = 0.001,
                )
                scenario.withActivity {
                    check(view.reflectBoolean("prepareBoundaryPageTurn", 1)) {
                        "expected final page to prepare an animated boundary transaction"
                    }
                    val cover = checkNotNull(view.reflectPrivateAny("conversionSnapshotDrawable"))
                    preparedCover = cover.reflectPrivateBitmap("bitmap").copy(Bitmap.Config.ARGB_8888, false)
                    (view as ViewGroup).getChildAt(0).alpha = 0f
                    check(view.reflectPrivateAny("pendingBoundaryPageTurn") != null)
                }

                writeWindowFrameEvidence("boundary-prepared-cover.png", checkNotNull(preparedCover))
                assertVisualDiffAtMost(
                    "the prepared outgoing page shot must equal the last committed Window before it takes ownership",
                    bitmapDiff(stable, checkNotNull(preparedCover)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )

                awaitCommittedFrame(scenario, view)
                waiting = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("boundary-stable.png", stable)
                writeWindowFrameEvidence("boundary-waiting.png", checkNotNull(waiting))
                assertVisualDiffAtMost(
                    "the outgoing page shot must keep owning the committed Window while live target content is hidden",
                    bitmapDiff(stable, checkNotNull(waiting)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
            } finally {
                stableA.recycle()
                stable.recycle()
                preparedCover?.recycle()
                waiting?.recycle()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowBoundarySnapshotFailureKeepsCommittedWindowAndHealthyRetryCrossesRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-boundary-retry-window-${UUID.randomUUID().toString().take(8)}"
        val uri = createShortChapterBoundaryEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val view = waitForConditionResult("expected stable chapter one for boundary failure capture") {
                scenario.withActivity { activity ->
                    val candidate = activity.findEpubFlowView() ?: return@withActivity null
                    val text = candidate.reflectTextView().text.toString()
                    if (
                        candidate.reflectInt("pageCount") != 1 ||
                        candidate.width <= 0 ||
                        candidate.height <= 0 ||
                        candidate.flowContentAlpha() < 1f ||
                        candidate.reflectPrivateAny("conversionSnapshotDrawable") != null ||
                        !text.contains("Chapter one end.")
                    ) {
                        return@withActivity null
                    }
                    candidate
                }
            }
            waitForConditionResult("expected reader activity window focus before boundary failure capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }
            val originalBackground = scenario.withActivity { view.background }
            val stable = captureUntilVisuallyStable(scenario, view)
            var rejected: Bitmap? = null
            var retryWaiting: Bitmap? = null
            var target: Bitmap? = null
            try {
                val failingBackground = FailNextDrawDrawable(originalBackground)
                awaitFirstCommittedFrameMatchingAfter(
                    scenario = scenario,
                    target = view,
                    frameReady = {
                        view.reflectTextView().text.toString().contains("Chapter one end.") &&
                            view.flowContentAlpha() >= 1f &&
                            view.reflectPrivateAny("conversionSnapshotDrawable") == null &&
                            view.reflectPrivateAny("pendingBoundaryPageTurn") == null &&
                            view.reflectPrivateAny("slideDrawable") == null
                    },
                    action = {
                        view.background = failingBackground
                        dispatchTap(view, PointF(view.width * 0.85f, view.height * 0.50f))
                        view.postInvalidateOnAnimation()
                    },
                )
                rejected = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("boundary-failure-stable.png", stable)
                writeWindowFrameEvidence("boundary-failure-rejected.png", checkNotNull(rejected))
                assertVisualDiffAtMost(
                    "a failed boundary snapshot must leave the committed Window on chapter one",
                    bitmapDiff(stable, checkNotNull(rejected)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
                scenario.withActivity {
                    assertTrue(view.reflectTextView().text.toString().contains("Chapter one end."))
                    assertEquals(1f, view.flowContentAlpha())
                    assertEquals(null, view.reflectPrivateAny("conversionSnapshotDrawable"))
                    assertEquals(null, view.reflectPrivateAny("pendingBoundaryPageTurn"))
                    assertEquals(null, view.reflectPrivateAny("slideDrawable"))
                    view.background = originalBackground
                }

                awaitFirstCommittedFrameMatchingAfter(
                    scenario = scenario,
                    target = view,
                    frameReady = {
                        view.reflectTextView().text.toString().contains("Chapter two start.") &&
                            view.flowContentAlpha() <= VISIBLE_CONTENT_ALPHA_EPSILON &&
                            view.reflectPrivateAny("conversionSnapshotDrawable") != null &&
                            view.reflectPrivateAny("pendingBoundaryPageTurn") != null
                    },
                    action = {
                        dispatchTap(view, PointF(view.width * 0.85f, view.height * 0.50f))
                        view.setPendingDecodesForTest(true)
                        view.postInvalidateOnAnimation()
                    },
                )
                retryWaiting = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("boundary-retry-waiting.png", checkNotNull(retryWaiting))
                assertVisualDiffAtMost(
                    "a healthy retry must keep the old committed Window while chapter two is hidden",
                    bitmapDiff(stable, checkNotNull(retryWaiting)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )

                scenario.withActivity {
                    view.setPendingDecodesForTest(false)
                    view.invokeNoArgCompat("tryRevealWhenStable")
                }
                waitForConditionResult("expected healthy boundary retry to settle on chapter two") {
                    scenario.withActivity {
                        val animatorRunning = (view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true
                        val settled =
                            view.reflectTextView().text.toString().contains("Chapter two start.") &&
                                view.flowContentAlpha() >= 1f &&
                                view.reflectPrivateAny("conversionSnapshotDrawable") == null &&
                                view.reflectPrivateAny("pendingBoundaryPageTurn") == null &&
                                view.reflectPrivateAny("slideDrawable") == null &&
                                !animatorRunning
                        if (settled) true else null
                    }
                }
                target = captureUntilVisuallyStable(scenario, view)
                writeWindowFrameEvidence("boundary-retry-target.png", checkNotNull(target))
                assertVisualDiffAtLeast(
                    "the healthy retry must eventually present a visually distinct chapter two Window",
                    bitmapDiff(stable, checkNotNull(target)),
                    minRgbMae = 0.02,
                    minBadPixelRatio = 0.0001,
                )
            } finally {
                scenario.withActivity {
                    view.background = originalBackground
                    view.setPendingDecodesForTest(false)
                }
                stable.recycle()
                rejected?.recycle()
                retryWaiting?.recycle()
                target?.recycle()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowNoneBoundaryCutHandsOffCommittedWindowAtomicallyRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.NONE)
        val title = "flow-boundary-none-window-${UUID.randomUUID().toString().take(8)}"
        val uri = createShortChapterBoundaryEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val view = waitForConditionResult("expected stable chapter one for NONE boundary capture") {
                scenario.withActivity { activity ->
                    val candidate = activity.findEpubFlowView() ?: return@withActivity null
                    val text = candidate.reflectTextView().text.toString()
                    if (
                        candidate.reflectInt("pageCount") != 1 ||
                        candidate.width <= 0 ||
                        candidate.height <= 0 ||
                        candidate.flowContentAlpha() < 1f ||
                        candidate.reflectPrivateAny("conversionSnapshotDrawable") != null ||
                        !text.contains("Chapter one end.")
                    ) {
                        return@withActivity null
                    }
                    candidate
                }
            }
            waitForConditionResult("expected reader activity window focus before NONE boundary capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }
            val stable = captureUntilVisuallyStable(scenario, view)
            var waiting: Bitmap? = null
            var firstTarget: Bitmap? = null
            var settledTarget: Bitmap? = null
            try {
                awaitFirstCommittedFrameMatchingAfter(
                    scenario = scenario,
                    target = view,
                    frameReady = {
                        view.reflectTextView().text.toString().contains("Chapter two start.") &&
                            view.flowContentAlpha() <= VISIBLE_CONTENT_ALPHA_EPSILON &&
                            view.reflectPrivateAny("conversionSnapshotDrawable") != null &&
                            view.reflectPrivateAny("pendingBoundaryPageTurn") != null
                    },
                    action = {
                        dispatchTap(view, PointF(view.width * 0.85f, view.height * 0.50f))
                        view.setPendingDecodesForTest(true)
                        view.postInvalidateOnAnimation()
                    },
                )
                waiting = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("boundary-none-stable.png", stable)
                writeWindowFrameEvidence("boundary-none-waiting.png", checkNotNull(waiting))
                assertVisualDiffAtMost(
                    "NONE must keep chapter one committed while the hidden target settles",
                    bitmapDiff(stable, checkNotNull(waiting)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )

                awaitFirstCommittedFrameMatchingAfter(
                    scenario = scenario,
                    target = view,
                    frameReady = {
                        view.reflectTextView().text.toString().contains("Chapter two start.") &&
                            view.flowContentAlpha() >= 1f &&
                            view.reflectPrivateAny("conversionSnapshotDrawable") == null &&
                            view.reflectPrivateAny("pendingBoundaryPageTurn") == null &&
                            view.reflectPrivateAny("slideDrawable") == null &&
                            view.reflectPrivateAny("curlDrawable") == null
                    },
                    action = {
                        view.setPendingDecodesForTest(false)
                        view.invokeNoArgCompat("tryRevealWhenStable")
                        view.postInvalidateOnAnimation()
                    },
                )
                firstTarget = captureWindowRegion(scenario, view)
                awaitCommittedFrame(scenario, view)
                settledTarget = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("boundary-none-first-target.png", checkNotNull(firstTarget))
                writeWindowFrameEvidence("boundary-none-settled-target.png", checkNotNull(settledTarget))
                assertVisualDiffAtMost(
                    "the first target-owned NONE Window must already equal the settled target",
                    bitmapDiff(checkNotNull(firstTarget), checkNotNull(settledTarget)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
                assertVisualDiffAtLeast(
                    "the atomic NONE cut must visibly reach chapter two",
                    bitmapDiff(stable, checkNotNull(settledTarget)),
                    minRgbMae = 0.02,
                    minBadPixelRatio = 0.0001,
                )
                scenario.withActivity {
                    assertTrue(view.reflectTextView().text.toString().contains("Chapter two start."))
                    assertEquals(1f, view.flowContentAlpha())
                    assertEquals(null, view.reflectPrivateAny("flipAnimator"))
                    assertEquals(null, view.reflectPrivateAny("slideDrawable"))
                    assertEquals(null, view.reflectPrivateAny("curlDrawable"))
                }
            } finally {
                scenario.withActivity { view.setPendingDecodesForTest(false) }
                stable.recycle()
                waiting?.recycle()
                firstTarget?.recycle()
                settledTarget?.recycle()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowConversionFadeImmediateFirstTurnKeepsCommittedWindowContinuousRuntime() = runBlocking {
        settings.setReadingMode(ReaderReadingMode.SCROLL)
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-conversion-first-turn-${UUID.randomUUID().toString().take(8)}"
        val uri = createComplexEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))

            val view = waitForConditionResult("expected stable complex SCROLL view for conversion first-turn capture") {
                scenario.withActivity { activity ->
                    val candidate = activity.findEpubFlowView() ?: return@withActivity null
                    val layout = candidate.reflectTextView().layout ?: return@withActivity null
                    if (
                        candidate.width <= 0 ||
                        candidate.height <= 0 ||
                        layout.height <= candidate.height * 4 ||
                        candidate.flowContentAlpha() < 1f ||
                        candidate.reflectPrivateAny("modeValue")?.toString() != "SCROLL" ||
                        candidate.reflectPrivateAny("conversionSnapshotDrawable") != null
                    ) {
                        return@withActivity null
                    }
                    candidate
                }
            }
            waitForConditionResult("expected reader activity window focus before conversion fade capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }

            var opaqueCover: Bitmap? = null
            var fadedWindow: Bitmap? = null
            var firstTurnWindow: Bitmap? = null
            try {
                scenario.withActivity {
                    val layoutHeight = checkNotNull(view.reflectTextView().layout).height
                    val targetScrollY = (layoutHeight * 45 / 100)
                        .coerceAtLeast(1)
                        .coerceAtMost(layoutHeight - view.height)
                    view.scrollTo(0, targetScrollY)
                }
                val stableScrollWindow = captureUntilVisuallyStable(scenario, view)
                stableScrollWindow.recycle()

                scenario.withActivity {
                    val layoutOffset = view.reflectInt("topLayoutOffset")
                    view.invokeSetModeAnchoredPaged(layoutOffset)
                    val cover = checkNotNull(view.reflectPrivateAny("conversionSnapshotDrawable")) {
                        "expected SCROLL->PAGED cover before pinning its fade"
                    }
                    opaqueCover = cover.reflectPrivateBitmap("bitmap").copy(Bitmap.Config.ARGB_8888, false)
                    val fade = checkNotNull(view.reflectPrivateAny("conversionFadeAnimator") as? ValueAnimator) {
                        "expected the conversion cover to be actively fading"
                    }
                    fade.setCurrentFraction(0.5f)
                    fade.pause()
                    val coverAlpha = cover.reflectPrivateInt("alphaValue")
                    check(coverAlpha in 96..160) {
                        "expected a materially partial conversion cover, alpha=$coverAlpha"
                    }
                    check(view.flowContentAlpha() >= 1f) {
                        "the stable PAGED frame must be opaque beneath the partial cover"
                    }
                }
                awaitCommittedFrame(scenario, view)
                fadedWindow = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("conversion-fade-opaque-cover.png", checkNotNull(opaqueCover))
                writeWindowFrameEvidence("conversion-fade-last-visible.png", checkNotNull(fadedWindow))

                val opaqueDiscontinuity = bitmapDiff(checkNotNull(fadedWindow), checkNotNull(opaqueCover))
                assertVisualDiffAtLeast(
                    "fixture must distinguish the last partially faded Window from the old opaque-cover front shot",
                    opaqueDiscontinuity,
                    minRgbMae = 0.75,
                    minBadPixelRatio = 0.01,
                )

                scenario.withActivity {
                    val downTime = SystemClock.uptimeMillis()
                    val point = PointF(view.width * 0.85f, view.height * 0.50f)
                    dispatchTouchEvent(view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point))
                    dispatchTouchEvent(
                        view,
                        motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_UP, point),
                    )
                    val turn = checkNotNull(view.reflectPrivateAny("flipAnimator") as? ValueAnimator) {
                        "the immediate first tap during conversion must start the SLIDE animator"
                    }
                    turn.setCurrentFraction(0f)
                    turn.pause()
                }
                awaitCommittedFrame(scenario, view)
                firstTurnWindow = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("conversion-fade-first-turn.png", checkNotNull(firstTurnWindow))

                assertVisualDiffAtMost(
                    "the immediate first-turn front must equal the last committed partially faded Window",
                    bitmapDiff(checkNotNull(fadedWindow), checkNotNull(firstTurnWindow)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
            } finally {
                opaqueCover?.recycle()
                fadedWindow?.recycle()
                firstTurnWindow?.recycle()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowPartialConversionFlattenFailureKeepsCommittedWindowAndRevealRuntime() = runBlocking {
        settings.setReadingMode(ReaderReadingMode.SCROLL)
        settings.setPageFlipStyle(PageFlipStyle.SLIDE)
        val title = "flow-conversion-flatten-failure-${UUID.randomUUID().toString().take(8)}"
        val uri = createComplexEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val view = waitForConditionResult("expected stable complex SCROLL view for flatten failure capture") {
                scenario.withActivity { activity ->
                    val candidate = activity.findEpubFlowView() ?: return@withActivity null
                    val layout = candidate.reflectTextView().layout ?: return@withActivity null
                    if (
                        candidate.width <= 0 ||
                        candidate.height <= 0 ||
                        layout.height <= candidate.height * 4 ||
                        candidate.flowContentAlpha() < 1f ||
                        candidate.reflectPrivateAny("modeValue")?.toString() != "SCROLL" ||
                        candidate.reflectPrivateAny("conversionSnapshotDrawable") != null
                    ) {
                        return@withActivity null
                    }
                    candidate
                }
            }
            waitForConditionResult("expected reader activity window focus before flatten failure capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }

            var opaqueCover: Bitmap? = null
            var partialWindow: Bitmap? = null
            var rejectedWindow: Bitmap? = null
            var revealedWindow: Bitmap? = null
            var coverOwner: Any? = null
            var coverBitmap: Bitmap? = null
            var fadeOwner: ValueAnimator? = null
            var failedDestination: Bitmap? = null
            var originalFlattener: Any? = null
            var flattenerInstalled = false
            var startPage = -1
            var startTop = -1
            var partialAlpha = -1
            try {
                scenario.withActivity {
                    val layoutHeight = checkNotNull(view.reflectTextView().layout).height
                    val targetScrollY = (layoutHeight * 45 / 100)
                        .coerceAtLeast(1)
                        .coerceAtMost(layoutHeight - view.height)
                    view.scrollTo(0, targetScrollY)
                }
                val stableScrollWindow = captureUntilVisuallyStable(scenario, view)
                stableScrollWindow.recycle()

                scenario.withActivity {
                    val layoutOffset = view.reflectInt("topLayoutOffset")
                    view.invokeSetModeAnchoredPaged(layoutOffset)
                    coverOwner = checkNotNull(view.reflectPrivateAny("conversionSnapshotDrawable")) {
                        "expected SCROLL->PAGED cover before forcing flatten failure"
                    }
                    coverBitmap = checkNotNull(coverOwner).reflectPrivateBitmap("bitmap")
                    opaqueCover = checkNotNull(coverBitmap).copy(Bitmap.Config.ARGB_8888, false)
                    fadeOwner = checkNotNull(view.reflectPrivateAny("conversionFadeAnimator") as? ValueAnimator) {
                        "expected the conversion cover to be actively fading"
                    }
                    checkNotNull(fadeOwner).setCurrentFraction(0.5f)
                    checkNotNull(fadeOwner).pause()
                    partialAlpha = checkNotNull(coverOwner).reflectPrivateInt("alphaValue")
                    check(partialAlpha in 96..160) {
                        "expected a materially partial conversion cover, alpha=$partialAlpha"
                    }
                    startPage = view.reflectInt("currentPageIndex")
                    startTop = view.scrollY
                }
                awaitCommittedFrame(scenario, view)
                partialWindow = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("conversion-flatten-partial.png", checkNotNull(partialWindow))
                assertVisualDiffAtLeast(
                    "flatten failure fixture must distinguish the partial Window from its opaque cover",
                    bitmapDiff(checkNotNull(partialWindow), checkNotNull(opaqueCover)),
                    minRgbMae = 0.75,
                    minBadPixelRatio = 0.01,
                )

                scenario.withActivity {
                    val failFlatten: (Any, Bitmap) -> Boolean = { _, destination ->
                        failedDestination = destination
                        false
                    }
                    originalFlattener = view.swapPrivateField("conversionSnapshotFlattener", failFlatten)
                    flattenerInstalled = true
                }
                awaitFirstCommittedFrameMatchingAfter(
                    scenario = scenario,
                    target = view,
                    frameReady = {
                        view.reflectInt("currentPageIndex") == startPage &&
                            view.scrollY == startTop &&
                            view.reflectPrivateAny("conversionSnapshotDrawable") === coverOwner &&
                            view.reflectPrivateAny("conversionFadeAnimator") === fadeOwner &&
                            checkNotNull(coverOwner).reflectPrivateInt("alphaValue") == partialAlpha &&
                            view.reflectPrivateAny("slideDrawable") == null &&
                            view.reflectPrivateAny("curlDrawable") == null &&
                            view.reflectPrivateAny("flipAnimator") == null
                    },
                    action = {
                        dispatchTap(view, PointF(view.width * 0.85f, view.height * 0.50f))
                        view.postInvalidateOnAnimation()
                    },
                )
                rejectedWindow = captureWindowRegion(scenario, view)
                writeWindowFrameEvidence("conversion-flatten-rejected.png", checkNotNull(rejectedWindow))
                assertVisualDiffAtMost(
                    "a failed partial flatten must preserve the exact committed Window",
                    bitmapDiff(checkNotNull(partialWindow), checkNotNull(rejectedWindow)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
                scenario.withActivity {
                    assertEquals(startPage, view.reflectInt("currentPageIndex"))
                    assertEquals(startTop, view.scrollY)
                    assertTrue(view.reflectPrivateAny("conversionSnapshotDrawable") === coverOwner)
                    assertTrue(view.reflectPrivateAny("conversionFadeAnimator") === fadeOwner)
                    assertEquals(partialAlpha, checkNotNull(coverOwner).reflectPrivateInt("alphaValue"))
                    assertTrue("the rejected composition bitmap must be recycled", checkNotNull(failedDestination).isRecycled)
                    assertFalse("the visible cover must remain alive after rejection", checkNotNull(coverBitmap).isRecycled)
                    assertEquals(null, view.reflectPrivateAny("slideDrawable"))
                    assertEquals(null, view.reflectPrivateAny("curlDrawable"))
                    assertEquals(null, view.reflectPrivateAny("flipAnimator"))
                    view.swapPrivateField("conversionSnapshotFlattener", originalFlattener)
                    flattenerInstalled = false
                    checkNotNull(fadeOwner).resume()
                }
                waitForConditionResult("expected rejected conversion turn to finish its original reveal") {
                    scenario.withActivity {
                        val revealed =
                            view.reflectInt("currentPageIndex") == startPage &&
                                view.scrollY == startTop &&
                                view.flowContentAlpha() >= 1f &&
                                view.reflectPrivateAny("conversionSnapshotDrawable") == null &&
                                view.reflectPrivateAny("conversionFadeAnimator") == null
                        if (revealed) true else null
                    }
                }
                revealedWindow = captureUntilVisuallyStable(scenario, view)
                writeWindowFrameEvidence("conversion-flatten-revealed.png", checkNotNull(revealedWindow))
                assertVisualDiffAtLeast(
                    "the original reveal must continue from the rejected partial Window to stable live content",
                    bitmapDiff(checkNotNull(partialWindow), checkNotNull(revealedWindow)),
                    minRgbMae = 0.02,
                    minBadPixelRatio = 0.0001,
                )
            } finally {
                scenario.withActivity {
                    if (flattenerInstalled) {
                        view.swapPrivateField("conversionSnapshotFlattener", originalFlattener)
                    }
                    fadeOwner?.takeIf { it.isPaused }?.resume()
                }
                opaqueCover?.recycle()
                partialWindow?.recycle()
                rejectedWindow?.recycle()
                revealedWindow?.recycle()
            }
        }
    }


    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowSimulationSoftwareCurlWindowFramesTrackFirstMoveAndCancelRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-software-curl-window-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val start = waitForConditionResult("expected multi-page flow view for software curl Window capture") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val nextTop = view.reflectNullableInt("pageTopPxAt", 1)
                    if (
                        view.reflectInt("pageCount") <= 4 ||
                        nextTop == null ||
                        view.width <= 0 ||
                        view.height <= 0 ||
                        view.flowContentAlpha() < 1f
                    ) {
                        return@withActivity null
                    }
                    FlowSimulationTurnStart(view = view, nextPageTop = nextTop)
                }
            }

            scenario.withActivity {
                start.view.scrollTo(0, 0)
                check(start.view.reflectBoolean("goToAdjacentPage", 1))
            }
            waitForFlowPageTurnSettled(scenario, start.view, 1, start.nextPageTop, "expected A2 warm-up forward settle")
            scenario.withActivity { check(start.view.reflectBoolean("goToAdjacentPage", -1)) }
            waitForFlowPageTurnSettled(scenario, start.view, 0, 0, "expected A2 warm-up backward settle")
            waitForConditionResult("expected reader activity window focus before software curl capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }
            lateinit var flowGestureDetector: GestureDetector
            lateinit var flowTextView: TextView
            var hostLongPressEnabled = false
            var textLongClickable = false
            var textSelectable = false
            scenario.withActivity {
                flowGestureDetector = start.view.reflectPrivateAny("gestureDetector") as GestureDetector
                flowTextView = checkNotNull(start.view.findDescendant { it is TextView } as? TextView)
                hostLongPressEnabled = flowGestureDetector.isLongpressEnabled
                textLongClickable = flowTextView.isLongClickable
                textSelectable = flowTextView.isTextSelectable
                flowGestureDetector.setIsLongpressEnabled(false)
                flowTextView.isLongClickable = false
                flowTextView.setTextIsSelectable(false)
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            awaitCommittedFrame(scenario, start.view)
            val stableA = captureWindowRegion(scenario, start.view)
            awaitCommittedFrame(scenario, start.view)
            val stable = captureWindowRegion(scenario, start.view)
            val downTime = SystemClock.uptimeMillis()
            val down = PointF(start.view.width * 0.85f, start.view.height * 0.10f)
            val move = PointF(start.view.width * 0.30f, down.y)
            var downFrame: Bitmap? = null
            var moveFrame: Bitmap? = null
            var settledFrame: Bitmap? = null
            var gestureActive = false
            try {
                assertVisualDiffAtMost(
                    "software curl Window test must begin from a visually settled frame",
                    bitmapDiff(stableA, stable),
                    maxRgbMae = 0.25,
                    maxBadPixelRatio = 0.001,
                )
                scenario.withActivity {
                    dispatchTouchEvent(start.view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
                }
                gestureActive = true
                awaitCommittedFrame(scenario, start.view)
                downFrame = captureWindowRegion(scenario, start.view)
                assertVisualDiffAtMost(
                    "software curl ACTION_DOWN must not change the composed reader Window",
                    bitmapDiff(stable, checkNotNull(downFrame)),
                    maxRgbMae = 0.35,
                    maxBadPixelRatio = 0.002,
                )

                scenario.withActivity {
                    dispatchTouchEvent(
                        start.view,
                        motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_MOVE, move),
                    )
                }
                scenario.withActivity {
                    val curl = checkNotNull(start.view.reflectPrivateAny("curlDrawable")) {
                        "SIMULATION finger drag must create the software PageCurlDrawable; " +
                            "classified=${start.view.reflectPrivateBoolean("classified")} " +
                            "stealing=${start.view.reflectPrivateBoolean("stealing")} " +
                            "selection=${start.view.reflectPrivateBoolean("inSelectionMode")} " +
                            "down=(${start.view.reflectPrivateAny("downX")},${start.view.reflectPrivateAny("downY")}) " +
                            "flipped=${start.view.reflectPrivateBoolean("flipped")} " +
                            "interactive=${start.view.reflectPrivateAny("interactiveTurnState")} " +
                            "animator=${start.view.reflectPrivateAny("flipAnimator")} " +
                            "page=${start.view.reflectInt("currentPageIndex")} scrollY=${start.view.scrollY}"
                    }
                    check(curl.javaClass.simpleName == "PageCurlDrawable") {
                        "expected PageCurlDrawable, actual=${curl.javaClass.name}"
                    }
                    check(start.view.reflectPrivateAny("slideDrawable") == null) {
                        "SIMULATION software curl must not fall back to a slide drawable"
                    }
                    check(start.view.rootView.findDescendant { it.javaClass.name == LEGACY_EPUB_CURL_OVERLAY_CLASS_NAME } == null) {
                        "A2 finger drag must not create the removed GL overlay child"
                    }
                }
                moveFrame = captureUntilMateriallyDifferent(
                    scenario = scenario,
                    target = start.view,
                    baseline = stable,
                    minRgbMae = 2.0,
                    minBadPixelRatio = 0.01,
                )
                val moveDiff = bitmapDiff(stable, checkNotNull(moveFrame))
                writeWindowFrameEvidence("software-curl-stable.png", stable)
                writeWindowFrameEvidence("software-curl-down.png", checkNotNull(downFrame))
                writeWindowFrameEvidence("software-curl-move.png", checkNotNull(moveFrame))
                val moveLuma = sampledMeanLuma(checkNotNull(moveFrame))
                assertTrue(
                    "software curl MOVE frame must remain visibly composed; meanLuma=$moveLuma",
                    moveLuma > 24.0,
                )

                scenario.withActivity {
                    dispatchTouchEvent(
                        start.view,
                        motionEvent(
                            downTime,
                            downTime + EVENT_STEP_MS * 2,
                            MotionEvent.ACTION_CANCEL,
                            move,
                        ),
                    )
                }
                gestureActive = false
                waitForConditionResult("expected cancelled software curl to settle on the outgoing page") {
                    scenario.withActivity {
                        val animatorRunning =
                            (start.view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true
                        val settled =
                            start.view.reflectInt("currentPageIndex") == 0 &&
                                start.view.scrollY == 0 &&
                                start.view.reflectPrivateAny("curlDrawable") == null &&
                                start.view.reflectPrivateAny("slideDrawable") == null &&
                                !animatorRunning
                        if (settled) true else null
                    }
                }
                settledFrame = captureUntilVisuallyStable(scenario, start.view)
                writeWindowFrameEvidence("software-curl-settled.png", checkNotNull(settledFrame))
                assertVisualDiffAtMost(
                    "software curl ACTION_CANCEL must restore the exact pre-touch committed Window",
                    bitmapDiff(stable, checkNotNull(settledFrame)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
                assertEquals(0, scenario.withActivity { start.view.reflectInt("currentPageIndex") })
                assertTrue("test precondition: software curl MOVE must materially differ from stable, diff=$moveDiff", moveDiff.rgbMae > 2.0)
            } finally {
                if (gestureActive) {
                    runCatching {
                        scenario.withActivity {
                            dispatchTouchEvent(
                                start.view,
                                motionEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, move),
                            )
                        }
                    }
                }
                scenario.withActivity {
                    flowGestureDetector.setIsLongpressEnabled(hostLongPressEnabled)
                    flowTextView.setTextIsSelectable(textSelectable)
                    flowTextView.isLongClickable = textLongClickable
                }
                stableA.recycle()
                stable.recycle()
                downFrame?.recycle()
                moveFrame?.recycle()
                settledFrame?.recycle()
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
    fun epubFlowMoonReaderVerticalSideSwipeUsesA2PageCurlRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-vertical-software-curl-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val start = waitForConditionResult("expected flow view for vertical software curl") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val nextTop = view.reflectNullableInt("pageTopPxAt", 1)
                    if (
                        view.reflectInt("pageCount") <= 4 ||
                        nextTop == null ||
                        view.width <= 0 ||
                        view.height <= 0 ||
                        view.flowContentAlpha() < 1f
                    ) {
                        return@withActivity null
                    }
                    FlowSimulationTurnStart(view = view, nextPageTop = nextTop)
                }
            }

            val drawableName = scenario.withActivity { activity ->
                start.view.scrollTo(0, 0)
                val density = start.view.resources.displayMetrics.density
                val down = PointF(start.view.width * 0.85f, start.view.height * 0.50f)
                val up = PointF(down.x + 10f * density, down.y - 60f * density)
                val downTime = SystemClock.uptimeMillis()
                dispatchTouchEvent(start.view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
                dispatchTouchEvent(
                    start.view,
                    motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_MOVE, up),
                )
                dispatchTouchEvent(
                    start.view,
                    motionEvent(downTime, downTime + EVENT_STEP_MS * 2, MotionEvent.ACTION_UP, up),
                )
                check(activity.findLegacyEpubCurlOverlay() == null) {
                    "A2 vertical swipe must not create the removed GL overlay child"
                }
                start.view.reflectPrivateAny("curlDrawable")?.javaClass?.simpleName
            }

            assertEquals(
                "clear side-column vertical swipe must use the software PageCurlDrawable",
                "PageCurlDrawable",
                drawableName,
            )
            waitForFlowPageTurnSettled(
                scenario = scenario,
                view = start.view,
                pageIndex = 1,
                scrollY = start.nextPageTop,
                message = "vertical software curl should settle on the next canonical page",
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
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowColdHandoffSplitsShotsAcrossRealFramesAndResumesLatestMoveRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-cold-handoff-frames-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val baseline = waitForConditionResult("expected stable flow view for cold handoff frame test") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    val pageOneTop = view.reflectNullableInt("pageTopPxAt", 1)
                    val pageTwoTop = view.reflectNullableInt("pageTopPxAt", 2)
                    if (
                        pageCount <= 4 || pageOneTop == null || pageTwoTop == null ||
                        view.width <= 0 || view.height <= 0 || view.flowContentAlpha() < 1f ||
                        view.reflectPrivateAny("conversionSnapshotDrawable") != null ||
                        view.reflectPrivateAny("flipStyle")?.toString() != PageFlipStyle.SIMULATION.name
                    ) return@withActivity null
                    FlowAnchorBaseline(view, pageCount, pageOneTop, pageTwoTop)
                }
            }

            data class FrameState(
                val motionState: String,
                val hasCurl: Boolean,
                val progress: Float?,
                val reportedOffset: Any?,
            )

            val states = mutableListOf<FrameState>()
            val callbackFailure = arrayOfNulls<Throwable>(1)
            val ready = CountDownLatch(1)
            val downTime = SystemClock.uptimeMillis()
            val down = PointF(baseline.view.width * 0.85f, baseline.view.height * 0.10f)
            val firstMove = PointF(baseline.view.width * 0.55f, down.y)
            val latestMove = PointF(baseline.view.width * 0.10f, down.y)
            val expectedProgress = (down.x - latestMove.x) / baseline.view.width.toFloat()
            var previousPrecacheEnabled: Any? = null
            var gestureActive = false

            try {
                val immediate = scenario.withActivity {
                    val view = baseline.view
                    val freeRest = view.nonLineTopBetween(
                        baseline.pageOneTop,
                        baseline.pageTwoTop,
                        fraction = 0.40f,
                    )
                    view.scrollTo(0, freeRest)
                    previousPrecacheEnabled = view.swapPrivateField("pageTexturePrecacheEnabled", false)
                    view.invokeNoArgCompat("recycleCachedTextures")
                    dispatchTouchEvent(view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
                    gestureActive = true
                    dispatchTouchEvent(
                        view,
                        motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_MOVE, firstMove),
                    )
                    dispatchTouchEvent(
                        view,
                        motionEvent(downTime, downTime + EVENT_STEP_MS * 2, MotionEvent.ACTION_MOVE, latestMove),
                    )
                    val initial = FrameState(
                        motionState = view.reflectPrivateAny("interactiveTurnState").toString(),
                        hasCurl = view.reflectPrivateAny("curlDrawable") != null,
                        progress = null,
                        reportedOffset = view.reflectPrivateAny("lastReportedTopOffset"),
                    )
                    val initialReportedOffset = initial.reportedOffset
                    val choreographer = Choreographer.getInstance()
                    choreographer.postFrameCallback {
                        try {
                            states += FrameState(
                                motionState = view.reflectPrivateAny("interactiveTurnState").toString(),
                                hasCurl = view.reflectPrivateAny("curlDrawable") != null,
                                progress = null,
                                reportedOffset = view.reflectPrivateAny("lastReportedTopOffset"),
                            )
                            choreographer.postFrameCallback {
                                try {
                                    val curl = view.reflectPrivateAny("curlDrawable")
                                    val progress = curl?.javaClass?.getDeclaredField("progress")
                                        ?.apply { isAccessible = true }
                                        ?.getFloat(curl)
                                    states += FrameState(
                                        motionState = view.reflectPrivateAny("interactiveTurnState").toString(),
                                        hasCurl = curl != null,
                                        progress = progress,
                                        reportedOffset = view.reflectPrivateAny("lastReportedTopOffset"),
                                    )
                                    check(states.all { it.reportedOffset == initialReportedOffset }) {
                                        "cold handoff must remain locator-silent across preparation frames: $states"
                                    }
                                } catch (failure: Throwable) {
                                    callbackFailure[0] = failure
                                } finally {
                                    ready.countDown()
                                }
                            }
                        } catch (failure: Throwable) {
                            callbackFailure[0] = failure
                            ready.countDown()
                        }
                    }
                    initial
                }

                assertEquals("LOCAL_SHOTS_WAITING", immediate.motionState)
                assertFalse("threshold MOVE must not synchronously install curl", immediate.hasCurl)
                assertTrue("two real Choreographer frames must complete", ready.await(5L, TimeUnit.SECONDS))
                callbackFailure[0]?.let { throw AssertionError("cold handoff frame callback failed", it) }
                assertEquals("expected target frame then visible-front frame", 2, states.size)
                assertEquals("LOCAL_SHOTS_WAITING", states[0].motionState)
                assertFalse("target-only frame must not install curl", states[0].hasCurl)
                assertEquals("SOFTWARE", states[1].motionState)
                assertTrue("visible-front frame must install curl", states[1].hasCurl)
                assertEquals(expectedProgress, checkNotNull(states[1].progress), 0.02f)
            } finally {
                if (gestureActive) {
                    scenario.withActivity {
                        dispatchTouchEvent(
                            baseline.view,
                            motionEvent(
                                downTime,
                                SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_CANCEL,
                                latestMove,
                            ),
                        )
                    }
                    gestureActive = false
                }
                waitForConditionResult("expected cold handoff cancel to retire overlay") {
                    scenario.withActivity {
                        val animatorRunning =
                            (baseline.view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true
                        if (
                            baseline.view.reflectPrivateAny("curlDrawable") == null &&
                            baseline.view.reflectPrivateAny("slideDrawable") == null &&
                            !animatorRunning
                        ) true else null
                    }
                }
                scenario.withActivity {
                    previousPrecacheEnabled?.let {
                        baseline.view.swapPrivateField("pageTexturePrecacheEnabled", it)
                    }
                }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun epubFlowSimulationColdFirstDragStartsA2CurlWithoutLegacyGlChildRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-software-curl-first-drag-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val view = waitForConditionResult("expected flow view for first software curl drag") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    if (
                        pageCount <= 4 ||
                        view.width <= 0 ||
                        view.height <= 0 ||
                        view.flowContentAlpha() < 1f ||
                        view.reflectPrivateAny("conversionSnapshotDrawable") != null ||
                        view.reflectPrivateAny("flipStyle")?.toString() != PageFlipStyle.SIMULATION.name
                    ) {
                        return@withActivity null
                    }
                    view
                }
            }
            waitForConditionResult("expected reader activity window focus before cold software curl capture") {
                scenario.withActivity { activity -> if (activity.hasWindowFocus()) true else null }
            }
            scenario.withActivity { view.scrollTo(0, 0) }
            scenario.withActivity { activity ->
                check(activity.findLegacyEpubCurlOverlay() == null) {
                    "cold-first A2 precondition requires no legacy GL overlay child"
                }
            }
            lateinit var flowGestureDetector: GestureDetector
            lateinit var flowTextView: TextView
            var hostLongPressEnabled = false
            var textLongClickable = false
            var textSelectable = false
            scenario.withActivity {
                flowGestureDetector = view.reflectPrivateAny("gestureDetector") as GestureDetector
                flowTextView = checkNotNull(view.findDescendant { it is TextView } as? TextView)
                hostLongPressEnabled = flowGestureDetector.isLongpressEnabled
                textLongClickable = flowTextView.isLongClickable
                textSelectable = flowTextView.isTextSelectable
                flowGestureDetector.setIsLongpressEnabled(false)
                flowTextView.isLongClickable = false
                flowTextView.setTextIsSelectable(false)
            }
            instrumentation.waitForIdleSync()
            device.waitForIdle()

            awaitCommittedFrame(scenario, view)
            val stableA = captureWindowRegion(scenario, view)
            awaitCommittedFrame(scenario, view)
            val stable = captureWindowRegion(scenario, view)
            val downTime = SystemClock.uptimeMillis()
            val down = PointF(view.width * 0.85f, view.height * 0.10f)
            val move = PointF(view.width * 0.15f, view.height * 0.10f)
            var downFrame: Bitmap? = null
            var moveFrame: Bitmap? = null
            var settledFrame: Bitmap? = null
            var gestureActive = false
            try {
                assertVisualDiffAtMost(
                    "cold software curl test must begin from a visually settled Window",
                    bitmapDiff(stableA, stable),
                    maxRgbMae = 0.25,
                    maxBadPixelRatio = 0.001,
                )
                scenario.withActivity {
                    dispatchTouchEvent(view, motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down))
                }
                gestureActive = true
                awaitCommittedFrame(scenario, view)
                downFrame = captureWindowRegion(scenario, view)
                assertVisualDiffAtMost(
                    "cold software curl ACTION_DOWN must not change the composed Window",
                    bitmapDiff(stable, checkNotNull(downFrame)),
                    maxRgbMae = 0.35,
                    maxBadPixelRatio = 0.002,
                )

                scenario.withActivity {
                    dispatchTouchEvent(
                        view,
                        motionEvent(downTime, downTime + EVENT_STEP_MS, MotionEvent.ACTION_MOVE, move),
                    )
                }
                scenario.withActivity { activity ->
                    val curl = checkNotNull(view.reflectPrivateAny("curlDrawable")) {
                        "the first SIMULATION drag must create the software PageCurlDrawable"
                    }
                    check(curl.javaClass.simpleName == "PageCurlDrawable") {
                        "expected PageCurlDrawable, actual=${curl.javaClass.name}"
                    }
                    check(view.reflectPrivateAny("slideDrawable") == null)
                    check(activity.findLegacyEpubCurlOverlay() == null) {
                        "the first finger drag must remain entirely in the local A2 renderer"
                    }
                }
                moveFrame = captureUntilMateriallyDifferent(
                    scenario = scenario,
                    target = view,
                    baseline = stable,
                    minRgbMae = 2.0,
                    minBadPixelRatio = 0.01,
                )

                scenario.withActivity {
                    dispatchTouchEvent(
                        view,
                        motionEvent(downTime, downTime + EVENT_STEP_MS * 2, MotionEvent.ACTION_CANCEL, move),
                    )
                }
                gestureActive = false
                waitForConditionResult("expected first software curl CANCEL to restore the outgoing page") {
                    scenario.withActivity {
                        val animatorRunning =
                            (view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true
                        val settled =
                            view.reflectInt("currentPageIndex") == 0 &&
                                view.scrollY == 0 &&
                                view.reflectPrivateAny("curlDrawable") == null &&
                                view.reflectPrivateAny("slideDrawable") == null &&
                                !animatorRunning
                        if (settled) true else null
                    }
                }
                scenario.withActivity { activity ->
                    check(activity.findLegacyEpubCurlOverlay() == null) {
                        "a completed cold-first gesture must not leave a legacy GL overlay child"
                    }
                }
                settledFrame = captureUntilVisuallyStable(scenario, view)
                assertVisualDiffAtMost(
                    "the first software curl ACTION_CANCEL must restore the exact pre-touch committed Window",
                    bitmapDiff(stable, checkNotNull(settledFrame)),
                    maxRgbMae = 0.75,
                    maxBadPixelRatio = 0.01,
                )
                writeWindowFrameEvidence("software-curl-cold-stable.png", stable)
                writeWindowFrameEvidence("software-curl-cold-down.png", checkNotNull(downFrame))
                writeWindowFrameEvidence("software-curl-cold-move.png", checkNotNull(moveFrame))
                writeWindowFrameEvidence("software-curl-cold-settled.png", checkNotNull(settledFrame))
            } finally {
                if (gestureActive) {
                    runCatching {
                        scenario.withActivity {
                            dispatchTouchEvent(
                                view,
                                motionEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, move),
                            )
                        }
                    }
                }
                scenario.withActivity {
                    flowGestureDetector.setIsLongpressEnabled(hostLongPressEnabled)
                    flowTextView.setTextIsSelectable(textSelectable)
                    flowTextView.isLongClickable = textLongClickable
                }
                stableA.recycle()
                stable.recycle()
                downFrame?.recycle()
                moveFrame?.recycle()
                settledFrame?.recycle()
            }
        }
    }

    @Test
    fun epubFlowSimulationSoftwareCurlDirectionFollowsDragNotDownHalfRuntime() = runBlocking {
        settings.setPageFlipStyle(PageFlipStyle.SIMULATION)
        val title = "flow-software-curl-direction-${UUID.randomUUID().toString().take(8)}"
        val uri = createEpubUri("$title.epub")

        ActivityScenario.launch<MainActivity>(readerIntent(uri)).use { scenario ->
            dismissBlockingDialogs()
            waitForObject(By.descStartsWith("阅读内容"))
            val start = waitForConditionResult("expected flow view for software curl direction test") {
                scenario.withActivity { activity ->
                    val view = activity.findEpubFlowView() ?: return@withActivity null
                    val pageCount = view.reflectInt("pageCount")
                    val pageOneTop = view.reflectNullableInt("pageTopPxAt", 1)
                    if (pageCount <= 4 || pageOneTop == null || view.width <= 0 || view.height <= 0) {
                        return@withActivity null
                    }
                    FlowSimulationTurnStart(view = view, nextPageTop = pageOneTop)
                }
            }

            scenario.withActivity {
                start.view.scrollTo(0, 0)
                start.view.reflectBoolean("goToAdjacentPage", 1)
            }
            waitForFlowPageTurnSettled(scenario, start.view, 1, start.nextPageTop, "expected discrete A2 warm-up forward settle")
            scenario.withActivity { start.view.reflectBoolean("goToAdjacentPage", -1) }
            waitForFlowPageTurnSettled(scenario, start.view, 0, 0, "expected discrete A2 warm-up backward settle")

            scenario.withActivity { activity ->
                dispatchDrag(
                    start.view,
                    start = PointF(start.view.width * 0.25f, start.view.height * 0.10f),
                    end = PointF(start.view.width * -0.45f, start.view.height * 0.10f),
                    beforeRelease = {
                        val curl = checkNotNull(start.view.reflectPrivateAny("curlDrawable")) {
                            "leftward SIMULATION drag must use PageCurlDrawable"
                        }
                        check(curl.javaClass.simpleName == "PageCurlDrawable")
                        check(activity.findLegacyEpubCurlOverlay() == null) {
                            "leftward finger drag must not create a legacy GL overlay child"
                        }
                    },
                )
            }
            waitForFlowPageTurnSettled(
                scenario,
                start.view,
                1,
                start.nextPageTop,
                "left-half leftward drag must still follow dx and turn forward",
            )

            scenario.withActivity { activity ->
                dispatchDrag(
                    start.view,
                    start = PointF(start.view.width * 0.75f, start.view.height * 0.10f),
                    end = PointF(start.view.width * 1.45f, start.view.height * 0.10f),
                    beforeRelease = {
                        val curl = checkNotNull(start.view.reflectPrivateAny("curlDrawable")) {
                            "rightward SIMULATION drag must use PageCurlDrawable"
                        }
                        check(curl.javaClass.simpleName == "PageCurlDrawable")
                        check(activity.findLegacyEpubCurlOverlay() == null) {
                            "rightward finger drag must not create a legacy GL overlay child"
                        }
                    },
                )
            }
            waitForFlowPageTurnSettled(
                scenario,
                start.view,
                0,
                0,
                "right-half rightward drag must still follow dx and turn backward",
            )
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

    private fun createCoverEpubUri(fileName: String): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeCoverEpub(file)
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

    private fun createShortChapterBoundaryEpubUri(fileName: String): Uri {
        val file = File(appContext.cacheDir, fileName)
        writeShortChapterBoundaryEpub(file)
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

    private fun writeCoverEpub(file: File) {
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
                    <ocf:container xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container">
                      <ocf:rootfiles>
                        <ocf:rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </ocf:rootfiles>
                    </ocf:container>
                """.trimIndent(),
            )
            addText(
                "OEBPS/content.opf",
                """
                    <opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="3.0">
                      <opf:manifest>
                        <opf:item id="cover" href="../Images/cover%20art.png" media-type="image/png" properties="thumbnail cover-image"/>
                        <opf:item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                      </opf:manifest>
                      <opf:spine><opf:itemref idref="ch1"/></opf:spine>
                    </opf:package>
                """.trimIndent(),
            )
            addText(
                "OEBPS/ch1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Cover runtime.</p></body></html>",
            )
            addBinary("Images/cover art.png", tinyPngBytes(Color.rgb(0x25, 0x69, 0xBE)))
        }
    }

    private fun writeShortChapterBoundaryEpub(file: File) {
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
                        <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                      </spine>
                    </package>
                """.trimIndent(),
            )
            addText(
                "OEBPS/ch1.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><p>Chapter one end.</p></body>
                    </html>
                """.trimIndent(),
            )
            addText(
                "OEBPS/ch2.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><p>Chapter two start.</p></body>
                    </html>
                """.trimIndent(),
            )
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

    private fun upsertProgress(progress: ReadingProgressEntity) {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking { db.readingProgressDao().upsert(progress) }
        } finally {
            db.close()
        }
    }

    private fun readingSessions(bookId: String): List<ReadingSessionEntity> {
        val db = Room.databaseBuilder(appContext, ReadflowDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking { db.readingSessionDao().allForBackup().filter { it.bookId == bookId } }
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
        activity: MainActivity,
        expectedPage: Int,
        expectedScrollY: Int,
        message: String,
    ): FlowReopenTrace {
        val frames = mutableListOf<FlowReopenFrame>()
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = activity.runOnMainForTest {
                val view = activity.findEpubFlowView() ?: return@runOnMainForTest null
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

    private fun MainActivity.findLegacyEpubCurlOverlay(): View? =
        window.decorView.findDescendant { it.javaClass.name == LEGACY_EPUB_CURL_OVERLAY_CLASS_NAME }

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

    private fun waitForFlowPageTurnSettled(
        scenario: ActivityScenario<MainActivity>,
        view: View,
        pageIndex: Int,
        scrollY: Int,
        message: String,
    ) {
        waitForConditionResult(message) {
            scenario.withActivity {
                val animatorRunning =
                    (view.reflectPrivateAny("flipAnimator") as? ValueAnimator)?.isRunning == true
                if (
                    view.reflectInt("currentPageIndex") == pageIndex &&
                    view.scrollY == scrollY &&
                    view.reflectPrivateAny("curlDrawable") == null &&
                    view.reflectPrivateAny("slideDrawable") == null &&
                    !animatorRunning
                ) true else null
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

    private fun View.swapPrivateField(name: String, value: Any?): Any? {
        val field = javaClass.getDeclaredField(name).apply { isAccessible = true }
        val previous = field.get(this)
        field.set(this, value)
        return previous
    }

    private fun View.setPendingDecodesForTest(pending: Boolean) {
        swapPrivateField("pendingDecodesProvider", { pending })
    }

    private fun Any.invokeNoArgCompat(name: String) {
        val exact = javaClass.declaredMethods.firstOrNull { method ->
            method.name == name && method.parameterCount == 0
        }
        val method = exact ?: javaClass.declaredMethods.single { candidate ->
            candidate.parameterCount == 0 && candidate.name.startsWith("$name\$")
        }
        method.isAccessible = true
        method.invoke(this)
    }

    private fun View.reflectTextView(): TextView =
        javaClass.getDeclaredField("textView").apply { isAccessible = true }.get(this) as TextView

    private fun View.nonLineTopBetween(startExclusive: Int, endExclusive: Int, fraction: Float): Int {
        val textView = reflectTextView()
        val layout = checkNotNull(textView.layout)
        val preferred = startExclusive + ((endExclusive - startExclusive) * fraction).toInt()
        return ((startExclusive + 1) until endExclusive)
            .filter { y ->
                val viewportTopInLayout = (y - textView.paddingTop).coerceAtLeast(0)
                layout.getLineTop(layout.getLineForVertical(viewportTopInLayout)) < viewportTopInLayout
            }
            .minByOrNull { y -> abs(y - preferred) }
            ?: error("runtime smoke needs a scroll position cutting a painted line between $startExclusive and $endExclusive")
    }

    private fun View.fullLineRangeInViewport(): IntRange {
        val textView = reflectTextView()
        val layout = checkNotNull(textView.layout)
        val viewportTopInLayout = (scrollY - textView.paddingTop).coerceAtLeast(0)
        val layoutBottomLimit = (
            scrollY + height - textView.paddingTop - textView.paddingBottom
        ).coerceAtLeast(viewportTopInLayout + 1)
        var first = layout.getLineForVertical(viewportTopInLayout)
        if (layout.getLineTop(first) < viewportTopInLayout && first < layout.lineCount - 1) first++
        var last = layout.getLineForVertical(layoutBottomLimit - 1)
        while (last > first && layout.getLineBottom(last) > layoutBottomLimit) last--
        return first..last.coerceAtLeast(first)
    }

    private fun View.invokeSetModeAnchoredPaged(layoutOffset: Int) {
        invokeSetModeAnchored("PAGED", layoutOffset)
    }

    private fun View.invokeSetModeAnchored(modeName: String, layoutOffset: Int) {
        val modeClass = Class.forName("$EPUB_FLOW_VIEW_CLASS_NAME\$Mode")
        val mode = checkNotNull(modeClass.enumConstants) { "EpubFlowView.Mode enum constants unavailable" }
            .first { (it as Enum<*>).name == modeName }
        javaClass.getDeclaredMethod("setModeAnchored", modeClass, Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(this, mode, layoutOffset)
    }

    private fun View.invokePrivateBitmap(name: String): Bitmap? {
        val method = javaClass.getDeclaredMethod(name).apply { isAccessible = true }
        return method.invoke(this) as? Bitmap
    }

    private fun Any.reflectPrivateInt(name: String): Int =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Int

    private fun Any.reflectPrivateLong(name: String): Long =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Long

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
        dispatchTouchEvent(target, motionEvent(downTime, downTime + 300L, MotionEvent.ACTION_MOVE, armMove))
        dispatchTouchEvent(
            target,
            motionEvent(downTime, downTime + 700L, MotionEvent.ACTION_MOVE, scrolledMove),
        )
        val duringDragY = target.scrollY
        val duringDragClip = target.reflectPrivateBoolean("pageClipActive")
        dispatchTouchEvent(target, motionEvent(downTime, downTime + 1_100L, MotionEvent.ACTION_UP, scrolledMove))
        return FlowTemporaryScrollResult(
            startY = startY,
            duringDragY = duringDragY,
            afterReleaseY = target.scrollY,
            duringDragClip = duringDragClip,
            afterReleaseClip = target.reflectPrivateBoolean("pageClipActive"),
            currentPage = target.reflectInt("currentPageIndex"),
        )
    }

    private fun dispatchDrag(
        target: View,
        start: PointF,
        end: PointF,
        steps: Int = 6,
        beforeRelease: (() -> Unit)? = null,
    ) {
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
        beforeRelease?.invoke()
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

    @SuppressLint("NewApi")
    private fun awaitCommittedFrame(scenario: ActivityScenario<MainActivity>, target: View) {
        check(Build.VERSION.SDK_INT >= 29) { "Window frame commits require API 29+" }
        check(target.isHardwareAccelerated) { "Window frame evidence requires a hardware-accelerated reader" }
        val committed = CountDownLatch(1)
        scenario.withActivity {
            target.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            target.postInvalidateOnAnimation()
        }
        assertTrue(
            "reader frame was not committed",
            committed.await(FRAME_COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        SystemClock.sleep(FRAME_PRESENTATION_GRACE_MS)
    }

    @SuppressLint("NewApi")
    private fun awaitFirstCommittedFrameMatchingAfter(
        scenario: ActivityScenario<MainActivity>,
        target: View,
        frameReady: () -> Boolean,
        action: () -> Unit,
    ) {
        check(Build.VERSION.SDK_INT >= 29) { "Window frame commits require API 29+" }
        check(target.isHardwareAccelerated) { "Window frame evidence requires a hardware-accelerated reader" }
        val committed = CountDownLatch(1)
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        scenario.withActivity {
            listener = ViewTreeObserver.OnPreDrawListener {
                if (frameReady()) {
                    target.viewTreeObserver.removeOnPreDrawListener(listener)
                    // ViewRootImpl captures commit callbacks after dispatchOnPreDraw, so this callback
                    // belongs to the first traversal that actually draws the newly active overlay.
                    target.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                }
                true
            }
            target.viewTreeObserver.addOnPreDrawListener(listener)
            action()
        }
        val didCommit = committed.await(FRAME_COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!didCommit) {
            scenario.withActivity {
                if (target.viewTreeObserver.isAlive) {
                    target.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            }
        }
        assertTrue("matching reader frame after action was not committed", didCommit)
        SystemClock.sleep(FRAME_PRESENTATION_GRACE_MS)
    }

    @SuppressLint("NewApi")
    private fun captureWindowRegion(
        scenario: ActivityScenario<MainActivity>,
        target: View,
    ): Bitmap {
        val spec = scenario.withActivity { activity ->
            val location = IntArray(2)
            if (Build.VERSION.SDK_INT >= 34) {
                target.getLocationInWindow(location)
            } else {
                target.getLocationOnScreen(location)
            }
            WindowCaptureSpec(
                window = activity.window,
                left = location[0],
                top = location[1],
                width = target.width,
                height = target.height,
            )
        }
        val screenshot = checkNotNull(
            if (Build.VERSION.SDK_INT >= 34) {
                instrumentation.uiAutomation.takeScreenshot(spec.window)
            } else {
                instrumentation.uiAutomation.takeScreenshot()
            },
        ) { "UiAutomation did not return a composed window screenshot" }
        try {
            check(spec.left >= 0 && spec.top >= 0) { "invalid capture origin: $spec" }
            check(spec.left + spec.width <= screenshot.width) {
                "capture exceeds screenshot width: spec=$spec screenshot=${screenshot.width}x${screenshot.height}"
            }
            check(spec.top + spec.height <= screenshot.height) {
                "capture exceeds screenshot height: spec=$spec screenshot=${screenshot.width}x${screenshot.height}"
            }
            val cropped = Bitmap.createBitmap(
                screenshot,
                spec.left,
                spec.top,
                spec.width,
                spec.height,
            )
            return try {
                checkNotNull(cropped.copy(Bitmap.Config.ARGB_8888, false)) {
                    "unable to copy composed reader frame"
                }
            } finally {
                if (cropped !== screenshot) cropped.recycle()
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun captureUntilVisuallyStable(
        scenario: ActivityScenario<MainActivity>,
        target: View,
    ): Bitmap {
        var previous = captureWindowRegion(scenario, target)
        var stablePairs = 0
        repeat(MAX_VISUAL_SETTLE_FRAMES) {
            awaitCommittedFrame(scenario, target)
            val current = captureWindowRegion(scenario, target)
            val diff = bitmapDiff(previous, current)
            previous.recycle()
            previous = current
            stablePairs = if (
                diff.rgbMae <= VISUAL_SETTLE_MAE &&
                diff.badPixelRatio <= VISUAL_SETTLE_BAD_PIXEL_RATIO
            ) {
                stablePairs + 1
            } else {
                0
            }
            if (stablePairs >= REQUIRED_VISUAL_SETTLE_PAIRS) return previous
        }
        previous.recycle()
        error("reader window did not visually settle within $MAX_VISUAL_SETTLE_FRAMES committed frames")
    }

    private fun captureUntilMateriallyDifferent(
        scenario: ActivityScenario<MainActivity>,
        target: View,
        baseline: Bitmap,
        minRgbMae: Double,
        minBadPixelRatio: Double,
    ): Bitmap {
        var lastDiff: BitmapDiff? = null
        repeat(MAX_VISUAL_SETTLE_FRAMES) {
            awaitCommittedFrame(scenario, target)
            val frame = captureWindowRegion(scenario, target)
            val diff = bitmapDiff(baseline, frame)
            lastDiff = diff
            if (diff.rgbMae >= minRgbMae && diff.badPixelRatio >= minBadPixelRatio) return frame
            frame.recycle()
        }
        error(
            "reader Window did not present a materially different frame within " +
                "$MAX_VISUAL_SETTLE_FRAMES commits; lastDiff=$lastDiff",
        )
    }

    private fun sampledMeanLuma(bitmap: Bitmap): Double {
        var total = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                total += Color.red(pixel) * 0.2126 + Color.green(pixel) * 0.7152 + Color.blue(pixel) * 0.0722
                count++
                x += 16
            }
            y += 16
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun bitmapDiff(expected: Bitmap, actual: Bitmap): BitmapDiff =
        bitmapRegionDiff(
            expected = expected,
            actual = actual,
            expectedLeft = 0,
            actualLeft = 0,
            width = expected.width,
            height = expected.height,
        )

    private fun bestHorizontalShiftPx(
        expected: Bitmap,
        actual: Bitmap,
        maxShiftPx: Int,
    ): Int {
        require(expected.width == actual.width && expected.height == actual.height)
        val maxShift = maxShiftPx.coerceIn(0, expected.width - 1)
        val probeWidth = minOf(expected.width / 3, expected.width - maxShift).coerceAtLeast(1)
        val sampleStride = 12
        val sampledRowCount = (expected.height + sampleStride - 1) / sampleStride
        val expectedRows = IntArray(sampledRowCount * expected.width)
        val actualRows = IntArray(sampledRowCount * probeWidth)
        val expectedRow = IntArray(expected.width)
        val actualRow = IntArray(probeWidth)

        var sampledRow = 0
        var y = 0
        while (y < expected.height) {
            expected.getPixels(expectedRow, 0, expected.width, 0, y, expected.width, 1)
            actual.getPixels(actualRow, 0, probeWidth, 0, y, probeWidth, 1)
            expectedRow.copyInto(expectedRows, sampledRow * expected.width)
            actualRow.copyInto(actualRows, sampledRow * probeWidth)
            sampledRow += 1
            y += sampleStride
        }

        var bestShift = 0
        var bestError = Long.MAX_VALUE
        for (shift in 0..maxShift) {
            var error = 0L
            for (row in 0 until sampledRowCount) {
                val expectedRowOffset = row * expected.width + shift
                val actualRowOffset = row * probeWidth
                var x = 0
                while (x < probeWidth) {
                    val expectedColor = expectedRows[expectedRowOffset + x]
                    val actualColor = actualRows[actualRowOffset + x]
                    error += kotlin.math.abs(Color.red(expectedColor) - Color.red(actualColor))
                    error += kotlin.math.abs(Color.green(expectedColor) - Color.green(actualColor))
                    error += kotlin.math.abs(Color.blue(expectedColor) - Color.blue(actualColor))
                    x += sampleStride
                }
            }
            if (error < bestError) {
                bestError = error
                bestShift = shift
            }
        }
        return bestShift
    }

    private fun bitmapRegionDiff(
        expected: Bitmap,
        actual: Bitmap,
        expectedLeft: Int,
        actualLeft: Int,
        width: Int,
        height: Int,
    ): BitmapDiff {
        require(expected.height >= height && actual.height >= height)
        require(expectedLeft >= 0 && expectedLeft + width <= expected.width)
        require(actualLeft >= 0 && actualLeft + width <= actual.width)
        val expectedPixels = IntArray(width * height)
        val actualPixels = IntArray(width * height)
        expected.getPixels(expectedPixels, 0, width, expectedLeft, 0, width, height)
        actual.getPixels(actualPixels, 0, width, actualLeft, 0, width, height)
        var totalChannelDelta = 0L
        var maxChannelDelta = 0
        var badPixels = 0
        expectedPixels.indices.forEach { index ->
            val expectedColor = expectedPixels[index]
            val actualColor = actualPixels[index]
            val red = kotlin.math.abs(Color.red(expectedColor) - Color.red(actualColor))
            val green = kotlin.math.abs(Color.green(expectedColor) - Color.green(actualColor))
            val blue = kotlin.math.abs(Color.blue(expectedColor) - Color.blue(actualColor))
            val pixelMax = maxOf(red, green, blue)
            totalChannelDelta += red + green + blue
            maxChannelDelta = maxOf(maxChannelDelta, pixelMax)
            if (pixelMax > BAD_PIXEL_CHANNEL_DELTA) badPixels += 1
        }
        return BitmapDiff(
            rgbMae = totalChannelDelta.toDouble() / (expectedPixels.size * 3.0),
            maxChannelDelta = maxChannelDelta,
            badPixelRatio = badPixels.toDouble() / expectedPixels.size.toDouble(),
            pixelCount = expectedPixels.size,
        )
    }

    private fun assertVisualDiffAtMost(
        message: String,
        diff: BitmapDiff,
        maxRgbMae: Double,
        maxBadPixelRatio: Double,
    ) {
        assertTrue(
            "$message; diff=$diff limits=(mae=$maxRgbMae,badRatio=$maxBadPixelRatio)",
            diff.rgbMae <= maxRgbMae && diff.badPixelRatio <= maxBadPixelRatio,
        )
    }

    private fun assertVisualDiffAtLeast(
        message: String,
        diff: BitmapDiff,
        minRgbMae: Double,
        minBadPixelRatio: Double,
    ) {
        assertTrue(
            "$message; diff=$diff limits=(mae=$minRgbMae,badRatio=$minBadPixelRatio)",
            diff.rgbMae >= minRgbMae && diff.badPixelRatio >= minBadPixelRatio,
        )
    }

    private fun writeWindowFrameEvidence(name: String, bitmap: Bitmap) {
        val root = checkNotNull(appContext.getExternalFilesDir(null)) {
            "external evidence directory is unavailable"
        }
        val directory = File(root, "handfeel-window-frames").apply { mkdirs() }
        File(directory, name).outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "failed to write window-frame evidence $name"
            }
        }
    }

    private fun View.drawRuntimeBitmap(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            draw(canvas)
        }

    private fun assertSampledPixelsEqual(message: String, expected: Bitmap, actual: Bitmap) {
        assertEquals("$message: width", expected.width, actual.width)
        assertEquals("$message: height", expected.height, actual.height)
        val diff = bitmapDiff(expected, actual)
        assertTrue("$message must match across every pixel; diff=$diff", diff.maxChannelDelta == 0)
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        point: PointF,
    ): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, point.x, point.y, 0)

    private class FailNextDrawDrawable(
        private val delegate: Drawable?,
    ) : Drawable() {
        private var failNext = true

        override fun draw(canvas: Canvas) {
            if (failNext) {
                failNext = false
                error("forced boundary snapshot draw failure")
            }
            delegate?.bounds = bounds
            delegate?.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            delegate?.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            delegate?.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = delegate?.opacity ?: PixelFormat.TRANSLUCENT
    }

    private data class WindowCaptureSpec(
        val window: Window,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private data class BitmapDiff(
        val rgbMae: Double,
        val maxChannelDelta: Int,
        val badPixelRatio: Double,
        val pixelCount: Int,
    )

    private data class FlowAnchorBaseline(
        val view: View,
        val pageCount: Int,
        val pageOneTop: Int,
        val pageTwoTop: Int,
    )

    private data class FlowAnchorResult(
        val outgoingLastFullLine: Int,
        val incomingFirstFullLine: Int,
        val incomingLineTop: Int,
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
        val afterReleaseClip: Boolean,
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

    private data class FlowBoundaryTapTarget(
        val view: View,
    )

    private data class FlowBoundaryTapResult(
        val currentPage: Int,
        val scrollY: Int,
        val pendingConsumed: Boolean,
        val flipAnimatorPresent: Boolean,
        val text: String,
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

    private data class FlowSimulationTurnStart(
        val view: View,
        val nextPageTop: Int,
    )

    private data class FlowSimulationTurnStarted(
        val drawableName: String?,
        val animatorRunning: Boolean,
        val currentPage: Int,
        val scrollY: Int,
    )

    private data class FlowTurnSettled(
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
        private const val LEGACY_EPUB_CURL_OVERLAY_CLASS_NAME = "dev.readflow.render.epub.EpubCurlOverlay"
        private const val DB_NAME = "readflow.db"
        private const val EVENT_STEP_MS = 24L
        private const val FRAME_POLL_MS = 16L
        private const val FRAME_COMMIT_TIMEOUT_MS = 2_000L
        private const val FRAME_PRESENTATION_GRACE_MS = 16L
        private const val BAD_PIXEL_CHANNEL_DELTA = 8
        private const val MAX_VISUAL_SETTLE_FRAMES = 24
        private const val REQUIRED_VISUAL_SETTLE_PAIRS = 2
        private const val VISUAL_SETTLE_MAE = 0.25
        private const val VISUAL_SETTLE_BAD_PIXEL_RATIO = 0.001
        private const val VISIBLE_CONTENT_ALPHA_EPSILON = 0.05f
        private val UI_TIMEOUT_MS = 12.seconds.inWholeMilliseconds
        private val DB_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
