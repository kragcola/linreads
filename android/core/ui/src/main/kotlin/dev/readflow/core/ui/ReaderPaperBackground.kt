package dev.readflow.core.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Moon+ Reader-style paper-texture reading background (纸质质感背景). Fills with the theme's flat paper
 * colour, then overlays a seamlessly TILED fibre-grain bitmap (migrated from Moon+'s `readbg_02`,
 * desaturated to neutral grey so one asset works for every palette) at a low alpha — so the page reads
 * as textured paper instead of flat fill, without shifting the palette's measured colour/contrast.
 *
 * The grain is tinted toward the ink colour and composited [PorterDuff.Mode.SRC_ATOP] over the paper:
 * on a light paper this darkens fibres slightly (paper texture); on a dark/night paper the same tint
 * is near-black and the low alpha keeps it a subtle tooth, never noise. Alpha is lower for night themes.
 *
 * One shared [Drawable] for all four engines (EPUB/TXT/MD/PDF) — they each call [readerPaperBackground]
 * with their palette instead of `setBackgroundColor`.
 */
private const val GRAIN_ASSET = "textures/paper_grain.png"

fun readerPaperBackground(
    context: Context,
    paperColor: Int,
    inkColor: Int,
    isNight: Boolean,
): Drawable {
    val grain = runCatching {
        context.assets.open(GRAIN_ASSET).use { BitmapFactory.decodeStream(it) }
    }.getOrNull() ?: return android.graphics.drawable.ColorDrawable(paperColor)
    val shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    // The grain bitmap already encodes the subtlety as low per-pixel alpha (only darker-than-mean
    // fibres are opaque, peak ~0x78); the paint alpha scales the whole tooth. Night papers are darker
    // and show grain more readily, so pull it down a touch there. The grain is forced white RGB so the
    // SRC_IN ink tint sets its hue per palette (dark fibre on light paper / light tooth on dark paper).
    val alpha = if (isNight) 0x80 else 0xC0
    return PaperTextureDrawable(paperColor, shader, PorterDuffColorFilter(inkColor, PorterDuff.Mode.SRC_IN), alpha)
}

private class PaperTextureDrawable(
    private val paperColor: Int,
    shader: Shader,
    grainTint: ColorFilter,
    private val grainAlpha: Int,
) : Drawable() {

    private val fillPaint = Paint().apply { color = paperColor }
    private val grainPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = shader
        colorFilter = grainTint
        alpha = grainAlpha
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), fillPaint)
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), grainPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
