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
) : AsyncDrawableLoader() {

    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = Collections.synchronizedMap(WeakHashMap<AsyncDrawable, Future<*>>())

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
        val reservedBounds = drawable.bounds.takeUnless { it.isEmpty }?.let { Rect(it) }
        val future = executor.submit {
            val bitmap = decodeEpubImage(file, href, maxSide = maxSide)
            if (bitmap == null) {
                handler.post { inFlight.remove(drawable) }
                return@submit
            }
            val target = reservedBounds ?: epubFlowImageTargetSize(
                href = href,
                intrinsicWidth = bitmap.width,
                intrinsicHeight = bitmap.height,
                columnWidthPx = columnWidthPx,
                pageHeightPx = pageHeightPx,
                inlineMaxHeightPx = inlineMaxHeightPx,
                fullPageHrefs = fullPageHrefs,
            )
            val d = BitmapDrawable(null, bitmap)
            d.setBounds(0, 0, target.width(), target.height())
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
