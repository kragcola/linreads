package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas page-curl (仿真书页翻动) — warps a snapshot of the OUTGOING page around a vertical
 * cylinder of radius [radius] and sweeps the crease across the viewport, revealing the incoming
 * page (the real view content) drawn beneath. 2D, no GL (参考 lciel/android-page-curl 的 2D 思路).
 *
 * Forward (next page): the free RIGHT edge peels up, the page rolls into a tube that travels left.
 * Backward (prev page): mirrored — the free LEFT edge peels and the tube travels right.
 *
 * The crease is vertical, so the warp varies only in x (y is identity) and the shading bands are
 * vertical → drawn with horizontal [LinearGradient]s AFTER the mesh (drawBitmapMesh's per-vertex
 * `colors` is unreliable on hardware canvases, so shading never relies on it): the rolled-back
 * underside is darkened from the fold root out to the ridge, and the lifted page casts a soft drop
 * shadow on the revealed page just beyond the ridge.
 */
internal class PageCurlDrawable(
    private val bitmap: Bitmap,
    private val viewportW: Int,
    private val viewportH: Int,
    private val forward: Boolean,
    private val radius: Float,
    private val density: Float,
) : Drawable() {

    /** 0 = page flat (no curl yet), 1 = page fully turned (rolled off-screen). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    private val meshCols = 48
    // 2 rows (top edge + bottom edge), each with (meshCols + 1) vertices, each vertex an (x, y) pair.
    private val verts = FloatArray((meshCols + 1) * 2 * 2)
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // drawBitmapMesh is silently a no-op on many hardware-accelerated canvases (emulator SwiftShader
    // + several real GPUs) — the warped frame never composites and the turn looks like an instant cut.
    // We render the mesh into this software-backed scratch bitmap (a Canvas over an in-memory Bitmap is
    // always a software canvas, where mesh warping IS honoured), then blit the finished frame with plain
    // drawBitmap, which every canvas supports. Allocated lazily on the first warped frame, freed in
    // [recycle]. ARGB_8888 (not 565): the curled-away region must be transparent so the incoming page
    // shows through, and the drop shadow is drawn with alpha onto it.
    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null

    override fun draw(canvas: Canvas) {
        // The host is a ScrollView, so its ViewOverlay draws in CONTENT coordinates (the canvas is
        // translated by scrollY). [setBounds] is given the live viewport in that space; honour it by
        // translating to bounds' top-left, then draw everything in local 0..W / 0..H. Without this the
        // page is painted at content y=0 — tens of thousands of px above the viewport, never visible.
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        if (progress <= 0f) {
            canvas.drawBitmap(bitmap, 0f, 0f, meshPaint)
            canvas.restoreToCount(save)
            return
        }
        val w = viewportW.toFloat()
        val h = viewportH.toFloat()
        // Forward crease: leaves the flat surface at x=W (p=0) and sweeps off the left edge (p=1).
        val crease = w - progress * (w + radius)
        var vi = 0
        for (row in 0..1) {
            val y = if (row == 0) 0f else h
            for (col in 0..meshCols) {
                val srcX = col.toFloat() / meshCols * w
                // Backward is the mirror image of forward across the vertical centre line; the texture
                // column is unchanged (no flipped glyphs), only the geometry reflects.
                val destX = if (forward) forwardDest(srcX, crease) else w - forwardDest(w - srcX, crease)
                verts[vi++] = destX
                verts[vi++] = y
            }
        }
        val frame = scratchBitmap()
        val sc = scratchCanvas ?: run { canvas.restoreToCount(save); return }
        frame.eraseColor(0)
        sc.drawBitmapMesh(bitmap, meshCols, 1, verts, 0, null, 0, meshPaint)
        drawShading(sc, crease, w, h)
        canvas.drawBitmap(frame, 0f, 0f, null)
        canvas.restoreToCount(save)
    }

    private fun scratchBitmap(): Bitmap {
        var b = scratch
        if (b == null || b.isRecycled) {
            b = Bitmap.createBitmap(viewportW, viewportH, Bitmap.Config.ARGB_8888)
            scratch = b
            scratchCanvas = Canvas(b)
        }
        return b
    }

    /** Forward warp: flat left of the crease, wrapped around a radius-[radius] cylinder to its right. */
    private fun forwardDest(srcX: Float, crease: Float): Float {
        if (srcX <= crease) return srcX
        val theta = (srcX - crease) / radius
        return crease + radius * sin(theta)
    }

    private fun drawShading(canvas: Canvas, crease: Float, w: Float, h: Float) {
        // Crease (page leaves the flat) and ridge (top of the arc) in actual destination space.
        val creaseX = if (forward) crease else w - crease
        val ridgeX = if (forward) crease + radius else w - (crease + radius)
        val bandLeft = min(creaseX, ridgeX)
        val bandRight = creaseX + ridgeX - bandLeft // = max(creaseX, ridgeX)

        // 1. Underside shade: the page curving up and over is darker towards the crease (less light).
        shadePaint.shader = LinearGradient(
            creaseX, 0f, ridgeX, 0f,
            intArrayOf(0x55000000, 0x14000000, 0x00000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(bandLeft, 0f, bandRight, h, shadePaint)

        // 2. Drop shadow the lifted page casts on the revealed page, just beyond the ridge.
        val shadowW = min(radius * 0.7f, w * 0.18f)
        val shadowFrom = ridgeX
        val shadowTo = if (forward) ridgeX + shadowW else ridgeX - shadowW
        shadePaint.shader = LinearGradient(
            shadowFrom, 0f, shadowTo, 0f,
            intArrayOf(0x4D000000, 0x00000000), null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(min(shadowFrom, shadowTo), 0f, max(shadowFrom, shadowTo), h, shadePaint)

        // 3. Specular highlight along the ridge — a thin bright seam where the paper bends over.
        val hi = 2f * density
        shadePaint.shader = LinearGradient(
            ridgeX - hi, 0f, ridgeX + hi, 0f,
            intArrayOf(0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(ridgeX - hi, 0f, ridgeX + hi, h, shadePaint)
        shadePaint.shader = null
    }

    fun recycle() {
        scratchCanvas = null
        scratch?.let { if (!it.isRecycled) it.recycle() }
        scratch = null
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
