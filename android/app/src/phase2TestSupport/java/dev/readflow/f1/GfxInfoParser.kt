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
    val slowUiThreadFrames: Int? = null,
    val slowIssueDrawCommandsFrames: Int? = null,
    val slowBitmapUploadsFrames: Int? = null,
)

/**
 * Pure parser for A02-style gfxinfo dumps. No Android framework dependency.
 *
 * Recognized line prefixes (trimmed):
 * - `Total frames rendered: N`
 * - `Janky frames: N (xx.xx%)` — only the leading integer is required
 * - `90th percentile: Nms`
 * - `95th percentile: Nms`
 * - `Number Slow UI thread: N` (Huawei canonical)
 * - `Number Slow issue draw commands: N`
 * - `Number Slow bitmap uploads: N`
 * - also the legacy `Slow UI thread: N frames` form (and its issue/bitmap siblings)
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
            slowUiThreadFrames = output.slowCountAfter("Slow UI thread:"),
            slowIssueDrawCommandsFrames = output.slowCountAfter("Slow issue draw commands:"),
            slowBitmapUploadsFrames = output.slowCountAfter("Slow bitmap uploads:"),
        )

    private fun String.lineValueAfter(prefix: String): String? =
        lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()

    /**
     * Accepts both the canonical Huawei `Number Slow ...: N` form and the legacy
     * `Slow ...: N frames` form. Returns null when neither form is present or the value is not an
     * integer.
     */
    private fun String.slowCountAfter(slowPrefix: String): Int? {
        val canonical = lineValueAfter("Number $slowPrefix")
            ?.toIntOrNull()
        if (canonical != null) return canonical
        return lineValueAfter(slowPrefix)
            ?.substringBefore("frames")
            ?.trim()
            ?.toIntOrNull()
    }
}
