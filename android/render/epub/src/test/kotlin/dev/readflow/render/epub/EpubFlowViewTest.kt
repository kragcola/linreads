package dev.readflow.render.epub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.ui.readerPaperBackground
import dev.readflow.render.api.READER_SEARCH_HIGHLIGHT_COLOR
import dev.readflow.render.api.ReaderSearchHighlightSpan
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.ReaderTextHighlightSpan
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

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
    fun `interactive slide keeps drawable ownership but renders through a distinct overlay view`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)

        try {
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val renderer = view.requireActiveSlideRendererView()

            assertFalse("the slide renderer must be distinct from the live page", renderer === view.getChildAt(0))
            assertFalse("the slide renderer must be distinct from the selectable text", renderer === view.textView)
            assertNotNull("the active slide renderer must be attached through the View overlay", renderer.parent)
            assertNull(
                "PageSlideDrawable must remain the bitmap owner without invalidating the Compose host overlay",
                slide.callback,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `interactive slide progress moves only the renderer by mirrored page width geometry`() {
        val progress = 0.375f
        for (forward in listOf(true, false)) {
            val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
            try {
                assertTrue("direction forward=$forward needs adjacent pages", view.pageCount() > 2)
                if (!forward) view.goToPage(1)
                val anchorX = if (forward) view.width.toFloat() else 0f
                assertTrue(view.beginInteractiveCurl(forward = forward, anchorX = anchorX))
                val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
                val renderer = view.requireActiveSlideRendererView()
                val livePage = view.getChildAt(0)
                // The fixture does not precache, so the installed pair may be a cold BitmapFrame
                // pair or a warm ArtifactFrame pair. This test owns renderer geometry, so pin the
                // exact frame wrappers and keep the liveness check frame-type agnostic.
                val front = checkNotNull(slide.frontFrameForTest()) {
                    "a started SLIDE turn must own a current-page frame"
                }
                val revealed = checkNotNull(slide.revealedFrameForTest()) {
                    "a started SLIDE turn must own a target-page frame"
                }
                val initialFrame = intArrayOf(renderer.left, renderer.top, renderer.right, renderer.bottom)
                val initialTranslationY = renderer.translationY

                assertEquals(0f, renderer.translationX, 0.001f)
                assertNull("progress must not route Drawable invalidation into the host overlay", slide.callback)

                val x = if (forward) view.width * (1f - progress) else view.width * progress
                view.updateInteractiveCurl(x)

                val expectedTranslation = (if (forward) -1f else 1f) * progress * view.width
                assertEquals(progress, slide.progress, 0.001f)
                assertEquals(expectedTranslation, renderer.translationX, 0.001f)
                assertEquals(initialTranslationY, renderer.translationY, 0.001f)
                assertTrue(
                    "progress must not relayout the recorded page pair",
                    initialFrame.contentEquals(
                        intArrayOf(renderer.left, renderer.top, renderer.right, renderer.bottom),
                    ),
                )
                assertEquals(0f, livePage.translationX, 0.001f)
                assertEquals(0f, livePage.translationY, 0.001f)
                assertSame("progress must retain the outgoing frame identity", front, slide.frontFrameForTest())
                assertSame(
                    "progress must retain the revealed frame identity",
                    revealed,
                    slide.revealedFrameForTest(),
                )
                assertTrue("progress must not discard the outgoing frame", slide.frameIsLiveForTest(front))
                assertTrue("progress must not discard the revealed frame", slide.frameIsLiveForTest(revealed))
            } finally {
                view.dispose()
            }
        }
    }

    @Test
    fun `slide settle and cancel detach renderer after transferring artifact ownership`() {
        for (commit in listOf(true, false)) {
            val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
            try {
                assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
                val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
                val renderer = view.requireActiveSlideRendererView()
                val front = checkNotNull(slide.frontArtifactForTest()) {
                    "warm SLIDE settle must own a current-page artifact"
                }
                val revealed = checkNotNull(slide.revealedArtifactForTest()) {
                    "warm SLIDE settle must own a target-page artifact"
                }

                view.updateInteractiveCurl(x = view.width * if (commit) 0.10f else 0.98f)
                view.endInteractiveCurl(velocityX = 0f)
                val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
                assertNotNull("commit=$commit requires a settle animator", animator)
                animator!!.end()

                assertTrue(
                    "commit=$commit must clear the active slide View field",
                    view.activeSlideRendererViews().isEmpty(),
                )
                assertNull("commit=$commit must detach the slide View from its overlay", renderer.parent)
                assertNull("commit=$commit must clear the active slide overlay pointer", view.privateField("slideOverlayView"))
                assertNull("commit=$commit must clear the drawable field", view.privateField("slideDrawable"))
                assertNull("commit=$commit must release the outgoing reference", slide.reflectedField("frontBitmap"))
                assertNull("commit=$commit must release the revealed reference", slide.reflectedField("revealedBitmap"))
                assertTrue("commit=$commit must keep the outgoing artifact live", front.slideArtifactRecordIsLive())
                assertTrue("commit=$commit must keep the revealed artifact live", revealed.slideArtifactRecordIsLive())
                if (commit) {
                    assertSame("commit=$commit must rekey the revealed artifact as current", revealed, view.slideFrontArtifactForTest())
                    assertSame("commit=$commit must rekey the outgoing artifact as backward", front, view.slideBackwardArtifactForTest())
                } else {
                    assertSame("cancel must keep the outgoing artifact as current", front, view.slideFrontArtifactForTest())
                    assertSame("cancel must keep the revealed artifact as forward target", revealed, view.slideRevealedArtifactForTest())
                }
            } finally {
                view.dispose()
            }
        }
    }

    @Test
    fun `warm slide settle keeps rekeyed artifact display lists behind the two host frame barrier`() {
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
        )
        view.background = ColorDrawable(0xFFEDE6D6.toInt())

        try {
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            val frontArtifact = checkNotNull(view.slideFrontArtifactForTest())
            val revealedArtifact = checkNotNull(view.slideRevealedArtifactForTest())

            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val renderer = view.requireActiveSlideRendererView()
            assertSame("the active strip must record the warmed front artifact", frontArtifact, slide.frontArtifactForTest())
            assertSame("the active strip must record the warmed revealed artifact", revealedArtifact, slide.revealedArtifactForTest())

            view.updateInteractiveCurl(x = view.width * 0.10f)
            view.endInteractiveCurl(velocityX = 0f)
            val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
            assertNotNull("the committed turn must enter visual settle", animator)
            // Hold the rapid-idle window across the settle callback so continueQueuedTurnsOrPrecache
            // schedules rapid idle instead of starting normal precache that would interleave its
            // artifact chain with the host-frame fence below.
            view.setPrivateField("rapidTurnSequenceActive", true)
            animator!!.end()
            view.setPrivateField("rapidTurnSequenceActive", false)

            assertNull("settle must detach the renderer before its frame barriers begin", renderer.parent)
            assertNull("settle must clear the active slide overlay pointer", view.privateField("slideOverlayView"))
            assertTrue(
                "detach must pin the recorded artifacts behind the render fence",
                view.renderRetiredSlideArtifactsForTest() == setOf(frontArtifact, revealedArtifact),
            )
            assertSame("the revealed artifact must become the stable current-page cache", revealedArtifact, view.slideFrontArtifactForTest())
            assertSame("the outgoing artifact must become the stable backward-page cache", frontArtifact, view.slideBackwardArtifactForTest())
            assertTrue("rekeyed artifacts must be live before frame one", frontArtifact.slideArtifactRecordIsLive())
            assertTrue("rekeyed artifacts must be live before frame one", revealedArtifact.slideArtifactRecordIsLive())

            // Drop the outgoing stable owner and request discard while its display list is still
            // pinned behind the fence: the artifact must stay live until frame two, while the
            // rekeyed current artifact survives the barrier untouched.
            view.discardSlideArtifactForTest(frontArtifact)
            view.setPrivateField("slideBackwardSlot", null)
            assertTrue("ownerless artifact must stay live while the fence charges it", frontArtifact.slideArtifactRecordIsLive())
            assertTrue("the rekeyed current artifact must stay live", revealedArtifact.slideArtifactRecordIsLive())

            shadowOf(Looper.getMainLooper()).runOneTask()
            assertTrue("the ownerless artifact must remain protected after only one host frame", frontArtifact.slideArtifactRecordIsLive())

            shadowOf(Looper.getMainLooper()).runOneTask()

            assertFalse("the ownerless artifact may discard only after frame two", frontArtifact.slideArtifactRecordIsLive())
            assertTrue("frame barriers must not discard an artifact still owned by the stable cache", revealedArtifact.slideArtifactRecordIsLive())
            assertSame(revealedArtifact, view.slideFrontArtifactForTest())
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    // The direct-overlay raster check runs against the API 28 Picture artifact record, mirroring
    // the warm artifact overlay test: a RenderNode display list cannot be drawn through a software
    // Bitmap Canvas (Canvas.drawRenderNode is a hardware-accelerated contract) and LEGACY Robolectric
    // cannot replay it. The real API 29+ RenderNode path stays covered in the hardware/device lane.
    @Config(sdk = [28])
    fun `mixed slide frame pair installs the direct two-page overlay and retires both owners`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
            val pages = view.privateField("paged") as List<EpubFlowPage>
            val originWindow = pages[0]
            val targetWindow = pages[1]
            val origin = view.capturePageTurnOriginForTest()
            assertEquals("the fixture origin must be the first page window", 0, origin.reflectedField("topPx") as Int)
            assertSame(originWindow, origin.reflectedField("window"))

            // The direct two-page overlay accepts every frame kind: a recorded command artifact on
            // the outgoing band and a viewport Bitmap on the revealed band are both replayed in the
            // same 2W strip, without any hidden record pass or rejection gate.
            val artifact = view.recordSlidePageArtifactForTest(originWindow.topPx, originWindow) as SlidePageArtifact
            val revealedBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF2563EB.toInt())
            }
            var parkedCount = 0
            var startedCount = 0
            view.onPageTurnTargetParked = { parkedCount++ }
            view.onPageTurnStarted = { startedCount++ }

            val result = view.startLocalInteractiveCurlForTest(
                origin = origin,
                targetWindow = targetWindow,
                forward = true,
                outgoing = SlidePageFrame.ArtifactFrame(artifact),
                revealed = SlidePageFrame.BitmapFrame(revealedBitmap),
            )

            assertEquals(
                "a mixed slide pair must install synchronously",
                "STARTED",
                result.name,
            )
            assertEquals("a started mixed pair must report the parked target", 1, parkedCount)
            assertEquals("a started mixed pair must report the turn start", 1, startedCount)
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val renderer = view.requireActiveSlideRendererView() as PageSlideOverlayView
            assertSame("the outgoing band must own the recorded artifact", artifact, slide.frontArtifactForTest())
            assertSame("the revealed band must own the supplied Bitmap", revealedBitmap, slide.privateBitmap("revealedBitmap"))
            assertNotNull("the mixed overlay must stay attached", renderer.parent)
            // Rasterize the 2W strip: the revealed Bitmap occupies local [W, 2W) and its first draw
            // is visible (never blank).
            val stripFrame = Bitmap.createBitmap(
                renderer.width.coerceAtLeast(1),
                renderer.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            try {
                renderer.draw(Canvas(stripFrame))
                assertEquals(
                    "the revealed band's first draw must carry the Bitmap pixels",
                    0xFF2563EB.toInt(),
                    stripFrame.getPixel(view.width + view.width / 2, view.height / 2),
                )
            } finally {
                stripFrame.recycle()
            }
            // Ownership retirement keeps both identities behind the two-host-frame barrier.
            view.dispose()
            assertNull("dispose must detach the renderer", renderer.parent)
            assertEquals(
                "the recorded artifact must be pinned behind the render fence",
                setOf<Any>(artifact),
                view.renderRetiredSlideArtifactsForTest(),
            )
            assertTrue("the artifact must stay live behind the fence", artifact.slideArtifactRecordIsLive())
            assertFalse("the revealed Bitmap must not recycle before frame one", revealedBitmap.isRecycled)
            shadowOf(Looper.getMainLooper()).runOneTask()
            shadowOf(Looper.getMainLooper()).runOneTask()
            assertTrue("the revealed Bitmap must recycle only after frame two", revealedBitmap.isRecycled)
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `disposing an active slide defers display list bitmap release through two host frames`() {
        val budget = PageShotBudget(48L * 1024L * 1024L)
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            pageShotBudget = budget,
        )
        view.recycleCachedTexturesForTest()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        val renderer = view.requireActiveSlideRendererView()
        val front = slide.privateBitmap("frontBitmap")
        val revealed = slide.privateBitmap("revealedBitmap")
        view.drawToBitmapForTest().recycle()
        val leasedWhileRecorded = budget.leasedBytes
        val chargedWhileRecorded = budget.chargedBytes
        val recordedBytes = front.allocationByteCount.toLong() + revealed.allocationByteCount.toLong()
        assertEquals(recordedBytes, leasedWhileRecorded)
        assertEquals(recordedBytes, chargedWhileRecorded)

        view.dispose()

        assertNull("dispose must detach the renderer before its frame barriers begin", renderer.parent)
        assertFalse("dispose must not recycle the recorded outgoing bitmap before frame one", front.isRecycled)
        assertFalse("dispose must not recycle the recorded revealed bitmap before frame one", revealed.isRecycled)
        assertEquals(leasedWhileRecorded, budget.leasedBytes)
        assertEquals(chargedWhileRecorded, budget.chargedBytes)

        shadowOf(Looper.getMainLooper()).runOneTask()

        assertFalse("dispose must keep the outgoing bitmap protected after one host frame", front.isRecycled)
        assertFalse("dispose must keep the revealed bitmap protected after one host frame", revealed.isRecycled)
        assertEquals(leasedWhileRecorded, budget.leasedBytes)
        assertEquals(chargedWhileRecorded, budget.chargedBytes)

        shadowOf(Looper.getMainLooper()).runOneTask()

        assertTrue("dispose may recycle the outgoing bitmap only after frame two", front.isRecycled)
        assertTrue("dispose may recycle the revealed bitmap only after frame two", revealed.isRecycled)
        assertEquals(0L, budget.leasedBytes)
        assertEquals(0L, budget.chargedBytes)
    }

    @Test
    fun `slide retirement fence releases ownerless artifacts and resumes precache after frame two`() {
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
        )
        view.background = ColorDrawable(0xFFEDE6D6.toInt())

        try {
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            val frontArtifact = checkNotNull(view.slideFrontArtifactForTest())
            val revealedArtifact = checkNotNull(view.slideRevealedArtifactForTest())

            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val renderer = view.requireActiveSlideRendererView()

            view.updateInteractiveCurl(x = view.width * 0.10f)
            view.endInteractiveCurl(velocityX = 0f)
            val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
            assertNotNull("the committed turn must enter visual settle", animator)
            // Hold the rapid-idle window across the settle callback so continueQueuedTurnsOrPrecache
            // schedules rapid idle instead of starting normal precache that would clear the retained
            // current/backward owners before the manual ownerless-fence setup below.
            view.setPrivateField("rapidTurnSequenceActive", true)
            animator!!.end()
            view.setPrivateField("rapidTurnSequenceActive", false)

            assertNull("settle must detach the renderer before its frame barriers begin", renderer.parent)
            assertNull("settle must clear the active slide overlay pointer", view.privateField("slideOverlayView"))
            assertNull(
                "the settle callback must not stage an artifact precache while the rapid window is active",
                view.privateField("pendingSlideArtifactPrecache"),
            )
            assertFalse(
                "the settle callback must not arm artifact precache while the rapid window is active",
                view.privateBool("slideArtifactPrecachePending"),
            )
            assertSame(
                "the revealed artifact must become the stable current-page cache",
                revealedArtifact,
                view.slideFrontArtifactForTest(),
            )
            assertSame(
                "the outgoing artifact must become the stable backward-page cache",
                frontArtifact,
                view.slideBackwardArtifactForTest(),
            )
            assertTrue("stable artifacts must be live before frame one", frontArtifact.slideArtifactRecordIsLive())
            assertTrue("stable artifacts must be live before frame one", revealedArtifact.slideArtifactRecordIsLive())

            // While the retirement fence still holds the outgoing artifact, drop its stable
            // backward owner and request discard through the render fence. The ownerless artifact
            // stays live until frame two; the rekeyed current owner survives untouched.
            view.setPrivateField("slideBackwardSlot", null)
            view.discardSlideArtifactForTest(frontArtifact)

            assertNull(
                "detaching must remove the outgoing artifact from stable cache ownership",
                view.slideBackwardArtifactForTest(),
            )
            assertTrue("detached outgoing artifact must stay live while the fence charges it", frontArtifact.slideArtifactRecordIsLive())
            assertTrue("detaching the outgoing owner must not disturb the stable current owner", revealedArtifact.slideArtifactRecordIsLive())

            shadowOf(Looper.getMainLooper()).runOneTask()

            assertTrue("the ownerless outgoing artifact must remain protected after only one host frame", frontArtifact.slideArtifactRecordIsLive())
            assertTrue("the stable current artifact must remain protected after only one host frame", revealedArtifact.slideArtifactRecordIsLive())

            shadowOf(Looper.getMainLooper()).runOneTask()

            assertFalse("the ownerless outgoing artifact may discard only after frame two", frontArtifact.slideArtifactRecordIsLive())
            assertTrue("frame barriers must not discard an artifact still owned by the stable cache", revealedArtifact.slideArtifactRecordIsLive())
            assertSame(revealedArtifact, view.slideFrontArtifactForTest())
            assertTrue(
                "frame two must immediately re-arm the artifact precache for the newly admissible " +
                    "adjacent pages without a new event; " +
                    "pending=${view.privateBool("slideArtifactPrecachePending")} " +
                    "request=${view.privateField("pendingSlideArtifactPrecache")}",
                view.privateBool("slideArtifactPrecachePending") &&
                    view.privateField("pendingSlideArtifactPrecache") != null,
            )
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `release inside the directional threshold restores the exact free rest viewport`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val freeRest = nonLineTopBetween(view, pageOneTop, pageTwoTop, fraction = 0.75f)

        view.scrollTo(0, freeRest)

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.50f)
        view.updateInteractiveCurl(x = view.width * 0.99f)
        view.endInteractiveCurl(velocityX = 0f)
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals("a cancelled turn must restore the viewport that was under the finger", freeRest, view.scrollY)
    }

    @Test
    fun `boundary drag that returns inside the directional threshold cancels its cold request`() {
        val requests = mutableListOf<Pair<Boolean, Long>>()
        val cancellations = mutableListOf<Boolean>()
        val commits = mutableListOf<Any>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE).apply {
            onBoundaryPreviewNeeded = { forward, generation -> requests += forward to generation }
            onBoundaryPreviewRequestCancelled = cancellations::add
            installBoundaryCommitRecorderForTest(commits)
        }
        view.goToLastPage()
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, y),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 100L, MotionEvent.ACTION_MOVE, view.width * 0.50f, y),
            )
            assertEquals("BOUNDARY_WAITING", view.privateField("interactiveTurnState").toString())
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 300L, MotionEvent.ACTION_MOVE, view.width * 0.84f, y),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 450L, MotionEvent.ACTION_UP, view.width * 0.84f, y),
            )

            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertEquals(listOf(true to view.boundaryPreviewGenerationToken()), requests)
            assertEquals(listOf(true), cancellations)
            assertTrue(commits.isEmpty())
        } finally {
            view.dispose()
        }
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
        val oldFront = checkNotNull(view.slideFrontArtifactForTest())
        val oldRevealed = checkNotNull(view.slideRevealedArtifactForTest())

        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        val newFront = view.slideFrontArtifactForTest()
        val newForward = view.slideRevealedArtifactForTest()
        val newBackward = view.slideBackwardArtifactForTest()
        assertTrue(
            "commit must rekey the revealed frame as current and retain the old current as a neighbor; " +
                "oldFrontLive=${oldFront.slideArtifactRecordIsLive()} " +
                "oldRevealedLive=${oldRevealed.slideArtifactRecordIsLive()} " +
                "new=[$newBackward,$newFront,$newForward]",
            newFront === oldRevealed &&
                oldRevealed.slideArtifactRecordIsLive() &&
                oldFront.slideArtifactRecordIsLive() &&
                (newBackward === oldFront || newForward === oldFront),
        )
    }

    @Test
    fun `committed warmed interactive turn rekeys active identities into the next cache`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.preCachePageTexturesForTest()
        val oldFront = checkNotNull(view.slideFrontArtifactForTest())
        val oldRevealed = checkNotNull(view.slideRevealedArtifactForTest())

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        val activeSlide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        assertTrue(activeSlide.frontArtifactForTest() === oldFront)
        assertTrue(activeSlide.revealedArtifactForTest() === oldRevealed)
        view.updateInteractiveCurl(x = view.width * 0.10f)
        view.endInteractiveCurl(velocityX = 0f)
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        val newFront = view.slideFrontArtifactForTest()
        val newBackward = view.slideBackwardArtifactForTest()
        assertTrue(
            "interactive commit must transfer the revealed identity into current and the old front " +
                "into the backward neighbor without discarding or replacing either; " +
                "oldFrontLive=${oldFront.slideArtifactRecordIsLive()} " +
                "oldRevealedLive=${oldRevealed.slideArtifactRecordIsLive()} " +
                "newBackward=$newBackward newFront=$newFront",
            newFront === oldRevealed &&
                newBackward === oldFront &&
                oldRevealed.slideArtifactRecordIsLive() &&
                oldFront.slideArtifactRecordIsLive(),
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
    fun `vertical side swipe drives progress through the configured horizontal renderer`() {
        for (style in listOf(PageFlipStyle.SLIDE, PageFlipStyle.SIMULATION)) {
            val view = pagedFlowView(flipStyle = style)
            assertTrue("style=$style pageCount=${view.pageCount()}", view.pageCount() > 2)
            view.preCachePageTexturesForTest()
            val x = view.width * 0.85f
            val downY = view.height * 0.65f
            val moveY = view.height * 0.10f
            val downTime = SystemClock.uptimeMillis()

            try {
                view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, downY))
                view.dispatchTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, x, moveY),
                )

                val progress = when (style) {
                    PageFlipStyle.SLIDE -> {
                        val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?) {
                            "vertical SLIDE swipe must create PageSlideDrawable"
                        }
                        assertNull("vertical SLIDE swipe must not install PageCurlDrawable", view.privateField("curlDrawable"))
                        slide.progress
                    }
                    PageFlipStyle.SIMULATION -> {
                        val curl = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?) {
                            "vertical SIMULATION swipe must create PageCurlDrawable"
                        }
                        assertNull("vertical SIMULATION swipe must not install PageSlideDrawable", view.privateField("slideDrawable"))
                        curl.progress
                    }
                    else -> error("unsupported test style $style")
                }
                assertEquals(
                    "style=$style progress must still follow vertical finger travel",
                    (downY - moveY) / view.height.toFloat(),
                    progress,
                    0.001f,
                )
            } finally {
                view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, x, moveY))
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
                view.dispose()
            }
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
    fun `chapter replacement discards an active boundary finger turn before recycling its frame`() {
        val discardedTokens = mutableListOf<Long>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            onBoundaryTurnDiscarded = { discardedTokens += it.token }
        }
        view.goToLastPage()
        val preview = view.newBoundaryPreviewForTest(forward = true, token = 501L)
        assertTrue(view.offerBoundaryPreviewForTest(preview))
        val downX = view.width * 0.75f
        val moveX = view.width * 0.10f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()
        val replacementText = (1..40).joinToString("\n") { "Replacement chapter line $it." }
        val replacementFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(replacementText, headingLevel = null, paragraphIndex = 0)),
        )

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
        assertEquals("BOUNDARY_SOFTWARE", view.privateField("interactiveTurnState").toString())
        assertTrue(view.privateField("activeBoundaryPreview") === preview)
        assertFalse(preview.bitmap.isRecycled)

        view.setChapter(replacementFlow, replacementFlow.text, pageHeightPx = view.height)

        assertNull("the old engine target must no longer look active", view.privateField("activeBoundaryPreview"))
        assertEquals(listOf(501L), discardedTokens)
        assertTrue("the detached boundary frame must be recycled exactly once", preview.bitmap.isRecycled)
        assertNull(view.privateField("curlDrawable"))
        assertEquals("NONE", view.privateField("interactiveTurnState").toString())
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
        val previewRequests = mutableListOf<Boolean>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = tapZones::add,
        )
        view.installBoundaryCommitRecorderForTest(commits)
        view.onBoundaryPreviewNeeded = { forward, _ -> previewRequests += forward }
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
            assertEquals(listOf(true), previewRequests)
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
        val previewRequests = mutableListOf<Boolean>()
        view = pagedFlowView(
            flipStyle = PageFlipStyle.NONE,
            onTapZone = { zone ->
                if (zone == EpubFlowTapZone.NEXT) view.startDiscreteBoundaryTurn(1)
            },
        )
        view.installBoundaryCommitRecorderForTest(commits)
        view.onBoundaryPreviewNeeded = { forward, _ -> previewRequests += forward }
        view.goToLastPage()
        val downX = view.width * 0.85f
        val moveX = view.width * 0.05f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            view.onTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
            assertEquals("BOUNDARY_WAITING", view.privateField("interactiveTurnState").toString())
            assertEquals(listOf(true), previewRequests)

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
    fun `boundary promotion waits for a held busy gesture to enqueue on release`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        view.goToLastPage()
        view.offerReadyBoundaryPreviewForTest(forward = true, token = 502L)
        var promotedTurns: Pair<Int, Boolean>? = null
        view.onBoundaryTurnCommitted = {
            promotedTurns = view.takeQueuedPageTurnsForPromotion()
        }

        assertTrue(view.startDiscreteBoundaryTurn(1))
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))

        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertNull("the boundary must not promote before the held stream resolves", promotedTurns)
        assertTrue(view.privateBool("busyPageTurnGesture"))

        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 420L, MotionEvent.ACTION_UP, x, y),
        )

        assertEquals(1 to true, promotedTurns)
    }

    @Test
    fun `external page mutation cancels a deferred held boundary commit`() {
        val discardedTokens = mutableListOf<Long>()
        val evictedTokens = mutableListOf<Long>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            onBoundaryTurnDiscarded = { discardedTokens += it.token }
            onBoundaryPreviewEvicted = { evictedTokens += it.token }
        }
        view.goToLastPage()
        assertTrue(view.preparePageShotBudgetForBoundaryPreview(forward = true, required = true))
        view.offerReadyBoundaryPreviewForTest(forward = true, token = 503L)

        assertTrue(view.startDiscreteBoundaryTurn(1))
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertTrue(view.privateBool("deferredBoundaryFinishCommit"))
        assertTrue(view.isPageMutationInFlight())

        view.goToPage(0)

        assertFalse("the external mutation must retire the deferred boundary transaction", view.isPageMutationInFlight())
        assertFalse(view.privateBool("deferredBoundaryFinishCommit"))
        assertFalse(view.privateBool("busyPageTurnGesture"))
        assertNull(view.privateField("activeBoundaryPreview"))
        assertNull(view.privateField("curlDrawable"))
        assertTrue("external navigation must not request a retry for the retired preview", discardedTokens.isEmpty())
        assertEquals(listOf(503L), evictedTokens)
        assertNull(view.privateField("boundaryPreviewBudgetDirection"))
        val pendingPrecache = checkNotNull(view.privateField("pendingPageTexturePrecache"))
        assertEquals("the pending warmup must belong to the new page", 0, pendingPrecache.reflectedField("fromPage"))
        assertEquals(
            requireNotNull(view.pageTopPxAt(0)),
            pendingPrecache.reflectedField("fromTop"),
        )
    }

    @Test
    fun `boundary turn without a commit owner discards both transferred page shots`() {
        val discarded = mutableListOf<BoundaryPagePreview>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE).apply {
            onBoundaryTurnDiscarded = discarded::add
        }
        view.goToLastPage()
        val previewBitmap = view.offerReadyBoundaryPreviewForTest(forward = true, token = 501L)

        try {
            assertTrue(view.startDiscreteBoundaryTurn(1))
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals("an unowned commit must become one discard", 1, discarded.size)
            assertTrue("the unowned target page-shot must be recycled", previewBitmap.isRecycled)
            assertNull("an unowned target must not remain as a continuity cover", view.privateField("conversionSnapshotDrawable"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `warmed SIMULATION boundary reuses warmed owner while SLIDE boundary captures one fresh outgoing Bitmap`() {
        EpubPageShotCaptureProbe.reset()
        try {
            for (style in listOf(PageFlipStyle.SIMULATION, PageFlipStyle.SLIDE)) {
                val view = pagedFlowView(flipStyle = style)
                view.goToLastPage()
                val background = RecordingBoundsTopDrawable()
                view.background = background
                view.preCachePageTexturesForTest()
                val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)
                val leasedBeforeTurn = budget.leasedBytes
                background.boundsTops.clear()
                val preview = view.offerReadyBoundaryPreviewForTest(forward = true, token = 51L)
                val warmedOutgoingBitmap = view.privateField("cachedFrontBitmap") as Bitmap?
                try {
                    val capturesBeforeTurn = EpubPageShotCaptureProbe.total()
                    assertTrue(view.startDiscreteBoundaryTurn(1))
                    if (style == PageFlipStyle.SIMULATION) {
                        val warmedOutgoing = checkNotNull(warmedOutgoingBitmap) {
                            "warmed SIMULATION boundary requires a cached current-page owner"
                        }
                        val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
                        assertTrue(
                            "a warmed SIMULATION boundary tap must transfer the existing current-page owner by identity",
                            paper.privateBitmap("frontBitmap") === warmedOutgoing,
                        )
                        assertTrue(
                            "the warmed SIMULATION discrete path must not call snapshotViewport or draw the live page again: " +
                                background.boundsTops,
                            background.boundsTops.isEmpty(),
                        )
                        assertTrue(
                            "taking the warmed outgoing owner must not add another page-shot lease: " +
                                "before=$leasedBeforeTurn after=${budget.leasedBytes}",
                            budget.leasedBytes <= leasedBeforeTurn,
                        )
                    } else {
                        // Warm SLIDE boundary stays Bitmap-based: the outgoing front is freshly
                        // captured exactly once because the boundary renderer and continuity
                        // reverseBitmap require Bitmap frames; the offered preview Bitmap identity is
                        // preserved and no warm Bitmap cache is re-added.
                        val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
                        assertNotNull(
                            "SLIDE boundary must capture a fresh outgoing Bitmap",
                            slide.privateBitmap("frontBitmap"),
                        )
                        assertTrue(
                            "SLIDE boundary renderer must retain the exact offered preview bitmap",
                            slide.privateBitmap("revealedBitmap") === preview,
                        )
                        assertEquals(
                            "warm SLIDE boundary may capture exactly one fresh outgoing viewport Bitmap",
                            capturesBeforeTurn + 1,
                            EpubPageShotCaptureProbe.total(),
                        )
                    }
                } finally {
                    view.dispose()
                }
            }
        } finally {
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `dirty SIMULATION front forces fresh outgoing shot for discrete boundary turn`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        try {
            view.goToLastPage()
            view.preCachePageTexturesForTest()
            val preview = view.newBoundaryPreviewForTest(forward = true, token = 511L)
            val previewBitmap = preview.bitmap
            assertTrue(view.offerBoundaryPreviewForTest(preview))
            val staleFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
            val staleMarker = 0xFF5B00FF.toInt()
            staleFront.eraseColor(staleMarker)
            view.replacePendingInPlacePageShotRefreshSlotsForTest("FRONT")
            assertEquals(
                "only FRONT should be dirty for this boundary entry",
                setOf("FRONT"),
                view.pendingInPlacePageShotRefreshSlotNamesForTest(),
            )

            assertTrue(view.startDiscreteBoundaryTurn(1))

            val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
            val outgoing = paper.privateBitmap("frontBitmap")
            val revealed = paper.privateBitmap("revealedBitmap")
            assertTrue(
                "dirty FRONT must not transfer the warmed outgoing owner",
                outgoing !== staleFront,
            )
            assertEquals(
                "fresh outgoing capture must not carry the stale FRONT marker",
                0,
                outgoing.countExactPixels(staleMarker),
            )
            assertTrue(
                "boundary renderer must retain the exact offered preview bitmap",
                revealed === previewBitmap,
            )
            assertFalse(
                "offered preview must remain live while the boundary renderer owns it",
                previewBitmap.isRecycled,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `page shot trim clears stable and inactive owners but preserves continuity cover`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
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
            val outgoing = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
                .privateBitmap("frontBitmap")

            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertTrue(
                "the continuity cover must receive the renderer-owned target without copying it",
                cover.privateBitmap("bitmap") === revealed,
            )
            assertFalse("the transferred target must remain alive", revealed.isRecycled)
            assertEquals("the boundary transaction must publish exactly once", 1, commits.size)
            val commit = commits.single() as BoundaryPagePreview
            assertSame(
                "the reverse preview must receive the renderer-owned outgoing shot without recapture",
                outgoing,
                commit.reverseBitmap,
            )
            assertFalse("the transferred outgoing shot must remain alive", outgoing.isRecycled)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `boundary continuity cover isolates input and replays one swipe after stable report`() {
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
            assertEquals("the accepted covered swipe must run only after stability", 1, view.currentPageIndex())
            assertEquals("the stable handoff must retire the continuity cover", false, view.privateBool("boundaryContinuityCover"))
        } finally {
            view.pendingDecodesProvider = { false }
            view.dispose()
        }
    }

    @Test
    fun `boundary continuity cover queues rapid edge taps until image layout stabilizes`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
        view.showConversionSnapshotForTest(markerBitmap(view.width, view.height))
        view.setPrivateField("boundaryContinuityCover", true)
        view.setPrivateField("awaitingReveal", true)
        view.setPrivateField("awaitingStableChapter", true)
        view.getChildAt(0).alpha = 0f
        view.pendingDecodesProvider = { true }

        fun tapAt(x: Float, eventTime: Long) {
            val y = view.height * 0.50f
            view.dispatchTouchEvent(motionEvent(eventTime, eventTime, MotionEvent.ACTION_DOWN, x, y))
            view.dispatchTouchEvent(motionEvent(eventTime, eventTime + 24L, MotionEvent.ACTION_UP, x, y))
        }

        try {
            val eventTime = SystemClock.uptimeMillis()
            repeat(3) { index -> tapAt(view.width * 0.85f, eventTime + index * 40L) }
            tapAt(view.width * 0.15f, eventTime + 120L)

            assertEquals("opposite covered taps must preserve their signed net intent", 2, view.privateInt("queuedPageTurnDelta"))
            assertNull(view.privateField("pendingInitialPageTurnDelta"))
            assertEquals(0, view.currentPageIndex())

            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

            assertEquals("the covered burst must drain after the image layout stabilizes", 2, view.currentPageIndex())
            assertEquals(0, view.privateInt("queuedPageTurnDelta"))
            assertFalse(view.privateBool("boundaryContinuityCover"))
        } finally {
            view.pendingDecodesProvider = { false }
            view.dispose()
        }
    }

    @Test
    fun `engine rejection releases a discrete boundary wait for another turn`() {
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
                "the view must retain accepted navigation until the preview producer resolves it",
                "BOUNDARY_DISCRETE_WAITING",
                view.privateField("interactiveTurnState").toString(),
            )
            assertEquals("waiting must not restart or duplicate the producer request", 1, previewRequests.size)

            view.rejectBoundaryPreview(forward = true, sourceChapterGeneration = previewRequests.single().second)

            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertTrue("the reader must accept another turn after engine rejection", view.startDiscreteBoundaryTurn(1))
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
    fun `missing boundary preview producer never leaves a turn waiting`() {
        val discrete = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        discrete.goToLastPage()
        try {
            assertFalse(discrete.startDiscreteBoundaryTurn(1))
            assertEquals("NONE", discrete.privateField("interactiveTurnState").toString())
        } finally {
            discrete.dispose()
        }

        listOf(PageFlipStyle.SLIDE, PageFlipStyle.NONE).forEach { style ->
            val view = pagedFlowView(flipStyle = style)
            view.goToLastPage()
            val y = view.height * 0.10f
            val downTime = SystemClock.uptimeMillis()
            try {
                view.dispatchTouchEvent(
                    motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, y),
                )
                view.dispatchTouchEvent(
                    motionEvent(downTime, downTime + 100L, MotionEvent.ACTION_MOVE, view.width * 0.50f, y),
                )
                view.dispatchTouchEvent(
                    motionEvent(downTime, downTime + 200L, MotionEvent.ACTION_UP, view.width * 0.50f, y),
                )

                assertEquals("style=$style", "NONE", view.privateField("interactiveTurnState").toString())
            } finally {
                view.dispose()
            }
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
        val originalFront = checkNotNull(view.slideFrontArtifactForTest())
        val originalTarget = checkNotNull(view.slideRevealedArtifactForTest())
        val originalFrontKey = checkNotNull(view.slideArtifactSlotKeyForTest("slideFrontSlot"))
        val originalTargetKey = checkNotNull(view.slideArtifactSlotKeyForTest("slideRevealedSlot"))

        view.measure(exactly(420), exactly(120))
        view.layout(0, 0, 420, 120)
        shadowOf(Looper.getMainLooper()).idle()
        view.preCachePageTexturesForTest()

        val resizedFront = checkNotNull(view.slideFrontArtifactForTest())
        val resizedTarget = checkNotNull(view.slideRevealedArtifactForTest())
        val resizedFrontKey = checkNotNull(view.slideArtifactSlotKeyForTest("slideFrontSlot"))
        val resizedTargetKey = checkNotNull(view.slideArtifactSlotKeyForTest("slideRevealedSlot"))
        assertTrue("width-only resize must discard the old front artifact", !originalFront.slideArtifactRecordIsLive())
        assertTrue("width-only resize must discard the old target artifact", !originalTarget.slideArtifactRecordIsLive())
        assertTrue("width-only resize must record a new front artifact owner", resizedFront !== originalFront)
        assertTrue("width-only resize must record a new target artifact owner", resizedTarget !== originalTarget)
        assertTrue("width-only resize must not preserve the old front key", resizedFrontKey != originalFrontKey)
        assertTrue("width-only resize must not preserve the old target key", resizedTargetKey != originalTargetKey)

        view.refreshHighlights(emptyList())

        assertTrue("highlight refresh must discard the current front artifact", !resizedFront.slideArtifactRecordIsLive())
        assertTrue("highlight refresh must discard the current target artifact", !resizedTarget.slideArtifactRecordIsLive())
        assertNull("highlight refresh must clear the current front key", view.slideArtifactSlotKeyForTest("slideFrontSlot"))
        assertNull("highlight refresh must clear the current target key", view.slideArtifactSlotKeyForTest("slideRevealedSlot"))
        view.preCachePageTexturesForTest()
        assertTrue(
            "highlight refresh must rebuild a new front artifact before the next local turn",
            checkNotNull(view.slideFrontArtifactForTest()) !== resizedFront,
        )
        assertTrue(
            "highlight refresh must rebuild a new target artifact before the next local turn",
            checkNotNull(view.slideRevealedArtifactForTest()) !== resizedTarget,
        )
    }

    @Test
    fun `refreshHighlights preserves plain BackgroundColorSpan while replacing annotation and search spans`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        val base = SpannableString("css annotation search rest")
        val cssSpan = BackgroundColorSpan(0x33FF0000)
        base.setSpan(cssSpan, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.textView.setText(base, android.widget.TextView.BufferType.SPANNABLE)

        view.refreshHighlights(
            annotationRanges = listOf(ReaderTextHighlightRange(4, 14, 0x66FFE082)),
            searchRanges = listOf(ReaderTextHighlightRange(15, 21, READER_SEARCH_HIGHLIGHT_COLOR)),
        )

        val text = view.textView.text as Spanned
        val plain = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
            .filter { it !is ReaderTextHighlightSpan && it !is ReaderSearchHighlightSpan }
        assertEquals(1, plain.size)
        assertEquals(0x33FF0000, plain.single().backgroundColor)
        assertEquals(0, text.getSpanStart(plain.single()))
        assertEquals(3, text.getSpanEnd(plain.single()))

        val annotations = text.getSpans(0, text.length, ReaderTextHighlightSpan::class.java)
        val searches = text.getSpans(0, text.length, ReaderSearchHighlightSpan::class.java)
        assertEquals(1, annotations.size)
        assertEquals(1, searches.size)
        assertEquals(0x66FFE082, annotations.single().backgroundColor)
        assertEquals(READER_SEARCH_HIGHLIGHT_COLOR, searches.single().backgroundColor)

        // Clear search only via empty searchRanges; annotation + CSS must remain.
        view.refreshHighlights(
            annotationRanges = listOf(ReaderTextHighlightRange(4, 14, 0x66FFE082)),
            searchRanges = emptyList(),
        )
        val cleared = view.textView.text as Spanned
        assertEquals(0, cleared.getSpans(0, cleared.length, ReaderSearchHighlightSpan::class.java).size)
        assertEquals(1, cleared.getSpans(0, cleared.length, ReaderTextHighlightSpan::class.java).size)
        val plainAfter = cleared.getSpans(0, cleared.length, BackgroundColorSpan::class.java)
            .filter { it !is ReaderTextHighlightSpan && it !is ReaderSearchHighlightSpan }
        assertEquals(1, plainAfter.size)
        assertEquals(0x33FF0000, plainAfter.single().backgroundColor)
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
        assertMotionPageShotSize(view, paper.privateBitmap("frontBitmap"))
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
    fun `page shot rendering bypasses TextView view level background`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val textBackground = RecordingBoundsTopDrawable()
        view.textView.background = textBackground

        try {
            view.goToPage(1)
            textBackground.boundsTops.clear()

            val shot = requireNotNull(view.snapshotPageAt(pageOneTop))
            shot.recycle()

            assertTrue(
                "page shots must render the TextView layout directly without traversing its View background",
                textBackground.boundsTops.isEmpty(),
            )
        } finally {
            view.dispose()
        }
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
    fun `motion page shots preserve viewport resolution like settled snapshots`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        // The fixture may warm three directional owners during layout. Release those owners so
        // this test measures the two explicit snapshot paths rather than slot admission.
        view.recycleCachedTexturesForTest()
        val motion = view.snapshotViewportForTest(
            kind = PageShotLeaseKind.EVICTABLE,
            label = "test.motion",
            fullResolution = false,
        )
        val settled = view.snapshotViewportForTest(
            kind = PageShotLeaseKind.EVICTABLE,
            label = "test.settled",
            fullResolution = true,
        )

        try {
            assertNotNull(motion)
            assertNotNull(settled)
            assertEquals(
                "motion snapshots must keep text and image detail at the viewport resolution",
                settled!!.width,
                motion!!.width,
            )
            assertEquals(settled.height, motion.height)
            assertEquals(view.width, settled.width)
            assertEquals(view.height, settled.height)
        } finally {
            listOfNotNull(motion, settled).forEach { bitmap ->
                val budget = view.privateField("pageShotBudget") as PageShotBudget
                budget.release(bitmap)
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            view.dispose()
        }
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `simulation text turn keeps motion content visible when motion starts`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = OnePixelDetailDrawable(stripeWidth = 8)
        view.textView.setTextColor(0x00000000)
        view.goToPage(1)
        view.recycleCachedTexturesForTest()
        view.preCachePageTexturesForTest()
        val staticFrame = view.drawAsScrolledChildToBitmapForTest()

        try {
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val motionStartFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val sampleY = view.height / 2
                val staticEdges = staticFrame.countHighContrastHorizontalTransitions(sampleY, 8, view.width - 8)
                val motionEdges = motionStartFrame.countHighContrastHorizontalTransitions(sampleY, 8, view.width - 8)
                assertTrue(
                    "a text page motion layer must retain visible content after bounded capture; " +
                        "staticEdges=$staticEdges motionEdges=$motionEdges " +
                        "distinct=${motionStartFrame.countDistinctPixels()} sampleY=$sampleY",
                    staticEdges > 0 && motionStartFrame.countDistinctPixels() > 1,
                )
            } finally {
                motionStartFrame.recycle()
            }
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            staticFrame.recycle()
            view.dispose()
        }
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
    fun `active page shot suppresses parked text draw for slide and simulation`() {
        for (style in listOf(PageFlipStyle.SLIDE, PageFlipStyle.SIMULATION)) {
            val view = pagedFlowView(flipStyle = style)
            assertTrue("style=$style pageCount=${view.pageCount()}", view.pageCount() > 2)
            val skipContentDrawStates = mutableListOf<Boolean>()
            val container = view.getChildAt(0)
            val childDraws = RecordingBoundsTopDrawable {
                skipContentDrawStates += container.reflectedField("skipContentDraw") as Boolean
            }
            view.textView.background = childDraws
            view.goToPage(1)

            view.drawToBitmapForTest().recycle()
            val drawsBeforeTurn = childDraws.boundsTops.size
            assertTrue("style=$style baseline draw must visit the parked TextView", drawsBeforeTurn > 0)

            try {
                assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
                // A cold direct start may capture the live outgoing/target pages before installing the
                // renderer. Start the overdraw window after that setup work has completed.
                val drawsAfterRendererStart = childDraws.boundsTops.size
                assertTrue(
                    "style=$style must hold live-content suppression between parent draws",
                    container.javaClass.getDeclaredField("skipContentDraw")
                        .apply { isAccessible = true }
                        .getBoolean(container),
                )
                skipContentDrawStates.clear()
                view.drawToBitmapForTest().recycle()
                assertEquals(
                    "style=$style must suppress live child traversal beneath its complete page-shot overlay",
                    drawsAfterRendererStart,
                    childDraws.boundsTops.size,
                )
                assertTrue(
                    "style=$style dispatchDraw must mark the live container suppressed; states=$skipContentDrawStates",
                    skipContentDrawStates.isEmpty(),
                )
                assertTrue(
                    "style=$style must keep live-content suppression after the draw pass",
                    container.javaClass.getDeclaredField("skipContentDraw")
                        .apply { isAccessible = true }
                        .getBoolean(container),
                )
            } finally {
                view.endInteractiveCurl(velocityX = 0f)
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
                assertFalse(
                    "style=$style overlay teardown must restore live-content traversal",
                    container.javaClass.getDeclaredField("skipContentDraw")
                        .apply { isAccessible = true }
                        .getBoolean(container),
                )
                view.dispose()
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `slide cancel settle keeps live content available under the overlay`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val container = view.privateField("container") as View
        fun liveContentSuppressed(): Boolean = container.reflectedField("skipContentDraw") as Boolean
        view.isVerticalScrollBarEnabled = false
        var outgoingFrame: Bitmap? = null

        try {
            view.goToPage(1)
            // The real input path defers a cold pair across two host frames. This test needs an
            // active overlay before ACTION_CANCEL so it can isolate the rollback underlay contract.
            view.preCachePageTexturesForTest()
            assertNotNull("fixture requires a warmed current-page slide artifact", view.slideFrontArtifactForTest())
            assertNotNull("fixture requires a warmed target-page slide artifact", view.slideRevealedArtifactForTest())
            outgoingFrame = view.drawAsScrolledChildToBitmapForTest()
            val downX = view.width * 0.85f
            val moveX = view.width * 0.15f
            val y = view.height * 0.50f
            val downTime = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y))
            val renderer = view.requireActiveSlideRendererView()
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertTrue("fixture must cross the normal release commit threshold", slide.progress > 0.5f)
            assertTrue("the active drag must suppress the parked live page", liveContentSuppressed())

            view.onTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y),
            )
            assertEquals("SOFTWARE_SETTLING", view.privateField("interactiveTurnState").toString())
            assertFalse("cancel settle must expose the parked live page as an underlay", liveContentSuppressed())

            val animator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
            assertTrue("cancel settle must remain animated", animator.isRunning)
            // Model the Huawei frame where the ViewOverlay loses its RenderNode for one traversal.
            renderer.visibility = View.INVISIBLE
            animator.currentPlayTime = animator.duration / 2L
            assertFalse(
                "settle update frames must not re-enable live-content suppression",
                liveContentSuppressed(),
            )
            val fallbackFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                assertTrue(
                    "a missing settle overlay frame must reveal the restored outgoing page, not paper/white",
                    bitmapsHaveSamePixels(checkNotNull(outgoingFrame), fallbackFrame),
                )
            } finally {
                fallbackFrame.recycle()
            }
        } finally {
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.let { animator ->
                if (animator.isRunning) animator.end()
            }
            outgoingFrame?.recycle()
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `slide teardown rerecords the parked image page after suppressing live draw`() {
        val fixture = visibleHeadingImageContinuationFixture(minHeadingPageIndex = 1)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        val liveImageColor = 0xFF16A34A.toInt()

        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "fixture must start from a non-zero page so overlay bounds exercise content coordinates",
                view.scrollY > 0,
            )
            // Keep the software Canvas lane on the cold Bitmap overlay. Warm API 29+ artifacts
            // are RenderNode-backed and are validated by the hardware/device lane instead of
            // asking a software Canvas to execute Canvas.drawRenderNode.
            view.recycleCachedTexturesForTest()
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            assertNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertNotNull(view.privateField("slideOverlayView") as PageSlideOverlayView?)
            fixture.imageDrawable.color = liveImageColor
            val content = view.getChildAt(0)
            val contentShadow = shadowOf(content)
            val textShadow = shadowOf(view.textView)
            contentShadow.clearWasInvalidated()
            textShadow.clearWasInvalidated()

            view.clearFlipOverlayForTest()

            assertNull(view.privateField("slideDrawable"))
            assertNull("teardown must clear the active slide overlay pointer", view.privateField("slideOverlayView"))
            assertTrue(
                "teardown must detach every dedicated slide renderer View",
                view.activeSlideRendererViews().isEmpty(),
            )
            assertTrue(
                "removing an opaque SLIDE View must rerecord the container that skipped its child",
                contentShadow.wasInvalidated(),
            )
            assertTrue(
                "removing an opaque SLIDE View must defer the expensive TextView RenderNode re-record",
                !textShadow.wasInvalidated(),
            )
            val firstSettledFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                assertTrue(
                    "the first settled SLIDE frame must retain page content while the child re-record is deferred",
                    firstSettledFrame.countDistinctPixels() > 1,
                )
            } finally {
                firstSettledFrame.recycle()
            }
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            assertTrue(
                "deferred SLIDE teardown must eventually dirty the TextView RenderNode",
                textShadow.wasInvalidated(),
            )
            val settledFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val liveImageHits = settledFrame.countExactPixels(liveImageColor)
                assertTrue(
                    "SLIDE teardown must restore the live boundary image pixels; hits=$liveImageHits",
                    liveImageHits > 0,
                )
            } finally {
                settledFrame.recycle()
            }
        } finally {
            if (view.privateField("slideDrawable") != null) view.clearFlipOverlayForTest()
            view.dispose()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `active simulation page shot overlay covers live synthetic boundary image until cleared`() {
        val fixture = visibleHeadingImageContinuationFixture()
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SIMULATION
        val baselineImageColor = fixture.imageDrawable.color
        val liveSyntheticColor = 0xFF16A34A.toInt()
        val overlayColor = 0xFF3B82F6.toInt()

        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            val baselineFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val baselineHits = baselineFrame.countExactPixels(baselineImageColor)
                assertTrue(
                    "fixture must visibly paint the heading-page continuation before overlay; " +
                        "hits=$baselineHits",
                    baselineHits > 0,
                )
            } finally {
                baselineFrame.recycle()
            }

            val front = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(overlayColor)
            }
            val revealed = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF1E3A8A.toInt())
            }
            val curl = PageCurlDrawable(
                front,
                revealed,
                view.width,
                view.height,
                true,
                view.resources.displayMetrics.density,
            ).apply {
                progress = 0f
                setBounds(0, view.scrollY, view.width, view.scrollY + view.height)
            }
            view.overlay.add(curl)
            view.setPrivateField("curlDrawable", curl)
            // Mirror the production install sequence (overlay.add -> curlDrawable ->
            // applyFlipProgress) so the transaction latch arms exactly as a real SIMULATION turn.
            view.applyFlipProgressForTest(0f, true)

            fixture.imageDrawable.color = liveSyntheticColor
            view.textView.invalidate()
            view.invalidate()
            val activeFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val activeLiveHits = activeFrame.countExactPixels(liveSyntheticColor)
                assertEquals(
                    "live synthetic boundary pixels must not paint above the active page-shot overlay",
                    0,
                    activeLiveHits,
                )
            } finally {
                activeFrame.recycle()
            }

            val containerShadow = shadowOf(view)
            val textShadow = shadowOf(view.textView)
            // Model the overlay-owned traversal consuming the earlier dirty signals.
            containerShadow.clearWasInvalidated()
            textShadow.clearWasInvalidated()
            view.clearFlipOverlayForTest()
            assertNull(view.privateField("curlDrawable"))
            assertTrue(
                "overlay teardown must invalidate the container so the live page replaces the page shot",
                containerShadow.wasInvalidated(),
            )
            assertTrue(
                "overlay teardown must defer the expensive text child re-record",
                !textShadow.wasInvalidated(),
            )
            val settledCover = checkNotNull(view.privateField("settledPageCover")) {
                "PAPER teardown must retain a terminal cover until the parked child is rerecorded"
            }
            val settledCurl = checkNotNull(settledCover.reflectedField("curlDrawable")) as PageCurlDrawable
            val firstSettledFrame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                // Robolectric's manual View.draw() path does not composite ViewOverlay children;
                // draw the production terminal Drawable directly to verify its actual page content.
                settledCurl.draw(Canvas(firstSettledFrame))
                val terminalCoverHits = firstSettledFrame.countExactPixels(0xFF1E3A8A.toInt())
                assertTrue(
                    "the terminal PAPER cover must retain the revealed page while the child re-record is deferred; " +
                        "hits=$terminalCoverHits",
                    terminalCoverHits > 0,
                )
            } finally {
                firstSettledFrame.recycle()
            }
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            assertTrue(
                "deferred overlay teardown must eventually invalidate the text child",
                textShadow.wasInvalidated(),
            )

            val settledFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val settledLiveHits = settledFrame.countExactPixels(liveSyntheticColor)
                assertTrue(
                    "cleared page-shot overlay must restore the live synthetic boundary preview; hits=$settledLiveHits",
                    settledLiveHits > 0,
                )
            } finally {
                settledFrame.recycle()
            }
        } finally {
            if (view.privateField("curlDrawable") != null) view.clearFlipOverlayForTest()
            view.dispose()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `deferred live text rerecord is discarded when its chapter generation becomes stale`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            view.goToPage(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val textShadow = shadowOf(view.textView)
            textShadow.clearWasInvalidated()
            view.clearFlipOverlayForTest()
            val generation = view.privateField("chapterGeneration") as Long
            view.setPrivateField("chapterGeneration", generation + 1L)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            assertFalse(
                "a stale teardown callback must not re-record a later chapter",
                textShadow.wasInvalidated(),
            )
            assertNull("stale callback must release its terminal cover", view.privateField("settledPageCover"))
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `repeated heading image round trips keep the canonical live page visible`() {
        val fixture = visibleHeadingImageContinuationFixture()
        val view = fixture.view
        view.flipStyle = PageFlipStyle.NONE
        val pages = view.privateField("paged") as List<EpubFlowPage>

        try {
            view.goToPage(fixture.headingPageIndex)
            val baseline = view.drawAsScrolledChildToBitmapForTest()
            try {
                repeat(6) { round ->
                    assertTrue("round=$round forward turn must reach the image page", view.goToAdjacentPage(1))
                    assertEquals(fixture.imagePageIndex, view.currentPageIndex())
                    assertEquals(
                        "round=$round forward turn must park the canonical image window",
                        pages[fixture.imagePageIndex],
                        view.privateField("activePageWindow"),
                    )

                    assertTrue("round=$round backward turn must return to the heading page", view.goToAdjacentPage(-1))
                    assertEquals(fixture.headingPageIndex, view.currentPageIndex())
                    assertEquals(
                        "round=$round backward turn must park the canonical heading window",
                        pages[fixture.headingPageIndex],
                        view.privateField("activePageWindow"),
                    )
                }

                val settled = view.drawAsScrolledChildToBitmapForTest()
                try {
                    assertTrue(
                        "repeated canonical round trips must preserve the same live heading text and image preview",
                        bitmapsHaveSamePixels(baseline, settled),
                    )
                } finally {
                    settled.recycle()
                }
            } finally {
                baseline.recycle()
            }
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `conversion snapshot suppresses live synthetic boundary image until cleared`() {
        val fixture = visibleHeadingImageContinuationFixture()
        val view = fixture.view
        val changedImageColor = 0xFF0891B2.toInt()
        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            // Isolate the synthetic preview drawn after super.draw(): the live TextView uses the
            // same ImageSpan color and must not satisfy either side of this gate regression.
            view.getChildAt(0).visibility = View.INVISIBLE
            fixture.imageDrawable.color = changedImageColor
            view.textView.invalidate()
            view.invalidate()
            val baselineFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val baselineHits = baselineFrame.countExactPixels(changedImageColor)
                assertTrue(
                    "fixture must visibly paint the changed synthetic heading-page continuation before cover; " +
                        "hits=$baselineHits",
                    baselineHits > 0,
                )
            } finally {
                baselineFrame.recycle()
            }

            val coverColor = 0xFFF5E9D7.toInt()
            val solidCover = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(coverColor)
            }
            view.showConversionSnapshotForTest(solidCover)
            val installedCover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertTrue(installedCover.privateBitmap("bitmap") === solidCover)
            assertEquals(255, installedCover.privateInt("alphaValue"))
            assertEquals(
                Rect(0, view.scrollY, view.width, view.scrollY + view.height),
                (installedCover as Drawable).bounds,
            )

            val coveredFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                assertEquals(
                    "the installed conversion snapshot must suppress synthetic continuation pixels appended after ViewOverlay",
                    0,
                    coveredFrame.countExactPixels(changedImageColor),
                )
            } finally {
                coveredFrame.recycle()
            }

            view.clearConversionSnapshotForTest()
            assertNull(view.privateField("conversionSnapshotDrawable"))

            val uncoveredFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val changedHits = uncoveredFrame.countExactPixels(changedImageColor)
                assertTrue(
                    "clearing the conversion snapshot must restore synthetic continuation pixels; hits=$changedHits",
                    changedHits > 0,
                )
            } finally {
                uncoveredFrame.recycle()
            }
        } finally {
            view.dispose()
        }
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
        val revealedSourceX = expectedSourceX * revealedBitmap.width / view.width
        assertEquals(
            "a viewport-resolution revealed shot must preserve the exact captured paper pixel",
            staticFrame.getPixel(expectedSourceX, sampleY),
            revealedBitmap.getPixel(revealedSourceX, sampleY * revealedBitmap.height / view.height),
        )
        assertEquals(
            "the incoming half-page should carry its captured paper texture, not reveal the static host background",
            expectedSourceX,
            slide.incomingSourceXForViewportX(sampleX),
        )

        staticFrame.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `held outgoing page shot keeps its viewport band while slide and simulation reveal the target`() {
        val frontColor = 0xFF16A34A.toInt()
        val targetColor = 0xFF2563EB.toInt()

        listOf(PageFlipStyle.SLIDE, PageFlipStyle.SIMULATION).forEach { style ->
            val view = pagedFlowView(flipStyle = style)
            try {
                assertTrue("$style fixture needs adjacent pages", view.pageCount() > 2)
                view.goToPage(1)
                shadowOf(Looper.getMainLooper()).idle()

                assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
                val drawable = when (style) {
                    PageFlipStyle.SLIDE -> checkNotNull(view.privateField("slideDrawable"))
                    PageFlipStyle.SIMULATION -> checkNotNull(view.privateField("curlDrawable"))
                    else -> error("unsupported test style $style")
                }

                view.updateInteractiveCurl(x = view.width / 2f)
                val progress = when (drawable) {
                    is PageSlideDrawable -> drawable.progress
                    is PageCurlDrawable -> drawable.progress
                    else -> error("unexpected turn drawable ${drawable.javaClass.name}")
                }
                assertEquals("$style must remain held at half progress", 0.5f, progress, 0.001f)
                assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())

                if (style == PageFlipStyle.SIMULATION) {
                    // Pixel-band verification only applies to the Bitmap paper renderer; the warm
                    // SLIDE strip replays frozen command artifacts and is not software-drawn here.
                    drawable.privateBitmap("frontBitmap").eraseColor(frontColor)
                    drawable.privateBitmap("revealedBitmap").eraseColor(targetColor)
                    val heldFrame = view.drawAsScrolledChildToBitmapForTest()
                    try {
                        assertEquals(
                            "SIMULATION must keep the outgoing page shot visible before release",
                            frontColor,
                            heldFrame.getPixel(view.width / 10, view.height / 2),
                        )
                        assertEquals(
                            "SIMULATION must reveal only the target half before release",
                            targetColor,
                            heldFrame.getPixel(view.width * 3 / 4, view.height / 2),
                        )
                    } finally {
                        heldFrame.recycle()
                    }
                } else {
                    assertNotNull(
                        "SLIDE held strip must own a current-page artifact frame",
                        (drawable as PageSlideDrawable).frontArtifactForTest(),
                    )
                    assertNotNull(
                        "SLIDE held strip must own a target-page artifact frame",
                        drawable.revealedArtifactForTest(),
                    )
                }
            } finally {
                if (view.privateField("interactiveTurnState").toString() != "NONE") {
                    view.endInteractiveCurl(velocityX = 0f)
                    (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.let { animator ->
                        if (animator.isRunning) animator.end()
                    }
                    shadowOf(Looper.getMainLooper()).idle()
                }
                view.dispose()
            }
        }
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
    fun `selectable child cannot hide the first paged threshold move from its flow owner`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val downX = view.width * 0.85f
        val moveX = view.width * 0.55f
        val y = view.height * 0.50f
        val downTime = SystemClock.uptimeMillis()
        view.textView.setOnTouchListener { child, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                child.parent.requestDisallowInterceptTouchEvent(true)
            }
            false
        }

        try {
            assertTrue(
                view.dispatchTouchEvent(
                    motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y),
                ),
            )
            // Robolectric does not consistently expose the selectable TextView's platform press
            // feedback after DOWN. Preserve the real-device state explicitly so this regression
            // verifies the ownership handoff rather than the shadow framework's focus behavior.
            view.textView.isPressed = true
            assertTrue("fixture needs selectable child focus before the parent steals", view.textView.requestFocus())
            assertTrue(
                view.dispatchTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )

            assertEquals(true, view.privateField("classified"))
            assertEquals(true, view.privateField("stealing"))
            assertFalse("page-turn ownership must retire child press feedback", view.textView.isPressed)
            assertFalse("page-turn ownership must retire child touch focus", view.textView.hasFocus())
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?) {
                "the threshold MOVE routed through the selectable child must start the slide"
            }
            assertEquals(
                "the routed threshold frame must include the full displacement since DOWN",
                (downX - moveX) / view.width.toFloat(),
                slide.progress,
                0.001f,
            )
        } finally {
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y),
            )
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `intercepted threshold MOVE is not applied twice when the same event reaches onTouchEvent`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val downX = view.width * 0.90f
        val firstMoveX = view.width * 0.70f
        val secondMoveX = view.width * 0.45f
        val y = view.height * 0.50f
        val downTime = SystemClock.uptimeMillis()
        val firstMove = motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, firstMoveX, y)
        val secondMove = motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_MOVE, secondMoveX, y)

        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        assertTrue(
            "threshold-crossing MOVE must be stolen in onInterceptTouchEvent",
            view.onInterceptTouchEvent(firstMove),
        )
        val slideAfterIntercept = checkNotNull(
            view.privateField("slideDrawable") as PageSlideDrawable?,
        ) { "intercept apply must start interactive slide once" }
        val frontAfterIntercept = slideAfterIntercept.frontArtifactForTest()
        val revealedAfterIntercept = slideAfterIntercept.revealedArtifactForTest()
        assertNotNull("the intercepted warm turn must own a current-page artifact", frontAfterIntercept)
        assertNotNull("the intercepted warm turn must own a target-page artifact", revealedAfterIntercept)
        val progressAfterIntercept = slideAfterIntercept.progress
        val expectedFirst = (downX - firstMoveX) / view.width.toFloat()
        assertEquals(
            "first intercepted MOVE progress must use full DOWN displacement once",
            expectedFirst,
            progressAfterIntercept,
            0.001f,
        )

        // Same MOVE may still be delivered to onTouchEvent after intercept returns true.
        assertTrue(view.onTouchEvent(firstMove))
        val slideAfterDuplicate = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        assertEquals(
            "duplicate onTouch MOVE must not re-apply the same threshold event",
            progressAfterIntercept,
            slideAfterDuplicate.progress,
            0.0001f,
        )
        assertTrue(
            "artifacts must not be re-recorded when the intercepted MOVE is redelivered",
            slideAfterDuplicate.frontArtifactForTest() === frontAfterIntercept,
        )
        assertTrue(
            "revealed artifact must stay the same owner across the suppressed redelivery",
            slideAfterDuplicate.revealedArtifactForTest() === revealedAfterIntercept,
        )

        assertTrue(view.onTouchEvent(secondMove))
        val slideAfterSecond = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        val expectedSecond = (downX - secondMoveX) / view.width.toFloat()
        assertEquals(
            "later MOVE must still advance progress monotonically after suppress is consumed",
            expectedSecond,
            slideAfterSecond.progress,
            0.001f,
        )
        assertTrue(
            "gesture progress must be monotonic after the first apply",
            slideAfterSecond.progress > progressAfterIntercept + 0.01f,
        )
        assertTrue(
            "subsequent MOVE must not re-record artifacts once the turn is live",
            slideAfterSecond.frontArtifactForTest() === frontAfterIntercept &&
                slideAfterSecond.revealedArtifactForTest() === revealedAfterIntercept,
        )

        view.onTouchEvent(motionEvent(downTime, downTime + 72L, MotionEvent.ACTION_CANCEL, secondMoveX, y))
        // CANCEL starts a non-commit settle; drain the retreat animator so the overlay clears.
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        assertNull(
            "CANCEL must still tear down the interactive turn after settle",
            view.privateField("slideDrawable"),
        )
        assertEquals(
            "CANCEL settle must leave interactive turn state NONE",
            "NONE",
            view.privateField("interactiveTurnState").toString(),
        )
    }

    @Test
    fun `warmed interactive turn takes cached page shots without recapturing them on first move`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.preCachePageTexturesForTest()
        val cachedFront = checkNotNull(view.slideFrontArtifactForTest())
        val cachedRevealed = checkNotNull(view.slideRevealedArtifactForTest())

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))

        try {
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertTrue(
                "the crossing MOVE must transfer the warmed current-page artifact instead of allocating a replacement",
                slide.frontArtifactForTest() === cachedFront,
            )
            assertTrue(
                "the crossing MOVE must transfer the warmed next-page artifact instead of drawing the view again",
                slide.revealedArtifactForTest() === cachedRevealed,
            )
            assertNull("the active drawable now owns the front artifact", view.slideFrontArtifactForTest())
            assertNull("the active drawable now owns the revealed artifact", view.slideRevealedArtifactForTest())
        } finally {
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `one shot soft budget still admits an interactive working pair`() {
        val oneShotBytes = 360L * 120L * 2L
        val budget = PageShotBudget(oneShotBytes)
        var pinnedAdmissionRequests = 0
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            pageShotBudget = budget,
            onPinnedPageShotAdmissionNeeded = { pinnedAdmissionRequests += 1 },
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        view.recycleCachedTexturesForTest()

        try {
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val front = slide.privateBitmap("frontBitmap")
            val revealed = slide.privateBitmap("revealedBitmap")
            assertTrue("the active working frames must have distinct owners", front !== revealed)
            assertFalse("the outgoing working frame must remain live", front.isRecycled)
            assertFalse("the target working frame must remain live", revealed.isRecycled)
            assertMotionPageShotSize(view, front)
            assertMotionPageShotSize(view, revealed)
            assertEquals(Bitmap.Config.RGB_565, front.config)
            assertEquals(Bitmap.Config.RGB_565, revealed.config)
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
    fun `full size argb pinned admission cannot exceed one active working pair`() {
        val oneShotBytes = 360L * 120L * 4L
        val budget = PageShotBudget(oneShotBytes)
        val view = pagedFlowView(pageShotBudget = budget)
        view.recycleCachedTexturesForTest()

        val first = view.snapshotViewportForTest(
            kind = PageShotLeaseKind.PINNED,
            label = "test.active.front",
            fullResolution = true,
        )
        val second = view.snapshotViewportForTest(
            kind = PageShotLeaseKind.PINNED,
            label = "test.active.target",
            fullResolution = true,
        )
        val third = view.snapshotViewportForTest(
            kind = PageShotLeaseKind.PINNED,
            label = "test.continuity.extra",
            fullResolution = true,
        )

        try {
            assertNotNull(first)
            assertNotNull(second)
            assertEquals(Bitmap.Config.ARGB_8888, first!!.config)
            assertEquals(Bitmap.Config.ARGB_8888, second!!.config)
            assertNull("a third full-size ARGB pinned frame must not bypass the device budget", third)
            assertEquals(oneShotBytes * 2L, budget.leasedBytes)
        } finally {
            listOfNotNull(first, second, third).forEach { bitmap ->
                budget.release(bitmap)
                if (!bitmap.isRecycled) bitmap.recycle()
            }
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
    fun `retained reverse boundary preview is pinned against speculative eviction`() {
        val oneShotBytes = 360L * 120L * 4L
        val budget = PageShotBudget(oneShotBytes)
        val view = pagedFlowView(pageShotBudget = budget)
        view.recycleCachedTexturesForTest()
        val bitmap = requireNotNull(view.snapshotPageAt(view.scrollY))
        val preview = BoundaryPagePreview(
            token = 54L,
            forward = false,
            sourceChapterGeneration = view.boundaryPreviewGenerationToken(),
            bitmap = bitmap,
        )

        try {
            assertTrue(view.offerRetainedBoundaryPreview(preview))
            assertTrue(
                "the retained reverse frame must be relabelled PINNED before any trim can run",
                budget.evictEvictable().isEmpty(),
            )
            assertFalse(bitmap.isRecycled)
            assertTrue(view.privateField("backwardBoundaryPreview") === preview)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `retained reverse boundary preview yields to a local working pair under one shot budget`() {
        val oneShotBytes = 360L * 120L * 2L
        val budget = PageShotBudget(oneShotBytes)
        val evicted = mutableListOf<Long>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            pageShotBudget = budget,
        ).apply {
            onBoundaryPreviewEvicted = { evicted += it.token }
        }
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        view.recycleCachedTexturesForTest()
        val reverse = requireNotNull(
            view.snapshotViewportForTest(
                kind = PageShotLeaseKind.EVICTABLE,
                label = "test.retained.reverse",
                fullResolution = false,
            ),
        )
        val preview = BoundaryPagePreview(
            token = 55L,
            forward = false,
            sourceChapterGeneration = view.boundaryPreviewGenerationToken(),
            bitmap = reverse,
        )

        try {
            assertTrue(view.offerRetainedBoundaryPreview(preview))

            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))

            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertFalse(slide.privateBitmap("frontBitmap").isRecycled)
            assertFalse(slide.privateBitmap("revealedBitmap").isRecycled)
            assertNull("the optional reverse owner must release the active pair's slot", view.privateField("backwardBoundaryPreview"))
            assertTrue(reverse.isRecycled)
            assertEquals(listOf(55L), evicted)
            assertEquals(oneShotBytes * 2L, budget.leasedBytes)
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
            val waitingContainer = view.privateField("container") as View
            assertEquals(
                "cold horizontal turn must provide immediate live-page feedback before pinned shots are ready",
                moveX - downX,
                waitingContainer.translationX,
                0.001f,
            )
            assertEquals(0f, waitingContainer.translationY, 0.001f)
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
            assertEquals(0f, waitingContainer.translationX, 0.001f)
            assertEquals(0f, waitingContainer.translationY, 0.001f)
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
        val cachedFront = checkNotNull(view.slideFrontArtifactForTest()) {
            "FREE_REST precache must warm the arbitrary outgoing viewport artifact"
        }
        val cachedRevealed = checkNotNull(view.slideRevealedArtifactForTest()) {
            "FREE_REST precache must warm its dynamic forward target artifact"
        }
        background.boundsTops.clear()

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))

        try {
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val frontTransferred = slide.frontArtifactForTest() === cachedFront
            val targetTransferred = slide.revealedArtifactForTest() === cachedRevealed
            val liveDrawBounds = background.boundsTops.toList()
            assertTrue(
                "the first interactive frame must transfer both warmed FREE_REST artifacts by identity " +
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
    fun `up after first cold target frame retains handoff and commits when late front arrives`() {
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
            assertNull("the target-only frame must not install curl", view.privateField("curlDrawable"))
            assertNull("the target-only frame must not install slide", view.privateField("slideDrawable"))

            view.onTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, y),
            )
            assertEquals(
                "a release past the commit threshold must retain the cold handoff until its front shot arrives",
                "LOCAL_SHOTS_WAITING",
                view.privateField("interactiveTurnState").toString(),
            )
            assertEquals(startPage, view.currentPageIndex())
            assertEquals(startTop, view.scrollY)
            assertEquals(startOffset, view.topLayoutOffset())
            assertTrue("the retained handoff must remain locator-silent", reportedOffsets.isEmpty())
            assertFalse("the prepared target must stay live while the front shot is pending", partialTarget.isRecycled)

            shadowOf(Looper.getMainLooper()).runOneTask()
            assertEquals("the late front callback must complete the two-shot working pair", 2, background.targetBitmaps.size)
            val lateFront = background.targetBitmaps.last()
            val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
            assertNull("SIMULATION must not degrade to slide", view.privateField("slideDrawable"))
            assertTrue("the resumed renderer must retain the prepared target identity", paper.privateBitmap("revealedBitmap") === partialTarget)
            assertTrue("the resumed renderer must own the late outgoing identity", paper.privateBitmap("frontBitmap") === lateFront)
            assertEquals("SOFTWARE_SETTLING", view.privateField("interactiveTurnState").toString())
            assertEquals("the retained release must park exactly the adjacent page", startPage + 1, view.currentPageIndex())
            assertTrue("the parked target must stay locator-silent until settle", reportedOffsets.isEmpty())

            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertNull(view.privateField("curlDrawable"))
            assertNull(view.privateField("slideDrawable"))
            assertEquals("the retained release must commit only one local page", startPage + 1, view.currentPageIndex())
            assertTrue("the committed viewport must leave its outgoing top", view.scrollY != startTop)
            assertTrue("the committed locator must leave its outgoing offset", view.topLayoutOffset() != startOffset)
            assertEquals("the committed target must publish exactly once", listOf(view.topLayoutOffset()), reportedOffsets)
            assertTrue(
                "settle must rekey the prepared target as current without recycling it",
                view.privateField("cachedFrontBitmap") === partialTarget && !partialTarget.isRecycled,
            )
            assertTrue(
                "settle must retain the outgoing frame as the backward neighbor",
                view.privateField("cachedBackwardBitmap") === lateFront && !lateFront.isRecycled,
            )

            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

            assertEquals("late settle and cache work must not submit another page", startPage + 1, view.currentPageIndex())
            assertEquals("late settle and cache work must not report again", 1, reportedOffsets.size)
            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
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
                    view.updateInteractiveCurl(x = view.width * if (commit) 0.25f else 0.99f)
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
    @Config(qualifiers = "xxhdpi")
    fun `fast five dp cold vertical boundary swipe keeps the live page still until preview then commits once`() {
        val commits = mutableListOf<Any>()
        val previewRequests = mutableListOf<Pair<Boolean, Long>>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        ).apply {
            installBoundaryCommitRecorderForTest(commits)
            onBoundaryPreviewNeeded = { forward, generation -> previewRequests += forward to generation }
        }
        view.goToLastPage()

        try {
            val downTime = SystemClock.uptimeMillis()
            val x = view.width * 0.85f
            val startY = view.height * 0.85f
            val travelPx = 5f * view.resources.displayMetrics.density
            val halfwayY = startY - travelPx / 2f
            val releaseY = startY - travelPx
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY))
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, x, halfwayY),
            )
            val currentPage = view.getChildAt(0)
            assertEquals(
                "a cold boundary wait must keep the live page still while the finger is down",
                0f,
                currentPage.translationY,
                0.01f,
            )
            assertEquals(0f, currentPage.translationX, 0.01f)
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, x, releaseY),
            )

            assertEquals(
                "an accepted cold boundary swipe must wait for its requested target",
                "BOUNDARY_DISCRETE_WAITING",
                view.privateField("interactiveTurnState").toString(),
            )
            assertEquals(listOf(true to view.boundaryPreviewGenerationToken()), previewRequests)
            val immediateTranslationY = currentPage.translationY
            assertEquals(
                "a cold boundary wait must not move the live page before the target preview exists",
                0f,
                immediateTranslationY,
                0.01f,
            )
            assertEquals(0f, currentPage.translationX, 0.01f)

            val preview = view.newBoundaryPreviewForTest(forward = true, token = 58L)
            assertTrue(view.offerBoundaryPreviewForTest(preview))
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals("the accepted cold swipe must publish exactly one boundary commit", 1, commits.size)
            assertEquals("settled feedback must restore the outgoing page translation", 0f, currentPage.translationY, 0.01f)
            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)
            assertEquals("late settle work must not publish the same swipe twice", 1, commits.size)
        } finally {
            view.dispose()
        }
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `fast five dp cold horizontal boundary swipe keeps the live page still until preview then commits once`() {
        val commits = mutableListOf<Any>()
        val previewRequests = mutableListOf<Pair<Boolean, Long>>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        ).apply {
            installBoundaryCommitRecorderForTest(commits)
            onBoundaryPreviewNeeded = { forward, generation -> previewRequests += forward to generation }
        }
        view.goToLastPage()

        val downTime = SystemClock.uptimeMillis()
        val startX = view.width * 0.85f
        val endX = startX - 5f * view.resources.displayMetrics.density
        val y = view.height * 0.50f
        val currentPage = view.getChildAt(0)

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, y))
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, (startX + endX) / 2f, y),
            )
            assertEquals(
                "a cold boundary wait must keep the live page still while the finger is down",
                0f,
                currentPage.translationX,
                0.01f,
            )
            assertEquals(0f, currentPage.translationY, 0.01f)
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, endX, y),
            )

            assertEquals("BOUNDARY_DISCRETE_WAITING", view.privateField("interactiveTurnState").toString())
            assertEquals(listOf(true to view.boundaryPreviewGenerationToken()), previewRequests)
            assertEquals(0f, currentPage.translationX, 0.01f)
            assertEquals(0f, currentPage.translationY, 0.01f)

            val preview = view.newBoundaryPreviewForTest(forward = true, token = 58L)
            assertTrue(view.offerBoundaryPreviewForTest(preview))
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals("the accepted cold horizontal boundary swipe must commit once", 1, commits.size)
            assertEquals(0f, currentPage.translationX, 0.01f)
            assertEquals(0f, currentPage.translationY, 0.01f)
        } finally {
            view.dispose()
        }
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `opposite page turn swipe begun under chapter cover never inherits the previous stream`() {
        lateinit var view: EpubFlowView
        val commits = mutableListOf<Boolean>()
        val trace = mutableListOf<String>()
        val violations = mutableListOf<String>()
        val incomingText = (1..160).joinToString("\n") { "Incoming chapter line $it stays paged." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )
        view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        )
        val outgoingFlow = view.privateField("flow") as EpubChapterFlow
        val outgoingText = view.textView.text

        fun record(label: String, expectedScrollY: Int? = null) {
            val state = view.privateField("interactiveTurnState").toString()
            val free = view.privateBool("freeScrolling")
            val clip = view.privateBool("pageClipActive")
            val motion = view.privateEnumName("pagedMotionState")
            val classified = view.privateBool("classified")
            val stealing = view.privateBool("stealing")
            val snapshot = "$label state=$state free=$free clip=$clip motion=$motion " +
                "scrollY=${view.scrollY} page=${view.currentPageIndex()} commits=$commits " +
                "classified=$classified stealing=$stealing"
            trace += snapshot
            if (free || !clip || motion == "DRAGGING_FREE" || motion == "FLING_FREE" || motion == "FREE_REST") {
                violations += snapshot
            }
            if (expectedScrollY != null && view.scrollY != expectedScrollY) {
                violations += "$snapshot expectedScrollY=$expectedScrollY"
            }
        }

        fun layoutCurrentChapter() {
            repeat(2) {
                view.measure(exactly(1080), exactly(600))
                view.layout(0, 0, 1080, 600)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }

        view.pendingDecodesProvider = { true }
        view.onBoundaryTurnCommitted = { preview ->
            commits += preview.forward
            if (preview.forward) {
                view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            } else {
                view.setChapter(outgoingFlow, outgoingText, pageHeightPx = view.height, landOnLast = true)
            }
        }
        view.goToLastPage()
        val firstOriginScrollY = view.scrollY
        val x = view.width * 0.85f
        val firstStartY = view.height * 0.85f
        val acceptedTurnDistance = 24f * view.resources.displayMetrics.density
        val firstEndY = firstStartY - acceptedTurnDistance
        val firstDownTime = SystemClock.uptimeMillis()

        try {
            assertEquals(
                EpubPagedTouchZone.PageTurn,
                EpubPagedTouchZones.classify(view.width, view.height, x, firstStartY),
            )
            view.offerReadyBoundaryPreviewForTest(forward = true, token = 59L)
            view.dispatchTouchEvent(motionEvent(firstDownTime, firstDownTime, MotionEvent.ACTION_DOWN, x, firstStartY))
            record("forward-down", firstOriginScrollY)
            view.dispatchTouchEvent(
                motionEvent(firstDownTime, firstDownTime + 24L, MotionEvent.ACTION_MOVE, x, firstEndY),
            )
            record("forward-move", firstOriginScrollY)
            view.dispatchTouchEvent(
                motionEvent(firstDownTime, firstDownTime + 48L, MotionEvent.ACTION_UP, x, firstEndY),
            )
            record("forward-up", firstOriginScrollY)
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)
            layoutCurrentChapter()
            record("incoming-hidden")

            assertEquals(listOf(true), commits)
            assertTrue("the incoming chapter must still be protected by the boundary cover", view.privateBool("boundaryContinuityCover"))
            assertTrue("the incoming chapter must still be awaiting stability", view.privateBool("awaitingStableChapter"))
            assertTrue("fixture needs a multi-page incoming chapter", view.pageCount() > 2)

            val secondOriginScrollY = view.scrollY
            val secondStartY = view.height * 0.15f
            val secondEndY = secondStartY + acceptedTurnDistance
            val secondDownTime = SystemClock.uptimeMillis()
            assertEquals(
                EpubPagedTouchZone.PageTurn,
                EpubPagedTouchZones.classify(view.width, view.height, x, secondStartY),
            )
            view.dispatchTouchEvent(
                motionEvent(secondDownTime, secondDownTime, MotionEvent.ACTION_DOWN, x, secondStartY),
            )
            record("backward-down-gated", secondOriginScrollY)

            view.pendingDecodesProvider = { false }
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("the target must reveal while the second finger remains down", view.privateBool("boundaryContinuityCover"))
            view.offerReadyBoundaryPreviewForTest(forward = false, token = 60L)
            record("backward-before-move", secondOriginScrollY)

            view.dispatchTouchEvent(
                motionEvent(secondDownTime, secondDownTime + 24L, MotionEvent.ACTION_MOVE, x, secondEndY),
            )
            record("backward-move", secondOriginScrollY)
            view.dispatchTouchEvent(
                motionEvent(secondDownTime, secondDownTime + 48L, MotionEvent.ACTION_UP, x, secondEndY),
            )
            record("backward-up", secondOriginScrollY)
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)
            layoutCurrentChapter()
            view.tryRevealWhenStable()
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            record("backward-settled")

            assertTrue(
                "non-center PageTurn streams must never enter temporary/free scroll or move the live viewport " +
                    "before their single commit; violations=$violations trace=${trace.joinToString(" | ")}",
                violations.isEmpty(),
            )
            assertEquals(
                "the opposite short swipe must cross back exactly once after the forward chapter turn; " +
                    "trace=${trace.joinToString(" | ")}",
                listOf(true, false),
                commits,
            )
            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertFalse(view.privateBool("freeScrolling"))
            assertTrue(view.privateBool("pageClipActive"))
            assertEquals("ALIGNED", view.privateEnumName("pagedMotionState"))
        } finally {
            view.pendingDecodesProvider = { false }
            view.dispose()
        }
    }

    @Test
    fun `accepted boundary swipe survives the adjacent renderer completion window`() {
        val commits = mutableListOf<Any>()
        val cancellations = mutableListOf<Boolean>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportHeight = 600,
        ).apply {
            installBoundaryCommitRecorderForTest(commits)
            onBoundaryPreviewRequestCancelled = cancellations::add
        }
        view.goToLastPage()
        view.onBoundaryPreviewNeeded = { forward, sourceGeneration ->
            val preview = BoundaryPagePreview(
                token = 57L,
                forward = forward,
                sourceChapterGeneration = sourceGeneration,
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888),
            )
            view.postDelayed({ view.offerBoundaryPreview(preview) }, 3_500L)
        }
        val x = view.width * 0.85f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, view.height * 0.85f),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 100L, MotionEvent.ACTION_MOVE, x, view.height * 0.65f),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 200L, MotionEvent.ACTION_UP, x, view.height * 0.50f),
            )

            assertEquals("BOUNDARY_DISCRETE_WAITING", view.privateField("interactiveTurnState").toString())
            shadowOf(Looper.getMainLooper()).idleFor(4_500L, TimeUnit.MILLISECONDS)

            assertEquals("an accepted swipe must commit when the valid adjacent preview arrives", 1, commits.size)
            assertTrue("the view must not cancel work before the renderer deadline", cancellations.isEmpty())
        } finally {
            view.dispose()
        }
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `gentle short swipe commits equally within and across chapters on a dense screen`() {
        fun dispatchGentleForwardSwipe(view: EpubFlowView) {
            val downTime = SystemClock.uptimeMillis()
            val x = view.width * 0.85f
            val startY = view.height * 0.85f
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY))
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 150L, MotionEvent.ACTION_MOVE, x, view.height * 0.73f),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 300L, MotionEvent.ACTION_UP, x, view.height * 0.73f),
            )
        }

        val local = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        )
        try {
            local.preCachePageTexturesForTest()
            dispatchGentleForwardSwipe(local)
            val localStateAfterRelease = local.privateField("interactiveTurnState")
            val localCommitAfterRelease = local.privateField("localSoftwareSettleCommit")
            val localAnimatorAfterRelease = local.privateField("flipAnimator") as android.animation.ValueAnimator?
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)
            assertTrue(
                "a directional 24dp swipe below the fling gate must turn an ordinary page; " +
                    "state=$localStateAfterRelease commit=$localCommitAfterRelease " +
                    "animatorRunning=${localAnimatorAfterRelease?.isRunning} scrollY=${local.scrollY}",
                local.scrollY > 0,
            )
        } finally {
            local.dispose()
        }

        val commits = mutableListOf<Any>()
        val cancellations = mutableListOf<Boolean>()
        val boundary = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        ).apply {
            installBoundaryCommitRecorderForTest(commits)
            onBoundaryPreviewRequestCancelled = cancellations::add
            onBoundaryPreviewNeeded = { _, _ -> Unit }
        }
        boundary.goToLastPage()
        try {
            dispatchGentleForwardSwipe(boundary)
            assertEquals(
                "the same directional 24dp swipe must retain its cross-chapter intent while the preview is cold",
                "BOUNDARY_DISCRETE_WAITING",
                boundary.privateField("interactiveTurnState").toString(),
            )
            assertTrue("a valid light swipe must not cancel adjacent-chapter rendering", cancellations.isEmpty())

            val preview = boundary.newBoundaryPreviewForTest(forward = true, token = 56L)
            assertTrue(boundary.offerBoundaryPreviewForTest(preview))
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals("the retained cross-chapter light swipe must commit exactly once", 1, commits.size)
            assertTrue(cancellations.isEmpty())
        } finally {
            boundary.dispose()
        }
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun `gentle eighteen dp horizontal drag does not turn an ordinary page`() {
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        )
        val downTime = SystemClock.uptimeMillis()
        val density = view.resources.displayMetrics.density
        val startX = view.width * 0.85f
        val endX = startX - 18f * density
        val y = view.height * 0.50f

        try {
            view.preCachePageTexturesForTest()
            view.drainPendingPageTexturePrecacheForTest()
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, y))
            repeat(6) { step ->
                val eventTime = downTime + (step + 1) * 24L
                val progress = (step + 1) / 6f
                val move = motionEvent(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    startX + (endX - startX) * progress,
                    y,
                )
                view.onInterceptTouchEvent(move)
                view.onTouchEvent(move)
            }
            view.onTouchEvent(
                motionEvent(downTime, downTime + 168L, MotionEvent.ACTION_UP, endX, y),
            )
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals(
                "an 18dp gentle horizontal drag must remain below the page-turn threshold",
                0,
                view.currentPageIndex(),
            )
            assertEquals(0, view.scrollY)
        } finally {
            view.dispose()
        }
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `slow five dp drift does not turn an ordinary page`() {
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        )

        try {
            view.preCachePageTexturesForTest()
            dispatchFiveDpForwardGesture(view, durationMs = 450L)
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals(
                "a slow 5dp drift must not be admitted as a page turn",
                0,
                view.currentPageIndex(),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun `rejected five dp micro drift consumes an existing edge tap candidate`() {
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE, onTapZone = tapZones::add)
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.85f

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
            // Robolectric's GestureDetector slop does not follow the configured density. Preserve its
            // clean-tap candidate while presenting the G1 recognizer with the real-device disagreement:
            // a 5dp, zero-velocity release that is too slow to turn but too large to remain a tap.
            view.setPrivateField(
                "downY",
                y + 5f * view.resources.displayMetrics.density,
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 150L, MotionEvent.ACTION_UP, x, y),
            )
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertTrue(
                "a rejected micro drift must be consumed instead of falling back to the right-edge tap zone",
                tapZones.isEmpty(),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    @Config(qualifiers = "xxhdpi")
    fun `fast directional five dp swipe turns exactly one ordinary page`() {
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
        )

        try {
            view.preCachePageTexturesForTest()
            dispatchFiveDpForwardGesture(view, durationMs = 48L)
            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)

            assertEquals(
                "a fast directional 5dp swipe must turn exactly one page; " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "classified=${view.privateField("classified")} flipped=${view.privateField("flipped")} " +
                    "distanceGate=${view.privateField("turnIntentDistancePx")} " +
                    "velocityGate=${view.privateField("flipFlingThresholdPxPerSec")}",
                1,
                view.currentPageIndex(),
            )
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
    fun `rapid discrete slide turn queues without replacing the active renderer`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        assertTrue(view.goToAdjacentPage(1))
        val firstSlide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        assertTrue(view.goToAdjacentPage(1))

        assertTrue("the queued request must not replace the active renderer", firstSlide === view.privateField("slideDrawable"))
        assertEquals(1, view.currentPageIndex())

        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

        assertEquals(2, view.currentPageIndex())
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
    @Config(qualifiers = "xxhdpi")
    fun `opposite boundary swipe during conversion fade cancels the covered queue`() {
        val commits = mutableListOf<Boolean>()
        val reports = mutableListOf<Int>()
        val tapZones = mutableListOf<EpubFlowTapZone>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            viewportWidth = 1080,
            viewportHeight = 600,
            onTapZone = tapZones::add,
            onTopOffsetChanged = reports::add,
        ).apply {
            onBoundaryTurnCommitted = { commits += it.forward }
        }
        assertTrue("fixture needs a backward chapter boundary", view.currentPageIndex() == 0)
        reports.clear()
        view.showConversionSnapshotForTest(markerBitmap(view.width, view.height))
        view.setPrivateField("boundaryContinuityCover", true)
        view.setPrivateField("awaitingReveal", true)
        view.setPrivateField("awaitingStableChapter", true)
        view.setPrivateField("pendingInitialPageTurnDelta", null)
        view.getChildAt(0).alpha = 0f
        view.pendingDecodesProvider = { false }

        try {
            dispatchFiveDpForwardGesture(view, durationMs = 48L)

            assertEquals("the cover must queue gesture A", 1, view.privateInt("queuedPageTurnDelta"))
            assertNull(view.privateField("pendingInitialPageTurnDelta"))
            assertFalse(view.privateBool("coverConsumedGesture"))

            view.tryRevealWhenStable()

            val fade = checkNotNull(view.privateField("conversionFadeAnimator") as android.animation.ValueAnimator?)
            assertTrue("stable reveal must enter the conversion snapshot fade window", fade.isRunning)
            assertFalse(view.privateBool("awaitingStableChapter"))
            assertEquals("gesture A must still be queued at fade start", 1, view.privateInt("queuedPageTurnDelta"))

            val downTime = SystemClock.uptimeMillis()
            val x = view.width * 0.85f
            val startY = view.height * 0.15f
            val halfwayY = startY + 7.5f
            val releaseY = startY + 15f
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY))
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, x, halfwayY),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, x, releaseY),
            )

            shadowOf(Looper.getMainLooper()).idleFor(800L, TimeUnit.MILLISECONDS)
            val pendingAfterB = view.privateField("pendingInitialPageTurnDelta")
            shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

            assertEquals("opposite covered gestures must cancel without a chapter commit", emptyList<Boolean>(), commits)
            assertEquals(0, view.privateInt("queuedPageTurnDelta"))
            assertEquals("signed cancellation must keep the reader on the original page", 0, view.currentPageIndex())
            assertTrue("the opposite gesture must not escape into chapter navigation", tapZones.isEmpty())
            assertNull(
                "the legacy initial slot must remain unused after signed cancellation; " +
                    "pending=$pendingAfterB state=${view.privateField("interactiveTurnState")} " +
                    "commits=$commits reports=$reports",
                pendingAfterB,
            )
            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertEquals("late work must not replay either cancelled gesture", 0, commits.size)
        } finally {
            view.pendingDecodesProvider = { false }
            view.dispose()
        }
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
    fun `direct image-first chapter cover waits for exact first-page pixels before fading`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        val visibleOutgoing = requireNotNull(view.snapshotViewportForTest())
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(
                EpubDisplayBlock.Image(
                    href = "chapter-opening.png",
                    altText = "chapter opening image",
                    paragraphIndex = 0,
                    isInlineContent = false,
                ),
                EpubDisplayBlock.Text(
                    text = (1..40).joinToString("\n") { "Incoming chapter line $it." },
                    headingLevel = null,
                    paragraphIndex = 1,
                ),
            ),
        )
        val incomingImageStart = checkNotNull(incomingFlow.segments.firstOrNull { it.isImage }).layoutStart
        var exactPixelsStable = false
        val queriedImageStarts = mutableListOf<List<Int>>()
        var coverRetiredCallbacks = 0
        // Broad work remains pending; the direct cover must use only the current-page image gate.
        view.pendingDecodesProvider = { true }
        view.imagePixelsStableProvider = { starts ->
            queriedImageStarts += starts.toList()
            exactPixelsStable
        }
        view.onConversionCoverRetired = { coverRetiredCallbacks++ }

        try {
            assertTrue(
                "a direct image-first target must freeze the currently visible outgoing viewport",
                view.prepareDirectImageFirstChapterCover(incomingFlow),
            )
            val cover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertAllPixelsEqual(
                "the direct cover must own the exact outgoing viewport before the prior image loader releases",
                visibleOutgoing,
                cover.privateBitmap("bitmap"),
            )
            assertNull(
                "direct navigation must not reuse boundary turn state",
                view.privateField("pendingBoundaryPageTurn"),
            )
            assertTrue(
                "the direct cover must use the existing continuity-input shield",
                view.privateBool("boundaryContinuityCover"),
            )

            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            // First process the posted layout/settle pass so the 800ms gate is armed from a
            // measured target rather than from Robolectric's nested-post scheduling order.
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("the unstable target must arm the safety reveal", view.privateBool("awaitingReveal"))
            shadowOf(Looper.getMainLooper()).idleFor(801L, TimeUnit.MILLISECONDS)

            val waitingCover = checkNotNull(view.privateField("conversionSnapshotDrawable"))
            assertEquals(
                "the safety reveal may expose live content beneath the frozen outgoing owner",
                1f,
                view.getChildAt(0).alpha,
            )
            assertEquals(
                "an unstable first image must keep the direct cover fully opaque after the safety timeout",
                255,
                waitingCover.privateInt("alphaValue"),
            )
            assertTrue(
                "the direct gate must query the current first-page image occurrence, queried=$queriedImageStarts",
                queriedImageStarts.any { incomingImageStart in it },
            )
            assertNull(
                "the direct image-first gate must never create a pending boundary turn",
                view.privateField("pendingBoundaryPageTurn"),
            )
            assertFalse(
                "the direct image-first continuity cover must block optional GPU preparation",
                view.isIdleGpuPreparationSafe(),
            )

            exactPixelsStable = true
            view.onAsyncImageDecodeFinished()
            view.textView.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(
                "cover retirement must wait until the conversion fade has finished",
                0,
                coverRetiredCallbacks,
            )
            assertFalse(
                "GPU preparation must remain gated while the direct cover is fading",
                view.isIdleGpuPreparationSafe(),
            )
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

            assertNull(
                "the current reveal fade must retire the direct cover only after exact first-page pixels stabilize",
                view.privateField("conversionSnapshotDrawable"),
            )
            assertEquals("cover retirement must reopen the idle preparation window", 1, coverRetiredCallbacks)
            assertTrue(view.isIdleGpuPreparationSafe())
        } finally {
            if (!visibleOutgoing.isRecycled) view.recyclePageShotForTest(visibleOutgoing)
            view.dispose()
        }
    }

    @Test
    fun `direct image-first chapter cover discards source rapid turns before target install`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(
                EpubDisplayBlock.Image(
                    href = "chapter-opening.png",
                    altText = "chapter opening image",
                    paragraphIndex = 0,
                    isInlineContent = false,
                ),
                EpubDisplayBlock.Text(
                    text = "Target chapter text.",
                    headingLevel = null,
                    paragraphIndex = 1,
                ),
            ),
        )

        try {
            assertTrue(view.prepareDirectImageFirstChapterCover(incomingFlow))
            view.setPrivateField("queuedPageTurnDelta", 2)
            view.setPrivateField("rapidTurnSequenceActive", true)

            view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)

            assertEquals(
                "source-page taps must not turn the newly selected chapter",
                0,
                view.privateInt("queuedPageTurnDelta"),
            )
            assertFalse(view.privateBool("rapidTurnSequenceActive"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `direct image-first chapter cover refuses a moving viewport`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(
                EpubDisplayBlock.Image(
                    href = "chapter-opening.png",
                    altText = "chapter opening image",
                    paragraphIndex = 0,
                    isInlineContent = false,
                ),
            ),
        )

        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.isRapidTurnPerformanceModeActive())

            assertFalse(
                "a direct cover must never snapshot the TextView while its visible page is an overlay",
                view.prepareDirectImageFirstChapterCover(incomingFlow),
            )
            assertNull(view.privateField("conversionSnapshotDrawable"))
        } finally {
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            view.dispose()
        }
    }

    @Test
    fun `direct image-first cover refuses to snapshot during page-turn motion`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(
                EpubDisplayBlock.Image(
                    href = "chapter-opening.png",
                    altText = "chapter opening image",
                    paragraphIndex = 0,
                    isInlineContent = false,
                ),
            ),
        )

        try {
            view.setPrivateField("queuedPageTurnDelta", 1)

            assertTrue("fixture must model unresolved source page-turn motion", view.isPageTurnMotionActive())
            assertFalse(view.prepareDirectImageFirstChapterCover(incomingFlow))
            assertNull(
                "the defensive guard must not install a snapshot of a page parked beneath the active turn",
                view.privateField("conversionSnapshotDrawable"),
            )
            assertNull(view.privateField("directImageFirstCoverTarget"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `direct image-first cover is ignored in scroll mode`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(
                EpubDisplayBlock.Image(
                    href = "chapter-opening.png",
                    altText = "chapter opening image",
                    paragraphIndex = 0,
                    isInlineContent = false,
                ),
            ),
        )

        try {
            view.mode = EpubFlowView.Mode.SCROLL

            assertFalse(view.prepareDirectImageFirstChapterCover(incomingFlow))
            assertNull(view.privateField("conversionSnapshotDrawable"))
            assertNull(view.privateField("directImageFirstCoverTarget"))
            assertFalse(view.privateBool("awaitingReveal"))
            assertFalse(view.privateBool("awaitingStableChapter"))
            assertEquals(1f, view.getChildAt(0).alpha)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `paged to scroll retires a waiting direct image-first cover through both mode paths`() {
        fun assertWaitingCoverIsRetired(switchToScroll: (EpubFlowView) -> Unit) {
            val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
            val incomingFlow = epubBuildChapterFlow(
                spineIndex = 1,
                blocks = listOf(
                    EpubDisplayBlock.Image(
                        href = "chapter-opening.png",
                        altText = "chapter opening image",
                        paragraphIndex = 0,
                        isInlineContent = false,
                    ),
                    EpubDisplayBlock.Text(
                        text = "Incoming target text.",
                        headingLevel = null,
                        paragraphIndex = 1,
                    ),
                ),
            )
            view.pendingDecodesProvider = { true }
            view.imagePixelsStableProvider = { false }

            try {
                assertTrue(view.prepareDirectImageFirstChapterCover(incomingFlow))
                view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
                assertNotNull(view.privateField("conversionSnapshotDrawable"))
                assertTrue(view.privateBool("awaitingStableChapter"))

                switchToScroll(view)

                assertEquals(EpubFlowView.Mode.SCROLL, view.mode)
                assertEquals(1f, view.getChildAt(0).alpha)
                assertFalse(view.privateBool("awaitingReveal"))
                assertFalse(view.privateBool("awaitingStableChapter"))
                assertNull(view.privateField("conversionSnapshotDrawable"))
                assertNull(view.privateField("directImageFirstCoverTarget"))
                assertFalse(view.privateBool("boundaryContinuityCover"))
            } finally {
                view.dispose()
            }
        }

        assertWaitingCoverIsRetired { view ->
            view.setModeAnchored(EpubFlowView.Mode.SCROLL, layoutOffset = 0)
        }
        assertWaitingCoverIsRetired { view ->
            view.mode = EpubFlowView.Mode.SCROLL
        }
    }

    @Test
    fun `accepted pending boundary animation pairs one start with one settle`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToLastPage()
        var startedCount = 0
        var settledCount = 0
        view.onPageTurnStarted = { startedCount++ }
        view.onPageSettled = { settledCount++ }
        view.pendingDecodesProvider = { true }
        assertTrue(view.prepareBoundaryPageTurn(1))
        val incomingText = (1..40).joinToString("\n") { "Incoming chapter line $it." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )

        view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)
        assertEquals(0, startedCount)
        assertEquals(0, settledCount)

        view.pendingDecodesProvider = { false }
        view.tryRevealWhenStable()
        val animator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)

        assertEquals("accepted target capture must publish one start", 1, startedCount)
        assertEquals(0, settledCount)
        animator.end()
        assertEquals(1, startedCount)
        assertEquals("the same visual transaction must publish one settle", 1, settledCount)
    }

    @Test
    fun `rejected pending boundary target capture publishes neither start nor settle`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToLastPage()
        var startedCount = 0
        var settledCount = 0
        view.onPageTurnStarted = { startedCount++ }
        view.onPageSettled = { settledCount++ }
        view.pendingDecodesProvider = { true }
        assertTrue(view.prepareBoundaryPageTurn(1))
        val incomingText = (1..40).joinToString("\n") { "Rejected chapter line $it." }
        val incomingFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(EpubDisplayBlock.Text(incomingText, headingLevel = null, paragraphIndex = 0)),
        )

        view.setChapter(incomingFlow, incomingFlow.text, pageHeightPx = view.height)
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)
        view.background = CaptureTargetBitmapThenThrowDrawable()
        view.pendingDecodesProvider = { false }
        view.tryRevealWhenStable()

        assertEquals(0, startedCount)
        assertEquals(0, settledCount)
        assertNull(view.privateField("slideDrawable"))
        assertNotNull(
            "a failed target capture must retain the outgoing continuity cover instead of hard-cutting",
            view.privateField("conversionSnapshotDrawable"),
        )
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
    fun `rapid discrete paper turn queues without replacing the active renderer`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        assertTrue(view.goToAdjacentPage(1))
        val firstPaper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?)
        assertTrue(view.goToAdjacentPage(1))

        assertTrue("the second request must not replace the active renderer", firstPaper === view.privateField("curlDrawable"))
        assertEquals(1, view.currentPageIndex())

        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

        assertEquals(2, view.currentPageIndex())
    }

    @Test
    fun `rapid paper burst starts an adjacent follow-up animation instead of jumping to a far page`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)

        assertTrue(view.goToAdjacentPage(1))
        val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertEquals(280L, firstAnimator.duration)
        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))
        assertTrue("queued input must not replace the active transaction", firstAnimator.isRunning)

        firstAnimator.end()
        view.drainPendingPageTexturePrecacheForTest()

        val followUpAnimator = checkNotNull(
            view.privateField("flipAnimator") as android.animation.ValueAnimator?,
        )
        assertTrue("the queue must start a new visual transaction", followUpAnimator !== firstAnimator)
        assertTrue("the adjacent follow-up must still be visibly animating", followUpAnimator.isRunning)
        assertEquals(280L, followUpAnimator.duration)
        assertTrue(
            "a rapid burst must keep constant velocity between adjacent page artifacts",
            followUpAnimator.interpolator is LinearInterpolator,
        )
        assertNotNull("rapid SIMULATION follow-up must keep PAPER", view.privateField("curlDrawable"))
        assertNull("rapid SIMULATION follow-up must not install slide", view.privateField("slideDrawable"))
        assertEquals("one follow-up animation may park only its adjacent target", 2, view.currentPageIndex())
        assertEquals("the remaining rapid request must wait for its own animation", 1, view.privateInt("queuedPageTurnDelta"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `rapid local follow-up mid frame keeps outgoing and revealed target text visible`() {
        val outgoingTextColor = 0xFF16A34A.toInt()
        val targetTextColor = 0xFF2563EB.toInt()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            text = (1..120).joinToString("\n") { line ->
                "Line $line carries marked EPUB body text across the full viewport width."
            },
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val markedText = SpannableString(view.textView.text).apply {
            setSpan(
                BackgroundColorSpan(outgoingTextColor),
                pages[1].startOffset,
                pages[1].endOffset,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                BackgroundColorSpan(targetTextColor),
                pages[2].startOffset,
                pages[2].endOffset,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val flow = checkNotNull(view.privateField("flow") as EpubChapterFlow?)
        view.recycleCachedTexturesForTest()
        view.setChapter(flow, markedText, pageHeightPx = view.height)
        view.measure(exactly(view.width), exactly(view.height))
        view.layout(0, 0, view.width, view.height)
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(exactly(view.width), exactly(view.height))
        view.layout(0, 0, view.width, view.height)
        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)
        view.invalidate()

        try {
            assertTrue(view.goToAdjacentPage(1))
            val firstAnimator = checkNotNull(
                view.privateField("flipAnimator") as android.animation.ValueAnimator?,
            )
            assertTrue(view.goToAdjacentPage(1))

            firstAnimator.end()
            view.drainPendingPageTexturePrecacheForTest()

            val followUpAnimator = checkNotNull(
                view.privateField("flipAnimator") as android.animation.ValueAnimator?,
            )
            val followUpPaper = checkNotNull(
                view.privateField("curlDrawable") as PageCurlDrawable?,
            )
            assertNull("SIMULATION rapid text turn must not degrade to slide", view.privateField("slideDrawable"))
            followUpPaper.privateBitmap("frontBitmap").eraseColor(outgoingTextColor)
            followUpAnimator.currentPlayTime = followUpAnimator.duration / 2L
            val midFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val outgoingHits = midFrame.countExactPixels(
                    outgoingTextColor,
                    Rect(0, 0, view.width / 2, view.height),
                )
                val targetHits = midFrame.countExactPixels(
                    targetTextColor,
                    Rect(view.width / 2, 0, view.width, view.height),
                )
                assertTrue(
                    "a rapid local turn must move the old body while revealing the new body before settle; " +
                        "outgoingHits=$outgoingHits targetHits=$targetHits " +
                        "playTime=${followUpAnimator.currentPlayTime}/${followUpAnimator.duration}",
                    outgoingHits > 0 && targetHits > 0,
                )
            } finally {
                midFrame.recycle()
            }
        } finally {
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            view.dispose()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `rapid image follow-up renders outgoing and target from one frozen generation`() {
        val fixture = visibleHeadingImageContinuationFixture(minHeadingPageIndex = 2)
        val view = fixture.view
        val outgoingColor = 0xFF16A34A.toInt()
        val frozenTargetColor = 0xFF2563EB.toInt()
        val liveMutationColor = 0xFFE11D48.toInt()
        view.flipStyle = PageFlipStyle.SIMULATION
        view.goToPage(fixture.headingPageIndex - 2)
        fixture.imageDrawable.color = frozenTargetColor
        view.textView.invalidate()
        view.invalidate()
        view.recycleCachedTexturesForTest()

        try {
            assertTrue(view.goToAdjacentPage(1))
            val firstAnimator = checkNotNull(
                view.privateField("flipAnimator") as android.animation.ValueAnimator?,
            )
            assertTrue(view.goToAdjacentPage(1))

            firstAnimator.end()
            view.drainPendingPageTexturePrecacheForTest()

            assertEquals(fixture.headingPageIndex, view.currentPageIndex())
            val followUpAnimator = checkNotNull(
                view.privateField("flipAnimator") as android.animation.ValueAnimator?,
            )
            val followUpPaper = checkNotNull(
                view.privateField("curlDrawable") as PageCurlDrawable?,
            )
            assertNull("SIMULATION rapid image turn must not degrade to slide", view.privateField("slideDrawable"))
            followUpPaper.privateBitmap("frontBitmap").eraseColor(outgoingColor)
            view.getChildAt(0).visibility = View.INVISIBLE
            fixture.imageDrawable.color = liveMutationColor
            view.textView.invalidate()
            view.invalidate()

            followUpAnimator.currentPlayTime = followUpAnimator.duration / 2L
            // Pin the renderer itself so the two sampled halves match the paper seam exactly.
            followUpPaper.progress = 0.5f
            val midFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val outgoingHalf = Rect(0, 0, view.width / 2, view.height)
                val revealedHalf = Rect(view.width / 2, 0, view.width, view.height)
                val outgoingHits = midFrame.countExactPixels(outgoingColor, outgoingHalf)
                val frozenTargetHits = midFrame.countExactPixels(frozenTargetColor, revealedHalf)
                val liveMutationHits = midFrame.countExactPixels(liveMutationColor, revealedHalf)
                assertTrue(
                    "a rapid image turn must render both halves from artifacts frozen before live pixels mutate; " +
                        "outgoingHits=$outgoingHits frozenTargetHits=$frozenTargetHits " +
                        "liveMutationHits=$liveMutationHits",
                    outgoingHits > 0 && frozenTargetHits > 0 && liveMutationHits == 0,
                )
            } finally {
                midFrame.recycle()
            }
        } finally {
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            view.dispose()
        }
    }

    @Test
    fun `rapid image landing waits for decoded target artifact before parking the page`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        var decodePending = false
        view.pendingDecodesProvider = { decodePending }

        try {
            assertTrue(view.goToAdjacentPage(1))
            val firstAnimator = checkNotNull(
                view.privateField("flipAnimator") as android.animation.ValueAnimator?,
            )
            assertTrue(view.goToAdjacentPage(1))
            decodePending = true

            firstAnimator.end()

            assertEquals(
                "a rapid image target must not park while its decoded pixels are pending",
                1,
                view.currentPageIndex(),
            )
            assertEquals("the blocked landing request must remain queued", 1, view.privateInt("queuedPageTurnDelta"))
            assertFalse(
                "no follow-up animation may expose the transparent live target",
                (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.isRunning == true,
            )
            assertNull(view.privateField("slideDrawable"))
            assertNull(view.privateField("curlDrawable"))

            decodePending = false
            view.onAsyncImageDecodeFinished()
            view.textView.viewTreeObserver.dispatchOnPreDraw()
            view.drainPendingPageTexturePrecacheForTest()

            val landingAnimator = checkNotNull(
                view.privateField("flipAnimator") as android.animation.ValueAnimator?,
            )
            assertTrue("decoded target pixels must release the queued landing animation", landingAnimator.isRunning)
            assertEquals(2, view.currentPageIndex())
            assertEquals(0, view.privateInt("queuedPageTurnDelta"))
            val paper = checkNotNull(view.privateField("curlDrawable"))
            assertNotNull(
                "the released landing must own a frozen target artifact",
                paper.reflectedField("revealedBitmap"),
            )
            assertNull("decoded SIMULATION landing must not degrade to slide", view.privateField("slideDrawable"))
        } finally {
            decodePending = false
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            view.dispose()
        }
    }

    @Test
    fun `rapid next at the chapter edge starts a boundary follow-up animation`() {
        lateinit var view: EpubFlowView
        view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            onTapZone = { zone ->
                if (zone == EpubFlowTapZone.NEXT) assertTrue(view.startDiscreteBoundaryTurn(1))
            },
        )
        val commits = mutableListOf<Long>()
        view.onBoundaryTurnCommitted = { commits += it.token }
        val finalPage = view.pageCount() - 1
        assertTrue("pageCount=${view.pageCount()}", finalPage > 2)
        view.goToPage(finalPage - 1)
        view.offerReadyBoundaryPreviewForTest(forward = true, token = 704L)

        assertTrue(view.goToAdjacentPage(1))
        val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertTrue(view.goToAdjacentPage(1))

        firstAnimator.end()

        val boundaryAnimator = checkNotNull(
            view.privateField("flipAnimator") as android.animation.ValueAnimator?,
        )
        assertTrue(boundaryAnimator !== firstAnimator)
        assertTrue(boundaryAnimator.isRunning)
        assertEquals(280L, boundaryAnimator.duration)
        assertNotNull(view.privateField("slideDrawable"))
        assertEquals("the boundary must not commit before its follow-up animation settles", emptyList<Long>(), commits)

        boundaryAnimator.end()

        assertEquals(listOf(704L), commits)
    }

    @Test
    fun `rapid simulation next at the chapter edge preserves paper renderer`() {
        lateinit var view: EpubFlowView
        view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTapZone = { zone ->
                if (zone == EpubFlowTapZone.NEXT) assertTrue(view.startDiscreteBoundaryTurn(1))
            },
        )
        val finalPage = view.pageCount() - 1
        assertTrue("pageCount=${view.pageCount()}", finalPage > 2)
        view.goToPage(finalPage - 1)
        view.offerReadyBoundaryPreviewForTest(forward = true, token = 705L)

        assertTrue(view.goToAdjacentPage(1))
        val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertTrue(view.goToAdjacentPage(1))

        firstAnimator.end()

        val boundaryAnimator = checkNotNull(
            view.privateField("flipAnimator") as android.animation.ValueAnimator?,
        )
        assertTrue(boundaryAnimator.isRunning)
        assertEquals(280L, boundaryAnimator.duration)
        assertNotNull("rapid boundary SIMULATION must keep PAPER", view.privateField("curlDrawable"))
        assertNull("rapid boundary SIMULATION must never degrade to slide", view.privateField("slideDrawable"))
    }

    @Test
    fun `page turn motion query spans a rapid follow-up and clears after its final settle`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        assertFalse(view.isPageTurnMotionActive())

        assertTrue(view.goToAdjacentPage(1))
        val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertTrue(view.isPageTurnMotionActive())
        assertTrue(view.goToAdjacentPage(1))

        firstAnimator.end()
        view.drainPendingPageTexturePrecacheForTest()

        val followUpAnimator = checkNotNull(
            view.privateField("flipAnimator") as android.animation.ValueAnimator?,
        )
        assertTrue(followUpAnimator !== firstAnimator)
        assertTrue(view.isPageTurnMotionActive())
        assertEquals(280L, followUpAnimator.duration)
        assertNotNull(view.privateField("slideDrawable"))

        followUpAnimator.end()

        assertEquals(0, view.privateInt("queuedPageTurnDelta"))
        assertFalse("the empty rapid idle window is not visual motion", view.isPageTurnMotionActive())
    }

    @Test
    fun `rapid performance mode releases at idle and publishes the final settle`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        var settledCount = 0
        view.onPageSettled = { settledCount++ }

        assertTrue(view.goToAdjacentPage(1))
        val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertTrue(view.goToAdjacentPage(1))
        firstAnimator.end()
        view.drainPendingPageTexturePrecacheForTest()
        val followUpAnimator = checkNotNull(
            view.privateField("flipAnimator") as android.animation.ValueAnimator?,
        )
        followUpAnimator.end()

        assertEquals(0, view.privateInt("queuedPageTurnDelta"))
        assertFalse(view.isPageTurnMotionActive())
        assertTrue(view.isRapidTurnPerformanceModeActive())
        val countBeforeIdle = settledCount

        shadowOf(Looper.getMainLooper()).idleFor(319L, TimeUnit.MILLISECONDS)
        assertTrue(view.isRapidTurnPerformanceModeActive())
        assertEquals(countBeforeIdle, settledCount)

        shadowOf(Looper.getMainLooper()).runOneTask()
        assertTrue(
            "the timeout must only arm the frame-split settle tail",
            view.isRapidTurnPerformanceModeActive(),
        )
        assertEquals(
            "the idle timeout must arm the first frame rather than run it inline",
            "APPLY_ASYNC",
            view.privateField("rapidIdleSettleStage").toString(),
        )
        assertEquals(
            "the settle callback must run after the split frames",
            countBeforeIdle,
            settledCount,
        )

        shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        assertFalse(view.isRapidTurnPerformanceModeActive())
        assertEquals(countBeforeIdle + 1, settledCount)
    }

    @Test
    fun `rapid idle image tail separates rebind from settle callback`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.NONE)
        try {
            view.pageTexturePrecacheEnabled = false
            var settledCount = 0
            view.onPageSettled = { settledCount++ }
            val pages = view.privateField("paged") as List<EpubFlowPage>

            view.acceptPromotedPageTurns(delta = 0, rapidSequence = true)
            view.onAsyncImagePixelsChangedRequiringTextRebind(pages.first().startOffset)
            assertTrue(view.privateBool("asyncImagePixelTextRebindPending"))

            fun advanceUntilStage(expected: String) {
                repeat(16) {
                    if (view.privateField("rapidIdleSettleStage").toString() == expected) return
                    shadowOf(Looper.getMainLooper()).runOneTask()
                }
                assertEquals(expected, view.privateField("rapidIdleSettleStage").toString())
            }

            advanceUntilStage("APPLY_ASYNC")
            assertEquals(
                "the rapid timeout must not execute image work or settle synchronously",
                0,
                settledCount,
            )
            assertEquals(
                "the timeout must arm the first frame-split stage",
                "APPLY_ASYNC",
                view.privateField("rapidIdleSettleStage").toString(),
            )
            assertTrue(view.privateBool("rapidTurnSequenceActive"))

            advanceUntilStage("PRECACHE")
            assertEquals(
                "the apply frame must hand off to precache without settling in the same callback",
                "PRECACHE",
                view.privateField("rapidIdleSettleStage").toString(),
            )
            assertEquals(0, settledCount)
            assertFalse(view.privateBool("asyncImagePixelTextRebindPending"))

            advanceUntilStage("SETTLE")
            assertEquals(
                "precache must hand off to settle on its own frame",
                "SETTLE",
                view.privateField("rapidIdleSettleStage").toString(),
            )
            assertEquals(0, settledCount)
            assertTrue(view.privateBool("rapidTurnSequenceActive"))

            advanceUntilStage("NONE")
            assertEquals(1, settledCount)
            assertFalse(view.privateBool("rapidTurnSequenceActive"))
            assertEquals(
                "the staged tail must be fully retired after its settle frame",
                "NONE",
                view.privateField("rapidIdleSettleStage").toString(),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `rapid idle image tail waits for relevant decode before rebind`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.NONE)
        try {
            view.pageTexturePrecacheEnabled = false
            var decodePending = true
            view.pendingDecodesProvider = { decodePending }
            val pages = view.privateField("paged") as List<EpubFlowPage>

            view.acceptPromotedPageTurns(delta = 0, rapidSequence = true)
            view.onAsyncImagePixelsChangedRequiringTextRebind(pages.first().startOffset)
            assertTrue(view.privateBool("asyncImagePixelTextRebindPending"))

            fun advanceUntilApplyStage() {
                repeat(16) {
                    if (view.privateField("rapidIdleSettleStage").toString() == "APPLY_ASYNC") return
                    shadowOf(Looper.getMainLooper()).runOneTask()
                }
                assertEquals("APPLY_ASYNC", view.privateField("rapidIdleSettleStage").toString())
            }
            advanceUntilApplyStage()

            shadowOf(Looper.getMainLooper()).idleFor(80L, TimeUnit.MILLISECONDS)
            assertEquals(
                "a relevant decode must keep the staged tail in APPLY_ASYNC",
                "APPLY_ASYNC",
                view.privateField("rapidIdleSettleStage").toString(),
            )
            assertTrue(
                "a relevant decode must keep the whole-TextView rebind pending",
                view.privateBool("asyncImagePixelTextRebindPending"),
            )
            assertTrue(view.privateBool("rapidTurnSequenceActive"))

            decodePending = false
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
            assertFalse(
                "the staged apply must consume the rebind after the relevant decode settles",
                view.privateBool("asyncImagePixelTextRebindPending"),
            )
            assertFalse(view.privateBool("rapidTurnSequenceActive"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `rapid idle settle waits for any pending precache state`() {
        listOf(
            Triple(PageFlipStyle.SIMULATION, "pageTexturePrecachePending", "pendingPageTexturePrecache"),
            Triple(PageFlipStyle.SLIDE, "slideArtifactPrecachePending", "pendingSlideArtifactPrecache"),
        ).forEach { (flipStyle, pendingFlagField, pendingRequestField) ->
            val view = pagedFlowView(flipStyle = flipStyle)
            var request: Any? = null
            var precacheCompleted = false
            try {
                var settledCount = 0
                view.onPageSettled = { settledCount++ }
                view.recycleCachedTexturesForTest()
                view.preCachePageTexturesForTest(idlePostedWork = false)
                assertTrue("$pendingFlagField must be armed by real precache", view.privateBool(pendingFlagField))
                request = checkNotNull(view.privateField(pendingRequestField))

                fun assertSettleWaitsForPendingState(
                    state: String,
                    cancelSyntheticRecheck: Boolean,
                ) {
                    view.setRapidIdleSettleStageForTest("SETTLE")
                    view.runRapidIdleSettleForTest()
                    assertEquals(
                        "$pendingFlagField/$pendingRequestField $state must keep settle behind precache",
                        0,
                        settledCount,
                    )
                    assertTrue(
                        "$pendingFlagField/$pendingRequestField $state must keep rapid ownership",
                        view.privateBool("rapidTurnSequenceActive"),
                    )
                    assertEquals(
                        "$pendingFlagField/$pendingRequestField $state must recheck settle next frame",
                        "SETTLE",
                        view.privateField("rapidIdleSettleStage").toString(),
                    )
                    assertTrue(
                        "$pendingFlagField/$pendingRequestField $state must post one next-frame recheck",
                        view.privateBool("rapidIdleSettlePosted"),
                    )
                    if (cancelSyntheticRecheck) view.cancelRapidIdleSettleForTest()
                }

                fun advanceUntilOneFinalSettle() {
                    repeat(16) {
                        if (settledCount == 1 && !view.privateBool("rapidTurnSequenceActive")) return
                        shadowOf(Looper.getMainLooper()).runOneTask()
                    }
                    assertEquals(
                        "$pendingFlagField/$pendingRequestField must publish exactly one final settle",
                        1,
                        settledCount,
                    )
                    assertFalse(view.privateBool("rapidTurnSequenceActive"))
                }

                view.setPrivateField("rapidTurnSequenceActive", true)
                view.setPrivateField(pendingRequestField, null)
                assertSettleWaitsForPendingState("flag-only", cancelSyntheticRecheck = true)

                view.setPrivateField(pendingRequestField, request)
                view.setPrivateField(pendingFlagField, false)
                assertSettleWaitsForPendingState("request-only", cancelSyntheticRecheck = false)

                view.setPrivateField(pendingFlagField, true)
                view.drainPendingPageTexturePrecacheForTest()
                precacheCompleted = true
                advanceUntilOneFinalSettle()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(
                    "$pendingFlagField/$pendingRequestField must not publish a duplicate final settle",
                    1,
                    settledCount,
                )
                assertFalse(view.privateBool("rapidTurnSequenceActive"))
            } finally {
                if (!precacheCompleted && request != null) {
                    view.setPrivateField(pendingRequestField, request)
                    view.setPrivateField(pendingFlagField, true)
                }
                view.removeCallbacks(view.privateField("rapidTurnIdleRunnable") as Runnable)
                view.removeCallbacks(view.privateField("rapidIdleSettleRunnable") as Runnable)
                view.dispose()
            }
        }
    }

    @Test
    fun `pointer down cancels pending slide precache but preserves retained warm slots`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            view.drainPendingPageTexturePrecacheForTest()
            val initialFront = checkNotNull(view.slideFrontArtifactForTest())
            val initialRevealed = checkNotNull(view.slideRevealedArtifactForTest())

            assertTrue(view.goToAdjacentPage(1))
            checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?).end()

            val pageBeforeDown = view.currentPageIndex()
            val topBeforeDown = view.scrollY
            val pages = view.privateField("paged") as List<EpubFlowPage>
            val warmFront = checkNotNull(view.slideFrontArtifactForTest())
            val warmBackward = checkNotNull(view.slideBackwardArtifactForTest())
            val pending = checkNotNull(view.privateField("pendingSlideArtifactPrecache"))
            assertTrue("settled forward turn must have a backward warm slot", pageBeforeDown > 0)
            assertSame(initialRevealed, warmFront)
            assertSame(initialFront, warmBackward)
            assertSame("pending front must alias the live current slot", warmFront, pending.reflectedField("frontArtifact"))
            assertSame("pending previous must alias the live backward slot", warmBackward, pending.reflectedField("previousArtifact"))
            assertEquals(true, pending.reflectedField("frontArtifactRetained"))
            assertEquals(true, pending.reflectedField("previousArtifactRetained"))
            assertNull(pending.reflectedField("targetArtifact"))
            assertTrue(view.privateBool("slideArtifactPrecachePending"))

            val downTime = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(
                motionEvent(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    view.width * 0.85f,
                    view.height * 0.50f,
                ),
            )

            assertNull(view.privateField("pendingSlideArtifactPrecache"))
            assertFalse(view.privateBool("slideArtifactPrecachePending"))
            assertNull(view.privateField("captureSlideArtifactTargetRunnable"))
            assertSame("cancellation must preserve the live current slot", warmFront, view.slideFrontArtifactForTest())
            assertSame("cancellation must preserve the live backward slot", warmBackward, view.slideBackwardArtifactForTest())
            assertTrue(warmFront.slideArtifactRecordIsLive())
            assertTrue(warmBackward.slideArtifactRecordIsLive())
            assertEquals(pageBeforeDown, view.currentPageIndex())
            assertEquals(topBeforeDown, view.scrollY)

            val usablePair = checkNotNull(
                view.takeCachedSlideArtifactsForTurnForTest(
                    fromPage = pageBeforeDown,
                    fromTop = topBeforeDown,
                    fromWindow = pages[pageBeforeDown],
                    targetPage = pageBeforeDown - 1,
                    targetTop = pages[pageBeforeDown - 1].topPx,
                    targetWindow = pages[pageBeforeDown - 1],
                ),
            )
            assertSame(warmFront, usablePair.first)
            assertSame(warmBackward, usablePair.second)
            view.discardSlideArtifactForTest(usablePair.first)
            view.discardSlideArtifactForTest(usablePair.second)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `page turn started callback covers visual follow-ups and interactive intent`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        var startedCount = 0
        view.onPageTurnStarted = { startedCount++ }

        assertTrue(view.goToAdjacentPage(1))
        val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        assertEquals(1, startedCount)

        assertTrue(view.goToAdjacentPage(1))
        assertEquals("queueing must not replace or restart the active transaction", 1, startedCount)

        firstAnimator.end()
        view.drainPendingPageTexturePrecacheForTest()
        val followUpAnimator = checkNotNull(
            view.privateField("flipAnimator") as android.animation.ValueAnimator?,
        )
        assertEquals(2, startedCount)
        followUpAnimator.end()
        shadowOf(Looper.getMainLooper()).idleFor(321L, TimeUnit.MILLISECONDS)

        val downTime = SystemClock.uptimeMillis()
        val y = view.height * 0.50f
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, y),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, view.width * 0.15f, y),
        )

        assertEquals(3, startedCount)
        assertTrue(view.isPageTurnMotionActive())
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, view.width * 0.15f, y),
        )

        view.dispose()
        assertNull(view.onPageTurnStarted)
    }

    @Test
    fun `page turn capture preparation runs before any outgoing or target snapshot`() {
        val events = mutableListOf<String>()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        view.recycleCachedTexturesForTest()
        view.background = RecordingTargetBitmapDrawable { events += "capture" }
        view.onPageTurnCapturePreparing = { events += "prepare" }
        view.onPageTurnStarted = { events += "started" }

        assertTrue(view.goToAdjacentPage(1))

        assertEquals("prepare", events.firstOrNull())
        assertTrue("an accepted turn must capture after preparation: $events", events.indexOf("capture") > 0)
        assertTrue(
            "the accepted-start callback remains after both page shots are owned: $events",
            events.lastIndexOf("started") > events.lastIndexOf("capture"),
        )
    }

    @Test
    fun `page turn started skips NONE turns`() {
        val none = pagedFlowView(flipStyle = PageFlipStyle.NONE)
        var noneStarted = 0
        none.onPageTurnStarted = { noneStarted++ }

        assertTrue(none.goToAdjacentPage(1))
        assertEquals(0, noneStarted)
    }

    @Test
    fun `page turn started skips rejected snapshot capture`() {
        val rejected = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        var rejectedStarted = 0
        rejected.recycleCachedTexturesForTest()
        rejected.background = CaptureTargetBitmapThenThrowDrawable()
        rejected.onPageTurnStarted = { rejectedStarted++ }

        assertTrue(rejected.goToAdjacentPage(1))
        assertEquals(0, rejectedStarted)
        assertEquals(0, rejected.currentPageIndex())
        assertFalse(rejected.isPageTurnMotionActive())
    }

    @Test
    fun `rapid paper burst drains every accepted forward request`() {
        val reportedOffsets = mutableListOf<Int>()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 5)
        reportedOffsets.clear()

        repeat(4) {
            assertTrue("request ${it + 1} must be accepted", view.goToAdjacentPage(1))
        }

        assertEquals("the active renderer must still own only the first turn", 1, view.currentPageIndex())

        shadowOf(Looper.getMainLooper()).idleFor(2L, TimeUnit.SECONDS)

        assertEquals("all accepted burst turns must drain in order", 4, view.currentPageIndex())
        assertEquals(
            "each accepted local page must publish one adjacent settle",
            4,
            reportedOffsets.size,
        )
        assertFalse((view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.isRunning == true)
    }

    @Test
    fun `rapid burst resumes a transferred dirty artifact before starting its next page`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)

        assertTrue(view.goToAdjacentPage(1))
        val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
        // Simulate a PIXELS_ONLY completion that arrived while the active pair owned the warm
        // current artifact. Settle must return that owner to the one-slot refresh queue.
        view.setPrivateField("activeFlipFrontPixelRefreshPending", true)
        assertTrue(view.goToAdjacentPage(1))

        firstAnimator.end()
        assertEquals(1, view.currentPageIndex())
        assertEquals(1, view.privateInt("queuedPageTurnDelta"))
        assertNull("the next turn waits for the dirty artifact refresh", view.privateField("slideDrawable"))
        assertNull("the next paper turn also waits for the dirty artifact refresh", view.privateField("curlDrawable"))

        shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)

        assertEquals(2, view.currentPageIndex())
        assertEquals(0, view.privateInt("queuedPageTurnDelta"))
        assertTrue(
            "the queued turn must start after the transferred artifact is refreshed",
            view.privateField("curlDrawable") != null || view.privateInt("cachedFromPage") == 2,
        )
    }

    @Test
    fun `prepared promotion expands the image window before starting a transferred turn`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        val decodeWindowPages = mutableListOf<Int>()
        view.onPageTurnTargetParked = { decodeWindowPages += view.currentPageIndex() }
        view.acceptPromotedPageTurns(delta = 1, rapidSequence = true)

        view.activatePreparedChapter()

        assertTrue("promotion must publish an active decode window", decodeWindowPages.isNotEmpty())
        assertEquals(
            "the active current-and-adjacent image window must be admitted before the queued target is captured",
            0,
            decodeWindowPages.first(),
        )
    }

    @Test
    fun `opposite rapid requests cancel before another renderer starts`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(-1))

        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

        assertEquals("opposite queued intent should remove unnecessary page-shot work", 1, view.currentPageIndex())
        assertEquals(0, view.privateInt("queuedPageTurnDelta"))
    }

    @Test
    fun `rapid follow-up prefetch tracks net queued direction and clears when intent cancels`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(-1))

            assertEquals(1, view.privateInt("queuedPageTurnDelta"))
            val netForward = checkNotNull(view.privateField("rapidFollowUpPageShot"))
            assertEquals(
                "the speculative target must follow the remaining queue, not the latest opposite key",
                true,
                netForward.reflectedField("forward"),
            )

            assertTrue(view.goToAdjacentPage(-1))

            assertEquals(0, view.privateInt("queuedPageTurnDelta"))
            assertNull(
                "a fully cancelled queue must release its now-unreachable speculative target",
                view.privateField("rapidFollowUpPageShot"),
            )
            assertNull(view.privateField("captureRapidFollowUpRunnable"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `rapid follow-up prefetch accepts a stable image target`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        try {
            assertTrue("fixture needs a local page before the heading", fixture.headingPageIndex > 0)
            assertEquals(fixture.headingPageIndex + 1, fixture.imagePageIndex)
            view.flipStyle = PageFlipStyle.SIMULATION
            view.goToPage(fixture.headingPageIndex - 1)
            var requestedStarts = emptyList<Int>()
            view.pendingDecodesProvider = { true }
            view.imagePixelsStableProvider = { starts ->
                requestedStarts = starts.toList()
                starts.all { it == fixture.imageLayoutStart }
            }

            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(1))

            assertEquals(1, view.privateInt("queuedPageTurnDelta"))
            assertEquals(listOf(fixture.imageLayoutStart), requestedStarts)
            assertNotNull(
                "a stable exact target must ignore unrelated global pending work",
                view.privateField("rapidFollowUpPageShot"),
            )
            assertNotNull(view.privateField("captureRapidFollowUpRunnable"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `rapid image target defers page-shot capture while pixel refresh is pending`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        var stable = false
        try {
            assertTrue(fixture.headingPageIndex > 0)
            view.flipStyle = PageFlipStyle.SIMULATION
            view.goToPage(fixture.headingPageIndex - 1)
            view.pendingDecodesProvider = { false }
            view.imagePixelsStableProvider = { starts -> starts.isEmpty() || stable }

            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(1))
            assertNull("an unresolved image target must not be captured", view.privateField("rapidFollowUpPageShot"))

            stable = true
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)

            assertNull(
                "a dirty image owner must not be captured into a stale rapid page shot",
                view.privateField("rapidFollowUpPageShot"),
            )
            assertTrue(
                "the pixel refresh must remain queued until the active turn settles",
                (view.privateField("asyncImagePixelRefreshOffsets") as Set<*>).isNotEmpty(),
            )
        } finally {
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            view.dispose()
        }
    }

    @Test
    fun `terminal image failure releases a queued rapid turn on the retained placeholder`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        var terminalFailure = false
        try {
            view.flipStyle = PageFlipStyle.SIMULATION
            view.goToPage(fixture.headingPageIndex - 1)
            view.pendingDecodesProvider = { false }
            view.imagePixelsStableProvider = { starts -> starts.isEmpty() || terminalFailure }

            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(1))
            val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
            firstAnimator.end()
            assertEquals(fixture.headingPageIndex, view.currentPageIndex())
            assertEquals(1, view.privateInt("queuedPageTurnDelta"))

            terminalFailure = true
            view.onAsyncImageDecodeFinished()
            view.textView.viewTreeObserver.dispatchOnPreDraw()
            view.drainPendingPageTexturePrecacheForTest()

            assertEquals("a terminal placeholder must not deadlock navigation", fixture.imagePageIndex, view.currentPageIndex())
            assertEquals(0, view.privateInt("queuedPageTurnDelta"))
        } finally {
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            view.dispose()
        }
    }

    @Test
    fun `real loader terminal failure drains queued navigation without recurring admission`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        val epub = java.io.File.createTempFile("readflow-real-loader-terminal", ".epub")
        val executor = QueuedImageDecodeExecutor()
        var decodeAttempts = 0
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = view.width,
            pageHeightProvider = { view.height },
            inlineMaxHeightPx = view.height,
            fullPageHrefs = emptySet(),
        )
        val loader = EpubFlowImageLoader(
            epubFileProvider = { epub },
            executor = executor,
            priorityLayoutRangesProvider = { view.decodeAdmissionLayoutRanges() },
            columnWidthPx = view.width,
            pageHeightProvider = { view.height },
            inlineMaxHeightPx = view.height,
            fullPageHrefs = emptySet(),
            imageBoundsProvider = { EpubImageBounds(width = view.width, height = view.height + 120) },
            imageQualityProvider = { EpubImageRenderQuality.RAPID },
            imageDecoder = { _, _, _ ->
                decodeAttempts += 1
                if (decodeAttempts == 1) {
                    Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                } else {
                    null
                }
            },
            onImageResultChanged = { result -> view.onAsyncImagePixelsChanged(result.layoutStart) },
            onDecodeFinished = view::onAsyncImageDecodeFinished,
        )
        val drawable = AsyncDrawable("plate-pattern.png", loader, resolver, null)
        loader.registerOccurrence(drawable, fixture.imageLayoutStart)
        drawable.setCallback2(object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = Unit
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        })
        loader.load(drawable)
        view.pendingDecodesProvider = {
            loader.hasRelevantPendingDecodes(view.relevantPendingDecodeLayoutRanges())
        }
        view.imagePixelsStableProvider = loader::hasStablePixels
        view.onDecodeWindowNeeded = {
            loader.updateDecodeWindow(view.decodeAdmissionLayoutRanges())
            loader.promoteToDisplayQuality(view.displayQualityLayoutRanges())
        }
        try {
            // Install retained RAPID pixels first. The queued turn then owns the real DISPLAY
            // promotion, its bounded retry, and the loader-to-view completion callback.
            view.goToPage(fixture.imagePageIndex)
            loader.updateDecodeWindow(view.decodeAdmissionLayoutRanges())
            loader.load(drawable)
            assertEquals(1, executor.queuedTaskCount)
            executor.runNext()
            shadowOf(Looper.getMainLooper()).idle()
            view.textView.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(1, decodeAttempts)
            assertFalse(loader.hasStablePixels(listOf(fixture.imageLayoutStart)))

            view.flipStyle = PageFlipStyle.SIMULATION
            view.goToPage(fixture.headingPageIndex - 1)
            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(1))
            val firstAnimator = checkNotNull(view.privateField("flipAnimator") as android.animation.ValueAnimator?)
            firstAnimator.end()
            assertEquals(fixture.headingPageIndex, view.currentPageIndex())
            assertEquals(1, view.privateInt("queuedPageTurnDelta"))
            assertEquals("the queued target must request DISPLAY promotion", 1, executor.queuedTaskCount)

            executor.runNext()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, decodeAttempts)
            assertEquals("the first DISPLAY miss must leave exactly one bounded retry", 1, executor.queuedTaskCount)
            assertEquals(1, view.privateInt("queuedPageTurnDelta"))

            shadowOf(Looper.getMainLooper()).idleFor(320L, TimeUnit.MILLISECONDS)
            assertEquals(
                "the active admission tick must not duplicate an in-flight retry",
                1,
                executor.queuedTaskCount,
            )
            assertEquals(2, decodeAttempts)

            executor.runNext()
            shadowOf(Looper.getMainLooper()).idle()
            view.textView.viewTreeObserver.dispatchOnPreDraw()
            view.drainPendingPageTexturePrecacheForTest()

            assertEquals(
                "the real second failure callback must release the queued turn on retained pixels",
                fixture.imagePageIndex,
                view.currentPageIndex(),
            )
            assertEquals(0, view.privateInt("queuedPageTurnDelta"))
            assertEquals(3, decodeAttempts)
            assertTrue(loader.hasStablePixels(listOf(fixture.imageLayoutStart)))
            assertEquals(0, executor.queuedTaskCount)

            shadowOf(Looper.getMainLooper()).idleFor(960L, TimeUnit.MILLISECONDS)
            assertEquals("terminal failure must stop later 320ms admission ticks", 3, decodeAttempts)
            assertEquals(0, executor.queuedTaskCount)
        } finally {
            loader.releaseAll(view)
            executor.shutdownNow()
            epub.delete()
            view.dispose()
        }
    }

    @Test
    fun `resize and dispose release a captured rapid follow-up page shot`() {
        data class Scenario(
            val name: String,
            val mutate: (EpubFlowView) -> Unit,
        )
        val scenarios = listOf(
            Scenario("resize") { view ->
                view.measure(exactly(view.width), exactly(view.height + 20))
                view.layout(0, 0, view.width, view.height + 20)
            },
            Scenario("dispose", EpubFlowView::dispose),
        )

        scenarios.forEach { scenario ->
            val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
            try {
                assertTrue("scenario=${scenario.name}", view.goToAdjacentPage(1))
                assertTrue("scenario=${scenario.name}", view.goToAdjacentPage(1))
                shadowOf(Looper.getMainLooper()).idleFor(40L, TimeUnit.MILLISECONDS)
                val request = checkNotNull(view.privateField("rapidFollowUpPageShot")) {
                    "scenario=${scenario.name} must create the speculative owner"
                }
                val bitmap = checkNotNull(request.reflectedField("bitmap") as Bitmap?) {
                    "scenario=${scenario.name} must finish its delayed capture"
                }

                scenario.mutate(view)

                assertNull("scenario=${scenario.name}", view.privateField("rapidFollowUpPageShot"))
                assertNull("scenario=${scenario.name}", view.privateField("captureRapidFollowUpRunnable"))
                assertTrue("scenario=${scenario.name} must recycle the detached page shot", bitmap.isRecycled)
            } finally {
                view.dispose()
            }
        }
    }

    @Test
    fun `swipe released while paper animation is active queues another turn`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        assertTrue(view.goToAdjacentPage(1))

        val downTime = SystemClock.uptimeMillis()
        val y = view.height * 0.50f
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, y),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, view.width * 0.15f, y),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, view.width * 0.15f, y),
        )

        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

        assertEquals("the busy renderer must preserve the released swipe intent", 2, view.currentPageIndex())
    }

    @Test
    fun `edge tap while paper animation is active bypasses selection and queues another turn`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        assertTrue(view.goToAdjacentPage(1))

        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f
        assertTrue(view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)))
        assertTrue(view.dispatchTouchEvent(motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_UP, x, y)))

        assertEquals(
            true,
            view.textView.getTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed),
        )
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.SECONDS)

        assertEquals("the busy edge tap must be drained without entering selectable text", 2, view.currentPageIndex())
    }

    @Test
    fun `edge tap inside rapid idle window reuses prefetched simulation target`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
            assertTrue(view.goToAdjacentPage(1))
            assertNotNull("an ordinary SIMULATION turn must use the paper renderer", view.privateField("curlDrawable"))

            fun tapNext(eventTime: Long) {
                val x = view.width * 0.85f
                val y = view.height * 0.50f
                view.dispatchTouchEvent(motionEvent(eventTime, eventTime, MotionEvent.ACTION_DOWN, x, y))
                view.dispatchTouchEvent(motionEvent(eventTime, eventTime + 24L, MotionEvent.ACTION_UP, x, y))
            }

            val firstTapTime = SystemClock.uptimeMillis()
            tapNext(firstTapTime)
            shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)
            assertEquals(2, view.currentPageIndex())
            assertTrue(view.privateBool("rapidTurnSequenceActive"))
            EpubPageShotCaptureProbe.reset()

            tapNext(firstTapTime + 300L)

            val followUpAnimator = checkNotNull(
                view.privateField("flipAnimator") as? android.animation.ValueAnimator,
            )
            assertEquals("the target page stays parked beneath the visual transaction", 3, view.currentPageIndex())
            assertTrue(followUpAnimator.isRunning)
            assertEquals(280L, followUpAnimator.duration)
            val paper = checkNotNull(view.privateField("curlDrawable")) {
                "SIMULATION must keep the paper renderer throughout a rapid sequence"
            }
            assertNotNull(
                "a rapid follow-up must retain a frozen target page artifact",
                paper.reflectedField("revealedBitmap"),
            )
            assertEquals(
                "rapid input must prefetch its next text target before the follow-up starts",
                0,
                EpubPageShotCaptureProbe.total(),
            )
            assertNull("SIMULATION rapid follow-up must never degrade to slide", view.privateField("slideDrawable"))
        } finally {
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `rapid turn consumes an already cached current and target pair without recapture`() {
        EpubPageShotCaptureProbe.reset()
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > fixture.headingPageIndex + 1)
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()

            val cachedFront = checkNotNull(view.slideFrontArtifactForTest())
            val cachedTarget = checkNotNull(view.slideRevealedArtifactForTest())
            val capturesAfterWarm = EpubPageShotCaptureProbe.total()
            val targetWindow = (view.privateField("paged") as List<EpubFlowPage>)[fixture.imagePageIndex]

            view.setPrivateField("rapidTurnSequenceActive", true)
            val started = view.javaClass.getDeclaredMethod(
                "goToPageAnimated",
                EpubFlowPage::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
                .invoke(view, targetWindow, true) as Boolean

            assertTrue("rapid turn should start from the warm pair", started)
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertSame(
                "rapid current page must transfer the cached front artifact",
                cachedFront,
                slide.frontArtifactForTest(),
            )
            assertSame(
                "rapid target page must transfer the cached revealed artifact",
                cachedTarget,
                slide.revealedArtifactForTest(),
            )
            assertEquals(
                "an already valid rapid pair must not allocate another page shot",
                capturesAfterWarm,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            view.dispose()
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `rapid queue bootstraps a complete pair before starting a cold turn`() {
        EpubPageShotCaptureProbe.reset()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
            view.recycleCachedTexturesForTest()
            EpubPageShotCaptureProbe.reset()
            view.setPrivateField("rapidTurnSequenceActive", true)
            view.setPrivateField("queuedPageTurnDelta", 1)

            val drain = view.drainQueuedPageTurnForTest()

            assertFalse("a cold rapid queue must wait for a warm pair", drain)
            assertTrue(
                "the queue must bootstrap current+target artifact precache instead of taking cold shots",
                view.privateBool("slideArtifactPrecachePending"),
            )
            assertNull("no animation may start before the pair is committed", view.privateField("slideDrawable"))

            view.drainPendingPageTexturePrecacheForTest()

            assertNotNull(
                "the queued warm turn must start synchronously once the exact artifact pair commits",
                view.privateField("slideDrawable"),
            )
            assertEquals("the committed pair must advance the queue", 0, view.privateInt("queuedPageTurnDelta"))
            assertEquals(
                "the artifact bootstrap must not allocate any viewport page-shot bitmap",
                0,
                EpubPageShotCaptureProbe.total(),
            )
            assertEquals(1, view.currentPageIndex())
        } finally {
            view.dispose()
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `queued warm turn starts synchronously on precache commit without any record pass`() {
        EpubPageShotCaptureProbe.reset()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
            view.recycleCachedTexturesForTest()
            EpubPageShotCaptureProbe.reset()
            view.setPrivateField("rapidTurnSequenceActive", true)
            view.setPrivateField("queuedPageTurnDelta", 1)

            // An accepted rapid queue bootstraps the artifact pair and stays pending.
            assertFalse("a cold rapid queue must wait for a warm pair", view.drainQueuedPageTurnForTest())
            assertTrue(
                "the queue must bootstrap current+target artifact precache instead of taking cold shots",
                view.privateBool("slideArtifactPrecachePending"),
            )
            // The commit path drains the queued turn synchronously; no hidden alpha-0 record pass
            // or frame retry is part of the admission contract.
            view.drainPendingPageTexturePrecacheForTest()
            val slide = view.privateField("slideDrawable")
            assertNotNull("the committed warm pair must start the queued turn in the same commit frame", slide)
            assertEquals("the started turn must consume the queue", 0, view.privateInt("queuedPageTurnDelta"))
            assertEquals("the started turn must park on the adjacent page", 1, view.currentPageIndex())
            assertNotNull(
                "the started turn must own a current-page artifact frame",
                (slide as PageSlideDrawable).frontArtifactForTest(),
            )
            assertNotNull(
                "the started turn must own a target-page artifact frame",
                slide.revealedArtifactForTest(),
            )
            assertEquals(
                "a warm queued start must not allocate any viewport page-shot bitmap",
                0,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            view.dispose()
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `ready artifact pair admits synchronously with no persistent renderer record state`() {
        EpubPageShotCaptureProbe.reset()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
            val pages = view.privateField("paged") as List<EpubFlowPage>
            view.recycleCachedTexturesForTest()
            EpubPageShotCaptureProbe.reset()
            // A ready artifact pair is admitted purely from the exact-key slots; an absent
            // persistent renderer (and therefore its record state) must never gate it.
            val front = view.recordSlidePageArtifactForTest(pages[0].topPx, pages[0]) as SlidePageArtifact
            val revealed = view.recordSlidePageArtifactForTest(pages[1].topPx, pages[1]) as SlidePageArtifact
            view.installSlideArtifactSlotForTest("slideFrontSlot", pages[0], front)
            view.installSlideArtifactSlotForTest("slideRevealedSlot", pages[1], revealed)
            view.setPrivateField("rapidTurnSequenceActive", true)
            view.setPrivateField("queuedPageTurnDelta", 1)

            assertTrue(
                "rapid admission must accept the exact cached pair with no renderer in play",
                view.rapidSlideArtifactPairAvailableForTest(pages[1]),
            )
            assertTrue(
                "the queued warm turn must start synchronously from the ready pair",
                view.drainQueuedPageTurnForTest(),
            )
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertSame("the front slot artifact must transfer by identity", front, slide.frontArtifactForTest())
            assertSame("the revealed slot artifact must transfer by identity", revealed, slide.revealedArtifactForTest())
            assertEquals("the admitted turn must consume the queue", 0, view.privateInt("queuedPageTurnDelta"))
            assertEquals("the admitted turn must park on the adjacent page", 1, view.currentPageIndex())
            assertEquals(
                "admission from a ready pair must not allocate any viewport page-shot bitmap",
                0,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            view.dispose()
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `rapid slide reversal discards the obsolete directional precache and re-arms the reverse pair`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        try {
            view.goToPage(1)
            view.recycleCachedTexturesForTest()
            val pages = view.privateField("paged") as List<EpubFlowPage>
            // A forward queued turn arms a directional precache whose backward window may be absent
            // (e.g. boundary-budget suppression). Mirror that exact state before the queue reverses.
            view.armPendingSlideArtifactPrecacheForTest(fromPageIndex = 1, targetPageIndex = 2)
            val forwardPending = checkNotNull(view.privateField("pendingSlideArtifactPrecache"))
            assertTrue(
                "the armed forward pending must stay valid before the direction change",
                view.pendingSlideArtifactPrecacheIsValidForTest(),
            )

            view.setPrivateField("rapidTurnSequenceActive", true)
            view.setPrivateField("queuedPageTurnDelta", -1)
            val retriesBefore = view.privateInt("rapidTurnPairBootstrapRetries")

            // Draining with the reversed queue must not trust the stale forward pending: it is
            // discarded and replaced by the exact reverse-direction request on the same attempt.
            assertFalse("a cold reverse queue must wait for its own warm pair", view.drainQueuedPageTurnForTest())
            val rearmedPending = checkNotNull(view.privateField("pendingSlideArtifactPrecache"))
            assertTrue(
                "the stale forward request must be replaced by a fresh reverse request",
                rearmedPending !== forwardPending,
            )
            assertNull(
                "the obsolete forward request must no longer own its recorded artifacts",
                forwardPending.reflectedField("frontArtifact"),
            )
            assertNull(
                "the obsolete forward request must no longer own its recorded target artifact",
                forwardPending.reflectedField("targetArtifact"),
            )
            val reversePrevious = rearmedPending.reflectedField("previousWindow")
            val reverseTarget = rearmedPending.reflectedField("targetWindow")
            assertTrue(
                "the re-armed pending must contain the exact reverse neighbor; " +
                    "previous=$reversePrevious target=$reverseTarget",
                reversePrevious === pages[0] || reverseTarget === pages[0],
            )
            assertNull("no animation may start before the reverse pair commits", view.privateField("slideDrawable"))
            assertEquals("the reversed queue must stay pending until the reverse pair is ready", -1, view.privateInt("queuedPageTurnDelta"))
            assertEquals(
                "a stale-direction discard must not consume the bootstrap retry budget",
                retriesBefore,
                view.privateInt("rapidTurnPairBootstrapRetries"),
            )

            // The fresh reverse chain commits and drains the queued reverse turn on its own commit.
            view.drainPendingPageTexturePrecacheForTest()
            assertEquals("the committed reverse pair must consume the queue", 0, view.privateInt("queuedPageTurnDelta"))
            assertEquals("the committed reverse pair must park on the reverse neighbor", 0, view.currentPageIndex())
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertNotNull("the reverse turn must own a current-page artifact frame", slide.frontArtifactForTest())
            assertNotNull("the reverse turn must own the reverse-neighbor artifact frame", slide.revealedArtifactForTest())
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `direct page texture precache waits for the rapid idle window`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)

        assertEquals(2, view.currentPageIndex())
        assertTrue(view.privateBool("rapidTurnSequenceActive"))
        assertFalse((view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.isRunning == true)
        view.recycleCachedTexturesForTest()

        view.preCachePageTexturesForTest(idlePostedWork = false)

        assertFalse(
            "a lifecycle precache must not start another page-shot chain inside the rapid window",
            view.privateBool("pageTexturePrecachePending"),
        )
        assertNull(view.privateField("cachedFrontBitmap"))

        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        view.drainPendingPageTexturePrecacheForTest()

        assertFalse(view.privateBool("rapidTurnSequenceActive"))
        assertNotNull("the final parked page should warm after rapid idle", view.privateField("cachedFrontBitmap"))
    }

    @Test
    fun `long press started inside rapid idle window does not become an edge tap`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)

        assertEquals(2, view.currentPageIndex())
        assertTrue(view.privateBool("rapidTurnSequenceActive"))
        assertFalse((view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.isRunning == true)

        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val y = view.height * 0.50f
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        shadowOf(Looper.getMainLooper()).idleFor(
            (ViewConfiguration.getLongPressTimeout() + 50).toLong(),
            TimeUnit.MILLISECONDS,
        )
        view.dispatchTouchEvent(
            motionEvent(
                downTime,
                downTime + ViewConfiguration.getLongPressTimeout() + 60L,
                MotionEvent.ACTION_UP,
                x,
                y,
            ),
        )

        assertEquals("a text long press must not turn the rapid sequence again", 2, view.currentPageIndex())
    }

    @Test
    fun `swipe released inside rapid idle window starts a visual follow-up settle`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)

        assertEquals(2, view.currentPageIndex())
        assertTrue(view.privateBool("rapidTurnSequenceActive"))
        val downTime = SystemClock.uptimeMillis()
        val y = view.height * 0.50f
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, y),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, view.width * 0.15f, y),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, view.width * 0.15f, y),
        )

        val followUpAnimator = checkNotNull(
            view.privateField("flipAnimator") as? android.animation.ValueAnimator,
        )
        assertEquals(3, view.currentPageIndex())
        assertTrue(followUpAnimator.isRunning)
        assertEquals(280L, followUpAnimator.duration)
        assertNotNull("rapid SIMULATION swipe must keep PAPER", view.privateField("curlDrawable"))
        assertNull("rapid SIMULATION swipe must not install slide", view.privateField("slideDrawable"))
    }

    @Test
    fun `rapid idle swipe release keeps outgoing artifact on first frame and uses full follow-up duration`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        val outgoingColor = 0xFF16A34A.toInt()
        try {
            assertTrue(view.goToAdjacentPage(1))
            assertTrue(view.goToAdjacentPage(1))
            shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)

            assertEquals(2, view.currentPageIndex())
            assertTrue(view.privateBool("rapidTurnSequenceActive"))
            assertFalse((view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.isRunning == true)

            val downTime = SystemClock.uptimeMillis()
            val y = view.height * 0.50f
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, view.width * 0.85f, y),
            )
            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, view.width * 0.15f, y),
            )

            val outgoing = checkNotNull(view.privateField("cachedFrontBitmap") as? Bitmap) {
                "rapid idle settle must retain a live outgoing artifact before release"
            }
            outgoing.eraseColor(outgoingColor)
            assertFalse("the held outgoing artifact must be live before release", outgoing.isRecycled)
            assertEquals(outgoingColor, outgoing.getPixel(0, 0))

            view.dispatchTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, view.width * 0.15f, y),
            )

            val followUpAnimator = checkNotNull(
                view.privateField("flipAnimator") as? android.animation.ValueAnimator,
            )
            val firstFramePaper = checkNotNull(
                view.privateField("curlDrawable") as? PageCurlDrawable,
            )
            val firstFrameOutgoing = firstFramePaper.privateBitmap("frontBitmap")
            assertSame(
                "the first released frame must retain the held outgoing artifact",
                outgoing,
                firstFrameOutgoing,
            )
            assertFalse(
                "the first released frame must keep its outgoing artifact unrecycled",
                firstFrameOutgoing.isRecycled,
            )
            assertEquals(
                "the first released frame must still contain outgoing content",
                outgoingColor,
                firstFrameOutgoing.getPixel(0, 0),
            )
            assertEquals("rapid follow-up animation must use the ordinary 280ms duration", 280L, followUpAnimator.duration)
        } finally {
            (view.privateField("flipAnimator") as? android.animation.ValueAnimator)?.end()
            view.dispose()
        }
    }

    @Test
    fun `async geometry reflow preserves an accepted rapid turn`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
        view.pendingDecodesProvider = { true }

        assertTrue(view.goToAdjacentPage(1))
        assertTrue(view.goToAdjacentPage(1))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals(1, view.currentPageIndex())
        assertEquals(1, view.privateInt("queuedPageTurnDelta"))
        view.pendingDecodesProvider = { false }
        val currentLayoutHeight = requireNotNull(view.textView.layout).height
        view.setPrivateField("paginatedLayoutHeight", currentLayoutHeight - 1)

        view.runReflowRunnable(idlePostedWork = false)

        assertEquals("reflow must not discard an input that was already accepted", 1, view.privateInt("queuedPageTurnDelta"))
        assertTrue(view.privateBool("rapidTurnSequenceActive"))
        shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

        assertEquals(2, view.currentPageIndex())
        assertEquals(0, view.privateInt("queuedPageTurnDelta"))
    }

    @Test
    fun `queued rapid idle defers layout reflow until the sequence settles`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            val generationBefore = view.privateField("pageLayoutGeneration") as Long
            val layoutHeight = requireNotNull(view.textView.layout).height
            view.setPrivateField("paginatedLayoutHeight", layoutHeight - 1)
            view.setPrivateField("rapidTurnSequenceActive", true)
            view.setPrivateField("queuedPageTurnDelta", 1)

            view.runReflowRunnable(idlePostedWork = false)

            assertEquals(
                "queued rapid input must keep the reflow pending instead of rebuilding pagination between turns",
                generationBefore,
                view.privateField("pageLayoutGeneration") as Long,
            )
            assertEquals(layoutHeight - 1, view.privateInt("paginatedLayoutHeight"))
        } finally {
            view.dispose()
        }
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
        assertMotionPageShotSize(view, paper.privateBitmap("frontBitmap"))
        assertMotionPageShotSize(view, paper.privateBitmap("revealedBitmap"))
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
        assertMotionPageShotSize(view, paper.privateBitmap("frontBitmap"))
        assertMotionPageShotSize(view, paper.privateBitmap("revealedBitmap"))
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
        assertMotionPageShotSize(view, paper.privateBitmap("frontBitmap"))
        assertMotionPageShotSize(view, paper.privateBitmap("revealedBitmap"))
        assertTrue("partial revealed texture should be recycled before live snapshot", partialRevealed.isRecycled)
    }

    @Test
    fun `precache refreshes recycled cached textures even when top keys match`() {
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

        view.preCachePageTexturesForTest()

        val cachedFront = view.privateField("cachedFrontBitmap") as Bitmap?
        val cachedRevealed = view.privateField("cachedRevealedBitmap") as Bitmap?
        assertTrue("precache should replace the recycled front texture", cachedFront !== recycledFront)
        assertTrue("precache should replace the recycled revealed texture", cachedRevealed !== recycledRevealed)
        assertTrue("precache should create a live front texture", cachedFront != null && !cachedFront.isRecycled)
        assertTrue("precache should create a live revealed texture", cachedRevealed != null && !cachedRevealed.isRecycled)
        assertMotionPageShotSize(view, checkNotNull(cachedFront))
        assertMotionPageShotSize(view, checkNotNull(cachedRevealed))
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
    fun `partial staged front survives real finger MOVE and seeds local handoff without front recapture`() {
        // Product contract: real touch MOVE with a matching staged front but missing directional
        // target must retain that front as pendingLocalPageShotHandoff.frontBitmap (LOCAL_SHOTS_WAITING)
        // and later install the overlay with that exact identity after only one additional target draw.
        val background = RecordingTargetBitmapDrawable()
        // The partial-front seeding contract lives on the Bitmap precache path; SIMULATION keeps
        // the split-frame Bitmap staging and deferred MOVE handoff unchanged.
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()

        val downX = view.width * 0.85f
        val moveX = view.width * 0.55f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            // Arm split-frame precache without idling all work.
            view.preCachePageTexturesForTest(idlePostedWork = false)
            // Exactly the first precache frame: matching current-page front, no directional target yet.
            shadowOf(Looper.getMainLooper()).runOneTask()
            val pending = checkNotNull(view.privateField("pendingPageTexturePrecache")) {
                "first precache frame must leave a staged pending request"
            }
            val stagedFront = checkNotNull(pending.reflectedField("frontBitmap") as Bitmap?) {
                "first precache frame must capture a live front bitmap"
            }
            assertFalse("staged front must be live after the first precache frame", stagedFront.isRecycled)
            assertNull(
                "directional target must be missing after only the front frame",
                pending.reflectedField("targetBitmap"),
            )
            assertEquals(
                "only the staged front draw may have occurred before the gesture",
                1,
                background.targetBitmaps.size,
            )

            // Real threshold-crossing finger MOVE (not the direct reflection overload).
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )

            assertEquals(
                "partial-front gesture must enter deferred local handoff",
                "LOCAL_SHOTS_WAITING",
                view.privateField("interactiveTurnState").toString(),
            )
            checkNotNull(view.privateField("pendingLocalPageShotHandoff")) {
                "LOCAL_SHOTS_WAITING must own a pending local handoff"
            }
            assertFalse(
                "real finger MOVE must retain the matching staged front instead of recycling it " +
                    "before the deferred target capture; staged=$stagedFront",
                stagedFront.isRecycled,
            )
            assertNull(
                "front-only extract must clear the incomplete pending precache",
                view.privateField("pendingPageTexturePrecache"),
            )
            assertEquals(
                "threshold MOVE must not allocate the missing target yet",
                1,
                background.targetBitmaps.size,
            )

            // Queued frames: target capture, then resume with the seeded front (no front recapture).
            shadowOf(Looper.getMainLooper()).runOneTask()
            assertNull(
                "target-only preparation frame must not install the slide overlay yet",
                view.privateField("slideDrawable"),
            )
            val handoffAfterTarget = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            assertFalse(
                "staged front must stay live while the target shot is pending",
                stagedFront.isRecycled,
            )
            assertNotNull(
                "first handoff frame must capture the directional target",
                handoffAfterTarget.reflectedField("targetBitmap"),
            )

            shadowOf(Looper.getMainLooper()).runOneTask()
            val paper = checkNotNull(view.privateField("curlDrawable") as PageCurlDrawable?) {
                "seeded SIMULATION handoff must install the local paper overlay after target + front resolution"
            }
            assertTrue(
                "local paper overlay must use the exact staged front identity; " +
                    "paperFront=${paper.privateBitmap("frontBitmap")} staged=$stagedFront " +
                    "recycled=${stagedFront.isRecycled}",
                paper.privateBitmap("frontBitmap") === stagedFront && !stagedFront.isRecycled,
            )
            assertEquals(
                "only one additional full page-shot draw (target) may occur after the staged front; " +
                    "draws=${background.targetBitmaps.size}",
                2,
                background.targetBitmaps.size,
            )
            assertTrue(
                "overlay revealed must be the newly captured target identity",
                paper.privateBitmap("revealedBitmap") === background.targetBitmaps[1],
            )
        } finally {
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `seeded front ACTION_CANCEL recycles staged front and blocks stale target capture`() {
        val background = RecordingTargetBitmapDrawable()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)
        val chargedBefore = budget.chargedBytes
        val leasedBefore = budget.leasedBytes

        val downX = view.width * 0.85f
        val moveX = view.width * 0.55f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.preCachePageTexturesForTest(idlePostedWork = false)
            shadowOf(Looper.getMainLooper()).runOneTask()
            val pending = checkNotNull(view.privateField("pendingPageTexturePrecache"))
            val stagedFront = checkNotNull(pending.reflectedField("frontBitmap") as Bitmap?)
            assertFalse(stagedFront.isRecycled)
            assertNull(pending.reflectedField("targetBitmap"))
            assertEquals(1, background.targetBitmaps.size)
            val chargedAfterStage = budget.chargedBytes

            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )

            assertEquals("LOCAL_SHOTS_WAITING", view.privateField("interactiveTurnState").toString())
            val handoff = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            assertTrue(
                "handoff must own the exact staged front identity",
                handoff.reflectedField("frontBitmap") === stagedFront && !stagedFront.isRecycled,
            )
            assertNull(handoff.reflectedField("targetBitmap"))
            assertNull(view.privateField("pendingPageTexturePrecache"))
            assertEquals(1, background.targetBitmaps.size)
            val dequeuedTarget = checkNotNull(
                view.privateField("capturePendingLocalTargetRunnable") as Runnable?,
            ) {
                "seeded handoff must queue the deferred target capture"
            }

            view.onTouchEvent(
                motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y),
            )

            assertTrue(
                "ACTION_CANCEL must recycle the seeded front and clear handoff/overlay/callbacks; " +
                    "frontRecycled=${stagedFront.isRecycled} " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "handoff=${view.privateField("pendingLocalPageShotHandoff")} " +
                    "slide=${view.privateField("slideDrawable")} curl=${view.privateField("curlDrawable")} " +
                    "targetCb=${view.privateField("capturePendingLocalTargetRunnable")} " +
                    "frontCb=${view.privateField("capturePendingLocalFrontRunnable")} " +
                    "charged=${budget.chargedBytes} leased=${budget.leasedBytes} " +
                    "beforeStage charged=$chargedBefore leased=$leasedBefore stagedCharged=$chargedAfterStage",
                stagedFront.isRecycled &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("pendingLocalPageShotHandoff") == null &&
                    view.privateField("slideDrawable") == null &&
                    view.privateField("curlDrawable") == null &&
                    view.privateField("capturePendingLocalTargetRunnable") == null &&
                    view.privateField("capturePendingLocalFrontRunnable") == null &&
                    budget.leasedBytes <= leasedBefore &&
                    budget.chargedBytes <= chargedBefore &&
                    budget.leasedBytes < chargedAfterStage,
            )

            val drawsBeforeStale = background.targetBitmaps.toList()
            dequeuedTarget.run()
            shadowOf(Looper.getMainLooper()).runOneTask()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)

            assertTrue(
                "a captured stale target runnable must not allocate or revive overlay/state; " +
                    "drawsBefore=$drawsBeforeStale drawsAfter=${background.targetBitmaps} " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "handoff=${view.privateField("pendingLocalPageShotHandoff")} " +
                    "slide=${view.privateField("slideDrawable")} curl=${view.privateField("curlDrawable")}",
                background.targetBitmaps == drawsBeforeStale &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("pendingLocalPageShotHandoff") == null &&
                    view.privateField("slideDrawable") == null &&
                    view.privateField("curlDrawable") == null &&
                    stagedFront.isRecycled,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `seeded front invalidation recycles seed and target and ignores stale resume`() {
        val background = RecordingTargetBitmapDrawable()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)

        val downX = view.width * 0.85f
        val moveX = view.width * 0.55f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.preCachePageTexturesForTest(idlePostedWork = false)
            shadowOf(Looper.getMainLooper()).runOneTask()
            val stagedFront = checkNotNull(
                (view.privateField("pendingPageTexturePrecache") as Any)
                    .reflectedField("frontBitmap") as Bitmap?,
            )

            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )
            val handoff = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            assertTrue(handoff.reflectedField("frontBitmap") === stagedFront)

            // Capture directional target so the request owns both seed and target.
            shadowOf(Looper.getMainLooper()).runOneTask()
            val handoffAfterTarget = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            val target = checkNotNull(handoffAfterTarget.reflectedField("targetBitmap") as Bitmap?) {
                "first handoff frame must capture the directional target"
            }
            assertTrue(
                handoffAfterTarget.reflectedField("frontBitmap") === stagedFront &&
                    !stagedFront.isRecycled &&
                    !target.isRecycled,
            )
            assertNull("target frame must not install overlay yet", view.privateField("slideDrawable"))
            val dequeuedFrontResume = checkNotNull(
                view.privateField("capturePendingLocalFrontRunnable") as Runnable?,
            ) {
                "after target capture the resume-with-seeded-front frame must be queued"
            }
            val leasedWithBoth = budget.leasedBytes

            // Representative visual invalidator (same family as target-only retirement suite).
            view.background = ColorDrawable(0xFFF1E8D8.toInt())

            assertTrue(
                "visual invalidation must recycle both seeded front and captured target; " +
                    "frontRecycled=${stagedFront.isRecycled} targetRecycled=${target.isRecycled} " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "handoff=${view.privateField("pendingLocalPageShotHandoff")} " +
                    "slide=${view.privateField("slideDrawable")} curl=${view.privateField("curlDrawable")} " +
                    "leasedBefore=$leasedWithBoth leasedAfter=${budget.leasedBytes}",
                stagedFront.isRecycled &&
                    target.isRecycled &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("pendingLocalPageShotHandoff") == null &&
                    view.privateField("slideDrawable") == null &&
                    view.privateField("curlDrawable") == null &&
                    budget.leasedBytes < leasedWithBoth,
            )

            val drawsBeforeStale = background.targetBitmaps.toList()
            dequeuedFrontResume.run()
            shadowOf(Looper.getMainLooper()).runOneTask()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)

            assertTrue(
                "queued stale front/resume runnable must be harmless after invalidation; " +
                    "drawsBefore=$drawsBeforeStale drawsAfter=${background.targetBitmaps} " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "handoff=${view.privateField("pendingLocalPageShotHandoff")} " +
                    "slide=${view.privateField("slideDrawable")} curl=${view.privateField("curlDrawable")}",
                background.targetBitmaps == drawsBeforeStale &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("pendingLocalPageShotHandoff") == null &&
                    view.privateField("slideDrawable") == null &&
                    view.privateField("curlDrawable") == null &&
                    stagedFront.isRecycled &&
                    target.isRecycled,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `seeded front new down supersedes A and only replacement B survives`() {
        val reportedOffsets = mutableListOf<Int>()
        val background = RecordingTargetBitmapDrawable()
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SIMULATION,
            onTopOffsetChanged = reportedOffsets::add,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        reportedOffsets.clear()
        val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)

        val y = view.height * 0.10f
        val aDownX = view.width * 0.85f
        val aMoveX = view.width * 0.55f
        val aDownTime = SystemClock.uptimeMillis()
        val bDownX = view.width * 0.15f
        val bMoveX = view.width * 0.75f
        val bDownTime = aDownTime + 100L

        try {
            view.preCachePageTexturesForTest(idlePostedWork = false)
            shadowOf(Looper.getMainLooper()).runOneTask()
            val stagedFront = checkNotNull(
                (view.privateField("pendingPageTexturePrecache") as Any)
                    .reflectedField("frontBitmap") as Bitmap?,
            )

            view.dispatchTouchEvent(motionEvent(aDownTime, aDownTime, MotionEvent.ACTION_DOWN, aDownX, y))
            assertTrue(
                "gesture A must enter seeded forward handoff",
                view.onInterceptTouchEvent(
                    motionEvent(aDownTime, aDownTime + 24L, MotionEvent.ACTION_MOVE, aMoveX, y),
                ),
            )
            val aHandoff = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            assertTrue(
                aHandoff.reflectedField("frontBitmap") === stagedFront && !stagedFront.isRecycled,
            )
            val dequeuedATarget = checkNotNull(
                view.privateField("capturePendingLocalTargetRunnable") as Runnable?,
            )

            // New DOWN synchronously retires A before B classification.
            view.dispatchTouchEvent(motionEvent(bDownTime, bDownTime, MotionEvent.ACTION_DOWN, bDownX, y))
            val bOriginPage = view.currentPageIndex()
            val bOriginTop = view.scrollY

            assertTrue(
                "new DOWN must recycle A's seeded front and clear handoff before B classifies; " +
                    "frontRecycled=${stagedFront.isRecycled} " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "handoff=${view.privateField("pendingLocalPageShotHandoff")} " +
                    "slide=${view.privateField("slideDrawable")} curl=${view.privateField("curlDrawable")} " +
                    "reports=$reportedOffsets",
                stagedFront.isRecycled &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("pendingLocalPageShotHandoff") == null &&
                    view.privateField("slideDrawable") == null &&
                    view.privateField("curlDrawable") == null &&
                    reportedOffsets.isEmpty(),
            )

            assertTrue(
                "gesture B must classify its own turn from the new anchor",
                view.onInterceptTouchEvent(
                    motionEvent(bDownTime, bDownTime + 24L, MotionEvent.ACTION_MOVE, bMoveX, y),
                ),
            )
            val bHandoff = checkNotNull(view.privateField("pendingLocalPageShotHandoff")) {
                "gesture B must own the current handoff after classification"
            }
            assertTrue(
                "only B may own the current handoff; A's front must stay recycled",
                view.privateField("pendingLocalPageShotHandoff") === bHandoff &&
                    stagedFront.isRecycled &&
                    view.privateField("interactiveTurnState").toString() == "LOCAL_SHOTS_WAITING",
            )
            val bFrontBeforeStale = bHandoff.reflectedField("frontBitmap")
            val bTargetBeforeStale = bHandoff.reflectedField("targetBitmap")
            val drawsBeforeStaleA = background.targetBitmaps.toList()
            val leasedBeforeStaleA = budget.leasedBytes

            dequeuedATarget.run()

            assertTrue(
                "stale A target callback must not mutate or revive B; " +
                    "sameOwner=${view.privateField("pendingLocalPageShotHandoff") === bHandoff} " +
                    "front=${bHandoff.reflectedField("frontBitmap")} " +
                    "target=${bHandoff.reflectedField("targetBitmap")} " +
                    "drawsBefore=$drawsBeforeStaleA drawsAfter=${background.targetBitmaps} " +
                    "state=${view.privateField("interactiveTurnState")} " +
                    "slide=${view.privateField("slideDrawable")} curl=${view.privateField("curlDrawable")} " +
                    "aFrontRecycled=${stagedFront.isRecycled} reports=$reportedOffsets",
                view.privateField("pendingLocalPageShotHandoff") === bHandoff &&
                    bHandoff.reflectedField("frontBitmap") === bFrontBeforeStale &&
                    bHandoff.reflectedField("targetBitmap") === bTargetBeforeStale &&
                    background.targetBitmaps == drawsBeforeStaleA &&
                    budget.leasedBytes == leasedBeforeStaleA &&
                    view.privateField("interactiveTurnState").toString() == "LOCAL_SHOTS_WAITING" &&
                    view.privateField("slideDrawable") == null &&
                    view.privateField("curlDrawable") == null &&
                    stagedFront.isRecycled &&
                    reportedOffsets.isEmpty(),
            )

            view.onTouchEvent(
                motionEvent(bDownTime, bDownTime + 48L, MotionEvent.ACTION_CANCEL, bMoveX, y),
            )
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

            assertTrue(
                "CANCEL of B must restore origin without reviving A; " +
                    "origin=[$bOriginPage,$bOriginTop] final=[${view.currentPageIndex()},${view.scrollY}] " +
                    "state=${view.privateField("interactiveTurnState")} reports=$reportedOffsets",
                view.currentPageIndex() == bOriginPage &&
                    view.scrollY == bOriginTop &&
                    view.privateField("interactiveTurnState").toString() == "NONE" &&
                    view.privateField("pendingLocalPageShotHandoff") == null &&
                    view.privateField("slideDrawable") == null &&
                    view.privateField("curlDrawable") == null &&
                    stagedFront.isRecycled &&
                    reportedOffsets.isEmpty(),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `partial front with opposite neighbor seeds handoff and recycles opposite on missing direction MOVE`() {
        // Stage front + forward (opposite of a backward request) while backward previous is missing.
        // Real backward MOVE must retain front for local handoff and recycle the staged forward neighbor.
        val background = RecordingTargetBitmapDrawable()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.goToPage(1)
        view.background = background
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        background.targetBitmaps.clear()
        val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)

        val downX = view.width * 0.15f
        val moveX = view.width * 0.45f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()

        try {
            view.preCachePageTexturesForTest(idlePostedWork = false)
            // Frame 1: current front.
            shadowOf(Looper.getMainLooper()).runOneTask()
            val pendingAfterFront = checkNotNull(view.privateField("pendingPageTexturePrecache"))
            val stagedFront = checkNotNull(pendingAfterFront.reflectedField("frontBitmap") as Bitmap?)
            assertNull(pendingAfterFront.reflectedField("targetBitmap"))
            assertNull(pendingAfterFront.reflectedField("previousBitmap"))

            // Frame 2: forward neighbor (opposite of the upcoming backward request).
            shadowOf(Looper.getMainLooper()).runOneTask()
            val pendingAfterOpposite = checkNotNull(view.privateField("pendingPageTexturePrecache")) {
                "after front+forward frames the precache must still be incomplete (previous missing)"
            }
            val stagedOpposite = checkNotNull(
                pendingAfterOpposite.reflectedField("targetBitmap") as Bitmap?,
            ) {
                "second precache frame must stage the forward neighbor"
            }
            assertTrue(
                pendingAfterOpposite.reflectedField("frontBitmap") === stagedFront &&
                    !stagedFront.isRecycled &&
                    !stagedOpposite.isRecycled,
            )
            assertNull(
                "previous/backward neighbor must still be missing",
                pendingAfterOpposite.reflectedField("previousBitmap"),
            )
            assertEquals(2, background.targetBitmaps.size)
            val leasedWithOpposite = budget.leasedBytes

            // Real threshold MOVE in the missing (backward) direction.
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )

            assertEquals(
                "missing-direction MOVE must enter deferred local handoff",
                "LOCAL_SHOTS_WAITING",
                view.privateField("interactiveTurnState").toString(),
            )
            val handoff = checkNotNull(view.privateField("pendingLocalPageShotHandoff"))
            assertTrue(
                "front must seed local handoff while opposite neighbor is released; " +
                    "frontLive=${!stagedFront.isRecycled} oppositeRecycled=${stagedOpposite.isRecycled} " +
                    "handoffFront=${handoff.reflectedField("frontBitmap")} " +
                    "pending=${view.privateField("pendingPageTexturePrecache")} " +
                    "draws=${background.targetBitmaps.size} " +
                    "leasedBefore=$leasedWithOpposite leasedAfter=${budget.leasedBytes}",
                handoff.reflectedField("frontBitmap") === stagedFront &&
                    !stagedFront.isRecycled &&
                    stagedOpposite.isRecycled &&
                    view.privateField("pendingPageTexturePrecache") == null &&
                    background.targetBitmaps.size == 2 &&
                    budget.leasedBytes < leasedWithOpposite,
            )
            assertNull(handoff.reflectedField("targetBitmap"))
            assertNull(view.privateField("slideDrawable"))
            assertNull(view.privateField("curlDrawable"))
        } finally {
            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.dispose()
        }
    }

    @Test
    fun `large viewport full RGB565 precache keeps all three directional owners within 32 MiB`() {
        val oppositeCapBytes = 32L * 1024L * 1024L
        val budget = PageShotBudget(oppositeCapBytes)
        val view = pagedFlowView(
            text = (1..800).joinToString("\n") { "Line $it marker text." },
            viewportWidth = 1_600,
            viewportHeight = 2_560,
            pageShotBudget = budget,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        view.goToPage(1)
        view.recycleCachedTexturesForTest()

        view.preCachePageTexturesForTest()

        val current = view.privateField("cachedFrontBitmap") as Bitmap?
        val forward = view.privateField("cachedRevealedBitmap") as Bitmap?
        val opposite = view.privateField("cachedBackwardBitmap") as Bitmap?
        val owners = listOfNotNull(current, forward, opposite)
        assertEquals("motion shots must keep current, previous, and next warm", 3, owners.size)
        owners.forEach { bitmap ->
            assertEquals(Bitmap.Config.RGB_565, bitmap.config)
            assertMotionPageShotSize(view, bitmap)
        }
        val threeMotionRgb565Bytes =
            view.privateNoArgInt("motionPageShotWidthPx").toLong() *
                view.privateNoArgInt("motionPageShotHeightPx").toLong() *
                2L * 3L
        assertEquals(threeMotionRgb565Bytes, budget.leasedBytes)
        assertTrue("three motion RGB565 owners must fit the opposite cache cap", budget.leasedBytes <= oppositeCapBytes)
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
    fun `chapter entry precache warms current, previous, and next frozen artifacts`() {
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
        )
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow
        val chapterText = view.textView.text

        try {
            view.recycleCachedTexturesForTest()
            EpubPageShotCaptureProbe.reset()
            view.setChapter(
                flow,
                chapterText,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            assertEquals("fixture must restore chapter entry to a middle page", 1, view.currentPageIndex())
            val frontSlot = view.slideArtifactSlotForTest("slideFrontSlot")
            val backwardSlot = view.slideArtifactSlotForTest("slideBackwardSlot")
            val revealedSlot = view.slideArtifactSlotForTest("slideRevealedSlot")
            assertNotNull("chapter entry must warm current", frontSlot)
            assertNotNull("chapter entry must warm previous", backwardSlot)
            assertNotNull("chapter entry must warm next", revealedSlot)
            assertEquals(1, checkNotNull(frontSlot).reflectedField("page"))
            assertEquals(0, checkNotNull(backwardSlot).reflectedField("page"))
            assertEquals(2, checkNotNull(revealedSlot).reflectedField("page"))
            assertNotNull("current slot must carry an exact artifact key", checkNotNull(frontSlot).reflectedField("key"))
            assertNotNull("previous slot must carry an exact artifact key", checkNotNull(backwardSlot).reflectedField("key"))
            assertNotNull("next slot must carry an exact artifact key", checkNotNull(revealedSlot).reflectedField("key"))
            assertTrue(checkNotNull(view.slideFrontArtifactForTest()).slideArtifactRecordIsLive())
            assertTrue(checkNotNull(view.slideBackwardArtifactForTest()).slideArtifactRecordIsLive())
            assertTrue(checkNotNull(view.slideRevealedArtifactForTest()).slideArtifactRecordIsLive())
            assertEquals(
                "warm SLIDE chapter entry must not allocate any viewport page-shot bitmap",
                0,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            EpubPageShotCaptureProbe.stop()
            view.dispose()
        }
    }

    @Test
    fun `chapter entry warm turns install a fresh direct artifact overlay without any record pass`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow

        try {
            view.recycleCachedTexturesForTest()
            view.setChapter(
                flow,
                view.textView.text,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()
            EpubPageShotCaptureProbe.reset()

            val front = checkNotNull(view.slideFrontArtifactForTest())
            val backward = checkNotNull(view.slideBackwardArtifactForTest())
            val revealed = checkNotNull(view.slideRevealedArtifactForTest())
            assertEquals(0, EpubPageShotCaptureProbe.total())

            // Reverse warm turn (page 1 -> page 0) admits synchronously from the exact slots.
            assertTrue(view.beginInteractiveCurl(forward = false, anchorX = 0f))
            val reverseSlide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertSame("reverse turn must own the current-page artifact", front, reverseSlide.frontArtifactForTest())
            assertSame("reverse turn must own the previous-page artifact", backward, reverseSlide.revealedArtifactForTest())
            val reverseRenderer = view.requireActiveSlideRendererView()
            view.updateInteractiveCurl(x = view.width * 0.10f)
            view.endInteractiveCurl(velocityX = 0f)
            val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
            animator?.end()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            view.drainPendingPageTexturePrecacheForTest()
            assertNull("the settled reverse turn must detach its overlay", reverseRenderer.parent)
            assertNull("the settled reverse turn must clear the active overlay pointer", view.privateField("slideOverlayView"))
            assertSame(
                "the settled reverse turn must rekey the revealed p0 artifact as current",
                backward,
                view.slideFrontArtifactForTest(),
            )
            assertSame(
                "the settled reverse turn must keep the outgoing p1 artifact as the forward target",
                front,
                view.slideRevealedArtifactForTest(),
            )
            assertNull(
                "the settled reverse turn must not re-arm a backward slot at the chapter boundary",
                view.slideBackwardArtifactForTest(),
            )

            // Forward warm turn (page 1 -> page 2) installs a fresh direct overlay.
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val forwardSlide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            // After the reverse commit the live page is p0, so the forward p0 -> p1 turn uses the
            // rekeyed p0 artifact as outgoing and the rekeyed p1 artifact as revealed. The original
            // p2 artifact was discarded with the cache recycle and must not be expected to survive.
            assertSame("forward turn must own the rekeyed current p0 artifact", backward, forwardSlide.frontArtifactForTest())
            assertSame("forward turn must own the rekeyed next-page p1 artifact", front, forwardSlide.revealedArtifactForTest())
            val forwardRenderer = view.requireActiveSlideRendererView()
            assertTrue("each warm turn must install its own direct overlay", forwardRenderer !== reverseRenderer)
            view.updateInteractiveCurl(x = view.width * 0.90f)
            view.endInteractiveCurl(velocityX = 0f)
            val forwardAnimator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
            view.setPrivateField("rapidTurnSequenceActive", true)
            forwardAnimator?.end()
            view.setPrivateField("rapidTurnSequenceActive", false)
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            assertNull("the settled forward turn must detach its overlay", forwardRenderer.parent)
            assertEquals(
                "chapter-entry warm turns must never allocate a viewport page-shot bitmap",
                0,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            EpubPageShotCaptureProbe.stop()
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `direct slide overlay lays front and revealed pages at strip-local 0 and W`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        val context = RuntimeEnvironment.getApplication() as Application
        val w = view.width
        val h = view.height
        val frontColor = 0xFF16A34A.toInt()
        val revealedColor = 0xFF2563EB.toInt()
        val front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(frontColor) }
        val revealed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(revealedColor) }
        try {
            for (forward in listOf(true, false)) {
                val renderer = PageSlideOverlayView(
                    context = context,
                    frontFrame = SlidePageFrame.BitmapFrame(front),
                    revealedFrame = SlidePageFrame.BitmapFrame(revealed),
                    viewportW = w,
                    viewportH = h,
                    forward = forward,
                    density = view.resources.displayMetrics.density,
                )
                val stripLeft = if (forward) 0 else -w
                renderer.layout(stripLeft, 0, stripLeft + 2 * w, h)
                val frame = Bitmap.createBitmap(
                    renderer.width.coerceAtLeast(1),
                    renderer.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    renderer.draw(Canvas(frame))
                    val frontX = if (forward) w / 2 else w + w / 2
                    val revealedX = if (forward) w + w / 2 else w / 2
                    assertEquals("forward=$forward front band must carry the front Bitmap", frontColor, frame.getPixel(frontX, h / 2))
                    assertEquals("forward=$forward revealed band must carry the revealed Bitmap", revealedColor, frame.getPixel(revealedX, h / 2))
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            front.recycle()
            revealed.recycle()
            view.dispose()
        }
    }

    @Test
    fun `warm pair transfer is exact-key and direction-specific without consuming mismatches`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow

        try {
            view.recycleCachedTexturesForTest()
            view.setChapter(
                flow,
                view.textView.text,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            val front = checkNotNull(view.slideFrontArtifactForTest())
            val backward = checkNotNull(view.slideBackwardArtifactForTest())
            val revealed = checkNotNull(view.slideRevealedArtifactForTest())

            // Forward page 1 -> 2 consumes the exact revealed slot; reverse page 1 -> 0 consumes
            // the exact backward slot. Both transfers are pure exact-key lookups — no renderer
            // state participates.
            val forwardPair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 2,
                targetTop = pages[2].topPx,
                targetWindow = pages[2],
            )
            assertNotNull("the forward exact pair must transfer", forwardPair)
            assertSame(front, forwardPair!!.first)
            assertSame(revealed, forwardPair.second)
            // Re-arm the exact same artifact identities so the reverse direction can be exercised
            // against the same slots (the transfer consumes them).
            view.installSlideArtifactSlotForTest("slideFrontSlot", pages[1], front)
            view.installSlideArtifactSlotForTest("slideRevealedSlot", pages[2], revealed)

            val reversePair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 0,
                targetTop = pages[0].topPx,
                targetWindow = pages[0],
            )
            assertNotNull("the reverse exact pair must transfer", reversePair)
            assertSame(front, reversePair!!.first)
            assertSame(backward, reversePair.second)

            // A target that exists in neither slot must not transfer or consume anything.
            view.installSlideArtifactSlotForTest("slideFrontSlot", pages[1], front)
            view.installSlideArtifactSlotForTest("slideBackwardSlot", pages[0], backward)
            val missingPair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 3,
                targetTop = pages[3].topPx,
                targetWindow = pages[3],
            )
            assertNull("a non-slotted target must not transfer", missingPair)
            assertSame("the rejected lookup must not consume the front slot", front, view.slideFrontArtifactForTest())
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `warm precache re-run with identical slots retains artifact identities`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow

        try {
            view.recycleCachedTexturesForTest()
            view.setChapter(
                flow,
                view.textView.text,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            val frontBefore = checkNotNull(view.slideFrontArtifactForTest())
            val revealedBefore = checkNotNull(view.slideRevealedArtifactForTest())
            // Re-running precache with the exact same slots must not re-record or churn identities.
            view.preCachePageTexturesForTest()
            view.drainPendingPageTexturePrecacheForTest()
            assertSame("the front artifact identity must be retained", frontBefore, view.slideFrontArtifactForTest())
            assertSame("the revealed artifact identity must be retained", revealedBefore, view.slideRevealedArtifactForTest())
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `warm pair is admitted synchronously with no persistent staged renderer`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>

        try {
            // Park on a middle page so a reverse gesture owns a real adjacent target.
            view.goToPage(1)
            shadowOf(Looper.getMainLooper()).idle()
            view.pageTexturePrecacheEnabled = false
            view.recycleCachedTexturesForTest()
            EpubPageShotCaptureProbe.reset()
            // Arm the exact-key slots by hand; no persistent renderer is ever created. The pair
            // must transfer purely from the artifact slots.
            val front = view.recordSlidePageArtifactForTest(pages[1].topPx, pages[1]) as SlidePageArtifact
            val backward = view.recordSlidePageArtifactForTest(pages[0].topPx, pages[0]) as SlidePageArtifact
            view.installSlideArtifactSlotForTest("slideFrontSlot", pages[1], front)
            view.installSlideArtifactSlotForTest("slideBackwardSlot", pages[0], backward)

            val pair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 0,
                targetTop = pages[0].topPx,
                targetWindow = pages[0],
            )
            assertNotNull("the ready pair must transfer with no renderer in play", pair)
            assertSame(front, pair!!.first)
            assertSame(backward, pair.second)
            // Re-arm the exact same identities: the gate consumed them, and the gesture must still
            // admit synchronously from the ready slots.
            view.installSlideArtifactSlotForTest("slideFrontSlot", pages[1], front)
            view.installSlideArtifactSlotForTest("slideBackwardSlot", pages[0], backward)

            // A real reverse gesture consumes the same pair synchronously with zero bitmap capture.
            assertTrue(view.beginInteractiveCurl(forward = false, anchorX = 0f))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertSame(front, slide.frontArtifactForTest())
            assertSame(backward, slide.revealedArtifactForTest())
            assertEquals(
                "a renderer-less ready pair must not fall back to page-shot Bitmaps",
                0,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            EpubPageShotCaptureProbe.stop()
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `viewport size change invalidates artifact keys and installs a fresh overlay`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow

        try {
            view.recycleCachedTexturesForTest()
            view.setChapter(
                flow,
                view.textView.text,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            val frontBefore = checkNotNull(view.slideFrontArtifactForTest())
            val revealedBefore = checkNotNull(view.slideRevealedArtifactForTest())
            // A viewport mutation changes the artifact key (viewport dims are key components), so
            // the old recorded pair can never be consumed for the new geometry.
            view.measure(exactly(view.width * 2), exactly(view.height * 2))
            view.layout(0, 0, view.width * 2, view.height * 2)
            val pair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 2,
                targetTop = pages[2].topPx,
                targetWindow = pages[2],
            )
            assertNull("a stale-keyed pair must not transfer after a viewport change", pair)
            // The stale rejection eagerly discards the old-keyed owners: the artifact keys include
            // the viewport dimensions, so the old pair can never be reused and recycleCachedTextures
            // clears the slots rather than retaining them by identity.
            assertNull("the stale pair rejection must discard the old front slot", view.slideFrontArtifactForTest())
            assertNull("the stale pair rejection must discard the old revealed slot", view.slideRevealedArtifactForTest())

            // A real turn after the mutation installs a fresh overlay sized to the new viewport;
            // the stale recorded artifacts are never reused as the rendered strip.
            view.recycleCachedTexturesForTest()
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val renderer = view.requireActiveSlideRendererView() as PageSlideOverlayView
            assertEquals("the fresh overlay must use the new viewport width", 2 * view.width, renderer.width)
            assertEquals("the fresh overlay must use the new viewport height", view.height, renderer.height)
            assertNotNull("the fresh overlay must stay attached", renderer.parent)
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `cache recycle discards artifact slots and re-precaches fresh identities`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow

        try {
            view.recycleCachedTexturesForTest()
            view.setChapter(
                flow,
                view.textView.text,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            val frontBefore = checkNotNull(view.slideFrontArtifactForTest())
            val revealedBefore = checkNotNull(view.slideRevealedArtifactForTest())
            // Full cache recycle discards the artifact slots; a later precache re-records fresh
            // identities instead of reusing stale ones.
            view.recycleCachedTexturesForTest()
            assertNull("cache recycle must clear the front slot", view.slideFrontArtifactForTest())
            assertNull("cache recycle must clear the revealed slot", view.slideRevealedArtifactForTest())
            assertNull("cache recycle must clear the backward slot", view.slideBackwardArtifactForTest())

            view.preCachePageTexturesForTest()
            view.drainPendingPageTexturePrecacheForTest()
            val frontAfter = checkNotNull(view.slideFrontArtifactForTest())
            val revealedAfter = checkNotNull(view.slideRevealedArtifactForTest())
            assertTrue("re-precache must record a fresh front artifact", frontAfter !== frontBefore)
            assertTrue("re-precache must record a fresh revealed artifact", revealedAfter !== revealedBefore)
            assertTrue("the fresh front artifact must stay live", frontAfter.slideArtifactRecordIsLive())
            assertTrue("the fresh revealed artifact must stay live", revealedAfter.slideArtifactRecordIsLive())
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `rapid pair admission depends only on the exact cached or pending pair`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>

        try {
            view.recycleCachedTexturesForTest()
            assertFalse(
                "rapid admission must reject with no cached or pending pair",
                view.rapidSlideArtifactPairAvailableForTest(pages[1]),
            )

            // A complete pending precache request (front + target artifacts recorded, no commit yet)
            // is already pair-ready: admission never waits for a hidden record pass.
            view.armPendingSlideArtifactPrecacheForTest(fromPageIndex = 0, targetPageIndex = 1)
            assertTrue(
                "a complete pending pair must admit the rapid turn",
                view.rapidSlideArtifactPairAvailableForTest(pages[1]),
            )

            // Commit the pending request through the real production predicate; the pair remains
            // admissible from the exact cached slots it installs.
            val request = checkNotNull(view.privateField("pendingSlideArtifactPrecache"))
            val commitMethod = view.javaClass.getDeclaredMethod(
                "commitPendingSlideArtifactPrecache",
                request.javaClass,
            )
            commitMethod.apply { isAccessible = true }.invoke(view, request)
            assertTrue(
                "the committed cached pair must admit the rapid turn",
                view.rapidSlideArtifactPairAvailableForTest(pages[1]),
            )
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `cold bitmap overlay begins with a visible first draw carrying the captured page pixels`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        try {
            // Drop the warm artifact slots so the gesture installs a cold Bitmap overlay.
            view.recycleCachedTexturesForTest()
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val renderer = view.requireActiveSlideRendererView() as PageSlideOverlayView
            assertEquals("the fresh cold overlay must be visible", 1f, renderer.alpha, 0.001f)
            assertNotNull("the fresh cold overlay must be attached", renderer.parent)
            val front = slide.privateBitmap("frontBitmap")
            assertFalse("the cold front Bitmap must be live", front.isRecycled)
            assertFalse("the cold revealed Bitmap must be live", slide.privateBitmap("revealedBitmap").isRecycled)

            // Rasterize the host frame immediately after installation: the first draw must carry
            // the captured outgoing page pixels, never a blank overlay.
            val hostFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                val sampleX = view.width / 4
                val sampleY = view.height / 2
                val sourceX = sampleX * front.width / view.width
                val sourceY = sampleY * front.height / view.height
                assertEquals(
                    "the cold overlay's first host draw must paint the captured front page",
                    front.getPixel(sourceX, sourceY),
                    hostFrame.getPixel(sampleX, sampleY),
                )
            } finally {
                hostFrame.recycle()
            }
        } finally {
            view.dispose()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    // This first-host-draw raster check runs against the API 28 Picture artifact record. The real
    // API 29+ RenderNode path is validated in the hardware/device lane: a RenderNode display list
    // cannot be rasterized through a software Bitmap Canvas (Canvas.drawRenderNode is a
    // hardware-accelerated contract), so this software test intentionally exercises the production
    // Picture fallback rather than pretending to rasterize RenderNode through a software Canvas.
    @Config(sdk = [28])
    fun `warm artifact overlay installed through the real start path paints its page on the first host draw`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
        view.goToPage(1)
        val staticTargetFrame = view.drawAsScrolledChildToBitmapForTest()
        view.goToPage(0)
        val staticFrontFrame = view.drawAsScrolledChildToBitmapForTest()
        try {
            // Warm the exact-key artifact pair through the real production precache chain, then
            // start the turn through the real gesture start path (no renderer or record hook).
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            view.drainPendingPageTexturePrecacheForTest()
            val frontArtifact = checkNotNull(view.slideFrontArtifactForTest()) {
                "the warmed fixture must own a current-page artifact"
            }
            val revealedArtifact = checkNotNull(view.slideRevealedArtifactForTest()) {
                "the warmed fixture must own a forward-target artifact"
            }

            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val renderer = view.requireActiveSlideRendererView() as PageSlideOverlayView
            assertEquals("the warm overlay must be visible", 1f, renderer.alpha, 0.001f)
            assertNotNull("the warm overlay must be attached through the View overlay", renderer.parent)
            assertSame("the front band must replay the warmed artifact", frontArtifact, slide.frontArtifactForTest())
            assertSame(
                "the revealed band must replay the warmed artifact",
                revealedArtifact,
                slide.revealedArtifactForTest(),
            )

            // Rasterize the host frame immediately after installation: the first draw must replay
            // the recorded artifact commands, never paint a blank strip over the parked target.
            val hostFrame = view.drawAsScrolledChildToBitmapForTest()
            try {
                // A fixed 3x3 sample grid can land entirely outside the only pixels that differ
                // between consecutive fixture pages (the line-number digits), so discover one real
                // difference deterministically: scan every pixel and keep the coordinate with the
                // strongest channel distance between the static front and parked target frames.
                var differX = -1
                var differY = -1
                var differFrontPixel = 0
                var differTargetPixel = 0
                var bestDistance = -1
                for (y in 0 until staticFrontFrame.height) {
                    for (x in 0 until staticFrontFrame.width) {
                        val frontPixel = staticFrontFrame.getPixel(x, y)
                        val targetPixel = staticTargetFrame.getPixel(x, y)
                        if (frontPixel == targetPixel) continue
                        val distance =
                            kotlin.math.abs(((frontPixel shr 16) and 0xFF) - ((targetPixel shr 16) and 0xFF)) +
                                kotlin.math.abs(((frontPixel shr 8) and 0xFF) - ((targetPixel shr 8) and 0xFF)) +
                                kotlin.math.abs((frontPixel and 0xFF) - (targetPixel and 0xFF))
                        if (distance > bestDistance) {
                            bestDistance = distance
                            differX = x
                            differY = y
                            differFrontPixel = frontPixel
                            differTargetPixel = targetPixel
                        }
                    }
                }
                assertTrue(
                    "the front and parked target pages must differ at some pixel so a blank overlay " +
                        "would expose target pixels; discovered=$differX,$differY distance=$bestDistance",
                    differX >= 0 && differY >= 0,
                )
                // The overlay's front band covers [0, W) at progress 0, so the discovered coordinate
                // is always inside it; a blank overlay would show the parked target value instead.
                assertTrue(
                    "the discovered coordinate must sit in the front band so the overlay covers it",
                    differX in 0 until view.width,
                )
                assertEquals(
                    "the warm overlay's first host draw must replay the front artifact page content " +
                        "at the discovered coordinate ($differX,$differY)",
                    differFrontPixel,
                    hostFrame.getPixel(differX, differY),
                )
                assertNotEquals(
                    "the parked target must stay hidden behind the front band at the discovered coordinate",
                    differTargetPixel,
                    hostFrame.getPixel(differX, differY),
                )
            } finally {
                hostFrame.recycle()
            }
        } finally {
            staticTargetFrame.recycle()
            staticFrontFrame.recycle()
            view.dispose()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    // LEGACY Robolectric ignores Paint shaders (drawRect falls back to the paint's solid color), so
    // the LinearGradient seam shadow would never darken the band; NATIVE graphics rasterizes it.
    fun `slide overlay seam shadow darkens the strip seam for both directions`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        val context = RuntimeEnvironment.getApplication() as Application
        val w = view.width
        val h = view.height
        val paperColor = 0xFFEDE6D6.toInt()
        val front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(paperColor) }
        val revealed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(paperColor) }
        try {
            for (forward in listOf(true, false)) {
                val renderer = PageSlideOverlayView(
                    context = context,
                    frontFrame = SlidePageFrame.BitmapFrame(front),
                    revealedFrame = SlidePageFrame.BitmapFrame(revealed),
                    viewportW = w,
                    viewportH = h,
                    forward = forward,
                    density = view.resources.displayMetrics.density,
                )
                val stripLeft = if (forward) 0 else -w
                renderer.layout(stripLeft, 0, stripLeft + 2 * w, h)
                val frame = Bitmap.createBitmap(
                    renderer.width.coerceAtLeast(1),
                    renderer.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    renderer.draw(Canvas(frame))
                    val shadowW = renderer.seamShadowWidthPx().coerceAtLeast(1)
                    // Forward shadow band is local [W, W+shadowW]; backward sits at [W-shadowW, W].
                    val bandX = if (forward) w + shadowW / 2 else w - shadowW / 2
                    val bodyX = w / 2
                    val bodyLuma = android.graphics.Color.luminance(frame.getPixel(bodyX, h / 2))
                    val seamLuma = android.graphics.Color.luminance(frame.getPixel(bandX, h / 2))
                    assertTrue(
                        "forward=$forward seam band must be darker than the page body; " +
                            "body=$bodyLuma seam=$seamLuma",
                        seamLuma < bodyLuma,
                    )
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            front.recycle()
            revealed.recycle()
            view.dispose()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    // Same LEGACY shader limitation as the seam-shadow test above; NATIVE rasterizes the gradient.
    fun `cold bitmap slide overlay retains the baseline seam shadow`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        // The cold page shots are captured from the live view, so pin a non-black paper background
        // first: the luma comparison then measures the seam shadow against paper instead of
        // transparent/black fixture pixels.
        view.background = ColorDrawable(0xFFEDE6D6.toInt())
        try {
            // Drop the warm artifact slots so the turn installs a fresh cold BitmapFrame overlay
            // whose onDraw must still draw the baseline seam shadow.
            view.recycleCachedTexturesForTest()
            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val renderer = view.requireActiveSlideRendererView() as PageSlideOverlayView
            val front = slide.privateBitmap("frontBitmap")
            val revealed = slide.privateBitmap("revealedBitmap")
            assertFalse("cold overlay front bitmap must be live", front.isRecycled)
            assertFalse("cold overlay revealed bitmap must be live", revealed.isRecycled)
            assertNotNull("the cold renderer must still be the fresh overlay", renderer.parent)
            // The opaque ColorDrawable background selects RGB_565 motion page shots; the exact
            // paper reference below is derived through that same config, so pin the contract here.
            assertEquals("cold overlay front bitmap must be an RGB_565 page shot", Bitmap.Config.RGB_565, front.config)
            assertEquals("cold overlay revealed bitmap must be an RGB_565 page shot", Bitmap.Config.RGB_565, revealed.config)
            // Rasterize the full 2W strip: forward shadow band is local [W, W+shadowW].
            val frame = Bitmap.createBitmap(renderer.width.coerceAtLeast(1), renderer.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            // The luma comparison must measure the shadow over real paper, not over dynamic glyph
            // pixels whose ink can be darker than the shadowed band. Discover one row where both
            // the front body column and the revealed-band seam column are uninked paper in the
            // source page shots, then compare the same strip row.
            // The opaque viewport background selects RGB_565 motion page shots, so an exact
            // match against the original ARGB paper value is invalid after storage. Instead of
            // assuming a packer rounding convention, derive the exact paper reference through
            // the same renderer path: a 1x1 RGB_565 bitmap painted with the same fixture
            // ColorDrawable via Canvas. Whatever the native packer produces for the page shots,
            // it produces the identical value here, and the equality below stays strict against
            // glyph pixels.
            val paperReference = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565).also { paper ->
                val canvas = Canvas(paper)
                ColorDrawable(0xFFEDE6D6.toInt()).apply {
                    setBounds(0, 0, paper.width, paper.height)
                    draw(canvas)
                }
            }
            try {
                renderer.draw(Canvas(frame))
                val shadowW = renderer.seamShadowWidthPx().coerceAtLeast(1)
                val bodyX = view.width / 2
                val paperPixel = paperReference.getPixel(0, 0)
                // Cold page shots can be lower resolution than the viewport: PageSlideOverlayView
                // maps them through drawBitmapWindow, so translate viewport x into each source
                // bitmap's width with the scale convention used elsewhere in this file
                // (sourceX = sampleX * bitmap.width / view.width), then clamp to the source width.
                val bodyPageX = (bodyX * front.width / view.width).coerceIn(0, front.width - 1)
                val seamPageX = ((shadowW / 2) * revealed.width / view.width)
                    .coerceIn(0, revealed.width - 1)
                // map each viewport y into both source heights with the same scale convention, so
                // the discovered paper row stays aligned with the output row even when the cold
                // page shots are lower resolution than the viewport.
                var paperRow = -1
                for (y in 0 until view.height) {
                    val frontSourceY = (y * front.height / view.height).coerceIn(0, front.height - 1)
                    val revealedSourceY =
                        (y * revealed.height / view.height).coerceIn(0, revealed.height - 1)
                    if (
                        front.getPixel(bodyPageX, frontSourceY) == paperPixel &&
                        revealed.getPixel(seamPageX, revealedSourceY) == paperPixel
                    ) {
                        paperRow = y
                        break
                    }
                }
                assertTrue(
                    "the cold fixture must contain a row where both sampled source columns are " +
                        "uninked paper equal to the RGB_565 renderer-derived reference " +
                        "paperPixel=0x${paperPixel.toString(16).padStart(8, '0')} " +
                        "paperRow=$paperRow shadowW=$shadowW",
                    paperRow >= 0,
                )
                val bandX = (view.width + shadowW / 2).coerceAtMost(frame.width - 1)
                val bodyLuma = android.graphics.Color.luminance(frame.getPixel(bodyX, paperRow))
                val seamLuma = android.graphics.Color.luminance(frame.getPixel(bandX, paperRow))
                assertTrue(
                    "cold overlay seam band must be darker than the page body; body=$bodyLuma seam=$seamLuma",
                    seamLuma < bodyLuma,
                )
            } finally {
                paperReference.recycle()
                frame.recycle()
            }
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `forward and reverse warm turns consume the exact direction slot artifacts`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow

        try {
            view.recycleCachedTexturesForTest()
            view.setChapter(
                flow,
                view.textView.text,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            val current = checkNotNull(view.slideFrontArtifactForTest())
            val previous = checkNotNull(view.slideBackwardArtifactForTest())
            val next = checkNotNull(view.slideRevealedArtifactForTest())

            // Forward consumes the exact next slot; the same pair transfers identically for a
            // reverse gesture, but only from the exact previous slot.
            val forwardPair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 2,
                targetTop = pages[2].topPx,
                targetWindow = pages[2],
            )
            assertNotNull("the forward exact pair must transfer", forwardPair)
            assertSame(current, forwardPair!!.first)
            assertSame(next, forwardPair.second)
            view.installSlideArtifactSlotForTest("slideFrontSlot", pages[1], current)
            view.installSlideArtifactSlotForTest("slideRevealedSlot", pages[2], next)

            val reversePair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 0,
                targetTop = pages[0].topPx,
                targetWindow = pages[0],
            )
            assertNotNull("the reverse exact pair must transfer", reversePair)
            assertSame(current, reversePair!!.first)
            assertSame(previous, reversePair.second)
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `mismatched warm pair lookups reject without consuming slots`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val flow = view.privateField("flow") as EpubChapterFlow

        try {
            view.recycleCachedTexturesForTest()
            view.setChapter(
                flow,
                view.textView.text,
                pageHeightPx = view.height,
                restoreOffset = pages[1].startOffset,
            )
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(0, 0, view.width, view.height)
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            val front = checkNotNull(view.slideFrontArtifactForTest())
            val revealed = checkNotNull(view.slideRevealedArtifactForTest())
            // A target page whose window is not the armed revealed slot must not transfer, and the
            // lookup must never consume either slot.
            val pair = view.takeCachedSlideArtifactsForTurnForTest(
                fromPage = 1,
                fromTop = pages[1].topPx,
                fromWindow = pages[1],
                targetPage = 3,
                targetTop = pages[3].topPx,
                targetWindow = pages[3],
            )
            assertNull("a non-slotted target must not transfer", pair)
            assertSame("the rejected lookup must not consume the front slot", front, view.slideFrontArtifactForTest())
            assertSame("the rejected lookup must not consume the revealed slot", revealed, view.slideRevealedArtifactForTest())
        } finally {
            view.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
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
    fun `pixel only async image result invalidates only its page texture without text rebind`() {
        val view = pagedFlowView()
        val pages = view.privateField("paged") as List<EpubFlowPage>
        assertTrue("fixture needs adjacent pages", pages.size > 1)
        val cachedFront = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cachedRevealed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        view.setPrivateField("cachedFrontBitmap", cachedFront)
        view.setPrivateField("cachedRevealedBitmap", cachedRevealed)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        val layoutBefore = requireNotNull(view.textView.layout)
        val method = runCatching {
            view.javaClass.getDeclaredMethod(
                "onAsyncImagePixelsChanged",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
        assertNotNull(
            "pixel-only image completion needs a selective refresh API",
            method,
        )

        method!!.invoke(view, pages[0].startOffset)

        // Slice A: dependent warm owners stay leased; no recycle/replace cold gap.
        assertFalse(
            "PIXELS_ONLY must keep the containing warm front owner (in-place refresh, not recycle)",
            cachedFront.isRecycled,
        )
        assertFalse("an unrelated adjacent page texture must stay warm", cachedRevealed.isRecycled)
        assertTrue(
            "dependent front slot must retain the same bitmap identity",
            view.privateField("cachedFrontBitmap") === cachedFront,
        )
        assertTrue(view.privateField("cachedRevealedBitmap") === cachedRevealed)
        assertTrue("same geometry must preserve the existing StaticLayout", view.textView.layout === layoutBefore)
        assertFalse("same geometry must not request a TextView re-layout", view.textView.isLayoutRequested)
    }

    @Test
    fun `PIXELS_ONLY on next page invalidates image shot and heading continuation shot`() {
        // Extra leading body lines so page 0 is unrelated text while heading sits on a later page.
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        try {
            val pages = view.privateField("paged") as List<EpubFlowPage>
            assertEquals(
                "precache revealed slot is the immediate next page after the heading; " +
                    "heading=${fixture.headingPageIndex} image=${fixture.imagePageIndex} pages=${pages.size}",
                fixture.headingPageIndex + 1,
                fixture.imagePageIndex,
            )
            assertTrue(
                "fixture needs an unrelated page before the heading continuation pair; " +
                    "heading=${fixture.headingPageIndex} image=${fixture.imagePageIndex} pages=${pages.size}",
                fixture.headingPageIndex > 0,
            )
            assertTrue(
                "production-faithful warm shots require precache enabled",
                view.pageTexturePrecacheEnabled,
            )

            // Park on the heading page so front=heading, revealed=image, backward=unrelated prior page.
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()

            // Drive the real precache path (split-frame capture) so warm owners carry production
            // tops + PageTextureKey values — not keyless manual injection.
            view.preCachePageTexturesForTest()
            val headingShot = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?) {
                "precache must warm the heading-page front shot"
            }
            val imageShot = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?) {
                "precache must warm the image-page revealed shot"
            }
            val unrelatedShot = checkNotNull(view.privateField("cachedBackwardBitmap") as Bitmap?) {
                "precache must warm the unrelated previous-page shot"
            }
            assertEquals(fixture.headingPageIndex, view.privateInt("cachedFromPage"))
            assertEquals(fixture.imagePageIndex, view.privateInt("cachedTargetPage"))
            assertEquals(fixture.headingPageIndex - 1, view.privateInt("cachedBackwardPage"))
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.headingPageIndex)),
                view.privateInt("cachedFromTopPx"),
            )
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.imagePageIndex)),
                view.privateInt("cachedTargetTopPx"),
            )
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.headingPageIndex - 1)),
                view.privateInt("cachedBackwardTopPx"),
            )
            val headingKey = checkNotNull(view.privateField("cachedFromTextureKey"))
            val imageKey = checkNotNull(view.privateField("cachedTargetTextureKey"))
            val unrelatedKey = checkNotNull(view.privateField("cachedBackwardTextureKey"))
            assertFalse(headingShot.isRecycled)
            assertFalse(imageShot.isRecycled)
            assertFalse(unrelatedShot.isRecycled)
            val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)
            val leasedBytesBefore = budget.leasedBytes

            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            // Drain one-slot-per-frame in-place redraws (no recycle/realloc rebuild).
            view.drainInPlacePageShotRefreshForTest()

            assertFalse(
                "image-page warm owner must stay leased after PIXELS_ONLY (in-place, not recycle)",
                imageShot.isRecycled,
            )
            assertFalse(
                "heading continuation warm owner must stay leased after PIXELS_ONLY",
                headingShot.isRecycled,
            )
            assertFalse(
                "unrelated warm page shot must stay alive after the full lifecycle",
                unrelatedShot.isRecycled,
            )
            assertTrue(
                "heading slot must keep the same bitmap identity after in-place refresh",
                view.privateField("cachedFrontBitmap") === headingShot,
            )
            assertTrue(
                "image slot must keep the same bitmap identity after in-place refresh",
                view.privateField("cachedRevealedBitmap") === imageShot,
            )
            assertTrue(
                "unrelated owner must remain the same cached instance",
                view.privateField("cachedBackwardBitmap") === unrelatedShot,
            )
            assertTrue(
                "unrelated texture key must stay consistent for the retained previous shot",
                view.privateField("cachedBackwardTextureKey") === unrelatedKey ||
                    view.privateField("cachedBackwardTextureKey") == unrelatedKey,
            )
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.headingPageIndex - 1)),
                view.privateInt("cachedBackwardTopPx"),
            )
            assertEquals(
                "page-shot budget leased bytes must stay stable (no release/realloc)",
                leasedBytesBefore,
                budget.leasedBytes,
            )
            assertEquals(fixture.headingPageIndex, view.privateInt("cachedFromPage"))
            assertEquals(fixture.imagePageIndex, view.privateInt("cachedTargetPage"))
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.headingPageIndex)),
                view.privateInt("cachedFromTopPx"),
            )
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.imagePageIndex)),
                view.privateInt("cachedTargetTopPx"),
            )
            // Same geometry: keys remain the pre-refresh page identity values.
            assertEquals(headingKey, view.privateField("cachedFromTextureKey"))
            assertEquals(imageKey, view.privateField("cachedTargetTextureKey"))
            assertTrue(
                "in-place refresh queue must drain after animation frames",
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )
            assertFalse(
                "split-frame precache must not arm a recycle rebuild after PIXELS_ONLY",
                view.privateBool("pageTexturePrecachePending"),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `PIXELS_ONLY AsyncDrawable paints heading continuation crop after transparent placeholder`() {
        val viewportWidth = 360
        val viewportHeight = 240
        val imageColor = 0xFFE11D48.toInt()
        val imageHeight = viewportHeight + 120
        val bodyText = (1..2).joinToString("\n") { "前置正文 $it 把标题推到页底附近。" }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Text(bodyText, headingLevel = null, paragraphIndex = 0),
                EpubDisplayBlock.Text("图版标题", headingLevel = 2, paragraphIndex = 1),
                EpubDisplayBlock.Image(
                    href = "plate-async.png",
                    altText = "async plate",
                    paragraphIndex = 2,
                    isInlineContent = false,
                ),
            ),
        )
        val imageSegment = flow.segments.single { it.isImage }
        val headingSegment = flow.segments.single {
            val block = it.block
            block is EpubDisplayBlock.Text && block.headingLevel != null
        }
        val placeholder = ColorDrawable(android.graphics.Color.TRANSPARENT).apply {
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val asyncDrawable = AsyncDrawable(
            "plate-async.png",
            AsyncDrawableLoader.noOp(),
            EpubFlowImageSizeResolver(
                columnWidthPx = viewportWidth,
                pageHeightProvider = { viewportHeight },
                inlineMaxHeightPx = viewportHeight,
                fullPageHrefs = emptySet(),
            ),
            null,
        ).apply {
            // Transparent same-size placeholder → PIXELS_ONLY when real pixels arrive.
            setResult(placeholder)
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val spannable = SpannableString(flow.text).apply {
            setSpan(
                AsyncDrawableSpan(
                    io.noties.markwon.core.MarkwonTheme.create(RuntimeEnvironment.getApplication()),
                    asyncDrawable,
                    AsyncDrawableSpan.ALIGN_CENTER,
                    false,
                ),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(
            flow = flow,
            spannable = spannable,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        try {
            assertTrue(
                "cached-shot contract requires production precache enabled",
                view.pageTexturePrecacheEnabled,
            )
            val layout = requireNotNull(view.textView.layout)
            val headingLine = layout.getLineForOffset(headingSegment.layoutStart)
            val imageLine = layout.getLineForOffset(imageSegment.layoutStart)
            assertTrue(
                "fixture needs an oversized following image for continuation crop",
                layout.getLineBottom(imageLine) - layout.getLineTop(imageLine) > viewportHeight,
            )
            val headingPageIndex = (0 until view.pageCount()).firstOrNull { pageIndex ->
                val top = requireNotNull(view.pageTopPxAt(pageIndex))
                val nextTop = view.pageTopPxAt(pageIndex + 1) ?: (layout.height + 1)
                layout.getLineTop(headingLine) in top until nextTop
            } ?: error("heading must land on a paged window")
            view.goToPage(headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()

            // Warm page shots while the placeholder is still transparent (cached-shot path, not only live draw).
            view.preCachePageTexturesForTest()
            val staleHeadingShot = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?) {
                "precache must warm the heading page while the placeholder is transparent"
            }
            val staleHeadingKey = checkNotNull(view.privateField("cachedFromTextureKey"))
            assertFalse(staleHeadingShot.isRecycled)

            val headingBottomOnPage =
                layout.getLineBottom(headingLine) - requireNotNull(view.pageTopPxAt(headingPageIndex))
            val sampleLeft = viewportWidth / 4
            val sampleTop = (headingBottomOnPage + 4).coerceAtMost(viewportHeight - 2)
            val sampleRight = (viewportWidth * 3) / 4
            val sampleBottom = viewportHeight - 2
            val warmHitsBefore = sampleImagePatternHits(
                bitmap = staleHeadingShot,
                left = sampleLeft,
                top = sampleTop,
                right = sampleRight,
                bottom = sampleBottom,
                stripeA = imageColor,
                stripeB = imageColor,
            )
            assertEquals(
                "warm heading shot must not already contain decoded image pixels",
                0,
                warmHitsBefore,
            )

            val decoded = ColorDrawable(imageColor).apply {
                setBounds(0, 0, viewportWidth, imageHeight)
            }
            asyncDrawable.setResult(decoded)
            asyncDrawable.setBounds(0, 0, viewportWidth, imageHeight)
            view.onAsyncImagePixelsChanged(imageSegment.layoutStart)
            // Drain one-slot-per-frame in-place redraw so the warm heading owner gets latest pixels.
            view.drainInPlacePageShotRefreshForTest()

            assertFalse(
                "heading continuation warm owner must stay leased after PIXELS_ONLY",
                staleHeadingShot.isRecycled,
            )
            val refreshedHeading = view.privateField("cachedFrontBitmap") as Bitmap?
            assertTrue(
                "heading slot must keep the transparent-era owner identity (in-place refresh)",
                refreshedHeading === staleHeadingShot,
            )
            val livePixels = view.drawToBitmapForTest()
            try {
                val warmHits = sampleImagePatternHits(
                    bitmap = staleHeadingShot,
                    left = sampleLeft,
                    top = sampleTop,
                    right = sampleRight,
                    bottom = sampleBottom,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                val liveHits = sampleImagePatternHits(
                    bitmap = livePixels,
                    left = sampleLeft,
                    top = sampleTop,
                    right = sampleRight,
                    bottom = sampleBottom,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue(
                    "heading continuation crop must show decoded pixels on the same warm owner and/or live render; " +
                        "warmHits=$warmHits liveHits=$liveHits headingBottomOnPage=$headingBottomOnPage",
                    warmHits > 0 || liveHits > 0,
                )
                assertNotNull(
                    "refreshed warm heading must keep a valid PageTextureKey",
                    view.privateField("cachedFromTextureKey"),
                )
                // Same geometry: key identity values remain consistent with the pre-decode page.
                assertEquals(staleHeadingKey, view.privateField("cachedFromTextureKey"))
            } finally {
                livePixels.recycle()
            }
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `far page PIXELS_ONLY does not invalidate warm shots or restart nearby precache`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        // Park on page 0 so relevant neighborhood is pages 0/1 (and no previous).
        view.goToPage(0)
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()

        val front = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val backward = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        view.setPrivateField("cachedFrontBitmap", front)
        view.setPrivateField("cachedRevealedBitmap", revealed)
        view.setPrivateField("cachedBackwardBitmap", backward)
        view.setPrivateField("cachedFromPage", 0)
        view.setPrivateField("cachedTargetPage", 1)
        view.setPrivateField("cachedBackwardPage", 2)
        view.setPrivateField("cachedFromTopPx", pages[0].topPx)
        view.setPrivateField("cachedTargetTopPx", pages[1].topPx)
        view.setPrivateField("cachedBackwardTopPx", pages[2].topPx)

        val genBefore = view.privateField("pageTexturePrecacheGeneration") as Long
        val wakeBefore = view.privateField("asyncImageWakeListener")
        val farOffset = pages.last().startOffset
        assertFalse(
            "far offset must sit outside current/adjacent layout ranges",
            view.relevantPendingDecodeLayoutRanges().any { farOffset in it },
        )

        view.onAsyncImagePixelsChanged(farOffset)
        shadowOf(Looper.getMainLooper()).idle()
        // Drain any preDraw wake scheduled by a buggy far-page path.
        shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)

        assertFalse("current warm front must stay", front.isRecycled)
        assertFalse("adjacent warm revealed must stay", revealed.isRecycled)
        assertFalse("nearby warm previous must stay", backward.isRecycled)
        assertTrue(view.privateField("cachedFrontBitmap") === front)
        assertTrue(view.privateField("cachedRevealedBitmap") === revealed)
        assertTrue(view.privateField("cachedBackwardBitmap") === backward)
        assertEquals(
            "far-page PIXELS_ONLY must not bump the precache generation",
            genBefore,
            view.privateField("pageTexturePrecacheGeneration") as Long,
        )
        assertTrue(
            "far-page PIXELS_ONLY must not install a new async-image wake listener; " +
                "before=$wakeBefore after=${view.privateField("asyncImageWakeListener")}",
            view.privateField("asyncImageWakeListener") == null ||
                view.privateField("asyncImageWakeListener") === wakeBefore,
        )
        view.dispose()
    }

    @Test
    fun `PIXELS_ONLY during held finger turn defers shot recycle and precache until settle`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        try {
            assertTrue(
                "production-faithful warm shots require precache enabled",
                view.pageTexturePrecacheEnabled,
            )
            // Park on the page before the heading so a forward turn targets the heading page,
            // whose warm continuation depends on the next-page image pixels.
            val parkPage = (fixture.headingPageIndex - 1).coerceAtLeast(0)
            view.goToPage(parkPage)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            assertNotNull(
                "precache must warm front artifact at park=$parkPage",
                view.slideFrontArtifactForTest(),
            )
            assertNotNull(
                "precache must warm revealed artifact at park=$parkPage",
                view.slideRevealedArtifactForTest(),
            )

            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            view.updateInteractiveCurl(x = view.width * 0.45f)
            assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())

            // The turn claims the warm artifact pair; PIXELS_ONLY must not mutate the recorded
            // active artifacts or bump the artifact precache generation.
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val frontDuringTurn = checkNotNull(slide.frontArtifactForTest())
            val revealedDuringTurn = checkNotNull(slide.revealedArtifactForTest())
            val genAtTurnStart = view.privateField("slideArtifactPrecacheGeneration") as Long
            val wakeBefore = view.privateField("asyncImageWakeListener")
            val pendingPrecacheBefore = view.privateBool("slideArtifactPrecachePending")

            // Interleaved PIXELS_ONLY completions while the finger still owns the turn.
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

            @Suppress("UNCHECKED_CAST")
            val queued = view.privateField("asyncImagePixelRefreshOffsets") as Set<Int>
            assertTrue(
                "PIXELS_ONLY offsets must queue while the finger owns the turn; queued=$queued",
                fixture.imageLayoutStart in queued,
            )
            assertTrue(
                "held-finger PIXELS_ONLY must not discard the recorded active front artifact",
                frontDuringTurn.slideArtifactRecordIsLive(),
            )
            assertTrue(
                "held-finger PIXELS_ONLY must not discard the recorded active revealed artifact",
                revealedDuringTurn.slideArtifactRecordIsLive(),
            )
            assertEquals(
                "held-finger PIXELS_ONLY must not bump the artifact precache generation",
                genAtTurnStart,
                view.privateField("slideArtifactPrecacheGeneration") as Long,
            )
            assertTrue(
                "held-finger PIXELS_ONLY must not install a new async-image precache wake; " +
                    "before=$wakeBefore after=${view.privateField("asyncImageWakeListener")}",
                view.privateField("asyncImageWakeListener") == null ||
                    view.privateField("asyncImageWakeListener") === wakeBefore,
            )
            assertEquals(
                "artifact precache arming must not change while the finger owns the turn",
                pendingPrecacheBefore,
                view.privateBool("slideArtifactPrecachePending"),
            )
            assertFalse(
                "artifact precache must not newly arm while the finger owns the turn",
                !pendingPrecacheBefore && view.privateBool("slideArtifactPrecachePending"),
            )

            // Settle the turn; deferred pixel refresh applies only after turnInFlight clears.
            view.endInteractiveCurl(velocityX = 0f)
            val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
            if (animator != null && animator.isRunning) {
                animator.end()
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("NONE", view.privateField("interactiveTurnState").toString())

            // Drain REFLOW_DEBOUNCE_MS + post-settle in-place redraw (owners may have moved after commit).
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
            view.drainInPlacePageShotRefreshForTest()
            view.invalidate()
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)

            assertTrue(
                "queued PIXELS_ONLY offsets must drain after the turn settles",
                (view.privateField("asyncImagePixelRefreshOffsets") as Set<*>).isEmpty(),
            )
            // Queued pixels drained; normal precache rebuilds the invalidated warm artifacts.
            view.textView.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()
            assertFalse(
                "artifact precache must not remain pending after post-settle drain",
                view.privateBool("slideArtifactPrecachePending"),
            )
            assertNotNull(
                "post-settle precache must rebuild a live front artifact",
                view.slideFrontArtifactForTest()?.takeIf { it.slideArtifactRecordIsLive() },
            )
        } finally {
            if (view.privateField("interactiveTurnState").toString() != "NONE") {
                view.endInteractiveCurl(velocityX = 0f)
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            }
            view.dispose()
        }
    }

    @Test
    fun `PIXELS_ONLY in-place refresh keeps warm identities and coalesces one slot per frame`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            val headingShot = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
            val imageShot = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
            val unrelatedShot = checkNotNull(view.privateField("cachedBackwardBitmap") as Bitmap?)
            val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)
            val leasedBefore = budget.leasedBytes
            val marker = 0xFF00FF00.toInt()
            headingShot.eraseColor(marker)
            imageShot.eraseColor(marker)
            assertEquals(marker, headingShot.getPixel(0, 0))
            assertEquals(marker, imageShot.getPixel(0, 0))

            // Multiple completions for the same offset coalesce into one refresh set.
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            @Suppress("UNCHECKED_CAST")
            val pendingSlots = view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>
            // Snapshot size: pendingSlots is the live mutable set, mutated by runOneTask.
            val pendingCountBeforeFrame = pendingSlots.size
            assertTrue(
                "dependent front+revealed must be scheduled; pending=$pendingSlots",
                pendingCountBeforeFrame >= 2,
            )
            assertTrue(view.privateBool("inPlacePageShotRefreshPosted"))

            // Frame 1: exactly one slot leaves the queue.
            shadowOf(Looper.getMainLooper()).runOneTask()
            @Suppress("UNCHECKED_CAST")
            val afterOne = view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>
            assertEquals(
                "exactly one dependent slot must repaint per animation frame",
                pendingCountBeforeFrame - 1,
                afterOne.size,
            )
            assertTrue(
                "warm identities must stay leased after the first frame",
                !headingShot.isRecycled &&
                    !imageShot.isRecycled &&
                    view.privateField("cachedFrontBitmap") === headingShot &&
                    view.privateField("cachedRevealedBitmap") === imageShot &&
                    view.privateField("cachedBackwardBitmap") === unrelatedShot,
            )

            // Drain remaining frames.
            view.drainInPlacePageShotRefreshForTest()
            assertTrue(
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )
            assertFalse(headingShot.isRecycled)
            assertFalse(imageShot.isRecycled)
            assertFalse(unrelatedShot.isRecycled)
            assertTrue(view.privateField("cachedFrontBitmap") === headingShot)
            assertTrue(view.privateField("cachedRevealedBitmap") === imageShot)
            assertTrue(view.privateField("cachedBackwardBitmap") === unrelatedShot)
            assertEquals(leasedBefore, budget.leasedBytes)
            // In-place erase+redraw must replace the solid marker pixels.
            assertTrue(
                "refreshed heading owner must no longer be solid marker fill",
                headingShot.getPixel(0, 0) != marker ||
                    headingShot.getPixel(headingShot.width / 2, headingShot.height / 2) != marker,
            )
            assertTrue(
                "refreshed image owner must no longer be solid marker fill",
                imageShot.getPixel(0, 0) != marker ||
                    imageShot.getPixel(imageShot.width / 2, imageShot.height / 2) != marker,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `PIXELS_ONLY required refresh slots force immediate finger turn through fresh cold handoff`() {
        for (style in listOf(PageFlipStyle.SLIDE, PageFlipStyle.SIMULATION)) {
            val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
            val view = fixture.view
            view.flipStyle = style
            try {
                view.goToPage(fixture.headingPageIndex)
                shadowOf(Looper.getMainLooper()).idle()
                view.recycleCachedTexturesForTest()
                view.preCachePageTexturesForTest()
                var staleFront: Bitmap? = null
                var staleRevealed: Bitmap? = null
                val marker = 0xFF00FF00.toInt()
                if (style == PageFlipStyle.SLIDE) {
                    // Warm SLIDE artifacts depend on the synthetic image continuation; a
                    // PIXELS_ONLY completion invalidates the frozen pair (no in-place repaint), so
                    // the next MOVE must reject a stale warm transfer and enter cold handoff.
                    checkNotNull(view.slideFrontArtifactForTest())
                    checkNotNull(view.slideRevealedArtifactForTest())
                    view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
                    assertNull(
                        "style=SLIDE a PIXELS_ONLY completion must invalidate the front artifact",
                        view.slideFrontArtifactForTest(),
                    )
                    assertNull(
                        "style=SLIDE a PIXELS_ONLY completion must invalidate the revealed artifact",
                        view.slideRevealedArtifactForTest(),
                    )
                } else {
                    staleFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
                    staleRevealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
                    staleFront!!.eraseColor(marker)
                    staleRevealed!!.eraseColor(marker)

                    // Both current and forward shots depend on the synthetic image continuation.
                    // They remain stale until the posted in-place redraw runs and are not eligible
                    // to turn.
                    view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
                    val pendingNames = view.pendingInPlacePageShotRefreshSlotNamesForTest()
                    assertTrue("style=$style required FRONT must be pending: $pendingNames", "FRONT" in pendingNames)
                    assertTrue("style=$style required REVEALED must be pending: $pendingNames", "REVEALED" in pendingNames)
                }

                val downX = view.width * 0.85f
                val moveX = view.width * 0.45f
                val y = view.height * 0.10f
                val downTime = SystemClock.uptimeMillis()
                view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
                assertTrue(
                    view.onInterceptTouchEvent(
                        motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                    ),
                )

                assertEquals(
                    "style=$style a required dirty owner must reject stale warm transfer",
                    "LOCAL_SHOTS_WAITING",
                    view.privateField("interactiveTurnState").toString(),
                )
                assertNull("style=$style stale pair must not install slide", view.privateField("slideDrawable"))
                assertNull("style=$style stale pair must not install curl", view.privateField("curlDrawable"))

                view.drainPendingLocalPageShotHandoffUntilOverlayForTest()
                val drawable = checkNotNull(
                    view.privateField("slideDrawable") ?: view.privateField("curlDrawable"),
                ) { "style=$style fresh split capture must install an overlay" }
                if (style == PageFlipStyle.SLIDE) {
                    assertNotNull(
                        "style=SLIDE fresh split capture must install a Bitmap outgoing frame",
                        drawable.privateBitmap("frontBitmap"),
                    )
                    assertNotNull(
                        "style=SLIDE fresh split capture must install a Bitmap revealed frame",
                        drawable.privateBitmap("revealedBitmap"),
                    )
                } else {
                    val freshFront = drawable.privateBitmap("frontBitmap")
                    val freshRevealed = drawable.privateBitmap("revealedBitmap")
                    assertTrue("style=$style outgoing shot must be freshly captured", freshFront !== checkNotNull(staleFront))
                    assertTrue("style=$style target shot must be freshly captured", freshRevealed !== checkNotNull(staleRevealed))
                    assertTrue(
                        "style=$style fresh outgoing shot must not carry the stale marker",
                        freshFront.getPixel(0, 0) != marker ||
                            freshFront.getPixel(freshFront.width / 2, freshFront.height / 2) != marker,
                    )
                    assertTrue(
                        "style=$style fresh target shot must not carry the stale marker",
                        freshRevealed.getPixel(0, 0) != marker ||
                            freshRevealed.getPixel(freshRevealed.width / 2, freshRevealed.height / 2) != marker,
                    )
                }

                view.onTouchEvent(
                    motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y),
                )
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            } finally {
                view.dispose()
            }
        }
    }

    @Test
    fun `unrelated pending PIXELS_ONLY slot does not block clean warm pair transfer`() {
        for (style in listOf(PageFlipStyle.SLIDE, PageFlipStyle.SIMULATION)) {
            val view = pagedFlowView(flipStyle = style)
            try {
                view.goToPage(1)
                shadowOf(Looper.getMainLooper()).idle()
                view.recycleCachedTexturesForTest()
                view.preCachePageTexturesForTest()
                if (style == PageFlipStyle.SLIDE) {
                    // Warm SLIDE has no in-place repaint slots: the clean artifact pair transfers
                    // regardless of any unrelated pending Bitmap refresh work.
                    val front = checkNotNull(view.slideFrontArtifactForTest())
                    val revealed = checkNotNull(view.slideRevealedArtifactForTest())
                    val backward = checkNotNull(view.slideBackwardArtifactForTest())
                    assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
                    assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())
                    val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
                    assertTrue("style=SLIDE clean FRONT artifact must transfer", slide.frontArtifactForTest() === front)
                    assertTrue("style=SLIDE clean REVEALED artifact must transfer", slide.revealedArtifactForTest() === revealed)
                    assertTrue("style=SLIDE unrelated BACKWARD artifact must not join the overlay", backward !== front && backward !== revealed)
                    assertFalse(view.privateBool("activeFlipFrontPixelRefreshPending"))
                    assertFalse(view.privateBool("activeFlipRevealedPixelRefreshPending"))
                } else {
                    val front = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
                    val revealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
                    val backward = checkNotNull(view.privateField("cachedBackwardBitmap") as Bitmap?)
                    view.replacePendingInPlacePageShotRefreshSlotsForTest("BACKWARD")

                    assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
                    assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())
                    val drawable = checkNotNull(view.privateField("slideDrawable") ?: view.privateField("curlDrawable"))
                    assertTrue(
                        "style=$style clean FRONT must transfer even when BACKWARD is dirty",
                        drawable.privateBitmap("frontBitmap") === front,
                    )
                    assertTrue(
                        "style=$style clean REVEALED must transfer even when BACKWARD is dirty",
                        drawable.privateBitmap("revealedBitmap") === revealed,
                    )
                    assertFalse("style=$style unrelated BACKWARD owner must not join the overlay", backward === front)
                    assertFalse("style=$style unrelated BACKWARD owner must not join the overlay", backward === revealed)
                    assertFalse("style=$style clean front needs no deferred repaint", view.privateBool("activeFlipFrontPixelRefreshPending"))
                    assertFalse(
                        "style=$style clean target needs no deferred repaint",
                        view.privateBool("activeFlipRevealedPixelRefreshPending"),
                    )
                }
            } finally {
                if (view.privateField("interactiveTurnState").toString() != "NONE") {
                    view.endInteractiveCurl(velocityX = 0f)
                    shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
                }
                view.dispose()
            }
        }
    }

    @Test
    fun `dispose cancels required-dirty cold handoff without reviving stale warm owners`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        view.goToPage(fixture.headingPageIndex)
        shadowOf(Looper.getMainLooper()).idle()
        view.recycleCachedTexturesForTest()
        view.preCachePageTexturesForTest()
        val front = checkNotNull(view.slideFrontArtifactForTest())
        val revealed = checkNotNull(view.slideRevealedArtifactForTest())
        val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)

        view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
        assertNull(
            "PIXELS_ONLY must invalidate the dependent warm front artifact",
            view.slideFrontArtifactForTest(),
        )
        assertNull(
            "PIXELS_ONLY must invalidate the dependent warm revealed artifact",
            view.slideRevealedArtifactForTest(),
        )
        assertFalse("the invalidated front artifact must be released", front.slideArtifactRecordIsLive())
        assertFalse("the invalidated revealed artifact must be released", revealed.slideArtifactRecordIsLive())
        val downX = view.width * 0.85f
        val moveX = view.width * 0.45f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
        assertTrue(
            view.onInterceptTouchEvent(
                motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
            ),
        )
        assertEquals("LOCAL_SHOTS_WAITING", view.privateField("interactiveTurnState").toString())
        assertNull(view.privateField("slideDrawable"))
        assertFalse(view.privateBool("activeFlipFrontPixelRefreshPending"))
        assertFalse(view.privateBool("activeFlipRevealedPixelRefreshPending"))

        view.dispose()

        assertFalse(view.privateBool("activeFlipFrontPixelRefreshPending"))
        assertFalse(view.privateBool("activeFlipRevealedPixelRefreshPending"))
        assertNull("dispose must not revive the invalidated front artifact slot", view.slideFrontArtifactForTest())
        assertNull("dispose must not revive the invalidated revealed artifact slot", view.slideRevealedArtifactForTest())
        assertEquals(0L, budget.reservedBytes)
        assertEquals(0L, budget.leasedBytes)
        assertEquals(0L, budget.chargedBytes)
        // Late idle work must not resurrect stale warm artifacts or attempt draws on released owners.
        shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)
        assertFalse(view.privateBool("activeFlipFrontPixelRefreshPending"))
        assertFalse(view.privateBool("activeFlipRevealedPixelRefreshPending"))
    }

    @Test
    fun `cold local-shot handoff keeps live container anchored for horizontal and vertical release`() {
        for (vertical in listOf(false, true)) {
            lateinit var view: EpubFlowView
            val captureTranslations = mutableListOf<Pair<Float, Float>>()
            val background = RecordingTargetBitmapDrawable {
                val container = view.privateField("container") as View
                captureTranslations += container.translationX to container.translationY
            }
            view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
            view.background = background
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            background.targetBitmaps.clear()
            captureTranslations.clear()

            val axis = if (vertical) "vertical" else "horizontal"
            val downX = view.width * 0.85f
            val downY = if (vertical) view.height * 0.75f else view.height * 0.10f
            val moveX = if (vertical) downX else view.width * 0.55f
            val moveY = if (vertical) view.height * 0.45f else downY
            val downTime = SystemClock.uptimeMillis()
            val container = view.privateField("container") as View

            try {
                view.dispatchTouchEvent(
                    motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY),
                )
                assertTrue(
                    "$axis threshold MOVE must be intercepted",
                    view.onInterceptTouchEvent(
                        motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, moveY),
                    ),
                )
                assertEquals("LOCAL_SHOTS_WAITING", view.privateField("interactiveTurnState").toString())
                if (vertical) {
                    assertEquals("$axis MOVE feedback must follow the finger", moveY - downY, container.translationY, 0.001f)
                    assertEquals(0f, container.translationX, 0.001f)
                } else {
                    assertEquals("$axis MOVE feedback must follow the finger", moveX - downX, container.translationX, 0.001f)
                    assertEquals(0f, container.translationY, 0.001f)
                }

                shadowOf(Looper.getMainLooper()).runOneTask()
                assertTrue("$axis target capture must draw the viewport background", captureTranslations.isNotEmpty())
                assertTrue(
                    "$axis target capture must observe an anchored live container: $captureTranslations",
                    captureTranslations.all { (x, y) -> x == 0f && y == 0f },
                )
                assertNull("$axis target-only frame must not install slide", view.privateField("slideDrawable"))
                if (vertical) {
                    assertEquals(moveY - downY, container.translationY, 0.001f)
                    assertEquals(0f, container.translationX, 0.001f)
                } else {
                    assertEquals(moveX - downX, container.translationX, 0.001f)
                    assertEquals(0f, container.translationY, 0.001f)
                }

                view.onTouchEvent(
                    motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_UP, moveX, moveY),
                )
                assertEquals(
                    "$axis ACTION_UP must retain the cold handoff until the front shot is ready",
                    "LOCAL_SHOTS_WAITING",
                    view.privateField("interactiveTurnState").toString(),
                )
                if (vertical) {
                    assertEquals(moveY - downY, container.translationY, 0.001f)
                    assertEquals(0f, container.translationX, 0.001f)
                } else {
                    assertEquals(moveX - downX, container.translationX, 0.001f)
                    assertEquals(0f, container.translationY, 0.001f)
                }

                shadowOf(Looper.getMainLooper()).runOneTask()
                assertTrue(
                    "$axis split captures must always observe an anchored live container: $captureTranslations",
                    captureTranslations.size >= 2 && captureTranslations.all { (x, y) -> x == 0f && y == 0f },
                )
                assertNotNull("$axis deferred pair must install the local slide renderer", view.privateField("slideDrawable"))
                assertEquals(0f, container.translationX, 0.001f)
                assertEquals(0f, container.translationY, 0.001f)

                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
                assertEquals(0f, container.translationX, 0.001f)
                assertEquals(0f, container.translationY, 0.001f)
            } finally {
                view.dispose()
                assertEquals(0f, container.translationX, 0.001f)
                assertEquals(0f, container.translationY, 0.001f)
            }
        }
    }

    @Test
    fun `cold local-shot cancel before front capture keeps live page anchored and clears handoff`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        view.recycleCachedTexturesForTest()
        val downX = view.width * 0.85f
        val moveX = view.width * 0.55f
        val y = view.height * 0.10f
        val downTime = SystemClock.uptimeMillis()
        val container = view.privateField("container") as View

        try {
            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )
            assertEquals("LOCAL_SHOTS_WAITING", view.privateField("interactiveTurnState").toString())
            assertEquals(moveX - downX, container.translationX, 0.001f)
            assertEquals(0f, container.translationY, 0.001f)

            view.onTouchEvent(motionEvent(downTime, downTime + 48L, MotionEvent.ACTION_CANCEL, moveX, y))

            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertNull(view.privateField("pendingLocalPageShotHandoff"))
            assertEquals(0f, container.translationX, 0.001f)
            assertEquals(0f, container.translationY, 0.001f)
        } finally {
            view.dispose()
            assertEquals(0f, container.translationX, 0.001f)
            assertEquals(0f, container.translationY, 0.001f)
        }
    }

    @Test
    fun `active turn PIXELS_ONLY completion queues offsets without mid-turn redraw or alloc`() {
        EpubPageShotCaptureProbe.reset()
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            val front = checkNotNull(view.slideFrontArtifactForTest())
            val revealed = checkNotNull(view.slideRevealedArtifactForTest())
            val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)

            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            view.updateInteractiveCurl(x = view.width * 0.45f)
            assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())
            val leasedAtTurn = budget.leasedBytes
            val genAtTurn = view.privateField("slideArtifactPrecacheGeneration") as Long
            val capturesAtTurn = EpubPageShotCaptureProbe.total()

            // PIXELS_ONLY while overlay owns the turn: queue only, never mutate the active artifacts.
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)

            @Suppress("UNCHECKED_CAST")
            val queued = view.privateField("asyncImagePixelRefreshOffsets") as Set<Int>
            assertTrue(
                "active-turn PIXELS_ONLY must queue the image offset; queued=$queued",
                fixture.imageLayoutStart in queued,
            )
            assertTrue(
                "active turn must not discard the recorded active artifacts",
                front.slideArtifactRecordIsLive() && revealed.slideArtifactRecordIsLive(),
            )
            assertEquals(
                "active turn must not allocate or recycle page shots for PIXELS_ONLY",
                leasedAtTurn,
                budget.leasedBytes,
            )
            assertEquals(
                "active turn must not bump artifact precache generation",
                genAtTurn,
                view.privateField("slideArtifactPrecacheGeneration") as Long,
            )
            assertEquals(
                "active turn must not allocate any viewport page-shot bitmap",
                capturesAtTurn,
                EpubPageShotCaptureProbe.total(),
            )
            assertTrue(
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )

            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            view.drainInPlacePageShotRefreshForTest()

            assertTrue(
                "queued PIXELS_ONLY must drain after settle",
                (view.privateField("asyncImagePixelRefreshOffsets") as Set<*>).isEmpty(),
            )
            assertTrue(
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )
            // The deferred pixel batch invalidates the rekeyed artifacts; normal precache rebuilds.
            view.textView.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()
            assertNotNull(
                "settle must rebuild a live front artifact after the deferred pixel batch",
                view.slideFrontArtifactForTest()?.takeIf { it.slideArtifactRecordIsLive() },
            )
            assertEquals(
                "settle must not allocate viewport page-shot bitmaps",
                capturesAtTurn,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            if (view.privateField("interactiveTurnState").toString() != "NONE") {
                view.endInteractiveCurl(velocityX = 0f)
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            }
            view.dispose()
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `PIXELS_ONLY with no warm owner wakes normal nearby precache`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            assertNull(view.privateField("cachedFrontBitmap"))
            assertNull(view.privateField("cachedRevealedBitmap"))
            assertNull(view.privateField("cachedBackwardBitmap"))
            assertNull(view.privateField("pendingPageTexturePrecache"))
            assertFalse(view.privateBool("pageTexturePrecachePending"))
            assertNull(
                "cold cache must start without an async-image wake listener",
                view.privateField("asyncImageWakeListener"),
            )

            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)

            assertTrue(
                "no-warm-owner PIXELS_ONLY must not schedule in-place slot redraws",
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )
            assertNotNull(
                "no-warm-owner PIXELS_ONLY must install a pre-draw wake for normal nearby precache",
                view.privateField("asyncImageWakeListener"),
            )
            // Drive the wake path to pre-draw and confirm cold slots become warm.
            view.invalidate()
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)
            view.preCachePageTexturesForTest()
            assertNotNull(
                "after wake, normal precache must warm the front for the next gesture",
                view.privateField("cachedFrontBitmap"),
            )
            assertNotNull(
                "after wake, normal precache must warm the adjacent revealed page",
                view.privateField("cachedRevealedBitmap"),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `PIXELS_ONLY discarded partial precache refills missing slots after in-place drain`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            // Fully warm, then drop adjacent slots so a new split-frame precache retains front only.
            view.preCachePageTexturesForTest()
            val retainedFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
            val oldRevealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
            val oldBackward = checkNotNull(view.privateField("cachedBackwardBitmap") as Bitmap?)
            val budget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)
            view.detachCachedTextureOwnerForTest(oldRevealed)
            view.detachCachedTextureOwnerForTest(oldBackward)
            assertTrue(budget.release(oldRevealed))
            assertTrue(budget.release(oldBackward))
            if (!oldRevealed.isRecycled) oldRevealed.recycle()
            if (!oldBackward.isRecycled) oldBackward.recycle()
            view.setPrivateField("cachedRevealedBitmap", null)
            view.setPrivateField("cachedBackwardBitmap", null)
            view.setPrivateField("cachedTargetPage", -1)
            view.setPrivateField("cachedBackwardPage", -1)
            view.setPrivateField("cachedTargetTopPx", -1)
            view.setPrivateField("cachedBackwardTopPx", -1)
            view.setPrivateField("cachedTargetTextureKey", null)
            view.setPrivateField("cachedBackwardTextureKey", null)
            assertTrue(view.privateField("cachedFrontBitmap") === retainedFront)

            // Partial pending: retained front + mid-flight target/previous captures.
            view.preCachePageTexturesForTest(idlePostedWork = false)
            val pending = checkNotNull(view.privateField("pendingPageTexturePrecache")) {
                "incomplete cache must arm a split-frame precache"
            }
            assertTrue(view.privateBool("pageTexturePrecachePending"))
            assertTrue(
                "production path must retain the existing front owner on the pending request",
                pending.reflectedField("frontBitmap") === retainedFront &&
                    pending.reflectedField("frontBitmapRetained") == true,
            )
            assertNull(view.privateField("cachedRevealedBitmap"))
            assertNull(view.privateField("cachedBackwardBitmap"))

            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)

            assertNull(
                "PIXELS_ONLY must discard the mid-flight partial pending precache",
                view.privateField("pendingPageTexturePrecache"),
            )
            assertFalse(view.privateBool("pageTexturePrecachePending"))
            assertTrue(
                "retained warm front identity must stay for in-place refresh",
                view.privateField("cachedFrontBitmap") === retainedFront && !retainedFront.isRecycled,
            )
            assertTrue(
                "dependent front must be queued for in-place redraw",
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isNotEmpty(),
            )

            view.drainInPlacePageShotRefreshForTest()
            // Drain ends by invoking normal preCache; advance postOnAnimation frames to commit
            // adjacent slots (split-frame refill, same pattern as preCachePageTexturesForTest).
            view.drainPendingPageTexturePrecacheForTest()

            assertTrue(
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )
            assertTrue(
                "in-place path must keep the retained front owner after drain",
                view.privateField("cachedFrontBitmap") === retainedFront && !retainedFront.isRecycled,
            )
            assertNotNull(
                "missing revealed slot must be refilled after in-place queue drain",
                view.privateField("cachedRevealedBitmap"),
            )
            assertNotNull(
                "missing backward slot must be refilled after in-place queue drain",
                view.privateField("cachedBackwardBitmap"),
            )
            assertFalse(
                checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?).isRecycled,
            )
            assertFalse(
                checkNotNull(view.privateField("cachedBackwardBitmap") as Bitmap?).isRecycled,
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `PIXELS_ONLY transient redraw skip requeues and later paints - stale identity drops safely`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            val front = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
            val revealed = checkNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
            val marker = 0xFF00AAFF.toInt()
            front.eraseColor(marker)
            revealed.eraseColor(marker)

            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            @Suppress("UNCHECKED_CAST")
            val pendingBefore = (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).toSet()
            assertTrue(pendingBefore.isNotEmpty())
            assertTrue(view.privateBool("inPlacePageShotRefreshPosted"))

            // Transient gate: paginated height not settled — slot stays queued, pixels stay marker-filled.
            val settledHeight = view.privateInt("paginatedLayoutHeight")
            view.setPrivateField("paginatedLayoutHeight", settledHeight - 1)
            shadowOf(Looper.getMainLooper()).runOneTask()
            @Suppress("UNCHECKED_CAST")
            val pendingAfterTransient =
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).toSet()
            assertEquals(
                "transient layout skip must requeue the same slots, not silently drop them",
                pendingBefore,
                pendingAfterTransient,
            )
            assertEquals(
                "transient skip must not paint over warm pixels yet",
                marker,
                front.getPixel(0, 0),
            )
            assertTrue(
                "transient skip must re-post the one-slot-per-frame callback",
                view.privateBool("inPlacePageShotRefreshPosted") || pendingAfterTransient.isNotEmpty(),
            )

            // Clear the transient gate and finish the queue: slots must paint in place.
            view.setPrivateField("paginatedLayoutHeight", settledHeight)
            view.drainInPlacePageShotRefreshForTest()
            assertTrue(
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )
            assertTrue(
                "after transient clears, in-place refresh must replace marker fill on front",
                front.getPixel(0, 0) != marker ||
                    front.getPixel(front.width / 2, front.height / 2) != marker,
            )
            assertTrue(view.privateField("cachedFrontBitmap") === front)
            assertTrue(view.privateField("cachedRevealedBitmap") === revealed)

            // Stale identity: re-queue, corrupt page/top, drain — drop without hanging.
            view.onAsyncImagePixelsChanged(fixture.imageLayoutStart)
            assertTrue(
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isNotEmpty(),
            )
            view.setPrivateField("cachedFromPage", -1)
            view.setPrivateField("cachedFromTopPx", -1)
            view.drainInPlacePageShotRefreshForTest()
            assertTrue(
                "stale identity must drop from the queue without hanging",
                (view.privateField("pendingInPlacePageShotRefreshSlots") as Set<*>).isEmpty(),
            )
            assertFalse(
                "stale-drop path must not recycle an unrelated still-leased revealed owner",
                revealed.isRecycled,
            )
        } finally {
            view.dispose()
        }
    }


    @Test
    fun `body paragraph page shows cropped top of following large image while next page shows complete image`() {
        val viewportWidth = 360
        val viewportHeight = 240
        val imageColor = 0xFF2563EB.toInt()
        val sampleBandHeight = 24
        val imageHeight = viewportHeight + 120
        // Body only — no heading. Product: any preceding visible text + oversized next-page image.
        // Prefer geometries where the image is the immediate next page; tolerate a separator-
        // only intermediate window when the paginator inserts one (same as heading fixture).
        var selectedView: EpubFlowView? = null
        var selectedFlow: EpubChapterFlow? = null
        var selectedImageSegment: EpubFlowSegment? = null
        var selectedBodyPageIndex = -1
        var selectedImagePageIndex = -1
        var lastDiagnostics: String? = null
        for (bodyLines in 3..24) {
            val bodyText = (1..bodyLines).joinToString("\n") {
                "正文段落 $it 铺满本页，后面是不可拆分的大图。"
            }
            val flow = epubBuildChapterFlow(
                spineIndex = 0,
                blocks = listOf(
                    EpubDisplayBlock.Text(bodyText, headingLevel = null, paragraphIndex = 0),
                    EpubDisplayBlock.Image(
                        href = "body-plate.png",
                        altText = "body plate",
                        paragraphIndex = 1,
                        isInlineContent = false,
                    ),
                ),
            )
            val imageSegment = flow.segments.single { it.isImage }
            val bodySegment = flow.segments.single {
                val block = it.block
                block is EpubDisplayBlock.Text && block.headingLevel == null
            }
            val imageDrawable = ColorDrawable(imageColor).apply {
                setBounds(0, 0, viewportWidth, imageHeight)
            }
            val spannable = SpannableString(flow.text).apply {
                setSpan(
                    ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
                    imageSegment.layoutStart,
                    imageSegment.layoutEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            val view = pagedFlowView(
                flow = flow,
                spannable = spannable,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
            val layout = requireNotNull(view.textView.layout)
            val imageLine = layout.getLineForOffset(imageSegment.layoutStart)
            val imageLineBottom = layout.getLineBottom(imageLine)
            val imageLineTop = layout.getLineTop(imageLine)
            if (imageLineBottom - imageLineTop <= viewportHeight) {
                view.dispose()
                continue
            }
            val pages = view.privateField("paged") as List<EpubFlowPage>
            val bodyPageIndex = pages.indexOfLast { page ->
                // Last page that ends at/after some body text and before the image offset.
                page.endOffset > bodySegment.layoutStart &&
                    page.endOffset <= imageSegment.layoutStart
            }
            val imagePageIndex = pages.indexOfFirst { page ->
                imageSegment.layoutStart >= page.startOffset &&
                    imageSegment.layoutStart < page.endOffset
            }
            lastDiagnostics =
                "lines=$bodyLines bodyPage=$bodyPageIndex imagePage=$imagePageIndex pages=${pages.size} " +
                    pages.mapIndexed { i, p ->
                        "#$i[${p.startOffset},${p.endOffset}) lines=${p.startLine}..${p.endLineExclusive}"
                    }.joinToString(" ")
            if (bodyPageIndex >= 0 && imagePageIndex == bodyPageIndex + 1) {
                selectedView = view
                selectedFlow = flow
                selectedImageSegment = imageSegment
                selectedBodyPageIndex = bodyPageIndex
                selectedImagePageIndex = imagePageIndex
                break
            }
            view.dispose()
        }
        val view = selectedView ?: error(
            "unable to place body text + immediate oversized image page; last=$lastDiagnostics",
        )
        val flow = requireNotNull(selectedFlow)
        val imageSegment = requireNotNull(selectedImageSegment)
        val bodyPageIndex = selectedBodyPageIndex
        val imagePageIndex = selectedImagePageIndex
        try {
            val layout = requireNotNull(view.textView.layout)
            val imageLine = layout.getLineForOffset(imageSegment.layoutStart)
            val imageLineTop = layout.getLineTop(imageLine)
            val imageLineBottom = layout.getLineBottom(imageLine)
            assertTrue(
                "fixture needs a non-inline image taller than the viewport",
                imageLineBottom - imageLineTop > viewportHeight,
            )
            val pages = view.privateField("paged") as List<EpubFlowPage>
            assertTrue(
                "body page must precede the complete-image page",
                bodyPageIndex < imagePageIndex,
            )
            assertEquals(
                "complete image should be the immediate next page after the body remainder page",
                bodyPageIndex + 1,
                imagePageIndex,
            )

            val bodyPageTop = requireNotNull(view.pageTopPxAt(bodyPageIndex))
            val bodyPage = pages[bodyPageIndex]
            val lastBodyLine = bodyPage.endLineExclusive - 1
            val bodyBottomOnPage = layout.getLineBottom(lastBodyLine) - bodyPageTop
            assertTrue(
                "body content must leave leftover space under the last line",
                bodyBottomOnPage in 1 until viewportHeight,
            )
            assertTrue(
                "complete image must not fit under the body on the same page",
                imageLineBottom - bodyPageTop > viewportHeight,
            )

            // Single occurrence identity stays stable (one U+FFFC / one image segment).
            assertEquals(1, flow.segments.count { it.isImage })
            assertEquals(1, flow.text.count { it == EPUB_FLOW_IMAGE_CHAR })
            assertEquals(1 to 0, flow.paragraphAtOffset(imageSegment.layoutStart))

            view.goToPage(bodyPageIndex)
            val bodySnapshot = requireNotNull(view.snapshotPageAt(bodyPageTop))
            val snapshotRemainderSamples = sampleImagePatternHits(
                bitmap = bodySnapshot,
                left = viewportWidth / 4,
                top = (bodyBottomOnPage + 4).coerceAtMost(viewportHeight - 2),
                right = (viewportWidth * 3) / 4,
                bottom = viewportHeight - 2,
                stripeA = imageColor,
                stripeB = imageColor,
            )
            val bodyPageShot = view.drawToBitmapForTest()
            try {
                assertTrue(
                    "page-turn snapshot must include the cropped next-page image preview; " +
                        "hits=$snapshotRemainderSamples bodyBottomOnPage=$bodyBottomOnPage",
                    snapshotRemainderSamples > 0,
                )
                val remainderSamples = sampleImagePatternHits(
                    bitmap = bodyPageShot,
                    left = viewportWidth / 4,
                    top = (bodyBottomOnPage + 4).coerceAtMost(viewportHeight - 2),
                    right = (viewportWidth * 3) / 4,
                    bottom = viewportHeight - 2,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue(
                    "body page must paint a cropped top preview of the following large image " +
                        "in the leftover remainder; hits=$remainderSamples " +
                        "snapshotHits=$snapshotRemainderSamples bodyBottomOnPage=$bodyBottomOnPage " +
                        "bodyPage=$bodyPageIndex imagePage=$imagePageIndex pageCount=${view.pageCount()}",
                    remainderSamples > 0,
                )
            } finally {
                bodyPageShot.recycle()
                bodySnapshot.recycle()
            }

            view.goToPage(imagePageIndex)
            val completeImageShot = view.drawToBitmapForTest()
            try {
                val topBandHits = sampleImagePatternHits(
                    bitmap = completeImageShot,
                    left = viewportWidth / 4,
                    top = 2,
                    right = (viewportWidth * 3) / 4,
                    bottom = sampleBandHeight,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue(
                    "next page must still render the complete image from its top; topBandHits=$topBandHits",
                    topBandHits > 0,
                )
                assertEquals(
                    "complete image page must start at the source image line top",
                    imageLineTop,
                    requireNotNull(view.pageTopPxAt(imagePageIndex)),
                )
            } finally {
                completeImageShot.recycle()
            }

            // Page count / locator identity must remain single-occurrence stable.
            assertEquals(1, (view.privateField("flow") as EpubChapterFlow).segments.count { it.isImage })
            assertEquals(1, (view.privateField("flow") as EpubChapterFlow).text.count { it == EPUB_FLOW_IMAGE_CHAR })
        } finally {
            view.dispose()
        }
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
    fun `isPageMutationInFlight is true during free fling and false after temporary rest settle`() {
        val settles = AtomicInteger(0)
        val view = pagedFlowView()
        view.onPageSettled = { settles.incrementAndGet() }
        view.goToPage(1)
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))
        assertTrue(
            "free fling must report page mutation in flight so font prewarm cannot rebuild mid-fling",
            view.isPageMutationInFlight(),
        )
        view.setPrivateField("freeFlingStartedAtMs", SystemClock.uptimeMillis() - 100L)
        view.fling(0)
        view.setPrivateField("freeFlingStableFrames", 0)
        view.computeScroll()
        view.computeScroll()
        assertEquals("FREE_REST", view.privateEnumName("pagedMotionState"))
        assertFalse(
            "temporary free-scroll rest must clear the mutation gate",
            view.isPageMutationInFlight(),
        )
        assertTrue(
            "finishTemporaryScrollRest must invoke onPageSettled so deferred rebuilds drain",
            settles.get() >= 1,
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
    fun `programmatic scroll aborts free fling before applying target viewport`() {
        val view = pagedFlowView()
        view.goToPage(1)
        view.releaseTemporaryScrollForTest(fingerVelocityY = -4_000f)
        assertEquals("FLING_FREE", view.privateEnumName("pagedMotionState"))

        view.scrollTo(0, 0)
        val trace = view.computeScrollTraceWithoutPostedWork()

        assertEquals(
            "external scrollTo must apply immediately; state=${view.privateEnumName("pagedMotionState")}",
            0,
            view.scrollY,
        )
        assertTrue(
            "external scrollTo must retire the old native trajectory; trace=$trace state=${view.privateEnumName("pagedMotionState")}",
            trace.all { it == 0 } && view.privateEnumName("pagedMotionState") == "FREE_REST",
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
    fun `inner center horizontal drag stays in the protected center zone`() {
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

        assertEquals("protected center drag must not become a tap or page turn", emptyList<EpubFlowTapZone>(), tapZones)
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
    fun `viewport resize refreshes existing full page async drawable bounds before repagination`() {
        var view: EpubFlowView? = null
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = 800,
            columnWidthProvider = { view?.width?.takeIf { it > 0 } ?: 800 },
            pageHeightProvider = { view?.height?.takeIf { it > 0 } ?: 1200 },
            inlineMaxHeightPx = 720,
            fullPageHrefs = setOf("plate.png"),
        )
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val drawable = AsyncDrawable("plate.png", AsyncDrawableLoader.noOp(), resolver, null).apply {
            initWithKnownDimensions(800, 20f)
            setResult(BitmapDrawable(null, bitmap))
        }
        val spannable = SpannableString("\uFFFC").apply {
            setSpan(
                AsyncDrawableSpan(
                    io.noties.markwon.core.MarkwonTheme.create(RuntimeEnvironment.getApplication()),
                    drawable,
                    AsyncDrawableSpan.ALIGN_CENTER,
                    false,
                ),
                0,
                1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        try {
            val flowView = pagedFlowView(
                spannable = spannable,
                viewportWidth = 800,
                viewportHeight = 1200,
            )
            view = flowView
            assertEquals(Rect(0, 0, 800, 450), drawable.bounds)

            flowView.layoutParams.width = 600
            flowView.layoutParams.height = 800
            flowView.measure(exactly(600), exactly(800))
            flowView.layout(0, 0, 600, 800)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(Rect(0, 0, 600, 338), drawable.bounds)
            assertEquals(600, requireNotNull(flowView.textView.layout).getLineWidth(0).toInt())
        } finally {
            view?.dispose()
            bitmap.recycle()
        }
    }

    @Test
    fun `stale viewport resize posts do not mutate a replacement chapter`() {
        val view = pagedFlowView(viewportWidth = 800, viewportHeight = 1200)
        assertTrue(view.pageCount() > 2)
        view.scrollTo(0, requireNotNull(view.pageTopPxAt(2)))
        assertTrue(view.topLayoutOffset() > 0)

        view.layoutParams.width = 600
        view.layoutParams.height = 800
        view.measure(exactly(600), exactly(800))
        view.layout(0, 0, 600, 800)

        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = 800,
            columnWidthProvider = { view.width.coerceAtLeast(1) },
            pageHeightProvider = { view.height.coerceAtLeast(1) },
            inlineMaxHeightPx = 720,
            fullPageHrefs = setOf("new-plate.png"),
        )
        val result = ColorDrawable(0xFF336699.toInt()).apply { setBounds(0, 0, 320, 180) }
        val drawable = AsyncDrawable("new-plate.png", AsyncDrawableLoader.noOp(), resolver, null).apply {
            initWithKnownDimensions(600, 20f)
            setResult(result)
            result.setBounds(0, 0, 320, 180)
            setBounds(0, 0, 320, 180)
        }
        val newFlow = epubBuildChapterFlow(
            spineIndex = 1,
            blocks = listOf(
                EpubDisplayBlock.Image("new-plate.png", altText = null, paragraphIndex = 0),
                EpubDisplayBlock.Text(
                    text = (1..80).joinToString("\n") { "Replacement chapter line $it" },
                    headingLevel = null,
                    paragraphIndex = 1,
                ),
            ),
        )
        val newSpannable = SpannableString(newFlow.text).apply {
            val imageSegment = newFlow.segments.first { it.isImage }
            setSpan(
                AsyncDrawableSpan(
                    io.noties.markwon.core.MarkwonTheme.create(RuntimeEnvironment.getApplication()),
                    drawable,
                    AsyncDrawableSpan.ALIGN_CENTER,
                    false,
                ),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        try {
            view.setChapter(newFlow, newSpannable, pageHeightPx = 800, restoreOffset = 0)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(Rect(0, 0, 320, 180), drawable.bounds)
            assertEquals(0, view.topLayoutOffset())
            assertFalse(view.privateBool("awaitingReveal"))
        } finally {
            view.dispose()
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

    @Test
    fun `heading page shows cropped top of following large image while next page shows complete image`() {
        val viewportWidth = 360
        val viewportHeight = 240
        val imageColor = 0xFFE11D48.toInt()
        val sampleBandHeight = 24
        val imageHeight = viewportHeight + 120
        // Keep the ImageSpan near the first viewport: Robolectric does not reliably paint a plain
        // DynamicDrawableSpan hundreds of pixels down a tall TextView, while device TextView does.
        val bodyText = (1..2).joinToString("\n") { "前置正文 $it 把标题推到页底附近。" }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Text(bodyText, headingLevel = null, paragraphIndex = 0),
                EpubDisplayBlock.Text("图版标题", headingLevel = 2, paragraphIndex = 1),
                EpubDisplayBlock.Image(
                    href = "plate-pattern.png",
                    altText = "patterned plate",
                    paragraphIndex = 2,
                    isInlineContent = false,
                ),
            ),
        )
        val imageSegment = flow.segments.single { it.isImage }
        val headingSegment = flow.segments.single {
            val block = it.block
            block is EpubDisplayBlock.Text && block.headingLevel != null
        }
        val imageDrawable = ColorDrawable(imageColor).apply {
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val spannable = SpannableString(flow.text).apply {
            setSpan(
                ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(
            flow = flow,
            spannable = spannable,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        try {
            val layout = requireNotNull(view.textView.layout)
            val headingLine = layout.getLineForOffset(headingSegment.layoutStart)
            val imageLine = layout.getLineForOffset(imageSegment.layoutStart)
            val imageLineTop = layout.getLineTop(imageLine)
            val imageLineBottom = layout.getLineBottom(imageLine)
            assertTrue(
                "fixture needs a non-inline image taller than the viewport",
                imageLineBottom - imageLineTop > viewportHeight,
            )

            val headingPageIndex = (0 until view.pageCount()).firstOrNull { pageIndex ->
                val top = requireNotNull(view.pageTopPxAt(pageIndex))
                val nextTop = view.pageTopPxAt(pageIndex + 1) ?: (layout.height + 1)
                layout.getLineTop(headingLine) in top until nextTop
            } ?: error("heading must land on some paged window")
            val headingPageTop = requireNotNull(view.pageTopPxAt(headingPageIndex))
            val nextPageIndex = headingPageIndex + 1
            assertTrue(
                "fixture needs a page after the heading for the complete image",
                nextPageIndex < view.pageCount(),
            )
            val nextPageTop = requireNotNull(view.pageTopPxAt(nextPageIndex))
            assertEquals(
                "the complete-image page must start at the source image line top",
                imageLineTop,
                nextPageTop,
            )

            assertTrue(
                "heading line must start on the heading page",
                layout.getLineTop(headingLine) >= headingPageTop,
            )
            val headingBottomOnPage = layout.getLineBottom(headingLine) - headingPageTop
            assertTrue(
                "heading itself must fit inside the viewport on its page",
                headingBottomOnPage in 1 until viewportHeight,
            )
            assertTrue(
                "complete image must not fit under the heading on the same page",
                imageLineBottom - headingPageTop > viewportHeight,
            )

            view.goToPage(headingPageIndex)
            val headingSnapshot = requireNotNull(view.snapshotPageAt(headingPageTop))
            val snapshotRemainderSamples = sampleImagePatternHits(
                bitmap = headingSnapshot,
                left = viewportWidth / 4,
                top = (headingBottomOnPage + 4).coerceAtMost(viewportHeight - 2),
                right = (viewportWidth * 3) / 4,
                bottom = viewportHeight - 2,
                stripeA = imageColor,
                stripeB = imageColor,
            )
            val headingPageShot = view.drawToBitmapForTest()
            try {
                assertTrue(
                    "page-turn snapshot must include the same cropped image preview; " +
                        "hits=$snapshotRemainderSamples",
                    snapshotRemainderSamples > 0,
                )
                val remainderSamples = sampleImagePatternHits(
                    bitmap = headingPageShot,
                    left = viewportWidth / 4,
                    top = (headingBottomOnPage + 4).coerceAtMost(viewportHeight - 2),
                    right = (viewportWidth * 3) / 4,
                    bottom = viewportHeight - 2,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue(
                    "heading page must paint a cropped top preview of the following large image " +
                        "in the otherwise blank remainder; hits=$remainderSamples " +
                        "snapshotHits=$snapshotRemainderSamples " +
                        "headingBottomOnPage=$headingBottomOnPage pageCount=${view.pageCount()} " +
                        "headingPage=$headingPageIndex nextTop=$nextPageTop " +
                        "pageClipActive=${view.privateBool("pageClipActive")} " +
                        "activeWindow=${view.privateField("activePageWindow")}",
                    remainderSamples > 0,
                )
            } finally {
                headingPageShot.recycle()
                headingSnapshot.recycle()
            }

            view.goToPage(nextPageIndex)
            val completeImageShot = view.drawToBitmapForTest()
            try {
                val topBandHits = sampleImagePatternHits(
                    bitmap = completeImageShot,
                    left = viewportWidth / 4,
                    top = 2,
                    right = (viewportWidth * 3) / 4,
                    bottom = sampleBandHeight,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                val fullPageHits = sampleImagePatternHits(
                    bitmap = completeImageShot,
                    left = viewportWidth / 4,
                    top = 0,
                    right = (viewportWidth * 3) / 4,
                    bottom = viewportHeight,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue(
                    "next page must render the complete image from its top; topBandHits=$topBandHits " +
                        "fullPageHits=$fullPageHits nextPageTop=$nextPageTop imageLineTop=$imageLineTop " +
                        "scrollY=${view.scrollY} currentPage=${view.currentPageIndex()}",
                    topBandHits > 0,
                )
                assertEquals(
                    "complete image page must start at the image's top pixels, not a mid-image crop",
                    imageColor,
                    completeImageShot.getPixel(viewportWidth / 2, 4),
                )
            } finally {
                completeImageShot.recycle()
            }

            val flowAfter = view.privateField("flow") as EpubChapterFlow
            assertEquals(1, flowAfter.segments.count { it.isImage })
            assertEquals(2 to 0, flowAfter.paragraphAtOffset(imageSegment.layoutStart))
            assertEquals(1 to 0, flowAfter.paragraphAtOffset(headingSegment.layoutStart))
            assertEquals(1, flowAfter.text.count { it == EPUB_FLOW_IMAGE_CHAR })
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `image starting at page top never paints a synthetic crop preview`() {
        val viewportWidth = 360
        val viewportHeight = 240
        val imageColor = 0xFF0EA5E9.toInt()
        val imageHeight = viewportHeight + 80
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Image(
                    href = "top-plate.png",
                    altText = "full page plate",
                    paragraphIndex = 0,
                    isInlineContent = false,
                ),
                EpubDisplayBlock.Text("后文段落", headingLevel = null, paragraphIndex = 1),
            ),
        )
        val imageSegment = flow.segments.single { it.isImage }
        val imageDrawable = ColorDrawable(imageColor).apply {
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val spannable = SpannableString(flow.text).apply {
            setSpan(
                ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(
            flow = flow,
            spannable = spannable,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        try {
            val layout = requireNotNull(view.textView.layout)
            val imageLine = layout.getLineForOffset(imageSegment.layoutStart)
            assertEquals("fixture requires the image on the first layout line", 0, imageLine)
            val pages = view.privateField("paged") as List<EpubFlowPage>
            val imagePage = pages.first { page ->
                imageSegment.layoutStart >= page.startOffset &&
                    imageSegment.layoutStart < page.endOffset
            }
            assertEquals(
                "image-at-top page must begin at the image line",
                imageLine,
                imagePage.startLine,
            )
            view.goToPage(0)
            val shot = view.drawToBitmapForTest()
            try {
                // Full image owns the page: top band is solid image pixels, not a leftover crop band.
                assertEquals(imageColor, shot.getPixel(viewportWidth / 2, 4))
                val fullHits = sampleImagePatternHits(
                    bitmap = shot,
                    left = viewportWidth / 4,
                    top = 0,
                    right = (viewportWidth * 3) / 4,
                    bottom = viewportHeight,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue("image-only top page must paint the complete image; hits=$fullHits", fullHits > 0)
            } finally {
                shot.recycle()
            }
            assertEquals(1, flow.segments.count { it.isImage })
            assertEquals(1, flow.text.count { it == EPUB_FLOW_IMAGE_CHAR })
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `heading with small leftover band still crops a visible first page preview`() {
        val viewportWidth = 360
        val viewportHeight = 240
        val imageColor = 0xFF7C3AED.toInt()
        val sampleBandHeight = 24
        val imageHeight = viewportHeight + 160
        // Keep the ImageSpan near the first viewport for reliable Robolectric paint, but use
        // slightly more body than the main crop fixture so the leftover band under the heading
        // is a tight strip rather than half a page.
        val bodyText = (1..3).joinToString("\n") { "填充行 $it 把标题挤到页底附近。" }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Text(bodyText, headingLevel = null, paragraphIndex = 0),
                EpubDisplayBlock.Text("小余量标题", headingLevel = 2, paragraphIndex = 1),
                EpubDisplayBlock.Image(
                    href = "small-band.png",
                    altText = "small band plate",
                    paragraphIndex = 2,
                    isInlineContent = false,
                ),
            ),
        )
        val imageSegment = flow.segments.single { it.isImage }
        val headingSegment = flow.segments.single {
            val block = it.block
            block is EpubDisplayBlock.Text && block.headingLevel != null
        }
        val imageDrawable = ColorDrawable(imageColor).apply {
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val spannable = SpannableString(flow.text).apply {
            setSpan(
                ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(
            flow = flow,
            spannable = spannable,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        try {
            val layout = requireNotNull(view.textView.layout)
            val headingLine = layout.getLineForOffset(headingSegment.layoutStart)
            val imageLine = layout.getLineForOffset(imageSegment.layoutStart)
            val imageLineTop = layout.getLineTop(imageLine)

            val headingPageIndex = (0 until view.pageCount()).firstOrNull { pageIndex ->
                val top = requireNotNull(view.pageTopPxAt(pageIndex))
                val nextTop = view.pageTopPxAt(pageIndex + 1) ?: (layout.height + 1)
                layout.getLineTop(headingLine) in top until nextTop
            } ?: error("heading must land on some paged window")
            val headingPageTop = requireNotNull(view.pageTopPxAt(headingPageIndex))
            val headingPageEndTop = view.pageTopPxAt(headingPageIndex + 1) ?: (layout.height + 1)
            assertTrue(
                "image must start after the heading page (crop path)",
                imageLineTop >= headingPageEndTop,
            )

            val imagePageIndex = (0 until view.pageCount()).firstOrNull { pageIndex ->
                val top = requireNotNull(view.pageTopPxAt(pageIndex))
                val nextTop = view.pageTopPxAt(pageIndex + 1) ?: (layout.height + 1)
                imageLineTop in top until nextTop
            } ?: error("image must land on some paged window")
            assertTrue(
                "complete image must own a later page than the heading",
                imagePageIndex > headingPageIndex,
            )
            val imagePageTop = requireNotNull(view.pageTopPxAt(imagePageIndex))
            assertEquals(
                "complete image page must start at the source image line top",
                imageLineTop,
                imagePageTop,
            )

            val headingBottomOnPage = layout.getLineBottom(headingLine) - headingPageTop
            assertTrue(
                "heading itself must fit inside the viewport on its page",
                headingBottomOnPage in 1 until viewportHeight,
            )
            val leftoverBand = viewportHeight - headingBottomOnPage
            // "Small" means the remainder is a minority of the viewport (not half-empty blank).
            assertTrue(
                "fixture needs a small leftover band under the heading; leftoverBand=$leftoverBand",
                leftoverBand in 8..(viewportHeight / 2),
            )

            view.goToPage(headingPageIndex)
            val headingPageShot = view.drawToBitmapForTest()
            try {
                val remainderHits = sampleImagePatternHits(
                    bitmap = headingPageShot,
                    left = viewportWidth / 4,
                    top = (headingBottomOnPage + 2).coerceAtMost(viewportHeight - 2),
                    right = (viewportWidth * 3) / 4,
                    bottom = viewportHeight - 1,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue(
                    "small leftover band must still paint a visible crop preview; " +
                        "hits=$remainderHits leftoverBand=$leftoverBand " +
                        "headingBottomOnPage=$headingBottomOnPage headingPage=$headingPageIndex",
                    remainderHits > 0,
                )
            } finally {
                headingPageShot.recycle()
            }

            view.goToPage(imagePageIndex)
            val complete = view.drawToBitmapForTest()
            try {
                val topBandHits = sampleImagePatternHits(
                    bitmap = complete,
                    left = viewportWidth / 4,
                    top = 2,
                    right = (viewportWidth * 3) / 4,
                    bottom = sampleBandHeight,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue(
                    "next page must still render the complete image from its top; " +
                        "topBandHits=$topBandHits imagePage=$imagePageIndex " +
                        "imagePageTop=$imagePageTop scrollY=${view.scrollY}",
                    topBandHits > 0,
                )
                assertEquals(
                    "complete image page must start at the image's top pixels",
                    imageColor,
                    complete.getPixel(viewportWidth / 2, 4),
                )
            } finally {
                complete.recycle()
            }
            assertEquals(1, flow.segments.count { it.isImage })
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `boundary image preview remains stable while async pixels arrive mid page turn`() {
        val viewportWidth = 360
        val viewportHeight = 240
        val imageColor = 0xFF059669.toInt()
        val imageHeight = viewportHeight + 120
        val bodyText = (1..2).joinToString("\n") { "前置 $it" }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Text(bodyText, headingLevel = null, paragraphIndex = 0),
                EpubDisplayBlock.Text("翻页中标题", headingLevel = 2, paragraphIndex = 1),
                EpubDisplayBlock.Image(
                    href = "mid-turn.png",
                    altText = "mid turn plate",
                    paragraphIndex = 2,
                    isInlineContent = false,
                ),
            ),
        )
        val imageSegment = flow.segments.single { it.isImage }
        val headingSegment = flow.segments.single {
            val block = it.block
            block is EpubDisplayBlock.Text && block.headingLevel != null
        }
        val imageDrawable = ColorDrawable(imageColor).apply {
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val spannable = SpannableString(flow.text).apply {
            setSpan(
                ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(
            flipStyle = PageFlipStyle.SLIDE,
            flow = flow,
            spannable = spannable,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        try {
            val layout = requireNotNull(view.textView.layout)
            val headingLine = layout.getLineForOffset(headingSegment.layoutStart)
            val headingPageIndex = (0 until view.pageCount()).first { pageIndex ->
                val top = requireNotNull(view.pageTopPxAt(pageIndex))
                val nextTop = view.pageTopPxAt(pageIndex + 1) ?: (layout.height + 1)
                layout.getLineTop(headingLine) in top until nextTop
            }
            view.goToPage(headingPageIndex)
            val headingPageTop = requireNotNull(view.pageTopPxAt(headingPageIndex))
            val lastLine = (view.privateField("paged") as List<EpubFlowPage>)[headingPageIndex]
                .endLineExclusive - 1
            val leftoverTop = layout.getLineBottom(lastLine) - headingPageTop
            assertTrue(leftoverTop in 1 until viewportHeight)

            val warmFront = requireNotNull(view.snapshotPageAt(headingPageTop))
            view.setPrivateField("cachedFrontBitmap", warmFront)
            view.setPrivateField("cachedFromPage", headingPageIndex)

            assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
            view.updateInteractiveCurl(x = view.width * 0.45f)
            assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())
            val slideBefore = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            val genBefore = view.privateField("pageLayoutGeneration") as Long

            // Async pixels during turn must not tear the boundary crop owner or repaginate.
            view.onAsyncImagePixelsChanged(imageSegment.layoutStart)
            view.refreshAfterAsyncImageResult()
            shadowOf(Looper.getMainLooper()).idleFor(120L, TimeUnit.MILLISECONDS)

            assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())
            assertTrue(
                "mid-turn async must keep the same slide owner",
                view.privateField("slideDrawable") === slideBefore,
            )
            assertEquals(
                "mid-turn async must not repaginate while turnInFlight",
                genBefore,
                view.privateField("pageLayoutGeneration") as Long,
            )
            assertTrue(
                "geometry refresh must stay deferred for the whole turn",
                view.privateBool("asyncImageRefreshPending") ||
                    (view.privateField("asyncImagePixelRefreshOffsets") as Set<*>).isNotEmpty(),
            )

            view.endInteractiveCurl(velocityX = 0f)
            val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
            if (animator != null && animator.isRunning) animator.end()
            shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)

            // After settle, heading page still shows a crop and image page still owns the full plate.
            view.goToPage(headingPageIndex)
            val afterShot = view.drawToBitmapForTest()
            try {
                val hits = sampleImagePatternHits(
                    bitmap = afterShot,
                    left = viewportWidth / 4,
                    top = (leftoverTop + 2).coerceAtMost(viewportHeight - 2),
                    right = (viewportWidth * 3) / 4,
                    bottom = viewportHeight - 1,
                    stripeA = imageColor,
                    stripeB = imageColor,
                )
                assertTrue("post-turn heading page must still show crop preview; hits=$hits", hits > 0)
            } finally {
                afterShot.recycle()
            }
            assertEquals(1, flow.segments.count { it.isImage })
        } finally {
            if (view.privateField("interactiveTurnState").toString() != "NONE") {
                view.endInteractiveCurl(velocityX = 0f)
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            }
            view.dispose()
        }
    }

    @Test
    fun `async image result with unchanged geometry preserves active software slide turn`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.scrollTo(0, requireNotNull(view.pageTopPxAt(0)))

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.40f)
        val slideBefore = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
        val progressBefore = slideBefore.progress
        val originBefore = view.privateField("curlOrigin")
        val scrollBefore = view.scrollY
        assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())
        assertTrue(progressBefore > 0.05f)

        try {
            view.refreshAfterAsyncImageResult()
            shadowOf(Looper.getMainLooper()).idle()

            val slideAfter = view.privateField("slideDrawable") as PageSlideDrawable?
            assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())
            assertTrue("the same overlay must remain installed", slideAfter === slideBefore)
            assertEquals(progressBefore, requireNotNull(slideAfter).progress, 0.001f)
            assertTrue(view.privateField("curlOrigin") === originBefore && originBefore != null)
            assertEquals(scrollBefore, view.scrollY)
        } finally {
            if (view.privateField("interactiveTurnState").toString() != "NONE") {
                view.endInteractiveCurl(velocityX = 0f)
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            }
            view.dispose()
        }
    }

    @Test
    fun `multiple geometry changes during active turn coalesce into one post turn refresh`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        view.scrollTo(0, requireNotNull(view.pageTopPxAt(0)))

        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.40f)
        assertEquals("SOFTWARE", view.privateField("interactiveTurnState").toString())

        try {
            val genAtTurnStart = view.privateField("pageTexturePrecacheGeneration") as Long

            // Several GEOMETRY_CHANGED completions while the finger owns the turn.
            view.refreshAfterAsyncImageResult()
            view.refreshAfterAsyncImageResult()
            view.refreshAfterAsyncImageResult()
            // Debounce windows would have fired if not turn-gated; they must re-arm instead of applying.
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

            assertTrue(
                "coalesced geometry flag must stay armed for the whole turn",
                view.privateBool("asyncImageRefreshPending"),
            )
            assertEquals(
                "geometry completions must not apply a full refresh while the turn is active",
                genAtTurnStart,
                view.privateField("pageTexturePrecacheGeneration") as Long,
            )

            // Complete the settle; the deferred flag stays set until the debounced post-turn runnable runs.
            view.endInteractiveCurl(velocityX = 0f)
            val animator = view.privateField("flipAnimator") as android.animation.ValueAnimator?
            if (animator != null && animator.isRunning) {
                animator.end()
            }
            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            // The settle callback runs synchronously; the debounced post-turn runnable has not
            // executed yet, so the coalesced flag must still be armed. (No idle() here: an idle
            // would drain the already-due debounce and make this timing assertion vacuous.)
            assertTrue(
                "deferred flag must still be set until the post-turn refresh runnable runs",
                view.privateBool("asyncImageRefreshPending"),
            )
            val genAtTurnEnd = view.privateField("pageTexturePrecacheGeneration") as Long

            // Single coalesced apply (REFLOW_DEBOUNCE_MS = 80). Full refresh may bump generation more
            // than once (recycle + precache restart); the contract is one apply, not +1 generation.
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)

            assertFalse(
                "the coalesced flag must clear after the single post-turn refresh",
                view.privateBool("asyncImageRefreshPending"),
            )
            val genAfterFirstApply = view.privateField("pageTexturePrecacheGeneration") as Long
            assertTrue(
                "post-turn full refresh must run exactly once for the coalesced mid-turn batch",
                genAfterFirstApply > genAtTurnEnd,
            )

            // No second geometry-driven full refresh is scheduled from the same batch.
            shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
            assertFalse(view.privateBool("asyncImageRefreshPending"))
            assertEquals(
                "a second full geometry refresh must not fire after the coalesced batch is drained",
                genAfterFirstApply,
                view.privateField("pageTexturePrecacheGeneration") as Long,
            )
        } finally {
            if (view.privateField("interactiveTurnState").toString() != "NONE") {
                view.endInteractiveCurl(velocityX = 0f)
                shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
            }
            view.dispose()
        }
    }

    @Test
    fun `setChapter drops deferred async image work owned by the previous chapter`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        assertTrue(view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()))
        view.updateInteractiveCurl(x = view.width * 0.40f)

        view.refreshAfterAsyncImageResult()
        view.onAsyncImagePixelsChanged(pages.first().startOffset)
        assertTrue(view.privateBool("asyncImageRefreshPending"))
        @Suppress("UNCHECKED_CAST")
        val queuedOffsets = view.privateField("asyncImagePixelRefreshOffsets") as Set<Int>
        assertTrue(queuedOffsets.isNotEmpty())

        view.pageTexturePrecacheEnabled = false
        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            view.textView.text,
            pageHeightPx = 120,
            restoreOffset = pages.first().startOffset,
        )

        assertFalse(
            "a new chapter must not inherit a geometry refresh posted by the previous chapter",
            view.privateBool("asyncImageRefreshPending"),
        )
        assertTrue(
            "a new chapter must not inherit pixel offsets from the previous chapter",
            (view.privateField("asyncImagePixelRefreshOffsets") as Set<*>).isEmpty(),
        )
        shadowOf(Looper.getMainLooper()).idleFor(200L, TimeUnit.MILLISECONDS)
        assertFalse(view.privateBool("asyncImageRefreshPending"))
        view.dispose()
    }

    @Test
    fun `relevant pending decode ranges cover current and adjacent pages only`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        view.scrollTo(0, pages[1].topPx)
        // Park on canonical page 1 so prev=0, current=1, next=2.
        view.setPrivateField("activePageWindow", pages[1])
        view.setPrivateField("currentPage", 1)

        val ranges = view.relevantPendingDecodeLayoutRanges()
        assertEquals(
            "visible current plus previous/next must yield three layout-offset ranges",
            3,
            ranges.size,
        )
        assertTrue("previous page range", ranges.any { pages[0].startOffset in it && pages[0].endOffset - 1 in it })
        assertTrue("current page range", ranges.any { pages[1].startOffset in it && pages[1].endOffset - 1 in it })
        assertTrue("next page range", ranges.any { pages[2].startOffset in it && pages[2].endOffset - 1 in it })
        assertFalse(
            "far page offsets must not be included",
            ranges.any { pages[3].startOffset in it },
        )
        view.dispose()
    }

    @Test
    fun `rapid decode admission adds one directional runway page within a four-page cap`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 6)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        view.goToPage(2)
        var windowUpdates = 0
        view.onDecodeWindowNeeded = { windowUpdates++ }

        view.acceptPromotedPageTurns(delta = 8, rapidSequence = true)
        val ranges = view.decodeAdmissionLayoutRanges()

        assertEquals("queued intent must publish the expanded admission window", 1, windowUpdates)
        listOf(1, 2, 3, 4).forEach { index ->
            assertTrue("page $index must be admitted", ranges.any { pages[index].startOffset in it })
        }
        assertFalse("the opposite runway must stay bounded", ranges.any { pages[0].startOffset in it })
        assertFalse("a second forward runway page exceeds the cap", ranges.any { pages[5].startOffset in it })
        view.dispose()
    }

    @Test
    fun `one queued rapid turn keeps the base neighborhood without opening a second runway`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        view.goToPage(2)

        view.acceptPromotedPageTurns(delta = 1, rapidSequence = true)
        val ranges = view.decodeAdmissionLayoutRanges()

        listOf(1, 2, 3).forEach { index ->
            assertTrue("base neighborhood page $index must be admitted", ranges.any { pages[index].startOffset in it })
        }
        assertFalse(
            "a single queued turn must not decode the second directional runway page",
            ranges.any { pages[4].startOffset in it },
        )
        view.dispose()
    }

    @Test
    fun `multi-turn rapid queue promotes the directional second landing to display quality`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 5)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        view.goToPage(2)

        view.acceptPromotedPageTurns(delta = 2, rapidSequence = true)
        val ranges = view.displayQualityLayoutRanges()

        listOf(1, 2, 3, 4).forEach { index ->
            assertTrue("page $index must be display quality", ranges.any { pages[index].startOffset in it })
        }
        assertFalse(
            "the opposite direction must remain outside the bounded display window",
            ranges.any { pages[0].startOffset in it },
        )
        view.dispose()
    }

    @Test
    fun `rapid queued turns defer in-place page-shot refresh and full text rebind until idle`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.NONE)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 4)
            view.preCachePageTexturesForTest()
            val pages = view.privateField("paged") as List<EpubFlowPage>
            val warmFront = checkNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
            val dirtyOffset = pages[0].startOffset

            // A rapid burst is queued while an async image pixel completion lands between turns.
            view.acceptPromotedPageTurns(delta = 2, rapidSequence = true)
            assertEquals(2, view.privateInt("queuedPageTurnDelta"))
            assertTrue(view.privateBool("rapidTurnSequenceActive"))
            view.onAsyncImagePixelsChanged(dirtyOffset)
            view.onAsyncImagePixelsChangedRequiringTextRebind(dirtyOffset)
            assertEquals(
                "the burst must stage the warm FRONT slot for a deferred in-place redraw",
                setOf("FRONT"),
                view.pendingInPlacePageShotRefreshSlotNamesForTest(),
            )

            // Let frame tasks run: queued turns must not drain the staged refresh or apply the
            // whole-TextView rebind before the sequence is idle.
            shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)

            assertTrue(
                "in-place page-shot refresh must stay staged while queued turns are unresolved",
                view.pendingInPlacePageShotRefreshSlotNamesForTest().isNotEmpty(),
            )
            assertFalse(
                "the deferred refresh must not be posted while the rapid sequence owns the frames",
                view.privateBool("inPlacePageShotRefreshPosted"),
            )
            assertTrue(
                "the whole-TextView rebind must stay pending until the rapid sequence settles",
                view.privateBool("asyncImagePixelTextRebindPending"),
            )
            assertSame(
                "deferral must keep the warm bitmap identity alive (no recycle, no rebuild)",
                warmFront,
                view.privateField("cachedFrontBitmap"),
            )
            assertFalse("the staged warm owner must stay live", warmFront.isRecycled)

            // Let the burst drain through its own rapid idle windows and settle.
            shadowOf(Looper.getMainLooper()).idleFor(2L, TimeUnit.SECONDS)

            assertEquals(0, view.privateInt("queuedPageTurnDelta"))
            assertFalse(view.privateBool("rapidTurnSequenceActive"))
            assertTrue(
                "the final idle settle must drain the coalesced pending slots",
                view.pendingInPlacePageShotRefreshSlotNamesForTest().isEmpty(),
            )
            assertFalse(
                "the final idle settle must apply the deferred whole-TextView rebind once",
                view.privateBool("asyncImagePixelTextRebindPending"),
            )
            @Suppress("UNCHECKED_CAST")
            val remainingOffsets = view.privateField("asyncImagePixelRefreshOffsets") as Set<Int>
            assertTrue(
                "the deferred pixel batch must be consumed by the settle refresh",
                remainingOffsets.isEmpty(),
            )
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `rapid queued turns defer geometry refresh until quiet idle`() {
        val view = pagedFlowView(flipStyle = PageFlipStyle.SLIDE)
        try {
            assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 2)
            view.preCachePageTexturesForTest()
            view.drainPendingPageTexturePrecacheForTest()
            val warmFront = checkNotNull(view.slideFrontArtifactForTest())

            view.setPrivateField("rapidTurnSequenceActive", true)
            view.setPrivateField("queuedPageTurnDelta", 1)
            view.refreshAfterAsyncImageResult()

            assertTrue(
                "geometry refresh must stay pending during the rapid sequence",
                view.privateBool("asyncImageRefreshPending"),
            )
            assertSame(
                "geometry refresh must not discard the warm front artifact between queued turns",
                warmFront,
                view.slideFrontArtifactForTest(),
            )
            assertTrue("the warm artifact must stay live for the next rapid frame", warmFront.slideArtifactRecordIsLive())

            shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)

            assertTrue(
                "posted geometry refresh must keep deferring while the rapid window is active",
                view.privateBool("asyncImageRefreshPending"),
            )
            assertTrue(
                "pending geometry refresh must not block a warm queued rapid turn",
                view.drainQueuedPageTurnForTest(),
            )
            assertNotNull("the queued turn must still install its animated overlay", view.privateField("slideDrawable"))

            shadowOf(Looper.getMainLooper()).idleFor(2L, TimeUnit.SECONDS)

            assertFalse(
                "quiet rapid idle must apply the deferred geometry refresh once",
                view.privateBool("asyncImageRefreshPending"),
            )
            assertFalse(view.privateBool("rapidTurnSequenceActive"))
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `far page pending decode does not block reveal or nearby precache`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val farStart = pages.last().startOffset
        // Simulates loader.hasRelevantPendingDecodes: far layoutStart only blocks when in range.
        var farPending = true
        view.pendingDecodesProvider = {
            farPending && view.relevantPendingDecodeLayoutRanges().any { farStart in it }
        }

        val restoreOffset = pages[0].startOffset
        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            view.textView.text,
            pageHeightPx = 120,
            restoreOffset = restoreOffset,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "far-page pending decode must not hold the current-page reveal",
            false,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(1f, view.getChildAt(0).alpha)

        view.recycleCachedTexturesForTest()
        view.preCachePageTexturesForTest()
        assertNotNull(
            "nearby page-shot precache must proceed while only a far-page decode is pending",
            view.privateField("cachedFrontBitmap"),
        )
        farPending = false
        view.dispose()
    }

    @Test
    fun `real loader pending occurrences are gated by the view page neighborhood`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val epub = java.io.File.createTempFile("readflow-relevant-gate", ".epub")
        val executor = Executors.newSingleThreadExecutor()
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        executor.submit {
            workerStarted.countDown()
            releaseWorker.await()
        }
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
        val loader = EpubFlowImageLoader(
            epubFileProvider = { epub },
            executor = executor,
            columnWidthPx = view.width,
            pageHeightProvider = { view.height },
            inlineMaxHeightPx = view.height,
            fullPageHrefs = emptySet(),
            imageBoundsProvider = { null },
        )
        val resolver = EpubFlowImageSizeResolver(
            columnWidthPx = view.width,
            pageHeightProvider = { view.height },
            inlineMaxHeightPx = view.height,
            fullPageHrefs = emptySet(),
        )
        view.pendingDecodesProvider = {
            loader.hasRelevantPendingDecodes(view.relevantPendingDecodeLayoutRanges())
        }

        try {
            val far = AsyncDrawable("far.png", loader, resolver, null)
            loader.registerOccurrence(far, pages.last().startOffset)
            loader.load(far)
            assertFalse(
                "a real far-page loader request must not block the current page neighborhood",
                requireNotNull(view.pendingDecodesProvider).invoke(),
            )

            loader.cancelAll()
            val current = AsyncDrawable("current.png", loader, resolver, null)
            loader.registerOccurrence(current, pages.first().startOffset)
            loader.load(current)
            assertTrue(
                "a real current-page loader request must still block",
                requireNotNull(view.pendingDecodesProvider).invoke(),
            )

            loader.cancelAll()
            val unknown = AsyncDrawable("unknown.png", loader, resolver, null)
            loader.load(unknown)
            assertTrue(
                "an unregistered real loader request must block conservatively",
                requireNotNull(view.pendingDecodesProvider).invoke(),
            )
        } finally {
            loader.releaseAll()
            releaseWorker.countDown()
            executor.shutdownNow()
            epub.delete()
            view.dispose()
        }
    }

    @Test
    fun `current page pending decode still blocks reveal`() {
        val view = pagedFlowView()
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pages = view.privateField("paged") as List<EpubFlowPage>
        val currentStart = pages[0].startOffset
        view.pendingDecodesProvider = {
            view.relevantPendingDecodeLayoutRanges().any { currentStart in it }
        }

        view.setChapter(
            view.privateField("flow") as EpubChapterFlow,
            view.textView.text,
            pageHeightPx = 120,
            restoreOffset = currentStart,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "current-page pending decode must still hold reveal",
            true,
            view.privateBool("awaitingReveal"),
        )
        assertEquals(0f, view.getChildAt(0).alpha)
        view.dispose()
    }

    @Test
    fun `boundary image preview reuses generation host without per frame span lookup`() {
        // Hot path: live draw + page-shot capture must not re-run Spannable.getSpans for the same
        // page-boundary image occurrence every frame. Host is generation-owned in page layout metadata.
        EpubBoundaryImageHostProbe.reset()
        val viewportWidth = 360
        val viewportHeight = 240
        val placeholderColor = 0x00000000
        val decodedColor = 0xFFDC2626.toInt()
        val imageHeight = viewportHeight + 120
        val bodyText = (1..2).joinToString("\n") { "前置 $it 把标题推到页底附近。" }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Text(bodyText, headingLevel = null, paragraphIndex = 0),
                EpubDisplayBlock.Text("缓存宿主标题", headingLevel = 2, paragraphIndex = 1),
                EpubDisplayBlock.Image(
                    href = "host-cache-plate.png",
                    altText = "host cache plate",
                    paragraphIndex = 2,
                    isInlineContent = false,
                ),
            ),
        )
        val imageSegment = flow.segments.single { it.isImage }
        val headingSegment = flow.segments.single {
            val block = it.block
            block is EpubDisplayBlock.Text && block.headingLevel != null
        }
        val placeholder = ColorDrawable(placeholderColor).apply {
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val asyncDrawable = AsyncDrawable(
            "host-cache-plate.png",
            AsyncDrawableLoader.noOp(),
            EpubFlowImageSizeResolver(
                columnWidthPx = viewportWidth,
                pageHeightProvider = { viewportHeight },
                inlineMaxHeightPx = viewportHeight,
                fullPageHrefs = emptySet(),
            ),
            null,
        ).apply {
            setResult(placeholder)
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val spannable = SpannableString(flow.text).apply {
            setSpan(
                AsyncDrawableSpan(
                    io.noties.markwon.core.MarkwonTheme.create(RuntimeEnvironment.getApplication()),
                    asyncDrawable,
                    AsyncDrawableSpan.ALIGN_CENTER,
                    false,
                ),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(
            flow = flow,
            spannable = spannable,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        try {
            val layout = requireNotNull(view.textView.layout)
            val headingLine = layout.getLineForOffset(headingSegment.layoutStart)
            val headingPageIndex = (0 until view.pageCount()).firstOrNull { pageIndex ->
                val top = requireNotNull(view.pageTopPxAt(pageIndex))
                val nextTop = view.pageTopPxAt(pageIndex + 1) ?: (layout.height + 1)
                layout.getLineTop(headingLine) in top until nextTop
            } ?: error("heading must land on a paged window")
            view.goToPage(headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()

            // Baseline after layout/pagination (host resolved into pageBoundaryImagePreviews metadata).
            val lookupsAfterLayout = EpubBoundaryImageHostProbe.spanHostLookups()
            assertTrue(
                "metadata build must resolve the boundary image host at least once; lookups=$lookupsAfterLayout",
                lookupsAfterLayout >= 1,
            )

            // Live draw path (draw → drawPageBoundaryImagePreview) must not re-resolve.
            val liveA = view.drawToBitmapForTest()
            val liveB = view.drawToBitmapForTest()
            val liveC = view.drawToBitmapForTest()
            liveA.recycle()
            liveB.recycle()
            liveC.recycle()
            assertEquals(
                "repeated live boundary-preview draws must not call Spannable.getSpans host lookup; " +
                    "afterLayout=$lookupsAfterLayout afterLive=${EpubBoundaryImageHostProbe.spanHostLookups()}",
                lookupsAfterLayout,
                EpubBoundaryImageHostProbe.spanHostLookups(),
            )

            // Page-shot / snapshot path also paints the crop; still no span scan.
            // Release budget leases after each capture: Bitmap.recycle does not free PageShotBudget
            // slots (DEFAULT_MAX_ACTIVE_SHOTS = 3), and goToPage may already hold warm owners.
            val headingTop = requireNotNull(view.pageTopPxAt(headingPageIndex))
            val pageShotBudget = checkNotNull(view.privateField("pageShotBudget") as PageShotBudget?)
            view.recycleCachedTexturesForTest()
            shadowOf(Looper.getMainLooper()).idle()
            val shotA = requireNotNull(view.snapshotPageAt(headingTop))
            pageShotBudget.release(shotA)
            if (!shotA.isRecycled) shotA.recycle()
            val shotB = requireNotNull(view.snapshotPageAt(headingTop))
            pageShotBudget.release(shotB)
            if (!shotB.isRecycled) shotB.recycle()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "page-shot boundary-preview draws must reuse generation-owned host; " +
                    "afterLayout=$lookupsAfterLayout afterShots=${EpubBoundaryImageHostProbe.spanHostLookups()}",
                lookupsAfterLayout,
                EpubBoundaryImageHostProbe.spanHostLookups(),
            )

            // Placeholder → decoded result on the same AsyncDrawable shell: host identity stays,
            // crop still paints, and draws still do not re-scan spans.
            val headingBottomOnPage =
                layout.getLineBottom(headingLine) - headingTop
            val sampleTop = (headingBottomOnPage + 2).coerceAtMost(viewportHeight - 2)
            val decoded = ColorDrawable(decodedColor).apply {
                setBounds(0, 0, viewportWidth, imageHeight)
            }
            asyncDrawable.setResult(decoded)
            asyncDrawable.setBounds(0, 0, viewportWidth, imageHeight)
            val afterPixels = view.drawToBitmapForTest()
            try {
                val hits = sampleImagePatternHits(
                    bitmap = afterPixels,
                    left = viewportWidth / 4,
                    top = sampleTop,
                    right = (viewportWidth * 3) / 4,
                    bottom = viewportHeight - 1,
                    stripeA = decodedColor,
                    stripeB = decodedColor,
                )
                assertTrue(
                    "same AsyncDrawable shell must paint decoded pixels into the leftover crop; " +
                        "hits=$hits sampleTop=$sampleTop",
                    hits > 0,
                )
            } finally {
                afterPixels.recycle()
            }
            assertEquals(
                "PIXELS_ONLY result swap must not force another span host lookup on draw",
                lookupsAfterLayout,
                EpubBoundaryImageHostProbe.spanHostLookups(),
            )

            // Chapter replacement invalidates generation-owned metadata; a new host resolution is required.
            val chapter2 = epubBuildChapterFlow(
                spineIndex = 1,
                blocks = listOf(
                    EpubDisplayBlock.Text("新章前置", headingLevel = null, paragraphIndex = 0),
                    EpubDisplayBlock.Text("新章标题", headingLevel = 2, paragraphIndex = 1),
                    EpubDisplayBlock.Image(
                        href = "host-cache-plate-2.png",
                        altText = "replacement plate",
                        paragraphIndex = 2,
                        isInlineContent = false,
                    ),
                ),
            )
            val image2 = chapter2.segments.single { it.isImage }
            val drawable2 = ColorDrawable(0xFF2563EB.toInt()).apply {
                setBounds(0, 0, viewportWidth, imageHeight)
            }
            val spannable2 = SpannableString(chapter2.text).apply {
                setSpan(
                    ImageSpan(drawable2, ImageSpan.ALIGN_BOTTOM),
                    image2.layoutStart,
                    image2.layoutEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            view.setChapter(chapter2, spannable2, pageHeightPx = viewportHeight)
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(viewportWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(viewportHeight, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, viewportWidth, viewportHeight)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)

            val lookupsAfterChapter = EpubBoundaryImageHostProbe.spanHostLookups()
            assertTrue(
                "chapter replacement must rebuild metadata and resolve a new host; " +
                    "before=$lookupsAfterLayout after=$lookupsAfterChapter",
                lookupsAfterChapter > lookupsAfterLayout,
            )
            // Draws under the new generation still must not re-scan.
            val gen2Baseline = lookupsAfterChapter
            view.drawToBitmapForTest().recycle()
            view.drawToBitmapForTest().recycle()
            assertEquals(
                "post-replacement draws must not re-resolve the host every frame",
                gen2Baseline,
                EpubBoundaryImageHostProbe.spanHostLookups(),
            )
        } finally {
            view.dispose()
            EpubBoundaryImageHostProbe.stop()
        }
    }

    @Test
    fun `goToPage and setFlipStyle arm warm page-shot precache without test-only helpers`() {
        // Production reader navigation parks via goToPage / live setFlipStyle; F1 warm gate and
        // interactive turns must not require preCachePageTexturesForTest to arm front+revealed.
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        try {
            assertTrue(
                "fixture needs heading then immediate image page; heading=${fixture.headingPageIndex} " +
                    "image=${fixture.imagePageIndex}",
                fixture.headingPageIndex > 0 &&
                    fixture.imagePageIndex == fixture.headingPageIndex + 1,
            )
            view.flipStyle = PageFlipStyle.SLIDE
            view.goToPage(fixture.headingPageIndex)
            // Production posts split-frame captures; drain the main looper like the real frame loop.
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()

            val cachedFront = checkNotNull(view.slideFrontArtifactForTest()) {
                "goToPage must arm heading front artifact without test precache helper"
            }
            val cachedRevealed = checkNotNull(view.slideRevealedArtifactForTest()) {
                "goToPage must arm image revealed artifact without test precache helper"
            }
            assertEquals(
                fixture.headingPageIndex,
                view.slideArtifactSlotPageTopForTest("slideFrontSlot")?.first,
            )
            assertEquals(
                fixture.imagePageIndex,
                view.slideArtifactSlotPageTopForTest("slideRevealedSlot")?.first,
            )
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.headingPageIndex)),
                view.slideArtifactSlotPageTopForTest("slideFrontSlot")?.second,
            )
            assertEquals(
                requireNotNull(view.pageTopPxAt(fixture.imagePageIndex)),
                view.slideArtifactSlotPageTopForTest("slideRevealedSlot")?.second,
            )
            assertTrue(cachedFront.slideArtifactRecordIsLive())
            assertTrue(cachedRevealed.slideArtifactRecordIsLive())
            assertFalse(view.privateBool("slideArtifactPrecachePending"))

            // Live flip-style change recycles owners; production must re-arm warm pair for the parked page.
            view.flipStyle = PageFlipStyle.SIMULATION
            shadowOf(Looper.getMainLooper()).idle()
            view.drainPendingPageTexturePrecacheForTest()
            assertNotNull(view.privateField("cachedFrontBitmap") as Bitmap?)
            assertNotNull(view.privateField("cachedRevealedBitmap") as Bitmap?)
            assertEquals(fixture.headingPageIndex, view.privateInt("cachedFromPage"))
            assertEquals(fixture.imagePageIndex, view.privateInt("cachedTargetPage"))
            assertEquals(PageFlipStyle.SIMULATION, view.flipStyle)
        } finally {
            view.dispose()
        }
    }

    @Test
    fun `mixed heading image warm interactive turn transfers cache without recapture or main-thread full decode`() {
        // Production hot path: warm front+target must be zero-copy transfer; page-shot allocate and
        // full-pixel image decode must not run on the threshold MOVE / beginInteractiveCurl.
        EpubImageDecodeProbe.reset()
        EpubPageShotCaptureProbe.reset()
        val reportedOffsets = mutableListOf<Int>()
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        // Rebind top-offset callback after fixture construction (pagedFlowView already ran).
        view.javaClass.getDeclaredField("onTopOffsetChanged").apply { isAccessible = true }
            .set(view, { offset: Int -> reportedOffsets.add(offset) })
        try {
            assertTrue(
                "fixture needs heading then immediate image page; heading=${fixture.headingPageIndex} " +
                    "image=${fixture.imagePageIndex}",
                fixture.headingPageIndex > 0 &&
                    fixture.imagePageIndex == fixture.headingPageIndex + 1,
            )
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            view.preCachePageTexturesForTest()
            val cachedFront = checkNotNull(view.slideFrontArtifactForTest()) {
                "warm mixed path requires a heading-page front artifact"
            }
            val cachedRevealed = checkNotNull(view.slideRevealedArtifactForTest()) {
                "warm mixed path requires an image-page revealed artifact"
            }
            assertEquals(
                fixture.headingPageIndex,
                view.slideArtifactSlotPageTopForTest("slideFrontSlot")?.first,
            )
            assertEquals(
                fixture.imagePageIndex,
                view.slideArtifactSlotPageTopForTest("slideRevealedSlot")?.first,
            )
            assertTrue(cachedFront.slideArtifactRecordIsLive())
            assertTrue(cachedRevealed.slideArtifactRecordIsLive())

            // Baseline after warm setup: MOVE must not grow these counters.
            val capturesAfterWarm = EpubPageShotCaptureProbe.total()
            val fullMainAfterWarm = EpubImageDecodeProbe.fullDecodeMainThread()
            reportedOffsets.clear()

            assertTrue(
                view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()),
            )
            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?)
            assertTrue(
                "warm mixed MOVE must transfer heading front artifact by identity",
                slide.frontArtifactForTest() === cachedFront && cachedFront.slideArtifactRecordIsLive(),
            )
            assertTrue(
                "warm mixed MOVE must transfer image-page target artifact by identity",
                slide.revealedArtifactForTest() === cachedRevealed && cachedRevealed.slideArtifactRecordIsLive(),
            )
            assertEquals(
                "warm path must not recapture full page shots after the pair exists",
                capturesAfterWarm,
                EpubPageShotCaptureProbe.total(),
            )
            assertEquals(
                "interactive turn must not full-decode images on the main thread",
                fullMainAfterWarm,
                EpubImageDecodeProbe.fullDecodeMainThread(),
            )
            assertTrue(
                "turn setup must stay locator-silent until settle",
                reportedOffsets.isEmpty(),
            )

            // Progress must cross the directional threshold (or fling left). beginInteractiveCurl
            // alone leaves progress at 0; a positive velocity is a reverse fling for forward turns.
            view.updateInteractiveCurl(x = view.width * 0.25f)
            assertEquals(
                "progress updates must not recapture after warm transfer",
                capturesAfterWarm,
                EpubPageShotCaptureProbe.total(),
            )
            assertEquals(
                "progress updates must not full-decode on the main thread",
                fullMainAfterWarm,
                EpubImageDecodeProbe.fullDecodeMainThread(),
            )
            assertTrue(reportedOffsets.isEmpty())

            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

            assertEquals(
                "committed mixed turn must land on the image page once",
                fixture.imagePageIndex,
                view.currentPageIndex(),
            )
            assertEquals(
                "locator must publish exactly once for the committed page",
                1,
                reportedOffsets.size,
            )
            assertTrue("committed front identity must not be discarded early", cachedFront.slideArtifactRecordIsLive())
            // After settle, the revealed image page is typically rekeyed as the new front.
            val settledFront = view.slideFrontArtifactForTest()
            assertTrue(
                "settle should retain a live image-page artifact (rekeyed target or new front)",
                settledFront != null && settledFront.slideArtifactRecordIsLive(),
            )
            assertEquals(
                "settle must not perform main-thread full image decode",
                fullMainAfterWarm,
                EpubImageDecodeProbe.fullDecodeMainThread(),
            )
        } finally {
            view.dispose()
            EpubImageDecodeProbe.stop()
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `warm same-chapter SLIDE prefetch lands the adjacent page without allocating a page-shot bitmap`() {
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        try {
            assertTrue(
                "fixture needs a nontrivial parked page followed by an adjacent image page; " +
                    "heading=${fixture.headingPageIndex} image=${fixture.imagePageIndex}",
                fixture.headingPageIndex > 0 &&
                    fixture.imagePageIndex == fixture.headingPageIndex + 1,
            )

            // Park mid-chapter, drain posted work, drop warm owners, then arm the probe so only
            // the prefetch and the turn below can be counted.
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            EpubPageShotCaptureProbe.reset()

            // Prefetch and drain the split-frame chain, then drive a real warm forward turn.
            view.preCachePageTexturesForTest()
            view.drainPendingPageTexturePrecacheForTest()

            assertTrue(
                "warm SLIDE turn must start from the parked page",
                view.beginInteractiveCurl(forward = true, anchorX = view.width.toFloat()),
            )
            view.updateInteractiveCurl(x = view.width * 0.25f)
            view.endInteractiveCurl(velocityX = 0f)
            shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)

            assertEquals(
                "warm same-chapter turn must land on the adjacent page",
                fixture.imagePageIndex,
                view.currentPageIndex(),
            )
            assertEquals(
                "warm prefetch plus the complete turn must not allocate or recapture any full-viewport page-shot bitmap",
                0,
                EpubPageShotCaptureProbe.total(),
            )
        } finally {
            view.dispose()
            EpubPageShotCaptureProbe.stop()
        }
    }

    @Test
    fun `mixed heading image cold interactive MOVE defers shots and commits once without main-thread full decode`() {
        EpubImageDecodeProbe.reset()
        EpubPageShotCaptureProbe.reset()
        val reportedOffsets = mutableListOf<Int>()
        val fixture = headingImageContinuationFixture(leadingBodyLines = 40)
        val view = fixture.view
        view.flipStyle = PageFlipStyle.SLIDE
        view.javaClass.getDeclaredField("onTopOffsetChanged").apply { isAccessible = true }
            .set(view, { offset: Int -> reportedOffsets.add(offset) })
        var startedCount = 0
        view.onPageTurnStarted = { startedCount++ }
        try {
            view.goToPage(fixture.headingPageIndex)
            shadowOf(Looper.getMainLooper()).idle()
            view.recycleCachedTexturesForTest()
            // No precache: first finger MOVE must enter LOCAL_SHOTS_WAITING (deferred cold handoff).
            assertNull(view.privateField("cachedFrontBitmap"))
            assertNull(view.privateField("cachedRevealedBitmap"))

            val capturesBeforeMove = EpubPageShotCaptureProbe.total()
            val fullMainBeforeMove = EpubImageDecodeProbe.fullDecodeMainThread()
            reportedOffsets.clear()

            val downX = view.width * 0.85f
            val moveX = view.width * 0.45f
            val y = view.height * 0.12f
            val downTime = SystemClock.uptimeMillis()

            view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, downX, y))
            assertTrue(
                view.onInterceptTouchEvent(
                    motionEvent(downTime, downTime + 24L, MotionEvent.ACTION_MOVE, moveX, y),
                ),
            )

            assertEquals(
                "cold mixed MOVE must defer local page shots",
                "LOCAL_SHOTS_WAITING",
                view.privateField("interactiveTurnState").toString(),
            )
            assertNull("threshold MOVE must not install slide yet", view.privateField("slideDrawable"))
            assertEquals(
                "threshold MOVE must not allocate full page shots synchronously",
                capturesBeforeMove,
                EpubPageShotCaptureProbe.total(),
            )
            assertEquals(
                "threshold MOVE must not full-decode images on the main thread",
                fullMainBeforeMove,
                EpubImageDecodeProbe.fullDecodeMainThread(),
            )
            assertEquals(
                "deferred intent must not publish the start until its visual renderer is installed",
                0,
                startedCount,
            )
            assertTrue(reportedOffsets.isEmpty())

            // Deferred frames: target, then front, then overlay install under the held finger.
            shadowOf(Looper.getMainLooper()).runOneTask()
            assertNull(
                "first deferred frame must not install the overlay yet",
                view.privateField("slideDrawable"),
            )
            assertEquals(
                "the deferred start must stay silent while only the target shot is prepared",
                0,
                startedCount,
            )
            shadowOf(Looper.getMainLooper()).runOneTask()

            val slide = checkNotNull(view.privateField("slideDrawable") as PageSlideDrawable?) {
                "deferred cold handoff must install the local slide after shot frames"
            }
            assertEquals(
                "the deferred local handoff must publish exactly one start when its renderer starts",
                1,
                startedCount,
            )
            val front = slide.privateBitmap("frontBitmap")
            val revealed = slide.privateBitmap("revealedBitmap")
            assertFalse(front.isRecycled)
            assertFalse(revealed.isRecycled)
            assertTrue(front !== revealed)
            assertTrue(
                "cold handoff captures after MOVE, not during the threshold event",
                EpubPageShotCaptureProbe.total() >= capturesBeforeMove + 2,
            )
            assertEquals(
                "deferred capture path must not main-thread full-decode EPUB zip images",
                fullMainBeforeMove,
                EpubImageDecodeProbe.fullDecodeMainThread(),
            )
            assertTrue("ready handoff under finger must stay locator-silent", reportedOffsets.isEmpty())

            view.onTouchEvent(
                motionEvent(downTime, downTime + 600L, MotionEvent.ACTION_UP, moveX, y),
            )
            shadowOf(Looper.getMainLooper()).idleFor(500L, TimeUnit.MILLISECONDS)

            assertEquals("NONE", view.privateField("interactiveTurnState").toString())
            assertEquals(
                "cold mixed commit must land on the image page",
                fixture.imagePageIndex,
                view.currentPageIndex(),
            )
            assertEquals(
                "committed locator must publish exactly once",
                1,
                reportedOffsets.size,
            )
            assertEquals(
                "no late main-thread full decode after settle",
                fullMainBeforeMove,
                EpubImageDecodeProbe.fullDecodeMainThread(),
            )
            // Working frames may be rekeyed into cache; neither identity may be double-freed early
            // while still owned — settle leaves at least one live neighbour owner.
            val settledFront = view.privateField("cachedFrontBitmap") as Bitmap?
            assertTrue(
                settledFront == null || !settledFront.isRecycled,
            )
        } finally {
            view.dispose()
            EpubImageDecodeProbe.stop()
            EpubPageShotCaptureProbe.stop()
        }
    }

    private data class HeadingImageContinuationFixture(
        val view: EpubFlowView,
        val imageLayoutStart: Int,
        val headingPageIndex: Int,
        val imagePageIndex: Int,
        val imageDrawable: ColorDrawable,
    )

    /**
     * Heading + oversized following image fixture used by continuation-aware invalidation tests.
     *
     * Page roles match production ownership ([EpubFlowPage] startOffset/endOffset windows and
     * [EpubFlowView] precache adjacency: front = heading, revealed = next window, backward = prev).
     * Line-top heuristics are intentionally not used: an oversized image line can sit past a
     * separator-only intermediate window even when its line top falls in a later page band.
     */
    private fun headingImageContinuationFixture(
        leadingBodyLines: Int = 2,
    ): HeadingImageContinuationFixture {
        // Prefer the requested leading count; if that yields a non-adjacent intermediate window
        // (separator-only page between a bottom-parked heading and the complete image page), search
        // nearby counts so the production next-page target owns the image offset.
        val lineCandidates = linkedSetOf(leadingBodyLines).apply {
            for (delta in 1..32) {
                add(leadingBodyLines + delta)
                if (leadingBodyLines - delta >= 1) add(leadingBodyLines - delta)
            }
        }
        var lastDiagnostics: String? = null
        for (lines in lineCandidates) {
            val fixture = buildHeadingImageContinuationFixture(leadingBodyLines = lines)
            val pages = fixture.view.privateField("paged") as List<EpubFlowPage>
            val productCase =
                fixture.headingPageIndex > 0 &&
                    fixture.imagePageIndex == fixture.headingPageIndex + 1 &&
                    fixture.imagePageIndex < pages.size
            if (productCase) return fixture
            lastDiagnostics =
                "lines=$lines heading=${fixture.headingPageIndex} image=${fixture.imagePageIndex} " +
                    "pages=${pages.size} " +
                    pages.mapIndexed { index, page ->
                        "#$index[${page.startOffset},${page.endOffset}) lines=${page.startLine}..${page.endLineExclusive}"
                    }.joinToString(" ")
            fixture.view.dispose()
        }
        error(
            "unable to place heading + immediate image page for continuation fixture; last=$lastDiagnostics",
        )
    }

    private fun visibleHeadingImageContinuationFixture(
        minHeadingPageIndex: Int = 0,
    ): HeadingImageContinuationFixture {
        var lastDiagnostics: String? = null
        for (lines in 2..32) {
            val fixture = buildHeadingImageContinuationFixture(leadingBodyLines = lines)
            val pages = fixture.view.privateField("paged") as List<EpubFlowPage>
            val adjacentImagePage =
                fixture.headingPageIndex >= minHeadingPageIndex &&
                fixture.imagePageIndex == fixture.headingPageIndex + 1 &&
                    fixture.imagePageIndex < pages.size
            val visibleCropHits = if (adjacentImagePage) {
                fixture.view.goToPage(fixture.headingPageIndex)
                shadowOf(Looper.getMainLooper()).idle()
                val baseline = fixture.view.drawAsScrolledChildToBitmapForTest()
                try {
                    baseline.countExactPixels(fixture.imageDrawable.color)
                } finally {
                    baseline.recycle()
                }
            } else {
                0
            }
            if (adjacentImagePage && visibleCropHits > 0) return fixture
            lastDiagnostics =
                "lines=$lines heading=${fixture.headingPageIndex} image=${fixture.imagePageIndex} " +
                    "pages=${pages.size} visibleCropHits=$visibleCropHits"
            fixture.view.dispose()
        }
        error("unable to place a visible heading continuation crop; last=$lastDiagnostics")
    }

    /**
     * Builds one heading/image continuation geometry and resolves page roles from production
     * offset ownership (`layoutOffset ∈ [startOffset, endOffset)`), not line-top bands.
     */
    private fun buildHeadingImageContinuationFixture(
        leadingBodyLines: Int,
    ): HeadingImageContinuationFixture {
        val viewportWidth = 360
        val viewportHeight = 240
        val imageHeight = viewportHeight + 120
        val bodyText = (1..leadingBodyLines).joinToString("\n") { "前置正文 $it 把标题推到页底附近。" }
        val flow = epubBuildChapterFlow(
            spineIndex = 0,
            blocks = listOf(
                EpubDisplayBlock.Text(bodyText, headingLevel = null, paragraphIndex = 0),
                EpubDisplayBlock.Text("图版标题", headingLevel = 2, paragraphIndex = 1),
                EpubDisplayBlock.Image(
                    href = "plate-pattern.png",
                    altText = "patterned plate",
                    paragraphIndex = 2,
                    isInlineContent = false,
                ),
            ),
        )
        val imageSegment = flow.segments.single { it.isImage }
        val headingSegment = flow.segments.single {
            val block = it.block
            block is EpubDisplayBlock.Text && block.headingLevel != null
        }
        val imageDrawable = ColorDrawable(0xFFE11D48.toInt()).apply {
            setBounds(0, 0, viewportWidth, imageHeight)
        }
        val spannable = SpannableString(flow.text).apply {
            setSpan(
                ImageSpan(imageDrawable, ImageSpan.ALIGN_BOTTOM),
                imageSegment.layoutStart,
                imageSegment.layoutEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val view = pagedFlowView(
            flow = flow,
            spannable = spannable,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        val pages = view.privateField("paged") as List<EpubFlowPage>
        // Production: pageWindowDependsOnImageOffset / containsLayoutOffset — offset window, not line top.
        val headingPageIndex = pages.indexOfFirst { page ->
            headingSegment.layoutStart >= page.startOffset &&
                headingSegment.layoutStart < page.endOffset
        }.takeIf { it >= 0 } ?: error(
            "heading layout offset ${headingSegment.layoutStart} must land in a paged window; pages=$pages",
        )
        val imagePageIndex = pages.indexOfFirst { page ->
            imageSegment.layoutStart >= page.startOffset &&
                imageSegment.layoutStart < page.endOffset
        }.takeIf { it >= 0 } ?: error(
            "image layout offset ${imageSegment.layoutStart} must land in a paged window; pages=$pages",
        )
        return HeadingImageContinuationFixture(
            view = view,
            imageLayoutStart = imageSegment.layoutStart,
            headingPageIndex = headingPageIndex,
            imagePageIndex = imagePageIndex,
            imageDrawable = imageDrawable,
        )
    }

    private fun pagedFlowView(
        flipStyle: PageFlipStyle = PageFlipStyle.NONE,
        onTapZone: (EpubFlowTapZone) -> Unit = {},
        onTopOffsetChanged: (Int) -> Unit = {},
        flow: EpubChapterFlow? = null,
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
        val chapterFlow = flow ?: epubBuildChapterFlow(spineIndex = 0, blocks = listOf(block))

        view.measure(exactly(viewportWidth), exactly(viewportHeight))
        view.layout(0, 0, viewportWidth, viewportHeight)
        view.setChapter(chapterFlow, spannable ?: chapterFlow.text, pageHeightPx = viewportHeight)
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

    private class QueuedImageDecodeExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false

        val queuedTaskCount: Int get() = tasks.size

        fun runNext() {
            tasks.removeFirst().run()
        }

        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException("executor stopped")
            tasks.addLast(command)
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0)

    private fun dispatchFiveDpForwardGesture(view: EpubFlowView, durationMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        val x = view.width * 0.85f
        val startY = view.height * 0.85f
        val travelPx = 5f * view.resources.displayMetrics.density
        val halfwayY = startY - travelPx / 2f
        val releaseY = startY - travelPx
        view.dispatchTouchEvent(motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY))
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + durationMs / 2L, MotionEvent.ACTION_MOVE, x, halfwayY),
        )
        view.dispatchTouchEvent(
            motionEvent(downTime, downTime + durationMs, MotionEvent.ACTION_UP, x, releaseY),
        )
    }

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

    /** Production capturePageTurnOrigin via reflection; returns the private PageTurnOrigin value. */
    private fun EpubFlowView.capturePageTurnOriginForTest(): Any =
        javaClass.getDeclaredMethod("capturePageTurnOrigin")
            .apply { isAccessible = true }
            .invoke(this)

    /**
     * Direct entry to startLocalInteractiveCurl, bypassing input classification so a test can force
     * the renderer-admission gate with an exact frame pair.
     */
    private fun EpubFlowView.startLocalInteractiveCurlForTest(
        origin: Any,
        targetWindow: EpubFlowPage,
        forward: Boolean,
        outgoing: SlidePageFrame,
        revealed: SlidePageFrame,
    ): Enum<*> {
        val originClass = javaClass.declaredClasses.single { it.simpleName == "PageTurnOrigin" }
        val axisClass = javaClass.declaredClasses.single { it.simpleName == "InteractiveTurnAxis" }
        val horizontal = checkNotNull(axisClass.enumConstants)
            .single { (it as Enum<*>).name == "HORIZONTAL" }
        val method = javaClass.getDeclaredMethod(
            "startLocalInteractiveCurl",
            originClass,
            EpubFlowPage::class.java,
            Boolean::class.javaPrimitiveType,
            axisClass,
            Float::class.javaPrimitiveType,
            SlidePageFrame::class.java,
            SlidePageFrame::class.java,
        )
        return method.apply { isAccessible = true }
            .invoke(this, origin, targetWindow, forward, horizontal, 0f, outgoing, revealed) as Enum<*>
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

    private fun EpubFlowView.setRapidIdleSettleStageForTest(stageName: String) {
        val field = javaClass.getDeclaredField("rapidIdleSettleStage").apply { isAccessible = true }
        val stage = checkNotNull(field.type.enumConstants).single { (it as Enum<*>).name == stageName }
        field.set(this, stage)
    }

    private fun EpubFlowView.runRapidIdleSettleForTest() {
        javaClass.getDeclaredMethod("runRapidIdleSettle")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun EpubFlowView.cancelRapidIdleSettleForTest() {
        removeCallbacks(privateField("rapidIdleSettleRunnable") as Runnable)
        setPrivateField("rapidIdleSettlePosted", false)
    }

    private fun EpubFlowView.preCachePageTexturesForTest(idlePostedWork: Boolean = true) {
        javaClass.getDeclaredMethod("preCachePageTextures")
            .apply { isAccessible = true }
            .invoke(this)
        if (idlePostedWork) shadowOf(Looper.getMainLooper()).idle()
    }

    private fun EpubFlowView.drainQueuedPageTurnForTest(): Boolean =
        javaClass.getDeclaredMethod("drainQueuedPageTurn")
            .apply { isAccessible = true }
            .invoke(this) as Boolean

    private fun EpubFlowView.takeCachedSlideArtifactsForTurnForTest(
        fromPage: Int,
        fromTop: Int,
        fromWindow: EpubFlowPage?,
        targetPage: Int,
        targetTop: Int,
        targetWindow: EpubFlowPage,
    ): Pair<Any, Any>? {
        val method = javaClass.getDeclaredMethod(
            "takeCachedSlideArtifactsForTurn",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            EpubFlowPage::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            EpubFlowPage::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        return method.apply { isAccessible = true }
            .invoke(this, fromPage, fromTop, fromWindow, targetPage, targetTop, targetWindow) as? Pair<Any, Any>
    }

    private fun EpubFlowView.recordSlidePageArtifactForTest(topPx: Int, window: EpubFlowPage): Any {
        val method = javaClass.getDeclaredMethod(
            "recordSlidePageArtifact",
            Int::class.javaPrimitiveType,
            EpubFlowPage::class.java,
        )
        return checkNotNull(method.apply { isAccessible = true }.invoke(this, topPx, window))
    }

    private fun EpubFlowView.installSlideArtifactSlotForTest(slotName: String, window: EpubFlowPage, artifact: Any) {
        val slotClass = Class.forName("dev.readflow.render.epub.EpubFlowView\$SlideArtifactSlot")
        val constructor = slotClass.declaredConstructors.single()
        val key = javaClass.getDeclaredMethod(
            "slidePageArtifactKey",
            Int::class.javaPrimitiveType,
            EpubFlowPage::class.java,
        ).apply { isAccessible = true }.invoke(this, window.topPx, window)
        val slot = constructor.apply { isAccessible = true }
            .newInstance(
                canonicalFloorPageIndexForTest(window),
                window.topPx,
                key,
                artifact,
            )
        javaClass.getDeclaredField(slotName).apply {
            isAccessible = true
            set(this@installSlideArtifactSlotForTest, slot)
        }
    }

    private fun EpubFlowView.canonicalFloorPageIndexForTest(window: EpubFlowPage): Int {
        val method = javaClass.getDeclaredMethod(
            "canonicalFloorPageIndexForTopPx",
            Int::class.javaPrimitiveType,
        )
        return method.apply { isAccessible = true }.invoke(this, window.topPx) as Int
    }

    private fun EpubFlowView.rapidSlideArtifactPairAvailableForTest(targetWindow: EpubFlowPage): Boolean {
        val method = javaClass.getDeclaredMethod(
            "rapidSlideArtifactPairAvailable",
            EpubFlowPage::class.java,
        )
        return method.apply { isAccessible = true }.invoke(this, targetWindow) as Boolean
    }

    /**
     * Arms a real pending slide-artifact precache request (front + target artifacts recorded, no
     * commit callbacks posted) via reflection, without going through the posted capture chain.
     */
    private fun EpubFlowView.armPendingSlideArtifactPrecacheForTest(fromPageIndex: Int, targetPageIndex: Int) {
        val pages = privateField("paged") as List<EpubFlowPage>
        val fromWindow = pages[fromPageIndex]
        val targetWindow = pages[targetPageIndex]
        val generation = privateField("slideArtifactPrecacheGeneration") as Long
        val fromKey = javaClass.getDeclaredMethod(
            "slidePageArtifactKey",
            Int::class.javaPrimitiveType,
            EpubFlowPage::class.java,
        ).apply { isAccessible = true }.invoke(this, fromWindow.topPx, fromWindow)
        val targetKey = javaClass.getDeclaredMethod(
            "slidePageArtifactKey",
            Int::class.javaPrimitiveType,
            EpubFlowPage::class.java,
        ).apply { isAccessible = true }.invoke(this, targetWindow.topPx, targetWindow)
        val frontArtifact = recordSlidePageArtifactForTest(fromWindow.topPx, fromWindow)
        val targetArtifact = recordSlidePageArtifactForTest(targetWindow.topPx, targetWindow)

        val requestClass = Class.forName("dev.readflow.render.epub.EpubFlowView\$PendingSlideArtifactPrecache")
        // Pick the 17-parameter primary constructor explicitly; default parameters may add a
        // synthetic default-argument constructor that .single() would fail on.
        val constructor = requestClass.declaredConstructors.single { it.parameterTypes.size == 17 }
        val request = constructor.apply { isAccessible = true }.newInstance(
            generation,
            fromWindow.topPx,
            fromWindow,
            null,
            targetWindow,
            fromPageIndex,
            -1,
            targetPageIndex,
            fromKey,
            null,
            targetKey,
            frontArtifact,
            null,
            targetArtifact,
            false,
            false,
            false,
        )
        javaClass.getDeclaredField("pendingSlideArtifactPrecache").apply {
            isAccessible = true
            set(this@armPendingSlideArtifactPrecacheForTest, request)
        }
        javaClass.getDeclaredField("slideArtifactPrecachePending").apply {
            isAccessible = true
            setBoolean(this@armPendingSlideArtifactPrecacheForTest, true)
        }
    }

    /** Invokes the real private validity predicate against the currently armed pending request. */
    private fun EpubFlowView.pendingSlideArtifactPrecacheIsValidForTest(): Boolean {
        val request = checkNotNull(privateField("pendingSlideArtifactPrecache"))
        val method = javaClass.getDeclaredMethod(
            "pendingSlideArtifactPrecacheIsValid",
            request.javaClass,
        )
        return method.apply { isAccessible = true }.invoke(this, request) as Boolean
    }

    /**
     * Advances one postOnAnimation frame at a time until a split-frame page-texture precache
     * commits (or is discarded). Drains both the legacy Bitmap precache pending state and the warm
     * SLIDE artifact precache pending state. Mirrors production's front/target/previous frame
     * chain; idle() alone is not reliable once work is already posted as chained animation
     * callbacks. A committed warm pair admits a queued turn synchronously — no hidden record pass
     * is modeled here. Caps frames and fails loudly so a stuck queue cannot silently pass.
     */
    private fun EpubFlowView.drainPendingPageTexturePrecacheForTest(maxFrames: Int = 8) {
        repeat(maxFrames) {
            if (!pendingPageTexturePrecacheWork()) return
            shadowOf(Looper.getMainLooper()).runOneTask()
        }
        assertTrue(
            "precache drain must converge within $maxFrames frames; " +
                "pageTexturePrecachePending=${privateBool("pageTexturePrecachePending")} " +
                "pendingPageTexturePrecache=${privateField("pendingPageTexturePrecache") != null} " +
                "slideArtifactPrecachePending=${privateBool("slideArtifactPrecachePending")} " +
                "pendingSlideArtifactPrecache=${privateField("pendingSlideArtifactPrecache") != null}",
            !pendingPageTexturePrecacheWork(),
        )
    }

    /** True while either precache chain still owns pending capture/commit work. */
    private fun EpubFlowView.pendingPageTexturePrecacheWork(): Boolean =
        privateBool("pageTexturePrecachePending") ||
            privateField("pendingPageTexturePrecache") != null ||
            privateBool("slideArtifactPrecachePending") ||
            privateField("pendingSlideArtifactPrecache") != null

    /**
     * Drains the one-slot-per-frame in-place PIXELS_ONLY redraw queue without requiring a full
     * idle that might also arm unrelated work. Caps frames so a stuck queue fails loudly.
     */
    private fun EpubFlowView.drainInPlacePageShotRefreshForTest(maxFrames: Int = 8) {
        repeat(maxFrames) {
            @Suppress("UNCHECKED_CAST")
            val pending = privateField("pendingInPlacePageShotRefreshSlots") as Set<*>
            val posted = privateBool("inPlacePageShotRefreshPosted")
            if (pending.isEmpty() && !posted) return
            shadowOf(Looper.getMainLooper()).runOneTask()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun EpubFlowView.pendingInPlacePageShotRefreshSlotNamesForTest(): Set<String> =
        (privateField("pendingInPlacePageShotRefreshSlots") as Set<*>)
            .mapTo(linkedSetOf()) { slot -> (slot as Enum<*>).name }

    private fun EpubFlowView.replacePendingInPlacePageShotRefreshSlotsForTest(vararg names: String) {
        val slotClass = javaClass.declaredClasses.single { it.simpleName == "CachedPageShotSlot" }
        val constants = checkNotNull(slotClass.enumConstants).associateBy { (it as Enum<*>).name }
        @Suppress("UNCHECKED_CAST")
        val pending = privateField("pendingInPlacePageShotRefreshSlots") as MutableSet<Any>
        pending.clear()
        names.forEach { name -> pending += checkNotNull(constants[name]) { "unknown page-shot slot $name" } }
    }

    private fun EpubFlowView.drainPendingLocalPageShotHandoffUntilOverlayForTest(maxFrames: Int = 8) {
        repeat(maxFrames) {
            if (privateField("slideDrawable") != null || privateField("curlDrawable") != null) return
            shadowOf(Looper.getMainLooper()).runOneTask()
        }
        assertTrue(
            "cold local handoff must install an overlay within $maxFrames frames; " +
                "state=${privateField("interactiveTurnState")}",
            privateField("slideDrawable") != null || privateField("curlDrawable") != null,
        )
    }

    private fun EpubFlowView.recycleCachedTexturesForTest() {
        javaClass.getDeclaredMethod("recycleCachedTextures")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun EpubFlowView.preparePinnedLocalWorkingPairBudgetForTest() {
        javaClass.getDeclaredMethod("preparePinnedLocalWorkingPairBudget")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun EpubFlowView.detachCachedTextureOwnerForTest(bitmap: Bitmap) {
        javaClass.getDeclaredMethod("detachCachedTextureOwner", Bitmap::class.java)
            .apply { isAccessible = true }
            .invoke(this, bitmap)
    }

    private fun EpubFlowView.recyclePageShotForTest(bitmap: Bitmap) {
        javaClass.getDeclaredMethod("recyclePageShot", Bitmap::class.java)
            .apply { isAccessible = true }
            .invoke(this, bitmap)
    }

    private fun assertMotionPageShotSize(view: EpubFlowView, bitmap: Bitmap) {
        assertEquals(view.privateNoArgInt("motionPageShotWidthPx"), bitmap.width)
        assertEquals(view.privateNoArgInt("motionPageShotHeightPx"), bitmap.height)
    }

    private fun EpubFlowView.snapshotViewportForTest(): Bitmap? =
        javaClass.getDeclaredMethod("snapshotViewport")
            .apply { isAccessible = true }
            .invoke(this) as Bitmap?

    private fun EpubFlowView.snapshotViewportForTest(
        kind: PageShotLeaseKind,
        label: String,
        fullResolution: Boolean,
    ): Bitmap? =
        javaClass.getDeclaredMethod(
            "snapshotViewport",
            PageShotLeaseKind::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
            .apply { isAccessible = true }
            .invoke(this, kind, label, fullResolution) as Bitmap?

    private fun EpubFlowView.showConversionSnapshotForTest(bitmap: Bitmap) {
        javaClass.getDeclaredMethod("showConversionSnapshot", Bitmap::class.java)
            .apply { isAccessible = true }
            .invoke(this, bitmap)
    }

    private fun EpubFlowView.clearConversionSnapshotForTest() {
        javaClass.getDeclaredMethod("clearConversionSnapshot")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun EpubFlowView.clearFlipOverlayForTest() {
        javaClass.getDeclaredMethod("clearFlipOverlay", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, false)
    }

    private fun EpubFlowView.applyFlipProgressForTest(progress: Float, forward: Boolean) {
        javaClass.getDeclaredMethod(
            "applyFlipProgress",
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
            .apply { isAccessible = true }
            .invoke(this, progress, forward)
    }

    private fun EpubFlowView.drawToBitmapForTest(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    private fun EpubFlowView.drawAsScrolledChildToBitmapForTest(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            draw(canvas)
        }

    private fun sampleImagePatternHits(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        stripeA: Int,
        stripeB: Int,
    ): Int {
        val l = left.coerceIn(0, bitmap.width - 1)
        val t = top.coerceIn(0, bitmap.height - 1)
        val r = right.coerceIn(l + 1, bitmap.width)
        val b = bottom.coerceIn(t + 1, bitmap.height)
        val stepX = ((r - l) / 8).coerceAtLeast(1)
        val stepY = ((b - t) / 8).coerceAtLeast(1)
        var hits = 0
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == stripeA || pixel == stripeB) hits++
                x += stepX
            }
            y += stepY
        }
        return hits
    }

    private fun Bitmap.countExactPixels(color: Int): Int {
        var matches = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getPixel(x, y) == color) matches++
            }
        }
        return matches
    }

    private fun Bitmap.countDistinctPixels(limit: Int = 8): Int {
        val colors = hashSetOf<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                colors += getPixel(x, y)
                if (colors.size >= limit) return colors.size
            }
        }
        return colors.size
    }

    private fun Bitmap.countExactPixels(color: Int, area: Rect): Int {
        var matches = 0
        for (y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(height)) {
            for (x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(width)) {
                if (getPixel(x, y) == color) matches++
            }
        }
        return matches
    }

    private fun Bitmap.countHighContrastHorizontalTransitions(y: Int, startX: Int, endX: Int): Int {
        val sampleY = y.coerceIn(0, height - 1)
        val left = startX.coerceIn(0, width - 1)
        val right = endX.coerceIn(left + 1, width)
        var edges = 0
        for (x in left + 1 until right) {
            val before = getPixel(x - 1, sampleY)
            val after = getPixel(x, sampleY)
            val rgbDistance =
                kotlin.math.abs(((before shr 16) and 0xFF) - ((after shr 16) and 0xFF)) +
                    kotlin.math.abs(((before shr 8) and 0xFF) - ((after shr 8) and 0xFF)) +
                    kotlin.math.abs((before and 0xFF) - (after and 0xFF))
            if (rgbDistance >= 600) edges++
        }
        return edges
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

    private fun EpubFlowView.privateNoArgInt(name: String): Int =
        javaClass.getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(this) as Int

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

    private fun EpubFlowView.activeSlideRendererViews(): List<View> {
        val livePage = getChildAt(0)
        return javaClass.declaredFields.asSequence()
            .filter { View::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(this) as? View
            }
            .filterNot { candidate -> candidate === livePage || candidate === textView }
            .filter { candidate -> candidate.alpha > 0f || candidate.visibility != View.VISIBLE }
            .distinct()
            .toList()
    }

    private fun EpubFlowView.requireActiveSlideRendererView(): View {
        val candidates = activeSlideRendererViews()
        assertEquals(
            "an active SLIDE turn must retain exactly one dedicated View renderer; candidates=$candidates",
            1,
            candidates.size,
        )
        return candidates.single()
    }

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

    // ---- Warm SLIDE artifact test helpers ------------------------------------------------------

    /** Identity of the cached current-page SLIDE artifact, or null when no slot is armed. */
    private fun EpubFlowView.slideFrontArtifactForTest(): SlidePageArtifact? =
        (privateField("slideFrontSlot") as Any?)?.reflectedField("artifact") as? SlidePageArtifact

    /** Identity of the cached forward-target SLIDE artifact, or null when no slot is armed. */
    private fun EpubFlowView.slideRevealedArtifactForTest(): SlidePageArtifact? =
        (privateField("slideRevealedSlot") as Any?)?.reflectedField("artifact") as? SlidePageArtifact

    /** Identity of the cached backward-target SLIDE artifact, or null when no slot is armed. */
    private fun EpubFlowView.slideBackwardArtifactForTest(): SlidePageArtifact? =
        (privateField("slideBackwardSlot") as Any?)?.reflectedField("artifact") as? SlidePageArtifact

    /** The raw cached slot (slideFrontSlot / slideRevealedSlot / slideBackwardSlot), or null. */
    private fun EpubFlowView.slideArtifactSlotForTest(slot: String): Any? = privateField(slot)

    /** (page, topPx) of a named artifact slot, or null when no slot is armed. */
    private fun EpubFlowView.slideArtifactSlotPageTopForTest(slot: String): Pair<Int, Int>? {
        val slotValue = privateField(slot) as Any? ?: return null
        return (slotValue.reflectedField("page") as Int) to (slotValue.reflectedField("topPx") as Int)
    }

    /** The exact artifact key of a named slot, or null when no slot is armed. */
    private fun EpubFlowView.slideArtifactSlotKeyForTest(slot: String): Any? =
        (privateField(slot) as Any?)?.reflectedField("key")

    /** True while the artifact still holds a recorded owner (not yet discarded behind the fence). */
    private fun Any.slideArtifactRecordIsLive(): Boolean =
        javaClass.getDeclaredField("record").apply { isAccessible = true }.get(this) != null

    /** Identities still pinned behind the two-host-frame artifact retirement barrier. */
    private fun EpubFlowView.renderRetiredSlideArtifactsForTest(): Set<*> {
        val retired = privateField("renderRetiredSlideArtifacts") as? Map<*, *> ?: return emptySet<Any>()
        return retired.keys.toSet()
    }

    /** Requests final discard through production fence-aware disposal. */
    private fun EpubFlowView.discardSlideArtifactForTest(artifact: Any) {
        val artifactClass = Class.forName("dev.readflow.render.epub.SlidePageArtifact")
        javaClass.getDeclaredMethod("discardSlideArtifact", artifactClass)
            .apply { isAccessible = true }
            .invoke(this, artifact)
    }

    /** Active outgoing artifact of a warm artifact-framed slide turn. */
    private fun PageSlideDrawable.frontArtifactForTest(): Any? =
        (reflectedField("frontFrame") as? SlidePageFrame.ArtifactFrame)?.artifact

    /** Active incoming artifact of a warm artifact-framed slide turn. */
    private fun PageSlideDrawable.revealedArtifactForTest(): Any? =
        (reflectedField("revealedFrame") as? SlidePageFrame.ArtifactFrame)?.artifact

    /** Active outgoing frame wrapper (Bitmap or artifact) of a slide turn, or null when absent. */
    private fun PageSlideDrawable.frontFrameForTest(): SlidePageFrame? =
        reflectedField("frontFrame") as? SlidePageFrame

    /** Active incoming frame wrapper (Bitmap or artifact) of a slide turn, or null when absent. */
    private fun PageSlideDrawable.revealedFrameForTest(): SlidePageFrame? =
        reflectedField("revealedFrame") as? SlidePageFrame

    /** True while the frame's owner is still live, independent of Bitmap vs artifact framing. */
    private fun PageSlideDrawable.frameIsLiveForTest(frame: SlidePageFrame): Boolean =
        when (frame) {
            is SlidePageFrame.BitmapFrame -> !frame.bitmap.isRecycled
            is SlidePageFrame.ArtifactFrame -> frame.artifact.slideArtifactRecordIsLive()
        }

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

    private class RecordingBoundsTopDrawable(
        private val beforeDraw: (() -> Unit)? = null,
    ) : Drawable() {
        val boundsTops = mutableListOf<Int>()

        override fun draw(canvas: Canvas) {
            beforeDraw?.invoke()
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

    private class OnePixelDetailDrawable(
        private val stripeWidth: Int = 1,
    ) : Drawable() {
        private val paint = Paint()

        override fun draw(canvas: Canvas) {
            val b = bounds
            for (x in b.left until b.right) {
                paint.color = if (((x - b.left) / stripeWidth.coerceAtLeast(1)) % 2 == 0) {
                    0xFF151515.toInt()
                } else {
                    0xFFEAEAEA.toInt()
                }
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
