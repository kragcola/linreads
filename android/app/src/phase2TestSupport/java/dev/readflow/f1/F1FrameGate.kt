package dev.readflow.f1

/**
 * Locked default physical-device acceptance for F1 mixed heading→image page-turn.
 * Instrumentation may override via args only when the selected values are written into evidence.
 *
 * Construction and [fromInstrumentationArgs] fail fast on invalid values so NaN/Infinity/zero/negative
 * cannot bypass the hard gate.
 */
data class F1FrameThresholds(
    val maxP95Ms: Int = DEFAULT_MAX_P95_MS,
    val maxJankyRatio: Double = DEFAULT_MAX_JANKY_RATIO,
    val minTotalFrames: Int = DEFAULT_MIN_TOTAL_FRAMES,
) {
    init {
        require(maxP95Ms > 0) { "maxP95Ms must be > 0, was $maxP95Ms" }
        require(maxJankyRatio.isFinite()) {
            "maxJankyRatio must be finite, was $maxJankyRatio"
        }
        require(maxJankyRatio in 0.0..1.0) {
            "maxJankyRatio must be in 0.0..1.0, was $maxJankyRatio"
        }
        require(minTotalFrames > 0) { "minTotalFrames must be > 0, was $minTotalFrames" }
    }

    companion object {
        const val DEFAULT_MAX_P95_MS: Int = 32
        const val DEFAULT_MAX_JANKY_RATIO: Double = 0.20
        const val DEFAULT_MIN_TOTAL_FRAMES: Int = 8

        /**
         * Parse instrumentation string overrides. Null / blank raw values keep defaults.
         * Invalid explicit values fail fast (no silent fallback to defaults).
         */
        fun fromInstrumentationArgs(
            maxP95Raw: String?,
            maxJankyRaw: String?,
            minFramesRaw: String?,
        ): F1FrameThresholds {
            val maxP95 = parsePositiveIntOverride(maxP95Raw, "maxP95Ms") ?: DEFAULT_MAX_P95_MS
            val maxJanky = parseJankyRatioOverride(maxJankyRaw) ?: DEFAULT_MAX_JANKY_RATIO
            val minFrames = parsePositiveIntOverride(minFramesRaw, "minTotalFrames")
                ?: DEFAULT_MIN_TOTAL_FRAMES
            return F1FrameThresholds(
                maxP95Ms = maxP95,
                maxJankyRatio = maxJanky,
                minTotalFrames = minFrames,
            )
        }

        private fun parsePositiveIntOverride(raw: String?, field: String): Int? {
            if (raw.isNullOrBlank()) return null
            val value = raw.trim().toIntOrNull()
                ?: throw IllegalArgumentException("invalid $field override: '$raw'")
            // Validation also runs in init; explicit message here for instrumentation context.
            if (value <= 0) {
                throw IllegalArgumentException("$field override must be > 0, was $value")
            }
            return value
        }

        private fun parseJankyRatioOverride(raw: String?): Double? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            val value = trimmed.toDoubleOrNull()
                ?: throw IllegalArgumentException("invalid maxJankyRatio override: '$raw'")
            if (!value.isFinite()) {
                throw IllegalArgumentException("maxJankyRatio override must be finite, was $trimmed")
            }
            if (value !in 0.0..1.0) {
                throw IllegalArgumentException(
                    "maxJankyRatio override must be in 0.0..1.0, was $value",
                )
            }
            return value
        }
    }
}

/**
 * Hard evaluation of gfxinfo metrics against [F1FrameThresholds].
 * Missing metrics or too few frames always fail — the harness cannot pass silently.
 */
data class F1FrameGateResult(
    val pass: Boolean,
    val reasons: List<String>,
    val jankyRatio: Double?,
    val totalFrames: Int?,
    val jankyFrames: Int?,
    val p90Ms: Int?,
    val p95Ms: Int?,
)

object F1FrameGate {
    fun evaluate(
        metrics: GfxInfoMetrics,
        thresholds: F1FrameThresholds = F1FrameThresholds(),
    ): F1FrameGateResult {
        val reasons = mutableListOf<String>()
        val total = metrics.totalFrames
        val janky = metrics.jankyFrames
        val p90 = metrics.p90Ms
        val p95 = metrics.p95Ms

        if (total == null) reasons += "missing_total_frames"
        if (janky == null) reasons += "missing_janky_frames"
        if (p90 == null) reasons += "missing_p90_ms"
        if (p95 == null) reasons += "missing_p95_ms"

        val jankyRatio: Double? =
            if (total != null && janky != null && total > 0) {
                janky.toDouble() / total.toDouble()
            } else {
                null
            }

        if (total != null && total < thresholds.minTotalFrames) {
            reasons += "total_frames_below_min($total<${thresholds.minTotalFrames})"
        }
        if (p95 != null && p95 > thresholds.maxP95Ms) {
            reasons += "p95_above_max(${p95}ms>${thresholds.maxP95Ms}ms)"
        }
        if (jankyRatio != null && jankyRatio > thresholds.maxJankyRatio) {
            reasons += "janky_ratio_above_max(${formatRatio(jankyRatio)}>${formatRatio(thresholds.maxJankyRatio)})"
        }
        // total==0 is also too few frames; covered by minTotalFrames when total is present.
        // When janky/total present but total==0, ratio is null — still fail via min frames.

        return F1FrameGateResult(
            pass = reasons.isEmpty(),
            reasons = reasons,
            jankyRatio = jankyRatio,
            totalFrames = total,
            jankyFrames = janky,
            p90Ms = p90,
            p95Ms = p95,
        )
    }

    private fun formatRatio(value: Double): String =
        String.format(java.util.Locale.US, "%.4f", value)
}
