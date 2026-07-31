package dev.readflow.render.pdf

internal data class PdfPanOffset(
    val x: Float,
    val y: Float,
) {
    companion object {
        val Zero = PdfPanOffset(0f, 0f)
    }
}

internal data class PdfPanBounds(
    val maxX: Float,
    val maxY: Float,
) {
    fun clamp(offset: PdfPanOffset): PdfPanOffset = PdfPanOffset(
        x = clampAxis(offset.x, maxX),
        y = clampAxis(offset.y, maxY),
    )

    fun canPanHorizontally(offset: PdfPanOffset, direction: Int): Boolean {
        val x = clamp(offset).x
        return when {
            direction > 0 -> x < maxX
            direction < 0 -> x > -maxX
            else -> false
        }
    }

    fun canPanVertically(offset: PdfPanOffset, direction: Int): Boolean {
        val y = clamp(offset).y
        return when {
            direction > 0 -> y < maxY
            direction < 0 -> y > -maxY
            else -> false
        }
    }
}

internal class PdfPanState {
    var offset: PdfPanOffset = PdfPanOffset.Zero
        private set

    fun panBy(deltaX: Float, deltaY: Float, bounds: PdfPanBounds): PdfPanOffset =
        set(PdfPanOffset(offset.x + deltaX, offset.y + deltaY), bounds)

    fun set(candidate: PdfPanOffset, bounds: PdfPanBounds): PdfPanOffset {
        offset = bounds.clamp(candidate)
        return offset
    }

    fun reclamp(bounds: PdfPanBounds): PdfPanOffset = set(offset, bounds)

    fun reset() {
        offset = PdfPanOffset.Zero
    }
}

internal fun pdfPanBounds(
    contentWidth: Float,
    contentHeight: Float,
    fittedPageWidth: Float,
    fittedPageHeight: Float,
    zoomScale: Float,
): PdfPanBounds {
    val width = contentWidth.nonNegativeFinite()
    val height = contentHeight.nonNegativeFinite()
    val fittedWidth = fittedPageWidth.nonNegativeFinite()
    val fittedHeight = fittedPageHeight.nonNegativeFinite()
    val zoom = zoomScale.finiteOrOne().coerceAtLeast(1f)
    return PdfPanBounds(
        maxX = panExtent(width, fittedWidth, zoom),
        maxY = panExtent(height, fittedHeight, zoom),
    )
}

internal fun pdfPanOffsetKeepingFocus(
    offset: PdfPanOffset,
    previousZoomScale: Float,
    zoomScale: Float,
    focusX: Float,
    focusY: Float,
    contentCenterX: Float,
    contentCenterY: Float,
): PdfPanOffset {
    if (previousZoomScale <= 0f || !previousZoomScale.isFinite()) return offset
    if (zoomScale <= 0f || !zoomScale.isFinite()) return offset
    val ratio = zoomScale / previousZoomScale
    if (!ratio.isFinite()) return offset
    return PdfPanOffset(
        x = focusX - contentCenterX -
            (focusX - contentCenterX - offset.x) * ratio,
        y = focusY - contentCenterY -
            (focusY - contentCenterY - offset.y) * ratio,
    )
}

private fun panExtent(contentSize: Float, fittedPageSize: Float, zoomScale: Float): Float {
    val extent = (fittedPageSize.toDouble() * zoomScale - contentSize) / 2.0
    return when {
        extent <= 0.0 -> 0f
        extent >= Float.MAX_VALUE -> Float.MAX_VALUE
        else -> extent.toFloat()
    }
}

private fun Float.nonNegativeFinite(): Float =
    if (isFinite() && this > 0f) this else 0f

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

private fun Float.finiteOrOne(): Float = if (isFinite()) this else 1f

private fun clampAxis(value: Float, maximum: Float): Float =
    if (maximum <= 0f) 0f else value.finiteOrZero().coerceIn(-maximum, maximum)
