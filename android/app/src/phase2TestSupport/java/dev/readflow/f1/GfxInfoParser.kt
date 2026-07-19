package dev.readflow.f1

/**
 * Parsed subset of `dumpsys gfxinfo` used by the F1 page-turn frame gate.
 * Null fields mean the metric line was missing or unparsable.
 */
data class GfxInfoMetrics(
    val totalFrames: Int?,
    val jankyFrames: Int?,
    val p90Ms: Int?,
    val p95Ms: Int?,
)

/**
 * Pure parser for A02-style gfxinfo dumps. No Android framework dependency.
 *
 * Recognized line prefixes (trimmed):
 * - `Total frames rendered: N`
 * - `Janky frames: N (xx.xx%)` — only the leading integer is required
 * - `90th percentile: Nms`
 * - `95th percentile: Nms`
 */
object GfxInfoParser {
    fun parse(output: String): GfxInfoMetrics =
        GfxInfoMetrics(
            totalFrames = output.lineValueAfter("Total frames rendered:")?.toIntOrNull(),
            jankyFrames = output.lineValueAfter("Janky frames:")
                ?.substringBefore(" ")
                ?.toIntOrNull(),
            p90Ms = output.lineValueAfter("90th percentile:")
                ?.removeSuffix("ms")
                ?.trim()
                ?.toIntOrNull(),
            p95Ms = output.lineValueAfter("95th percentile:")
                ?.removeSuffix("ms")
                ?.trim()
                ?.toIntOrNull(),
        )

    private fun String.lineValueAfter(prefix: String): String? =
        lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
}
