package dev.readflow.render.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import dev.readflow.render.api.READER_SEARCH_HIGHLIGHT_COLOR

/** Semi-transparent selection paint — distinct from search blue and annotation yellow. */
internal const val PDF_SELECTION_HIGHLIGHT_COLOR: Int = 0x663B82F6

/**
 * Page host that owns the PDF bitmap [ImageView] and paints three independent overlay layers:
 * - persistent annotation rectangles
 * - transient search rectangles
 * - live selection rectangles
 *
 * Layers are never mixed: search clear does not wipe annotations; selection clear does not wipe either.
 *
 * Same-page long-press + drag selection is handled here when a [selectionListener] is set;
 * scroll / pinch continue to work when no selection gesture is active (parent can intercept).
 */
internal class PdfSearchPageHost(
    context: Context,
) : FrameLayout(context) {

    val imageView: ImageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val annotationOverlay = PdfHighlightLayerOverlay(context)
    private val searchOverlay = PdfHighlightLayerOverlay(context).apply {
        defaultColor = READER_SEARCH_HIGHLIGHT_COLOR
    }
    private val selectionOverlay = PdfHighlightLayerOverlay(context).apply {
        defaultColor = PDF_SELECTION_HIGHLIGHT_COLOR
    }

    /** Optional: engine-driven same-page framework selection. */
    var selectionListener: PdfPageSelectionListener? = null

    /** Optional: request an adjacent fixed page after a zoom-pan crosses a horizontal edge. */
    var boundaryPageTurnListener: ((pageDelta: Int) -> Unit)? = null

    private var selecting = false
    private var selectionStartPagePt: Pair<Float, Float>? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var panning = false
    private var boundaryDragX = 0f
    private var appliedZoomScale = 1f
    private var pendingZoomFocus: PdfPanOffset? = null
    private val panState = PdfPanState()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // ReaderTapContainer observes the same stream and owns the zoom value. This host
                // keeps the focal point and blocks ViewPager interception while the pinch is live.
                selecting = false
                panning = false
                boundaryDragX = 0f
                selectionStartPagePt = null
                pendingZoomFocus = PdfPanOffset(detector.focusX, detector.focusY)
                parent?.requestDisallowInterceptTouchEvent(true)
                selectionListener?.onSelectionCancelled(tag as? Int ?: -1)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pendingZoomFocus = PdfPanOffset(detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pendingZoomFocus = null
                parent?.requestDisallowInterceptTouchEvent(hasPanRange())
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                if (selectionListener == null) return
                val pagePt = viewToPagePoint(e.x, e.y) ?: return
                selecting = true
                selectionStartPagePt = pagePt
                setTag(dev.readflow.render.api.R.id.selection_aware_interactive_tap_consumed, true)
                parent?.requestDisallowInterceptTouchEvent(true)
                selectionListener?.onSelectionGesture(
                    pageIndex = tag as? Int ?: return,
                    startPagePoint = pagePt,
                    stopPagePoint = pagePt,
                    finished = false,
                )
            }
        },
    )

    init {
        addView(imageView)
        addView(annotationOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(searchOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(selectionOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Overlay children do not take clicks; host handles selection gestures.
        annotationOverlay.isClickable = false
        searchOverlay.isClickable = false
        selectionOverlay.isClickable = false
    }

    fun setSearchRects(bitmapRects: List<PdfRect>?) {
        searchOverlay.setRects(
            bitmapRects.orEmpty().map { PdfColoredRect(it, READER_SEARCH_HIGHLIGHT_COLOR) },
        )
        searchOverlay.invalidate()
    }

    /** Backward-compatible alias used by existing search call sites. */
    fun setHighlightRects(bitmapRects: List<PdfRect>?) = setSearchRects(bitmapRects)

    fun setAnnotationRects(colored: List<PdfColoredRect>?) {
        annotationOverlay.setRects(colored.orEmpty())
        annotationOverlay.invalidate()
    }

    fun setSelectionRects(bitmapRects: List<PdfRect>?) {
        selectionOverlay.setRects(
            bitmapRects.orEmpty().map { PdfColoredRect(it, PDF_SELECTION_HIGHLIGHT_COLOR) },
        )
        selectionOverlay.invalidate()
    }

    fun clearHighlight() = setSearchRects(null)

    fun clearSelectionPaint() = setSelectionRects(null)

    fun clearAnnotationPaint() = setAnnotationRects(null)

    fun clearAllPaint() {
        clearHighlight()
        clearSelectionPaint()
        clearAnnotationPaint()
    }

    fun rebindHighlightPaint() {
        annotationOverlay.syncFromImage(imageView)
        searchOverlay.syncFromImage(imageView)
        selectionOverlay.syncFromImage(imageView)
        annotationOverlay.invalidate()
        searchOverlay.invalidate()
        selectionOverlay.invalidate()
    }

    fun setZoomScale(scale: Float) {
        val nextScale = scale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val previousScale = appliedZoomScale
        appliedZoomScale = nextScale
        if (nextScale <= 1f) {
            panState.reset()
        } else {
            val geometry = fittedPageGeometry()
            if (geometry != null) {
                val bounds = geometry.panBounds(nextScale)
                val focus = pendingZoomFocus
                if (focus != null && previousScale != nextScale) {
                    panState.set(
                        pdfPanOffsetKeepingFocus(
                            offset = panState.offset,
                            previousZoomScale = previousScale,
                            zoomScale = nextScale,
                            focusX = focus.x,
                            focusY = focus.y,
                            contentCenterX = geometry.contentCenterX,
                            contentCenterY = geometry.contentCenterY,
                        ),
                        bounds,
                    )
                } else {
                    panState.reclamp(bounds)
                }
            }
        }
        applyPdfTransform()
    }

    /** Recompute fit + pan after layout changes such as device rotation. */
    fun reapplyPdfTransform() {
        applyPdfTransform()
    }

    /** Test / engine helper: expose whether a selection drag is in progress. */
    fun isSelectionGestureActive(): Boolean = selecting

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Children are paint-only. The host receives their unhandled stream; it only takes over
        // interception after long-press selection has started.
        return PdfSelectionGestureLifecycle.hostInterceptsForSelection(
            selecting = selecting,
            scaleInProgress = scaleDetector.isInProgress,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount > 1) {
            boundaryDragX = 0f
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            selecting = false
            panning = false
            selectionStartPagePt = null
            return true
        }
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                panning = false
                boundaryDragX = 0f
                if (hasPanRange()) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    val start = selectionStartPagePt ?: return true
                    val stop = viewToPagePoint(event.x, event.y) ?: return true
                    selectionListener?.onSelectionGesture(
                        pageIndex = tag as? Int ?: return true,
                        startPagePoint = start,
                        stopPagePoint = stop,
                        finished = false,
                    )
                    return true
                }
                val deltaX = event.x - lastTouchX
                val deltaY = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                if (!panning) {
                    val totalX = event.x - downX
                    val totalY = event.y - downY
                    if (totalX * totalX + totalY * totalY > touchSlop * touchSlop) {
                        panning = canConsumePan(deltaX, deltaY)
                        if (!panning) {
                            recordBoundaryDrag(deltaX, deltaY, unconsumedX = deltaX)
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return true
                        }
                    }
                }
                if (panning) {
                    if (deltaX == 0f && deltaY == 0f) return true
                    if (!canConsumePan(deltaX, deltaY)) {
                        recordBoundaryDrag(deltaX, deltaY, unconsumedX = deltaX)
                        parent?.requestDisallowInterceptTouchEvent(false)
                        panning = false
                        return true
                    }
                    // ViewGroup cannot replay this MOVE after intercept is re-enabled. Preserve only
                    // the clamped horizontal remainder so ACTION_UP can request the adjacent page.
                    val previousPanX = panState.offset.x
                    panBy(deltaX, deltaY)
                    recordBoundaryDrag(
                        deltaX = deltaX,
                        deltaY = deltaY,
                        unconsumedX = deltaX - (panState.offset.x - previousPanX),
                    )
                    if (!canConsumePan(deltaX, deltaY)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> resetTouchAnchorAfterPointerUp(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selecting) {
                    val start = selectionStartPagePt
                    val stop = viewToPagePoint(event.x, event.y)
                    selecting = false
                    selectionStartPagePt = null
                    boundaryDragX = 0f
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP && start != null && stop != null) {
                        selectionListener?.onSelectionGesture(
                            pageIndex = tag as? Int ?: return true,
                            startPagePoint = start,
                            stopPagePoint = stop,
                            finished = true,
                        )
                    } else {
                        selectionListener?.onSelectionCancelled(tag as? Int ?: -1)
                    }
                    return true
                }
                // Small movement without selection: allow click/scroll propagation
                val dx = event.x - downX
                val dy = event.y - downY
                val pageTurnDelta = if (event.actionMasked == MotionEvent.ACTION_UP) {
                    takeBoundaryPageTurnDelta()
                } else {
                    boundaryDragX = 0f
                    0
                }
                if (!panning && dx * dx + dy * dy < touchSlop * touchSlop) {
                    performClick()
                }
                panning = false
                pendingZoomFocus = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (pageTurnDelta != 0) {
                    boundaryPageTurnListener?.invoke(pageTurnDelta)
                }
            }
        }
        // Consume DOWN so long-press and a future zoom-pan keep one coherent stream. ViewPager can
        // still intercept fit-scale swipes because the host does not disallow them.
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /**
     * Map view coordinates to PDF page-point space using the current fitted/matrix drawable bounds.
     * Returns null when the drawable is not laid out.
     */
    fun viewToPagePoint(viewX: Float, viewY: Float): Pair<Float, Float>? {
        val drawable = imageView.drawable ?: return null
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        if (drawableWidth <= 0 || drawableHeight <= 0) return null

        val bitmapX: Float
        val bitmapY: Float
        val matrix = imageView.imageMatrix
        if (imageView.scaleType == ImageView.ScaleType.MATRIX && drawable is BitmapDrawable) {
            val inverse = android.graphics.Matrix()
            if (!matrix.invert(inverse)) return null
            val pts = floatArrayOf(viewX, viewY)
            inverse.mapPoints(pts)
            bitmapX = pts[0]
            bitmapY = pts[1]
        } else {
            val contentLeft = imageView.paddingLeft.toFloat()
            val contentTop = imageView.paddingTop.toFloat()
            val contentWidth = (imageView.width - imageView.paddingLeft - imageView.paddingRight).toFloat()
            val contentHeight = (imageView.height - imageView.paddingTop - imageView.paddingBottom).toFloat()
            if (contentWidth <= 0f || contentHeight <= 0f) return null
            val scale = minOf(contentWidth / drawableWidth, contentHeight / drawableHeight)
            val drawnW = drawableWidth * scale
            val drawnH = drawableHeight * scale
            val offsetX = contentLeft + (contentWidth - drawnW) / 2f
            val offsetY = contentTop + (contentHeight - drawnH) / 2f
            bitmapX = (viewX - offsetX) / scale
            bitmapY = (viewY - offsetY) / scale
        }

        // Page-point mapping is deferred to the engine (needs pageWidthPt/pageHeightPt + bitmap size).
        // Here we return bitmap-local coordinates; the engine converts to page points.
        return bitmapX to bitmapY
    }

    private fun applyPdfTransform() {
        if (appliedZoomScale <= 1f) {
            panState.reset()
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView.imageMatrix = Matrix()
            rebindHighlightPaint()
            return
        }
        val geometry = fittedPageGeometry() ?: return
        val pan = panState.reclamp(geometry.panBounds(appliedZoomScale))
        val drawScale = geometry.fitScale * appliedZoomScale
        val left = geometry.contentLeft +
            (geometry.contentWidth - geometry.fittedPageWidth * appliedZoomScale) / 2f + pan.x
        val top = geometry.contentTop +
            (geometry.contentHeight - geometry.fittedPageHeight * appliedZoomScale) / 2f + pan.y
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    drawScale, 0f, left,
                    0f, drawScale, top,
                    0f, 0f, 1f,
                ),
            )
        }
        // Paint and hit testing both consume imageView.imageMatrix, so pan cannot drift from text.
        rebindHighlightPaint()
    }

    private fun panBy(deltaX: Float, deltaY: Float) {
        val geometry = fittedPageGeometry() ?: return
        panState.panBy(deltaX, deltaY, geometry.panBounds(appliedZoomScale))
        applyPdfTransform()
    }

    private fun hasPanRange(): Boolean {
        val bounds = currentPanBounds() ?: return false
        return bounds.maxX > 0f || bounds.maxY > 0f
    }

    private fun canConsumePan(deltaX: Float, deltaY: Float): Boolean {
        val bounds = currentPanBounds() ?: return false
        return if (kotlin.math.abs(deltaX) >= kotlin.math.abs(deltaY)) {
            bounds.canPanHorizontally(panState.offset, deltaX.direction())
        } else {
            bounds.canPanVertically(panState.offset, deltaY.direction())
        }
    }

    private fun recordBoundaryDrag(deltaX: Float, deltaY: Float, unconsumedX: Float) {
        if (appliedZoomScale <= 1f || !hasPanRange()) return
        if (kotlin.math.abs(deltaX) < kotlin.math.abs(deltaY)) return
        if (boundaryDragX != 0f && boundaryDragX * deltaX < 0f) {
            boundaryDragX = 0f
        }
        if (!unconsumedX.isFinite() || unconsumedX == 0f) return
        if (boundaryDragX != 0f && boundaryDragX * unconsumedX < 0f) {
            boundaryDragX = 0f
        }
        boundaryDragX += unconsumedX
    }

    private fun takeBoundaryPageTurnDelta(): Int {
        val dragX = boundaryDragX
        boundaryDragX = 0f
        if (!dragX.isFinite() || kotlin.math.abs(dragX) < touchSlop.toFloat()) return 0
        return if (dragX > 0f) -1 else 1
    }

    private fun currentPanBounds(): PdfPanBounds? =
        fittedPageGeometry()?.panBounds(appliedZoomScale)

    private fun fittedPageGeometry(): FittedPageGeometry? {
        val drawable = imageView.drawable ?: return null
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        val contentWidth = (imageView.width - imageView.paddingLeft - imageView.paddingRight).toFloat()
        val contentHeight = (imageView.height - imageView.paddingTop - imageView.paddingBottom).toFloat()
        if (drawableWidth <= 0 || drawableHeight <= 0 || contentWidth <= 0f || contentHeight <= 0f) {
            return null
        }
        val fitScale = minOf(contentWidth / drawableWidth, contentHeight / drawableHeight)
        return FittedPageGeometry(
            contentLeft = imageView.paddingLeft.toFloat(),
            contentTop = imageView.paddingTop.toFloat(),
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            fittedPageWidth = drawableWidth * fitScale,
            fittedPageHeight = drawableHeight * fitScale,
            fitScale = fitScale,
        )
    }

    private fun resetTouchAnchorAfterPointerUp(event: MotionEvent) {
        if (event.pointerCount <= 1) return
        val remainingIndex = if (event.actionIndex == 0) 1 else 0
        downX = event.getX(remainingIndex)
        downY = event.getY(remainingIndex)
        lastTouchX = downX
        lastTouchY = downY
        panning = false
    }

    private data class FittedPageGeometry(
        val contentLeft: Float,
        val contentTop: Float,
        val contentWidth: Float,
        val contentHeight: Float,
        val fittedPageWidth: Float,
        val fittedPageHeight: Float,
        val fitScale: Float,
    ) {
        val contentCenterX: Float get() = contentLeft + contentWidth / 2f
        val contentCenterY: Float get() = contentTop + contentHeight / 2f

        fun panBounds(zoomScale: Float): PdfPanBounds = pdfPanBounds(
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            fittedPageWidth = fittedPageWidth,
            fittedPageHeight = fittedPageHeight,
            zoomScale = zoomScale,
        )
    }
}

private fun Float.direction(): Int = when {
    this > 0f -> 1
    this < 0f -> -1
    else -> 0
}

/**
 * Callback for same-page long-press + drag selection. Coordinates are **bitmap pixel** space
 * from [PdfSearchPageHost.viewToPagePoint]; the engine converts to PDF page points before
 * calling framework [selectContent].
 */
internal interface PdfPageSelectionListener {
    fun onSelectionGesture(
        pageIndex: Int,
        startPagePoint: Pair<Float, Float>,
        stopPagePoint: Pair<Float, Float>,
        finished: Boolean,
    )

    fun onSelectionCancelled(pageIndex: Int)
}

/**
 * Transparent overlay that draws semi-transparent colored rectangles over the PDF page bitmap.
 * Rectangles are stored in bitmap pixel space and mapped on draw using the image drawable bounds.
 */
internal class PdfHighlightLayerOverlay(context: Context) : View(context) {

    var defaultColor: Int = READER_SEARCH_HIGHLIGHT_COLOR

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var coloredRects: List<PdfColoredRect> = emptyList()
    private var viewRects: List<Pair<RectF, Int>> = emptyList()

    fun setRects(rects: List<PdfColoredRect>) {
        coloredRects = rects.toList()
        viewRects = emptyList()
    }

    fun setBitmapRects(rects: List<PdfRect>, color: Int = defaultColor) {
        setRects(rects.map { PdfColoredRect(it, color) })
    }

    fun syncFromImage(imageView: ImageView) {
        if (coloredRects.isEmpty()) {
            viewRects = emptyList()
            return
        }
        val drawable = imageView.drawable
        val drawableWidth = drawable?.intrinsicWidth ?: 0
        val drawableHeight = drawable?.intrinsicHeight ?: 0
        val contentLeft = imageView.paddingLeft.toFloat()
        val contentTop = imageView.paddingTop.toFloat()
        val contentWidth = (imageView.width - imageView.paddingLeft - imageView.paddingRight).toFloat()
        val contentHeight = (imageView.height - imageView.paddingTop - imageView.paddingBottom).toFloat()
        val matrix = imageView.imageMatrix
        if (imageView.scaleType == ImageView.ScaleType.MATRIX && drawable is BitmapDrawable) {
            viewRects = coloredRects.map { colored ->
                val src = colored.rect
                val pts = floatArrayOf(src.left, src.top, src.right, src.bottom)
                matrix.mapPoints(pts)
                RectF(
                    minOf(pts[0], pts[2]),
                    minOf(pts[1], pts[3]),
                    maxOf(pts[0], pts[2]),
                    maxOf(pts[1], pts[3]),
                ) to colored.color
            }
        } else {
            val mapped = mapBitmapRectsToView(
                bitmapRects = coloredRects.map { it.rect },
                drawableWidth = drawableWidth,
                drawableHeight = drawableHeight,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentWidth = contentWidth,
                contentHeight = contentHeight,
                zoomScale = 1f,
            )
            viewRects = mapped.zip(coloredRects) { rect, colored ->
                rect.toRectF() to colored.color
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewRects.isEmpty() && coloredRects.isNotEmpty()) {
            (parent as? PdfSearchPageHost)?.let { syncFromImage(it.imageView) }
        }
        for ((rect, color) in viewRects) {
            paint.color = color
            canvas.drawRect(rect, paint)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        (parent as? PdfSearchPageHost)?.let { syncFromImage(it.imageView) }
    }
}

/** Backward-compatible name used by older call sites / tests. */
internal typealias PdfSearchHighlightOverlay = PdfHighlightLayerOverlay
