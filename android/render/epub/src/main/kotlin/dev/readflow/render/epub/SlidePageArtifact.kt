package dev.readflow.render.epub

import androidx.annotation.RequiresApi
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.os.Build

/**
 * One frozen same-chapter page for a warm SLIDE turn, recorded as a command artifact instead of a
 * viewport-sized Bitmap. The recording replays the exact snapshot semantics of the Bitmap page-shot
 * path — theme background, exact page clip, translated [android.widget.TextView] layout draw, and
 * the page-boundary image preview crop — so warm SLIDE turns never allocate or upload a page-shot
 * texture on the precache or animation path.
 *
 * API 29+ records once into an android.graphics.RenderNode and draws with
 * Canvas.drawRenderNode; API 26-28 uses the [Picture] fallback and draws with Canvas.drawPicture.
 * The common artifact owner only holds the API-safe [SlidePageArtifactRecord] interface; RenderNode
 * references live exclusively in the SDK-gated [Api29SlidePageRenderNodeRecord] implementation,
 * which is never resolved or invoked below API 29. A failed RenderNode record conservatively
 * re-records as a Picture through normal exception handling.
 */
internal class SlidePageArtifact internal constructor(
    private var record: SlidePageArtifactRecord?,
    val width: Int,
    val height: Int,
) {
    /** Replays the recorded commands at the current canvas origin (viewport coordinates). */
    fun drawTo(canvas: Canvas) {
        record?.drawTo(canvas)
    }

    /**
     * Releases the recorded display list / picture reference. Call only after the render barrier has
     * retired the host frames that could still reference this artifact.
     */
    fun discard() {
        record?.discard()
        record = null
    }

    companion object {
        /**
         * Records [draw] once at [width] x [height]. RenderNode on API 29+ with a conservative
         * Picture fallback; Picture everywhere below API 29.
         */
        fun record(width: Int, height: Int, draw: (Canvas) -> Unit): SlidePageArtifact {
            require(width > 0 && height > 0) {
                "slide artifact requires a positive viewport, got ${width}x$height"
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    Api29SlidePageRenderNodeRecord.record(width, height, draw)
                } catch (_: Throwable) {
                    SlidePagePictureRecord.record(width, height, draw)
                }
            } else {
                SlidePagePictureRecord.record(width, height, draw)
            }
        }
    }
}

/**
 * API-safe recorded page-command owner. Implementations are SDK-gated: the RenderNode recording
 * lives in the private [Api29SlidePageRenderNodeRecord] class, and the [Picture] fallback is
 * API 26-safe. The common [SlidePageArtifact] never references an API 29+ type through this owner.
 */
internal interface SlidePageArtifactRecord {
    fun drawTo(canvas: Canvas)
    fun discard()
}

/** One frame slot owned by a SLIDE transaction: a legacy Bitmap or a frozen command artifact. */
internal sealed class SlidePageFrame {
    class BitmapFrame(val bitmap: Bitmap) : SlidePageFrame()
    class ArtifactFrame(val artifact: SlidePageArtifact) : SlidePageFrame()
}

/**
 * API 29+ RenderNode implementation, isolated so API 26-28 never resolves android.graphics.RenderNode
 * while the artifact abstraction loads. This is the only type in the file that holds or calls it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class Api29SlidePageRenderNodeRecord private constructor(
    private var renderNode: android.graphics.RenderNode?,
) : SlidePageArtifactRecord {
    override fun drawTo(canvas: Canvas) {
        renderNode?.let(canvas::drawRenderNode)
    }

    override fun discard() {
        renderNode?.discardDisplayList()
        renderNode = null
    }

    companion object {
        fun record(width: Int, height: Int, draw: (Canvas) -> Unit): SlidePageArtifact {
            val node = android.graphics.RenderNode("slide-page-artifact")
            node.setPosition(0, 0, width, height)
            val canvas = node.beginRecording(width, height)
            draw(canvas)
            node.endRecording()
            return SlidePageArtifact(Api29SlidePageRenderNodeRecord(node), width, height)
        }
    }
}

/**
 * API 26-28 supported fallback: records once into a [Picture] and replays with drawPicture.
 * [Picture] has no public close(); discarding releases the reference so the recorded stream is
 * released by the GC/native finalizer.
 */
private class SlidePagePictureRecord private constructor(
    private var picture: Picture?,
) : SlidePageArtifactRecord {
    override fun drawTo(canvas: Canvas) {
        picture?.let(canvas::drawPicture)
    }

    override fun discard() {
        picture = null
    }

    companion object {
        fun record(width: Int, height: Int, draw: (Canvas) -> Unit): SlidePageArtifact {
            val picture = Picture()
            val canvas = picture.beginRecording(width, height)
            draw(canvas)
            picture.endRecording()
            return SlidePageArtifact(SlidePagePictureRecord(picture), width, height)
        }
    }
}
