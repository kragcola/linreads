package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lightweight PAPER page turn derived from CoolReader's mature `PAGE_ANIMATION_PAPER` renderer.
 *
 * The visible page body stays flat and 1:1. Only the moving edge, capped at 30% of the viewport,
 * is split into narrow source strips and compressed onto a quarter-sine curve. This creates a soft
 * paper-flex cue without a 3D scene, full-page perspective transform, mesh, Path, or scratch Bitmap.
 * All lookup tables, Paints, and Rects are prepared before the first frame; changing [progress]
 * only updates primitive coordinates and invalidates the drawable.
 *
 * Forward turns compress the outgoing page toward the left and expose the target underneath.
 * Backward turns keep the outgoing page flat on the right while the target page grows in from the
 * left with the same local edge bend, matching CoolReader's single-page PAPER composition.
 */
internal class PageCurlDrawable(
    frontBitmap: Bitmap,
    revealedBitmap: Bitmap,
    private val viewportW: Int,
    private val viewportH: Int,
    private val forward: Boolean,
    private val density: Float,
    private val bitmapRecycler: (Bitmap) -> Unit = { bitmap ->
        if (!bitmap.isRecycled) bitmap.recycle()
    },
) : Drawable() {

    internal data class RenderStats(
        val meshDraws: Int,
        val bentStripBitmapDraws: Int,
    )

    private var frontBitmap: Bitmap? = frontBitmap
    private var revealedBitmap: Bitmap? = revealedBitmap

    /** 0 = outgoing page covers the viewport, 1 = target page fully revealed. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x70808080
        strokeWidth = 1f
    }
    private val highlightPaints = Array(SHADE_LEVELS) { index ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb((index + 1) * 64 / SHADE_LEVELS, 255, 255, 255)
        }
    }
    private val shadePaints = Array(SHADE_LEVELS) { index ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb((index + 1) * 72 / SHADE_LEVELS, 0, 0, 0)
        }
    }

    private val bitmapSrc = Rect()
    private val bitmapDst = Rect()
    private val shadeRect = Rect()
    private val gradientRect = Rect()
    private val meshVertices = FloatArray((MESH_COLUMNS + 1) * MESH_ROWS * 4)
    private var meshDrawsInLastFrame = 0
    private var bentStripBitmapDrawsInLastFrame = 0

    internal fun renderStatsForTest(): RenderStats = RenderStats(
        meshDraws = meshDrawsInLastFrame,
        bentStripBitmapDraws = bentStripBitmapDrawsInLastFrame,
    )

    override fun draw(canvas: Canvas) {
        meshDrawsInLastFrame = 0
        bentStripBitmapDrawsInLastFrame = 0
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val width = viewportW.coerceAtLeast(0)
        val height = viewportH.coerceAtLeast(0)
        if (width == 0 || height == 0) {
            canvas.restoreToCount(save)
            return
        }

        if (progress <= 0f) {
            frontBitmap?.let { drawWholeBitmap(canvas, it) }
            canvas.restoreToCount(save)
            return
        }

        val divider = if (forward) {
            (width * (1f - progress)).roundToInt().coerceIn(0, width)
        } else {
            (width * progress).roundToInt().coerceIn(0, width)
        }

        if (forward) {
            revealedBitmap?.let { drawFlatRange(canvas, it, divider, width) }
            frontBitmap?.let { drawPaperWidth(canvas, it, divider) }
        } else {
            frontBitmap?.let { drawFlatRange(canvas, it, divider, width) }
            revealedBitmap?.let { drawPaperWidth(canvas, it, divider) }
        }
        drawSeamShadow(canvas, divider)
        if (divider in 1 until width) {
            canvas.drawLine(divider.toFloat(), 0f, divider.toFloat(), height.toFloat(), dividerPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawWholeBitmap(canvas: Canvas, bitmap: Bitmap) {
        bitmapSrc.set(0, 0, bitmap.width, bitmap.height)
        bitmapDst.set(0, 0, viewportW, viewportH)
        canvas.drawBitmap(bitmap, bitmapSrc, bitmapDst, bitmapPaint)
    }

    private fun drawFlatRange(canvas: Canvas, bitmap: Bitmap, left: Int, right: Int) {
        if (right <= left) return
        val sourceLeft = viewportXToBitmapX(left, bitmap.width)
        val sourceRight = viewportXToBitmapX(right, bitmap.width)
        if (sourceRight <= sourceLeft) return
        bitmapSrc.set(sourceLeft, 0, sourceRight, bitmap.height)
        bitmapDst.set(left, 0, right, viewportH)
        canvas.drawBitmap(bitmap, bitmapSrc, bitmapDst, bitmapPaint)
    }

    private fun viewportXToBitmapX(viewportX: Int, bitmapWidth: Int): Int =
        (viewportX.toLong() * bitmapWidth.toLong() / viewportW.coerceAtLeast(1).toLong())
            .toInt()
            .coerceIn(0, bitmapWidth)

    /** Compresses the full source page into [destinationWidth], bending only its right edge. */
    private fun drawPaperWidth(canvas: Canvas, bitmap: Bitmap, destinationWidth: Int) {
        val sourceWidth = viewportW
        val dstWidth = destinationWidth.coerceIn(0, sourceWidth)
        if (dstWidth <= 0) return
        if (dstWidth >= sourceWidth) {
            drawWholeBitmap(canvas, bitmap)
            return
        }

        val maxBendWidth = (sourceWidth * BEND_PERCENT / 100).coerceAtLeast(1)
        val maxCompressionBeforeFullBend =
            (maxBendWidth * (HALF_PI_FIXED - TABLE_SCALE) / TABLE_SCALE).coerceAtLeast(1)
        val maxBentSourceWidth =
            (maxBendWidth * HALF_PI_FIXED / TABLE_SCALE).coerceAtLeast(1)
        val compression = sourceWidth - dstWidth

        var bentSrcStart: Int
        var bentSrcEnd: Int
        var bentDstStart: Int
        var bentDstEnd: Int
        var startAngle: Int
        var endAngle: Int
        var flatEnd = -1

        if (compression < maxCompressionBeforeFullBend) {
            val tableIndex =
                (compression * TABLE_SIZE / maxCompressionBeforeFullBend).coerceIn(0, TABLE_SIZE)
            val projectedBend = DST_TABLE[tableIndex] * maxBendWidth / TABLE_SCALE
            bentSrcStart = dstWidth - projectedBend
            bentSrcEnd = sourceWidth
            bentDstStart = bentSrcStart
            bentDstEnd = dstWidth
            flatEnd = bentSrcStart
            startAngle = 0
            endAngle = SRC_TABLE[tableIndex]
        } else if (dstWidth >= maxBendWidth) {
            bentSrcStart = dstWidth - maxBendWidth
            bentSrcEnd = bentSrcStart + maxBentSourceWidth
            bentDstStart = bentSrcStart
            bentDstEnd = dstWidth
            flatEnd = bentSrcStart
            startAngle = 0
            endAngle = HALF_PI_FIXED
        } else {
            bentSrcStart = 0
            val hiddenProjection = (maxBendWidth - dstWidth).coerceAtLeast(0)
            val tableIndex =
                (TABLE_SIZE * hiddenProjection / maxBendWidth).coerceIn(0, TABLE_SIZE)
            bentSrcEnd = ASIN_TABLE[tableIndex] * maxBentSourceWidth / TABLE_SCALE
            bentDstStart = 0
            bentDstEnd = dstWidth
            startAngle = ASIN_TABLE[tableIndex]
            endAngle = HALF_PI_FIXED
        }

        if (flatEnd > 0) drawFlatRange(canvas, bitmap, 0, flatEnd)
        if (bentDstStart >= bentDstEnd || bentSrcStart >= bentSrcEnd) return

        val destinationBase =
            SIN_TABLE[startAngle * TABLE_SIZE / HALF_PI_FIXED] * maxBendWidth / TABLE_SCALE
        updateMeshVertices(
            sourceWidth = sourceWidth,
            sourceHeight = viewportH,
            bentSrcStart = bentSrcStart,
            bentSrcEnd = bentSrcEnd,
            bentDstStart = bentDstStart,
            bentDstEnd = bentDstEnd,
            startAngle = startAngle,
            endAngle = endAngle,
            maxBendWidth = maxBendWidth,
            destinationBase = destinationBase,
        )
        val meshSave = canvas.save()
        try {
            canvas.clipRect(bentDstStart, 0, bentDstEnd, viewportH)
            meshDrawsInLastFrame++
            canvas.drawBitmapMesh(
                bitmap,
                MESH_COLUMNS,
                MESH_ROWS,
                meshVertices,
                0,
                null,
                0,
                bitmapPaint,
            )
        } finally {
            canvas.restoreToCount(meshSave)
        }
        shadeRect.set(bentDstStart, 0, bentDstEnd, viewportH)
        drawGradient(canvas, shadeRect, highlightPaints, 0, SHADE_LEVELS - 1)
    }

    private fun updateMeshVertices(
        sourceWidth: Int,
        sourceHeight: Int,
        bentSrcStart: Int,
        bentSrcEnd: Int,
        bentDstStart: Int,
        bentDstEnd: Int,
        startAngle: Int,
        endAngle: Int,
        maxBendWidth: Int,
        destinationBase: Int,
    ) {
        val safeBendWidth = maxBendWidth.coerceAtLeast(1)
        for (column in 0..MESH_COLUMNS) {
            val sourceX = sourceWidth * column / MESH_COLUMNS
            val destinationX = when {
                sourceX <= bentSrcStart -> bentDstStart
                sourceX >= bentSrcEnd -> bentDstEnd
                else -> {
                    val angle = (
                        startAngle +
                            (sourceX - bentSrcStart).toLong() * TABLE_SCALE / safeBendWidth
                        ).toInt().coerceIn(startAngle, endAngle)
                    bentDstStart +
                        SIN_TABLE[angle * TABLE_SIZE / HALF_PI_FIXED] * safeBendWidth / TABLE_SCALE -
                        destinationBase
                }
            }.coerceIn(bentDstStart, bentDstEnd)
            val top = column * 2
            meshVertices[top] = destinationX.toFloat()
            meshVertices[top + 1] = 0f
            val bottom = ((MESH_COLUMNS + 1) * 2) + top
            meshVertices[bottom] = destinationX.toFloat()
            meshVertices[bottom + 1] = sourceHeight.toFloat()
        }
    }

    private fun drawSeamShadow(canvas: Canvas, divider: Int) {
        if (divider <= 0 || divider >= viewportW) return
        val width = min(viewportW / 10, (32f * density).roundToInt()).coerceAtLeast(1)
        shadeRect.set(divider, 0, (divider + width).coerceAtMost(viewportW), viewportH)
        drawGradient(canvas, shadeRect, shadePaints, SHADE_LEVELS / 2, SHADE_LEVELS / 10)
    }

    private fun drawGradient(
        canvas: Canvas,
        rect: Rect,
        paints: Array<Paint>,
        startIndex: Int,
        endIndex: Int,
    ) {
        val count = kotlin.math.abs(endIndex - startIndex) + 1
        val direction = if (startIndex <= endIndex) 1 else -1
        val width = rect.width()
        for (index in 0 until count) {
            val left = rect.left + width * index / count
            val right = rect.left + width * (index + 1) / count
            if (right <= left) continue
            gradientRect.set(left, rect.top, right, rect.bottom)
            canvas.drawRect(gradientRect, paints[startIndex + index * direction])
        }
    }

    /** Transfers the revealed page to the caller without copying it. */
    fun takeRevealedBitmap(): Bitmap? {
        val bitmap = revealedBitmap
        revealedBitmap = null
        if (frontBitmap === bitmap) frontBitmap = null
        return bitmap?.takeUnless { it.isRecycled }
    }

    /** Transfers the outgoing page to the caller without copying it. */
    fun takeFrontBitmap(): Bitmap? {
        val bitmap = frontBitmap
        frontBitmap = null
        if (revealedBitmap === bitmap) revealedBitmap = null
        return bitmap?.takeUnless { it.isRecycled }
    }

    fun recycle() {
        val front = frontBitmap
        val revealed = revealedBitmap
        frontBitmap = null
        revealedBitmap = null
        if (front != null) bitmapRecycler(front)
        if (revealed != null && revealed !== front) bitmapRecycler(revealed)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val TABLE_SIZE = 1024
        const val TABLE_SCALE = 0x10000
        const val BEND_PERCENT = 30
        const val MESH_COLUMNS = 96
        const val MESH_ROWS = 1
        const val SHADE_LEVELS = 16
        val HALF_PI_FIXED = (PI / 2.0 * TABLE_SCALE).roundToInt()
        val SIN_TABLE = IntArray(TABLE_SIZE + 1)
        val ASIN_TABLE = IntArray(TABLE_SIZE + 1)
        val SRC_TABLE = IntArray(TABLE_SIZE + 1)
        val DST_TABLE = IntArray(TABLE_SIZE + 1)

        init {
            for (index in 0..TABLE_SIZE) {
                val angle = PI / 2.0 * index / TABLE_SIZE
                SIN_TABLE[index] = (sin(angle) * TABLE_SCALE).roundToInt()
                ASIN_TABLE[index] = (asin(index.toDouble() / TABLE_SIZE) * TABLE_SCALE).roundToInt()
                val compression = index * (PI / 2.0 - 1.0) / TABLE_SIZE
                val shiftedAngle = solveShiftAngle(compression)
                SRC_TABLE[index] = (shiftedAngle * TABLE_SCALE).roundToInt()
                DST_TABLE[index] = (sin(shiftedAngle) * TABLE_SCALE).roundToInt()
            }
        }

        /** Solves angle - sin(angle) = compression on 0..PI/2. */
        private fun solveShiftAngle(compression: Double): Double {
            var low = 0.0
            var high = PI / 2.0
            var middle = 0.0
            repeat(15) {
                middle = (low + high) / 2.0
                if (middle - sin(middle) < compression) low = middle else high = middle
            }
            return middle
        }
    }
}
