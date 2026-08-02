package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
private const val EPUB_IMAGE_DECODE_MAX_FAILURES = 2
private const val EPUB_RETIRED_PIXEL_BUDGET_BYTES = 64L * 1024L * 1024L
private const val EPUB_RETIRED_PIXEL_MIN_AGE_MS = 320L

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

/** Keeps unknown-size inline images bounded while full-page art reserves the viewport. */
private fun epubUnknownImageTargetSize(
    columnWidthPx: Int,
    pageHeightPx: Int,
    inlineMaxHeightPx: Int,
    isFullPage: Boolean,
): Rect {
    val width = columnWidthPx.coerceAtLeast(1)
    val height = pageHeightPx.coerceAtLeast(1)
    if (isFullPage) return Rect(0, 0, width, height)
    val inlineSize = min(width, inlineMaxHeightPx.coerceAtLeast(1))
    return Rect(0, 0, inlineSize, inlineSize)
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
    val installsFirstPixels: Boolean = false,
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
        // Known-geometry placeholders retain one drawable identity, so first pixels and later
        // promotions only invalidate that layer. Rebind only when no retained owner existed.
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

    /** Drops decoded pixels while retaining the final layout geometry for a later window re-entry. */
    fun retirePixels(): List<Bitmap> {
        val retired = ArrayList<Bitmap>(3)
        fun retire(bitmap: Bitmap?) {
            if (
                bitmap != null &&
                bitmap !== EpubImagePixelDrawableHolder.bitmap &&
                retired.none { existing -> existing === bitmap }
            ) {
                retired += bitmap
            }
        }
        retire(fallbackPixels)
        retire(pixels)
        retire(previousPixels)
        fallbackPixels = null
        fallbackQuality = null
        pixels = EpubImagePixelDrawableHolder.bitmap
        previousPixels = null
        transitionStartedAtMs = 0L
        invalidateSelf()
        return retired
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
    private val decodeFailureCountByDrawable = WeakHashMap<AsyncDrawable, Int>()
    private val terminalFailureGenerationByDrawable = WeakHashMap<AsyncDrawable, Long>()
    private val promotionCompletionByDrawable = WeakHashMap<AsyncDrawable, Runnable>()
    private val retiredPixelBitmaps = ArrayList<RetiredPixelBitmap>()
    private var retiredPixelBytes = 0L
    private var retiredPixelBarrierGeneration = 0L
    private var retiredPixelBarrierSatisfied = false
    private val retiredPixelMaintenanceRunnable = object : Runnable {
        override fun run() {
            trimRetiredPixelBudget()
            val overBudget = synchronized(lifecycleLock) {
                retiredPixelBytes > EPUB_RETIRED_PIXEL_BUDGET_BYTES
            }
            if (overBudget && !released) {
                handler.postDelayed(this, EPUB_RETIRED_PIXEL_MIN_AGE_MS)
            }
        }
    }
    private val retiredPixelFlushRunnable = Runnable { flushRetiredPixels() }
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

    /**
     * Returns true only when every image occurrence at [layoutStarts] owns attached decoded pixels.
     * Rapid page turns use this exact-page contract instead of waiting for unrelated decode work.
     *
     * With [requireDisplayPromotion] (the default) the gate additionally requires DISPLAY quality
     * and a completed promotion crossfade, which is the settled-page contract. While a rapid motion
     * window owns the frames the view passes `false`, so retained decoded pixels of any installed
     * quality keep the queued burst moving while DISPLAY promotion completes in the quiet-idle
     * cleanup. An occurrence with no retained pixels yet (only the transparent placeholder) still
     * fails conservatively, so a queue waits instead of parking on a page that could disappear.
     */
    fun hasStablePixels(
        layoutStarts: Collection<Int>,
        requireDisplayPromotion: Boolean = true,
    ): Boolean =
        synchronized(lifecycleLock) {
            val starts = layoutStarts.filter { it >= 0 }.distinct()
            if (starts.isEmpty()) return true
            starts.all { start ->
                layoutStartByDrawable.entries.any { (drawable, registeredStart) ->
                    val retainedPixels =
                        (drawable.result as? EpubImagePixelSource)?.hasDecodedPixels == true
                    val request = inFlight[drawable]
                    val rapidPromotionMayRunAhead =
                        !requireDisplayPromotion && retainedPixels && request?.isPromotion == true
                    registeredStart == start &&
                        drawable.isAttached &&
                        (request == null || rapidPromotionMayRunAhead) &&
                        (
                            (
                                retainedPixels &&
                                    (
                                        !requireDisplayPromotion ||
                                            (
                                                installedQualityByDrawable[drawable] ==
                                                    EpubImageRenderQuality.DISPLAY &&
                                                    promotionCompletionByDrawable[drawable] == null
                                                )
                                        )
                                ) || terminalFailureGenerationByDrawable[drawable] == lifecycleGeneration
                            )
                }
            }
        }

    private fun markTerminalFailure(drawable: AsyncDrawable) {
        synchronized(lifecycleLock) {
            if (!released && drawable.isAttached) {
                terminalFailureGenerationByDrawable[drawable] = lifecycleGeneration
            }
        }
    }

    /**
     * Defers the destructive native-pixel release until the just-retired layer has crossed a
     * conservative HWUI safety window. Callers use this after a page/chapter lifecycle transition;
     * [flushRetiredPixels] remains the explicit force barrier for tests/teardown that already owns
     * the render lifecycle.
     */
    fun scheduleRetiredPixelFlush() {
        val hasRetired = synchronized(lifecycleLock) {
            if (retiredPixelBitmaps.isEmpty()) {
                false
            } else {
                retiredPixelBarrierSatisfied = true
                true
            }
        }
        if (!hasRetired) return
        handler.removeCallbacks(retiredPixelFlushRunnable)
        handler.postDelayed(retiredPixelFlushRunnable, EPUB_RETIRED_PIXEL_MIN_AGE_MS)
    }

    /**
     * Requests two attached-host frame traversals before starting the native-pixel safety age.
     * This gives HWUI a concrete opportunity to replace the display list that referenced the
     * retired drawable; the ordinary delayed flush remains the detached-host fallback.
     */
    fun scheduleRetiredPixelFlushAfterRenderBarrier(host: View) {
        val barrierGeneration = synchronized(lifecycleLock) {
            if (retiredPixelBitmaps.isEmpty()) return
            retiredPixelBarrierSatisfied = false
            ++retiredPixelBarrierGeneration
        }
        handler.removeCallbacks(retiredPixelFlushRunnable)
        if (!host.isAttachedToWindow) {
            scheduleRetiredPixelFlush()
            return
        }
        var remainingFrames = 2
        lateinit var frameBarrier: Runnable
        frameBarrier = Runnable {
            val isCurrent = synchronized(lifecycleLock) {
                retiredPixelBarrierGeneration == barrierGeneration
            }
            if (!isCurrent) return@Runnable
            remainingFrames -= 1
            host.postInvalidateOnAnimation()
            if (remainingFrames > 0 && host.isAttachedToWindow) {
                host.postOnAnimation(frameBarrier)
            } else {
                scheduleRetiredPixelFlush()
            }
        }
        host.postInvalidateOnAnimation()
        host.postOnAnimation(frameBarrier)
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
        val update = synchronized(lifecycleLock) {
            if (released) return 0
            decodeWindowRanges = ranges
            decodeWindowRestrictsAdmission = true
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
            val cancelledCompletions = mutableListOf<Runnable>()
            val retiredLayers = layoutStartByDrawable.entries.mapNotNull { (drawable, layoutStart) ->
                if (ranges.any { range -> layoutStart in range }) return@mapNotNull null
                val layer = drawable.result as? EpubImagePixelDrawable ?: return@mapNotNull null
                installedQualityByDrawable.remove(drawable)
                promotionCompletionByDrawable.remove(drawable)?.let(cancelledCompletions::add)
                layer.takeIf { it.hasDecodedPixels }
            }
            val dormant = layoutStartByDrawable.entries.mapNotNull { (drawable, layoutStart) ->
                drawable.takeIf {
                        drawable.isAttached &&
                        ranges.any { range -> layoutStart in range } &&
                        installedQualityByDrawable[drawable] == null &&
                        terminalFailureGenerationByDrawable[drawable] != lifecycleGeneration &&
                        inFlight[drawable] == null
                }
            }
            DecodeWindowUpdate(
                cancelled = staleRequests,
                candidates = dormant,
                retiredLayers = retiredLayers,
                cancelledCompletions = cancelledCompletions,
                generation = lifecycleGeneration,
            )
        }
        update.cancelled.forEach { request -> request.future?.cancel(true) }
        update.cancelledCompletions.forEach(handler::removeCallbacks)
        retirePixelLayers(update.retiredLayers)
        trimRetiredPixelBudget()
        if (update.cancelled.isNotEmpty()) notifyDecodeFinished(update.generation)
        return update.candidates.count { requestLoad(it, forcedQuality = null) }
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
        retryAttempt: Int = 0,
    ): Boolean {
        if (synchronized(lifecycleLock) { released }) return false
        if (synchronized(lifecycleLock) { terminalFailureGenerationByDrawable[drawable] == lifecycleGeneration }) {
            return false
        }
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
        } ?: run {
            markTerminalFailure(drawable)
            return false
        }
        val href = drawable.destination
        val pageHeightPx = try {
            pageHeightProvider().coerceAtLeast(1)
        } catch (_: RuntimeException) {
            markTerminalFailure(drawable)
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
            // When intrinsic bounds are unavailable, the placeholder still reserves a full-page
            // fallback. Re-fit that fallback against the measured viewport instead of reusing a
            // pre-measure page height that can clip or push the illustration across a page break.
            isFullPage -> epubUnknownImageTargetSize(
                columnWidthPx = currentColumnWidthPx,
                pageHeightPx = pageHeightPx,
                inlineMaxHeightPx = inlineMaxHeightPx,
                isFullPage = true,
            )
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
            !drawable.bounds.isEmpty -> Rect(drawable.bounds)
            else -> epubUnknownImageTargetSize(
                columnWidthPx = currentColumnWidthPx,
                pageHeightPx = pageHeightPx,
                inlineMaxHeightPx = inlineMaxHeightPx,
                isFullPage = false,
            )
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
                retryAttempt = retryAttempt,
            )
            inFlight[drawable] = new
            new to old
        }
        superseded?.future?.cancel(true)
        // Keep the critical lane for visible DISPLAY work. RAPID runway images are deliberately
        // speculative and must not serialize behind a full-resolution landing decode; they can
        // fill the bulk lane while the current/adjacent display contract stays responsive.
        val decodeExecutor = priorityExecutor?.takeIf {
            quality == EpubImageRenderQuality.DISPLAY &&
                layoutStart >= 0 &&
                decodeWindow.ranges.any { range -> layoutStart in range }
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
    fun releaseAll(renderBarrierHost: View? = null) {
        cancelAll(permanently = true)
        renderBarrierHost?.let(::scheduleRetiredPixelFlushAfterRenderBarrier)
    }

    // Reserve the final image box before pixel decode. Markwon treats a non-empty-bounds placeholder
    // as a result for ReplacementSpan measurement, but still calls load(...) after attach because the
    // current result is the placeholder. This keeps first pagination close to the final image geometry.
    override fun placeholder(drawable: AsyncDrawable): Drawable? {
        if (synchronized(lifecycleLock) { released }) return null
        val bounds = try {
            imageBoundsProvider(drawable.destination)
        } catch (_: RuntimeException) {
            return null
        }
        val pageHeightPx = try {
            pageHeightProvider().coerceAtLeast(1)
        } catch (_: RuntimeException) {
            return null
        }
        val currentColumnWidthPx = currentColumnWidthPx()
        val isFullPage = drawable.isFullPageOccurrence(fullPageHrefs)
        val target = if (bounds == null) {
            // Keep a stable layer when intrinsic dimensions are unavailable. requestLoad uses the
            // same fallback, so first pixels update this layer in place instead of rebinding the
            // chapter TextView during a page turn.
            epubUnknownImageTargetSize(
                columnWidthPx = currentColumnWidthPx,
                pageHeightPx = pageHeightPx,
                inlineMaxHeightPx = inlineMaxHeightPx,
                isFullPage = isFullPage,
            )
        } else {
            drawable.constrainTarget(
                epubFlowImageTargetSize(
                    intrinsicWidth = bounds.width,
                    intrinsicHeight = bounds.height,
                    columnWidthPx = currentColumnWidthPx,
                    pageHeightPx = pageHeightPx,
                    inlineMaxHeightPx = inlineMaxHeightPx,
                    isFullPage = isFullPage,
                ),
            )
        }
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
            var retryPromotion = false
            var retryPromotionStarted = false
            try {
                synchronized(lifecycleLock) {
                    if (!isCurrentRequestLocked(drawable, request)) return@synchronized
                    accepted = true
                    if (bitmap != null && drawable.isAttached) {
                        decodeFailureCountByDrawable.remove(drawable)
                        val beforeBounds = Rect(drawable.bounds)
                        val target = reservedBounds ?: Rect(drawable.bounds)
                        val retainedLayer = drawable.result as? EpubImagePixelDrawable
                        val replacesPlaceholder = retainedLayer == null
                        val result = retainedLayer ?: EpubImagePixelDrawable(target)
                        val installsFirstPixels = !result.hasDecodedPixels
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
                                installsFirstPixels = installsFirstPixels,
                            )
                        }
                    } else if (bitmap == null && drawable.isAttached) {
                        val hasRetainedPixels =
                            (drawable.result as? EpubImagePixelSource)?.hasDecodedPixels == true
                        val failureCount = (decodeFailureCountByDrawable[drawable] ?: 0) + 1
                        decodeFailureCountByDrawable[drawable] = failureCount
                        retryPromotion =
                            hasRetainedPixels &&
                                request.isPromotion &&
                                request.retryAttempt == 0 &&
                                failureCount < EPUB_IMAGE_DECODE_MAX_FAILURES
                        if (
                            (!hasRetainedPixels && request.quality == EpubImageRenderQuality.DISPLAY) ||
                            failureCount >= EPUB_IMAGE_DECODE_MAX_FAILURES
                        ) {
                            terminalFailureGenerationByDrawable[drawable] = request.generation
                        }
                    }
                    inFlight.remove(drawable)
                }
                if (retryPromotion) {
                    retryPromotionStarted = requestLoad(
                        drawable = drawable,
                        forcedQuality = request.quality,
                        retryAttempt = request.retryAttempt + 1,
                    )
                }
                if (retryPromotionStarted) decodeFinishedNotified = true
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
                if (
                    drawable.isAttached &&
                    (drawable.result as? EpubImagePixelSource)?.hasDecodedPixels != true &&
                        request.quality == EpubImageRenderQuality.DISPLAY
                ) {
                    terminalFailureGenerationByDrawable[drawable] = request.generation
                }
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
        val cancellation = synchronized(lifecycleLock) {
            lifecycleGeneration++
            if (permanently) released = true
            val retiredLayers = if (permanently) {
                layoutStartByDrawable.keys.mapNotNull { drawable ->
                    (drawable.result as? EpubImagePixelDrawable)
                        ?.takeIf { it.hasDecodedPixels }
                }
            } else {
                emptyList()
            }
            if (permanently) {
                layoutStartByDrawable.clear()
                installedQualityByDrawable.clear()
                decodeFailureCountByDrawable.clear()
                terminalFailureGenerationByDrawable.clear()
                decodeWindowRanges = null
                decodeWindowRestrictsAdmission = false
            }
            val requests = inFlight.values.toList().also { inFlight.clear() }
            if (!permanently) {
                decodeFailureCountByDrawable.clear()
                terminalFailureGenerationByDrawable.clear()
            }
            val fadeCompletions = promotionCompletionByDrawable.values.toList().also {
                promotionCompletionByDrawable.clear()
            }
            LoaderCancellation(
                pending = requests,
                completions = fadeCompletions,
                retiredLayers = retiredLayers,
                generation = lifecycleGeneration,
            )
        }
        cancellation.pending.forEach { it.future?.cancel(true) }
        cancellation.completions.forEach(handler::removeCallbacks)
        retirePixelLayers(cancellation.retiredLayers)
        if (permanently) {
            handler.removeCallbacks(retiredPixelMaintenanceRunnable)
            handler.removeCallbacks(retiredPixelFlushRunnable)
            val hasRetired = synchronized(lifecycleLock) { retiredPixelBitmaps.isNotEmpty() }
            if (hasRetired) handler.postDelayed(retiredPixelFlushRunnable, EPUB_RETIRED_PIXEL_MIN_AGE_MS)
        }
        if (!permanently && cancellation.pending.isNotEmpty()) {
            notifyDecodeFinished(cancellation.generation)
        }
    }

    private fun retirePixelLayers(layers: Collection<EpubImagePixelDrawable>) {
        if (layers.isEmpty()) return
        val retired = ArrayList<Bitmap>()
        layers.forEach { layer ->
            layer.retirePixels().forEach { bitmap ->
                if (retired.none { existing -> existing === bitmap }) retired += bitmap
            }
        }
        if (retired.isEmpty()) return
        val retiredAtMs = SystemClock.uptimeMillis()
        synchronized(lifecycleLock) {
            var addedPixels = false
            retired.forEach { bitmap ->
                if (retiredPixelBitmaps.none { existing -> existing.bitmap === bitmap }) {
                    val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(0L)
                    retiredPixelBitmaps += RetiredPixelBitmap(bitmap, retiredAtMs, bytes)
                    retiredPixelBytes += bytes
                    addedPixels = true
                }
            }
            if (addedPixels) {
                retiredPixelBarrierSatisfied = false
                retiredPixelBarrierGeneration += 1L
            }
        }
        handler.removeCallbacks(retiredPixelFlushRunnable)
        handler.removeCallbacks(retiredPixelMaintenanceRunnable)
        handler.postDelayed(retiredPixelMaintenanceRunnable, EPUB_RETIRED_PIXEL_MIN_AGE_MS)
    }

    private fun trimRetiredPixelBudget(): Int {
        val now = SystemClock.uptimeMillis()
        val retired = synchronized(lifecycleLock) {
            if (!retiredPixelBarrierSatisfied) return@synchronized emptyList()
            buildList {
                while (retiredPixelBytes > EPUB_RETIRED_PIXEL_BUDGET_BYTES) {
                    val oldest = retiredPixelBitmaps.firstOrNull() ?: break
                    if (now - oldest.retiredAtMs < EPUB_RETIRED_PIXEL_MIN_AGE_MS) break
                    retiredPixelBitmaps.removeAt(0)
                    retiredPixelBytes = (retiredPixelBytes - oldest.bytes).coerceAtLeast(0L)
                    add(oldest.bitmap)
                }
            }
        }
        retired.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        return retired.size
    }

    /**
     * Recycles pixel buffers only after the host has crossed an explicit HWUI lifecycle barrier.
     * Removing pixels from the retained drawable is immediate; releasing their native storage is
     * deferred so a render-thread display list recorded by the just-finished page turn cannot race
     * a fixed-delay recycle.
     */
    fun flushRetiredPixels(): Int {
        handler.removeCallbacks(retiredPixelFlushRunnable)
        val retired = synchronized(lifecycleLock) {
            retiredPixelBarrierGeneration += 1L
            retiredPixelBarrierSatisfied = false
            retiredPixelBitmaps.map(RetiredPixelBitmap::bitmap).also {
                retiredPixelBitmaps.clear()
                retiredPixelBytes = 0L
            }
        }
        retired.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        return retired.size
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
        val retryAttempt: Int,
        var future: Future<*>? = null,
    )

    private data class DecodeWindowUpdate(
        val cancelled: List<DecodeRequest>,
        val candidates: List<AsyncDrawable>,
        val retiredLayers: List<EpubImagePixelDrawable>,
        val cancelledCompletions: List<Runnable>,
        val generation: Long,
    )

    private data class LoaderCancellation(
        val pending: List<DecodeRequest>,
        val completions: List<Runnable>,
        val retiredLayers: List<EpubImagePixelDrawable>,
        val generation: Long,
    )

    private data class RetiredPixelBitmap(
        val bitmap: Bitmap,
        val retiredAtMs: Long,
        val bytes: Long,
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
