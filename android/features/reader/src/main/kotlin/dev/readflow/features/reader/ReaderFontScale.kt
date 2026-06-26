package dev.readflow.features.reader

internal fun scaledReaderFontSize(
    startSp: Float,
    scaleFactor: Float,
    minSp: Float = 12f,
    maxSp: Float = 32f,
): Float {
    if (!scaleFactor.isFinite() || scaleFactor <= 0f) {
        return startSp.coerceIn(minSp, maxSp)
    }
    return (startSp * scaleFactor).coerceIn(minSp, maxSp)
}
