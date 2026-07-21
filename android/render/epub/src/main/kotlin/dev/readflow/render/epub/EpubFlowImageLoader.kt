package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.ImageSizeResolver
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.min
import kotlin.math.roundToInt

private const val EPUB_DISPLAY_PROMOTION_FADE_MS = 120L

/**
 * Computes the on-screen size for a flow image from its intrinsic pixels. Full-page illustrations
 * (covers/彩插, when [isFullPage]) are FITTED to the whole viewport (column × page), preserving
 * aspect ratio and UPSCALING when intrinsic pixels are smaller — so a cover fills the page like the
 * legacy paged renderer. Inline images keep intrinsic size capped at the column width and an inline
 * max height, so avatars/footnote glyphs stay small.
 */
internal fun epubFlowImageTargetSize(
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    columnWidthPx: Int,
    pageHeightPx: Int,
    inlineMaxHeightPx: Int,
    isFullPage: Boolean,
): Rect {
    val iw = intrinsicWidth.coerceAtLeast(1)
    val ih = intrinsicHeight.coerceAtLeast(1)
    val col = columnWidthPx.coerceAtLeast(1)
    if (isFullPage) {
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

internal class EpubMaxHeightImageSizeResolver(
    private val delegate: ImageSizeResolver,
    private val maxHeightProvider: () -> Int,
) : ImageSizeResolver() {
    override fun resolveImageSize(drawable: AsyncDrawable): Rect =
        constrain(delegate.resolveImageSize(drawable))

    fun constrain(bounds: Rect): Rect {
        val maxHeight = try {
            maxHeightProvider().coerceAtLeast(1)
        } catch (_: RuntimeException) {
            bounds.height().coerceAtLeast(1)
        }
        if (bounds.height() <= maxHeight) return Rect(bounds)
        val scale = maxHeight.toFloat() / bounds.height().coerceAtLeast(1)
        return Rect(
            0,
            0,
            (bounds.width() * scale).roundToInt().coerceAtLeast(1),
            maxHeight,
        )
    }
}

internal class EpubOccurrenceImageSizeResolver(
    private val delegate: ImageSizeResolver,
    val isFullPage: Boolean,
) : ImageSizeResolver() {
    override fun resolveImageSize(drawable: AsyncDrawable): Rect =
        if (delegate is EpubFlowImageSizeResolver) {
            delegate.resolveImageSize(drawable, isFullPage)
        } else {
            delegate.resolveImageSize(drawable)
        }

    fun constrain(bounds: Rect): Rect =
        (delegate as? EpubMaxHeightImageSizeResolver)?.constrain(bounds) ?: bounds
}

private fun AsyncDrawable.isFullPageOccurrence(fullPageHrefs: Set<String>): Boolean =
    (imageSizeResolver as? EpubOccurrenceImageSizeResolver)?.isFullPage
        ?: (destination in fullPageHrefs)

private fun AsyncDrawable.constrainTarget(bounds: Rect): Rect =
    when (val resolver = imageSizeResolver) {
        is EpubOccurrenceImageSizeResolver -> resolver.constrain(bounds)
        is EpubMaxHeightImageSizeResolver -> resolver.constrain(bounds)
        else -> bounds
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
internal enum class EpubAsyncImageResultKind { PIXELS_ONLY, GEOMETRY_CHANGED }

internal data class EpubAsyncImageResult(
    val layoutStart: Int,
    val destination: String,
    val generation: Long,
    val beforeBounds: Rect,
    val afterBounds: Rect,
    val isFullPage: Boolean,
    val quality: EpubImageRenderQuality = EpubImageRenderQuality.DISPLAY,
    val replacesPlaceholder: Boolean = true,
) {
    val kind: EpubAsyncImageResultKind = if (
        beforeBounds.isEmpty ||
        beforeBounds.width() != afterBounds.width() ||
        beforeBounds.height() != afterBounds.height()
    ) {
        EpubAsyncImageResultKind.GEOMETRY_CHANGED
    } else {
        EpubAsyncImageResultKind.PIXELS_ONLY
    }

    val requiresTextRebind: Boolean
        // Unknown geometry has no retained pixel layer, so its first decoded result still replaces
        // the span owner. Known-geometry placeholders keep one drawable identity and update in place.
        get() = replacesPlaceholder
}

/** Retained image layer whose geometry is independent from the decoded pixel resolution. */
private object EpubImagePixelDrawableHolder {
    val bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
}

internal interface EpubImagePixelSource {
    val hasDecodedPixels: Boolean
    fun drawPixels(canvas: Canvas, destination: Rect)
}

private class EpubImagePixelDrawable(
    geometry: Rect,
) : BitmapDrawable(null, EpubImagePixelDrawableHolder.bitmap), EpubImagePixelSource {
    private var fallbackPixels: Bitmap? = null
    private var fallbackQuality: EpubImageRenderQuality? = null
    private var pixels: Bitmap = EpubImagePixelDrawableHolder.bitmap
    private var previousPixels: Bitmap? = null
    private var transitionStartedAtMs = 0L
    private var geometryWidth = geometry.width().coerceAtLeast(1)
    private var geometryHeight = geometry.height().coerceAtLeast(1)

    init {
        setBounds(geometry)
    }

    override fun getIntrinsicWidth(): Int = geometryWidth

    override fun getIntrinsicHeight(): Int = geometryHeight

    override fun draw(canvas: Canvas) = drawPixels(canvas, bounds)

    override fun drawPixels(canvas: Canvas, destination: Rect) {
        val previous = previousPixels
        if (previous == null) {
            canvas.drawBitmap(pixels, null, destination, paint)
            return
        }
        val elapsed = (SystemClock.uptimeMillis() - transitionStartedAtMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / EPUB_DISPLAY_PROMOTION_FADE_MS).coerceIn(0f, 1f)
        val originalAlpha = paint.alpha
        paint.alpha = (originalAlpha * (1f - progress)).roundToInt().coerceIn(0, originalAlpha)
        canvas.drawBitmap(previous, null, destination, paint)
        paint.alpha = (originalAlpha * progress).roundToInt().coerceIn(0, originalAlpha)
        canvas.drawBitmap(pixels, null, destination, paint)
        paint.alpha = originalAlpha
        if (progress < 1f) {
            invalidateSelf()
        } else {
            previousPixels = null
        }
    }

    override val hasDecodedPixels: Boolean
        get() = fallbackPixels != null

    fun installInitialPixels(bitmap: Bitmap, quality: EpubImageRenderQuality) {
        fallbackPixels = bitmap
        fallbackQuality = quality
        pixels = bitmap
        previousPixels = null
        transitionStartedAtMs = 0L
        invalidateSelf()
    }

    fun updateGeometry(geometry: Rect) {
        geometryWidth = geometry.width().coerceAtLeast(1)
        geometryHeight = geometry.height().coerceAtLeast(1)
        bounds = geometry
        invalidateSelf()
    }

    fun promotePixels(bitmap: Bitmap) {
        if (bitmap === pixels) return
        previousPixels = pixels
        pixels = bitmap
        transitionStartedAtMs = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    fun restoreFallbackPixels(): EpubImageRenderQuality? {
        val fallback = fallbackPixels ?: return null
        val quality = fallbackQuality ?: return null
        if (pixels === fallback) return null
        previousPixels = null
        pixels = fallback
        transitionStartedAtMs = 0L
        invalidateSelf()
        return quality
    }

    fun finishPromotion() {
        previousPixels = null
        transitionStartedAtMs = 0L
        invalidateSelf()
    }
}

internal class EpubFlowImageLoader(
    private val epubFileProvider: () -> File?,
    private val executor: ExecutorService,
    private val priorityExecutor: ExecutorService? = null,
    private val priorityLayoutRangesProvider: () -> Collection<IntRange> = { emptyList() },
    private val columnWidthPx: Int,
    private val columnWidthProvider: () -> Int = { columnWidthPx },
    private val pageHeightProvider: () -> Int,
    private val inlineMaxHeightPx: Int,
    private val fullPageHrefs: Set<String>,
    private val imageBoundsProvider: (String) -> EpubImageBounds? = { href ->
        epubFileProvider()?.let { decodeEpubImageBounds(it, href) }
    },
    private val imageQualityProvider: (layoutStart: Int) -> EpubImageRenderQuality = {
        EpubImageRenderQuality.DISPLAY
    },
    private val imageDecoder: (File, String, EpubImageDecodeBudget) -> Bitmap? = { file, href, budget ->
        decodeEpubImage(
            epubFile = file,
            entryPath = href,
            maxSide = budget.maxSide,
            maxPixels = budget.maxPixels,
        )
    },
    private val onImageResultChanged: ((EpubAsyncImageResult) -> Unit)? = null,
    private val onDecodeFinished: (() -> Unit)? = null,
) : AsyncDrawableLoader() {

    private val handler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private val inFlight = WeakHashMap<AsyncDrawable, DecodeRequest>()
    private val layoutStartByDrawable = WeakHashMap<AsyncDrawable, Int>()
    private val installedQualityByDrawable = WeakHashMap<AsyncDrawable, EpubImageRenderQuality>()
    private val promotionCompletionByDrawable = WeakHashMap<AsyncDrawable, Runnable>()
    private var decodeWindowRanges: List<IntRange>? = null
    private var decodeWindowRestrictsAdmission = false
    private var lifecycleGeneration = 0L
    private var released = false

    /** Returns true while at least one async image decode is still in flight. */
    fun hasPendingDecodes(): Boolean = synchronized(lifecycleLock) { inFlight.isNotEmpty() }

    /**
     * Returns true when a pending decode is relevant to [layoutRanges] (current / previous / next
     * page char windows). Unknown or unregistered occurrences ([layoutStart] missing or &lt; 0)
     * conservatively return true so reveal/precache never paints transparent placeholders.
     * An empty [layoutRanges] list also blocks while any decode is pending (no safe window).
     */
    fun hasRelevantPendingDecodes(layoutRanges: Collection<IntRange>): Boolean =
        synchronized(lifecycleLock) {
            if (inFlight.isEmpty()) return false
            if (layoutRanges.isEmpty()) return true
            for (drawable in inFlight.keys) {
                val start = layoutStartByDrawable[drawable]
                if (start == null || start < 0) return true
                if (layoutRanges.any { start in it }) return true
            }
            return false
        }

    fun registerOccurrence(drawable: AsyncDrawable, layoutStart: Int) {
        synchronized(lifecycleLock) {
            if (!released) layoutStartByDrawable[drawable] = layoutStart
        }
    }

    /**
     * Cancels work outside [layoutRanges] and wakes newly adjacent occurrences. Markwon attaches
     * every image span in a chapter, so the loader owns viewport admission instead of treating
     * attachment as permission to decode the whole spine.
     */
    fun updateDecodeWindow(layoutRanges: Collection<IntRange>): Int {
        val ranges = layoutRanges.toList()
        val (cancelled, candidates, generation) = synchronized(lifecycleLock) {
            if (released) return 0
            decodeWindowRanges = ranges
            decodeWindowRestrictsAdmission = true
            if (ranges.isEmpty()) return 0
            val staleRequests = buildList {
                val iterator = inFlight.entries.iterator()
                while (iterator.hasNext()) {
                    val (drawable, request) = iterator.next()
                    val layoutStart = layoutStartByDrawable[drawable] ?: continue
                    if (ranges.none { range -> layoutStart in range }) {
                        add(request)
                        iterator.remove()
                    }
                }
            }
            val dormant = layoutStartByDrawable.entries.mapNotNull { (drawable, layoutStart) ->
                drawable.takeIf {
                    drawable.isAttached &&
                        ranges.any { range -> layoutStart in range } &&
                        installedQualityByDrawable[drawable] == null &&
                        inFlight[drawable] == null
                }
            }
            Triple(staleRequests, dormant, lifecycleGeneration)
        }
        cancelled.forEach { request -> request.future?.cancel(true) }
        if (cancelled.isNotEmpty()) notifyDecodeFinished(generation)
        return candidates.count { requestLoad(it, forcedQuality = null) }
    }

    fun promoteToDisplayQuality(layoutRanges: Collection<IntRange>): Int {
        if (layoutRanges.isEmpty()) return 0
        val candidates = synchronized(lifecycleLock) {
            if (released) return 0
            layoutStartByDrawable.entries.mapNotNull { (drawable, layoutStart) ->
                drawable.takeIf {
                    drawable.isAttached &&
                        layoutRanges.any { range -> layoutStart in range } &&
                        installedQualityByDrawable[drawable] != EpubImageRenderQuality.DISPLAY
                }
            }
        }
        return candidates.count { requestLoad(it, EpubImageRenderQuality.DISPLAY) }
    }

    fun cancelDisplayPromotions(): Int {
        val (cancelled, completions, generation) = synchronized(lifecycleLock) {
            if (released) return 0
            val requests = inFlight.entries
                .filter { (_, request) -> request.isPromotion }
                .map { (drawable, request) ->
                    inFlight.remove(drawable)
                    request
                }
            Triple(requests, promotionCompletionByDrawable.values.toList(), lifecycleGeneration)
        }
        cancelled.forEach { request -> request.future?.cancel(true) }
        completions.forEach { completion ->
            handler.removeCallbacks(completion)
            completion.run()
        }
        if (cancelled.isNotEmpty()) notifyDecodeFinished(generation)
        return cancelled.size + completions.size
    }

    fun demoteDisplayQualityOutside(layoutRanges: Collection<IntRange>): Int {
        val generation: Long
        val candidates = synchronized(lifecycleLock) {
            if (released) return 0
            generation = lifecycleGeneration
            layoutStartByDrawable.entries.mapNotNull { (drawable, layoutStart) ->
                val layer = drawable.result as? EpubImagePixelDrawable ?: return@mapNotNull null
                if (
                    installedQualityByDrawable[drawable] == EpubImageRenderQuality.DISPLAY &&
                    layoutRanges.none { range -> layoutStart in range }
                ) {
                    Triple(drawable, layoutStart, layer)
                } else {
                    null
                }
            }
        }
        var demoted = 0
        candidates.forEach { (drawable, layoutStart, layer) ->
            val pendingCompletion = synchronized(lifecycleLock) {
                promotionCompletionByDrawable.remove(drawable)
            }
            pendingCompletion?.let(handler::removeCallbacks)
            val quality = layer.restoreFallbackPixels() ?: return@forEach
            val accepted = synchronized(lifecycleLock) {
                if (
                    released ||
                    generation != lifecycleGeneration ||
                    drawable.result !== layer
                ) {
                    false
                } else {
                    installedQualityByDrawable[drawable] = quality
                    true
                }
            }
            if (!accepted) return@forEach
            demoted++
            drawable.invalidateSelf()
            try {
                onImageResultChanged?.invoke(
                    EpubAsyncImageResult(
                        layoutStart = layoutStart,
                        destination = drawable.destination,
                        generation = generation,
                        beforeBounds = Rect(drawable.bounds),
                        afterBounds = Rect(drawable.bounds),
                        isFullPage = drawable.isFullPageOccurrence(fullPageHrefs),
                        quality = quality,
                        replacesPlaceholder = false,
                    ),
                )
            } catch (_: RuntimeException) {
                // The host can retire between the generation check and callback dispatch.
            }
        }
        return demoted
    }

    override fun load(drawable: AsyncDrawable) {
        requestLoad(drawable, forcedQuality = null)
    }

    private fun requestLoad(
        drawable: AsyncDrawable,
        forcedQuality: EpubImageRenderQuality?,
    ): Boolean {
        if (synchronized(lifecycleLock) { released }) return false
        val layoutStart = synchronized(lifecycleLock) { layoutStartByDrawable[drawable] ?: -1 }
        val decodeWindow = if (layoutStart >= 0) {
            currentDecodeWindow()
        } else {
            DecodeWindowSnapshot(emptyList(), restrictsAdmission = false)
        }
        if (forcedQuality == null && layoutStart >= 0 && decodeWindow.restrictsAdmission) {
            val admitted = decodeWindow.ranges.any { range -> layoutStart in range }
            if (!admitted) return false
        }
        val file = try {
            epubFileProvider()
        } catch (_: RuntimeException) {
            null
        } ?: return false
        val href = drawable.destination
        val pageHeightPx = try {
            pageHeightProvider().coerceAtLeast(1)
        } catch (_: RuntimeException) {
            return false
        }
        val currentColumnWidthPx = currentColumnWidthPx()
        val isFullPage = drawable.isFullPageOccurrence(fullPageHrefs)
        val intrinsicBounds = try {
            imageBoundsProvider(href)
        } catch (_: RuntimeException) {
            null
        }
        val targetBounds = when {
            // Re-fit full-page images against the measured viewport, never the pre-measure estimate.
            isFullPage && intrinsicBounds != null -> drawable.constrainTarget(
                epubFlowImageTargetSize(
                    intrinsicWidth = intrinsicBounds.width,
                    intrinsicHeight = intrinsicBounds.height,
                    columnWidthPx = currentColumnWidthPx,
                    pageHeightPx = pageHeightPx,
                    inlineMaxHeightPx = inlineMaxHeightPx,
                    isFullPage = true,
                ),
            )
            !drawable.bounds.isEmpty -> Rect(drawable.bounds)
            intrinsicBounds != null -> drawable.constrainTarget(
                epubFlowImageTargetSize(
                    intrinsicWidth = intrinsicBounds.width,
                    intrinsicHeight = intrinsicBounds.height,
                    columnWidthPx = currentColumnWidthPx,
                    pageHeightPx = pageHeightPx,
                    inlineMaxHeightPx = inlineMaxHeightPx,
                    isFullPage = false,
                ),
            )
            else -> Rect(0, 0, currentColumnWidthPx, pageHeightPx)
        }
        val quality = forcedQuality ?: try {
            imageQualityProvider(layoutStart)
        } catch (_: RuntimeException) {
            EpubImageRenderQuality.DISPLAY
        }
        val decodeBudget = epubImageDecodeBudget(
            targetWidth = targetBounds.width(),
            targetHeight = targetBounds.height(),
            quality = quality,
        )
        // Inline images (avatars/icons/footnote glyphs) size independently of the page height, so their
        // pre-decode placeholder box is already final — reuse it to avoid a decode-time reflow. Full-page
        // images FIT the page height, but the placeholder was reserved pre-measure against the engine's
        // screen estimate (~100px too tall → the cover overflowed one page and got clipped away). Re-fit
        // full-page images at decode time, when [pageHeightProvider] returns the MEASURED viewport (审:
        // 封面/彩插顶到边缘被裁 / 闪一下消失). Never reuse a full-page image's stale reserved box.
        val reservedBounds = Rect(targetBounds)
        val (request, superseded) = synchronized(lifecycleLock) {
            if (released) return false
            val installedQuality = installedQualityByDrawable[drawable]
            if (installedQuality != null && installedQuality.ordinal >= quality.ordinal) return false
            val currentRequest = inFlight[drawable]
            if (currentRequest != null && currentRequest.quality.ordinal >= quality.ordinal) return false
            val old = inFlight.remove(drawable)
            val new = DecodeRequest(
                generation = lifecycleGeneration,
                quality = quality,
                isPromotion = forcedQuality != null,
            )
            inFlight[drawable] = new
            new to old
        }
        superseded?.future?.cancel(true)
        val decodeExecutor = priorityExecutor?.takeIf {
            layoutStart >= 0 && decodeWindow.ranges.any { range -> layoutStart in range }
        } ?: executor
        val future = try {
            decodeExecutor.submit {
                var bitmap: Bitmap? = null
                try {
                    bitmap = imageDecoder(file, href, decodeBudget)
                } finally {
                    postDecodeResult(drawable, request, isFullPage, reservedBounds, bitmap)
                }
            }
        } catch (_: RuntimeException) {
            finishWithoutResult(drawable, request)
            return false
        }
        val shouldCancel = synchronized(lifecycleLock) {
            request.future = future
            !isCurrentRequestLocked(drawable, request)
        }
        if (shouldCancel) future.cancel(true)
        return !shouldCancel
    }

    private fun currentDecodeWindow(): DecodeWindowSnapshot {
        synchronized(lifecycleLock) {
            decodeWindowRanges?.let { ranges ->
                return DecodeWindowSnapshot(ranges, decodeWindowRestrictsAdmission)
            }
            if (released) return DecodeWindowSnapshot(emptyList(), restrictsAdmission = true)
        }
        val provided = runCatching { priorityLayoutRangesProvider().toList() }
            .getOrDefault(emptyList())
        return synchronized(lifecycleLock) {
            val existing = decodeWindowRanges
            if (existing != null) {
                DecodeWindowSnapshot(existing, decodeWindowRestrictsAdmission)
            } else {
                decodeWindowRanges = provided
                decodeWindowRestrictsAdmission = provided.isNotEmpty()
                DecodeWindowSnapshot(provided, decodeWindowRestrictsAdmission)
            }
        }
    }

    override fun cancel(drawable: AsyncDrawable) {
        val (pending, generation) = synchronized(lifecycleLock) {
            (inFlight.remove(drawable) ?: return) to lifecycleGeneration
        }
        pending.future?.cancel(true)
        notifyDecodeFinished(generation)
    }

    /** Cancels current work while keeping this loader reusable for a later scheduling pass. */
    fun cancelAll() {
        cancelAll(permanently = false)
    }

    /** Permanently releases this loader. Subsequent [load] calls are ignored. */
    fun releaseAll() {
        cancelAll(permanently = true)
    }

    // Reserve the final image box before pixel decode. Markwon treats a non-empty-bounds placeholder
    // as a result for ReplacementSpan measurement, but still calls load(...) after attach because the
    // current result is the placeholder. This keeps first pagination close to the final image geometry.
    override fun placeholder(drawable: AsyncDrawable): Drawable? {
        if (synchronized(lifecycleLock) { released }) return null
        val bounds = try {
            imageBoundsProvider(drawable.destination)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val pageHeightPx = try {
            pageHeightProvider().coerceAtLeast(1)
        } catch (_: RuntimeException) {
            return null
        }
        val target = drawable.constrainTarget(
            epubFlowImageTargetSize(
                intrinsicWidth = bounds.width,
                intrinsicHeight = bounds.height,
                columnWidthPx = currentColumnWidthPx(),
                pageHeightPx = pageHeightPx,
                inlineMaxHeightPx = inlineMaxHeightPx,
                isFullPage = drawable.isFullPageOccurrence(fullPageHrefs),
            ),
        )
        return EpubImagePixelDrawable(target)
    }

    private fun postDecodeResult(
        drawable: AsyncDrawable,
        request: DecodeRequest,
        isFullPage: Boolean,
        reservedBounds: Rect?,
        bitmap: Bitmap?,
    ) {
        val posted = handler.post {
            var accepted = false
            var installed = false
            var installedResult: EpubAsyncImageResult? = null
            var promotedLayer: EpubImagePixelDrawable? = null
            var decodeFinishedNotified = false
            try {
                synchronized(lifecycleLock) {
                    if (!isCurrentRequestLocked(drawable, request)) return@synchronized
                    accepted = true
                    if (bitmap != null && drawable.isAttached) {
                        val beforeBounds = Rect(drawable.bounds)
                        val target = reservedBounds ?: Rect(drawable.bounds)
                        val retainedLayer = drawable.result as? EpubImagePixelDrawable
                        val replacesPlaceholder = retainedLayer == null
                        val result = retainedLayer ?: EpubImagePixelDrawable(target)
                        if (drawable.isAttached) {
                            if (replacesPlaceholder) {
                                result.installInitialPixels(bitmap, request.quality)
                                drawable.result = result
                            } else if (!result.hasDecodedPixels) {
                                result.updateGeometry(target)
                                if (drawable.bounds != target) drawable.bounds = target
                                result.installInitialPixels(bitmap, request.quality)
                            } else {
                                result.promotePixels(bitmap)
                                if (request.quality == EpubImageRenderQuality.DISPLAY) {
                                    promotedLayer = result
                                }
                                drawable.invalidateSelf()
                            }
                            installedQualityByDrawable[drawable] = request.quality
                            installed = true
                            installedResult = EpubAsyncImageResult(
                                layoutStart = layoutStartByDrawable[drawable] ?: -1,
                                destination = drawable.destination,
                                generation = request.generation,
                                beforeBounds = beforeBounds,
                                afterBounds = Rect(drawable.bounds),
                                isFullPage = isFullPage,
                                quality = request.quality,
                                replacesPlaceholder = replacesPlaceholder,
                            )
                        }
                    }
                    inFlight.remove(drawable)
                }
                if (accepted) {
                    synchronized(lifecycleLock) {
                        if (!released && request.generation == lifecycleGeneration) {
                            try {
                                if (installed) {
                                    drawable.invalidateSelf()
                                    val result = installedResult
                                    if (result != null && onImageResultChanged != null) {
                                        // Successful install: the host (PIXELS_ONLY / GEOMETRY_CHANGED)
                                        // owns reveal/precache wake so far-page PIXELS_ONLY can
                                        // suppress it. Do not dual-fire onDecodeFinished.
                                        onImageResultChanged.invoke(result)
                                        decodeFinishedNotified = true
                                    }
                                    // No result callback: finally falls through to onDecodeFinished once.
                                }
                            } finally {
                                if (!decodeFinishedNotified && !released) {
                                    decodeFinishedNotified = true
                                    onDecodeFinished?.invoke()
                                }
                            }
                        }
                    }
                    val layer = promotedLayer
                    val result = installedResult
                    if (layer != null && result != null) {
                        schedulePromotionCompletion(drawable, layer, result)
                    }
                }
            } catch (_: RuntimeException) {
                // Providers and drawable hosts belong to a chapter generation that may already be retired.
                // Treat their failure like a decode miss while still releasing pending/reveal gates below.
            } finally {
                if (accepted) {
                    synchronized(lifecycleLock) {
                        if (isCurrentRequestLocked(drawable, request)) inFlight.remove(drawable)
                    }
                    if (!decodeFinishedNotified) notifyDecodeFinished(request.generation)
                }
                if (!installed) bitmap?.recycle()
            }
        }
        if (!posted) {
            bitmap?.recycle()
            finishWithoutResult(drawable, request)
        }
    }

    private fun finishWithoutResult(drawable: AsyncDrawable, request: DecodeRequest) {
        val removed = synchronized(lifecycleLock) {
            if (isCurrentRequestLocked(drawable, request)) {
                inFlight.remove(drawable)
                true
            } else {
                false
            }
        }
        if (removed) notifyDecodeFinished(request.generation)
    }

    private fun schedulePromotionCompletion(
        drawable: AsyncDrawable,
        layer: EpubImagePixelDrawable,
        installedResult: EpubAsyncImageResult,
    ) {
        lateinit var completion: Runnable
        completion = Runnable {
            val shouldPublish = synchronized(lifecycleLock) {
                if (promotionCompletionByDrawable[drawable] !== completion) {
                    false
                } else {
                    promotionCompletionByDrawable.remove(drawable)
                    !released &&
                        installedResult.generation == lifecycleGeneration &&
                        drawable.isAttached &&
                        drawable.result === layer &&
                        installedQualityByDrawable[drawable] == EpubImageRenderQuality.DISPLAY
                }
            }
            if (!shouldPublish) return@Runnable
            layer.finishPromotion()
            drawable.invalidateSelf()
            try {
                onImageResultChanged?.invoke(
                    installedResult.copy(
                        beforeBounds = Rect(drawable.bounds),
                        afterBounds = Rect(drawable.bounds),
                        replacesPlaceholder = false,
                    ),
                )
            } catch (_: RuntimeException) {
                // The host can retire after the guarded check.
            }
        }
        val previous = synchronized(lifecycleLock) {
            if (
                released ||
                installedResult.generation != lifecycleGeneration ||
                drawable.result !== layer
            ) {
                return
            }
            promotionCompletionByDrawable.put(drawable, completion)
        }
        previous?.let(handler::removeCallbacks)
        if (!handler.postDelayed(completion, EPUB_DISPLAY_PROMOTION_FADE_MS)) {
            synchronized(lifecycleLock) {
                if (promotionCompletionByDrawable[drawable] === completion) {
                    promotionCompletionByDrawable.remove(drawable)
                }
            }
            layer.finishPromotion()
        }
    }

    private fun cancelAll(permanently: Boolean) {
        val (pending, completions, generation) = synchronized(lifecycleLock) {
            lifecycleGeneration++
            if (permanently) released = true
            if (permanently) {
                layoutStartByDrawable.clear()
                installedQualityByDrawable.clear()
                decodeWindowRanges = null
                decodeWindowRestrictsAdmission = false
            }
            val requests = inFlight.values.toList().also { inFlight.clear() }
            val fadeCompletions = promotionCompletionByDrawable.values.toList().also {
                promotionCompletionByDrawable.clear()
            }
            Triple(requests, fadeCompletions, lifecycleGeneration)
        }
        pending.forEach { it.future?.cancel(true) }
        completions.forEach(handler::removeCallbacks)
        if (!permanently && pending.isNotEmpty()) notifyDecodeFinished(generation)
    }

    private fun isCurrentRequestLocked(drawable: AsyncDrawable, request: DecodeRequest): Boolean =
        !released && request.generation == lifecycleGeneration && inFlight[drawable] === request

    private fun currentColumnWidthPx(): Int =
        try {
            columnWidthProvider().coerceAtLeast(1)
        } catch (_: RuntimeException) {
            columnWidthPx.coerceAtLeast(1)
        }

    private fun notifyDecodeFinished(generation: Long) {
        handler.post {
            synchronized(lifecycleLock) {
                if (!released && generation == lifecycleGeneration) {
                    onDecodeFinished?.invoke()
                }
            }
        }
    }

    private class DecodeRequest(
        val generation: Long,
        val quality: EpubImageRenderQuality,
        val isPromotion: Boolean,
        var future: Future<*>? = null,
    )

    private data class DecodeWindowSnapshot(
        val ranges: List<IntRange>,
        val restrictsAdmission: Boolean,
    )
}

/**
 * Fallback sizer used by Markwon's [AsyncDrawable] once it has a canvas width. Delegates to the same
 * [epubFlowImageTargetSize] the loader uses, so the on-screen size is identical on either path.
 */
internal class EpubFlowImageSizeResolver(
    private val columnWidthPx: Int,
    private val columnWidthProvider: () -> Int = { columnWidthPx },
    private val pageHeightProvider: () -> Int,
    private val inlineMaxHeightPx: Int,
    private val fullPageHrefs: Set<String>,
) : ImageSizeResolver() {

    fun isFullPage(destination: String): Boolean = destination in fullPageHrefs

    override fun resolveImageSize(drawable: AsyncDrawable): Rect =
        resolveImageSize(drawable, isFullPage(drawable.destination))

    fun resolveImageSize(drawable: AsyncDrawable, isFullPage: Boolean): Rect {
        val result = drawable.result ?: return Rect(0, 0, 1, 1)
        val requested = drawable.imageSize
        if (!isFullPage && requested == null) {
            result.bounds.takeUnless { it.isEmpty }?.let { return Rect(it) }
        }
        val sourceWidth = result.intrinsicWidth.takeIf { it > 0 }
            ?: result.bounds.width().takeIf { it > 0 }
            ?: 1
        val sourceHeight = result.intrinsicHeight.takeIf { it > 0 }
            ?: result.bounds.height().takeIf { it > 0 }
            ?: 1
        val currentColumnWidthPx = currentColumnWidthPx()
        val fallback = epubFlowImageTargetSize(
            intrinsicWidth = sourceWidth,
            intrinsicHeight = sourceHeight,
            columnWidthPx = currentColumnWidthPx,
            pageHeightPx = pageHeightProvider().coerceAtLeast(1),
            inlineMaxHeightPx = inlineMaxHeightPx,
            isFullPage = isFullPage,
        )
        if (isFullPage || requested == null) return fallback
        val ratio = sourceWidth.toFloat() / sourceHeight
        val width = requested.width?.resolveCssImageDimension(currentColumnWidthPx, drawable.lastKnowTextSize)
        val height = requested.height?.resolveCssImageDimension(pageHeightProvider(), drawable.lastKnowTextSize)
        val requestedWidth: Int
        val requestedHeight: Int
        when {
            width != null && height != null -> {
                requestedWidth = width
                requestedHeight = height
            }
            width != null -> {
                requestedWidth = width
                requestedHeight = (width / ratio).toInt().coerceAtLeast(1)
            }
            height != null -> {
                requestedHeight = height
                requestedWidth = (height * ratio).toInt().coerceAtLeast(1)
            }
            else -> return fallback
        }
        val maxWidth = currentColumnWidthPx
        val maxHeight = if (isFullPage) {
            pageHeightProvider().coerceAtLeast(1)
        } else {
            inlineMaxHeightPx.coerceAtLeast(1)
        }
        val scale = minOf(
            1f,
            maxWidth.toFloat() / requestedWidth,
            maxHeight.toFloat() / requestedHeight,
        )
        return Rect(
            0,
            0,
            (requestedWidth * scale).toInt().coerceAtLeast(1),
            (requestedHeight * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun currentColumnWidthPx(): Int =
        try {
            columnWidthProvider().coerceAtLeast(1)
        } catch (_: RuntimeException) {
            columnWidthPx.coerceAtLeast(1)
        }
}

private fun io.noties.markwon.image.ImageSize.Dimension.resolveCssImageDimension(
    percentageBasisPx: Int,
    textSizePx: Float,
): Int? =
    when (unit) {
        "%" -> (percentageBasisPx * value / 100f).toInt()
        "em" -> (textSizePx * value).toInt()
        "" -> value.toInt()
        else -> null
    }?.coerceAtLeast(1)
