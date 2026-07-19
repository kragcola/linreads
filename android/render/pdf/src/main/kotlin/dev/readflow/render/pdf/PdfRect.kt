package dev.readflow.render.pdf

import android.graphics.RectF

/**
 * Axis-aligned rectangle in PDF page-point or bitmap pixel space.
 *
 * Pure floats so JVM unit tests can assert geometry without Robolectric /
 * android.jar [RectF] stubs (which leave fields at 0 on the unit-test classpath).
 * Convert to [RectF] only at the Android graphics boundary (draw / framework).
 */
internal data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isEmpty(): Boolean = right <= left || bottom <= top

    fun toRectF(): RectF = RectF(left, top, right, bottom)

    companion object {
        fun from(rect: RectF): PdfRect = PdfRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}
