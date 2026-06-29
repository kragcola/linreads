package dev.readflow.render.epub

import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.DrawableUtils
import io.noties.markwon.image.ImageSizeResolver
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Markwon (Apache-2.0) async image loader for the continuous-flow surface. Decodes EPUB zip images
 * OFF the main thread, downsampled to the display column width, and posts the result back so the
 * hosting TextView re-lays-out the image line (审计 M7: no eager full-res bitmap retention). Bitmaps
 * are only decoded for images actually attached to the layout, and cancelled when detached.
 */
internal class EpubFlowImageLoader(
    private val epubFileProvider: () -> File?,
    private val executor: ExecutorService,
    private val columnWidthPx: Int,
) : AsyncDrawableLoader() {

    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = Collections.synchronizedMap(WeakHashMap<AsyncDrawable, Future<*>>())

    override fun load(drawable: AsyncDrawable) {
        val file = epubFileProvider() ?: return
        val href = drawable.destination
        val future = executor.submit {
            val bitmap = decodeEpubImage(file, href, maxSide = columnWidthPx.coerceAtLeast(64))
            if (bitmap == null) {
                handler.post { inFlight.remove(drawable) }
                return@submit
            }
            val d = BitmapDrawable(null, bitmap).also { DrawableUtils.applyIntrinsicBounds(it) }
            handler.post {
                inFlight.remove(drawable)
                if (drawable.isAttached) drawable.result = d
            }
        }
        inFlight[drawable] = future
    }

    override fun cancel(drawable: AsyncDrawable) {
        inFlight.remove(drawable)?.cancel(true)
    }

    override fun placeholder(drawable: AsyncDrawable): Drawable? = null
}

/**
 * Sizes an image to the column width (small images keep intrinsic size, larger scale down) and caps
 * height to one viewport so a single image line never exceeds a page — the paginator's oversized-line
 * guard then gives such an image its own page.
 */
internal class EpubFlowImageSizeResolver(
    private val columnWidthPx: Int,
    private val maxHeightPx: Int,
) : ImageSizeResolver() {

    override fun resolveImageSize(drawable: AsyncDrawable): Rect {
        val result = drawable.result ?: return Rect(0, 0, 1, 1)
        val iw = result.intrinsicWidth.coerceAtLeast(1)
        val ih = result.intrinsicHeight.coerceAtLeast(1)
        var w = min(iw, columnWidthPx.coerceAtLeast(1))
        var h = (w.toFloat() * ih / iw).roundToInt().coerceAtLeast(1)
        if (maxHeightPx in 1 until h) {
            h = maxHeightPx
            w = (h.toFloat() * iw / ih).roundToInt().coerceAtLeast(1)
        }
        return Rect(0, 0, w, h)
    }
}
