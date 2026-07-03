package dev.readflow.render.epub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import dev.readflow.core.model.PageFlipStyle
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
    fun `initial restore stays hidden until the first layout settle window`() {
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

        view.setChapter(flow, flow.text, pageHeightPx = 120, restoreOffset = restoreOffset)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("restored content must stay hidden until the settle debounce fires", 0f, view.getChildAt(0).alpha)
        assertEquals("initial reveal should still be pending after the first positioning pass", true, view.privateBool("awaitingReveal"))

        shadowOf(Looper.getMainLooper()).idleFor(250L, TimeUnit.MILLISECONDS)

        assertEquals("restored content should reveal after the settle window", false, view.privateBool("awaitingReveal"))
        assertEquals(1f, view.getChildAt(0).alpha)
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

    private fun pagedFlowView(
        flipStyle: PageFlipStyle = PageFlipStyle.NONE,
        onTapZone: (EpubFlowTapZone) -> Unit = {},
        spannable: CharSequence? = null,
        text: String? = null,
        textPaddingTop: Int = 0,
    ): EpubFlowView {
        val context = RuntimeEnvironment.getApplication() as Application
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = EpubFlowView(
            context = context,
            onTapZone = onTapZone,
            onTopOffsetChanged = {},
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
}
