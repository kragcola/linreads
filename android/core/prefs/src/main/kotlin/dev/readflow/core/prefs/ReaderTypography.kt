package dev.readflow.core.prefs

/**
 * 排版滑块口径的唯一真源（v4 P2：统一 reader 与 Settings 两处字号/行距范围）。
 * reader FontPanel、Settings 页、各处 clamp 都引用此处，避免再次漂移。
 */
object ReaderTypography {
    const val MIN_FONT_SP = 12f
    const val MAX_FONT_SP = 32f
    /** 12..32sp，1sp 步进 → Slider steps（不含两端）。 */
    const val FONT_SLIDER_STEPS = 19
    const val DEFAULT_FONT_SP = 18

    const val MIN_LINE_SPACING = 1.4f
    const val MAX_LINE_SPACING = 2.2f
    const val DEFAULT_LINE_SPACING = 1.75f
    /** 1.4..2.2，0.1 步进 → Slider steps（不含两端）。 */
    const val LINE_SPACING_SLIDER_STEPS = 7

    fun clampFontSp(sp: Float): Float =
        if (!sp.isFinite()) DEFAULT_FONT_SP.toFloat() else sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    fun clampLineSpacing(multiplier: Float): Float =
        if (!multiplier.isFinite()) DEFAULT_LINE_SPACING else multiplier.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
}
