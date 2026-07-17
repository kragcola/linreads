package dev.readflow.core.model

/**
 * Chapter-level progress snapshot for the reader chrome.
 * Engines that support chapters (EPUB via spine, etc.) update this
 * on each scroll/page-turn; engines without a chapter TOC use
 * [Kind.PAGE] or [Kind.DOCUMENT] instead of inventing a fake 1/1 chapter.
 */
data class ChapterInfo(
    /** 0-based index of the current chapter / page. */
    val currentIndex: Int,
    /** Total number of chapters / pages. DOCUMENT never invents a count (use 0). */
    val totalChapters: Int,
    /** Display name of the current chapter (e.g. TOC label or "第3章"). */
    val currentTitle: String,
    /** Progress within the current chapter, 0..1. Unused for PAGE (always 0). */
    val progressInChapter: Float,
    /** How [currentIndex] / [totalChapters] should be interpreted by chrome. */
    val kind: Kind = Kind.CHAPTER,
) {
    enum class Kind {
        CHAPTER,
        PAGE,
        DOCUMENT,
    }
}

/**
 * Build [ChapterInfo] from an ordered TOC plus whole-book progression.
 *
 * TOC should be sorted by ascending [Locator.totalProgression] when values are present.
 * Non-finite progression (NaN/Inf) is sanitized before selection and local-progress math.
 *
 * Selection rule (conservative): among entries with a **finite** progression that is
 * `<=` the current finite progression, pick the last match. If none qualify (all missing
 * or non-finite), pick the first TOC entry — never treat missing progression as `0` and
 * walk to the last item.
 *
 * Local progress uses finite neighboring anchors when available; otherwise `0f`.
 * Blank titles use [documentTitleFallback] (trimmed), then "正文".
 * Empty TOC yields [Kind.DOCUMENT] with totalChapters=0.
 */
fun chapterInfoFromOrderedToc(
    tocEntries: List<TocEntry>,
    totalProgression: Float?,
    documentTitleFallback: String,
): ChapterInfo {
    val fallbackTitle = documentTitleFallback.trim().ifEmpty { "正文" }
    if (tocEntries.isEmpty()) {
        return ChapterInfo(
            currentIndex = 0,
            totalChapters = 0,
            currentTitle = fallbackTitle,
            progressInChapter = 0f,
            kind = ChapterInfo.Kind.DOCUMENT,
        )
    }
    val progress = finiteProgressionOrDefault(totalProgression, default = 0f)

    // Last entry with finite progression <= current; if none, first entry.
    var index = -1
    for (i in tocEntries.indices) {
        val start = finiteProgressionOrNull(tocEntries[i].locator.totalProgression) ?: continue
        if (start <= progress) {
            index = i
        } else {
            // Ordered TOC: first finite start past current progress ends the scan.
            break
        }
    }
    if (index < 0) index = 0

    val entry = tocEntries[index]
    val start = finiteProgressionOrNull(entry.locator.totalProgression)
    val end = finiteEndAnchor(tocEntries, index)
    val local = localProgressInSpan(progress = progress, start = start, end = end)
    val title = entry.title.trim().ifEmpty { fallbackTitle }
    return ChapterInfo(
        currentIndex = index,
        totalChapters = tocEntries.size,
        currentTitle = title,
        progressInChapter = local,
        kind = ChapterInfo.Kind.CHAPTER,
    )
}

/**
 * Build page-oriented [ChapterInfo]. Never invents a page count of 1 when [pageCount] is 0.
 * [pageIndex] is clamped into a valid range when [pageCount] > 0.
 */
fun pageChapterInfo(
    pageIndex: Int,
    pageCount: Int,
    documentTitleFallback: String,
): ChapterInfo {
    val safeCount = pageCount.coerceAtLeast(0)
    val safeIndex = if (safeCount == 0) {
        0
    } else {
        pageIndex.coerceIn(0, safeCount - 1)
    }
    val title = documentTitleFallback.trim().ifEmpty { "正文" }
    return ChapterInfo(
        currentIndex = safeIndex,
        totalChapters = safeCount,
        currentTitle = title,
        progressInChapter = 0f,
        kind = ChapterInfo.Kind.PAGE,
    )
}

/**
 * Select the TOC entry at [currentIndex] + [delta]. Clamps a malformed base index into
 * range before applying delta; returns null when the target is out of bounds or TOC is empty.
 */
fun adjacentTocEntry(
    tocEntries: List<TocEntry>,
    currentIndex: Int,
    delta: Int,
): TocEntry? {
    if (tocEntries.isEmpty()) return null
    val base = currentIndex.coerceIn(0, tocEntries.lastIndex)
    val target = base + delta
    if (target !in tocEntries.indices) return null
    return tocEntries[target]
}

/** Finite progression in [0, 1], or null when missing / non-finite. */
private fun finiteProgressionOrNull(value: Float?): Float? {
    if (value == null || !value.isFinite()) return null
    return value.coerceIn(0f, 1f)
}

/** Finite progression in [0, 1], or [default] when missing / non-finite. */
private fun finiteProgressionOrDefault(value: Float?, default: Float): Float =
    finiteProgressionOrNull(value) ?: default.let { d ->
        if (d.isFinite()) d.coerceIn(0f, 1f) else 0f
    }

/**
 * End of the current chapter span: next TOC entry with a finite progression, or `1f` when
 * this is the last entry (document end). Missing/non-finite neighbor → null (local = 0).
 */
private fun finiteEndAnchor(tocEntries: List<TocEntry>, index: Int): Float? {
    if (index >= tocEntries.lastIndex) return 1f
    return finiteProgressionOrNull(tocEntries[index + 1].locator.totalProgression)
}

private fun localProgressInSpan(progress: Float, start: Float?, end: Float?): Float {
    if (start == null || end == null) return 0f
    val span = (end - start).coerceAtLeast(0f)
    if (span <= 0f) return 0f
    return ((progress - start) / span).coerceIn(0f, 1f)
}
