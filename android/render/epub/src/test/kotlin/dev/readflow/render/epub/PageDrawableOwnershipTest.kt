package dev.readflow.render.epub

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageDrawableOwnershipTest {

    @Test
    fun `curl transfers revealed bitmap without recycling it during cleanup`() {
        verifyDistinctOwnership { front, revealed ->
            PageCurlDrawable(front, revealed, 4, 4, forward = true, density = 1f)
        }
    }

    @Test
    fun `slide transfers revealed bitmap without recycling it during cleanup`() {
        verifyDistinctOwnership { front, revealed ->
            PageSlideDrawable(front, revealed, 4, 4, forward = true, density = 1f)
        }
    }

    @Test
    fun `curl alias transfer clears both drawable owners`() {
        val bitmap = bitmap()
        val drawable = PageCurlDrawable(bitmap, bitmap, 4, 4, forward = true, density = 1f)
        try {
            assertSame(bitmap, drawable.takeRevealedBitmap())
            assertNull(drawable.takeRevealedBitmap())
            drawable.recycle()
            assertFalse("the transferred alias belongs to the continuity cover", bitmap.isRecycled)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun `slide alias transfer clears both drawable owners`() {
        val bitmap = bitmap()
        val drawable = PageSlideDrawable(bitmap, bitmap, 4, 4, forward = true, density = 1f)
        try {
            assertSame(bitmap, drawable.takeRevealedBitmap())
            assertNull(drawable.takeRevealedBitmap())
            drawable.recycle()
            assertFalse("the transferred alias belongs to the continuity cover", bitmap.isRecycled)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun `GL handoff keeps revealed bitmap alive after overlay dismiss`() {
        val front = bitmap()
        val revealed = bitmap()
        val overlay = EpubCurlOverlay(RuntimeEnvironment.getApplication() as Application)
        try {
            overlay.start(front, revealed, forward = true, settled = {})

            assertSame(revealed, overlay.takeRevealedBitmap())
            assertNull(overlay.takeRevealedBitmap())
            overlay.dismiss()

            assertTrue("dismiss still owns and recycles the outgoing GL page", front.isRecycled)
            assertFalse("dismiss must not recycle the transferred continuity cover", revealed.isRecycled)
        } finally {
            overlay.dismiss()
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
        }
    }

    @Test
    fun `GL alias handoff clears both overlay owners`() {
        val bitmap = bitmap()
        val overlay = EpubCurlOverlay(RuntimeEnvironment.getApplication() as Application)
        try {
            overlay.start(bitmap, bitmap, forward = true, settled = {})

            assertSame(bitmap, overlay.takeRevealedBitmap())
            overlay.dismiss()

            assertFalse("the transferred alias belongs to the continuity cover", bitmap.isRecycled)
        } finally {
            overlay.dismiss()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun verifyDistinctOwnership(factory: (Bitmap, Bitmap) -> Any) {
        val front = bitmap()
        val revealed = bitmap()
        try {
            when (val drawable = factory(front, revealed)) {
                is PageCurlDrawable -> {
                    assertSame(revealed, drawable.takeRevealedBitmap())
                    assertNull(drawable.takeRevealedBitmap())
                    drawable.recycle()
                }
                is PageSlideDrawable -> {
                    assertSame(revealed, drawable.takeRevealedBitmap())
                    assertNull(drawable.takeRevealedBitmap())
                    drawable.recycle()
                }
            }
            assertTrue("cleanup still owns and recycles the outgoing page", front.isRecycled)
            assertFalse("cleanup must not recycle the transferred continuity cover", revealed.isRecycled)
        } finally {
            if (!front.isRecycled) front.recycle()
            if (!revealed.isRecycled) revealed.recycle()
        }
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
}
