package dev.readflow.render.epub

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import fi.harism.curl.CurlPage
import fi.harism.curl.CurlRenderer
import fi.harism.curl.CurlView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubCurlOverlayTest {

    @Test
    fun `aborting a turn does not request replacement page textures`() {
        val curlView = CurlView(RuntimeEnvironment.getApplication() as Application)
        var updateCalls = 0
        curlView.setPageProvider(
            object : CurlView.PageProvider {
                override fun getPageCount(): Int = 2

                override fun updatePage(page: CurlPage, width: Int, height: Int, index: Int) {
                    updateCalls += 1
                }
            },
        )
        curlView.onPageSizeChanged(100, 100)
        updateCalls = 0

        curlView.abortTurn()

        assertEquals(
            "abort is cleanup and must not allocate or ask the provider for another texture",
            0,
            updateCalls,
        )
    }

    @Test
    fun `texture copy failure fails start without exposing or retaining the turn`() {
        val overlay = EpubCurlOverlay(
            RuntimeEnvironment.getApplication() as Application,
            textureCopier = { _, _ -> null },
        )
        val curlView = overlay.getChildAt(0) as CurlView
        curlView.onPageSizeChanged(4, 4)
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var settleCalls = 0

        try {
            val failure = runCatching {
                overlay.start(front, revealed, forward = true) { settleCalls += 1 }
            }.exceptionOrNull()

            assertNotNull("the caller needs an explicit failure so it can choose its existing fallback", failure)
            assertFalse("a failed texture transaction must release active ownership synchronously", overlay.active)
            assertEquals(0f, overlay.alpha)
            assertTrue("the overlay owns and must release the surviving page shot on failure", revealed.isRecycled)

            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("a failed texture must never become a visible frame", 0f, overlay.alpha)
            assertEquals("start failure is returned to the caller, not reported as a settled turn", 0, settleCalls)
        } finally {
            overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
        }
    }

    @Test
    fun `texture copy exception is returned after releasing overlay ownership`() {
        val expected = IllegalStateException("texture allocator failed")
        val overlay = EpubCurlOverlay(
            RuntimeEnvironment.getApplication() as Application,
            textureCopier = { _, _ -> throw expected },
        )
        val curlView = overlay.getChildAt(0) as CurlView
        curlView.onPageSizeChanged(4, 4)
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        try {
            val failure = runCatching {
                overlay.start(front, revealed, forward = true) { }
            }.exceptionOrNull()

            assertSame("the existing caller fallback must receive the original renderer failure", expected, failure)
            assertFalse(overlay.active)
            assertEquals(0f, overlay.alpha)
            assertTrue(front.isRecycled)
            assertTrue(revealed.isRecycled)
        } finally {
            overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
        }
    }

    @Test
    fun `texture copy failure after start aborts before the first frame is exposed`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val overlay = EpubCurlOverlay(
            activity,
            textureCopier = { _, _ -> null },
        )
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            overlay,
            FrameLayout.LayoutParams(100, 100),
        )
        val curlView = overlay.getChildAt(0) as CurlView
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val settled = mutableListOf<Boolean>()

        try {
            overlay.start(front, revealed, forward = true, settled::add)
            assertTrue("test precondition: no provider copy runs before the GL page size exists", overlay.active)
            assertEquals(0f, overlay.alpha)

            curlView.onPageSizeChanged(4, 4)
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)

            assertFalse("an asynchronous texture failure must release active turn ownership", overlay.active)
            assertEquals("a failed texture frame must remain hidden", 0f, overlay.alpha)
            assertTrue("the failed turn must release its outgoing page shot", front.isRecycled)
            assertTrue("the failed turn must release its revealed page shot", revealed.isRecycled)
            assertEquals("the host must receive one non-commit settlement", listOf(false), settled)

            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("retired frame callbacks must not settle the failed turn twice", listOf(false), settled)
        } finally {
            overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `revealed texture copy failure after first frame aborts discrete turn`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        var copyCalls = 0
        val overlay = EpubCurlOverlay(
            activity,
            textureCopier = { source, config ->
                copyCalls += 1
                if (copyCalls == 3) null else source.copy(config, false)
            },
        )
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            overlay,
            FrameLayout.LayoutParams(100, 100),
        )
        val curlView = overlay.getChildAt(0) as CurlView
        val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        overlay.measure(exact, exact)
        overlay.layout(0, 0, 100, 100)
        val renderer = curlView.privateField("mRenderer") as CurlRenderer
        renderer.getPageRect(CurlRenderer.PAGE_RIGHT).set(-1f, 1f, 1f, -1f)
        curlView.onPageSizeChanged(4, 4)
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val settled = mutableListOf<Boolean>()

        try {
            overlay.start(front, revealed, forward = true, settled::add)
            assertEquals("front and back textures must be ready before the first frame", 2, copyCalls)
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            assertEquals("the first renderer frame must remain hidden until its buffer has swapped", 0f, overlay.alpha)
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            assertEquals("test precondition: the prepared front frame must already be visible", 1f, overlay.alpha)

            overlay.animateTurn(420L)
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)

            assertEquals("the revealed page must be the third texture allocation", 3, copyCalls)
            assertFalse("a revealed texture failure must release active turn ownership", overlay.active)
            assertEquals("the failed revealed frame must be hidden synchronously", 0f, overlay.alpha)
            assertEquals(false, curlView.privateField("mAnimate"))
            assertTrue("the failed turn must release its outgoing page shot", front.isRecycled)
            assertTrue("the failed turn must release its revealed page shot", revealed.isRecycled)
            assertEquals("the host must receive one non-commit settlement", listOf(false), settled)

            val staleObserver = curlView.privateField("mCurlAnimationObserver") as CurlView.CurlAnimationObserver
            staleObserver.onCurlAnimationEnd(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("a retired observer must not settle the failed turn twice", listOf(false), settled)
        } finally {
            overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `surface resize texture failure after first frame aborts the visible turn`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        var copyCalls = 0
        val overlay = EpubCurlOverlay(
            activity,
            textureCopier = { source, config ->
                copyCalls += 1
                if (copyCalls == 3) null else source.copy(config, false)
            },
        )
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            overlay,
            FrameLayout.LayoutParams(100, 100),
        )
        val curlView = overlay.getChildAt(0) as CurlView
        curlView.onPageSizeChanged(4, 4)
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val settled = mutableListOf<Boolean>()

        try {
            overlay.start(front, revealed, forward = true, settled::add)
            assertEquals(2, copyCalls)
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            assertEquals("the first renderer frame must remain hidden until its buffer has swapped", 0f, overlay.alpha)
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            assertEquals("test precondition: the prepared front frame must already be visible", 1f, overlay.alpha)

            curlView.onPageSizeChanged(8, 8)
            assertEquals("the resize must reach the failing third texture allocation", 3, copyCalls)
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("a resize texture failure must release active turn ownership", overlay.active)
            assertEquals("a failed resized frame must be hidden on the UI thread", 0f, overlay.alpha)
            assertEquals(false, curlView.privateField("mAnimate"))
            assertTrue("the failed turn must release its outgoing page shot", front.isRecycled)
            assertTrue("the failed turn must release its revealed page shot", revealed.isRecycled)
            assertEquals("the host must receive one non-commit settlement", listOf(false), settled)

            curlView.onPageSizeChanged(12, 12)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("repeated resize work must not settle the failed turn twice", listOf(false), settled)
        } finally {
            overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `surface resize texture copy completes before dismiss recycles page shots`() {
        val blockSurfaceCopy = AtomicBoolean(false)
        val copyEntered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val overlay = EpubCurlOverlay(
            RuntimeEnvironment.getApplication() as Application,
            textureCopier = { source, config ->
                if (blockSurfaceCopy.get() && Thread.currentThread().name == SURFACE_THREAD_NAME) {
                    copyEntered.countDown()
                    check(releaseCopy.await(2, TimeUnit.SECONDS)) { "timed out waiting to release surface copy" }
                }
                source.copy(config, false)
            },
        )
        val curlView = overlay.getChildAt(0) as CurlView
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val surfaceFailure = AtomicReference<Throwable?>(null)
        val dismissFailure = AtomicReference<Throwable?>(null)
        val dismissFinished = CountDownLatch(1)

        overlay.start(front, revealed, forward = true) { }
        blockSurfaceCopy.set(true)
        val surfaceThread = Thread(
            {
                runCatching { curlView.onPageSizeChanged(4, 4) }
                    .exceptionOrNull()
                    ?.let(surfaceFailure::set)
            },
            SURFACE_THREAD_NAME,
        )
        val dismissThread = Thread {
            runCatching { overlay.dismiss() }
                .exceptionOrNull()
                ?.let(dismissFailure::set)
            dismissFinished.countDown()
        }

        try {
            surfaceThread.start()
            assertTrue(
                "test precondition: the GL resize must be copying an owned page shot",
                copyEntered.await(2, TimeUnit.SECONDS),
            )
            dismissThread.start()

            assertFalse(
                "dismiss must wait for the in-flight GL ownership read before recycling",
                dismissFinished.await(100, TimeUnit.MILLISECONDS),
            )
            assertFalse("front shot must stay alive while the renderer copies it", front.isRecycled)
            assertFalse("revealed shot must stay alive while the renderer copies it", revealed.isRecycled)
        } finally {
            releaseCopy.countDown()
            surfaceThread.join(2_000)
            dismissThread.join(2_000)
            if (overlay.active) overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
        }

        assertEquals(null, surfaceFailure.get())
        assertEquals(null, dismissFailure.get())
        assertFalse(surfaceThread.isAlive)
        assertFalse(dismissThread.isAlive)
        assertTrue(front.isRecycled)
        assertTrue(revealed.isRecycled)
    }

    @Test
    fun `old pending discrete callback cannot start a replacement turn before its frame is ready`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val overlay = EpubCurlOverlay(activity)
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            overlay,
            FrameLayout.LayoutParams(100, 100),
        )
        val curlView = overlay.getChildAt(0) as CurlView
        val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        overlay.measure(exact, exact)
        overlay.layout(0, 0, 100, 100)
        val renderer = curlView.privateField("mRenderer") as CurlRenderer
        renderer.getPageRect(CurlRenderer.PAGE_RIGHT).set(-1f, 1f, 1f, -1f)
        curlView.onPageSizeChanged(100, 100)
        assertEquals("test precondition: overlay must pass its posted-runnable size gate", 100, overlay.width)
        assertEquals("test precondition: curl child must pass its posted-runnable size gate", 100, curlView.width)
        assertTrue(
            "test precondition: renderer page rect must allow a discrete turn to start",
            renderer.getPageRect(CurlRenderer.PAGE_RIGHT).width() > 0f,
        )
        val firstFront = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val firstRevealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val secondFront = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val secondRevealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        try {
            overlay.start(firstFront, firstRevealed, forward = true) { }
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            overlay.animateTurn(420L)
            val oldPending = overlay.privateField("pendingDiscreteTurnRunnable") as Runnable

            overlay.dismiss()
            assertEquals(
                "dismiss must release ownership of the retired turn callback",
                null,
                overlay.privateField("pendingDiscreteTurnRunnable"),
            )
            overlay.start(secondFront, secondRevealed, forward = true) { }
            overlay.animateTurn(420L)
            assertEquals(false, curlView.privateField("mAnimate"))

            oldPending.run()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)

            assertEquals(
                "a runnable posted by the retired turn must not consume the replacement duration",
                false,
                curlView.privateField("mAnimate"),
            )
            assertEquals("the replacement surface must remain hidden until its own rendered frame", 0f, overlay.alpha)
        } finally {
            overlay.dismiss()
            if (!firstFront.isRecycled) firstFront.recycle()
            if (!firstRevealed.isRecycled) firstRevealed.recycle()
            if (!secondFront.isRecycled) secondFront.recycle()
            if (!secondRevealed.isRecycled) secondRevealed.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `start resets frame readiness and pending work for a direct replacement`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val overlay = EpubCurlOverlay(activity)
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            overlay,
            FrameLayout.LayoutParams(100, 100),
        )
        val curlView = overlay.getChildAt(0) as CurlView
        val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        overlay.measure(exact, exact)
        overlay.layout(0, 0, 100, 100)
        val renderer = curlView.privateField("mRenderer") as CurlRenderer
        renderer.getPageRect(CurlRenderer.PAGE_RIGHT).set(-1f, 1f, 1f, -1f)
        curlView.onPageSizeChanged(100, 100)
        val firstFront = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val firstRevealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val secondFront = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val secondRevealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        try {
            overlay.start(firstFront, firstRevealed, forward = true) { }
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
            overlay.animateTurn(420L)
            val oldPending = overlay.privateField("pendingDiscreteTurnRunnable") as Runnable
            val firstGeneration = overlay.privateField("turnVisibilityGeneration") as Long

            overlay.start(secondFront, secondRevealed, forward = true) { }
            val replacementGeneration = overlay.privateField("turnVisibilityGeneration") as Long
            assertTrue(
                "a direct replacement must begin a new visibility generation",
                replacementGeneration > firstGeneration,
            )
            assertEquals(
                "start must release the retired pending callback",
                null,
                overlay.privateField("pendingDiscreteTurnRunnable"),
            )
            assertTrue(firstFront.isRecycled)
            assertTrue(firstRevealed.isRecycled)

            oldPending.run()
            overlay.animateTurn(420L)

            assertEquals(
                "a replacement must wait for its own rendered frame",
                false,
                overlay.privateField("turnFrameReady"),
            )
            assertEquals(
                "a replacement must not queue a discrete turn before its own frame is ready",
                null,
                overlay.privateField("pendingDiscreteTurnRunnable"),
            )
            assertEquals(false, curlView.privateField("mAnimate"))
            assertEquals(0f, overlay.alpha)
        } finally {
            overlay.dismiss()
            if (!firstFront.isRecycled) firstFront.recycle()
            if (!firstRevealed.isRecycled) firstRevealed.recycle()
            if (!secondFront.isRecycled) secondFront.recycle()
            if (!secondRevealed.isRecycled) secondRevealed.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `dismiss aborts harism animation and curl state`() {
        val overlay = EpubCurlOverlay(
            RuntimeEnvironment.getApplication() as Application,
        )
        val curlView = overlay.getChildAt(0) as CurlView
        curlView.setPrivateField("mAnimate", true)
        curlView.setPrivateField("mCurlState", 2)

        overlay.dismiss()

        assertEquals(false, curlView.privateField("mAnimate"))
        assertEquals(0, curlView.privateField("mCurlState"))
    }

    @Test
    fun `dismiss keeps the warmed GL surface attached but transparent`() {
        val overlay = EpubCurlOverlay(
            RuntimeEnvironment.getApplication() as Application,
        )

        overlay.dismiss()

        assertEquals(
            "a dismissed overlay must keep its GL surface attached so the next turn cannot expose a cold black buffer",
            View.VISIBLE,
            overlay.visibility,
        )
        assertEquals(0f, overlay.alpha)
        assertEquals(View.VISIBLE, overlay.getChildAt(0).visibility)
    }

    @Test
    fun `start stays transparent until the new GL texture frame is rendered`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val overlay = EpubCurlOverlay(activity)
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            overlay,
            FrameLayout.LayoutParams(100, 100),
        )
        val curlView = overlay.getChildAt(0) as CurlView
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        try {
            overlay.start(front, revealed, forward = true) { }

            assertTrue(overlay.active)
            assertEquals(
                "a retained SurfaceView buffer must stay hidden while the new textures render",
                0f,
                overlay.alpha,
            )

            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)

            assertEquals(
                "the first renderer callback occurs before its swap, so the retained surface must stay hidden",
                0f,
                overlay.alpha,
            )

            curlView.renderCompleteFrame()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("the second draw proves the first draw completed its swap loop", 1f, overlay.alpha)
        } finally {
            overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `transparent inactive overlay does not take the reader touch target`() {
        val overlay = EpubCurlOverlay(
            RuntimeEnvironment.getApplication() as Application,
        )
        val exact = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        overlay.measure(exact, exact)
        overlay.layout(0, 0, 100, 100)
        overlay.getChildAt(0).setOnTouchListener { _, _ -> true }
        overlay.dismiss()
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0)

        try {
            assertFalse(overlay.dispatchTouchEvent(down))
        } finally {
            down.recycle()
        }
    }

    @Test
    fun `detaching an active overlay aborts and releases the complete turn transaction`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val parent = activity.findViewById<FrameLayout>(android.R.id.content)
        val overlay = EpubCurlOverlay(activity)
        parent.addView(overlay, FrameLayout.LayoutParams(100, 100))
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val revealed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var settleCalls = 0

        try {
            overlay.start(front, revealed, forward = true) { settleCalls += 1 }
            val curlView = overlay.getChildAt(0) as CurlView
            curlView.setPrivateField("mAnimate", true)
            curlView.setPrivateField("mCurlState", 2)
            val safetyDismiss = overlay.privateField("safetyDismissRunnable") as Runnable
            val callbackHandler = checkNotNull(overlay.handler)
            assertTrue(
                "test precondition: active turn must own its safety callback",
                callbackHandler.hasCallbacks(safetyDismiss),
            )

            parent.removeView(overlay)
            val activeAfterDetach = overlay.active
            val frontRecycledAfterDetach = front.isRecycled
            val revealedRecycledAfterDetach = revealed.isRecycled
            val animationAfterDetach = curlView.privateField("mAnimate")
            val curlStateAfterDetach = curlView.privateField("mCurlState")
            val settleCallbackCleared = overlay.privateField("onTurnSettled") == null
            val safetyCallbackCleared = !callbackHandler.hasCallbacks(safetyDismiss)

            val staleObserver = curlView.privateField("mCurlAnimationObserver") as CurlView.CurlAnimationObserver
            staleObserver.onCurlAnimationEnd(1)

            assertFalse("detach must synchronously release active ownership", activeAfterDetach)
            assertTrue("detach must recycle the outgoing page shot", frontRecycledAfterDetach)
            assertTrue("detach must recycle the revealed page shot", revealedRecycledAfterDetach)
            assertEquals(false, animationAfterDetach)
            assertEquals(0, curlStateAfterDetach)
            assertTrue("detach must clear the settle callback", settleCallbackCleared)
            assertTrue("detach must remove the safety callback", safetyCallbackCleared)
            assertEquals("an old GL observer must not settle a detached turn", 0, settleCalls)
        } finally {
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
            controller.pause().stop().destroy()
        }
    }

    private fun Any.setPrivateField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun CurlView.renderCompleteFrame() {
        onDrawFrame()
        onFrameRendered()
    }

    private fun Any.privateField(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private companion object {
        const val SURFACE_THREAD_NAME = "epub-curl-surface-change-test"
    }
}
