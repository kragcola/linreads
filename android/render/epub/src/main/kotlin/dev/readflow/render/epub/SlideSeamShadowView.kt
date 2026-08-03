package dev.readflow.render.epub

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Isolated lightweight seam shadow for the staged SLIDE strip.
 *
 * The shadow is a thin vertical gradient band that the flow view lays out at the strip seam each
 * animation frame. Its own display list is tiny and is only invalidated when the turn direction
 * flips (the gradient is mirrored with scaleX), so promoting the [PageSlideOverlayView] content
 * display list never triggers a page-content record pass.
 */
internal class SlideSeamShadowView(
    context: Context,
    widthPx: Int,
) : View(context) {

    private val paint = Paint().apply {
        isAntiAlias = true
        shader = LinearGradient(
            0f,
            0f,
            widthPx.toFloat(),
            0f,
            0x40000000.toInt(),
            0x00000000,
            Shader.TileMode.CLAMP,
        )
    }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
