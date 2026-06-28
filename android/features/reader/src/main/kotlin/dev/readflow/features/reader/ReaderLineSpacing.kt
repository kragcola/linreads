package dev.readflow.features.reader

import dev.readflow.core.prefs.ReaderTypography

internal fun clampedReaderLineSpacing(
    multiplier: Float,
    minMultiplier: Float = ReaderTypography.MIN_LINE_SPACING,
    maxMultiplier: Float = ReaderTypography.MAX_LINE_SPACING,
    defaultMultiplier: Float = ReaderTypography.DEFAULT_LINE_SPACING,
): Float {
    if (!multiplier.isFinite()) return defaultMultiplier
    return multiplier.coerceIn(minMultiplier, maxMultiplier)
}
