package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `vertical slide moves pages along Y and keeps horizontal source rows intact`() {
        val width = 24
        val height = 120
        val front = yRampBitmap(width, height)
        val revealed = yRampBitmap(width, height, greenOffset = 100)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = PageSlideDrawable(
            front,
            revealed,
            width,
            height,
            forward = true,
            density = 1f,
            vertical = true,
        )
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.progress = 0.5f
            drawable.draw(Canvas(output))

            assertEquals(front.getPixel(width / 2, 70), output.getPixel(width / 2, 10))
            assertEquals(revealed.getPixel(width / 2, 30), output.getPixel(width / 2, 90))
            assertTrue(drawable.incomingSourceYForViewportY(90) in 29..30)
        } finally {
            drawable.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    private fun yRampBitmap(width: Int, height: Int, greenOffset: Int = 0): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                val color = Color.rgb(10, (y + greenOffset).coerceAtMost(255), 30)
                for (x in 0 until width) setPixel(x, y, color)
            }
        }
}
