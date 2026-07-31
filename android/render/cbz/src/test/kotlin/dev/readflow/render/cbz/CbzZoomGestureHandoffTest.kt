package dev.readflow.render.cbz

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.github.panpf.zoomimage.ZoomImageView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CbzZoomGestureHandoffTest {

    @Test
    fun `fit scale image lets pager intercept a multi sample horizontal swipe`() {
        val context = RuntimeEnvironment.getApplication()
        val image = ZoomImageView(context)
        var boundarySwipes = 0
        val page = CbzPageGestureHost(context, image) { boundarySwipes++ }.apply { addView(image) }
        val bitmap = Bitmap.createBitmap(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, Bitmap.Config.ARGB_8888)
        val pager = RecordingHorizontalPager(context)

        try {
            image.setImageBitmap(bitmap)
            pager.addView(
                page,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            pager.measure(exactly(VIEWPORT_WIDTH), exactly(VIEWPORT_HEIGHT))
            pager.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

            dispatch(pager, MotionEvent.ACTION_DOWN, eventTime = 0L, x = 280f)
            dispatch(pager, MotionEvent.ACTION_MOVE, eventTime = 16L, x = 160f)
            dispatch(pager, MotionEvent.ACTION_MOVE, eventTime = 32L, x = 40f)
            dispatch(pager, MotionEvent.ACTION_UP, eventTime = 48L, x = 40f)

            assertEquals(
                "a fit-scale comic page must hand a horizontal swipe to its pager",
                1,
                pager.horizontalInterceptions,
            )
            assertEquals(
                "a pager cancellation must retain the same smooth boundary fallback",
                1,
                boundarySwipes,
            )
        } finally {
            image.setImageDrawable(null)
            bitmap.recycle()
        }
    }

    @Test
    fun `fit scale single move swipe uses boundary fallback`() {
        val context = RuntimeEnvironment.getApplication()
        val image = ZoomImageView(context)
        var boundarySwipes = 0
        val page = CbzPageGestureHost(context, image) { boundarySwipes++ }.apply { addView(image) }
        val bitmap = Bitmap.createBitmap(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, Bitmap.Config.ARGB_8888)
        val pager = RecordingHorizontalPager(context)

        try {
            image.setImageBitmap(bitmap)
            pager.addView(
                page,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            pager.measure(exactly(VIEWPORT_WIDTH), exactly(VIEWPORT_HEIGHT))
            pager.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

            dispatch(pager, MotionEvent.ACTION_DOWN, eventTime = 0L, x = 280f)
            dispatch(pager, MotionEvent.ACTION_MOVE, eventTime = 16L, x = 40f)
            dispatch(pager, MotionEvent.ACTION_UP, eventTime = 32L, x = 40f)

            assertEquals(1, boundarySwipes)
            assertEquals(0, pager.horizontalInterceptions)
        } finally {
            image.setImageDrawable(null)
            bitmap.recycle()
        }
    }

    private fun dispatch(pager: View, action: Int, eventTime: Long, x: Float) {
        MotionEvent.obtain(0L, eventTime, action, x, 100f, 0).also { event ->
            try {
                pager.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private class RecordingHorizontalPager(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var initialX = 0f
        private var initialY = 0f
        var horizontalInterceptions = 0
            private set

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.x
                    initialY = event.y
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val distanceX = kotlin.math.abs(event.x - initialX)
                        val distanceY = kotlin.math.abs(event.y - initialY)
                        if (distanceX > touchSlop && distanceX > distanceY) {
                            horizontalInterceptions++
                            return true
                        }
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    private companion object {
        const val VIEWPORT_WIDTH = 360
        const val VIEWPORT_HEIGHT = 640
    }
}
