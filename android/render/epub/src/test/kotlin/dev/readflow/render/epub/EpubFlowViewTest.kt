package dev.readflow.render.epub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.ui.readerPaperBackground
import org.junit.Assert.assertEquals
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
    fun `cancelled gl curl restores normalized from page anchor`() {
        val overlay = FakeCurlOverlay()
        val view = pagedFlowView(flipStyle = PageFlipStyle.SIMULATION).apply {
            curlOverlay = overlay
        }
        assertTrue("pageCount=${view.pageCount()}", view.pageCount() > 3)
        val pageOneTop = requireNotNull(view.pageTopPxAt(1))
        val pageTwoTop = requireNotNull(view.pageTopPxAt(2))
        val nearPageTwo = pageOneTop + ((pageTwoTop - pageOneTop) * 3 / 4)

        view.scrollTo(0, nearPageTwo)

        assertTrue(view.beginGlInteractiveCurl(forward = true, atY = view.height / 2f))
        overlay.settle(committed = false)

        assertEquals(2, view.currentPageIndex())
        assertEquals(pageTwoTop, view.scrollY)
    }

    @Test
    fun `committed gl curl lands on adjacent page from normalized anchor`() {
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

        assertTrue(view.beginGlInteractiveCurl(forward = true, atY = view.height / 2f))
        overlay.settle(committed = true)

        assertEquals(3, view.currentPageIndex())
        assertEquals(pageThreeTop, view.scrollY)
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

        view.runReflowRunnable()

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
        assertEquals(0f, view.getChildAt(0).alpha)

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
    fun `full page image taller than viewport is not clipped away when parked on it`() {
        // A full-page illustration lays out as ONE line taller than the viewport (审: 满页彩插).
        // Build a chapter whose 2nd line is such an oversized image line, park exactly on its top,
        // and assert the page clip keeps it (returns null = no clip) instead of backing off to the
        // previous line and clipping the whole viewport blank — the "闪一下后消失" regression.
        val tallImageHeight = 300 // > 120 viewport
        val builder = SpannableString("A\n￼\nB")
        builder.setSpan(
            FixedHeightReplacementSpan(tallImageHeight),
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

    private fun EpubFlowView.beginInteractiveCurl(forward: Boolean, anchorX: Float): Boolean =
        javaClass.getDeclaredMethod(
            "beginInteractiveCurl",
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(this, forward, anchorX) as Boolean

    private fun EpubFlowView.updateInteractiveCurl(x: Float) {
        javaClass.getDeclaredMethod("updateInteractiveCurl", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, x)
    }

    private fun EpubFlowView.endInteractiveCurl(velocityX: Float) {
        javaClass.getDeclaredMethod("endInteractiveCurl", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, velocityX)
    }

    private fun EpubFlowView.beginGlInteractiveCurl(forward: Boolean, atY: Float): Boolean =
        javaClass.getDeclaredMethod(
            "beginGlInteractiveCurl",
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(this, forward, atY) as Boolean

    private fun EpubFlowView.runReflowRunnable() {
        (privateField("reflowRunnable") as Runnable).run()
        shadowOf(Looper.getMainLooper()).idle()
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

    private class FakeCurlOverlay : EpubCurlTurnOverlay {
        private var settled: ((Boolean) -> Unit)? = null
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

        override fun start(
            front: Bitmap,
            revealed: Bitmap,
            forward: Boolean,
            settled: (committed: Boolean) -> Unit,
        ) {
            frontWidth = front.width
            frontHeight = front.height
            revealedWidth = revealed.width
            revealedHeight = revealed.height
            frontBitmapCopy?.recycle()
            frontBitmapCopy = front.copy(Bitmap.Config.ARGB_8888, false)
            front.recycle()
            revealed.recycle()
            this.settled = settled
            active = true
            startCount += 1
        }

        override fun animateTurn(durationMs: Long) = Unit

        override fun forwardTouch(ev: MotionEvent) = Unit

        override fun dismiss() {
            active = false
        }

        fun settle(committed: Boolean) {
            val callback = checkNotNull(settled) { "curl overlay was not started" }
            settled = null
            active = false
            callback(committed)
        }
    }

    /** A ReplacementSpan that lays out at a fixed pixel height — stands in for a full-page image line. */
    private class FixedHeightReplacementSpan(private val heightPx: Int) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            if (fm != null) {
                fm.ascent = -heightPx
                fm.top = -heightPx
                fm.descent = 0
                fm.bottom = 0
            }
            return 1
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) = Unit
    }
}
