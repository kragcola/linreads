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
    fun setTextAnnotations(annotations: List<ReaderTextAnnotation>)
}

class ReaderTextHighlightSpan(color: Int) : BackgroundColorSpan(color)

fun CharSequence.withTextHighlightSpans(ranges: List<ReaderTextHighlightRange>): CharSequence {
    if (isEmpty()) return this
    val spannable = SpannableString(this)
    spannable.getSpans(0, spannable.length, ReaderTextHighlightSpan::class.java).forEach {
        spannable.removeSpan(it)
    }
    ranges.forEach { range ->
        val start = range.start.coerceIn(0, spannable.length)
        val end = range.end.coerceIn(0, spannable.length)
        if (start < end) {
            spannable.setSpan(
                ReaderTextHighlightSpan(range.color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return spannable
}
