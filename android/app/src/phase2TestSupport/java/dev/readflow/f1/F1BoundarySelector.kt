package dev.readflow.f1

/**
 * Extracted viewport geometry for one heading→image page-boundary candidate.
 *
 * Pure data only — device harness reflects [PageBoundaryImagePreview] / page windows into these
 * fields. No hardcoded page numbers: selection is viewport-adaptive over the extracted set.
 */
data class F1BoundaryCandidate(
    /** Stable fixture / discovery order (lower first when scores tie). */
    val candidateIndex: Int,
    /** Page index that ends with the heading (or preceding text). */
    val headingPage: Int,
    /** Page index that owns the full indivisible image. */
    val imagePage: Int,
    /** Crop band top in heading-page viewport coordinates (px). */
    val cropBandTopPx: Int,
    /** Crop band bottom exclusive in heading-page viewport coordinates (px). */
    val cropBandBottomPx: Int,
    /** Viewport height used when the band was measured (px). */
    val viewportHeightPx: Int,
    /** Full image line height in layout (px); must exceed the leftover band for a crop. */
    val imageLineHeightPx: Int,
)

/**
 * Deterministic selection among viewport-extracted heading→image boundaries.
 */
data class F1BoundarySelection(
    val candidateIndex: Int,
    val headingPage: Int,
    val imagePage: Int,
    val cropBandTopPx: Int,
    val cropBandBottomPx: Int,
    val bandHeightPx: Int,
)

/**
 * Pure viewport-adaptive boundary selector for F1 mixed heading→image page-turn.
 *
 * Acceptance:
 * - [F1BoundaryCandidate.imagePage] == [F1BoundaryCandidate.headingPage] + 1
 * - crop band is visible inside the viewport and at least [minBandHeightPx] tall
 * - image line is taller than the leftover band (true crop, not full-image fit)
 *
 * Ranking (deterministic): lowest [headingPage], then largest band height, then lowest
 * [candidateIndex].
 */
object F1BoundarySelector {
    /**
     * Default minimum crop-band height in px for pure JVM tests.
     * Device harness must pass a meaningful physical floor (≥24dp converted to px).
     */
    const val DEFAULT_MIN_BAND_HEIGHT_PX: Int = 1

    fun select(
        candidates: List<F1BoundaryCandidate>,
        minBandHeightPx: Int = DEFAULT_MIN_BAND_HEIGHT_PX,
    ): F1BoundarySelection? {
        require(minBandHeightPx > 0) {
            "minBandHeightPx must be > 0, was $minBandHeightPx"
        }
        val valid = candidates.mapNotNull { candidate ->
            val bandTop = candidate.cropBandTopPx
            val bandBottom = candidate.cropBandBottomPx
            val viewportH = candidate.viewportHeightPx
            val bandHeight = bandBottom - bandTop
            val adjacent = candidate.imagePage == candidate.headingPage + 1
            val bandInViewport =
                bandTop >= 0 &&
                    bandBottom <= viewportH &&
                    bandHeight >= minBandHeightPx &&
                    viewportH > 0
            val trueCrop = candidate.imageLineHeightPx > bandHeight
            if (!adjacent || !bandInViewport || !trueCrop) {
                null
            } else {
                F1BoundarySelection(
                    candidateIndex = candidate.candidateIndex,
                    headingPage = candidate.headingPage,
                    imagePage = candidate.imagePage,
                    cropBandTopPx = bandTop,
                    cropBandBottomPx = bandBottom,
                    bandHeightPx = bandHeight,
                )
            }
        }
        if (valid.isEmpty()) return null
        return valid.minWith(
            compareBy<F1BoundarySelection> { it.headingPage }
                .thenByDescending { it.bandHeightPx }
                .thenBy { it.candidateIndex },
        )
    }
}
