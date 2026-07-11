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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubFlowViewTest {

    @Test
    fun `page turn from temporary scroll starts at nearest canonical page anchor`() {
        val view = pagedFlowView()
        assertTrue(
            "pageCount=${view.pageCount()} textView=${view.textView.width}x${view.textView.height} " +
                "lineCount=${view.textView.layout?.lineCount}",
            view.pageCount() > 3,
        )
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        requireNotNull(view.pageTopPxAt(3))
        val nearPageTwo = pageOneTop + ((pageTwoTop - pageOneTop) * 3 / 4)

        view.scrollTo(0, nearPageTwo)

        view.goToAdjacentPage(1)

        assertEquals(3, view.currentPageIndex())
        assertEquals(view.pageTopPxAt(3), view.scrollY)
    }

    @Test
    fun `cancelled interactive turn restores normalized from page anchor`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val nearPageTwo = pageOneTop + ((pageTwoTop - pageOneTop) * 3 / 4)

        view.scrollTo(0, nearPageTwo)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.75f)
        view.endInteractiveCurl(velocityX = 0f)

        assertEquals(2, view.currentPageIndex())
        assertEquals(pageTwoTop, view.scrollY)
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
    fun `committed interactive turn lands on adjacent page from normalized anchor`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val pageThreeTop = requireNotNull(view.pageTopPxAt(3))
        val nearPageTwo = pageOneTop + ((pageTwoTop - pageOneTop) * 3 / 4)

        view.scrollTo(0, nearPageTwo)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.25f)
        view.endInteractiveCurl(velocityX = 0f)

        assertEquals(3, view.currentPageIndex())
        assertEquals(pageThreeTop, view.scrollY)
    }

    @Test
    fun `clearing previews during committed interactive settle publishes the parked target once`() {
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

        view.clearBoundaryPreviews()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(targetPage, view.currentPageIndex())
        assertEquals(listOf(targetOffset), reportedOffsets)
        assertNull(view.privateField("slideDrawable"))
    }

    @Test
    fun `simulation drag uses software curl without activating gl overlay`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
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
            assertEquals("interactive finger drag must not start the GL overlay", 0, overlay.startCount)
            assertFalse("interactive finger drag must leave the GL overlay inactive", overlay.active)
        } finally {
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            if (overlay.active) overlay.dismiss()
        }

        assertEquals("ACTION_CANCEL must keep the outgoing logical page", startPage, view.currentPageIndex())
        assertEquals("ACTION_CANCEL must restore the outgoing page anchor", startTop, view.scrollY)
    }

    @Test
    fun `simulation vertical side swipe uses software curl without activating gl overlay`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
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
            assertEquals("vertical finger swipe must not start the GL overlay", 0, overlay.startCount)
            assertFalse("vertical finger swipe must leave the GL overlay inactive", overlay.active)
        } finally {
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            if (overlay.active) overlay.dismiss()
        }
    }

    @Test
    fun `cache ready simulation boundary move stays finger driven without requesting navigation`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = tapZones::add,
        ).apply {
            curlOverlay = overlay
        }
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
            assertEquals("finger tracking must not start the discrete GL overlay", 0, overlay.startCount)
            assertFalse("finger tracking must leave the discrete GL overlay inactive", overlay.active)
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
    fun `boundary up before preview ready cancels and late preview cannot revive the gesture`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = tapZones::add,
        )
        view.goToLastPage()
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        val downX = view.width * 0.75f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y))
        val latePreview = view.offerReadyBoundaryPreviewForTest(forward = true, token = 3L)
        shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

        assertEquals("UP-before-ready must keep the outgoing logical page", startPage, view.currentPageIndex())
        assertEquals("UP-before-ready must keep the outgoing page anchor", startTop, view.scrollY)
        assertTrue("UP-before-ready and late readiness must not request navigation", tapZones.isEmpty())
        assertNull(
            "a preview arriving after ACTION_UP must not create a detached software curl",
            view.privateField("curlDrawable"),
        )
        if (!latePreview.isRecycled) latePreview.recycle()
    }

    @Test
    fun `NONE boundary swipe released before preview arrives cannot auto commit`() {
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
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        val downX = view.width * 0.85f
        val moveX = view.width * 0.05f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
        assertEquals(
            "BOUNDARY_WAITING",
            view.privateField("interactiveTurnState").toString(),
        )

        view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y))
        val latePreview = view.offerReadyBoundaryPreviewForTest(forward = true, token = 99L)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("NONE", view.privateField("interactiveTurnState").toString())
        assertEquals(startPage, view.currentPageIndex())
        assertEquals(startTop, view.scrollY)
        assertTrue(commits.isEmpty())
        assertNull(view.privateField("conversionSnapshotDrawable"))
        assertFalse("the late result remains cached for a later explicit input", latePreview.isRecycled)
        if (!latePreview.isRecycled) latePreview.recycle()
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
    fun `discrete simulation boundary commit transfers fake gl reveal before dismiss`() {
        val commits = mutableListOf<Any>()
        val overlay = FakeCurlOverlay(transferRevealedOwnership = true)
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        view.installBoundaryCommitRecorderForTest(commits)
        view.goToLastPage()
        val revealed = view.offerReadyBoundaryPreviewForTest(forward = true, token = 6L)

        try {
            assertTrue(view.startDiscreteBoundaryTurn(1))
            assertEquals("the cache-hit turn must enter the GL renderer immediately", 1, overlay.startCount)

            overlay.settle(committed = true)

            val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertEquals("the host must claim the GL target exactly once", 1, overlay.takeRevealedCount)
            assertEquals("the completed overlay must still be dismissed", 1, overlay.dismissCount)
            assertTrue(
                "the continuity cover must own the exact bitmap released by GL",
                cover.privateBitmap("bitmap") === revealed,
            )
            assertFalse("dismiss must not recycle a revealed bitmap after ownership transfer", revealed.isRecycled)
            assertEquals("the GL boundary transaction must publish exactly once", 1, commits.size)
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
    fun `cancelled discrete gl turn restores normalized from page anchor`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val nearPageTwo = pageOneTop + ((pageTwoTop - pageOneTop) * 3 / 4)

        view.scrollTo(0, nearPageTwo)

        assertTrue(view.goToAdjacentPage(1))
        overlay.settle(committed = false)

        assertEquals(2, view.currentPageIndex())
        assertEquals(pageTwoTop, view.scrollY)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `discrete gl conversion front copy failure rejects turn and preserves partial owner`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
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
        val fade = checkNotNull(view.privateField("conversionFadeAnimator"))
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        val failCopy: (Bitmap) -> Bitmap? = { null }
        view.setPrivateField("pageShotBitmapCopier", failCopy)

        assertTrue(view.goToAdjacentPage(1))

        assertEquals("a failed GL front copy must not start the overlay", 0, overlay.startCount)
        assertEquals(startPage, view.currentPageIndex())
        assertEquals(startTop, view.scrollY)
        assertEquals("the exact partial owner must remain installed", cover, view.privateField("conversionSnapshotDrawable"))
        assertEquals(128, cover.privateInt("alphaValue"))
        assertTrue("the original reveal must continue", view.privateField("conversionFadeAnimator") === fade)
        assertNull(view.privateField("slideDrawable"))
        assertNull(view.privateField("flipAnimator"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `discrete gl turn keeps partial conversion owner until first frame is ready`() {
        val overlay = FakeCurlOverlay(autoFirstFrameReady = false)
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
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

        assertTrue(view.goToAdjacentPage(1))

        assertEquals(true, overlay.active)
        assertEquals(
            "the discrete GL handoff must keep the exact visible cover until GL draws its first frame",
            cover,
            view.privateField("conversionSnapshotDrawable"),
        )
        assertEquals("the visible conversion composition must remain frozen", 128, cover.privateInt("alphaValue"))
        assertNull(
            "the conversion reveal must pause while the discrete GL first frame is pending",
            view.privateField("conversionFadeAnimator"),
        )

        overlay.signalFirstFrameReady()

        assertEquals("GL must still own the turn when the cover retires", true, overlay.active)
        assertNull(view.privateField("conversionSnapshotDrawable"))
        overlay.settle(committed = false)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `discrete gl rejects conversion turn when captured front cannot be copied`() {
        val overlay = FakeCurlOverlay(autoFirstFrameReady = false)
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
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
        val fade = checkNotNull(view.privateField("conversionFadeAnimator"))
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY
        var capturedFront: Bitmap? = null
        val failGlFrontCopy: (Bitmap) -> Bitmap? = { source ->
            capturedFront = source
            null
        }
        view.setPrivateField("pageShotBitmapCopier", failGlFrontCopy)

        try {
            assertTrue("the failed turn must remain consumed by the self-paging view", view.goToAdjacentPage(1))

            assertNotNull("the visible conversion composition must reach the GL front-copy seam", capturedFront)
            assertEquals("a failed GL front copy must not start the curl overlay", 0, overlay.startCount)
            assertEquals("the rejected turn must retain the current page", startPage, view.currentPageIndex())
            assertEquals("the rejected turn must retain the current visual anchor", startTop, view.scrollY)
            assertEquals("the same conversion cover must remain the visible owner", cover, view.privateField("conversionSnapshotDrawable"))
            assertTrue("the original conversion reveal must continue without being restarted", view.privateField("conversionFadeAnimator") === fade)
            assertNull("the rejected GL turn must not fall through to a slide", view.privateField("slideDrawable"))
            assertNull("the rejected GL turn must not start any page-turn animator", view.privateField("flipAnimator"))
            assertEquals(false, overlay.active)
        } finally {
            overlay.dismiss()
            overlay.frontBitmapCopy?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `cancelled discrete gl turn before first frame resumes partial conversion reveal`() {
        val overlay = FakeCurlOverlay(autoFirstFrameReady = false)
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
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
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY

        assertTrue(view.goToAdjacentPage(1))
        overlay.settle(committed = false)

        assertEquals(startPage, view.currentPageIndex())
        assertEquals(startTop, view.scrollY)
        assertEquals(
            "a pre-first-frame cancel must restore the same cover instead of exposing a different owner",
            cover,
            view.privateField("conversionSnapshotDrawable"),
        )
        assertEquals(128, cover.privateInt("alphaValue"))
        assertNotNull(
            "a pre-first-frame cancel must resume the interrupted conversion reveal",
            view.privateField("conversionFadeAnimator"),
        )

        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

        assertNull("the resumed reveal must eventually retire its cover", view.privateField("conversionSnapshotDrawable"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `clearing previews aborts pre-frame gl turn and resumes conversion fade`() {
        val overlay = FakeCurlOverlay(autoFirstFrameReady = false)
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        val layout = requireNotNull(view.textView.layout)
        view.mode = EpubFlowView.Mode.SCROLL
        view.pendingDecodesProvider = { false }
        view.setModeAnchored(
            EpubFlowView.Mode.PAGED,
            layout.getLineStart(5.coerceAtMost(layout.lineCount - 1)),
        )
        val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
        cover.javaClass.getDeclaredField("alphaValue").apply { isAccessible = true }.setInt(cover, 128)

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(overlay.active)
        assertNull(view.privateField("conversionFadeAnimator"))

        view.clearBoundaryPreviews()

        assertFalse(overlay.active)
        assertFalse(view.privateBool("glConversionOwnerHeld"))
        assertFalse(view.privateBool("glConversionFadePaused"))
        assertNotNull(view.privateField("conversionFadeAnimator"))

        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        assertNull("the resumed reveal must retire its cover", view.privateField("conversionSnapshotDrawable"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `discrete gl settle timeout before first frame does not commit target page`() {
        val overlay = FakeCurlOverlay(autoFirstFrameReady = false)
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
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
        val startPage = view.currentPageIndex()
        val startTop = view.scrollY

        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(1_000L, TimeUnit.MILLISECONDS)

        assertEquals(
            "the host timeout must not commit a target that GL never proved visible",
            startPage,
            view.currentPageIndex(),
        )
        assertEquals("the host timeout must restore the outgoing page anchor", startTop, view.scrollY)
    }

    @Test
    fun `committed discrete gl turn lands on adjacent page from normalized anchor`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val pageThreeTop = requireNotNull(view.pageTopPxAt(3))
        val nearPageTwo = pageOneTop + ((pageTwoTop - pageOneTop) * 3 / 4)

        view.scrollTo(0, nearPageTwo)

        assertTrue(view.goToAdjacentPage(1))
        overlay.settle(committed = true)

        assertEquals(3, view.currentPageIndex())
        assertEquals(pageThreeTop, view.scrollY)
    }

    @Test
    fun `discrete gl turn snapshots clamped final page at canonical visual top`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val maxScroll = (view.getChildAt(0).height - view.height).coerceAtLeast(0)
        val target = (0 until view.pageCount()).firstOrNull { index ->
            requireNotNull(view.pageTopPxAt(index)) > maxScroll
        } ?: error("test requires a short final page whose raw top exceeds maxScroll=$maxScroll")
        assertTrue("test target must have a previous page", target > 0)
        val rawTargetTop = requireNotNull(view.pageTopPxAt(target))
        val from = target - 1
        view.goToPage(from)
        val fromVisualTop = view.scrollY

        assertEquals(fromVisualTop, view.textureTopPxForPageForTest(from))
        assertEquals(maxScroll, view.textureTopPxForPageForTest(target))
        assertTrue(rawTargetTop != view.textureTopPxForPageForTest(target))

        assertTrue(view.goToAdjacentPage(1))

        assertEquals(1, overlay.startCount)
    }

    @Test
    fun `page turn snapshots use the same padding clip as paged rendering`() {
        val view = pagedFlowView(textPaddingTop = 12)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val layout = requireNotNull(view.textView.layout)
        val topLine = layout.getLineForVertical(pageOneTop)
        assertEquals("test must use a canonical line-top page", pageOneTop, layout.getLineTop(topLine))

        val pageBottom = pageOneTop + view.height
        var bottomLine = layout.getLineForVertical(pageBottom - 1)
        if (bottomLine > 0 && layout.getLineBottom(bottomLine) > pageBottom) bottomLine--
        val unpaddedClipBottom = (layout.getLineBottom(bottomLine) - pageOneTop).coerceIn(0, view.height)

        assertEquals(
            "snapshot top clip must drop the same padding strip as dispatchDraw",
            pageOneTop + view.textView.paddingTop,
            view.snapshotClipTopForTest(pageOneTop),
        )
        assertEquals(
            "snapshot bottom clip must include TextView padding just like dispatchDraw",
            minOf(pageOneTop + unpaddedClipBottom + view.textView.paddingTop, pageOneTop + view.height),
            view.snapshotClipBottomForTest(pageOneTop),
        )
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
    fun `next page from clamped final page reports chapter boundary`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val maxScroll = (view.getChildAt(0).height - view.height).coerceAtLeast(0)
        val finalPage = (0 until view.pageCount()).lastOrNull { index ->
            requireNotNull(view.pageTopPxAt(index)) > maxScroll
        } ?: error("test requires a short final page whose raw top exceeds maxScroll=$maxScroll")

        view.goToPage(finalPage)

        assertEquals("final page should be visually clamped to maxScroll", maxScroll, view.scrollY)
        assertEquals("final page index should be retained after clamped goToPage", finalPage, view.currentPageIndex())

        val moved = view.goToAdjacentPage(1)

        assertEquals("next from final page must return false for cross-spine advance", false, moved)
        assertEquals(finalPage, view.currentPageIndex())
        assertEquals(maxScroll, view.scrollY)
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
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.setPrivateField("awaitingReveal", true)
        view.getChildAt(0).alpha = 0f
        val visibleCover = markerBitmap(view.width, view.height)
        view.showConversionSnapshotForTest(visibleCover.copy(Bitmap.Config.ARGB_8888, false))

        assertTrue(view.goToAdjacentPage(1))

        val front = requireNotNull(overlay.frontBitmapCopy) { "GL turn should receive a front texture" }
        assertAllPixelsEqual(
            "the GL curl must start from the same frozen frame that was visible before the tap",
            visibleCover,
            front,
        )
        front.recycle()
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
    fun `partial conversion capture failure cannot escape through interactive slide or gl fallbacks`() {
        listOf(PageFlipStyle.SLIDE, PageFlipStyle.SIMULATION).forEach { style ->
            val tapZones = mutableListOf<EpubFlowTapZone>()
            val curlOverlay = FakeCurlOverlay()
            val view = pagedFlowView(flipStyle = style, onTapZone = tapZones::add).apply {
                if (style == PageFlipStyle.SIMULATION) this.curlOverlay = curlOverlay
            }
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
            assertEquals("style=$style must not hand failed shots to GL", 0, curlOverlay.startCount)

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
    fun `rapid discrete gl turn while overlay is active does not restart or double advance`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))

        assertEquals(1, overlay.startCount)
        assertEquals(0, view.currentPageIndex())
        assertEquals(0, view.scrollY)

        overlay.settle(committed = true)

        assertEquals(1, view.currentPageIndex())
        assertEquals(pageOneTop, view.scrollY)
    }

    @Test
    fun `discrete gl turn falls back to target page if harism never settles`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))

        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(1_000L, TimeUnit.MILLISECONDS)

        assertEquals(1, view.currentPageIndex())
        assertEquals(pageOneTop, view.scrollY)
        assertEquals(false, overlay.active)
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
    fun `discrete gl turn recycles stale cached textures when top keys mismatch`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
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

        assertEquals(1, overlay.startCount)
        assertEquals(view.width, overlay.frontWidth)
        assertEquals(view.height, overlay.frontHeight)
        assertEquals(view.width, overlay.revealedWidth)
        assertEquals(view.height, overlay.revealedHeight)
        assertTrue("stale front texture should be recycled after key mismatch", staleFront.isRecycled)
        assertTrue("stale revealed texture should be recycled after key mismatch", staleRevealed.isRecycled)
    }

    @Test
    fun `discrete gl turn ignores recycled cached textures and snapshots live pages`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
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

        assertEquals(1, overlay.startCount)
        assertEquals(view.width, overlay.frontWidth)
        assertEquals(view.height, overlay.frontHeight)
        assertEquals(view.width, overlay.revealedWidth)
        assertEquals(view.height, overlay.revealedHeight)
    }

    @Test
    fun `discrete gl turn treats partial cached textures as stale and snapshots live pages`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
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

        assertEquals(1, overlay.startCount)
        assertEquals(view.width, overlay.frontWidth)
        assertEquals(view.height, overlay.frontHeight)
        assertEquals(view.width, overlay.revealedWidth)
        assertEquals(view.height, overlay.revealedHeight)
        assertTrue("partial revealed texture should be recycled before live snapshot", partialRevealed.isRecycled)
    }

    @Test
    fun `precache refreshes recycled cached textures even when top keys match`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
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
    fun `precache skips hidden initial reveal layer`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        (view.privateField("cachedFrontBitmap") as Bitmap?)?.recycle()
        (view.privateField("cachedRevealedBitmap") as Bitmap?)?.recycle()
        view.setPrivateField("cachedFrontBitmap", null)
        view.setPrivateField("cachedRevealedBitmap", null)
        view.setPrivateField("cachedFromPage", -1)
        view.setPrivateField("cachedTargetPage", -1)
        view.setPrivateField("cachedFromTopPx", -1)
        view.setPrivateField("cachedTargetTopPx", -1)
        view.getChildAt(0).alpha = 0f

        view.preCachePageTexturesForTest()

        assertNull("hidden reveal layer must not be cached as the front texture", view.privateField("cachedFrontBitmap"))
        assertNull("hidden reveal layer must not be cached as the revealed texture", view.privateField("cachedRevealedBitmap"))
    }

    @Test
    fun `precache discards partial texture pair when revealed snapshot fails`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        (view.privateField("cachedFrontBitmap") as Bitmap?)?.recycle()
        (view.privateField("cachedRevealedBitmap") as Bitmap?)?.recycle()
        view.setPrivateField("cachedFrontBitmap", null)
        view.setPrivateField("cachedRevealedBitmap", null)
        view.setPrivateField("cachedFromPage", -1)
        view.setPrivateField("cachedTargetPage", -1)
        view.setPrivateField("cachedFromTopPx", -1)
        view.setPrivateField("cachedTargetTopPx", -1)
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
    fun `pending precache does not commit while gl turn is active`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        (view.privateField("cachedFrontBitmap") as Bitmap?)?.recycle()
        (view.privateField("cachedRevealedBitmap") as Bitmap?)?.recycle()
        view.setPrivateField("cachedFrontBitmap", null)
        view.setPrivateField("cachedRevealedBitmap", null)
        view.setPrivateField("cachedFromPage", -1)
        view.setPrivateField("cachedTargetPage", -1)
        view.setPrivateField("cachedFromTopPx", -1)
        view.setPrivateField("cachedTargetTopPx", -1)

        view.preCachePageTexturesForTest(idlePostedWork = false)
        assertTrue(view.goToAdjacentPage(1))
        assertTrue("GL overlay should keep a turn active", overlay.active)
        shadowOf(Looper.getMainLooper()).idle()

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
        (view.privateField("cachedFrontBitmap") as Bitmap?)?.recycle()
        (view.privateField("cachedRevealedBitmap") as Bitmap?)?.recycle()
        view.setPrivateField("cachedFrontBitmap", null)
        view.setPrivateField("cachedRevealedBitmap", null)
        view.setPrivateField("cachedFromPage", -1)
        view.setPrivateField("cachedTargetPage", -1)
        view.setPrivateField("cachedFromTopPx", -1)
        view.setPrivateField("cachedTargetTopPx", -1)
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
        (view.privateField("cachedFrontBitmap") as Bitmap?)?.recycle()
        (view.privateField("cachedRevealedBitmap") as Bitmap?)?.recycle()
        view.setPrivateField("cachedFrontBitmap", null)
        view.setPrivateField("cachedRevealedBitmap", null)
        view.setPrivateField("cachedFromPage", -1)
        view.setPrivateField("cachedTargetPage", -1)
        view.pendingDecodesProvider = { true }

        view.preCachePageTexturesForTest()

        assertNull("transparent image placeholders must not become cached turn fronts", view.privateField("cachedFrontBitmap"))
        assertNull("transparent image placeholders must not become cached revealed pages", view.privateField("cachedRevealedBitmap"))
    }

    @Test
    fun `precache waits until image reflow pagination matches the live layout`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        (view.privateField("cachedFrontBitmap") as Bitmap?)?.recycle()
        (view.privateField("cachedRevealedBitmap") as Bitmap?)?.recycle()
        view.setPrivateField("cachedFrontBitmap", null)
        view.setPrivateField("cachedRevealedBitmap", null)
        view.setPrivateField("cachedFromPage", -1)
        view.setPrivateField("cachedTargetPage", -1)
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
    fun `temporary scroll release does not trigger tap zone`() {
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
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 48L,
                MotionEvent.ACTION_MOVE,
                startX,
                view.height * 0.10f,
            ),
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + 72L,
                MotionEvent.ACTION_UP,
                startX,
                view.height * 0.10f,
            ),
        )

        assertEquals("temporary-scroll UP must not be treated as a clean tap", emptyList<EpubFlowTapZone>(), tapZones)
        val canonicalTops = (0 until view.pageCount()).mapNotNull { view.textureTopPxForPageForTest(it) }.toSet()
        assertTrue(
            "temporary-scroll UP should settle on a canonical paged anchor, scrollY=${view.scrollY}, pages=$canonicalTops",
            view.scrollY in canonicalTops,
        )
    }

    @Test
    fun `temporary scroll release normalizes to canonical page anchor`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val layout = requireNotNull(view.textView.layout)
        val midLineTop = (layout.getLineForVertical(pageOneTop + 1) + 1 until layout.lineCount)
            .map { layout.getLineTop(it) }
            .first { it in (pageOneTop + 1) until pageTwoTop }
        view.scrollTo(0, midLineTop)

        view.settleTemporaryScrollAnchorForTest()

        val canonicalTops = (0 until view.pageCount()).mapNotNull { view.textureTopPxForPageForTest(it) }.toSet()
        assertTrue(
            "temporary scroll release must land on a real paged anchor, scrollY=${view.scrollY}, pages=$canonicalTops",
            view.scrollY in canonicalTops,
        )
        assertEquals("current page should describe the parked canonical anchor", view.pageTopPxAt(view.currentPageIndex()), view.scrollY)
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
    fun `inner center horizontal drag is consumed without page turn`() {
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

        assertEquals("inner center horizontal drag must not trigger a paged tap/flip", emptyList<EpubFlowTapZone>(), tapZones)
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

    private fun pagedFlowView(
        flipStyle: PageFlipStyle = PageFlipStyle.NONE,
        onTapZone: (EpubFlowTapZone) -> Unit = {},
        onTopOffsetChanged: (Int) -> Unit = {},
        spannable: CharSequence? = null,
        text: String? = null,
        textPaddingTop: Int = 0,
    ): EpubFlowView {
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = EpubFlowView(
            context = context,
            onTapZone = onTapZone,
            onTopOffsetChanged = onTopOffsetChanged,
            onSelectionRange = { _, _ -> },
        )
        activity.addContentView(view, ViewGroup.LayoutParams(360, 120))
        view.flipStyle = flipStyle
        view.textView.textSize = 18f
        view.textView.setPadding(0, textPaddingTop, 0, 0)
        val chapterText = text ?: (1..80).joinToString("\n") { "Line $it marker text." }
        val block = EpubDisplayBlock.Text(chapterText, headingLevel = null, paragraphIndex = 0)
        val flow = epubBuildChapterFlow(spineIndex = 0, blocks = listOf(block))

        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        view.setChapter(flow, spannable ?: flow.text, pageHeightPx = 120)
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(exactly(360), exactly(120))
        view.layout(0, 0, 360, 120)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        return view
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
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
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
        javaClass.getDeclaredMethod("snapshotClipBottomFor", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, topPx) as Int

    private fun EpubFlowView.settleTemporaryScrollAnchorForTest() {
        javaClass.getDeclaredMethod("settleTemporaryScrollAnchor")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun EpubFlowView.pageClipBottomForTest(): Int? =
        javaClass.getDeclaredMethod("pageClipBottomInViewport")
            .apply { isAccessible = true }
            .invoke(this) as Int?

    private fun EpubFlowView.privateInt(name: String): Int =
        privateField(name) as Int

    private fun EpubFlowView.privateBool(name: String): Boolean =
        privateField(name) as Boolean

    private fun EpubFlowView.setPrivateField(name: String, value: Any?) {
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
    }

    private fun EpubFlowView.privateField(name: String): Any? =
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

    private class FakeCurlOverlay(
        private val autoFirstFrameReady: Boolean = true,
        private val transferRevealedOwnership: Boolean = false,
    ) : EpubCurlTurnOverlay {
        private var settled: ((Boolean) -> Unit)? = null
        private var firstFrameReady: (() -> Unit)? = null
        private var ownedFrontBitmap: Bitmap? = null
        private var ownedRevealedBitmap: Bitmap? = null
        override var active: Boolean = false
            private set
        var startCount: Int = 0
            private set
        var frontWidth: Int = -1
            private set
        var frontHeight: Int = -1
            private set
        var revealedWidth: Int = -1
            private set
        var revealedHeight: Int = -1
            private set
        var frontBitmapCopy: Bitmap? = null
            private set
        var dismissCount: Int = 0
            private set
        var takeRevealedCount: Int = 0
            private set

        override fun start(
            front: Bitmap,
            revealed: Bitmap,
            forward: Boolean,
            settled: (committed: Boolean) -> Unit,
            firstFrameReady: () -> Unit,
        ) {
            frontWidth = front.width
            frontHeight = front.height
            revealedWidth = revealed.width
            revealedHeight = revealed.height
            frontBitmapCopy?.recycle()
            frontBitmapCopy = front.copy(Bitmap.Config.ARGB_8888, false)
            if (transferRevealedOwnership) {
                ownedFrontBitmap = front
                ownedRevealedBitmap = revealed
            } else {
                front.recycle()
                revealed.recycle()
            }
            this.settled = settled
            this.firstFrameReady = firstFrameReady
            active = true
            startCount += 1
            if (autoFirstFrameReady) signalFirstFrameReady()
        }

        override fun animateTurn(durationMs: Long) = Unit

        override fun takeRevealedBitmap(): Bitmap? {
            takeRevealedCount += 1
            val bitmap = ownedRevealedBitmap
            ownedRevealedBitmap = null
            if (ownedFrontBitmap === bitmap) ownedFrontBitmap = null
            return bitmap?.takeUnless { it.isRecycled }
        }

        override fun dismiss() {
            dismissCount += 1
            active = false
            firstFrameReady = null
            ownedFrontBitmap?.let { if (!it.isRecycled) it.recycle() }
            ownedRevealedBitmap?.let { if (!it.isRecycled) it.recycle() }
            ownedFrontBitmap = null
            ownedRevealedBitmap = null
        }

        fun settle(committed: Boolean) {
            val callback = checkNotNull(settled) { "curl overlay was not started" }
            settled = null
            active = false
            callback(committed)
        }

        fun signalFirstFrameReady() {
            val callback = checkNotNull(firstFrameReady) { "curl overlay was not waiting for its first frame" }
            firstFrameReady = null
            callback()
        }
    }

}
