package dev.readflow.render.epub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RenderNode
import org.junit.Assert.assertEquals
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
    fun `slide RenderNode artifact has explicit recorded viewport bounds`() {
        val width = 37
        val height = 53
        val artifact = SlidePageArtifact.record(width, height) { }
        try {
            val record = checkNotNull(artifact.javaClass.getDeclaredField("record").apply {
                isAccessible = true
            }.get(artifact))
            assertTrue(
                "API 29+ artifact recording must not silently fall back to Picture",
                record.javaClass.name.endsWith("Api29SlidePageRenderNodeRecord"),
            )
            val node = record.javaClass.getDeclaredField("renderNode").apply {
                isAccessible = true
            }.get(record) as RenderNode

            assertEquals(0, node.left)
            assertEquals(0, node.top)
            assertEquals(width, node.right)
            assertEquals(height, node.bottom)
            assertEquals(width, node.width)
            assertEquals(height, node.height)
            assertTrue("the node must retain its completed display list", node.hasDisplayList())
        } finally {
            artifact.discard()
        }
    }

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
    fun `slide artifact alias transfer clears both drawable owners without recycling transferred ownership`() {
        val artifact = SlidePageArtifact.record(4, 4) { }
        val revealedAlias = SlidePageFrame.ArtifactFrame(artifact)
        val frontAlias = SlidePageFrame.ArtifactFrame(artifact)
        var recyclerCalls = 0
        val drawable = PageSlideDrawable(
            frontAlias,
            revealedAlias,
            4,
            4,
            forward = true,
            density = 1f,
            frameRecycler = { recyclerCalls += 1 },
        )
        try {
            val returned = checkNotNull(drawable.takeRevealedFrame()) as SlidePageFrame.ArtifactFrame
            assertSame("the transferred frame must be the exact revealed alias", revealedAlias, returned)
            assertSame("the transferred frame must carry the shared artifact", artifact, returned.artifact)
            assertNull("the transferred alias must clear the front owner", drawable.takeFrontFrame())
            drawable.recycle()
            assertEquals("the transferred artifact must not be recycled again", 0, recyclerCalls)
            assertNull("bitmap accessor on an artifact frame returns null", drawable.takeRevealedBitmap())
        } finally {
            artifact.discard()
        }
    }

    @Test
    fun `slide bitmap accessor leaves artifact owners for normal cleanup`() {
        val frontArtifact = SlidePageArtifact.record(4, 4) { }
        val revealedArtifact = SlidePageArtifact.record(4, 4) { }
        var recyclerCalls = 0
        val drawable = PageSlideDrawable(
            SlidePageFrame.ArtifactFrame(frontArtifact),
            SlidePageFrame.ArtifactFrame(revealedArtifact),
            4,
            4,
            forward = true,
            density = 1f,
            frameRecycler = { recyclerCalls += 1 },
        )
        try {
            assertNull("artifact frames expose no revealed bitmap", drawable.takeRevealedBitmap())
            assertNull("artifact frames expose no front bitmap", drawable.takeFrontBitmap())
            drawable.recycle()
            assertEquals("recycle must own and release both artifact frames exactly once", 2, recyclerCalls)
        } finally {
            frontArtifact.discard()
            revealedArtifact.discard()
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
