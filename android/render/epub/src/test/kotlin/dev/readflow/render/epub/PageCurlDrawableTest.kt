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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageCurlDrawableTest {

    @Test
    fun `paper turn keeps a flat one to one region and bends only the moving edge`() {
        val width = 120
        val height = 24
        val front = xRampBitmap(width, height)
        val revealed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 80, 220))
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = PageCurlDrawable(front, revealed, width, height, forward = true, density = 1f)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.progress = 0.5f
            drawable.draw(Canvas(output))

            val flatX = 12
            val expectedFlat = front.getPixel(flatX, height / 2)
            val actualFlat = output.getPixel(flatX, height / 2)
            assertTrue(
                "the body before the local bend must retain 1:1 source sampling: expected=" +
                    "${Color.red(expectedFlat)}, actual=${Color.red(actualFlat)}",
                abs(Color.red(expectedFlat) - Color.red(actualFlat)) <= 1,
            )
            assertEquals(
                "after half a turn the destination half beyond the moving edge must expose the target page",
                revealed.getPixel(width * 3 / 4, height / 2),
                output.getPixel(width * 3 / 4, height / 2),
            )
        } finally {
            drawable.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    @Test
    fun `backward paper turn grows the previous page while the outgoing right side stays flat`() {
        val width = 120
        val height = 24
        val front = xRampBitmap(width, height)
        val revealed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                val color = Color.rgb(10, x.coerceAtMost(255), 40)
                for (y in 0 until height) setPixel(x, y, color)
            }
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = PageCurlDrawable(front, revealed, width, height, forward = false, density = 1f)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.progress = 0.5f
            drawable.draw(Canvas(output))

            assertEquals(
                "the outgoing page to the right of the seam must remain 1:1",
                front.getPixel(90, height / 2),
                output.getPixel(90, height / 2),
            )
            val expectedPrevious = revealed.getPixel(12, height / 2)
            val actualPrevious = output.getPixel(12, height / 2)
            assertTrue(
                "the flat body of the previous page must grow in without scaling",
                abs(Color.green(expectedPrevious) - Color.green(actualPrevious)) <= 1,
            )
        } finally {
            drawable.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    @Test
    fun `paper page remains visible at the full bend to terminal bend boundary`() {
        val width = 120
        val height = 24
        val front = xRampBitmap(width, height)
        val revealed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 80, 220))
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = PageCurlDrawable(front, revealed, width, height, forward = true, density = 1f)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.progress = 0.7f // destination width = 36 = the 30% bend band
            drawable.draw(Canvas(output))

            assertTrue(
                "the turning paper must not disappear at the exact 30% phase boundary",
                output.getPixel(18, height / 2) != revealed.getPixel(18, height / 2),
            )
        } finally {
            drawable.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    private fun xRampBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                val color = Color.rgb(x.coerceAtMost(255), 30, 10)
                for (y in 0 until height) setPixel(x, y, color)
            }
        }
}
