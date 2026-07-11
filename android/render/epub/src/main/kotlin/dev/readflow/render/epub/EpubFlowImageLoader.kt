package dev.readflow.render.epub

import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.ImageSizeResolver
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Computes the on-screen size for a flow image from its intrinsic pixels. Full-page illustrations
 * (covers/彩插, in [fullPageHrefs]) are FITTED to the whole viewport (column × page), preserving
 * aspect ratio and UPSCALING when intrinsic pixels are smaller — so a cover fills the page like the
 * legacy paged renderer. Inline images keep intrinsic size capped at the column width and an inline
 * max height, so avatars/footnote glyphs stay small.
 */
internal fun epubFlowImageTargetSize(
    href: String,
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    columnWidthPx: Int,
    pageHeightPx: Int,
    inlineMaxHeightPx: Int,
    fullPageHrefs: Set<String>,
): Rect {
    val iw = intrinsicWidth.coerceAtLeast(1)
    val ih = intrinsicHeight.coerceAtLeast(1)
    val col = columnWidthPx.coerceAtLeast(1)
    if (href in fullPageHrefs) {
        val page = pageHeightPx.coerceAtLeast(1)
        val scale = min(col.toFloat() / iw, page.toFloat() / ih)
        val w = (iw * scale).roundToInt().coerceIn(1, col)
        val h = (ih * scale).roundToInt().coerceIn(1, page)
        return Rect(0, 0, w, h)
    }
    var w = min(iw, col)
    var h = (w.toFloat() * ih / iw).roundToInt().coerceAtLeast(1)
    if (inlineMaxHeightPx in 1 until h) {
        h = inlineMaxHeightPx
        w = (h.toFloat() * iw / ih).roundToInt().coerceAtLeast(1)
    }
    return Rect(0, 0, w, h)
}

/**
 * Markwon (Apache-2.0) async image loader for the continuous-flow surface. Decodes EPUB zip images
 * OFF the main thread and posts the result back so the hosting TextView re-lays-out the image line
 * (审计 M7: no eager full-res bitmap retention). Bitmaps are only decoded for images actually
 * attached to the layout, and cancelled when detached.
 *
 * The decoded drawable's BOUNDS are set here to the final on-screen size. Markwon's [AsyncDrawable]
 * only routes through [ImageSizeResolver] once it has a non-zero canvas width (set lazily by the
 * scheduler); until then it falls back to the drawable's own bounds. Sizing here makes full-page
 * covers fill the viewport regardless of that timing (审计 regression: a column-width cap left the
 * cover small with whitespace below it).
 */
internal class EpubFlowImageLoader(
    private val epubFileProvider: () -> File?,
    private val executor: ExecutorService,
    private val columnWidthPx: Int,
    private val pageHeightProvider: () -> Int,
    private val inlineMaxHeightPx: Int,
    private val fullPageHrefs: Set<String>,
    private val imageBoundsProvider: (String) -> EpubImageBounds? = { href ->
        epubFileProvider()?.let { decodeEpubImageBounds(it, href) }
    },
    private val onImageResultChanged: (() -> Unit)? = null,
    private val onDecodeFinished: (() -> Unit)? = null,
) : AsyncDrawableLoader() {

    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = Collections.synchronizedMap(WeakHashMap<AsyncDrawable, Future<*>>())

    /** Returns true while at least one async image decode is still in flight. */
    fun hasPendingDecodes(): Boolean = inFlight.isNotEmpty()

    override fun load(drawable: AsyncDrawable) {
        val file = epubFileProvider() ?: return
        val href = drawable.destination
        val pageHeightPx = pageHeightProvider().coerceAtLeast(1)
        // Full-page images may be upscaled to fill the viewport, so decode them at the larger of the
        // two viewport dimensions to avoid blur; inline images never exceed the column width.
        val maxSide = if (href in fullPageHrefs) {
            maxOf(columnWidthPx, pageHeightPx).coerceAtLeast(64)
        } else {
            columnWidthPx.coerceAtLeast(64)
        }
        // Inline images (avatars/icons/footnote glyphs) size independently of the page height, so their
        // pre-decode placeholder box is already final — reuse it to avoid a decode-time reflow. Full-page
        // images FIT the page height, but the placeholder was reserved pre-measure against the engine's
        // screen estimate (~100px too tall → the cover overflowed one page and got clipped away). Re-fit
        // full-page images at decode time, when [pageHeightProvider] returns the MEASURED viewport (审:
        // 封面/彩插顶到边缘被裁 / 闪一下消失). Never reuse a full-page image's stale reserved box.
        val isFullPage = href in fullPageHrefs
        val reservedBounds = if (isFullPage) {
            null
        } else {
            drawable.bounds.takeUnless { it.isEmpty }?.let { Rect(it) }
        }
        val future = executor.submit {
            val bitmap = decodeEpubImage(file, href, maxSide = maxSide)
            handler.post {
                if (inFlight.remove(drawable) == null) {
                    bitmap?.recycle()
                    return@post
                }
                var installed = false
                try {
                    if (bitmap != null && drawable.isAttached) {
                        val d = BitmapDrawable(null, bitmap)
                        // Re-read the page height on the main thread: for a full-page image this is now the real
                        // measured viewport, so the fit lands inside one page instead of the pre-measure estimate.
                        val target = reservedBounds ?: epubFlowImageTargetSize(
                            href = href,
                            intrinsicWidth = bitmap.width,
                            intrinsicHeight = bitmap.height,
                            columnWidthPx = columnWidthPx,
                            pageHeightPx = pageHeightProvider().coerceAtLeast(1),
                            inlineMaxHeightPx = inlineMaxHeightPx,
                            fullPageHrefs = fullPageHrefs,
                        )
                        d.setBounds(0, 0, target.width(), target.height())
                        drawable.result = d
                        installed = true
                        // Bounds-equal placeholder swaps do not reliably make TextView re-record the image span.
                        // Invalidate the drawable and notify the host to rebuild the text layout.
                        drawable.invalidateSelf()
                        onImageResultChanged?.invoke()
                    }
                } finally {
                    if (!installed) bitmap?.recycle()
                    onDecodeFinished?.invoke()
                }
            }
        }
        inFlight[drawable] = future
    }

    override fun cancel(drawable: AsyncDrawable) {
        val pending = inFlight.remove(drawable) ?: return
        pending.cancel(true)
        handler.post { onDecodeFinished?.invoke() }
    }

    // Reserve the final image box before pixel decode. Markwon treats a non-empty-bounds placeholder
    // as a result for ReplacementSpan measurement, but still calls load(...) after attach because the
    // current result is the placeholder. This keeps first pagination close to the final image geometry.
    override fun placeholder(drawable: AsyncDrawable): Drawable? {
        val bounds = imageBoundsProvider(drawable.destination) ?: return null
        val pageHeightPx = pageHeightProvider().coerceAtLeast(1)
        val target = epubFlowImageTargetSize(
            href = drawable.destination,
            intrinsicWidth = bounds.width,
            intrinsicHeight = bounds.height,
            columnWidthPx = columnWidthPx,
            pageHeightPx = pageHeightPx,
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
        )
        return ColorDrawable(android.graphics.Color.TRANSPARENT).apply {
            setBounds(0, 0, target.width(), target.height())
        }
    }
}

/**
 * Fallback sizer used by Markwon's [AsyncDrawable] once it has a canvas width. Delegates to the same
 * [epubFlowImageTargetSize] the loader uses, so the on-screen size is identical on either path.
 */
internal class EpubFlowImageSizeResolver(
    private val columnWidthPx: Int,
    private val pageHeightProvider: () -> Int,
    private val inlineMaxHeightPx: Int,
    private val fullPageHrefs: Set<String>,
) : ImageSizeResolver() {

    override fun resolveImageSize(drawable: AsyncDrawable): Rect {
        val result = drawable.result ?: return Rect(0, 0, 1, 1)
        result.bounds.takeUnless { it.isEmpty }?.let { return Rect(it) }
        return epubFlowImageTargetSize(
            href = drawable.destination,
            intrinsicWidth = result.intrinsicWidth,
            intrinsicHeight = result.intrinsicHeight,
            columnWidthPx = columnWidthPx,
            pageHeightPx = pageHeightProvider().coerceAtLeast(1),
            inlineMaxHeightPx = inlineMaxHeightPx,
            fullPageHrefs = fullPageHrefs,
        )
    }
}
