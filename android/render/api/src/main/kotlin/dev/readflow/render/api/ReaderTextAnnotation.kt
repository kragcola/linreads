package dev.readflow.render.api

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import dev.readflow.core.model.Locator

data class ReaderTextAnnotation(
    val id: String,
    val start: Locator,
    val end: Locator,
    val selectedText: String,
    val note: String?,
    val color: Int,
)

data class ReaderTextHighlightRange(
    val start: Int,
    val end: Int,
    val color: Int,
)

interface TextAnnotatableReaderEngine : ReaderEngine {
    /**
     * Runtime capability for creating new text annotations via selection in the current open session.
     * Default true keeps reflow engines (EPUB/TXT/Markdown) advertising annotation creation.
     * PDF overrides from framework selection API availability — not page-content probes.
     * List/jump/delete of already-saved annotations is independent of this flag.
     */
    val supportsTextAnnotationCreation: Boolean get() = true

    fun setTextAnnotations(annotations: List<ReaderTextAnnotation>)
}

class ReaderTextHighlightSpan(color: Int) : BackgroundColorSpan(color)

/**
 * Transient in-page search selection span. Distinct type so refresh/clear can remove only search
 * paint without wiping [ReaderTextHighlightSpan], CSS [BackgroundColorSpan], or selection spans.
 */
class ReaderSearchHighlightSpan(
    color: Int = READER_SEARCH_HIGHLIGHT_COLOR,
) : BackgroundColorSpan(color)

/**
 * Apply persistent annotation highlight ranges. Optionally also paints transient search ranges
 * via [ReaderSearchHighlightSpan]. Only dedicated span types are stripped/replaced; plain
 * [BackgroundColorSpan] instances (CSS, selection) are preserved.
 */
fun CharSequence.withTextHighlightSpans(
    ranges: List<ReaderTextHighlightRange>,
    searchRanges: List<ReaderTextHighlightRange> = emptyList(),
): CharSequence {
    if (isEmpty()) return this
    val spannable = SpannableString(this)
    spannable.getSpans(0, spannable.length, ReaderTextHighlightSpan::class.java).forEach {
        spannable.removeSpan(it)
    }
    spannable.getSpans(0, spannable.length, ReaderSearchHighlightSpan::class.java).forEach {
        spannable.removeSpan(it)
    }
    ranges.forEach { range ->
        applyExclusiveBackgroundSpan(spannable, range) { color -> ReaderTextHighlightSpan(color) }
    }
    searchRanges.forEach { range ->
        applyExclusiveBackgroundSpan(spannable, range) { color -> ReaderSearchHighlightSpan(color) }
    }
    return spannable
}

/** Apply or clear only search highlight spans; leaves annotation and plain background spans alone. */
fun CharSequence.withSearchHighlightSpans(
    searchRanges: List<ReaderTextHighlightRange>,
): CharSequence {
    if (isEmpty()) return this
    val spannable = SpannableString(this)
    spannable.getSpans(0, spannable.length, ReaderSearchHighlightSpan::class.java).forEach {
        spannable.removeSpan(it)
    }
    searchRanges.forEach { range ->
        applyExclusiveBackgroundSpan(spannable, range) { color -> ReaderSearchHighlightSpan(color) }
    }
    return spannable
}

private inline fun applyExclusiveBackgroundSpan(
    spannable: SpannableString,
    range: ReaderTextHighlightRange,
    spanFactory: (color: Int) -> BackgroundColorSpan,
) {
    val start = range.start.coerceIn(0, spannable.length)
    val end = range.end.coerceIn(0, spannable.length)
    if (start < end) {
        spannable.setSpan(
            spanFactory(range.color),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}
