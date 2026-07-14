package dev.readflow.render.epub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.ui.readerPaperBackground
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubFlowViewTest {

    @Test
    fun `next page from free rest starts with the first line not fully visible in the viewport`() {
        val view = pagedFlowView()
        assertTrue(
            "pageCount=${view.pageCount()} textView=${view.textView.width}x${view.textView.height} " +
                "lineCount=${view.textView.layout?.lineCount}",
            view.pageCount() > 3,
        )
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.75f)

        view.scrollTo(0, freeRest)
        val before = fullLineRangeInViewport(view)

        assertTrue(view.goToAdjacentPage(1))
        val after = fullLineRangeInViewport(view)

        assertEquals(
            "forward turn must neither repeat nor skip a full line from the free-rest viewport",
            before.last + 1,
            after.first,
        )
        assertEquals(
            "the turned page must settle on its first complete line top",
            requireNotNull(view.textView.layout).getLineTop(after.first),
            view.scrollY,
        )
    }

    @Test
    fun `previous page from padded free rest ends before the line intersecting the painted top edge`() {
        val view = pagedFlowView(textPaddingTop = 12, textPaddingBottom = 9)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.75f)

        view.scrollTo(0, freeRest)
        val layout = requireNotNull(view.textView.layout)
        val viewportTopInLayout = (view.scrollY - view.textView.paddingTop).coerceAtLeast(0)
        val topIntersectingLine = layout.getLineForVertical(viewportTopInLayout)
        assertTrue(
            "test requires a painted line cut by FREE_REST's visual top edge",
            layout.getLineTop(topIntersectingLine) < viewportTopInLayout,
        )

        assertTrue(view.goToAdjacentPage(-1))
        val after = fullLineRangeInViewport(view)

        assertEquals(
            "backward turn must end immediately before the line intersecting FREE_REST's top edge",
            topIntersectingLine - 1,
            after.last,
        )
    }

    @Test
    fun `cancelled interactive turn restores the exact free rest viewport`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.75f)

        view.scrollTo(0, freeRest)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.75f)
        view.endInteractiveCurl(velocityX = 0f)
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals("a cancelled turn must restore the viewport that was under the finger", freeRest, view.scrollY)
    }

    @Test
    fun `action cancel rolls back an interactive slide even after the commit threshold`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val startTop = requireNotNull(view.pageTopPxAt(0))
        val downX = view.width * 0.85f
        val moveX = view.width * 0.15f
        val y = view.height * 0.50f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
        val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        assertTrue("test must cross the normal release commit threshold", slide.progress > 0.5f)

        view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals("input cancellation must never commit a page turn", 0, view.currentPageIndex())
        assertEquals("input cancellation must restore the outgoing page anchor", startTop, view.scrollY)
    }

    @Test
    fun `committed interactive turn continues after the last full line at free rest`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.75f)

        view.scrollTo(0, freeRest)
        val before = fullLineRangeInViewport(view)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.25f)
        view.endInteractiveCurl(velocityX = 0f)
        val after = fullLineRangeInViewport(view)

        assertEquals(
            "interactive commit must reveal the first line after the outgoing viewport's last full line",
            before.last + 1,
            after.first,
        )
    }

    @Test
    fun `clearing boundary previews lets a committed local settle finish naturally`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTopOffsetChanged = reportedOffsets::add,
        )
        reportedOffsets.clear()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = 0f)
        view.endInteractiveCurl(velocityX = 0f)

        val targetPage = view.currentPageIndex()
        val targetOffset = view.topLayoutOffset()
        assertTrue("the committed gesture must park an adjacent target", targetPage > 0)
        assertTrue("the target stays silent until settle", reportedOffsets.isEmpty())

        val animator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertTrue("fixture needs a running local settle", animator.isRunning)
        view.clearBoundaryPreviews()

        assertTrue("clearing boundary-owned previews must not cancel a local settle", animator.isRunning)
        assertEquals("SOFTWARE_SETTLING", view.privateField("interactiveTurnState").toString())
        assertNotNull("the local overlay must remain until its own settle ends", view.privateField("slideDrawable"))
        assertTrue("preview invalidation must not publish the local target early", reportedOffsets.isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals(targetPage, view.currentPageIndex())
        assertEquals(listOf(targetOffset), reportedOffsets)
        assertNull(view.privateField("slideDrawable"))
    }

    @Test
    fun `boundary clear followed by external navigation publishes only the external locator`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        reportedOffsets.clear()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.25f)
        view.endInteractiveCurl(velocityX = 0f)

        val parkedTargetOffset = view.topLayoutOffset()
        val oldAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertTrue("fixture needs a silently parked local commit", oldAnimator.isRunning && reportedOffsets.isEmpty())

        view.clearBoundaryPreviews()
        view.goToPage(2)
        val externalOffset = view.topLayoutOffset()

        assertTrue("the external destination must differ from the silently parked local target", externalOffset != parkedTargetOffset)
        assertTrue(
            "clearBoundaryPreviews must not publish before external navigation takes ownership; " +
                "parked=$parkedTargetOffset external=$externalOffset reports=$reportedOffsets",
            reportedOffsets == listOf(externalOffset),
        )
        assertFalse("external navigation must synchronously retire the old animator", oldAnimator.isRunning)
        assertNull("external navigation must synchronously clear the old slide", view.privateField("slideDrawable"))
        assertNull("external navigation must synchronously clear the old origin", view.privateField("curlOrigin"))
        assertEquals("NONE", view.privateField("interactiveTurnState").toString())

        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals("late settle work must not publish another locator", listOf(externalOffset), reportedOffsets)
        assertEquals(2, view.currentPageIndex())
    }

    @Test
    fun `dispose cancelling a committed interactive settle publishes no locator`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTopOffsetChanged = reportedOffsets::add,
        )
        reportedOffsets.clear()
        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = 0f)
        view.endInteractiveCurl(velocityX = 0f)
        val parkedPage = view.currentPageIndex()
        val animator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertTrue("fixture needs a committed target parked under a running settle", parkedPage > 0 && animator.isRunning)
        assertTrue("the committed target must stay silent before settle", reportedOffsets.isEmpty())
        val reportsBeforeDispose = reportedOffsets.toList()

        view.dispose()
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals(
            "dispose-driven animator cancellation must not publish the parked target locator",
            reportsBeforeDispose,
            reportedOffsets,
        )
    }

    @Test
    fun `simulation drag uses local paper renderer`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        val downX = view.width * 0.85f
        val moveX = view.width * 0.15f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )

            assertNotNull(
                "SIMULATION finger drag must create the software PageCurlDrawable",
                view.privateField("curlDrawable") as PageCurlDrawable?,
            )
            assertNull("software curl must not fall back to a slide drawable", view.privateField("slideDrawable"))
        } finally {
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }

        assertEquals("ACTION_CANCEL must keep the outgoing logical page", startPage, view.currentPageIndex())
        assertEquals("ACTION_CANCEL must restore the outgoing page anchor", startTop, view.scrollY)
    }

    @Test
    fun `simulation discrete turn uses the same software renderer as finger drag`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)

        view.goToAdjacentPage(1)

        assertNotNull(
            "SIMULATION tap and key turns must install the same local paper drawable as a finger drag",
            view.privateField("curlDrawable") as PageCurlDrawable?,
        )
        assertNull("the local paper renderer must not degrade to slide", view.privateField("slideDrawable"))
        assertNotNull("a discrete local-paper turn still needs a settle animator", view.privateField("flipAnimator"))
    }

    @Test
    fun `simulation discrete turn publishes the parked target only after settle`() {
        val reports = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reports::add,
        )
        reports.clear()

        view.goToAdjacentPage(1)

        assertTrue("the target page may be parked beneath the overlay", view.currentPageIndex() > 0)
        assertTrue("the parked target must stay silent while the local PAPER animation is visible", reports.isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals("the settled target must publish exactly once", 1, reports.size)
        assertEquals(view.topLayoutOffset(), reports.single())
    }

    @Test
    fun `warmed simulation discrete turn does not recapture live pages`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val background = RecordingBoundsTopDrawable()
        view.background = background
        view.preCachePageTexturesForTest()
        background.boundsTops.clear()

        view.goToAdjacentPage(1)

        assertTrue(
            "a warmed tap or key turn must consume cached page shots without drawing the live view: " +
                background.boundsTops,
            background.boundsTops.isEmpty(),
        )
    }

    @Test
    fun `committed warmed turn rekeys existing page shot identities into the next cache`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.preCachePageTexturesForTest()
        val oldFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
        val oldRevealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)

        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        val newFront = view.privateField("cachedFrontBitmap") as Bitmap?
        val newForward = view.privateField("cachedRevealedBitmap") as Bitmap?
        val newBackward = view.privateField("cachedBackwardBitmap") as Bitmap?
        assertTrue(
            "commit must rekey the revealed frame as current and retain the old current as a neighbor; " +
                "oldFront=$oldFront recycled=${oldFront.isRecycled} " +
                "oldRevealed=$oldRevealed recycled=${oldRevealed.isRecycled} " +
                "new=[$newBackward,$newFront,$newForward]",
            newFront === oldRevealed &&
                !oldRevealed.isRecycled &&
                !oldFront.isRecycled &&
                (newBackward === oldFront || newForward === oldFront),
        )
    }

    @Test
    fun `committed warmed interactive turn rekeys active identities into the next cache`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.preCachePageTexturesForTest()
        val oldFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
        val oldRevealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val activeSlide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        assertTrue(activeSlide.privateBitmap("frontBitmap") === oldFront)
        assertTrue(activeSlide.privateBitmap("revealedBitmap") === oldRevealed)
        view.updateInteractiveCurl(x = view.width * 0.10f)
        view.endInteractiveCurl(velocityX = 0f)
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        val newFront = view.privateField("cachedFrontBitmap") as Bitmap?
        val newBackward = view.privateField("cachedBackwardBitmap") as Bitmap?
        assertTrue(
            "interactive commit must transfer the revealed identity into current and the old front " +
                "into the backward neighbor without recycling or replacing either; " +
                "oldFront=$oldFront recycled=${oldFront.isRecycled} " +
                "oldRevealed=$oldRevealed recycled=${oldRevealed.isRecycled} " +
                "newBackward=$newBackward newFront=$newFront",
            newFront === oldRevealed &&
                newBackward === oldFront &&
                !oldRevealed.isRecycled &&
                !oldFront.isRecycled,
        )
    }

    @Test
    fun `settle without an active drawable clears stale local lifecycle anchors`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val detachedSlide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        assertNotNull("fixture requires an active local origin", view.privateField("curlOrigin"))
        assertNotNull("fixture requires an active local target window", view.privateField("curlTargetWindow"))
        view.overlay.remove(detachedSlide)
        detachedSlide.recycle()
        view.setPrivateField("slideDrawable", null)

        view.endInteractiveCurl(velocityX = 0f)

        assertEquals("NONE", view.privateField("interactiveTurnState").toString())
        assertNull("a no-drawable settle must retire the stale local target window", view.privateField("curlTargetWindow"))
        assertNull("a no-drawable settle must retire the matching origin", view.privateField("curlOrigin"))
        view.dispose()
    }

    @Test
    fun `simulation vertical side swipe uses local paper renderer`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val x = view.width * 0.85f
        val downY = view.height * 0.65f
        val upY = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, downY))
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, x, upY),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, x, upY),
            )

            assertNotNull(
                "SIMULATION vertical side swipe must create the software PageCurlDrawable",
                view.privateField("curlDrawable") as PageCurlDrawable?,
            )
            assertNull("vertical software curl must not fall back to a slide drawable", view.privateField("slideDrawable"))
        } finally {
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `cache ready simulation boundary move stays finger driven without requesting navigation`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = tapZones::add,
        )
        view.goToLastPage()
        val preview = view.offerReadyBoundaryPreviewForTest(forward = true)
        val downX = view.width * 0.75f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))

            assertTrue(
                "a cache-ready chapter boundary must start the same software curl as an in-chapter drag",
                view.privateField("curlDrawable") is PageCurlDrawable,
            )
            assertTrue(
                "boundary MOVE must retain the finger transaction instead of requesting chapter navigation",
                tapZones.isEmpty(),
            )
        } finally {
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            if (!preview.isRecycled) preview.recycle()
        }
    }

    @Test
    fun `cancelling cache ready boundary drag leaves logical position silent and unchanged`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = tapZones::add,
            onTopOffsetChanged = reportedOffsets::add,
        )
        view.goToLastPage()
        reportedOffsets.clear()
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        val preview = view.offerReadyBoundaryPreviewForTest(forward = true, token = 2L)
        val downX = view.width * 0.75f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals("ACTION_CANCEL must keep the outgoing logical page", startPage, view.currentPageIndex())
        assertEquals("ACTION_CANCEL must restore the outgoing page anchor", startTop, view.scrollY)
        assertTrue("ACTION_CANCEL must not request adjacent-chapter navigation", tapZones.isEmpty())
        assertTrue(
            "ACTION_CANCEL must not report a target offset that the engine could publish as a locator",
            reportedOffsets.isEmpty(),
        )
        if (!preview.isRecycled) preview.recycle()
    }

    @Test
    fun `committed boundary drag keeps its intent until a late preview arrives`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val commits = mutableListOf<Any>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = tapZones::add,
        )
        view.installBoundaryCommitRecorderForTest(commits)
        view.goToLastPage()
        val downX = view.width * 0.75f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y))

            assertEquals("BOUNDARY_DISCRETE_WAITING", view.privateField("interactiveTurnState").toString())
            view.offerReadyBoundaryPreviewForTest(forward = true, token = 3L)
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals("the retained drag must publish exactly one boundary commit", 1, commits.size)
            assertTrue("the retained drag must not re-enter tap navigation", tapZones.isEmpty())
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `NONE boundary swipe keeps the same late preview intent as an in chapter hard turn`() {
        lateinit var view: EpubFlowView
        val commits = mutableListOf<Any>()
        view = pagedFlowView(
            flipStyle = PageFlipStyle.NONE,
            onTapZone = { zone ->
                if (zone == EpubFlowTapZone.NEXT) view.startDiscreteBoundaryTurn(1)
            },
        )
        view.installBoundaryCommitRecorderForTest(commits)
        view.goToLastPage()
        val downX = view.width * 0.85f
        val moveX = view.width * 0.05f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
            assertEquals("BOUNDARY_WAITING", view.privateField("interactiveTurnState").toString())

            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y))
            assertEquals("BOUNDARY_DISCRETE_WAITING", view.privateField("interactiveTurnState").toString())
            view.offerReadyBoundaryPreviewForTest(forward = true, token = 99L)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertEquals(1, commits.size)
            assertNotNull(view.privateField("conversionSnapshotDrawable"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `committed cache ready boundary drag publishes exactly one commit`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val commits = mutableListOf<Any>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = tapZones::add,
        )
        view.installBoundaryCommitRecorderForTest(commits)
        view.goToLastPage()
        val preview = view.offerReadyBoundaryPreviewForTest(forward = true, token = 4L)
        val downX = view.width * 0.85f
        val moveX = view.width * 0.05f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y))
        shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

        assertEquals("a committed boundary transaction must publish once", 1, commits.size)
        assertTrue("commit must not re-enter the legacy tap-zone navigation path", tapZones.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)
        assertEquals("settle and reveal callbacks must not publish the same commit again", 1, commits.size)
        if (!preview.isRecycled) preview.recycle()
    }

    @Test
    fun `discrete slide boundary cache hit commits once and hands revealed bitmap to continuity cover`() {
        val commits = mutableListOf<Any>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        view.installBoundaryCommitRecorderForTest(commits)
        view.goToLastPage()
        val revealed = view.offerReadyBoundaryPreviewForTest(forward = true, token = 5L)

        try {
            assertTrue(view.startDiscreteBoundaryTurn(1))
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertTrue(
                "the settled software turn must transfer the cached target without copying it",
                cover.privateBitmap("bitmap") === revealed,
            )
            assertFalse("the continuity cover must keep the revealed target alive", revealed.isRecycled)
            assertEquals("the discrete software turn must publish exactly once", 1, commits.size)

            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)
            assertEquals("settle callbacks must not publish the same target twice", 1, commits.size)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `warmed discrete boundary turn transfers outgoing identity without viewport recapture`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        view.goToLastPage()
        val background = RecordingBoundsTopDrawable()
        view.background = background
        view.preCachePageTexturesForTest()
        val warmedOutgoing = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
        val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)
        val leasedBeforeTurn = budget.leasedBytes
        background.boundsTops.clear()
        view.offerReadyBoundaryPreviewForTest(forward = true, token = 51L)

        try {
            assertTrue(view.startDiscreteBoundaryTurn(1))

            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertTrue(
                "a warmed boundary tap must transfer the existing current-page owner by identity",
                slide.privateBitmap("frontBitmap") === warmedOutgoing,
            )
            assertTrue(
                "the warmed discrete path must not call snapshotViewport or draw the live page again: " +
                    background.boundsTops,
                background.boundsTops.isEmpty(),
            )
            assertTrue(
                "taking the warmed outgoing owner must not add another page-shot lease: " +
                    "before=$leasedBeforeTurn after=${budget.leasedBytes}",
                budget.leasedBytes <= leasedBeforeTurn,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `page shot trim clears stable and inactive owners but preserves continuity cover`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        view.goToPage(1)
        view.preCachePageTexturesForTest()
        val continuityCover = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
        val inactiveBoundary = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
        view.detachCachedTextureOwnerForTest(continuityCover)
        view.detachCachedTextureOwnerForTest(inactiveBoundary)
        val stableShots = listOfNotNull(
            view.privateField("cachedFrontBitmap") as Bitmap?,
            view.privateField("cachedRevealedBitmap") as Bitmap?,
            view.privateField("cachedBackwardBitmap") as Bitmap?,
        )
        assertTrue("fixture requires one remaining stable local page-shot owner", stableShots.isNotEmpty())
        val preview = view.newBoundaryPreviewForTest(
            forward = true,
            token = 52L,
            bitmapOverride = inactiveBoundary,
        )
        assertTrue(view.offerBoundaryPreviewForTest(preview))
        view.showConversionSnapshotForTest(continuityCover)
        val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)

        try {
            view.pausePageShotSpeculationAndTrim()
            budget.evictEvictable().forEach { identity ->
                (identity as? Bitmap)?.let { if (!it.isRecycled) it.recycle() }
            }

            assertNull(view.privateField("cachedFrontBitmap"))
            assertNull(view.privateField("cachedRevealedBitmap"))
            assertNull(view.privateField("cachedBackwardBitmap"))
            assertTrue("trim must recycle every stable local owner", stableShots.all(Bitmap::isRecycled))
            assertNull("trim must clear the inactive boundary slot", view.privateField("forwardBoundaryPreview"))
            assertTrue("trim must recycle the inactive boundary frame", inactiveBoundary.isRecycled)
            val installedCover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertTrue(
                "the visible continuity owner must remain the exact pinned bitmap",
                installedCover.privateBitmap("bitmap") === continuityCover,
            )
            assertFalse("the visible continuity owner must survive speculative eviction", continuityCover.isRecycled)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `discrete simulation boundary uses local paper renderer and transfers target once`() {
        val commits = mutableListOf<Any>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        view.installBoundaryCommitRecorderForTest(commits)
        view.goToLastPage()
        val revealed = view.offerReadyBoundaryPreviewForTest(forward = true, token = 6L)

        try {
            assertTrue(view.startDiscreteBoundaryTurn(1))
            assertNotNull(
                "a boundary tap or key turn must use the same local PAPER drawable as a drag",
                view.privateField("curlDrawable") as PageCurlDrawable?,
            )

            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertTrue(
                "the continuity cover must receive the renderer-owned target without copying it",
                cover.privateBitmap("bitmap") === revealed,
            )
            assertFalse("the transferred target must remain alive", revealed.isRecycled)
            assertEquals("the boundary transaction must publish exactly once", 1, commits.size)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `boundary continuity cover blocks all input after safety reveal until one stable report`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val reportedOffsets = mutableListOf<Int>()
        var linkClicks = 0
        val incomingText = "Open linked chapter only after stability.\n" +
            (1..80).joinToString("\n") { "Incoming chapter line $it remains gated." }
        val linkStart = incomingText.indexOf("linked")
        val incomingSpannable = SpannableString(incomingText).apply {
            setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        linkClicks += 1
                    }
                },
                linkStart,
                linkStart + "linked".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTapZone = tapZones::add,
            onTopOffsetChanged = reportedOffsets::add,
        )
        view.pendingDecodesProvider = { true }
        view.onBoundaryTurnCommitted = {
            view.setChapter(
                flow = incomingFlow,
                spannable = incomingSpannable,
                pageHeightPx = view.height,
                reportPositionAfterStableReveal = true,
            )
        }
        view.goToLastPage()
        reportedOffsets.clear()
        view.offerReadyBoundaryPreviewForTest(forward = true, token = 7L)

        try {
            assertTrue(view.startDiscreteBoundaryTurn(1))
            assertEquals("starting the visual transaction must remain locator-silent", emptyList<Int>(), reportedOffsets)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            assertEquals("committing and installing the hidden target must remain locator-silent", emptyList<Int>(), reportedOffsets)
            view.measure(exactly(360), exactly(120))
            view.layout(0, 0, 360, 120)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(801L, TimeUnit.MILLISECONDS)

            assertEquals("the safety reveal must retire only the hidden flag", false, view.privateBool("awaitingReveal"))
            assertEquals("pending image work must keep the chapter unstable", true, view.privateBool("awaitingStableChapter"))
            assertEquals("the live target may render beneath the frozen owner", 1f, view.getChildAt(0).alpha)
            assertNotNull("the boundary frame must still own the visible window", view.privateField("conversionSnapshotDrawable"))
            assertTrue("the committed boundary cover gate must remain active", view.privateBool("boundaryContinuityCover"))
            assertEquals("safety reveal must not publish target progress early", emptyList<Int>(), reportedOffsets)

            val tapTime = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(
                motionEvent(tapTime, tapTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, view.height * 0.50f),
            )
            view.dispatchTouchEvent(
                motionEvent(tapTime, tapTime + 24L, MotionEvent.ACTION_UP, view.width * 0.85f, view.height * 0.50f),
            )

            val dragTime = tapTime + 40L
            view.dispatchTouchEvent(
                motionEvent(dragTime, dragTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, view.height * 0.10f),
            )
            view.dispatchTouchEvent(
                motionEvent(dragTime, dragTime + 24L, MotionEvent.ACTION_MOVE, view.width * 0.10f, view.height * 0.10f),
            )
            view.dispatchTouchEvent(
                motionEvent(dragTime, dragTime + 48L, MotionEvent.ACTION_UP, view.width * 0.10f, view.height * 0.10f),
            )

            val linkPoint = view.pointForTextOffset(linkStart + 1)
            val linkTime = dragTime + 80L
            view.dispatchTouchEvent(
                motionEvent(linkTime, linkTime, MotionEvent.ACTION_DOWN, linkPoint.first, linkPoint.second),
            )
            view.dispatchTouchEvent(
                motionEvent(linkTime, linkTime + 24L, MotionEvent.ACTION_UP, linkPoint.first, linkPoint.second),
            )

            assertEquals("the cover gate must consume clean page taps", emptyList<EpubFlowTapZone>(), tapZones)
            assertEquals("the cover gate must keep child links inactive", 0, linkClicks)
            assertNull("gated input must not start another slide", view.privateField("slideDrawable"))
            assertNull("gated input must not start a software curl", view.privateField("curlDrawable"))
            assertFalse(
                "gated input must not start a running animator",
                (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.isRunning == true,
            )
            assertEquals("gated input must leave the target parked", 0, view.currentPageIndex())
            assertEquals("gated input must remain silent to locator persistence", emptyList<Int>(), reportedOffsets)

            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("the stable target must publish its locator exactly once", 1, reportedOffsets.size)
            assertEquals("the stable handoff must retire the continuity cover", false, view.privateBool("boundaryContinuityCover"))
        } finally {
            view.pendingDecodesProvider = { false }
            view.dispose()
        }
    }

    @Test
    fun `discrete boundary cache miss timeout releases state for another turn`() {
        val previewRequests = mutableListOf<Pair<Boolean, Long>>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE).apply {
            onBoundaryPreviewNeeded = { forward, generation ->
                previewRequests += forward to generation
            }
        }
        view.goToLastPage()

        try {
            assertTrue(view.startDiscreteBoundaryTurn(1))
            assertEquals("BOUNDARY_DISCRETE_WAITING", view.privateField("interactiveTurnState").toString())
            assertEquals(1, previewRequests.size)

            shadowOf(Looper.getMainLooper()).idleFor(3_001L, TimeUnit.MILLISECONDS)

            assertEquals(
                "a cold preview timeout must release turnInFlight",
                "NONE",
                view.privateField("interactiveTurnState").toString(),
            )
            assertTrue("the reader must accept another turn after timeout", view.startDiscreteBoundaryTurn(1))
            assertEquals("the retry must request a fresh preview", 2, previewRequests.size)
            assertEquals("BOUNDARY_DISCRETE_WAITING", view.privateField("interactiveTurnState").toString())

            view.clearBoundaryPreviews()

            assertEquals(
                "settings invalidation must also release an active wait",
                "NONE",
                view.privateField("interactiveTurnState").toString(),
            )
            assertTrue("clearing previews must not permanently lock navigation", view.startDiscreteBoundaryTurn(1))
            assertEquals(3, previewRequests.size)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `width only resize rejects a preview produced for the old viewport token`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        val stalePreview = view.newBoundaryPreviewForTest(forward = true, token = 41L)

        view.measure(exactly(420), exactly(120))
        view.layout(0, 0, 420, 120)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "a frame rendered at 360px wide must not be accepted after a width-only resize to 420px",
            view.offerBoundaryPreviewForTest(stalePreview),
        )
        assertTrue("the rejected stale viewport frame must be recycled", stalePreview.bitmap.isRecycled)
    }

    @Test
    fun `width resize and highlight refresh invalidate warmed local texture owners`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        view.preCachePageTexturesForTest()
        val originalFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
        val originalTarget = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
        val originalFrontKey = checkNotNull(view.privateField("cachedFromTextureKey"))
        val originalTargetKey = checkNotNull(view.privateField("cachedTargetTextureKey"))

        view.measure(exactly(420), exactly(120))
        view.layout(0, 0, 420, 120)
        shadowOf(Looper.getMainLooper()).idle()
        view.preCachePageTexturesForTest()

        val resizedFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
        val resizedTarget = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
        val resizedFrontKey = checkNotNull(view.privateField("cachedFromTextureKey"))
        val resizedTargetKey = checkNotNull(view.privateField("cachedTargetTextureKey"))
        assertTrue("width-only resize must recycle the old front texture", originalFront.isRecycled)
        assertTrue("width-only resize must recycle the old target texture", originalTarget.isRecycled)
        assertTrue("width-only resize must allocate a new front texture owner", resizedFront !== originalFront)
        assertTrue("width-only resize must allocate a new target texture owner", resizedTarget !== originalTarget)
        assertTrue("width-only resize must not preserve the old front key", resizedFrontKey != originalFrontKey)
        assertTrue("width-only resize must not preserve the old target key", resizedTargetKey != originalTargetKey)

        view.refreshHighlights(emptyList())

        assertTrue("highlight refresh must recycle the current front texture", resizedFront.isRecycled)
        assertTrue("highlight refresh must recycle the current target texture", resizedTarget.isRecycled)
        assertNull("highlight refresh must clear the current front key", view.privateField("cachedFromTextureKey"))
        assertNull("highlight refresh must clear the current target key", view.privateField("cachedTargetTextureKey"))
        view.preCachePageTexturesForTest()
        assertTrue(
            "highlight refresh must rebuild a new front bitmap before the next local turn",
            checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?) !== resizedFront,
        )
        assertTrue(
            "highlight refresh must rebuild a new target bitmap before the next local turn",
            checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?) !== resizedTarget,
        )
    }

    @Test
    fun `cancelled discrete paper turn restores the exact free rest viewport`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.75f)

        view.scrollTo(0, freeRest)

        assertTrue(view.goToAdjacentPage(1))
        (view.privateField("flipAnimator") as android.animation.ValueAnimator).cancel()

        assertEquals("cancel must restore the original free-rest pixels", freeRest, view.scrollY)
    }

    @Test
    fun `committed discrete paper turn continues after the last full line at free rest`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.75f)

        view.scrollTo(0, freeRest)
        val before = fullLineRangeInViewport(view)

        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        val after = fullLineRangeInViewport(view)

        assertEquals(
            "discrete commit must reveal the first line after the outgoing viewport's last full line",
            before.last + 1,
            after.first,
        )
    }

    @Test
    fun `discrete paper turn reaches and snapshots the final page raw line top`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val target = view.pageCount() - 1
        val rawTargetTop = requireNotNull(view.pageTopPxAt(target))
        val naturalMaxScroll = (view.textView.height - view.height).coerceAtLeast(0)
        assertTrue(
            "test requires tail extent beyond the chapter's natural max scroll",
            rawTargetTop > naturalMaxScroll,
        )
        val from = target - 1
        view.goToPage(from)
        val fromVisualTop = view.scrollY

        assertEquals(fromVisualTop, view.textureTopPxForPageForTest(from))
        assertEquals(
            "tail extent must make the last paginator line-top a real scroll and texture coordinate",
            rawTargetTop,
            view.textureTopPxForPageForTest(target),
        )

        assertTrue(view.goToAdjacentPage(1))
        assertEquals("the live final page must park on its raw line top", rawTargetTop, view.scrollY)

        val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
        assertEquals(view.width, paper.privateBitmap("frontBitmap").width)
        assertEquals(view.height, paper.privateBitmap("frontBitmap").height)
    }

    @Test
    fun `settled live and snapshot pages share measured padding aware full line bounds`() {
        val view = pagedFlowView(textPaddingTop = 12, textPaddingBottom = 9)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.40f)
        view.scrollTo(0, freeRest)
        view.setPrivateField("pageClipActive", true)
        val fullLines = fullLineRangeInViewport(view)
        val expectedTop = layout.getLineTop(fullLines.first) + view.textView.paddingTop
        val expectedBottom = layout.getLineBottom(fullLines.last) + view.textView.paddingTop

        assertEquals(
            "settled live rendering must exclude the partial line above the padded content band",
            expectedTop,
            view.pageClipTopForTest(),
        )
        assertEquals(
            "snapshot top must use the same first complete line as live rendering",
            expectedTop,
            view.snapshotClipTopForTest(freeRest),
        )
        assertEquals(
            "settled live rendering must stop at the last line above measured bottom padding",
            expectedBottom,
            freeRest + requireNotNull(view.pageClipBottomForTest()) + view.textView.paddingTop,
        )
        assertEquals(
            "snapshot bottom must use the same last complete line as live rendering",
            expectedBottom,
            view.snapshotClipBottomForTest(freeRest),
        )
    }

    @Test
    fun `padding aware free rest top keeps a line whose painted top is below the viewport edge`() {
        val view = pagedFlowView(textPaddingTop = 12, textPaddingBottom = 9)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val layout = requireNotNull(view.textView.layout)
        val line = layout.getLineForVertical(requireNotNull(view.pageTopPxAt(1)))
        val lineTop = layout.getLineTop(line)
        val paintedLineTop = lineTop + view.textView.paddingTop
        val freeRest = lineTop + view.textView.paddingTop / 2
        assertTrue(
            "the selected line must be fully visible because TextView paints it below FREE_REST's top edge",
            paintedLineTop >= freeRest,
        )

        view.scrollTo(0, freeRest)
        view.setPrivateField("pageClipActive", true)

        assertEquals(
            "live clipping must keep the first line whose painted top is inside the viewport",
            paintedLineTop,
            view.pageClipTopForTest(),
        )
        assertEquals(
            "snapshot clipping must keep the same fully visible line as live rendering",
            paintedLineTop,
            view.snapshotClipTopForTest(freeRest),
        )
    }

    @Test
    fun `aligned page with padding taller than a line clips exactly at its window painted top`() {
        val view = pagedFlowView(textPaddingTop = 48)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val layout = requireNotNull(view.textView.layout)
        val firstLineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
        assertTrue("test requires top padding taller than one line", view.textView.paddingTop > firstLineHeight)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))

        view.goToPage(1)

        val activeWindow = checkNotNull(view.privateField("activePageWindow") as EpubFlowPage?)
        assertEquals("test must park the aligned window at page one", pageOneTop, activeWindow.topPx)
        val expectedPaintedTop = activeWindow.topPx + view.textView.paddingTop
        assertEquals(
            "aligned live clipping must never expose an earlier complete line through oversized top padding",
            expectedPaintedTop,
            view.pageClipTopForTest(),
        )
        assertEquals(
            "aligned snapshot clipping must start at the exact same painted window top",
            expectedPaintedTop,
            view.snapshotClipTopForTest(activeWindow.topPx),
        )
    }

    @Test
    fun `free rest locator starts at the first fully visible painted line`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            onTopOffsetChanged = reportedOffsets::add,
            textPaddingTop = 12,
            textPaddingBottom = 9,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val layout = requireNotNull(view.textView.layout)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = ((pageOneTop + 1) until pageTwoTop).first { y ->
            val viewportTopInLayout = (y - view.textView.paddingTop).coerceAtLeast(0)
            val intersectingLine = layout.getLineForVertical(viewportTopInLayout)
            val firstFullLine = (intersectingLine + 1).coerceAtMost(layout.lineCount - 1)
            layout.getLineTop(intersectingLine) < viewportTopInLayout &&
                layout.getLineForVertical(y) != firstFullLine
        }
        val viewportTopInLayout = (freeRest - view.textView.paddingTop).coerceAtLeast(0)
        val intersectingLine = layout.getLineForVertical(viewportTopInLayout)
        val firstFullLine = (intersectingLine + 1).coerceAtMost(layout.lineCount - 1)
        val expectedOffset = layout.getLineStart(firstFullLine)

        view.scrollTo(0, freeRest)
        reportedOffsets.clear()
        view.settleTemporaryScrollAnchorForTest()

        assertEquals(
            "FREE_REST locator must match the same first complete line selected by the painted top clip",
            expectedOffset,
            view.topLayoutOffset(),
        )
        assertEquals("FREE_REST release must publish that complete-line locator", listOf(expectedOffset), reportedOffsets)
    }

    @Test
    fun `page turn snapshots keep paper background anchored to viewport`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        assertTrue("test needs a non-zero page top", pageOneTop > 0)
        val background = RecordingBoundsTopDrawable()
        view.background = background

        view.goToPage(1)
        view.snapshotViewportForTest()
        view.snapshotPageAt(pageOneTop)

        assertTrue(
            "page-turn snapshots must draw paper in viewport coordinates, recorded=${background.boundsTops}",
            background.boundsTops.isNotEmpty() && background.boundsTops.all { it == 0 },
        )
    }

    @Test
    fun `page turn snapshot preserves exact paper colour under the finger`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = android.graphics.drawable.ColorDrawable(0xFFEDE6D6.toInt())
        view.goToPage(1)
        val staticFrame = view.drawToBitmapForTest()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val fingerDownFrame = view.drawToBitmapForTest()
        val snapshot = requireNotNull(view.snapshotViewportForTest())

        assertEquals(
            "MoonReader high-quality page shots keep textured paper from changing when a turn layer appears",
            Bitmap.Config.ARGB_8888,
            snapshot.config,
        )
        assertEquals(
            "the first interactive turn frame must not quantize the static paper colour",
            staticFrame.getPixel(4, 4),
            fingerDownFrame.getPixel(4, 4),
        )

        staticFrame.recycle()
        fingerDownFrame.recycle()
        snapshot.recycle()
    }

    @Test
    fun `first slide frame preserves scrolled paper texture under the finger`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = StripedPaperDrawable()
        view.goToPage(1)
        val staticFrame = view.drawAsScrolledChildToBitmapForTest()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val fingerDownFrame = view.drawAsScrolledChildToBitmapForTest()
        val x = view.width - 4
        val y = 4

        assertEquals(
            "pressing into the turn layer must not shift the paper-grain phase from the static page",
            staticFrame.getPixel(x, y),
            fingerDownFrame.getPixel(x, y),
        )

        staticFrame.recycle()
        fingerDownFrame.recycle()
    }

    @Test
    fun `first slide frame preserves real paper texture under the finger`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = readerPaperBackground(
            context = context,
            paperColor = 0xFFEDE6D6.toInt(),
            inkColor = 0xFF2A2620.toInt(),
            isNight = false,
        )
        view.goToPage(1)
        val staticFrame = view.drawAsScrolledChildToBitmapForTest()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val fingerDownFrame = view.drawAsScrolledChildToBitmapForTest()
        val x = view.width - 4
        val y = 4

        assertEquals(
            "the real paper-grain drawable should not change when the first turn layer appears",
            staticFrame.getPixel(x, y),
            fingerDownFrame.getPixel(x, y),
        )

        staticFrame.recycle()
        fingerDownFrame.recycle()
    }

    @Test
    fun `first slide frame preserves real paper texture across the viewport`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = readerPaperBackground(
            context = context,
            paperColor = 0xFFEDE6D6.toInt(),
            inkColor = 0xFF2A2620.toInt(),
            isNight = false,
        )
        view.textView.setTextColor(0x00000000)
        view.goToPage(1)
        val staticFrame = view.drawAsScrolledChildToBitmapForTest()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val fingerDownFrame = view.drawAsScrolledChildToBitmapForTest()

        assertSampledPixelsEqual(
            "the first turn layer must reuse the exact same paper texture as the static page",
            staticFrame,
            fingerDownFrame,
        )

        staticFrame.recycle()
        fingerDownFrame.recycle()
    }

    @Test
    fun `action down alone preserves real paper texture`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = readerPaperBackground(
            context = context,
            paperColor = 0xFFEDE6D6.toInt(),
            inkColor = 0xFF2A2620.toInt(),
            isNight = false,
        )
        view.textView.setTextColor(0x00000000)
        view.goToPage(1)
        val staticFrame = view.drawAsScrolledChildToBitmapForTest()
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        val pressedFrame = view.drawAsScrolledChildToBitmapForTest()

        assertSampledPixelsEqual(
            "ACTION_DOWN must not tint or shift the reader paper before any turn is committed",
            staticFrame,
            pressedFrame,
        )

        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_CANCEL, x, y))
        staticFrame.recycle()
        pressedFrame.recycle()
    }

    @Test
    fun `interactive slide moves incoming paper as a page shot`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = XRampPaperDrawable()
        view.textView.setTextColor(0x00000000)
        view.goToPage(1)
        val staticFrame = view.drawAsScrolledChildToBitmapForTest()
        val sampleX = view.width * 3 / 4
        val sampleY = 4
        val expectedSourceX = sampleX - view.width / 2
        assertTrue(
            "test requires paper columns with different colours",
            staticFrame.getPixel(sampleX, sampleY) != staticFrame.getPixel(expectedSourceX, sampleY),
        )

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width / 2f)
        val slide = view.privateField("slideDrawable") as PageSlideDrawable
        assertEquals("test must sample a half-complete slide", 0.5f, slide.progress, 0.001f)
        val revealedBitmap = slide.privateBitmap("revealedBitmap")
        assertEquals(
            "revealed page shot should preserve the paper column at the source x",
            staticFrame.getPixel(expectedSourceX, sampleY),
            revealedBitmap.getPixel(expectedSourceX, sampleY),
        )
        assertEquals(
            "the incoming half-page should carry its captured paper texture, not reveal the static host background",
            expectedSourceX,
            slide.incomingSourceXForViewportX(sampleX),
        )

        staticFrame.recycle()
    }

    @Test
    fun `first threshold crossing move uses the full down displacement for interactive slide progress`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val downX = view.width * 0.85f
        val moveX = view.width * 0.55f
        val y = view.height * 0.50f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        assertTrue(
            view.onInterceptTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
            ),
        )

        try {
            val slide = view.privateField("slideDrawable") as PageSlideDrawable?
            assertNotNull("the first threshold-crossing MOVE should start the interactive slide", slide)
            val expectedProgress = (downX - moveX) / view.width.toFloat()
            assertEquals(
                "the first interactive frame must include all finger travel since ACTION_DOWN",
                expectedProgress,
                checkNotNull(slide).progress,
                0.001f,
            )
        } finally {
            view.onTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y),
            )
        }
    }

    @Test
    fun `warmed interactive turn takes cached page shots without recapturing them on first move`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.preCachePageTexturesForTest()
        val cachedFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
        val cachedRevealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))

        try {
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertTrue(
                "the crossing MOVE must transfer the warmed current-page shot instead of allocating a replacement",
                slide.privateBitmap("frontBitmap") === cachedFront,
            )
            assertTrue(
                "the crossing MOVE must transfer the warmed next-page shot instead of drawing the view again",
                slide.privateBitmap("revealedBitmap") === cachedRevealed,
            )
            assertNull("the active drawable now owns the front shot", view.privateField("cachedFrontBitmap"))
            assertNull("the active drawable now owns the revealed shot", view.privateField("cachedRevealedBitmap"))
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `one shot soft budget still admits an interactive working pair`() {
        val oneShotBytes = 360L * 120L * 4L
        val budget = PageShotBudget(oneShotBytes)
        var pinnedAdmissionRequests = 0
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            pageShotBudget = budget,
            onPinnedPageShotAdmissionNeeded = { pinnedAdmissionRequests += 1 },
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)

        try {
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val front = slide.privateBitmap("frontBitmap")
            val revealed = slide.privateBitmap("revealedBitmap")
            assertTrue("the active working frames must have distinct owners", front !== revealed)
            assertFalse("the outgoing working frame must remain live", front.isRecycled)
            assertFalse("the target working frame must remain live", revealed.isRecycled)
            assertEquals(
                "a one-shot soft limit may be exceeded only by the two pinned working frames",
                oneShotBytes * 2L,
                budget.leasedBytes,
            )
            assertEquals(
                "the second working frame must exercise over-cap pinned admission",
                1,
                pinnedAdmissionRequests,
            )

            view.updateInteractiveCurl(x = view.width * 0.25f)
            assertEquals(
                "the admitted pair must remain finger-animatable",
                0.75f,
                slide.progress,
                0.001f,
            )
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `active boundary preview survives pinned outgoing admission eviction`() {
        val budget = PageShotBudget(1L)
        val cancelledDirections = mutableListOf<Boolean>()
        val evictedPreviews = mutableListOf<BoundaryPagePreview>()
        var pinnedAdmissionRequests = 0
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            pageShotBudget = budget,
            onPinnedPageShotAdmissionNeeded = { pinnedAdmissionRequests += 1 },
        ).apply {
            onBoundaryPreviewRequestCancelled = cancelledDirections::add
            onBoundaryPreviewEvicted = evictedPreviews::add
        }
        view.goToLastPage()
        assertTrue(view.preparePageShotBudgetForBoundaryPreview(forward = true, required = true))
        val target = view.offerReadyBoundaryPreviewForTest(forward = true, token = 53L)

        try {
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val active = checkNotNull(view.privateField("activeBoundaryPreview") as BoundaryPagePreview?)
            assertTrue("the boundary turn must retain the offered target owner", active.bitmap === target)
            assertFalse("the active boundary target must remain live", target.isRecycled)
            assertEquals("the outgoing shot must force owner-aware pinned admission", 1, pinnedAdmissionRequests)
            assertTrue(
                "pinned outgoing admission must preserve the direction already owned by the active target; " +
                    "cancelled=$cancelledDirections evicted=${evictedPreviews.map(BoundaryPagePreview::token)} " +
                    "budgetDirection=${view.privateField("boundaryPreviewBudgetDirection")}",
                cancelledDirections.isEmpty() &&
                    evictedPreviews.isEmpty() &&
                    view.privateField("boundaryPreviewBudgetDirection") == true,
            )
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `deferred local pair evicts inactive boundary before a fourth shot while cover stays visible`() {
        val shotBytes = 360L * 120L * 4L
        val budget = PageShotBudget(shotBytes * 3L)
        val evictedTokens = mutableListOf<Long>()
        val chargedSamples = mutableListOf<Long>()
        var coverAliveWhenBoundaryEvicted = false
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            pageShotBudget = budget,
        )
        view.goToPage(1)
        view.recycleCachedTexturesForTest()
        val cover = requireNotNull(view.snapshotPageAt(view.scrollY))
        view.showConversionSnapshotForTest(cover)
        val inactiveBoundaryBitmap = requireNotNull(view.snapshotPageAt(view.scrollY))
        val inactiveBoundary = BoundaryPagePreview(
            token = 55L,
            forward = true,
            sourceChapterGeneration = view.boundaryPreviewGenerationToken(),
            bitmap = inactiveBoundaryBitmap,
        )
        view.onBoundaryPreviewEvicted = { preview ->
            evictedTokens += preview.token
            chargedSamples += budget.chargedBytes
            val installedCover = view.privateField("conversionSnapshotDrawable")
            coverAliveWhenBoundaryEvicted =
                installedCover != null &&
                installedCover.privateBitmap("bitmap") === cover &&
                !cover.isRecycled
        }
        assertTrue(view.offerBoundaryPreviewForTest(inactiveBoundary))
        val downX = view.width * 0.85f
        val moveX = view.width * 0.55f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )
            assertEquals("LOCAL_SHOTS_WAITING", view.privateField("interactiveTurnState").toString())
            chargedSamples += budget.chargedBytes

            shadowOf(Looper.getMainLooper()).runOneTask()

            val staged = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            val stagedTarget = checkNotNull(staged.reflectedField("targetBitmap") as Bitmap?)
            chargedSamples += budget.chargedBytes
            assertFalse("the deferred target must remain live", stagedTarget.isRecycled)
            assertFalse("the visible cover must remain installed during target preparation", cover.isRecycled)
            assertEquals("target admission must evict the inactive boundary first", listOf(inactiveBoundary.token), evictedTokens)
            assertTrue("inactive-boundary eviction must observe the visible cover still alive", coverAliveWhenBoundaryEvicted)
            assertTrue("the inactive boundary must leave before the local working pair completes", inactiveBoundaryBitmap.isRecycled)
            assertNull(view.privateField("forwardBoundaryPreview"))
            assertTrue(budget.leasedBytes <= shotBytes * 3L)

            shadowOf(Looper.getMainLooper()).runOneTask()
            chargedSamples += budget.chargedBytes

            assertEquals(listOf(inactiveBoundary.token), evictedTokens)
            assertNotNull("the deferred pair must become the active local renderer", view.privateField("slideDrawable"))
            assertTrue(
                "cover + inactive boundary + deferred pair must never charge a fourth full-screen identity: " +
                    chargedSamples,
                chargedSamples.all { it <= shotBytes * 3L },
            )
        } finally {
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `warmed forward turn from free rest transfers dynamic page shots without live recapture`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.40f)
        val canonicalTops = (0 until view.pageCount()).mapNotNull(view::pageTopPxAt).toSet()

        view.scrollTo(0, freeRest)
        val outgoingFullLines = fullLineRangeInViewport(view)
        val dynamicTargetTop = requireNotNull(view.textView.layout).getLineTop(outgoingFullLines.last + 1)
        assertTrue("test requires a non-canonical FREE_REST", freeRest !in canonicalTops)
        assertTrue("test requires a non-canonical dynamic forward target", dynamicTargetTop !in canonicalTops)

        val background = RecordingBoundsTopDrawable()
        view.background = background
        view.preCachePageTexturesForTest()
        val cachedFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?) {
            "FREE_REST precache must warm the arbitrary outgoing viewport"
        }
        val cachedRevealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?) {
            "FREE_REST precache must warm its dynamic forward target"
        }
        background.boundsTops.clear()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))

        try {
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val frontTransferred = slide.privateBitmap("frontBitmap") === cachedFront
            val targetTransferred = slide.privateBitmap("revealedBitmap") === cachedRevealed
            val liveDrawBounds = background.boundsTops.toList()
            assertTrue(
                "the first interactive frame must transfer both warmed FREE_REST shots by identity " +
                    "without live recapture; frontTransferred=$frontTransferred " +
                    "targetTransferred=$targetTransferred liveDrawBounds=$liveDrawBounds",
                frontTransferred && targetTransferred && liveDrawBounds.isEmpty(),
            )
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `cold free rest simulation drag resumes at latest held move after split page shot preparation`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.40f)
        view.scrollTo(0, freeRest)
        val outgoingFullLines = fullLineRangeInViewport(view)
        val targetLine = outgoingFullLines.last + 1
        val layout = requireNotNull(view.textView.layout)
        val targetTop = layout.getLineTop(targetLine)
        val targetOffset = layout.getLineStart(targetLine)
        val startPage = view.currentPageIndex()
        val background = RecordingBoundsTopDrawable()
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.boundsTops.clear()
        reportedOffsets.clear()

        val downX = view.width * 0.85f
        val firstCrossingX = view.width * 0.55f
        val latestHeldX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()
        var released = false

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, firstCrossingX, y),
                ),
            )
            view.onTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_MOVE, latestHeldX, y),
            )

            assertTrue(
                "a cold threshold MOVE must enqueue page-shot work instead of synchronously drawing or turning; " +
                    "draws=${background.boundsTops} curl=${view.privateField("curlDrawable")} " +
                    "page=${view.currentPageIndex()} scrollY=${view.scrollY} reports=$reportedOffsets",
                background.boundsTops.isEmpty() &&
                    view.privateField("curlDrawable") == null &&
                    view.currentPageIndex() == startPage &&
                    view.scrollY == freeRest &&
                    reportedOffsets.isEmpty(),
            )

            shadowOf(Looper.getMainLooper()).runOneTask()

            assertEquals("the first preparation frame must capture only the target page", 1, background.boundsTops.size)
            assertNull("the target-only frame must not install an overlay", view.privateField("curlDrawable"))
            assertEquals("preparation must not park the target page", startPage, view.currentPageIndex())
            assertEquals("preparation must not move the live viewport", freeRest, view.scrollY)
            assertTrue("preparation must remain locator-silent", reportedOffsets.isEmpty())

            shadowOf(Looper.getMainLooper()).runOneTask()

            assertEquals("the second preparation frame must capture the visible outgoing page", 2, background.boundsTops.size)
            val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
            val expectedProgress = (downX - latestHeldX) / view.width.toFloat()
            assertEquals(
                "once both shots are ready the held gesture must resume at the latest MOVE without another event",
                expectedProgress,
                paper.progress,
                0.001f,
            )
            assertTrue("resuming under the held finger must remain locator-silent", reportedOffsets.isEmpty())

            view.onTouchEvent(
                motionEvent(downTime, downTime + 600L, MotionEvent.ACTION_UP, latestHeldX, y),
            )
            released = true
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

            assertEquals(
                "release without another MOVE must commit from the latest held coordinate, not the first sub-threshold progress",
                targetTop,
                view.scrollY,
            )
            assertEquals(listOf(targetOffset), reportedOffsets)
        } finally {
            if (!released) {
                view.onTouchEvent(
                    motionEvent(downTime, downTime + 600L, MotionEvent.ACTION_CANCEL, latestHeldX, y),
                )
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            }
            view.dispose()
        }
    }

    @Test
    fun `up after first cold target frame cancels handoff and retires late front work`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val background = RecordingTargetBitmapDrawable()
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        reportedOffsets.clear()
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        val startOffset = view.topLayoutOffset()
        val downX = view.width * 0.85f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )
            shadowOf(Looper.getMainLooper()).runOneTask()
            val partialTarget = background.targetBitmaps.single()
            val overlayBeforeUp =
                view.privateField("curlDrawable") != null || view.privateField("slideDrawable") != null

            view.onTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y),
            )
            val stateAfterUp = view.privateField("interactiveTurnState").toString()
            val pageAfterUp = view.currentPageIndex()
            val scrollAfterUp = view.scrollY
            val offsetAfterUp = view.topLayoutOffset()
            val reportsAfterUp = reportedOffsets.toList()

            shadowOf(Looper.getMainLooper()).runOneTask()
            val drawsAfterLateFront = background.targetBitmaps.toList()
            val curlAfterLateFront = view.privateField("curlDrawable")
            val slideAfterLateFront = view.privateField("slideDrawable")
            val stateAfterLateFront = view.privateField("interactiveTurnState").toString()
            val pageAfterLateFront = view.currentPageIndex()
            val scrollAfterLateFront = view.scrollY
            val offsetAfterLateFront = view.topLayoutOffset()
            val reportsAfterLateFront = reportedOffsets.toList()

            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

            val finalCurl = view.privateField("curlDrawable")
            val finalSlide = view.privateField("slideDrawable")
            val finalState = view.privateField("interactiveTurnState").toString()

            assertTrue(
                "UP before the front frame must retire the partial target and make late work inert; " +
                    "draws=${background.targetBitmaps.size} partialTarget=$partialTarget " +
                    "partialRecycled=${partialTarget.isRecycled} " +
                    "overlayBeforeUp=$overlayBeforeUp finalCurl=$finalCurl finalSlide=$finalSlide " +
                    "stateAfterUp=$stateAfterUp pageAfterUp=$pageAfterUp scrollAfterUp=$scrollAfterUp " +
                    "offsetAfterUp=$offsetAfterUp reportsAfterUp=$reportsAfterUp " +
                    "drawsAfterLateFront=$drawsAfterLateFront curlAfterLateFront=$curlAfterLateFront " +
                    "slideAfterLateFront=$slideAfterLateFront stateAfterLateFront=$stateAfterLateFront " +
                    "pageAfterLateFront=$pageAfterLateFront scrollAfterLateFront=$scrollAfterLateFront " +
                    "offsetAfterLateFront=$offsetAfterLateFront reportsAfterLateFront=$reportsAfterLateFront " +
                    "page=${view.currentPageIndex()} startPage=$startPage " +
                    "scrollY=${view.scrollY} startTop=$startTop " +
                    "offset=${view.topLayoutOffset()} startOffset=$startOffset " +
                    "state=$finalState reports=$reportedOffsets",
                background.targetBitmaps.size == 1 && partialTarget.isRecycled &&
                    !overlayBeforeUp && finalCurl == null && finalSlide == null &&
                    stateAfterUp == "NONE" && pageAfterUp == startPage && scrollAfterUp == startTop &&
                    offsetAfterUp == startOffset && reportsAfterUp.isEmpty() &&
                    drawsAfterLateFront.size == 1 && curlAfterLateFront == null && slideAfterLateFront == null &&
                    stateAfterLateFront == "NONE" && pageAfterLateFront == startPage &&
                    scrollAfterLateFront == startTop && offsetAfterLateFront == startOffset &&
                    reportsAfterLateFront.isEmpty() &&
                    view.currentPageIndex() == startPage &&
                    view.scrollY == startTop &&
                    view.topLayoutOffset() == startOffset &&
                    finalState == "NONE" &&
                    reportedOffsets.isEmpty(),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `new down supersedes a target-only cold handoff and only the replacement gesture resumes`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(2)
        val background = RecordingTargetBitmapDrawable()
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        reportedOffsets.clear()

        val y = view.height * 0.10f
        val aDownX = view.width * 0.85f
        val aMoveX = view.width * 0.55f
        val aDownTime = SystemClock.uptimeMillis()
        val bDownX = view.width * 0.15f
        val bFirstMoveX = view.width * 0.45f
        val bLatestMoveX = view.width * 0.90f
        val bDownTime = aDownTime + 100L
        val expectedBProgress = (bLatestMoveX - bDownX) / view.width.toFloat()

        try {
            view.dispatchTouchEvent(motionEvent(aDownTime, aDownTime, MotionEvent.ACTION_DOWN, aDownX, y))
            assertTrue(
                "gesture A must enter the cold forward handoff",
                view.onInterceptTouchEvent(
                    motionEvent(aDownTime, aDownTime + 24L, MotionEvent.ACTION_MOVE, aMoveX, y),
                ),
            )
            shadowOf(Looper.getMainLooper()).runOneTask()
            val aTarget = background.targetBitmaps.single()
            assertNull("gesture A target-only frame must not install curl", view.privateField("curlDrawable"))
            assertNull("gesture A target-only frame must not install slide", view.privateField("slideDrawable"))

            view.dispatchTouchEvent(motionEvent(bDownTime, bDownTime, MotionEvent.ACTION_DOWN, bDownX, y))
            val bOriginPage = view.currentPageIndex()
            val bOriginTop = view.scrollY
            val bOriginOffset = view.topLayoutOffset()

            assertTrue(
                "a new DOWN must synchronously retire gesture A before gesture B is classified; " +
                    "aTargetRecycled=${aTarget.isRecycled} state=${view.privateField("interactiveTurnState")} " +
                    "curl=${view.privateField("curlDrawable")} slide=${view.privateField("slideDrawable")} " +
                    "reports=$reportedOffsets",
                aTarget.isRecycled &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("curlDrawable") == null &&
                    view.privateField("slideDrawable") == null &&
                    reportedOffsets.isEmpty(),
            )

            assertTrue(
                "gesture B must classify an opposite backward turn from its own anchor",
                view.onInterceptTouchEvent(
                    motionEvent(bDownTime, bDownTime + 24L, MotionEvent.ACTION_MOVE, bFirstMoveX, y),
                ),
            )
            view.onTouchEvent(
                motionEvent(bDownTime, bDownTime + 48L, MotionEvent.ACTION_MOVE, bLatestMoveX, y),
            )

            shadowOf(Looper.getMainLooper()).runOneTask()
            assertTrue(
                "the first replacement frame must belong to B's target, not A's late front callback; " +
                    "draws=${background.targetBitmaps.size} state=${view.privateField("interactiveTurnState")} " +
                    "curl=${view.privateField("curlDrawable")} slide=${view.privateField("slideDrawable")}",
                background.targetBitmaps.size == 2 &&
                    view.privateField("interactiveTurnState").toString() == "LOCAL_SHOTS_WAITING" &&
                    view.privateField("curlDrawable") == null &&
                    view.privateField("slideDrawable") == null,
            )

            shadowOf(Looper.getMainLooper()).runOneTask()
            val bPaper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?) {
                "gesture B's second frame must install the SIMULATION overlay"
            }
            val bPaperForward = bPaper.javaClass.getDeclaredField("forward")
                .apply { isAccessible = true }
                .getBoolean(bPaper)
            assertNull("gesture B must not degrade to a slide", view.privateField("slideDrawable"))
            assertEquals("only B's two shots may follow A's retired target", 3, background.targetBitmaps.size)
            assertFalse("the surviving overlay must use gesture B's backward direction", bPaperForward)
            assertEquals(
                "the surviving overlay must resume from gesture B's latest MOVE and anchor",
                expectedBProgress,
                bPaper.progress,
                0.001f,
            )
            assertTrue("the replacement handoff must remain locator-silent", reportedOffsets.isEmpty())

            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            assertTrue(
                "draining stale and replacement frame work must leave exactly B's overlay and progress; " +
                    "draws=${background.targetBitmaps.size} curl=${view.privateField("curlDrawable")} " +
                    "progress=${bPaper.progress} reports=$reportedOffsets",
                background.targetBitmaps.size == 3 &&
                    view.privateField("curlDrawable") === bPaper &&
                    view.privateField("slideDrawable") == null &&
                    !bPaperForward &&
                    kotlin.math.abs(bPaper.progress - expectedBProgress) < 0.001f &&
                    reportedOffsets.isEmpty(),
            )

            view.onTouchEvent(
                motionEvent(bDownTime, bDownTime + 72L, MotionEvent.ACTION_CANCEL, bLatestMoveX, y),
            )
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

            assertTrue(
                "CANCEL must restore gesture B's origin without letting A publish or revive; " +
                    "origin=[$bOriginPage,$bOriginTop,$bOriginOffset] " +
                    "final=[${view.currentPageIndex()},${view.scrollY},${view.topLayoutOffset()}] " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "curl=${view.privateField("curlDrawable")} slide=${view.privateField("slideDrawable")} " +
                    "reports=$reportedOffsets",
                view.currentPageIndex() == bOriginPage &&
                    view.scrollY == bOriginTop &&
                    view.topLayoutOffset() == bOriginOffset &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("curlDrawable") == null &&
                    view.privateField("slideDrawable") == null &&
                    reportedOffsets.isEmpty(),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `dequeued local target callback cannot overwrite the replacement handoff target`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(2)
        val background = RecordingTargetBitmapDrawable()
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        reportedOffsets.clear()

        val y = view.height * 0.10f
        val aDownX = view.width * 0.85f
        val aMoveX = view.width * 0.55f
        val aDownTime = SystemClock.uptimeMillis()
        val bDownX = view.width * 0.15f
        val bMoveX = view.width * 0.75f
        val bDownTime = aDownTime + 100L

        try {
            view.dispatchTouchEvent(motionEvent(aDownTime, aDownTime, MotionEvent.ACTION_DOWN, aDownX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(aDownTime, aDownTime + 24L, MotionEvent.ACTION_MOVE, aMoveX, y),
                ),
            )
            val dequeuedATarget = view.privateField("capturePendingLocalTargetRunnable") as Runnable

            view.dispatchTouchEvent(motionEvent(bDownTime, bDownTime, MotionEvent.ACTION_DOWN, bDownX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(bDownTime, bDownTime + 24L, MotionEvent.ACTION_MOVE, bMoveX, y),
                ),
            )
            val bRequest = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            shadowOf(Looper.getMainLooper()).runOneTask()
            val bTarget = checkNotNull(bRequest.reflectedField("targetBitmap") as Bitmap?)
            val drawsBeforeLateA = background.targetBitmaps.toList()

            dequeuedATarget.run()

            assertTrue(
                "an already-dequeued A target callback must not read or overwrite replacement B; " +
                    "sameOwner=${view.privateField("pendingLocalPageShotHandoff") === bRequest} " +
                    "sameTarget=${bRequest.reflectedField("targetBitmap") === bTarget} " +
                    "drawsBefore=$drawsBeforeLateA drawsAfter=${background.targetBitmaps} " +
                    "targetRecycled=${bTarget.isRecycled} state=${view.privateField("interactiveTurnState")} " +
                    "curl=${view.privateField("curlDrawable")} slide=${view.privateField("slideDrawable")} " +
                    "reports=$reportedOffsets",
                view.privateField("pendingLocalPageShotHandoff") === bRequest &&
                    bRequest.reflectedField("targetBitmap") === bTarget &&
                    background.targetBitmaps == drawsBeforeLateA &&
                    !bTarget.isRecycled &&
                    view.privateField("interactiveTurnState").toString() == "LOCAL_SHOTS_WAITING" &&
                    view.privateField("curlDrawable") == null &&
                    view.privateField("slideDrawable") == null &&
                    reportedOffsets.isEmpty(),
            )
        } finally {
            view.onTouchEvent(
                motionEvent(bDownTime, bDownTime + 48L, MotionEvent.ACTION_CANCEL, bMoveX, y),
            )
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `local snapshot invalidated during draw cannot retire its replacement handoff`() {
        lateinit var view: EpubFlowView
        var armed = false
        var replaced = false
        var replacementIntercepted = false
        var replacementRequest: Any? = null
        var allocatedByA: Bitmap? = null
        val y = 12f
        val aDownX = 306f
        val aMoveX = 198f
        val bDownX = 54f
        val bMoveX = 270f
        val aDownTime = SystemClock.uptimeMillis()
        val bDownTime = aDownTime + 100L
        val background = RecordingTargetBitmapDrawable {
            if (armed && !replaced) {
                replaced = true
                view.dispatchTouchEvent(
                    motionEvent(bDownTime, bDownTime, MotionEvent.ACTION_DOWN, bDownX, y),
                )
                replacementIntercepted = view.onInterceptTouchEvent(
                    motionEvent(bDownTime, bDownTime + 24L, MotionEvent.ACTION_MOVE, bMoveX, y),
                )
                replacementRequest = view.privateField("pendingLocalPageShotHandoff")
            }
        }
        view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(2)
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        armed = true

        try {
            view.dispatchTouchEvent(
                motionEvent(aDownTime, aDownTime, MotionEvent.ACTION_DOWN, aDownX, y),
            )
            assertTrue(
                "fixture must queue handoff A's target snapshot",
                view.onInterceptTouchEvent(
                    motionEvent(aDownTime, aDownTime + 24L, MotionEvent.ACTION_MOVE, aMoveX, y),
                ),
            )

            shadowOf(Looper.getMainLooper()).runOneTask()
            allocatedByA = background.targetBitmaps.single()
            val requestB = checkNotNull(replacementRequest)

            assertTrue("callback must establish replacement handoff B", replaced && replacementIntercepted)
            assertTrue(
                "the stale foreground snapshot allocated by A must be recycled before its callback returns",
                allocatedByA!!.isRecycled,
            )
            assertTrue(
                "A returning from snapshot draw must not clear or mutate replacement B; " +
                    "owner=${view.privateField("pendingLocalPageShotHandoff")} requestB=$requestB " +
                    "targetB=${requestB.reflectedField("targetBitmap")} " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "callback=${view.privateField("capturePendingLocalTargetRunnable")}",
                view.privateField("pendingLocalPageShotHandoff") === requestB &&
                    requestB.reflectedField("targetBitmap") == null &&
                    view.privateField("interactiveTurnState").toString() == "LOCAL_SHOTS_WAITING" &&
                    view.privateField("capturePendingLocalTargetRunnable") != null &&
                    view.privateField("curlDrawable") == null &&
                    view.privateField("slideDrawable") == null,
            )
        } finally {
            allocatedByA?.let { if (!it.isRecycled) it.recycle() }
            view.onTouchEvent(
                motionEvent(bDownTime, bDownTime + 48L, MotionEvent.ACTION_CANCEL, bMoveX, y),
            )
            view.dispose()
        }
    }

    @Test
    fun `target-only cold handoff is retired by every visual and layout invalidator`() {
        data class Scenario(
            val name: String,
            val disposesView: Boolean = false,
            val invalidate: (EpubFlowView) -> Unit,
        )

        val replacementText = (1..40).joinToString("\n") { "Replacement chapter line $it." }
        val replacementFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(replacementText, headingLevel = null, paragraphIndex = 0)),
        )
        val scenarios = listOf(
            Scenario("background replacement") { view ->
                view.background = ColorDrawable(0xFFF1E8D8.toInt())
            },
            Scenario("highlight refresh") { view ->
                view.refreshHighlights(emptyList())
            },
            Scenario("flip style change") { view ->
                view.flipStyle = PageFlipStyle.SLIDE
            },
            Scenario("mode change") { view ->
                view.mode = EpubFlowView.Mode.SCROLL
            },
            Scenario("resize") { view ->
                view.measure(exactly(420), exactly(140))
                view.layout(0, 0, 420, 140)
            },
            Scenario("chapter replacement") { view ->
                view.setChapter(replacementFlow, replacementFlow.text, pageHeightPx = view.height)
                view.measure(exactly(360), exactly(120))
                view.layout(0, 0, 360, 120)
            },
            Scenario("dispose", disposesView = true) { view ->
                view.dispose()
            },
        )

        scenarios.forEach { scenario ->
            val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
            val background = RecordingTargetBitmapDrawable()
            view.background = background
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            background.targetBitmaps.clear()
            val downX = view.width * 0.85f
            val moveX = view.width * 0.10f
            val y = view.height * 0.10f
            val downTime = SystemClock.uptimeMillis()

            try {
                view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
                assertTrue(
                    "${scenario.name}: fixture must classify the cold local turn",
                    view.onInterceptTouchEvent(
                        motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                    ),
                )
                shadowOf(Looper.getMainLooper()).runOneTask()
                val partialTarget = background.targetBitmaps.single()
                assertNull("${scenario.name}: target-only checkpoint must not install curl", view.privateField("curlDrawable"))
                assertNull("${scenario.name}: target-only checkpoint must not install slide", view.privateField("slideDrawable"))

                scenario.invalidate(view)
                val stateImmediately = view.privateField("interactiveTurnState").toString()
                val curlImmediately = view.privateField("curlDrawable")
                val slideImmediately = view.privateField("slideDrawable")
                val lateTrace = buildList {
                    repeat(8) {
                        shadowOf(Looper.getMainLooper()).runOneTask()
                        add(
                            Triple(
                                view.privateField("interactiveTurnState").toString(),
                                view.privateField("curlDrawable"),
                                view.privateField("slideDrawable"),
                            ),
                        )
                    }
                }
                shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)
                val finalState = view.privateField("interactiveTurnState").toString()
                val finalCurl = view.privateField("curlDrawable")
                val finalSlide = view.privateField("slideDrawable")
                val lateWorkStayedRetired = scenario.disposesView || lateTrace.all { (state, curl, slide) ->
                    state == "NONE" && curl == null && slide == null
                }

                assertTrue(
                    "${scenario.name}: invalidation must retire the target-only cold handoff; " +
                        "targetRecycled=${partialTarget.isRecycled} " +
                        "immediate=[$stateImmediately,$curlImmediately,$slideImmediately] " +
                        "lateTrace=$lateTrace final=[$finalState,$finalCurl,$finalSlide]",
                    partialTarget.isRecycled &&
                        stateImmediately == "NONE" && curlImmediately == null && slideImmediately == null &&
                        lateWorkStayedRetired &&
                        finalState == "NONE" && finalCurl == null && finalSlide == null,
                )
            } finally {
                view.dispose()
            }
        }
    }

    @Test
    fun `programmatic navigation during target-only cold handoff owns the final viewport`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val navigationPage = 2
        val navigationTop = requireNotNull(view.pageTopPxAt(navigationPage))
        val background = RecordingTargetBitmapDrawable()
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        reportedOffsets.clear()
        val downX = view.width * 0.85f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )
            shadowOf(Looper.getMainLooper()).runOneTask()
            val partialTarget = background.targetBitmaps.single()
            assertNull("target-only checkpoint must not install curl", view.privateField("curlDrawable"))
            assertNull("target-only checkpoint must not install slide", view.privateField("slideDrawable"))

            view.goToPage(navigationPage)
            val navigationOffset = view.topLayoutOffset()
            val reportsAfterNavigation = reportedOffsets.toList()
            val stateAfterNavigation = view.privateField("interactiveTurnState").toString()

            val lateTrace = buildList {
                repeat(8) {
                    shadowOf(Looper.getMainLooper()).runOneTask()
                    add(
                        Triple(
                            view.currentPageIndex() to view.scrollY,
                            view.privateField("interactiveTurnState").toString(),
                            view.privateField("curlDrawable") to view.privateField("slideDrawable"),
                        ),
                    )
                }
            }
            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)
            val finalState = view.privateField("interactiveTurnState").toString()
            val finalCurl = view.privateField("curlDrawable")
            val finalSlide = view.privateField("slideDrawable")

            assertTrue(
                "programmatic navigation must retire the target-only handoff and own the final viewport; " +
                    "targetRecycled=${partialTarget.isRecycled} " +
                    "navigation=[$navigationPage,$navigationTop,$navigationOffset] " +
                    "stateAfterNavigation=$stateAfterNavigation reportsAfterNavigation=$reportsAfterNavigation " +
                    "lateTrace=$lateTrace final=[${view.currentPageIndex()},${view.scrollY}," +
                    "${view.topLayoutOffset()},$finalState,$finalCurl,$finalSlide] reports=$reportedOffsets",
                partialTarget.isRecycled &&
                    stateAfterNavigation == "NONE" &&
                    reportsAfterNavigation == listOf(navigationOffset) &&
                    lateTrace.all { (viewport, state, overlays) ->
                        viewport.first == navigationPage && viewport.second == navigationTop &&
                            state == "NONE" && overlays.first == null && overlays.second == null
                    } &&
                    view.currentPageIndex() == navigationPage &&
                    view.scrollY == navigationTop &&
                    view.topLayoutOffset() == navigationOffset &&
                    finalState == "NONE" && finalCurl == null && finalSlide == null &&
                    reportedOffsets == reportsAfterNavigation,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `external viewport mutations retire a held software gesture before late cancel`() {
        data class Scenario(
            val name: String,
            val mutate: (EpubFlowView, Int) -> Unit,
        )

        val scenarios = listOf(
            Scenario("goToPage") { view, _ -> view.goToPage(2) },
            Scenario("goToOffset") { view, targetOffset -> view.goToOffset(targetOffset) },
            Scenario("mode") { view, targetOffset ->
                view.setModeAnchored(EpubFlowView.Mode.SCROLL, targetOffset)
            },
            Scenario("resize") { view, _ ->
                view.measure(exactly(420), exactly(140))
                view.layout(0, 0, 420, 140)
            },
        )
        val failures = mutableListOf<String>()

        scenarios.forEach { scenario ->
            val reportedOffsets = mutableListOf<Int>()
            val view = pagedFlowView(
                flipStyle = PageFlipStyle.SIMULATION,
                onTopOffsetChanged = reportedOffsets::add,
            )
            assertTrue("${scenario.name}: pageCount=${view.pageCount()}", view.pageCount() > 3)
            val targetOffset = (view.privateField("paged") as List<EpubFlowPage>)[2].startOffset
            reportedOffsets.clear()
            val heldX = view.width * 0.65f
            val y = view.height * 0.10f
            val downTime = SystemClock.uptimeMillis()

            try {
                assertTrue(
                    "${scenario.name}: fixture must start a ready software turn",
                    view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()),
                )
                view.updateInteractiveCurl(x = heldX)
                assertTrue(
                    "${scenario.name}: fixture requires a held overlay before mutation",
                    view.privateField("curlDrawable") != null || view.privateField("slideDrawable") != null,
                )

                scenario.mutate(view, targetOffset)
                shadowOf(Looper.getMainLooper()).idle()

                val retiredImmediately =
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        view.privateField("curlOrigin") == null
                val mutationMode = view.mode
                val mutationPage = view.currentPageIndex()
                val mutationTop = view.scrollY
                val mutationOffset = view.topLayoutOffset()
                val reportsAfterMutation = reportedOffsets.toList()

                view.onTouchEvent(
                    motionEvent(downTime, downTime + 72L, MotionEvent.ACTION_CANCEL, heldX, y),
                )
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

                val finalOwnedByMutation =
                    view.mode == mutationMode &&
                        view.currentPageIndex() == mutationPage &&
                        view.scrollY == mutationTop &&
                        view.topLayoutOffset() == mutationOffset &&
                        view.privateField("interactiveTurnState").toString() == "NONE" &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        reportedOffsets == reportsAfterMutation
                if (!retiredImmediately || !finalOwnedByMutation) {
                    failures +=
                        "${scenario.name}: retired=$retiredImmediately " +
                            "mutation=[$mutationMode,$mutationPage,$mutationTop,$mutationOffset,$reportsAfterMutation] " +
                            "final=[${view.mode},${view.currentPageIndex()},${view.scrollY}," +
                            "${view.topLayoutOffset()},${view.privateField("interactiveTurnState")}," +
                            "${view.privateField("curlDrawable")},${view.privateField("slideDrawable")}," +
                            "$reportedOffsets]"
                }
            } finally {
                view.dispose()
            }
        }

        assertTrue(
            "external navigation/layout ownership must retire the held gesture and make late CANCEL inert: $failures",
            failures.isEmpty(),
        )
    }

    @Test
    fun `visual only invalidators restore a held software origin without publishing`() {
        data class Scenario(
            val name: String,
            val invalidate: (EpubFlowView) -> Unit,
        )

        val scenarios = listOf(
            Scenario("background replacement") { view ->
                view.background = ColorDrawable(0xFFF1E8D8.toInt())
            },
            Scenario("highlight refresh") { view ->
                view.refreshHighlights(emptyList())
            },
            Scenario("flip style change") { view ->
                view.flipStyle = PageFlipStyle.SLIDE
            },
        )
        val failures = mutableListOf<String>()

        scenarios.forEach { scenario ->
            val reportedOffsets = mutableListOf<Int>()
            val view = pagedFlowView(
                flipStyle = PageFlipStyle.SIMULATION,
                onTopOffsetChanged = reportedOffsets::add,
            )
            assertTrue("${scenario.name}: pageCount=${view.pageCount()}", view.pageCount() > 3)
            val originPage = view.currentPageIndex()
            val originTop = view.scrollY
            val originOffset = view.topLayoutOffset()
            val heldX = view.width * 0.65f
            val y = view.height * 0.10f
            val downTime = SystemClock.uptimeMillis()
            reportedOffsets.clear()

            try {
                assertTrue(
                    "${scenario.name}: fixture must start a ready held turn",
                    view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()),
                )
                view.updateInteractiveCurl(x = heldX)
                assertTrue(
                    "${scenario.name}: target must be silently parked beneath a held overlay",
                    view.currentPageIndex() != originPage &&
                        view.privateField("curlDrawable") != null &&
                        reportedOffsets.isEmpty(),
                )

                scenario.invalidate(view)

                val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
                val restoredImmediately =
                    view.currentPageIndex() == originPage &&
                        view.scrollY == originTop &&
                        view.topLayoutOffset() == originOffset &&
                        view.privateField("interactiveTurnState").toString() == "NONE" &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        view.privateField("curlOrigin") == null &&
                        animator?.isRunning != true &&
                        reportedOffsets.isEmpty()

                view.onTouchEvent(
                    motionEvent(downTime, downTime + 72L, MotionEvent.ACTION_CANCEL, heldX, y),
                )
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

                val lateCancelStayedInert =
                    view.currentPageIndex() == originPage &&
                        view.scrollY == originTop &&
                        view.topLayoutOffset() == originOffset &&
                        view.privateField("interactiveTurnState").toString() == "NONE" &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        reportedOffsets.isEmpty()
                if (!restoredImmediately || !lateCancelStayedInert) {
                    failures +=
                        "${scenario.name}: immediate=$restoredImmediately late=$lateCancelStayedInert " +
                            "origin=[$originPage,$originTop,$originOffset] " +
                            "final=[${view.currentPageIndex()},${view.scrollY},${view.topLayoutOffset()}," +
                            "${view.privateField("interactiveTurnState")},${view.privateField("curlDrawable")}," +
                            "${view.privateField("slideDrawable")},$reportedOffsets]"
                }
            } finally {
                view.dispose()
            }
        }

        assertTrue(
            "visual-only invalidation must synchronously restore the held origin without locator publication: " +
                failures,
            failures.isEmpty(),
        )
    }

    @Test
    fun `external viewport mutations supersede a committed local settle without stale publication`() {
        data class Scenario(
            val name: String,
            val mutate: (EpubFlowView) -> Int?,
        )

        val replacementText = (1..40).joinToString("\n") { "Replacement chapter line $it." }
        val replacementFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(replacementText, headingLevel = null, paragraphIndex = 0)),
        )
        val scenarios = listOf(
            Scenario("goToPage") { view ->
                view.goToPage(2)
                view.topLayoutOffset()
            },
            Scenario("setChapter") { view ->
                view.setChapter(replacementFlow, replacementFlow.text, pageHeightPx = view.height)
                null
            },
        )
        val failures = mutableListOf<String>()

        scenarios.forEach { scenario ->
            val reportedOffsets = mutableListOf<Int>()
            val view = pagedFlowView(
                flipStyle = PageFlipStyle.SIMULATION,
                onTopOffsetChanged = reportedOffsets::add,
            )
            assertTrue("${scenario.name}: pageCount=${view.pageCount()}", view.pageCount() > 3)
            reportedOffsets.clear()

            try {
                assertTrue(
                    "${scenario.name}: fixture must start a ready local turn",
                    view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()),
                )
                view.updateInteractiveCurl(x = view.width * 0.25f)
                view.endInteractiveCurl(velocityX = 0f)

                val parkedTargetPage = view.currentPageIndex()
                val parkedTargetOffset = view.topLayoutOffset()
                val oldAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
                assertTrue(
                    "${scenario.name}: fixture needs a silently parked commit under a running settle",
                    parkedTargetPage > 0 && oldAnimator.isRunning && reportedOffsets.isEmpty(),
                )

                val expectedMutationOffset = scenario.mutate(view)
                val expectedImmediateReports = expectedMutationOffset?.let(::listOf).orEmpty()
                val immediateRetirement =
                    !oldAnimator.isRunning &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        view.privateField("curlOrigin") == null &&
                        reportedOffsets == expectedImmediateReports &&
                        parkedTargetOffset !in reportedOffsets

                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
                val finalReports = reportedOffsets.toList()
                val stayedOwnedByMutation =
                    !oldAnimator.isRunning &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        view.privateField("curlOrigin") == null &&
                        parkedTargetOffset !in finalReports &&
                        (expectedMutationOffset == null || finalReports == listOf(expectedMutationOffset))

                if (!immediateRetirement || !stayedOwnedByMutation) {
                    failures +=
                        "${scenario.name}: target=[$parkedTargetPage,$parkedTargetOffset] " +
                            "expectedImmediate=$expectedImmediateReports immediate=$immediateRetirement " +
                            "animatorRunning=${oldAnimator.isRunning} " +
                            "overlay=[${view.privateField("curlDrawable")},${view.privateField("slideDrawable")}] " +
                            "origin=${view.privateField("curlOrigin")} reports=$finalReports"
                }
            } finally {
                view.dispose()
            }
        }

        assertTrue(
            "an external viewport owner must synchronously retire the released settle without " +
                "publishing its silently parked target: $failures",
            failures.isEmpty(),
        )
    }

    @Test
    fun `visual invalidators resolve released local settles by the release decision`() {
        data class Invalidation(
            val name: String,
            val apply: (EpubFlowView) -> Unit,
        )

        val invalidations = listOf(
            Invalidation("background replacement") { view ->
                view.background = ColorDrawable(0xFFF1E8D8.toInt())
            },
            Invalidation("highlight refresh") { view ->
                view.refreshHighlights(emptyList())
            },
            Invalidation("flip style change") { view ->
                view.flipStyle = PageFlipStyle.SLIDE
            },
        )
        val failures = mutableListOf<String>()

        listOf(false, true).forEach { commit ->
            invalidations.forEach { invalidation ->
                val reportedOffsets = mutableListOf<Int>()
                val view = pagedFlowView(
                    flipStyle = PageFlipStyle.SIMULATION,
                    onTopOffsetChanged = reportedOffsets::add,
                )
                val originPage = view.currentPageIndex()
                val originTop = view.scrollY
                val originOffset = view.topLayoutOffset()
                reportedOffsets.clear()

                try {
                    assertTrue(
                        "${invalidation.name}/commit=$commit: fixture must start a ready local turn",
                        view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()),
                    )
                    val targetPage = view.currentPageIndex()
                    val targetTop = view.scrollY
                    val targetOffset = view.topLayoutOffset()
                    view.updateInteractiveCurl(x = view.width * if (commit) 0.25f else 0.75f)
                    view.endInteractiveCurl(velocityX = 0f)
                    val oldAnimator = checkNotNull(
                        view.privateField("flipAnimator") as android.animation.ValueAnimator?,
                    )
                    assertTrue(
                        "${invalidation.name}/commit=$commit: fixture needs a released running settle",
                        oldAnimator.isRunning && reportedOffsets.isEmpty(),
                    )

                    invalidation.apply(view)

                    val expectedPage = if (commit) targetPage else originPage
                    val expectedTop = if (commit) targetTop else originTop
                    val expectedOffset = if (commit) targetOffset else originOffset
                    val expectedReports = if (commit) listOf(targetOffset) else emptyList()
                    val immediateResolution =
                        view.currentPageIndex() == expectedPage &&
                            view.scrollY == expectedTop &&
                            view.topLayoutOffset() == expectedOffset &&
                            !oldAnimator.isRunning &&
                            view.privateField("curlDrawable") == null &&
                            view.privateField("slideDrawable") == null &&
                            view.privateField("curlOrigin") == null &&
                            reportedOffsets == expectedReports

                    shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
                    val stayedResolved =
                        view.currentPageIndex() == expectedPage &&
                            view.scrollY == expectedTop &&
                            view.topLayoutOffset() == expectedOffset &&
                            !oldAnimator.isRunning &&
                            view.privateField("curlDrawable") == null &&
                            view.privateField("slideDrawable") == null &&
                            view.privateField("curlOrigin") == null &&
                            reportedOffsets == expectedReports

                    if (!immediateResolution || !stayedResolved) {
                        failures +=
                            "${invalidation.name}/commit=$commit: immediate=$immediateResolution " +
                                "stayed=$stayedResolved expected=[$expectedPage,$expectedTop,$expectedOffset," +
                                "$expectedReports] final=[${view.currentPageIndex()},${view.scrollY}," +
                                "${view.topLayoutOffset()},${oldAnimator.isRunning}," +
                                "${view.privateField("curlDrawable")},${view.privateField("slideDrawable")}," +
                                "${view.privateField("curlOrigin")},$reportedOffsets]"
                    }
                } finally {
                    view.dispose()
                }
            }
        }

        assertTrue(
            "visual invalidation must finish a released commit exactly once or retire a released cancel " +
                "at its origin: $failures",
            failures.isEmpty(),
        )
    }

    @Test
    fun `cold handoff tracks latest move across canonical free rest and axis matrix`() {
        data class Scenario(
            val name: String,
            val freeRest: Boolean,
            val vertical: Boolean,
            val forward: Boolean,
        )

        val scenarios = listOf(
            Scenario("canonical forward horizontal", freeRest = false, vertical = false, forward = true),
            Scenario("FREE_REST backward horizontal", freeRest = true, vertical = false, forward = false),
            Scenario("canonical forward vertical", freeRest = false, vertical = true, forward = true),
        )

        scenarios.forEach { scenario ->
            val reportedOffsets = mutableListOf<Int>()
            val view = pagedFlowView(
                flipStyle = PageFlipStyle.SIMULATION,
                onTopOffsetChanged = reportedOffsets::add,
            )
            assertTrue("${scenario.name}: pageCount=${view.pageCount()}", view.pageCount() > 3)
            if (scenario.freeRest) {
                val pageOneTop = requireNotNull(view.pageTopPxAt(1))
                val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
                view.scrollTo(0, nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.40f))
            }
            val originPage = view.currentPageIndex()
            val originTop = view.scrollY
            val originOffset = view.topLayoutOffset()
            val background = RecordingBoundsTopDrawable()
            view.background = background
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            background.boundsTops.clear()
            reportedOffsets.clear()

            val downX = when {
                scenario.vertical -> view.width * 0.85f
                scenario.forward -> view.width * 0.85f
                else -> view.width * 0.15f
            }
            val downY = if (scenario.vertical) view.height * 0.75f else view.height * 0.10f
            val firstX = when {
                scenario.vertical -> downX
                scenario.forward -> view.width * 0.55f
                else -> view.width * 0.45f
            }
            val firstY = if (scenario.vertical) view.height * 0.45f else downY
            val latestX = when {
                scenario.vertical -> downX
                scenario.forward -> view.width * 0.10f
                else -> view.width * 0.90f
            }
            val latestY = if (scenario.vertical) view.height * 0.05f else downY
            val expectedProgress = if (scenario.vertical) {
                (downY - latestY) / view.height.toFloat()
            } else if (scenario.forward) {
                (downX - latestX) / view.width.toFloat()
            } else {
                (latestX - downX) / view.width.toFloat()
            }
            val downTime = SystemClock.uptimeMillis()

            try {
                view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY))
                assertTrue(
                    "${scenario.name}: first threshold MOVE must be intercepted",
                    view.onInterceptTouchEvent(
                        motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, firstX, firstY),
                    ),
                )
                view.onTouchEvent(
                    motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_MOVE, latestX, latestY),
                )

                assertTrue(
                    "${scenario.name}: synchronous MOVE stage must remain shot- and locator-silent; " +
                        "draws=${background.boundsTops} curl=${view.privateField("curlDrawable")} " +
                        "slide=${view.privateField("slideDrawable")} reports=$reportedOffsets",
                    background.boundsTops.isEmpty() &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        reportedOffsets.isEmpty(),
                )

                shadowOf(Looper.getMainLooper()).runOneTask()
                assertTrue(
                    "${scenario.name}: target frame must not install an incomplete overlay",
                    background.boundsTops.size == 1 &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        reportedOffsets.isEmpty(),
                )

                shadowOf(Looper.getMainLooper()).runOneTask()
                val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?) {
                    "${scenario.name}: second frame must install the SIMULATION renderer"
                }
                assertNull("${scenario.name}: SIMULATION must not degrade to slide", view.privateField("slideDrawable"))
                assertEquals(
                    "${scenario.name}: ready handoff must resume from the latest coordinate on its classified axis",
                    expectedProgress,
                    paper.progress,
                    0.001f,
                )
                assertTrue("${scenario.name}: ready handoff must remain locator-silent", reportedOffsets.isEmpty())

                view.onTouchEvent(
                    motionEvent(downTime, downTime + 72L, MotionEvent.ACTION_CANCEL, latestX, latestY),
                )
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

                assertTrue(
                    "${scenario.name}: ACTION_CANCEL must restore the exact origin without publishing; " +
                        "origin=[$originPage,$originTop,$originOffset] " +
                        "final=[${view.currentPageIndex()},${view.scrollY},${view.topLayoutOffset()}] " +
                        "state=${view.privateField("interactiveTurnState")} " +
                        "curl=${view.privateField("curlDrawable")} slide=${view.privateField("slideDrawable")} " +
                        "reports=$reportedOffsets",
                    (scenario.freeRest || view.currentPageIndex() == originPage) &&
                        view.scrollY == originTop &&
                        view.topLayoutOffset() == originOffset &&
                        view.privateField("interactiveTurnState").toString() == "NONE" &&
                        view.privateField("curlDrawable") == null &&
                        view.privateField("slideDrawable") == null &&
                        reportedOffsets.isEmpty(),
                )
            } finally {
                view.dispose()
            }
        }
    }

    @Test
    fun `warmed free rest forward turn avoids chapter and page table scans`() {
        val blocks = (0 until 512).map { paragraphIndex ->
            EpubDisplayBlock.Text(
                text = "Segment $paragraphIndex marker text for deterministic pagination.",
                headingLevel = null,
                paragraphIndex = paragraphIndex,
            )
        }
        val sourceFlow = epubBuildChapterFlow(spineIndex = 0, blocks = blocks)
        val countedSegments = CountingList(sourceFlow.segments)
        val countedFlow = sourceFlow.copy(segments = countedSegments)
        val view = pagedFlowView(flipStyle = PageFlipStyle.NONE)
        view.setChapter(countedFlow, countedFlow.text, pageHeightPx = view.height)
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        assertTrue("fixture needs a long paged chapter", view.pageCount() > 64)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.40f)
        view.scrollTo(0, freeRest)
        val before = fullLineRangeInViewport(view)
        val layout = requireNotNull(view.textView.layout)
        val targetLine = before.last + 1
        val targetTop = layout.getLineTop(targetLine)
        val targetOffset = layout.getLineStart(targetLine)
        view.recycleCachedTexturesForTest()
        view.setPrivateField("pageTexturePrecachePending", true)
        @Suppress("UNCHECKED_CAST")
        val originalPages = view.privateField("paged") as List<EpubFlowPage>
        val countedPages = CountingList(originalPages)
        view.setPrivateField("paged", countedPages)
        countedSegments.reset()
        countedPages.reset()

        val accepted = view.goToAdjacentPage(1)
        val finalY = view.scrollY
        val visibleOffset = view.topLayoutOffset()

        assertTrue(
            "a warmed FREE_REST turn must use indexed chapter and page metadata; " +
                "segmentCount=${countedSegments.size} segmentAccesses=${countedSegments.elementAccesses} " +
                "pageCount=${countedPages.size} pageAccesses=${countedPages.elementAccesses} " +
                "targetTop=$targetTop finalY=$finalY targetOffset=$targetOffset visibleOffset=$visibleOffset",
            accepted && finalY == targetTop && visibleOffset == targetOffset &&
                countedSegments.elementAccesses <= 16 &&
                countedPages.elementAccesses <= 64,
        )
    }

    @Test
    fun `warmed middle page serves a backward drag without live recapture`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        val background = RecordingBoundsTopDrawable()
        view.background = background
        view.preCachePageTexturesForTest()
        background.boundsTops.clear()

        assertTrue(view.beginInteractiveCurl(forward = false, anchorX = 0f))

        try {
            assertTrue(
                "a warmed backward first MOVE must transfer current+previous shots without drawing live pages: " +
                    background.boundsTops,
                background.boundsTops.isEmpty(),
            )
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `warmed chapter boundary drag transfers current shot without live recapture`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        view.goToLastPage()
        val background = RecordingBoundsTopDrawable()
        view.background = background
        view.preCachePageTexturesForTest()
        background.boundsTops.clear()
        val preview = view.offerReadyBoundaryPreviewForTest(forward = true)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))

        try {
            assertTrue(
                "a warmed boundary first MOVE must transfer the current shot without drawing live pages: " +
                    background.boundsTops,
                background.boundsTops.isEmpty(),
            )
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            if (!preview.isRecycled) preview.recycle()
        }
    }

    @Test
    fun `boundary drag capture failure keeps the prepared target for a healthy retry`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        view.goToLastPage()
        view.recycleCachedTexturesForTest()
        val originalBackground = view.background
        val preview = view.offerReadyBoundaryPreviewForTest(forward = true)
        view.background = CaptureTargetBitmapThenThrowDrawable()

        assertFalse(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        assertFalse("an outgoing capture failure must not destroy the prepared target", preview.isRecycled)
        val restored = checkNotNull(view.privateField("forwardBoundaryPreview") as BoundaryPagePreview?)
        assertTrue("the same target preview must be returned to its direction slot", restored.bitmap === preview)

        view.background = originalBackground
        assertTrue("a later healthy gesture must reuse the restored target", view.beginInteractiveCurl(true, view.width.toFloat()))
        view.endInteractiveCurl(velocityX = 0f)
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `waiting boundary rejection restores retry token without cancelling request on up`() {
        lateinit var view: EpubFlowView
        val budget = PageShotBudget(0L)
        val previewRequests = mutableListOf<Pair<Boolean, Long>>()
        val cancelledRequests = mutableListOf<Boolean>()
        val discardedTokens = mutableListOf<Long>()
        val evictedTokens = mutableListOf<Long>()
        var forceFirstPinnedDrawFailure = true
        view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            pageShotBudget = budget,
            onPinnedPageShotAdmissionNeeded = {
                if (forceFirstPinnedDrawFailure) {
                    forceFirstPinnedDrawFailure = false
                    view.background = CaptureTargetBitmapThenThrowDrawable()
                }
            },
        ).apply {
            onBoundaryPreviewNeeded = { forward, generation ->
                previewRequests += forward to generation
                assertTrue(preparePageShotBudgetForBoundaryPreview(forward, required = true))
            }
            onBoundaryPreviewRequestCancelled = cancelledRequests::add
            onBoundaryTurnDiscarded = { discardedTokens += it.token }
            onBoundaryPreviewEvicted = { evictedTokens += it.token }
        }
        view.goToLastPage()
        val originalBackground = view.background
        val downX = view.width * 0.85f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()
        val preview = view.newBoundaryPreviewForTest(forward = true, token = 54L)
        var retryStarted = false

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
            assertEquals("BOUNDARY_WAITING", view.privateField("interactiveTurnState").toString())
            assertEquals(1, previewRequests.size)

            assertTrue(view.offerBoundaryPreviewForTest(preview))
            val stateAfterRejectedResume = view.privateField("interactiveTurnState").toString()
            val restoredBeforeUp = view.privateField("forwardBoundaryPreview") as BoundaryPagePreview?

            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y))
            view.background = originalBackground
            retryStarted = view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat())
            val activeRetry = view.privateField("activeBoundaryPreview") as BoundaryPagePreview?

            assertTrue(
                "a rejected waiting resume must leave an idle gesture while preserving the same engine token; " +
                    "stateAfterReject=$stateAfterRejectedResume restored=${restoredBeforeUp?.token} " +
                    "cancelled=$cancelledRequests discarded=$discardedTokens evicted=$evictedTokens " +
                    "retryStarted=$retryStarted activeRetry=${activeRetry?.token}",
                stateAfterRejectedResume == "NONE" &&
                    restoredBeforeUp?.token == preview.token &&
                    restoredBeforeUp.bitmap === preview.bitmap &&
                    cancelledRequests.isEmpty() &&
                    discardedTokens.isEmpty() &&
                    evictedTokens.isEmpty() &&
                    retryStarted &&
                    activeRetry?.token == preview.token,
            )
        } finally {
            if (retryStarted) view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `quick side swipe keeps its chapter boundary turn intent until preview arrives`() {
        val commits = mutableListOf<Any>()
        val previewRequests = mutableListOf<Pair<Boolean, Long>>()
        val cancelledRequests = mutableListOf<Boolean>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE).apply {
            installBoundaryCommitRecorderForTest(commits)
            onBoundaryPreviewNeeded = { forward, generation -> previewRequests += forward to generation }
            onBoundaryPreviewRequestCancelled = cancelledRequests::add
        }
        view.goToLastPage()
        val startX = view.width * 0.85f
        val startY = view.height * 0.85f
        val moveY = view.height * 0.65f
        val releaseY = view.height * 0.50f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY))
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, startX, moveY),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, startX, releaseY),
            )

            assertEquals(listOf(true to view.boundaryPreviewGenerationToken()), previewRequests)
            assertEquals(
                "a fast released swipe must remain a discrete boundary intent instead of cancelling cold work",
                "BOUNDARY_DISCRETE_WAITING",
                view.privateField("interactiveTurnState").toString(),
            )
            assertTrue("the in-flight adjacent preview must not be cancelled on finger-up", cancelledRequests.isEmpty())

            val preview = view.newBoundaryPreviewForTest(forward = true, token = 55L)
            assertTrue(view.offerBoundaryPreviewForTest(preview))
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals("the retained short swipe must commit exactly once", 1, commits.size)
            assertTrue(cancelledRequests.isEmpty())
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `fast release settles sooner than a stationary release from the same progress`() {
        fun settleDuration(velocityX: Float): Long {
            val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            view.updateInteractiveCurl(x = view.width * 0.40f)
            view.endInteractiveCurl(velocityX)
            return (view.privateField("flipAnimator") as android.animation.ValueAnimator).duration
        }

        val stationaryDuration = settleDuration(velocityX = 0f)
        val fastFlingDuration = settleDuration(velocityX = -5_000f)

        assertTrue(
            "release velocity must carry into settle instead of switching every gesture to one fixed curve: " +
                "stationary=$stationaryDuration fast=$fastFlingDuration",
            fastFlingDuration < stationaryDuration,
        )
    }

    @Test
    fun `first threshold crossing move uses the full down displacement for temporary scroll`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val x = view.width * 0.50f
        val downY = view.height * 0.50f
        val moveY = view.height * 0.15f
        val downTime = SystemClock.uptimeMillis()
        val startScrollY = view.scrollY

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, downY))
        assertTrue(
            view.onInterceptTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, x, moveY),
            ),
        )

        try {
            assertEquals(
                "the first temporary-scroll frame must include all finger travel since ACTION_DOWN",
                startScrollY + (downY - moveY).toInt(),
                view.scrollY,
            )
        } finally {
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, x, moveY))
        }
    }

    @Test
    fun `live paged draw keeps paper background anchored to viewport while scrolled`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        assertTrue("test needs a non-zero page top", pageOneTop > 0)
        val background = RecordingBoundsTopDrawable()
        view.background = background

        view.goToPage(1)
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.RGB_565)))

        assertTrue(
            "static reader draw must paint paper in the same viewport coordinates as page-turn snapshots, recorded=${background.boundsTops}",
            background.boundsTops.isNotEmpty() && background.boundsTops.all { it == 0 },
        )
    }

    @Test
    fun `live paged draw paints paper in the viewport instead of exposing host background`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val scrolledPage = (1 until view.pageCount()).firstOrNull { index ->
            (view.pageTopPxAt(index) ?: 0) > view.height
        } ?: error("test needs a page top beyond the viewport height")
        view.background = android.graphics.drawable.ColorDrawable(0xFFEDE6D6.toInt())
        view.textView.setTextColor(0x00000000)

        view.goToPage(scrolledPage)
        val staticFrame = view.drawToBitmapForTest()
        val viewportShot = requireNotNull(view.snapshotViewportForTest())

        assertEquals(
            "static reader draw must fill the viewport with the same paper as the page-turn snapshot",
            viewportShot.getPixel(4, 4),
            staticFrame.getPixel(4, 4),
        )
        assertEquals(
            "the EPUB surface must own the visible paper; otherwise the outer host texture shows through",
            0xFFEDE6D6.toInt(),
            staticFrame.getPixel(4, 4),
        )

        staticFrame.recycle()
        viewportShot.recycle()
    }

    @Test
    fun `next page from raw final line top reports chapter boundary without moving`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val finalPage = view.pageCount() - 1
        val rawFinalTop = requireNotNull(view.pageTopPxAt(finalPage))
        val naturalMaxScroll = (view.textView.height - view.height).coerceAtLeast(0)
        assertTrue("test requires a short tail page", rawFinalTop > naturalMaxScroll)

        view.goToPage(finalPage)

        assertEquals("tail extent must keep the final raw line top reachable", rawFinalTop, view.scrollY)
        assertEquals("final page index should be retained at its raw top", finalPage, view.currentPageIndex())

        val moved = view.goToAdjacentPage(1)

        assertEquals("next from final page must return false for cross-spine advance", false, moved)
        assertEquals(finalPage, view.currentPageIndex())
        assertEquals(rawFinalTop, view.scrollY)
    }

    @Test
    fun `dynamic tail page beyond canonical last top remains reachable then reports boundary`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val layout = requireNotNull(view.textView.layout)
        val canonicalLastIndex = view.pageCount() - 1
        val canonicalLastTop = requireNotNull(view.pageTopPxAt(canonicalLastIndex))
        val canonicalPreviousTop = requireNotNull(view.pageTopPxAt(canonicalLastIndex - 1))
        var freeRest: Int? = null
        var targetLine: Int? = null
        for (candidateY in (canonicalLastTop - 1) downTo (canonicalPreviousTop + 1)) {
            val viewportTopInLayout = (candidateY - view.textView.paddingTop).coerceAtLeast(0)
            val intersectingLine = layout.getLineForVertical(viewportTopInLayout)
            if (layout.getLineTop(intersectingLine) >= viewportTopInLayout) continue
            view.scrollTo(0, candidateY)
            val candidateTargetLine = fullLineRangeInViewport(view).last + 1
            if (candidateTargetLine >= layout.lineCount) continue
            if (layout.getLineTop(candidateTargetLine) <= canonicalLastTop) continue
            freeRest = candidateY
            targetLine = candidateTargetLine
            break
        }
        val freeRestY = checkNotNull(freeRest) {
            "test needs a FREE_REST tail viewport before canonicalLastTop=$canonicalLastTop"
        }
        val dynamicTargetLine = checkNotNull(targetLine)
        val targetTop = layout.getLineTop(dynamicTargetLine)
        val targetOffset = layout.getLineStart(dynamicTargetLine)
        view.scrollTo(0, freeRestY)

        val firstAccepted = view.goToAdjacentPage(1)
        val finalY = view.scrollY
        val visibleOffset = view.topLayoutOffset()
        val secondAccepted = view.goToAdjacentPage(1)
        val boundaryY = view.scrollY

        assertTrue(
            "the dynamic tail page must own its raw line top before the next turn reaches the boundary; " +
                "canonicalLastTop=$canonicalLastTop targetTop=$targetTop finalY=$finalY boundaryY=$boundaryY " +
                "targetOffset=$targetOffset visibleOffset=$visibleOffset " +
                "firstAccepted=$firstAccepted secondAccepted=$secondAccepted",
            targetTop > canonicalLastTop &&
                firstAccepted && finalY == targetTop && visibleOffset == targetOffset &&
                !secondAccepted && boundaryY == targetTop,
        )
    }

    @Test
    fun `cold land on last reaches the raw final line top before tail extent is remeasured`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = EpubFlowView(
            context = context,
            onTapZone = {},
            onTopOffsetChanged = {},
            onSelectionRange = { _, _ -> },
        )
        activity.addContentView(view, ViewGroup.LayoutParams(360, 120))
        view.textView.textSize = 18f
        val chapterText = (1..80).joinToString("\n") { "Cold line $it marker text." }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(EpubDisplayBlock.Text(chapterText, headingLevel = null, paragraphIndex = 0)),
        )
        view.textView.text = flow.text
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)

        view.setChapter(flow, flow.text, pageHeightPx = 120, landOnLast = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("test requires several cold-paginated pages", view.pageCount() > 3)
        val rawLastTop = requireNotNull(view.pageTopPxAt(view.pageCount() - 1))
        val oldNaturalMaxScroll = (view.textView.height - view.height).coerceAtLeast(0)
        assertTrue("test requires the raw final top beyond the old natural child max", rawLastTop > oldNaturalMaxScroll)
        assertEquals(
            "cold landOnLast must reach the paginator's raw last line top without waiting for a second measure",
            rawLastTop,
            view.scrollY,
        )
    }

    @Test
    fun `rapid discrete slide turn while animation is active does not double advance`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))

        assertEquals(1, view.currentPageIndex())
        assertEquals(pageOneTop, view.scrollY)
    }

    @Test
    fun `active slide into the final page consumes a rapid next without reporting a chapter boundary`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val finalPage = view.pageCount() - 1
        view.goToPage(finalPage - 1)

        assertTrue(view.goToAdjacentPage(1))
        assertEquals("the live page is parked beneath the active slide", finalPage, view.currentPageIndex())
        assertNotNull(view.privateField("slideDrawable"))

        assertTrue(
            "a rapid request during the active slide must be consumed, not reported as a chapter boundary",
            view.goToAdjacentPage(1),
        )
        assertEquals(finalPage, view.currentPageIndex())
    }

    @Test
    fun `first slide turn finishes initial reveal before animating`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val container = view.getChildAt(0)
        container.alpha = 0f

        assertTrue(view.goToAdjacentPage(1))

        assertEquals("first turn must not snapshot or animate a hidden reveal layer", 1f, container.alpha)
        assertNotNull("first turn should create the slide animation drawable", view.privateField("slideDrawable"))
    }

    @Test
    fun `first paged turn requested before initial layout settles is replayed with animation`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = EpubFlowView(
            context = context,
            onTapZone = {},
            onTopOffsetChanged = {},
            onSelectionRange = { _, _ -> },
        ).apply {
            flipStyle = PageFlipStyle.SLIDE
            textView.textSize = 18f
        }
        activity.addContentView(view, ViewGroup.LayoutParams(360, 120))
        val chapterText = (1..80).joinToString("\n") { "Line $it marker text." }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(EpubDisplayBlock.Text(chapterText, headingLevel = null, paragraphIndex = 0)),
        )
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)

        view.setChapter(flow, flow.text, pageHeightPx = 120)
        val accepted = view.goToAdjacentPage(1)

        assertEquals("the first tap should be queued instead of falling through as a boundary turn", true, accepted)
        assertEquals("the early tap should not create an instant cut before layout exists", null, view.privateField("slideDrawable"))

        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)

        assertEquals("the queued first tap should finish reveal once the first measured frame is positioned", false, view.privateBool("awaitingReveal"))
        assertEquals(1f, view.getChildAt(0).alpha)
        assertNotNull("the queued first tap should start the normal slide animation once measured", view.privateField("flipAnimator"))
        assertEquals(1, view.currentPageIndex())
        assertEquals(view.pageTopPxAt(1), view.scrollY)
    }

    @Test
    fun `first turn requested after zero-height initial settle replays when measured`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.layout(0, 0, 360, 0)
        view.setPrivateField("awaitingReveal", true)
        view.setPrivateField("pendingInitialPageTurnDelta", null)
        view.getChildAt(0).alpha = 0f

        val accepted = view.goToAdjacentPage(1)

        assertEquals("the first turn should be queued while the real viewport height is still zero", true, accepted)
        assertEquals("zero-height first turn must not silently cut to the next page", 0, view.currentPageIndex())
        assertEquals(1, view.privateInt("pendingInitialPageTurnDelta"))
        assertEquals(null, view.privateField("slideDrawable"))

        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the queued first turn should finish reveal once the measured viewport is available", false, view.privateBool("awaitingReveal"))
        assertEquals(1f, view.getChildAt(0).alpha)
        assertNotNull("the queued first turn should animate once the measured viewport is available", view.privateField("flipAnimator"))
        assertEquals(1, view.currentPageIndex())
        assertEquals(view.pageTopPxAt(1), view.scrollY)
    }

    @Test
    fun `first turn during initial reveal finishes reveal and animates immediately`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.setPrivateField("awaitingReveal", true)
        view.setPrivateField("pendingInitialPageTurnDelta", null)
        view.getChildAt(0).alpha = 0f

        val accepted = view.goToAdjacentPage(1)

        assertEquals("the first turn should be accepted while initial content is still hidden", true, accepted)
        assertEquals(
            "a ready paged surface should finish reveal instead of swallowing the first turn",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(1f, view.getChildAt(0).alpha)
        assertNull("ready first turn should not be left queued", view.privateField("pendingInitialPageTurnDelta"))
        assertNotNull("ready first turn should start the normal slide animation immediately", view.privateField("flipAnimator"))
        assertEquals(1, view.currentPageIndex())
        assertEquals(view.pageTopPxAt(1), view.scrollY)
    }

    @Test
    fun `first slide turn during frozen conversion uses visible cover as outgoing page shot`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.setPrivateField("awaitingReveal", true)
        view.getChildAt(0).alpha = 0f
        val visibleCover = markerBitmap(view.width, view.height)
        view.showConversionSnapshotForTest(visibleCover.copy(Bitmap.Config.ARGB_8888, false))
        view.getChildAt(0).alpha = 1f
        val livePagedFrame = requireNotNull(view.snapshotViewportForTest())
        view.getChildAt(0).alpha = 0f
        assertTrue(
            "test requires a visible frozen frame that differs from the hidden paged frame",
            !bitmapsHaveSamePixels(visibleCover, livePagedFrame),
        )
        livePagedFrame.recycle()

        assertTrue(view.goToAdjacentPage(1))

        val slide = view.privateField("slideDrawable")
        assertNotNull("the first tap during conversion should still start the normal slide animation", slide)
        val front = slide!!.privateBitmap("frontBitmap")
        assertAllPixelsEqual(
            "the turn must start from the frame the reader was visibly showing under the finger",
            visibleCover,
            front,
        )
        visibleCover.recycle()
    }

    @Test
    fun `first simulation turn during frozen conversion uses visible cover as front texture`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.setPrivateField("awaitingReveal", true)
        view.getChildAt(0).alpha = 0f
        val visibleCover = markerBitmap(view.width, view.height)
        view.showConversionSnapshotForTest(visibleCover.copy(Bitmap.Config.ARGB_8888, false))

        assertTrue(view.goToAdjacentPage(1))

        val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
        val front = paper.privateBitmap("frontBitmap")
        assertAllPixelsEqual(
            "the local PAPER turn must start from the same frozen frame that was visible before the tap",
            visibleCover,
            front,
        )
        visibleCover.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `first turn during conversion fade flattens the last visible composition into its front shot`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val livePage = requireNotNull(view.snapshotViewportForTest())
        val coverPixels = markerBitmap(view.width, view.height)
        val expected = livePage.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(expected).drawBitmap(
            coverPixels,
            0f,
            0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 128 },
        )
        assertFalse(
            "the fixture must produce a partial composition rather than another opaque cover copy",
            bitmapsHaveSamePixels(expected, coverPixels),
        )
        view.showConversionSnapshotForTest(coverPixels.copy(Bitmap.Config.ARGB_8888, false))
        val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
        cover.javaClass.getDeclaredField("alphaValue").apply { isAccessible = true }.setInt(cover, 128)

        assertTrue(view.goToAdjacentPage(1))

        val slide = checkNotNull(view.privateField("slideDrawable"))
        assertAllPixelsEqual(
            "the turn front must equal the exact live-plus-partial-cover composition visible before the tap",
            expected,
            slide.privateBitmap("frontBitmap"),
        )
        assertNull("the captured conversion cover must not remain beneath the active turn", view.privateField("conversionSnapshotDrawable"))
        livePage.recycle()
        coverPixels.recycle()
        expected.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `partial conversion capture failure rejects the turn and lets the reveal finish`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val layout = requireNotNull(view.textView.layout)
        val originalBackground = view.background
        view.mode = EpubFlowView.Mode.SCROLL
        view.pendingDecodesProvider = { false }
        view.setModeAnchored(
            EpubFlowView.Mode.PAGED,
            layout.getLineStart(5.coerceAtMost(layout.lineCount - 1)),
        )
        val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
        cover.javaClass.getDeclaredField("alphaValue").apply { isAccessible = true }.setInt(cover, 128)
        assertNotNull("fixture requires an active conversion reveal", view.privateField("conversionFadeAnimator"))
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        view.background = CaptureTargetBitmapThenThrowDrawable()

        assertTrue("the failed request must still be consumed by the self-paging view", view.goToAdjacentPage(1))

        view.background = originalBackground
        assertEquals("a failed visible-frame capture must not advance the page", startPage, view.currentPageIndex())
        assertEquals("a failed visible-frame capture must keep the current anchor", startTop, view.scrollY)
        assertEquals("the partially visible cover must remain the same owner", cover, view.privateField("conversionSnapshotDrawable"))
        assertNull("no slide may start from a stale opaque cover", view.privateField("slideDrawable"))
        assertNull("no page-turn animator may start after capture failure", view.privateField("flipAnimator"))
        assertNotNull("the existing reveal must continue after the rejected turn", view.privateField("conversionFadeAnimator"))

        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

        assertNull("the uninterrupted reveal must eventually retire its cover", view.privateField("conversionSnapshotDrawable"))
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `partial conversion flatten failure recycles only the failed destination and preserves the reveal`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val layout = requireNotNull(view.textView.layout)
        view.mode = EpubFlowView.Mode.SCROLL
        view.pendingDecodesProvider = { false }
        view.setModeAnchored(
            EpubFlowView.Mode.PAGED,
            layout.getLineStart(5.coerceAtMost(layout.lineCount - 1)),
        )
        val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
        cover.javaClass.getDeclaredField("alphaValue").apply { isAccessible = true }.setInt(cover, 128)
        val coverBitmap = cover.privateBitmap("bitmap")
        val fade = checkNotNull(view.privateField("conversionFadeAnimator"))
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        var failedDestination: Bitmap? = null
        val failFlatten: (Any, Bitmap) -> Boolean = { _, destination ->
            failedDestination = destination
            false
        }
        view.setPrivateField("conversionSnapshotFlattener", failFlatten)

        assertTrue(view.goToAdjacentPage(1))

        val destination = checkNotNull(failedDestination)
        assertTrue("the failed mutable composition must be recycled immediately", destination.isRecycled)
        assertFalse("the still-visible cover must retain ownership", coverBitmap.isRecycled)
        assertEquals(startPage, view.currentPageIndex())
        assertEquals(startTop, view.scrollY)
        assertEquals(cover, view.privateField("conversionSnapshotDrawable"))
        assertEquals(128, cover.privateInt("alphaValue"))
        assertTrue("the same reveal animator must continue", view.privateField("conversionFadeAnimator") === fade)
        assertNull(view.privateField("slideDrawable"))

        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        assertTrue("the cover is recycled only after its original reveal completes", coverBitmap.isRecycled)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `partial conversion capture failure cannot escape through interactive renderer fallbacks`() {
        listOf(PageFlipStyle.SLIDE, PageFlipStyle.SIMULATION).forEach { style ->
            val tapZones = mutableListOf<EpubFlowTapZone>()
            val view = pagedFlowView(flipStyle = style, onTapZone = tapZones::add)
            assertTrue("style=$style pageCount=${view.pageCount()}", view.pageCount() > 3)
            val layout = requireNotNull(view.textView.layout)
            val originalBackground = view.background
            view.mode = EpubFlowView.Mode.SCROLL
            view.pendingDecodesProvider = { false }
            view.setModeAnchored(
                EpubFlowView.Mode.PAGED,
                layout.getLineStart(5.coerceAtMost(layout.lineCount - 1)),
            )
            val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            cover.javaClass.getDeclaredField("alphaValue").apply { isAccessible = true }.setInt(cover, 128)
            val startPage = view.currentPageIndex()
            val startTop = view.scrollY
            view.background = CaptureTargetBitmapThenThrowDrawable()
            val downX = view.width * 0.75f
            val moveX = view.width * 0.10f
            val y = view.height * 0.10f
            val downTime = SystemClock.uptimeMillis()

            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))

            view.background = originalBackground
            assertEquals("style=$style must not request a fallback turn", emptyList<EpubFlowTapZone>(), tapZones)
            assertEquals("style=$style must retain the current page", startPage, view.currentPageIndex())
            assertEquals("style=$style must retain the current anchor", startTop, view.scrollY)
            assertEquals("style=$style must retain the partial owner", cover, view.privateField("conversionSnapshotDrawable"))
            assertNull("style=$style must not start a slide", view.privateField("slideDrawable"))
            assertNull("style=$style must not start a local paper turn", view.privateField("curlDrawable"))

            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
            assertNull("style=$style reveal must still finish", view.privateField("conversionSnapshotDrawable"))
        }
    }

    @Test
    fun `boundary turn keeps the outgoing frame visible while the incoming chapter waits`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToLastPage()
        val visibleOutgoing = requireNotNull(view.snapshotViewportForTest())
        view.pendingDecodesProvider = { true }

        assertTrue("the final page should prepare an animated cross-chapter turn", view.prepareBoundaryPageTurn(1))
        val incomingText = (1..40).joinToString("\n") { "Incoming chapter line $it." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )

        try {
            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)

            assertEquals("the incoming live chapter stays hidden until it is stable", 0f, view.getChildAt(0).alpha)
            val cover = view.privateField("conversionSnapshotDrawable")
            assertNotNull(
                "the outgoing frame must remain the visible owner while the incoming chapter is hidden",
                cover,
            )
            assertAllPixelsEqual(
                "the waiting cover must be the exact frame visible before the boundary request",
                visibleOutgoing,
                checkNotNull(cover).privateBitmap("bitmap"),
            )
        } finally {
            visibleOutgoing.recycle()
            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `pending boundary turn consumes a second navigation without mutating the target chapter`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToLastPage()
        view.pendingDecodesProvider = { true }
        assertTrue("the outgoing chapter should prepare a boundary turn", view.prepareBoundaryPageTurn(1))
        val incomingText = (1..120).joinToString("\n") { "Incoming chapter line $it keeps the target paginated." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )

        try {
            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            assertTrue("precondition: incoming chapter needs multiple pages", view.pageCount() > 1)
            assertNotNull(view.privateField("pendingBoundaryPageTurn"))
            assertNotNull(view.privateField("conversionSnapshotDrawable"))

            assertTrue("a gated navigation is consumed by the flow view", view.goToAdjacentPage(1))

            assertEquals("the waiting target chapter must stay on its first page", 0, view.currentPageIndex())
            assertNotNull("the first boundary transaction must remain pending", view.privateField("pendingBoundaryPageTurn"))
            assertNotNull("the outgoing frame must remain the visible owner", view.privateField("conversionSnapshotDrawable"))
            assertNull("a second turn must not start while the boundary transaction is pending", view.privateField("slideDrawable"))
        } finally {
            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `boundary turn cannot settle into a later target chapter generation`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToLastPage()
        view.pendingDecodesProvider = { true }
        assertTrue("the outgoing chapter should prepare a boundary turn", view.prepareBoundaryPageTurn(1))
        val incomingText = (1..80).joinToString("\n") { "Incoming generation line $it." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )

        try {
            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            assertNotNull("the first target generation should still own the pending turn", view.privateField("pendingBoundaryPageTurn"))

            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)

            assertNull(
                "reinstalling the target chapter must invalidate the older boundary transaction",
                view.privateField("pendingBoundaryPageTurn"),
            )
            assertNotNull(
                "the frozen frame must remain the visible owner while the later generation is hidden",
                view.privateField("conversionSnapshotDrawable"),
            )
            assertEquals(0f, view.getChildAt(0).alpha)
            assertNull("the stale transaction must not start a turn", view.privateField("slideDrawable"))

            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            assertNull(view.privateField("pendingBoundaryPageTurn"))
            assertNotNull(
                "the boundary cover must survive any number of target-generation rebuilds until reveal",
                view.privateField("conversionSnapshotDrawable"),
            )
            assertEquals(0f, view.getChildAt(0).alpha)

            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

            assertNull("the superseded transaction must never start a turn", view.privateField("slideDrawable"))
            assertNull("the cover should retire only after the later generation is visible", view.privateField("conversionSnapshotDrawable"))
            assertEquals(1f, view.getChildAt(0).alpha)
        } finally {
            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `superseded boundary cover gates navigation until the later chapter reveals`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToLastPage()
        view.pendingDecodesProvider = { true }
        assertTrue("the outgoing chapter should prepare a boundary turn", view.prepareBoundaryPageTurn(1))
        val incomingText = (1..120).joinToString("\n") { "Replacement chapter line $it remains hidden." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )

        try {
            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            assertNull("the original boundary transaction is superseded", view.privateField("pendingBoundaryPageTurn"))
            assertNotNull("its frame must still cover the hidden replacement", view.privateField("conversionSnapshotDrawable"))
            assertEquals(0f, view.getChildAt(0).alpha)

            assertTrue("navigation must be consumed while the continuity cover owns the window", view.goToAdjacentPage(1))

            assertEquals("the hidden target must stay parked on its first page", 0, view.currentPageIndex())
            assertNotNull("the continuity cover must remain installed", view.privateField("conversionSnapshotDrawable"))
            assertEquals(0f, view.getChildAt(0).alpha)
            assertNull("no turn may start from an unstable replacement chapter", view.privateField("slideDrawable"))
        } finally {
            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `self paging clean tap reports consumed to the outer reader host`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE, onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_UP, x, y))

        assertEquals(
            "a self-paging EPUB tap must suppress the outer ReaderTapContainer from handling the same UP again",
            true,
            view.getTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed),
        )
        assertEquals("the same tap should still report the self-paging NEXT action", listOf(EpubFlowTapZone.NEXT), tapZones)
    }

    @Test
    fun `self paging center tap leaves outer reader host available for chrome toggle`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE, onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.50f
        val y = view.height * 0.50f

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_UP, x, y))

        assertEquals(
            "center MENU taps must not suppress the outer ReaderTapContainer chrome toggle",
            false,
            view.getTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed),
        )
        assertEquals("the inner EPUB surface should still report MENU for its engine callback", listOf(EpubFlowTapZone.MENU), tapZones)
    }

    @Test
    fun `scroll mode edge tap toggles menu instead of paging`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE, onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.mode = EpubFlowView.Mode.SCROLL

        // Left edge and right edge taps that would page PREV/NEXT in PAGED mode.
        cleanTap(view, view.width * 0.1f)
        cleanTap(view, view.width * 0.9f)

        assertEquals(
            "scroll mode has no pages: edge taps must toggle the menu, never page-jump",
            listOf(EpubFlowTapZone.MENU, EpubFlowTapZone.MENU),
            tapZones,
        )
    }

    @Test
    fun `paged mode edge tap still pages`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE, onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        cleanTap(view, view.width * 0.1f)
        cleanTap(view, view.width * 0.9f)
        cleanTap(view, view.width * 0.5f)

        assertEquals(
            "paged mode edge taps must still page PREV/NEXT, center toggles menu",
            listOf(EpubFlowTapZone.PREV, EpubFlowTapZone.NEXT, EpubFlowTapZone.MENU),
            tapZones,
        )
    }

    private fun cleanTap(view: EpubFlowView, x: Float) {
        val downTime = SystemClock.uptimeMillis()
        val y = view.height * 0.5f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_UP, x, y))
    }

    @Test
    fun `first clean tap during initial reveal reports consumed and starts animation`() {
        lateinit var view: EpubFlowView
        view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTapZone = { zone ->
                if (zone == EpubFlowTapZone.NEXT) view.goToAdjacentPage(1)
            },
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.setPrivateField("awaitingReveal", true)
        view.setPrivateField("pendingInitialPageTurnDelta", null)
        view.getChildAt(0).alpha = 0f
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_UP, x, y))

        assertEquals(
            "the first real tap path should suppress the outer host from replaying the same UP",
            true,
            view.getTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed),
        )
        assertEquals("first tap should finish the reveal before snapshotting", false, view.privateBool("awaitingReveal"))
        assertEquals(1f, view.getChildAt(0).alpha)
        assertNotNull("first tap should start the normal slide animation", view.privateField("flipAnimator"))
    }

    @Test
    fun `queued first turn at chapter boundary is forwarded after measured viewport`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE, onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val finalPage = view.pageCount() - 1
        view.layout(0, 0, 360, 0)
        view.setPrivateField("awaitingReveal", true)
        view.setPrivateField("pendingLandOnLast", true)
        view.setPrivateField("pendingInitialPageTurnDelta", null)

        val accepted = view.goToAdjacentPage(1)

        assertEquals("the boundary first turn should still be queued during zero-height settle", true, accepted)
        assertEquals("boundary turn must not be forwarded before the measured viewport exists", emptyList<EpubFlowTapZone>(), tapZones)

        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("boundary turn should finish reveal once the measured final page is positioned", false, view.privateBool("awaitingReveal"))
        assertEquals(1f, view.getChildAt(0).alpha)
        assertEquals("after settling on the final page, NEXT must be forwarded for cross-spine advance", listOf(EpubFlowTapZone.NEXT), tapZones)
        assertEquals(finalPage, view.currentPageIndex())
        assertEquals(view.textureTopPxForPageForTest(finalPage), view.scrollY)
    }

    @Test
    fun `initial restore reveals immediately when layout settled and no decodes pending`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = EpubFlowView(
            context = context,
            onTapZone = {},
            onTopOffsetChanged = {},
            onSelectionRange = { _, _ -> },
        )
        activity.addContentView(view, ViewGroup.LayoutParams(360, 120))
        view.textView.textSize = 18f
        val chapterText = (1..80).joinToString("\n") { "Line $it marker text." }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(EpubDisplayBlock.Text(chapterText, headingLevel = null, paragraphIndex = 0)),
        )
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        val restoreOffset = flow.offsetForParagraph(0, chapterText.indexOf("Line 30").coerceAtLeast(0))

        // No pending decodes → signal-driven reveal fires immediately at the restore position.
        view.setChapter(flow, flow.text, pageHeightPx = 120, restoreOffset = restoreOffset)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "with no pending decodes, content must reveal immediately after positioning",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    fun `snapshotting another page restores the static parked page`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))

        view.goToPage(1)

        val snapshot = view.snapshotPageAt(pageTwoTop)

        assertNotNull(snapshot)
        snapshot?.recycle()
        assertEquals("MoonReader-style temporary screenshot scroll must restore the visible page", 1, view.currentPageIndex())
        assertEquals("snapshotting a target page must not leave the live paper/content shifted", pageOneTop, view.scrollY)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `snapshotting a page recycles its allocated bitmap when drawing fails`() {
        val view = pagedFlowView()
        val failingDrawable = CaptureTargetBitmapThenThrowDrawable()
        view.background = failingDrawable
        val pageTop = requireNotNull(view.pageTopPxAt(0))

        assertNull(view.snapshotPageAt(pageTop))

        val allocated = requireNotNull(failingDrawable.targetBitmap)
        assertEquals(view.width, allocated.width)
        assertEquals(view.height, allocated.height)
        assertTrue("the failed full-page allocation must be recycled immediately", allocated.isRecycled)
    }

    @Test
    fun `rapid discrete paper turn while animator is active does not restart or double advance`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))

        assertTrue(view.goToAdjacentPage(1))
        val firstPaper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
        assertTrue(view.goToAdjacentPage(1))

        assertTrue("the second request must not replace the active renderer", firstPaper === view.privateField("curlDrawable"))
        assertEquals(1, view.currentPageIndex())
        assertEquals(pageOneTop, view.scrollY)

        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals(1, view.currentPageIndex())
        assertEquals(pageOneTop, view.scrollY)
    }

    @Test
    fun `async reflow recycles stale turn texture cache before rebuilding pagination`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val oldFromTop = requireNotNull(view.pageTopPxAt(0))
        val oldTargetTop = requireNotNull(view.pageTopPxAt(1))
        val staleFront = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        val staleRevealed = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        view.setPrivateField("cachedFrontBitmap", staleFront)
        view.setPrivateField("cachedRevealedBitmap", staleRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        view.setPrivateField("cachedFromTopPx", oldFromTop)
        view.setPrivateField("cachedTargetTopPx", oldTargetTop)
        val currentLayoutHeight = requireNotNull(view.textView.layout).height
        view.setPrivateField("paginatedLayoutHeight", currentLayoutHeight - 1)

        view.runReflowRunnable(idlePostedWork = false)

        assertTrue("stale front texture should be recycled after reflow", staleFront.isRecycled)
        assertTrue("stale revealed texture should be recycled after reflow", staleRevealed.isRecycled)
        assertEquals(-1, view.privateInt("cachedFromTopPx"))
        assertEquals(-1, view.privateInt("cachedTargetTopPx"))
    }

    @Test
    fun `free fling defers reflow and chapter or mode replacement stops its old trajectory`() {
        val reflowView = pagedFlowView()
        reflowView.goToPage(1)
        reflowView.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", reflowView.privateEnumName("pagedMotionState"))
        val liveLayoutHeight = requireNotNull(reflowView.textView.layout).height
        val stalePaginatedHeight = liveLayoutHeight - 1
        reflowView.setPrivateField("paginatedLayoutHeight", stalePaginatedHeight)

        reflowView.runReflowRunnable(idlePostedWork = false)

        val reflowDeferred = reflowView.privateEnumName("pagedMotionState") == "FLING_FREE" &&
            reflowView.privateInt("paginatedLayoutHeight") == stalePaginatedHeight

        val chapterView = pagedFlowView()
        chapterView.goToPage(1)
        chapterView.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        val chapterFlow = chapterView.privateField("flow") as EpubChapterFlow
        val chapterText = chapterView.textView.text
        chapterView.setChapter(chapterFlow, chapterText, pageHeightPx = chapterView.height)
        val chapterStartY = chapterView.scrollY
        val chapterTrace = chapterView.computeScrollTraceWithoutPostedWork()

        val modeView = pagedFlowView()
        modeView.goToPage(1)
        modeView.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        modeView.mode = EpubFlowView.Mode.SCROLL
        val modeStartY = modeView.scrollY
        val modeTrace = modeView.computeScrollTraceWithoutPostedWork()

        assertTrue(
            "FLING_FREE reflow must defer, and chapter/mode replacement must abort the old trajectory; " +
                "reflowDeferred=$reflowDeferred reflowState=${reflowView.privateEnumName("pagedMotionState")} " +
                "paginatedHeight=${reflowView.privateInt("paginatedLayoutHeight")} staleHeight=$stalePaginatedHeight " +
                "chapterStartY=$chapterStartY chapterTrace=$chapterTrace " +
                "modeStartY=$modeStartY modeTrace=$modeTrace",
            reflowDeferred &&
                chapterTrace.all { it == chapterStartY } &&
                modeTrace.all { it == modeStartY },
        )
    }

    @Test
    fun `text reflow waits for dragging free finger ownership to release`() {
        val view = pagedFlowView()
        view.goToPage(1)
        val x = view.width * 0.50f
        val downY = view.height * 0.60f
        val dragY = view.height * 0.15f
        val downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, downY))
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 300L, MotionEvent.ACTION_MOVE, x, dragY))
        assertEquals("DRAGGING_FREE", view.privateEnumName("pagedMotionState"))
        val fingerOwnedY = view.scrollY
        val oldPaginatedHeight = view.privateInt("paginatedLayoutHeight")

        view.textView.text = view.textView.text.toString() + "\n" +
            (81..120).joinToString("\n") { "Late reflow line $it marker text." }
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        val liveLayoutHeight = requireNotNull(view.textView.layout).height
        assertTrue(
            "fixture needs text layout height to change; old=$oldPaginatedHeight live=$liveLayoutHeight",
            liveLayoutHeight != oldPaginatedHeight,
        )
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)
        val duringDragY = view.scrollY
        val paginatedDuringDrag = view.privateInt("paginatedLayoutHeight")

        view.dispatchTouchEvent(motionEvent(downTime, downTime + 1_100L, MotionEvent.ACTION_UP, x, dragY))
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)
        val paginatedAfterRelease = view.privateInt("paginatedLayoutHeight")

        assertTrue(
            "layout debounce must preserve the exact finger-owned viewport until release, then accept reflow; " +
                "fingerOwnedY=$fingerOwnedY duringDragY=$duringDragY " +
                "oldPaginatedHeight=$oldPaginatedHeight liveLayoutHeight=$liveLayoutHeight " +
                "paginatedDuringDrag=$paginatedDuringDrag paginatedAfterRelease=$paginatedAfterRelease " +
                "state=${view.privateEnumName("pagedMotionState")}",
            duringDragY == fingerOwnedY &&
                paginatedDuringDrag == oldPaginatedHeight &&
                paginatedAfterRelease == liveLayoutHeight,
        )
    }

    @Test
    fun `deferred size reanchor during free fling owns its aligned viewport`() {
        val chapterText = (1..80).joinToString("\n") { line ->
            "Line $line has enough marker text to wrap differently when the viewport width changes."
        }
        val view = pagedFlowView(text = chapterText)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(2)
        val oldFlingY = view.scrollY
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))
        SystemClock.sleep(16L)
        view.computeScroll()
        val offsetAtResize = view.topLayoutOffset()

        view.measure(exactly(240), exactly(120))
        view.layout(0, 0, 240, 120)
        shadowOf(Looper.getMainLooper()).idle()
        repeat(160) {
            if (view.privateEnumName("pagedMotionState") != "FLING_FREE") return@repeat
            SystemClock.sleep(16L)
            view.computeScroll()
        }
        assertEquals("ALIGNED", view.privateEnumName("pagedMotionState"))
        val reanchoredY = view.scrollY
        val reanchoredOffset = view.topLayoutOffset()

        shadowOf(Looper.getMainLooper()).idle()
        val finalY = view.scrollY
        val trace = view.computeScrollTraceWithoutPostedWork()

        assertTrue(
            "the deferred size reanchor must retire the pre-resize free-fling trajectory; " +
                "oldFlingY=$oldFlingY offsetAtResize=$offsetAtResize " +
                "reanchoredY=$reanchoredY reanchoredOffset=$reanchoredOffset " +
                "finalY=$finalY trace=$trace visibleOffset=${view.topLayoutOffset()}",
            trace.all { it == finalY } &&
                view.topLayoutOffset() == reanchoredOffset,
        )
    }

    @Test
    fun `dispose prevents text clear layout and queued work from reviving reflow or texture owners`() {
        val view = pagedFlowView()
        view.recycleCachedTexturesForTest()
        view.preCachePageTexturesForTest(idlePostedWork = false)
        assertTrue("fixture must leave one old texture callback queued", view.privateBool("pageTexturePrecachePending"))
        val layoutGenerationAtDispose = view.privateField("pageLayoutGeneration") as Long
        val paginatedHeightAtDispose = view.privateInt("paginatedLayoutHeight")

        view.dispose()
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)

        assertEquals(
            "disposed text clear and its layout callback must not repaginate the retired chapter",
            layoutGenerationAtDispose,
            view.privateField("pageLayoutGeneration") as Long,
        )
        assertEquals(
            "disposed queued work must not replace the retired pagination geometry",
            paginatedHeightAtDispose,
            view.privateInt("paginatedLayoutHeight"),
        )
        assertFalse("disposed queued work must not leave texture work pending", view.privateBool("pageTexturePrecachePending"))
        assertNull("disposed queued work must not recreate a front texture", view.privateField("cachedFrontBitmap"))
        assertNull("disposed queued work must not recreate a target texture", view.privateField("cachedRevealedBitmap"))
        assertNull("disposed queued work must not recreate a backward texture", view.privateField("cachedBackwardBitmap"))
    }

    @Test
    fun `dispose silences queued size reanchor and pending boundary navigation`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTapZone = tapZones::add,
            onTopOffsetChanged = reportedOffsets::add,
        )
        view.goToLastPage()
        view.layout(0, 0, 360, 0)
        view.setPrivateField("awaitingReveal", true)
        view.setPrivateField("pendingLandOnLast", true)
        view.setPrivateField("pendingInitialPageTurnDelta", null)
        assertTrue(view.goToAdjacentPage(1))
        assertEquals(1, view.privateInt("pendingInitialPageTurnDelta"))
        assertTrue("the boundary callback must still be pending before size settles", tapZones.isEmpty())

        view.measure(exactly(420), exactly(120))
        view.layout(0, 0, 420, 120)
        assertEquals(
            "the size-change settle must still own the queued boundary request before posted work drains",
            1,
            view.privateInt("pendingInitialPageTurnDelta"),
        )
        reportedOffsets.clear()
        tapZones.clear()

        view.dispose()
        val retiredY = view.scrollY
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

        assertTrue(
            "posted size reanchor work must be inert after dispose; " +
                "retiredY=$retiredY finalY=${view.scrollY} " +
                "reportedOffsets=$reportedOffsets tapZones=$tapZones",
            view.scrollY == retiredY &&
                reportedOffsets.isEmpty() &&
                tapZones.isEmpty(),
        )
    }

    @Test
    fun `discrete paper turn recycles stale cached textures when top keys mismatch`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        val pageZeroTop = requireNotNull(view.pageTopPxAt(0))
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val staleFront = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        val staleRevealed = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        view.setPrivateField("cachedFrontBitmap", staleFront)
        view.setPrivateField("cachedRevealedBitmap", staleRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        view.setPrivateField("cachedFromTopPx", pageZeroTop + 1)
        view.setPrivateField("cachedTargetTopPx", pageOneTop + 1)

        assertTrue(view.goToAdjacentPage(1))

        val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
        assertEquals(view.width, paper.privateBitmap("frontBitmap").width)
        assertEquals(view.height, paper.privateBitmap("frontBitmap").height)
        assertEquals(view.width, paper.privateBitmap("revealedBitmap").width)
        assertEquals(view.height, paper.privateBitmap("revealedBitmap").height)
        assertTrue("stale front texture should be recycled after key mismatch", staleFront.isRecycled)
        assertTrue("stale revealed texture should be recycled after key mismatch", staleRevealed.isRecycled)
    }

    @Test
    fun `discrete paper turn ignores recycled cached textures and snapshots live pages`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        val pageZeroTop = requireNotNull(view.pageTopPxAt(0))
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val recycledFront = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565).apply { recycle() }
        val recycledRevealed = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565).apply { recycle() }
        view.setPrivateField("cachedFrontBitmap", recycledFront)
        view.setPrivateField("cachedRevealedBitmap", recycledRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        view.setPrivateField("cachedFromTopPx", pageZeroTop)
        view.setPrivateField("cachedTargetTopPx", pageOneTop)

        assertTrue(view.goToAdjacentPage(1))

        val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
        assertEquals(view.width, paper.privateBitmap("frontBitmap").width)
        assertEquals(view.height, paper.privateBitmap("frontBitmap").height)
        assertEquals(view.width, paper.privateBitmap("revealedBitmap").width)
        assertEquals(view.height, paper.privateBitmap("revealedBitmap").height)
    }

    @Test
    fun `discrete paper turn treats partial cached textures as stale and snapshots live pages`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        val pageZeroTop = requireNotNull(view.pageTopPxAt(0))
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val partialRevealed = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        view.setPrivateField("cachedFrontBitmap", null)
        view.setPrivateField("cachedRevealedBitmap", partialRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        view.setPrivateField("cachedFromTopPx", pageZeroTop)
        view.setPrivateField("cachedTargetTopPx", pageOneTop)

        assertTrue(view.goToAdjacentPage(1))

        val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
        assertEquals(view.width, paper.privateBitmap("frontBitmap").width)
        assertEquals(view.height, paper.privateBitmap("frontBitmap").height)
        assertEquals(view.width, paper.privateBitmap("revealedBitmap").width)
        assertEquals(view.height, paper.privateBitmap("revealedBitmap").height)
        assertTrue("partial revealed texture should be recycled before live snapshot", partialRevealed.isRecycled)
    }

    @Test
    fun `precache refreshes recycled cached textures even when top keys match`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        val pageZeroTop = requireNotNull(view.pageTopPxAt(0))
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val recycledFront = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565).apply { recycle() }
        val recycledRevealed = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565).apply { recycle() }
        view.setPrivateField("cachedFrontBitmap", recycledFront)
        view.setPrivateField("cachedRevealedBitmap", recycledRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        view.setPrivateField("cachedFromTopPx", pageZeroTop)
        view.setPrivateField("cachedTargetTopPx", pageOneTop)

        view.preCachePageTexturesForTest()

        val cachedFront = view.privateField("cachedFrontBitmap") as Bitmap?
        val cachedRevealed = view.privateField("cachedRevealedBitmap") as Bitmap?
        assertTrue("precache should replace the recycled front texture", cachedFront !== recycledFront)
        assertTrue("precache should replace the recycled revealed texture", cachedRevealed !== recycledRevealed)
        assertTrue("precache should create a live front texture", cachedFront != null && !cachedFront.isRecycled)
        assertTrue("precache should create a live revealed texture", cachedRevealed != null && !cachedRevealed.isRecycled)
        assertEquals(view.width, cachedFront?.width)
        assertEquals(view.height, cachedFront?.height)
        assertEquals(view.width, cachedRevealed?.width)
        assertEquals(view.height, cachedRevealed?.height)
    }

    @Test
    fun `duplicate precache requests coalesce before allocating page shots`() {
        val view = pagedFlowView()
        view.recycleCachedTexturesForTest()
        val background = RecordingBoundsTopDrawable()
        view.background = background

        view.preCachePageTexturesForTest(idlePostedWork = false)
        view.preCachePageTexturesForTest(idlePostedWork = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "page zero should render current+next once, not duplicate and overwrite two full pairs",
            2,
            background.boundsTops.size,
        )
    }

    @Test
    fun `dequeued precache front callback cannot overwrite the replacement request front`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        val background = RecordingTargetBitmapDrawable()
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()

        try {
            view.preCachePageTexturesForTest(idlePostedWork = false)
            val dequeuedAFront = view.privateField("capturePrecacheFrontRunnable") as Runnable

            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest(idlePostedWork = false)
            val bRequest = checkNotNull(view.privateField("pendingPageTexturePrecache"))
            shadowOf(Looper.getMainLooper()).runOneTask()
            val bFront = checkNotNull(bRequest.reflectedField("frontBitmap") as Bitmap?)
            val drawsBeforeLateA = background.targetBitmaps.toList()

            dequeuedAFront.run()

            assertTrue(
                "an already-dequeued A precache callback must not read or overwrite replacement B; " +
                    "sameOwner=${view.privateField("pendingPageTexturePrecache") === bRequest} " +
                    "sameFront=${bRequest.reflectedField("frontBitmap") === bFront} " +
                    "drawsBefore=$drawsBeforeLateA drawsAfter=${background.targetBitmaps} " +
                    "frontRecycled=${bFront.isRecycled} pending=${view.privateBool("pageTexturePrecachePending")} " +
                    "committed=[${view.privateField("cachedFrontBitmap")}," +
                    "${view.privateField("cachedBackwardBitmap")},${view.privateField("cachedRevealedBitmap")} ]",
                view.privateField("pendingPageTexturePrecache") === bRequest &&
                    bRequest.reflectedField("frontBitmap") === bFront &&
                    background.targetBitmaps == drawsBeforeLateA &&
                    !bFront.isRecycled &&
                    view.privateBool("pageTexturePrecachePending") &&
                    view.privateField("cachedFrontBitmap") == null &&
                    view.privateField("cachedBackwardBitmap") == null &&
                    view.privateField("cachedRevealedBitmap") == null,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `precache snapshot invalidated during draw recycles A without touching replacement B`() {
        lateinit var view: EpubFlowView
        var armed = false
        var replaced = false
        var replacementRequest: Any? = null
        var allocatedByA: Bitmap? = null
        var requestA: Any? = null
        val background = RecordingTargetBitmapDrawable {
            if (armed && !replaced) {
                replaced = true
                view.recycleCachedTexturesForTest()
                view.preCachePageTexturesForTest(idlePostedWork = false)
                replacementRequest = view.privateField("pendingPageTexturePrecache")
            }
        }
        view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        armed = true

        try {
            view.preCachePageTexturesForTest(idlePostedWork = false)
            requestA = checkNotNull(view.privateField("pendingPageTexturePrecache"))

            shadowOf(Looper.getMainLooper()).runOneTask()
            allocatedByA = background.targetBitmaps.single()
            val requestB = checkNotNull(replacementRequest)

            assertTrue("callback must replace precache request A with B", replaced && requestB !== requestA)
            assertTrue(
                "A returning from snapshot draw must not clear or populate replacement B; " +
                    "owner=${view.privateField("pendingPageTexturePrecache")} requestB=$requestB " +
                    "frontB=${requestB.reflectedField("frontBitmap")} " +
                    "targetB=${requestB.reflectedField("targetBitmap")} " +
                    "previousB=${requestB.reflectedField("previousBitmap")} " +
                    "pending=${view.privateBool("pageTexturePrecachePending")}",
                view.privateField("pendingPageTexturePrecache") === requestB &&
                    requestB.reflectedField("frontBitmap") == null &&
                    requestB.reflectedField("targetBitmap") == null &&
                    requestB.reflectedField("previousBitmap") == null &&
                    view.privateBool("pageTexturePrecachePending") &&
                    view.privateField("capturePrecacheFrontRunnable") != null &&
                    view.privateField("cachedFrontBitmap") == null &&
                    view.privateField("cachedRevealedBitmap") == null &&
                    view.privateField("cachedBackwardBitmap") == null,
            )
            assertTrue(
                "the stale background snapshot allocated by A must be recycled before its callback returns",
                allocatedByA!!.isRecycled,
            )
        } finally {
            allocatedByA?.let { if (!it.isRecycled) it.recycle() }
            (requestA?.reflectedField("frontBitmap") as Bitmap?)?.let { if (!it.isRecycled) it.recycle() }
            view.dispose()
        }
    }

    @Test
    fun `staged precache is transferred or recycled before direct and discrete page shots`() {
        data class Scenario(
            val name: String,
            val direct: Boolean,
            val forward: Boolean,
            val stagedFrames: Int,
        )

        val scenarios = listOf(
            Scenario("complete forward discrete", direct = false, forward = true, stagedFrames = 2),
            Scenario("complete forward direct", direct = true, forward = true, stagedFrames = 2),
            Scenario("incomplete backward direct", direct = true, forward = false, stagedFrames = 1),
        )
        val failures = mutableListOf<String>()

        scenarios.forEach { scenario ->
            var stagedFront: Bitmap? = null
            val partialRecycleStateBeforeFallbackDraw = mutableListOf<Boolean>()
            val background = RecordingTargetBitmapDrawable {
                stagedFront?.let { partialRecycleStateBeforeFallbackDraw += it.isRecycled }
            }
            val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
            assertTrue("${scenario.name}: pageCount=${view.pageCount()}", view.pageCount() > 3)
            view.goToPage(1)
            view.background = background
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            background.targetBitmaps.clear()

            try {
                view.preCachePageTexturesForTest(idlePostedWork = false)
                repeat(scenario.stagedFrames) { shadowOf(Looper.getMainLooper()).runOneTask() }
                val staged = background.targetBitmaps.toList()
                stagedFront = staged.first()
                val started = if (scenario.direct) {
                    view.beginInteractiveCurl(
                        forward = scenario.forward,
                        anchorX = if (scenario.forward) view.width.toFloat() else 0f,
                    )
                } else {
                    view.goToAdjacentPage(if (scenario.forward) 1 else -1)
                }
                val paper = view.privateField("curlDrawable") as PageCurlDrawable?
                val pending = view.privateField("pendingPageTexturePrecache")
                val pendingFlag = view.privateBool("pageTexturePrecachePending")

                val ownerWasResolvedBeforeForegroundCapture = if (scenario.stagedFrames == 2) {
                    val stagedTarget = staged[1]
                    started && paper != null &&
                        background.targetBitmaps.size == 2 &&
                        pending == null && !pendingFlag &&
                        paper.privateBitmap("frontBitmap") === stagedFront &&
                        paper.privateBitmap("revealedBitmap") === stagedTarget &&
                        !stagedFront!!.isRecycled && !stagedTarget.isRecycled
                } else {
                    started && paper != null &&
                        background.targetBitmaps.size == 3 &&
                        pending == null && !pendingFlag &&
                        stagedFront!!.isRecycled &&
                        partialRecycleStateBeforeFallbackDraw.size == 2 &&
                        partialRecycleStateBeforeFallbackDraw.all { it }
                }

                if (!ownerWasResolvedBeforeForegroundCapture) {
                    failures +=
                        "${scenario.name}: started=$started staged=${staged.size} " +
                            "draws=${background.targetBitmaps.size} pending=$pending pendingFlag=$pendingFlag " +
                            "stagedFrontRecycled=${stagedFront!!.isRecycled} " +
                            "recycledBeforeFallback=$partialRecycleStateBeforeFallbackDraw " +
                            "paperFront=${paper?.runCatching { privateBitmap("frontBitmap") }?.getOrNull()} " +
                            "paperTarget=${paper?.runCatching { privateBitmap("revealedBitmap") }?.getOrNull()}"
                }
            } finally {
                view.dispose()
            }
        }

        assertTrue(
            "a turn must transfer a complete staged pair or recycle an incomplete owner before fallback: $failures",
            failures.isEmpty(),
        )
    }

    @Test
    fun `large viewport precache keeps current and forward target without opposite owner`() {
        val view = pagedFlowView(
            text = (1..800).joinToString("\n") { "Line $it marker text." },
            viewportWidth = 1_600,
            viewportHeight = 2_560,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        view.recycleCachedTexturesForTest()

        view.preCachePageTexturesForTest()

        val current = view.privateField("cachedFrontBitmap") as Bitmap?
        val forward = view.privateField("cachedRevealedBitmap") as Bitmap?
        val opposite = view.privateField("cachedBackwardBitmap") as Bitmap?
        assertTrue(
            "a 1600x2560 ARGB_8888 viewport exceeds the page-shot budget at three owners; " +
                "current=$current forward=$forward opposite=$opposite " +
                "configs=${listOf(current?.config, forward?.config, opposite?.config)}",
            current?.config == Bitmap.Config.ARGB_8888 &&
                forward?.config == Bitmap.Config.ARGB_8888 &&
                opposite == null,
        )
    }

    @Test
    fun `middle page precache captures one shot per frame and commits all three atomically`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        val background = RecordingBoundsTopDrawable()
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.boundsTops.clear()

        fun cacheOwners(): List<Bitmap?> = listOf(
            view.privateField("cachedFrontBitmap") as Bitmap?,
            view.privateField("cachedBackwardBitmap") as Bitmap?,
            view.privateField("cachedRevealedBitmap") as Bitmap?,
        )

        try {
            view.preCachePageTexturesForTest(idlePostedWork = false)

            shadowOf(Looper.getMainLooper()).runOneTask()
            val drawsAfterFirst = background.boundsTops.size
            val cacheAfterFirst = cacheOwners()
            val pendingAfterFirst = view.privateBool("pageTexturePrecachePending")

            shadowOf(Looper.getMainLooper()).runOneTask()
            val drawsAfterSecond = background.boundsTops.size
            val cacheAfterSecond = cacheOwners()
            val pendingAfterSecond = view.privateBool("pageTexturePrecachePending")

            shadowOf(Looper.getMainLooper()).runOneTask()
            val drawsAfterThird = background.boundsTops.size
            val cacheAfterThird = cacheOwners()
            val pendingAfterThird = view.privateBool("pageTexturePrecachePending")

            assertTrue(
                "middle-page precache must capture one page shot per frame and publish only a complete triple; " +
                    "draws=[$drawsAfterFirst,$drawsAfterSecond,$drawsAfterThird] " +
                    "cacheAfterFirst=$cacheAfterFirst cacheAfterSecond=$cacheAfterSecond " +
                    "cacheAfterThird=$cacheAfterThird " +
                    "pending=[$pendingAfterFirst,$pendingAfterSecond,$pendingAfterThird]",
                drawsAfterFirst == 1 && cacheAfterFirst.all { it == null } && pendingAfterFirst &&
                    drawsAfterSecond == 2 && cacheAfterSecond.all { it == null } && pendingAfterSecond &&
                    drawsAfterThird == 3 && cacheAfterThird.all { it != null && !it.isRecycled } &&
                    !pendingAfterThird,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `precache skips hidden initial reveal layer`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        view.getChildAt(0).alpha = 0f

        view.preCachePageTexturesForTest()

        assertNull("hidden reveal layer must not be cached as the front texture", view.privateField("cachedFrontBitmap"))
        assertNull("hidden reveal layer must not be cached as the revealed texture", view.privateField("cachedRevealedBitmap"))
    }

    @Test
    fun `precache discards partial texture pair when revealed snapshot fails`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        view.background = ThrowOnSecondDrawDrawable()

        view.preCachePageTexturesForTest()

        assertEquals(null, view.privateField("cachedFrontBitmap"))
        assertEquals(null, view.privateField("cachedRevealedBitmap"))
        assertEquals(-1, view.privateInt("cachedFromPage"))
        assertEquals(-1, view.privateInt("cachedTargetPage"))
        assertEquals(-1, view.privateInt("cachedFromTopPx"))
        assertEquals(-1, view.privateInt("cachedTargetTopPx"))
    }

    @Test
    fun `pending precache does not commit while discrete paper turn is active`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()

        view.preCachePageTexturesForTest(idlePostedWork = false)
        assertTrue(view.goToAdjacentPage(1))
        assertTrue("the local PAPER animator should keep a turn active", (view.privateField("flipAnimator") as android.animation.ValueAnimator).isRunning)
        shadowOf(Looper.getMainLooper()).runOneTask()

        assertEquals(null, view.privateField("cachedFrontBitmap"))
        assertEquals(null, view.privateField("cachedRevealedBitmap"))
        assertEquals(-1, view.privateInt("cachedFromPage"))
        assertEquals(-1, view.privateInt("cachedTargetPage"))
        assertEquals(-1, view.privateInt("cachedFromTopPx"))
        assertEquals(-1, view.privateInt("cachedTargetTopPx"))
    }

    @Test
    fun `interactive slide keeps ownership while precache and reflow are pending`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.recycleCachedTexturesForTest()
        reportedOffsets.clear()
        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.55f)
        val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        val progressBeforeBackgroundWork = slide.progress
        try {
            view.preCachePageTexturesForTest()

            assertNull(
                "pre-cache must not replace turn ownership with a new front page shot while the finger is down",
                view.privateField("cachedFrontBitmap"),
            )
            assertNull(
                "pre-cache must not replace turn ownership with a new revealed page shot while the finger is down",
                view.privateField("cachedRevealedBitmap"),
            )

            val currentLayoutHeight = requireNotNull(view.textView.layout).height
            view.setPrivateField("paginatedLayoutHeight", currentLayoutHeight - 1)
            view.runReflowRunnable()

            assertEquals(
                "reflow must leave the active finger-tracked drawable installed",
                slide,
                view.privateField("slideDrawable"),
            )
            assertEquals(
                "reflow must not move the page shot beneath the live finger",
                progressBeforeBackgroundWork,
                slide.progress,
                0.001f,
            )
            assertTrue(
                "reflow must not report or commit new locator geometry while the finger owns the turn; " +
                    "reported=$reportedOffsets",
                reportedOffsets.isEmpty(),
            )
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `precache waits until pending image decodes finish`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        view.pendingDecodesProvider = { true }

        view.preCachePageTexturesForTest()

        assertNull("transparent image placeholders must not become cached turn fronts", view.privateField("cachedFrontBitmap"))
        assertNull("transparent image placeholders must not become cached revealed pages", view.privateField("cachedRevealedBitmap"))
    }

    @Test
    fun `precache waits until image reflow pagination matches the live layout`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.recycleCachedTexturesForTest()
        val layoutHeight = requireNotNull(view.textView.layout).height
        view.setPrivateField("paginatedLayoutHeight", layoutHeight - 1)

        view.preCachePageTexturesForTest()

        assertNull("stale pagination must not produce a front page shot", view.privateField("cachedFrontBitmap"))
        assertNull("stale pagination must not produce a revealed page shot", view.privateField("cachedRevealedBitmap"))
    }

    @Test
    fun `async image result invalidates same geometry turn textures`() {
        val view = pagedFlowView()
        val cachedFront = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cachedRevealed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        view.setPrivateField("cachedFrontBitmap", cachedFront)
        view.setPrivateField("cachedRevealedBitmap", cachedRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)

        view.refreshAfterAsyncImageResult()

        assertTrue("the placeholder front texture must be retired when image pixels arrive", cachedFront.isRecycled)
        assertTrue("the placeholder revealed texture must be retired when image pixels arrive", cachedRevealed.isRecycled)
        assertNull(view.privateField("cachedFrontBitmap"))
        assertNull(view.privateField("cachedRevealedBitmap"))
    }

    @Test
    fun `switching out of paged mode recycles cached turn textures`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val cachedFront = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        val cachedRevealed = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        view.setPrivateField("cachedFrontBitmap", cachedFront)
        view.setPrivateField("cachedRevealedBitmap", cachedRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        view.setPrivateField("cachedFromTopPx", requireNotNull(view.pageTopPxAt(0)))
        view.setPrivateField("cachedTargetTopPx", requireNotNull(view.pageTopPxAt(1)))

        view.mode = EpubFlowView.Mode.SCROLL

        assertTrue("mode switch should recycle cached front texture", cachedFront.isRecycled)
        assertTrue("mode switch should recycle cached revealed texture", cachedRevealed.isRecycled)
        assertEquals(-1, view.privateInt("cachedFromPage"))
        assertEquals(-1, view.privateInt("cachedTargetPage"))
        assertEquals(-1, view.privateInt("cachedFromTopPx"))
        assertEquals(-1, view.privateInt("cachedTargetTopPx"))
    }

    @Test
    fun `slow temporary scroll release keeps its exact non canonical free rest`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        val downTime = SystemClock.uptimeMillis()
        val startX = view.width * 0.50f
        val startY = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY))
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 300L,
                MotionEvent.ACTION_MOVE,
                startX,
                view.height * 0.30f,
            ),
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 700L,
                MotionEvent.ACTION_MOVE,
                startX,
                view.height * 0.10f,
            ),
        )
        val canonicalTops = (0 until view.pageCount()).mapNotNull { view.pageTopPxAt(it) }.toSet()
        while (view.scrollY in canonicalTops) view.scrollTo(0, view.scrollY + 1)
        val releasedAt = view.scrollY
        assertTrue("test must release between canonical page tops", releasedAt !in canonicalTops)
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 1_100L,
                MotionEvent.ACTION_UP,
                startX,
                view.height * 0.10f,
            ),
        )

        assertEquals("temporary-scroll UP must not be treated as a clean tap", emptyList<EpubFlowTapZone>(), tapZones)
        assertEquals(
            "a slow UP must retain the arbitrary viewport instead of snapping to a paginator anchor",
            releasedAt,
            view.scrollY,
        )
        assertTrue("FREE_REST must re-arm full-line clipping", view.privateBool("pageClipActive"))
    }

    @Test
    fun `fast temporary scroll release continues with native fling without snapping`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
        view.goToPage(1)
        val x = view.width * 0.50f
        val downY = view.height * 0.50f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, downY))
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 16L, MotionEvent.ACTION_MOVE, x, view.height * 0.35f),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 32L, MotionEvent.ACTION_MOVE, x, view.height * 0.15f),
        )
        val canonicalTops = (0 until view.pageCount()).mapNotNull { view.pageTopPxAt(it) }.toSet()
        while (view.scrollY in canonicalTops) view.scrollTo(0, view.scrollY + 1)
        val releasedAt = view.scrollY
        assertTrue("test must release between canonical page tops", releasedAt !in canonicalTops)

        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 40L, MotionEvent.ACTION_UP, x, view.height * 0.10f),
        )

        assertEquals("UP must not synchronously snap away from the finger's release pixel", releasedAt, view.scrollY)
        val stateAfterUp = (view.privateField("pagedMotionState") as Enum<*>).name
        val clipAfterUp = view.privateBool("pageClipActive")
        val childHeight = view.getChildAt(0).height
        val scrollRange = (childHeight - view.height).coerceAtLeast(0)
        val scrollTrace = mutableListOf(view.scrollY)
        val stateTrace = mutableListOf(stateAfterUp)
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(16L, TimeUnit.MILLISECONDS)
            view.computeScroll()
            scrollTrace += view.scrollY
            stateTrace += (view.privateField("pagedMotionState") as Enum<*>).name
        }
        assertTrue(
            "a fast upward release must keep advancing after the finger lifts; " +
                "stateAfterUp=$stateAfterUp clipAfterUp=$clipAfterUp childHeight=$childHeight " +
                "viewportHeight=${view.height} scrollRange=$scrollRange releasedAt=$releasedAt " +
                "scrollTrace=$scrollTrace stateTrace=$stateTrace",
            view.scrollY > releasedAt,
        )
        assertTrue("kinetic FREE_REST must not quantize onto a paginator anchor", view.scrollY !in canonicalTops)
    }

    @Test
    fun `stationary tap during free fling only stops inertia without firing a tap zone`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        view.goToPage(1)
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))
        val x = view.width * 0.50f
        val y = view.height * 0.50f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        val stoppedAt = view.scrollY
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 16L, MotionEvent.ACTION_UP, x, y))
        val stoppedTrace = view.computeScrollTraceWithoutPostedWork()

        assertTrue(
            "a no-displacement tap may stop inertia but must not navigate or toggle chrome; " +
                "tapZones=$tapZones stoppedAt=$stoppedAt trace=$stoppedTrace",
            tapZones.isEmpty() && stoppedTrace.all { it == stoppedAt },
        )
    }

    @Test
    fun `stationary fling stop consumes host tap and does not activate a clickable span`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        var linkClicks = 0
        val text = "Open linked chapter without activating it while stopping inertia.\n" +
            (1..80).joinToString("\n") { "Line $it marker text." }
        val linkStart = text.indexOf("linked")
        val spannable = SpannableString(text).apply {
            setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        linkClicks += 1
                    }
                },
                linkStart,
                linkStart + "linked".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(onTapZone = tapZones::add, spannable = spannable, text = text)
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))
        val linkPoint = view.pointForTextOffset(linkStart + 1)
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(
            motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, linkPoint.first, linkPoint.second),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 16L, MotionEvent.ACTION_UP, linkPoint.first, linkPoint.second),
        )

        val consumedId = dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed
        assertTrue(
            "a fling-stop tap must be visibly consumed by both host surfaces without activating content; " +
                "viewConsumed=${view.getTag(consumedId)} textConsumed=${view.textView.getTag(consumedId)} " +
                "linkClicks=$linkClicks tapZones=$tapZones",
            view.getTag(consumedId) == true &&
                view.textView.getTag(consumedId) == true &&
                linkClicks == 0 && tapZones.isEmpty(),
        )
    }

    @Test
    fun `free fling publishes only after two computed stable frames and not after a dropped frame`() {
        val reports = mutableListOf<Int>()
        val view = pagedFlowView(onTopOffsetChanged = reports::add)
        view.goToPage(1)
        reports.clear()
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        view.setPrivateField("freeFlingStartedAtMs", SystemClock.uptimeMillis() - 100L)

        SystemClock.sleep(16L)
        val beforeMovingCompute = view.scrollY
        view.computeScroll()
        val afterMovingCompute = view.scrollY
        val stateAfterMovingCompute = view.privateEnumName("pagedMotionState")
        val reportsAfterMovingCompute = reports.toList()

        view.fling(0)
        view.setPrivateField("freeFlingStableFrames", 0)
        view.computeScroll()
        val stateAfterFirstStable = view.privateEnumName("pagedMotionState")
        val reportsAfterFirstStable = reports.toList()
        SystemClock.sleep(120L)
        val stateAfterDroppedFrame = view.privateEnumName("pagedMotionState")
        val reportsAfterDroppedFrame = reports.toList()
        view.computeScroll()

        assertTrue(
            "fling settle must observe movement after super.computeScroll and require two computed stable frames; " +
                "beforeMoving=$beforeMovingCompute afterMoving=$afterMovingCompute " +
                "stateAfterMoving=$stateAfterMovingCompute reportsAfterMoving=$reportsAfterMovingCompute " +
                "stateAfterFirstStable=$stateAfterFirstStable reportsAfterFirstStable=$reportsAfterFirstStable " +
                "stateAfterDropped=$stateAfterDroppedFrame reportsAfterDropped=$reportsAfterDroppedFrame " +
                "finalState=${view.privateEnumName("pagedMotionState")} finalReports=$reports",
            afterMovingCompute > beforeMovingCompute &&
                stateAfterMovingCompute == "FLING_FREE" && reportsAfterMovingCompute.isEmpty() &&
                stateAfterFirstStable == "FLING_FREE" && reportsAfterFirstStable.isEmpty() &&
                stateAfterDroppedFrame == "FLING_FREE" && reportsAfterDroppedFrame.isEmpty() &&
                view.privateEnumName("pagedMotionState") == "FREE_REST" && reports.size == 1,
        )
    }

    @Test
    fun `natural free fling settle terminates its trajectory before publishing rest`() {
        val reports = mutableListOf<Int>()
        val view = pagedFlowView(onTopOffsetChanged = reports::add)
        view.goToPage(1)
        reports.clear()
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        val movingTrace = mutableListOf<Int>()

        repeat(160) {
            if (view.privateEnumName("pagedMotionState") != "FLING_FREE") return@repeat
            SystemClock.sleep(16L)
            view.computeScroll()
            movingTrace += view.scrollY
        }

        assertEquals(
            "the real scroller must reach FREE_REST within the deterministic frame budget; traceTail=${movingTrace.takeLast(8)}",
            "FREE_REST",
            view.privateEnumName("pagedMotionState"),
        )
        val settledY = view.scrollY
        val reportsAtSettle = reports.toList()
        val postSettleTrace = buildList {
            repeat(12) {
                SystemClock.sleep(16L)
                view.computeScroll()
                add(view.scrollY)
            }
        }

        assertTrue(
            "once FREE_REST publishes, no residual native trajectory may move content or report again; " +
                "settledY=$settledY postSettleTrace=$postSettleTrace " +
                "reportsAtSettle=$reportsAtSettle finalReports=$reports",
            postSettleTrace.all { it == settledY } &&
                reportsAtSettle.size == 1 && reports == reportsAtSettle,
        )
    }

    @Test
    fun `programmatic same chapter navigation aborts the old free fling trajectory`() {
        val reports = mutableListOf<Int>()
        val view = pagedFlowView(onTopOffsetChanged = reports::add)
        view.goToPage(1)
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))
        val targetTop = requireNotNull(view.pageTopPxAt(0))
        val layout = requireNotNull(view.textView.layout)
        val targetOffset = layout.getLineStart(layout.getLineForVertical(targetTop))
        reports.clear()

        view.goToOffset(targetOffset)
        val trace = view.computeScrollTraceWithoutPostedWork()

        assertTrue(
            "public same-chapter navigation must own the final viewport after aborting the old scroller; " +
                "targetTop=$targetTop trace=$trace state=${view.privateEnumName("pagedMotionState")} " +
                "targetOffset=$targetOffset visibleOffset=${view.topLayoutOffset()} reports=$reports",
            trace.all { it == targetTop } &&
                view.privateEnumName("pagedMotionState") == "ALIGNED" &&
                view.topLayoutOffset() == targetOffset &&
                reports.lastOrNull() == targetOffset,
        )
    }

    @Test
    fun `anchored paged to scroll replacement during free fling owns the target viewport`() {
        val view = pagedFlowView()
        view.goToPage(1)
        val oldFlingY = view.scrollY
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))
        val layout = requireNotNull(view.textView.layout)
        val targetOffset = layout.getLineStart(0)
        val targetY = layout.getLineTop(0)
        assertTrue("fixture needs a replacement target away from the old fling viewport", targetY != oldFlingY)

        view.setModeAnchored(EpubFlowView.Mode.SCROLL, targetOffset)
        val trace = listOf(view.scrollY) + view.computeScrollTraceWithoutPostedWork()

        assertTrue(
            "the anchored mode target must retire the old free-fling trajectory; " +
                "oldFlingY=$oldFlingY targetY=$targetY trace=$trace " +
                "visibleOffset=${view.topLayoutOffset()} targetOffset=$targetOffset",
            trace.all { it == targetY } &&
                view.topLayoutOffset() == targetOffset,
        )
    }

    @Test
    fun `new chapter restore after posted settle retires the old free fling trajectory`() {
        val view = pagedFlowView()
        view.goToPage(1)
        val oldFlingY = view.scrollY
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))
        val incomingText = (1..100).joinToString("\n") { "Replacement chapter line $it owns this viewport." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )
        val restoreOffset = incomingFlow.offsetForParagraph(
            0,
            incomingText.indexOf("Replacement chapter line 40"),
        )

        view.setChapter(
            incomingFlow,
            incomingFlow.text,
            pageHeightPx = view.height,
            restoreOffset = restoreOffset,
        )
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idle()
        val restoredY = view.scrollY
        val restoredOffset = view.topLayoutOffset()
        assertTrue("fixture needs the new chapter restore away from the old fling viewport", restoredY != oldFlingY)

        val trace = view.computeScrollTraceWithoutPostedWork()

        assertTrue(
            "the settled replacement chapter must keep ownership after later computeScroll calls; " +
                "oldFlingY=$oldFlingY restoredY=$restoredY trace=$trace " +
                "restoredOffset=$restoredOffset visibleOffset=${view.topLayoutOffset()}",
            trace.all { it == restoredY } &&
                view.topLayoutOffset() == restoredOffset,
        )
    }

    @Test
    fun `reapplying the current flow anchor does not report duplicate progress`() {
        val reports = mutableListOf<Int>()
        val view = pagedFlowView(onTopOffsetChanged = reports::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        val parkedScrollY = view.scrollY
        val parkedOffset = view.topLayoutOffset()
        reports.clear()

        view.goToOffset(parkedOffset)

        assertEquals(
            "same-anchor goTo should not feed a duplicate locator/progress loop back into the reader",
            emptyList<Int>(),
            reports,
        )
        assertEquals("same-anchor goTo should leave the visible page stable", parkedScrollY, view.scrollY)
    }

    @Test
    fun `anchored switch to paged snaps to nearest canonical page anchor`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }

        view.mode = EpubFlowView.Mode.SCROLL

        view.setModeAnchored(EpubFlowView.Mode.PAGED, layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop)))

        assertEquals(
            "SCROLL->PAGED should settle to the nearest canonical page anchor, not floor back to the previous page",
            pageTwoTop,
            view.scrollY,
        )
    }

    @Test
    fun `scroll to paged anchored switch reveals immediately when layout settled`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }

        view.mode = EpubFlowView.Mode.SCROLL
        view.getChildAt(0).alpha = 1f

        view.setModeAnchored(EpubFlowView.Mode.PAGED, layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop)))

        assertEquals("SCROLL->PAGED should park immediately on the target canonical page", pageTwoTop, view.scrollY)
        assertEquals(
            "SCROLL->PAGED conversion should reveal immediately when layout is settled and no decodes pending",
            false,
            view.privateBool("awaitingReveal"),
        )

        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)

        assertEquals(1f, view.getChildAt(0).alpha)
        assertEquals(pageTwoTop, view.scrollY)
    }

    @Test
    fun `scroll to paged conversion keeps frozen viewport cover until reveal finishes`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }

        view.mode = EpubFlowView.Mode.SCROLL

        view.setModeAnchored(EpubFlowView.Mode.PAGED, layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop)))

        val cover = view.privateField("conversionSnapshotDrawable")
        assertNotNull(
            "SCROLL->PAGED should freeze the current viewport over the hidden live content so the user never sees the conversion",
            cover,
        )
        val bitmap = cover!!.privateBitmap("bitmap")
        assertEquals(view.width, bitmap.width)
        assertEquals(view.height, bitmap.height)
        val coverBounds = (cover as Drawable).bounds
        assertEquals(
            "conversion cover must be positioned over the current visible scroll window",
            view.scrollY,
            coverBounds.top,
        )
        assertEquals(view.scrollY + view.height, coverBounds.bottom)
        assertEquals(
            "the stable paged content should be fully opaque beneath the still-opaque conversion cover",
            1f,
            view.getChildAt(0).alpha,
        )

        shadowOf(Looper.getMainLooper()).idleFor(360L, TimeUnit.MILLISECONDS)

        assertEquals("the conversion cover should be removed after the stable paged frame has settled", null, view.privateField("conversionSnapshotDrawable"))
        assertTrue("the temporary conversion cover bitmap must be recycled", bitmap.isRecycled)
        assertEquals(1f, view.getChildAt(0).alpha)
        assertEquals(pageTwoTop, view.scrollY)
    }

    @Test
    fun `scroll to paged conversion crossfades frozen frame to live paged frame`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }

        view.mode = EpubFlowView.Mode.SCROLL
        view.setModeAnchored(
            EpubFlowView.Mode.PAGED,
            layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop)),
        )
        val cover = view.privateField("conversionSnapshotDrawable")
        assertNotNull("test requires a frozen conversion cover", cover)

        // setModeAnchored reveals through the real stability gate (tryRevealWhenStable): with layout
        // settled and no pending decodes it reveals synchronously, so the conversion is already committed.
        assertEquals(false, view.privateBool("awaitingReveal"))
        assertEquals(
            "the frozen scroll frame must still be at full opacity before the cross-fade animation advances",
            255,
            cover!!.privateInt("alphaValue"),
        )

        // Advance past the REVEAL_FADE_MS cross-fade (120ms).
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

        assertNull(
            "conversion cover must be cleared and removed after cross-fade completes",
            view.privateField("conversionSnapshotDrawable"),
        )
    }

    @Test
    fun `scroll to paged reflow during hidden conversion keeps the conversion anchor`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }
        val staleOpenRestoreOffset = layout.getLineStart(layout.getLineForVertical(pageOneTop))
        val conversionOffset = layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop))

        view.setPrivateField("pendingRestoreOffset", staleOpenRestoreOffset)
        view.mode = EpubFlowView.Mode.SCROLL
        view.setModeAnchored(EpubFlowView.Mode.PAGED, conversionOffset)
        assertEquals(pageTwoTop, view.scrollY)

        val currentLayoutHeight = layout.height
        view.setPrivateField("paginatedLayoutHeight", currentLayoutHeight - 1)
        view.runReflowRunnable()

        assertEquals(
            "a reflow during hidden SCROLL->PAGED conversion must preserve the user's conversion anchor, not an old open restore anchor",
            pageTwoTop,
            view.scrollY,
        )
    }

    @Test
    fun `scroll to paged reflow during reveal fade re-hides then immediately re-reveals when settled`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }
        val conversionOffset = layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop))

        view.mode = EpubFlowView.Mode.SCROLL
        view.setModeAnchored(EpubFlowView.Mode.PAGED, conversionOffset)
        view.setPrivateField("awaitingReveal", false)
        view.getChildAt(0).alpha = 0.5f
        view.setPrivateField("paginatedLayoutHeight", layout.height - 1)

        view.runReflowRunnable()

        assertEquals(
            "late reflow re-hides then immediately re-reveals via stability gate when layout is settled",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(pageTwoTop, view.scrollY)

        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    fun `scroll to paged reflow during reveal still parks and cross-fades via stability gate`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }
        val conversionOffset = layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop))

        view.mode = EpubFlowView.Mode.SCROLL
        view.setModeAnchored(EpubFlowView.Mode.PAGED, conversionOffset)
        val cover = view.privateField("conversionSnapshotDrawable")
        assertNotNull("test requires a frozen conversion cover", cover)
        // setModeAnchored reveals through the real stability gate (tryRevealWhenStable): no images
        // pending + layout settled → immediate reveal, so awaitingReveal is already cleared here.
        assertEquals(false, view.privateBool("awaitingReveal"))

        view.setPrivateField("paginatedLayoutHeight", layout.height - 1)
        view.runReflowRunnable()

        assertEquals(
            "reflow re-hides then immediately re-reveals via stability gate when layout settled",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(pageTwoTop, view.scrollY)

        shadowOf(Looper.getMainLooper()).idleFor(360L, TimeUnit.MILLISECONDS)

        assertEquals(null, view.privateField("conversionSnapshotDrawable"))
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    fun `temporary scroll cancel re-arms paged clip without tap zone`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        val downTime = SystemClock.uptimeMillis()
        val startX = view.width * 0.50f
        val startY = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY))
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 24L,
                MotionEvent.ACTION_MOVE,
                startX,
                view.height * 0.30f,
            ),
        )
        assertEquals("temporary scroll should drop page clipping while dragging", false, view.privateBool("pageClipActive"))
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 48L,
                MotionEvent.ACTION_CANCEL,
                startX,
                view.height * 0.30f,
            ),
        )

        assertEquals("temporary-scroll CANCEL must not be treated as a clean tap", emptyList<EpubFlowTapZone>(), tapZones)
        assertEquals("temporary-scroll CANCEL should re-arm page clipping", true, view.privateBool("pageClipActive"))
    }

    @Test
    fun `scroll mode cancel reports the last visible offset without snapping`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(onTopOffsetChanged = reportedOffsets::add)
        view.mode = EpubFlowView.Mode.SCROLL
        reportedOffsets.clear()
        val x = view.width * 0.50f
        val downY = view.height * 0.75f
        val moveY = view.height * 0.20f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, downY))
        view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, x, moveY))
        val cancelledAtScrollY = view.scrollY
        assertTrue("test must visibly scroll before cancellation", cancelledAtScrollY > 0)

        view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, x, moveY))

        assertEquals("SCROLL cancellation must keep the visible position", cancelledAtScrollY, view.scrollY)
        assertEquals(
            "SCROLL cancellation ends the stream, so its final visible locator must be reported",
            view.topLayoutOffset(),
            reportedOffsets.lastOrNull(),
        )
    }

    @Test
    fun `center dead-zone drag release does not trigger tap zone`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        val downTime = SystemClock.uptimeMillis()
        val startX = view.width * 0.37f
        val startY = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY))
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 24L,
                MotionEvent.ACTION_MOVE,
                startX,
                view.height * 0.10f,
            ),
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 48L,
                MotionEvent.ACTION_UP,
                startX,
                view.height * 0.10f,
            ),
        )

        assertEquals("center dead-zone UP must not be treated as a clean tap", emptyList<EpubFlowTapZone>(), tapZones)
        assertEquals(0, view.currentPageIndex())
        assertEquals(0, view.scrollY)
    }

    @Test
    fun `action down alone does not move paged content`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))

        assertEquals("ACTION_DOWN alone must not trigger a tap zone", emptyList<EpubFlowTapZone>(), tapZones)
        assertEquals(0, view.currentPageIndex())
        assertEquals(0, view.scrollY)

        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_CANCEL, x, y))
    }

    @Test
    fun `inner center horizontal drag follows the paged turn path`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        val downTime = SystemClock.uptimeMillis()
        val startX = view.width * 0.50f
        val startY = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY))
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 24L,
                MotionEvent.ACTION_MOVE,
                view.width * 0.10f,
                startY,
            ),
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 48L,
                MotionEvent.ACTION_UP,
                view.width * 0.10f,
                startY,
            ),
        )

        assertEquals(
            "horizontal intent must turn from the middle without stealing the vertical temporary-scroll zone",
            listOf(EpubFlowTapZone.NEXT),
            tapZones,
        )
        assertEquals(0, view.currentPageIndex())
        assertEquals(0, view.scrollY)
    }

    @Test
    fun `long press drag in page turn zone does not trigger paged flip`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        val downTime = SystemClock.uptimeMillis()
        val startX = view.width * 0.85f
        val startY = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY))
        shadowOf(Looper.getMainLooper()).idleFor(
            (ViewConfiguration.getLongPressTimeout() + 50).toLong(),
            TimeUnit.MILLISECONDS,
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + ViewConfiguration.getLongPressTimeout() + 60L,
                MotionEvent.ACTION_MOVE,
                view.width * 0.15f,
                startY,
            ),
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + ViewConfiguration.getLongPressTimeout() + 80L,
                MotionEvent.ACTION_UP,
                view.width * 0.15f,
                startY,
            ),
        )

        assertEquals("long-press selection should keep ownership away from page turn", emptyList<EpubFlowTapZone>(), tapZones)
        assertEquals(0, view.currentPageIndex())
        assertEquals(0, view.scrollY)
    }

    @Test
    fun `long press drag in center dead zone does not trigger paged flip or scroll`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(onTapZone = tapZones::add)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        val downTime = SystemClock.uptimeMillis()
        val startX = view.width * 0.37f
        val startY = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY))
        shadowOf(Looper.getMainLooper()).idleFor(
            (ViewConfiguration.getLongPressTimeout() + 50).toLong(),
            TimeUnit.MILLISECONDS,
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + ViewConfiguration.getLongPressTimeout() + 60L,
                MotionEvent.ACTION_MOVE,
                startX,
                view.height * 0.10f,
            ),
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + ViewConfiguration.getLongPressTimeout() + 80L,
                MotionEvent.ACTION_UP,
                startX,
                view.height * 0.10f,
            ),
        )

        assertEquals("center dead-zone long-press drag must not trigger a tap/flip", emptyList<EpubFlowTapZone>(), tapZones)
        assertEquals(0, view.currentPageIndex())
        assertEquals(0, view.scrollY)
    }

    @Test
    fun `link tap inside text wins over paged tap zone`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        var linkClicks = 0
        val text = "Open linked chapter from this line.\n" +
            (1..80).joinToString("\n") { "Line $it marker text." }
        val linkStart = text.indexOf("linked")
        val linkEnd = linkStart + "linked".length
        val spannable = SpannableString(text).apply {
            setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        linkClicks += 1
                    }
                },
                linkStart,
                linkEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(onTapZone = tapZones::add, spannable = spannable, text = text)

        val linkPoint = view.pointForTextOffset(linkStart + 1)
        val downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, linkPoint.first, linkPoint.second),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_UP, linkPoint.first, linkPoint.second),
        )

        assertEquals(1, linkClicks)
        assertEquals("link tap must not also trigger a paged tap zone", emptyList<EpubFlowTapZone>(), tapZones)
    }

    @Test
    fun `stale link tap marker does not suppress next clean tap outside text`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(
            onTapZone = tapZones::add,
            text = "Short linked line.",
        )
        view.textView.setTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed, true)

        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height - 4f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_UP, x, y))

        assertEquals(
            "a stale child link marker from the previous gesture must not swallow a new clean tap",
            listOf(EpubFlowTapZone.NEXT),
            tapZones,
        )
    }

    // ---- Signal-driven reveal (stability gate) tests -------------------------------------------

    @Test
    fun `reveal is deferred while pending decodes provider reports true`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val restoreOffset = view.textView.layout!!.getLineStart(10)
        // Simulate "images still decoding" by making the provider return true.
        view.pendingDecodesProvider = { true }

        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            (view.textView.text as CharSequence),
            pageHeightPx = 120,
            restoreOffset = restoreOffset,
        )
        shadowOf(Looper.getMainLooper()).idle()

        // With pending decodes, the stability gate must NOT reveal.
        assertEquals(
            "content must stay hidden while images are still decoding",
            true,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(
            "container alpha must stay 0 while awaiting stability",
            0f,
            view.getChildAt(0).alpha,
        )
    }

    @Test
    fun `completed async image wakes reveal before the safety timeout`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val restoreOffset = view.textView.layout!!.getLineStart(10)
        var decodePending = true
        view.pendingDecodesProvider = { decodePending }

        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            view.textView.text,
            pageHeightPx = 120,
            restoreOffset = restoreOffset,
        )
        shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        assertTrue("precondition: decode should still hold the reveal gate", view.privateBool("awaitingReveal"))

        decodePending = false
        view.refreshAfterAsyncImageResult()
        view.textView.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

        assertEquals(
            "decode completion must wake the stability gate instead of waiting for the 800ms safety net",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    fun `async image completion waits for requested layout before revealing`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        var decodePending = true
        view.pendingDecodesProvider = { decodePending }
        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            view.textView.text,
            pageHeightPx = 120,
            restoreOffset = view.textView.layout!!.getLineStart(10),
        )
        shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        assertTrue("precondition: decode should hold the reveal gate", view.privateBool("awaitingReveal"))

        decodePending = false
        view.suppressLayout(true)
        view.textView.requestLayout()
        view.refreshAfterAsyncImageResult()
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

        assertTrue(
            "an image completion callback must not reveal from the stale pre-layout geometry",
            view.privateBool("awaitingReveal"),
        )

        view.suppressLayout(false)
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        view.textView.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        assertEquals(false, view.privateBool("awaitingReveal"))
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    fun `reveal fires immediately when layout settled and no pending decodes`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val restoreOffset = view.textView.layout!!.getLineStart(10)
        // No pending decodes → stability gate should pass immediately.
        view.pendingDecodesProvider = { false }

        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            (view.textView.text as CharSequence),
            pageHeightPx = 120,
            restoreOffset = restoreOffset,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "content should reveal immediately when layout is settled and no decodes are pending",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    fun `800ms safety net reveals even when pending decodes never finish`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val restoreOffset = view.textView.layout!!.getLineStart(10)
        // Simulate permanently pending decodes
        view.pendingDecodesProvider = { true }

        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            (view.textView.text as CharSequence),
            pageHeightPx = 120,
            restoreOffset = restoreOffset,
        )
        // Let the stability gate arm the safety net but not fire yet (80ms settle + 250ms idle).
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)
        assertEquals(
            "content should still be hidden before the 800ms safety fires",
            true,
            view.privateBool("awaitingReveal"),
        )

        // Advance past the 800ms safety net.
        shadowOf(Looper.getMainLooper()).idleFor(900L, TimeUnit.MILLISECONDS)

        assertEquals(
            "800ms safety net must reveal content even with pending decodes",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(1f, view.getChildAt(0).alpha)
    }

    @Test
    fun `settleInitialPosition uses NEAREST anchor to align with setModeAnchored`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        // Pick an offset near the middle of a page boundary so FLOOR vs NEAREST diverge.
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        // Find the first line whose top is >= midpoint (closer to page 2 than page 1).
        val midpoint = pageOneTop + (pageTwoTop - pageOneTop) / 2
        val nearPageTwoLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it >= midpoint }
        val boundaryOffset = layout.getLineStart(layout.getLineForVertical(nearPageTwoLineTop))

        // Simulate settleInitialPosition with this offset.
        view.setPrivateField("pendingRestoreOffset", boundaryOffset)
        view.setPrivateField("pendingLandOnLast", false)
        view.setPrivateField("awaitingReveal", true)
        view.getChildAt(0).alpha = 0f
        EpubFlowView::class.java.getDeclaredMethod("settleInitialPosition").apply { isAccessible = true }
            .invoke(view)
        shadowOf(Looper.getMainLooper()).idle()

        // With NEAREST, the offset should land on page 2 (closer page).
        assertEquals(
            "settleInitialPosition must use NEAREST anchor, landing on the closer page",
            2,
            view.currentPageIndex(),
        )
    }

    @Test
    fun `conversion snapshot cross-fades alpha to 0 instead of instant removal`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val nearNextPageLineTop = (0 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (midpoint + 1) until pageTwoTop }

        view.mode = EpubFlowView.Mode.SCROLL
        view.pendingDecodesProvider = { false } // allow immediate reveal
        view.setModeAnchored(
            EpubFlowView.Mode.PAGED,
            layout.getLineStart(layout.getLineForVertical(nearNextPageLineTop)),
        )
        val cover = view.privateField("conversionSnapshotDrawable")
        assertNotNull("test requires a frozen conversion cover", cover)
        val coverAlphaBefore = cover!!.privateInt("alphaValue")
        assertEquals("conversion cover must start fully opaque", 255, coverAlphaBefore)

        // setModeAnchored already revealed via the stability gate (no pending decodes), starting the
        // cross-fade animator. Advance past the REVEAL_FADE_MS cross-fade (120ms) to let it complete.
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

        // After cross-fade animation completes, alpha should be 0 and the cover cleared.
        val coverAfter = view.privateField("conversionSnapshotDrawable")
        assertNull(
            "conversion cover must be cleared after cross-fade completes",
            coverAfter,
        )
    }

    @Test
    fun `conversion cover fades over a fully opaque live page`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midpoint = pageOneTop + ((pageTwoTop - pageOneTop) / 2)
        val conversionLineTop = (0 until layout.lineCount)
            .map(layout::getLineTop)
            .first { it in (midpoint + 1) until pageTwoTop }

        view.mode = EpubFlowView.Mode.SCROLL
        view.pendingDecodesProvider = { false }
        view.setModeAnchored(
            EpubFlowView.Mode.PAGED,
            layout.getLineStart(layout.getLineForVertical(conversionLineTop)),
        )
        val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
        assertEquals(255, cover.privateInt("alphaValue"))
        assertEquals(
            "once the conversion cover begins retiring, the stable live page must already be fully opaque " +
                "so both owners are never faded at the same time",
            1f,
            view.getChildAt(0).alpha,
        )
    }

    @Test
    fun `full page image taller than viewport is not clipped away when parked on it`() {
        // A full-page illustration lays out as ONE line taller than the viewport (审: 满页彩插).
        // Build a chapter whose 2nd line is such an oversized image line, park exactly on its top,
        // and assert the page clip keeps it (returns null = no clip) instead of backing off to the
        // previous line and clipping the whole viewport blank — the "闪一下后消失" regression.
        val tallImageHeight = 300 // > 120 viewport
        val imageColor = 0xFF1A8F5D.toInt()
        val builder = SpannableString("A\n￼\nB")
        val imageDrawable = ColorDrawable(imageColor).apply {
            setBounds(0, 0, 360, tallImageHeight)
        }
        builder.setSpan(
            ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
            2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val view = pagedFlowView(spannable = builder)

        val layout = requireNotNull(view.textView.layout)
        // The oversized image sits on line 1 (0="A", 1=image, 2="B").
        val imageLineTop = layout.getLineTop(1)
        assertTrue(
            "image line must be taller than the viewport for this regression",
            layout.getLineBottom(1) - imageLineTop > view.height,
        )

        // Park exactly on the image line's top, as a page turn onto the full-page image would.
        view.setPrivateField("pageClipActive", true)
        view.scrollTo(0, imageLineTop)

        assertNull(
            "a full-page image taller than the viewport must not be clipped away when it IS the page",
            view.pageClipBottomForTest(),
        )
        val snapshot = requireNotNull(view.snapshotPageAt(imageLineTop))
        try {
            assertEquals(
                "the page-turn snapshot must retain the same oversized image line as the live viewport",
                imageColor,
                snapshot.getPixel(view.width / 2, view.height / 2),
            )
        } finally {
            snapshot.recycle()
        }
    }

    @Test
    fun `bottom padded oversized image keeps the same live and snapshot bottom strip`() {
        val imageColor = 0xFF1A8F5D.toInt()
        val builder = SpannableString("A\n￼\nB")
        val imageDrawable = ColorDrawable(imageColor).apply {
            setBounds(0, 0, 360, 300)
        }
        builder.setSpan(
            ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
            2,
            3,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val view = pagedFlowView(
            spannable = builder,
            textPaddingBottom = 11,
        )
        val layout = requireNotNull(view.textView.layout)
        val imageLineTop = layout.getLineTop(1)
        assertTrue("fixture needs one oversized line", layout.getLineBottom(1) - imageLineTop > view.height)
        view.setPrivateField("pageClipActive", true)
        view.scrollTo(0, imageLineTop)
        val liveClipBottom = view.pageClipBottomForTest()
        val liveVisibleBottom = liveClipBottom?.let { clipBottom ->
            imageLineTop + clipBottom + view.textView.paddingTop
        } ?: (imageLineTop + view.height)
        val snapshotVisibleBottom = view.snapshotClipBottomForTest(imageLineTop)
        val live = view.drawToBitmapForTest()
        val snapshot = requireNotNull(view.snapshotPageAt(imageLineTop))
        try {
            val liveBottomPixel = live.getPixel(view.width / 2, view.height - 1)
            val snapshotBottomPixel = snapshot.getPixel(view.width / 2, view.height - 1)
            val expectedVisibleBottom = imageLineTop + view.height
            assertTrue(
                "bottom padding must not remove a pixel strip from an indivisible oversized line; " +
                    "paddingBottom=${view.textView.paddingBottom} liveClipBottom=$liveClipBottom " +
                    "liveVisibleBottom=$liveVisibleBottom snapshotVisibleBottom=$snapshotVisibleBottom " +
                    "expectedVisibleBottom=$expectedVisibleBottom " +
                    "liveBottomPixel=$liveBottomPixel snapshotBottomPixel=$snapshotBottomPixel imageColor=$imageColor",
                liveVisibleBottom == expectedVisibleBottom &&
                    snapshotVisibleBottom == expectedVisibleBottom &&
                    liveBottomPixel == imageColor && snapshotBottomPixel == imageColor,
            )
        } finally {
            live.recycle()
            snapshot.recycle()
        }
    }

    @Test
    fun `free rest inside an oversized image keeps that line visible and locatable`() {
        val tallImageHeight = 300
        val builder = SpannableString("A\n￼\nB")
        val imageDrawable = ColorDrawable(0xFF1A8F5D.toInt()).apply {
            setBounds(0, 0, 360, tallImageHeight)
        }
        builder.setSpan(
            ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
            2,
            3,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val view = pagedFlowView(spannable = builder)
        val layout = requireNotNull(view.textView.layout)
        val imageLine = 1
        val imageLineTop = layout.getLineTop(imageLine)
        val imageLineBottom = layout.getLineBottom(imageLine)
        assertTrue("test requires one indivisible line taller than the viewport", imageLineBottom - imageLineTop > view.height)
        val freeRest = imageLineTop + 50
        assertTrue("FREE_REST must stay inside the oversized image line", freeRest in (imageLineTop + 1) until imageLineBottom)

        view.scrollTo(0, freeRest)
        view.setPrivateField("pageClipActive", true)

        assertEquals(
            "live top clipping inside an oversized line must keep its visible viewport slice",
            freeRest,
            view.pageClipTopForTest(),
        )
        assertEquals(
            "snapshot top clipping inside an oversized line must keep the same viewport slice",
            freeRest,
            view.snapshotClipTopForTest(freeRest),
        )
        assertEquals(
            "FREE_REST locator inside an oversized line must remain on that indivisible line",
            layout.getLineStart(imageLine),
            view.topLayoutOffset(),
        )
    }

    @Test
    fun `free rest inside a regular async image keeps its visible slice`() {
        val imageColor = 0xFF1A8F5D.toInt()
        val builder = SpannableString("A\n\uFFFC\n" + (1..30).joinToString("\n") { "Tail line $it" })
        val executor = Executors.newSingleThreadExecutor()
        val loader = EpubFlowImageLoader(
            epubFileProvider = { null },
            executor = executor,
            columnWidthPx = 360,
            pageHeightProvider = { 120 },
            inlineMaxHeightPx = 80,
            fullPageHrefs = emptySet(),
            imageBoundsProvider = { EpubImageBounds(width = 360, height = 80) },
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = 360,
            pageHeightProvider = { 120 },
            inlineMaxHeightPx = 80,
            fullPageHrefs = emptySet(),
        )
        val asyncDrawable = AsyncDrawable("inline.png", loader, resolver, null).apply {
            result = ColorDrawable(imageColor).apply { setBounds(0, 0, 360, 80) }
            setBounds(0, 0, 360, 80)
        }
        builder.setSpan(
            AsyncDrawableSpan(
                io.noties.markwon.core.MarkwonTheme.create(RuntimeEnvironment.getApplication()),
                asyncDrawable,
                AsyncDrawableSpan.ALIGN_CENTER,
                false,
            ),
            2,
            3,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val view = pagedFlowView(spannable = builder)
        val layout = requireNotNull(view.textView.layout)
        val imageLine = 1
        val imageLineTop = layout.getLineTop(imageLine)
        val imageLineBottom = layout.getLineBottom(imageLine)
        assertTrue("fixture requires a regular image shorter than the viewport", imageLineBottom - imageLineTop < view.height)
        val freeRest = imageLineTop + 20
        assertTrue(freeRest in (imageLineTop + 1) until imageLineBottom)

        try {
            view.scrollTo(0, freeRest)
            view.setPrivateField("pageClipActive", true)

            assertEquals(
                "a partially scrolled image is an indivisible visual line and must not be clipped from the top",
                freeRest,
                view.pageClipTopForTest(),
            )
            assertEquals(
                "page-shot capture must retain the same partially visible image slice",
                freeRest,
                view.snapshotClipTopForTest(freeRest),
            )
            val live = view.drawToBitmapForTest()
            val snapshot = requireNotNull(view.snapshotPageAt(freeRest))
            try {
                assertEquals(imageColor, live.getPixel(view.width / 2, 10))
                assertEquals(imageColor, snapshot.getPixel(view.width / 2, 10))
            } finally {
                live.recycle()
                snapshot.recycle()
            }
        } finally {
            view.dispose()
            executor.shutdownNow()
        }
    }

    @Test
    fun `regular image crossing the page bottom keeps its visible strip`() {
        val imageColor = 0xFF1A8F5D.toInt()
        val prefix = (1..10).joinToString("\n") { "Head line $it" }
        val builder = SpannableString("$prefix\n\uFFFC\nTail")
        val imageOffset = prefix.length + 1
        builder.setSpan(
            ImageSpan(
                ColorDrawable(imageColor).apply { setBounds(0, 0, 360, 80) },
                ImageSpan.ALIGN_BOTTOM,
            ),
            imageOffset,
            imageOffset + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val view = pagedFlowView(spannable = builder)
        val layout = requireNotNull(view.textView.layout)
        val imageLine = layout.getLineForOffset(imageOffset)
        val imageTop = layout.getLineTop(imageLine)
        val freeRest = imageTop - (view.height - 30)
        assertTrue("fixture must expose only the top strip at the page bottom", freeRest > 0)

        try {
            view.scrollTo(0, freeRest)
            view.setPrivateField("pageClipActive", true)
            val liveClipBottom = view.pageClipBottomForTest()
            assertTrue(
                "the live clip must reach the viewport bottom instead of backing off to the image top; " +
                    "line=${layout.getLineTop(imageLine)}..${layout.getLineBottom(imageLine)} " +
                    "rest=$freeRest clipBottom=$liveClipBottom",
                liveClipBottom == null || liveClipBottom >= view.height,
            )
            assertEquals(
                "the page shot must preserve the same visible image strip at the viewport bottom",
                freeRest + view.height,
                view.snapshotClipBottomForTest(freeRest),
            )
        } finally {
            view.dispose()
        }
    }

    private fun pagedFlowView(
        flipStyle: PageFlipStyle = PageFlipStyle.NONE,
        onTapZone: (EpubFlowTapZone) -> Unit = {},
        onTopOffsetChanged: (Int) -> Unit = {},
        spannable: CharSequence? = null,
        text: String? = null,
        textPaddingTop: Int = 0,
        textPaddingBottom: Int = 0,
        viewportWidth: Int = 360,
        viewportHeight: Int = 120,
        pageShotBudget: PageShotBudget = PageShotBudget(48L * 1024L * 1024L),
        onPinnedPageShotAdmissionNeeded: (() -> Unit)? = null,
    ): EpubFlowView {
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = EpubFlowView(
            context = context,
            onTapZone = onTapZone,
            onTopOffsetChanged = onTopOffsetChanged,
            onSelectionRange = { _, _ -> },
            pageShotBudget = pageShotBudget,
            onPinnedPageShotAdmissionNeeded = onPinnedPageShotAdmissionNeeded,
        )
        activity.addContentView(view, ViewGroup.LayoutParams(viewportWidth, viewportHeight))
        view.flipStyle = flipStyle
        view.textView.textSize = 18f
        view.textView.setPadding(0, textPaddingTop, 0, textPaddingBottom)
        val chapterText = text ?: (1..80).joinToString("\n") { "Line $it marker text." }
        val block = EpubDisplayBlock.Text(chapterText, headingLevel = null, paragraphIndex = 0)
        val flow = epubBuildChapterFlow(spineIndex = 0, blocks = listOf(block))

        view.measure(exactly(viewportWidth), exactly(viewportHeight))
        view.layout(0, 0, viewportWidth, viewportHeight)
        view.setChapter(flow, spannable ?: flow.text, pageHeightPx = viewportHeight)
        view.measure(exactly(viewportWidth), exactly(viewportHeight))
        view.layout(0, 0, viewportWidth, viewportHeight)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(exactly(viewportWidth), exactly(viewportHeight))
        view.layout(0, 0, viewportWidth, viewportHeight)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        return view
    }

    private fun nonLineTopBetween(
        view: EpubFlowView,
        startExclusive: Int,
        endExclusive: Int,
        fraction: Float,
    ): Int {
        val layout = requireNotNull(view.textView.layout)
        val preferred = startExclusive + ((endExclusive - startExclusive) * fraction).toInt()
        return ((startExclusive + 1) until endExclusive)
            .filter { y ->
                val viewportTopInLayout = (y - view.textView.paddingTop).coerceAtLeast(0)
                layout.getLineTop(layout.getLineForVertical(viewportTopInLayout)) < viewportTopInLayout
            }
            .minByOrNull { y -> kotlin.math.abs(y - preferred) }
            ?: error("test needs a scroll position cutting a painted line between $startExclusive and $endExclusive")
    }

    private fun fullLineRangeInViewport(view: EpubFlowView): IntRange {
        val layout = requireNotNull(view.textView.layout)
        val viewportTop = view.scrollY
        val viewportTopInLayout = (viewportTop - view.textView.paddingTop).coerceAtLeast(0)
        val layoutBottomLimit = (
            viewportTop + view.height - view.textView.paddingTop - view.textView.paddingBottom
        ).coerceAtLeast(viewportTopInLayout + 1)
        var first = layout.getLineForVertical(viewportTopInLayout)
        if (layout.getLineTop(first) < viewportTopInLayout && first < layout.lineCount - 1) first++
        var last = layout.getLineForVertical(layoutBottomLimit - 1)
        while (last > first && layout.getLineBottom(last) > layoutBottomLimit) last--
        return first..last.coerceAtLeast(first)
    }

    private fun exactly(size: Int): Int =
        android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

    private fun EpubFlowView.pointForTextOffset(offset: Int): Pair<Float, Float> {
        val layout = requireNotNull(textView.layout) { "TextView layout is required for hit testing" }
        val line = layout.getLineForOffset(offset)
        val x = layout.getPrimaryHorizontal(offset) + textView.totalPaddingLeft - textView.scrollX
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f +
            textView.totalPaddingTop -
            textView.scrollY
        return x to y
    }

    private fun EpubFlowView.beginInteractiveCurl(forward: Boolean, anchorX: Float): Boolean {
        val axisClass = javaClass.declaredClasses.single { it.simpleName == "InteractiveTurnAxis" }
        val horizontal = checkNotNull(axisClass.enumConstants)
            .single { (it as Enum<*>).name == "HORIZONTAL" }
        val result = javaClass.getDeclaredMethod(
            "beginInteractiveCurl",
            Boolean::class.javaPrimitiveType,
            axisClass,
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(this, forward, horizontal, anchorX) as Enum<*>
        return result.name == "STARTED"
    }

    private fun EpubFlowView.offerReadyBoundaryPreviewForTest(
        forward: Boolean,
        token: Long = 1L,
    ): Bitmap {
        val preview = newBoundaryPreviewForTest(forward, token)
        assertTrue("the current-generation boundary preview must be accepted", offerBoundaryPreviewForTest(preview))
        return preview.bitmap
    }

    private fun EpubFlowView.newBoundaryPreviewForTest(
        forward: Boolean,
        token: Long,
        bitmapOverride: Bitmap? = null,
    ): BoundaryPagePreview {
        val previewClass = runCatching {
            Class.forName("dev.readflow.render.epub.BoundaryPagePreview")
        }.getOrNull()
        assertNotNull(
            "方案 A requires BoundaryPagePreview(token, forward, sourceChapterGeneration, bitmap)",
            previewClass,
        )
        val constructor = previewClass!!.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.contentEquals(
                arrayOf(
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Bitmap::class.java,
                ),
            )
        }
        assertNotNull(
            "BoundaryPagePreview must expose token, direction, source generation, and target frame",
            constructor,
        )
        val bitmap = bitmapOverride ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val paperColor = 0xFFF3EFE5.toInt()
            val contentColor = 0xFF26352D.toInt()
            eraseColor(paperColor)
            for (x in (width / 4) until (width * 3 / 4)) {
                setPixel(x, height / 2, contentColor)
            }
            assertEquals(
                "a ready boundary preview must carry rendered content pixels",
                contentColor,
                getPixel(width / 2, height / 2),
            )
            assertTrue(
                "a ready boundary preview must not be a pure paper background",
                getPixel(width / 2, height / 2) != getPixel(0, 0),
            )
        }
        val generation = privateField("chapterGeneration") as Long
        return constructor!!.apply { isAccessible = true }
            .newInstance(token, forward, generation, bitmap) as BoundaryPagePreview
    }

    private fun EpubFlowView.offerBoundaryPreviewForTest(preview: BoundaryPagePreview): Boolean {
        val offer = javaClass.declaredMethods.firstOrNull { method ->
            method.name == "offerBoundaryPreview" &&
                method.parameterTypes.contentEquals(arrayOf(BoundaryPagePreview::class.java))
        }
        assertNotNull("EpubFlowView must accept a ready preview through offerBoundaryPreview", offer)
        return offer!!.apply { isAccessible = true }.invoke(this, preview) as Boolean
    }

    private fun EpubFlowView.installBoundaryCommitRecorderForTest(commits: MutableList<Any>) {
        val callbackField = javaClass.declaredFields.firstOrNull { field ->
            field.name == "onBoundaryTurnCommitted"
        }
        assertNotNull(
            "EpubFlowView must expose an onBoundaryTurnCommitted delegate for model publication",
            callbackField,
        )
        val callback: (Any) -> Unit = { commit -> commits.add(commit) }
        callbackField!!.apply { isAccessible = true }.set(this, callback)
    }

    private fun EpubFlowView.updateInteractiveCurl(x: Float) {
        javaClass.getDeclaredMethod(
            "updateInteractiveCurl",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )
            .apply { isAccessible = true }
            .invoke(this, x, 0f)
    }

    private fun EpubFlowView.endInteractiveCurl(velocityX: Float) {
        javaClass.getDeclaredMethod("endInteractiveCurl", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, velocityX)
    }

    private fun EpubFlowView.settleTemporaryScrollAnchorForTest() {
        javaClass.getDeclaredMethod("settleTemporaryScrollAnchor")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun EpubFlowView.releaseTemporaryScrollForTest(fingerVelocityY: Float) {
        javaClass.getDeclaredMethod("releaseTemporaryScroll", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, fingerVelocityY)
    }

    private fun EpubFlowView.computeScrollTraceWithoutPostedWork(): List<Int> = buildList {
        repeat(4) {
            SystemClock.sleep(16L)
            computeScroll()
            add(scrollY)
        }
    }

    private fun EpubFlowView.runReflowRunnable(idlePostedWork: Boolean = true) {
        (privateField("reflowRunnable") as Runnable).run()
        if (idlePostedWork) shadowOf(Looper.getMainLooper()).idle()
    }

    private fun EpubFlowView.preCachePageTexturesForTest(idlePostedWork: Boolean = true) {
        javaClass.getDeclaredMethod("preCachePageTextures")
            .apply { isAccessible = true }
            .invoke(this)
        if (idlePostedWork) shadowOf(Looper.getMainLooper()).idle()
    }

    private fun EpubFlowView.recycleCachedTexturesForTest() {
        javaClass.getDeclaredMethod("recycleCachedTextures")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun EpubFlowView.detachCachedTextureOwnerForTest(bitmap: Bitmap) {
        javaClass.getDeclaredMethod("detachCachedTextureOwner", Bitmap::class.java)
            .apply { isAccessible = true }
            .invoke(this, bitmap)
    }

    private fun EpubFlowView.snapshotViewportForTest(): Bitmap? =
        javaClass.getDeclaredMethod("snapshotViewport")
            .apply { isAccessible = true }
            .invoke(this) as Bitmap?

    private fun EpubFlowView.showConversionSnapshotForTest(bitmap: Bitmap) {
        javaClass.getDeclaredMethod("showConversionSnapshot", Bitmap::class.java)
            .apply { isAccessible = true }
            .invoke(this, bitmap)
    }

    private fun EpubFlowView.drawToBitmapForTest(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    private fun EpubFlowView.drawAsScrolledChildToBitmapForTest(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            draw(canvas)
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
                assertEquals(
                    "$message at ($x,$y)",
                    expected.getPixel(x, y),
                    actual.getPixel(x, y),
                )
                x += stepX
            }
            y += stepY
        }
    }

    private fun assertAllPixelsEqual(message: String, expected: Bitmap, actual: Bitmap) {
        assertEquals("$message: width", expected.width, actual.width)
        assertEquals("$message: height", expected.height, actual.height)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals(
                    "$message at ($x,$y)",
                    expected.getPixel(x, y),
                    actual.getPixel(x, y),
                )
            }
        }
    }

    private fun bitmapsHaveSamePixels(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) return false
            }
        }
        return true
    }

    private fun markerBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint()
            for (y in 0 until height) {
                paint.color = if ((y / 5) % 2 == 0) 0xFF204060.toInt() else 0xFFE0C080.toInt()
                canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 1).toFloat(), paint)
            }
        }

    private fun EpubFlowView.textureTopPxForPageForTest(index: Int): Int? =
        javaClass.getDeclaredMethod("textureTopPxForPage", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, index) as Int?

    private fun EpubFlowView.snapshotClipTopForTest(topPx: Int): Int =
        javaClass.getDeclaredMethod("snapshotClipTopFor", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, topPx) as Int

    private fun EpubFlowView.snapshotClipBottomForTest(topPx: Int): Int =
        javaClass.getDeclaredMethod(
            "snapshotClipBottomFor",
            Int::class.javaPrimitiveType,
            EpubFlowPage::class.java,
        )
            .apply { isAccessible = true }
            .invoke(this, topPx, null) as Int

    private fun EpubFlowView.pageClipTopForTest(): Int =
        javaClass.getDeclaredMethod("pageClipTopInViewport")
            .apply { isAccessible = true }
            .invoke(this) as Int

    private fun EpubFlowView.pageClipBottomForTest(): Int? =
        javaClass.getDeclaredMethod("pageClipBottomInViewport")
            .apply { isAccessible = true }
            .invoke(this) as Int?

    private fun EpubFlowView.privateInt(name: String): Int =
        privateField(name) as Int

    private fun EpubFlowView.privateBool(name: String): Boolean =
        privateField(name) as Boolean

    private fun EpubFlowView.privateEnumName(name: String): String =
        (privateField(name) as Enum<*>).name

    private fun EpubFlowView.setPrivateField(name: String, value: Any?) {
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
    }

    private fun EpubFlowView.privateField(name: String): Any? =
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this)

    private fun Any.reflectedField(name: String): Any? =
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this)

    private fun Any.privateBitmap(name: String): Bitmap =
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this) as Bitmap

    private fun Any.privateInt(name: String): Int =
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this) as Int

    private class CountingList<T>(
        private val delegate: List<T>,
    ) : AbstractList<T>() {
        var elementAccesses: Int = 0
            private set

        override val size: Int
            get() = delegate.size

        override fun get(index: Int): T {
            elementAccesses += 1
            return delegate[index]
        }

        fun reset() {
            elementAccesses = 0
        }
    }

    private class ThrowWhenBoundsTopDrawable(
        private val failingTop: Int,
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            if (bounds.top == failingTop) error("snapshot failure for target page")
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class CaptureTargetBitmapThenThrowDrawable : Drawable() {
        var targetBitmap: Bitmap? = null
            private set

        override fun draw(canvas: Canvas) {
            val targetBitmapField = org.robolectric.shadows.ShadowLegacyCanvas::class.java
                .getDeclaredField("targetBitmap")
                .apply { isAccessible = true }
            targetBitmap = targetBitmapField.get(shadowOf(canvas)) as Bitmap
            error("snapshot draw failure after bitmap allocation")
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class ThrowOnSecondDrawDrawable : Drawable() {
        private var drawCount = 0

        override fun draw(canvas: Canvas) {
            drawCount += 1
            if (drawCount == 2) error("snapshot failure for revealed page")
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class RecordingBoundsTopDrawable : Drawable() {
        val boundsTops = mutableListOf<Int>()

        override fun draw(canvas: Canvas) {
            boundsTops += bounds.top
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class RecordingTargetBitmapDrawable(
        private val beforeDraw: (() -> Unit)? = null,
    ) : Drawable() {
        val targetBitmaps = mutableListOf<Bitmap>()

        override fun draw(canvas: Canvas) {
            beforeDraw?.invoke()
            val targetBitmapField = org.robolectric.shadows.ShadowLegacyCanvas::class.java
                .getDeclaredField("targetBitmap")
                .apply { isAccessible = true }
            targetBitmaps += targetBitmapField.get(shadowOf(canvas)) as Bitmap
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class StripedPaperDrawable : Drawable() {
        private val paint = Paint()

        override fun draw(canvas: Canvas) {
            val b = bounds
            var y = b.top
            while (y < b.bottom) {
                paint.color = if ((y / 6) % 2 == 0) 0xFFEDE6D6.toInt() else 0xFFE3D9C6.toInt()
                canvas.drawRect(
                    b.left.toFloat(),
                    y.toFloat(),
                    b.right.toFloat(),
                    minOf(y + 6, b.bottom).toFloat(),
                    paint,
                )
                y += 6
            }
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private class XRampPaperDrawable : Drawable() {
        private val paint = Paint()

        override fun draw(canvas: Canvas) {
            val b = bounds
            for (x in b.left until b.right) {
                val level = (x and 0xFF)
                paint.color = 0xFF000000.toInt() or (level shl 16) or (0xD0 shl 8) or 0xA0
                canvas.drawRect(
                    x.toFloat(),
                    b.top.toFloat(),
                    (x + 1).toFloat(),
                    b.bottom.toFloat(),
                    paint,
                )
            }
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

}
