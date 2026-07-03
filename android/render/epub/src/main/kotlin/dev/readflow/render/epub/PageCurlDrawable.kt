package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Lightweight simulated page-turn (仿真书页翻动, 轻量版). A snapshot of the OUTGOING page is rotated
 * around its spine edge with a [Camera] 3D hinge and blitted with ONE [Canvas.drawBitmap] + a matrix,
 * plus a single darkening overlay rect. No [Canvas.drawBitmapMesh], no per-frame software scratch
 * bitmap, no per-pixel warp — every frame is a GPU-composited transform, so it holds 60fps at tablet
 * resolution (the heavy mesh-curl version it replaces warped a full-screen ARGB_8888 bitmap on the UI
 * thread each frame, ~15MB/frame — the cause of the stutter).
 *
 * Forward (next page): the page pivots around its LEFT edge (the spine), the free right edge lifts
 * toward the viewer and foreshortens to zero width at 90° — reading as a page swinging away to the
 * left, revealing the incoming page (the real view content) drawn beneath.
 * Backward (prev page): mirrored — pivots around the RIGHT edge, the left edge lifts.
 *
 * As the page folds it darkens (less light reaches the tilting surface), and a soft shadow falls on
 * the revealed page just past the spine.
 */
internal class PageCurlDrawable(
    private val frontBitmap: Bitmap,
    private val revealedBitmap: Bitmap,
    private val viewportW: Int,
    private val viewportH: Int,
    private val forward: Boolean,
    private val density: Float,
) : Drawable() {

    /** 0 = page flat (no fold), 1 = page fully turned (folded edge-on, off the spine). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camera = Camera()
    private val matrix = Matrix()

    init {
        // Push the camera back so a full-screen page doesn't blow up into extreme perspective as it
        // tilts (the default location is very close). Farther Z = gentler, book-like fold.
        camera.setLocation(0f, 0f, -density * 12f)
    }

    override fun draw(canvas: Canvas) {
        // Host is a ScrollView → its ViewOverlay draws in CONTENT coords (canvas translated by scrollY).
        // [setBounds] is the live viewport in that space; translate to its top-left, then work in local
        // 0..W / 0..H.
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = viewportW.toFloat()
        val h = viewportH.toFloat()
        canvas.drawBitmap(revealedBitmap, 0f, 0f, paint)
        if (progress <= 0f) {
            canvas.drawBitmap(frontBitmap, 0f, 0f, paint)
            canvas.restoreToCount(save)
            return
        }

        val degrees = progress * 90f
        val pivotX = if (forward) 0f else w
        val pivotY = h / 2f

        camera.save()
        // Forward: pivot at left, lift the right edge toward the viewer (positive rotateY pulls the
        // right side forward). Backward: mirror the sign so the left edge lifts at the right pivot.
        camera.rotateY(if (forward) degrees else -degrees)
        camera.getMatrix(matrix)
        camera.restore()
        // Re-anchor the rotation pivot to the spine edge (Camera rotates about the origin by default).
        matrix.preTranslate(-pivotX, -pivotY)
        matrix.postTranslate(pivotX, pivotY)

        val mSave = canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(frontBitmap, 0f, 0f, paint)
        // The tilting surface catches less light → darken proportionally to the fold angle.
        shadePaint.shader = null
        shadePaint.color = 0xFF000000.toInt()
        shadePaint.alpha = (progress * 130f).toInt().coerceIn(0, 130)
        canvas.drawRect(0f, 0f, w, h, shadePaint)
        canvas.restoreToCount(mSave)

        // Soft shadow the lifted page casts on the revealed page, just past the spine edge.
        drawSpineShadow(canvas, w, h)
        canvas.restoreToCount(save)
    }

    private fun drawSpineShadow(canvas: Canvas, w: Float, h: Float) {
        val shadowW = (28f * density) * progress
        if (shadowW <= 0f) return
        val from = if (forward) 0f else w
        val to = if (forward) shadowW else w - shadowW
        val edgeAlpha = (progress * 0.45f * 255f).toInt().coerceIn(0, 115)
        shadePaint.color = 0xFF000000.toInt()
        shadePaint.alpha = 255
        shadePaint.shader = LinearGradient(
            from, 0f, to, 0f,
            intArrayOf(edgeAlpha shl 24, 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(minOf(from, to), 0f, maxOf(from, to), h, shadePaint)
        shadePaint.shader = null
    }

    fun recycle() {
        if (!frontBitmap.isRecycled) frontBitmap.recycle()
        if (!revealedBitmap.isRecycled) revealedBitmap.recycle()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
