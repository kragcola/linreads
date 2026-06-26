package dev.readflow.features.reader

internal fun scaledReaderZoom(
    startScale: Float,
    scaleFactor: Float,
    minScale: Float = 1f,
    maxScale: Float = 4f,
): Float {
    val current = startScale.coerceIn(minScale, maxScale)
    if (!scaleFactor.isFinite() || scaleFactor <= 0f) return current
    return (current * scaleFactor).coerceIn(minScale, maxScale)
}
