package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageSlideDrawableTest {

    @Test
    fun `forward slide moves pages left and preserves horizontal source columns`() {
        val width = 120
        val height = 24
        val front = xRampBitmap(width, height)
        val revealed = xRampBitmap(width, height, greenOffset = 100)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = PageSlideDrawable(
            front,
            revealed,
            width,
            height,
            forward = true,
            density = 1f,
        )
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.progress = 0.5f
            drawable.draw(Canvas(output))

            assertEquals(front.getPixel(70, height / 2), output.getPixel(10, height / 2))
            assertEquals(revealed.getPixel(30, height / 2), output.getPixel(90, height / 2))
            assertEquals(30, drawable.incomingSourceXForViewportX(90))
        } finally {
            drawable.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    @Test
    fun `backward slide moves pages right and preserves horizontal source columns`() {
        val width = 120
        val height = 24
        val front = xRampBitmap(width, height)
        val revealed = xRampBitmap(width, height, greenOffset = 100)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = PageSlideDrawable(
            front,
            revealed,
            width,
            height,
            forward = false,
            density = 1f,
        )
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.progress = 0.5f
            drawable.draw(Canvas(output))

            assertEquals(revealed.getPixel(70, height / 2), output.getPixel(10, height / 2))
            assertEquals(front.getPixel(30, height / 2), output.getPixel(90, height / 2))
            assertEquals(70, drawable.incomingSourceXForViewportX(10))
        } finally {
            drawable.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    private fun xRampBitmap(width: Int, height: Int, greenOffset: Int = 0): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                val color = Color.rgb(10, (x + greenOffset).coerceAtMost(255), 30)
                for (y in 0 until height) setPixel(x, y, color)
            }
        }
}
