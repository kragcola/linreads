package dev.readflow.core.prefs

import dev.readflow.core.model.ReaderTypographyRange

/**
 * UI-facing typography range wrapper for reader and Settings controls.
 * Persisted theme profile validation uses the same core model bounds.
 */
object ReaderTypography {
    /** Bump only when a release intentionally installs a new baseline for every user. */
    const val BASELINE_VERSION = 1

    const val MIN_FONT_SP = ReaderTypographyRange.MIN_FONT_SP
    const val MAX_FONT_SP = ReaderTypographyRange.MAX_FONT_SP
    /** 12..32sp，1sp 步进 → Slider steps（不含两端）。 */
    const val FONT_SLIDER_STEPS = 19
    const val DEFAULT_FONT_SP = ReaderTypographyRange.DEFAULT_FONT_SIZE

    const val MIN_LINE_SPACING = ReaderTypographyRange.MIN_LINE_SPACING
    const val MAX_LINE_SPACING = ReaderTypographyRange.MAX_LINE_SPACING
    const val DEFAULT_LINE_SPACING = ReaderTypographyRange.DEFAULT_LINE_SPACING
    /** 0.1..3.0，0.1 步进 → Slider steps（不含两端）。 */
    const val LINE_SPACING_SLIDER_STEPS = 28

    fun clampFontSp(sp: Float): Float =
        if (!sp.isFinite()) DEFAULT_FONT_SP.toFloat() else sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    fun clampLineSpacing(multiplier: Float): Float =
        if (!multiplier.isFinite()) DEFAULT_LINE_SPACING else multiplier.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
}
