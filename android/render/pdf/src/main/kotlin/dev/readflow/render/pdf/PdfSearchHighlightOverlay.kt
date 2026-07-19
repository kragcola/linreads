package dev.readflow.render.pdf

import android.content.Context
import android.graphics.Canvas
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

    private var selecting = false
    private var selectionStartPagePt: Pair<Float, Float>? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Accept the scale stream so isInProgress becomes true; release parent
                // intercept so RecyclerView / outer pinch-zoom can continue. Returning
                // false left isInProgress false and could strand selection intercept.
                selecting = false
                selectionStartPagePt = null
                parent?.requestDisallowInterceptTouchEvent(false)
                selectionListener?.onSelectionCancelled(tag as? Int ?: -1)
                return true
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

    /** Test / engine helper: expose whether a selection drag is in progress. */
    fun isSelectionGestureActive(): Boolean = selecting

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        // Idle scroll / pinch: never intercept. Selection drag only after long-press.
        return PdfSelectionGestureLifecycle.hostInterceptsForSelection(
            selecting = selecting,
            scaleInProgress = scaleDetector.isInProgress,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            selecting = false
            selectionStartPagePt = null
            parent?.requestDisallowInterceptTouchEvent(false)
            // Do not consume — parent RecyclerView / zoom pipeline owns multi-touch.
            return false
        }
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
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
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selecting) {
                    val start = selectionStartPagePt
                    val stop = viewToPagePoint(event.x, event.y)
                    selecting = false
                    selectionStartPagePt = null
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
                if (dx * dx + dy * dy < touchSlop * touchSlop) {
                    performClick()
                }
            }
        }
        // When not selecting, return false so parent RecyclerView receives scroll.
        return PdfSelectionGestureLifecycle.hostInterceptsForSelection(
            selecting = selecting,
            scaleInProgress = false,
        ) || super.onTouchEvent(event)
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
