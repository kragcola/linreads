package dev.readflow.features.reader

internal fun clampedReaderLineSpacing(
    multiplier: Float,
    minMultiplier: Float = 1.4f,
    maxMultiplier: Float = 2.2f,
    defaultMultiplier: Float = 1.75f,
): Float {
    if (!multiplier.isFinite()) return defaultMultiplier
    return multiplier.coerceIn(minMultiplier, maxMultiplier)
}
