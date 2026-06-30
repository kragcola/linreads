package dev.readflow.render.epub

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import dev.readflow.render.api.ReaderTextHighlightRange
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSizeResolver

/**
 * Styling inputs for the continuous-flow Spannable. Pulled from engine state so font/spacing/theme
 * changes just rebuild the Spannable.
 */
internal data class EpubFlowStyle(
    val fontSizeSp: Float,
    val lineSpacingMultiplier: Float,
    val inkColor: Int,
    val typeface: Typeface,
    val columnWidthPx: Int,
    val imageMaxHeightPx: Int,
    val density: Float,
    /** First-line indent for body paragraphs (Moon+ 首行缩进, ~2 CJK char widths). 0 disables. */
    val firstLineIndentPx: Int = 0,
)

/**
 * Builds the styled [SpannableStringBuilder] for one chapter from its [flow]. The produced text is
 * byte-identical to [flow].text so [EpubChapterFlow.paragraphAtOffset] stays exact. Headings, style
 * spans, links, indents and inline images are applied over each segment's `[layoutStart,layoutEnd)`.
 *
 * @param markwonTheme theme for AsyncDrawableSpan (image vertical alignment/metrics).
 * @param imageLoader Markwon loader that decodes images async into the attached TextView.
 * @param imageSizeResolver scales the loaded image to the column.
 * @param highlightRanges absolute-layout-offset highlight ranges (from annotations) to paint.
 */
internal fun epubBuildFlowSpannable(
    context: Context,
    flow: EpubChapterFlow,
    style: EpubFlowStyle,
    markwonTheme: MarkwonTheme,
    imageLoader: AsyncDrawableLoader,
    imageSizeResolver: ImageSizeResolver,
    onLinkClick: (EpubTextLink) -> Unit,
    highlightRanges: List<ReaderTextHighlightRange> = emptyList(),
): SpannableStringBuilder {
    val sb = SpannableStringBuilder(flow.text)
    val headingPaddingPx = (8 * style.density).toInt()

    flow.segments.forEach { seg ->
        when (val block = seg.block) {
            is EpubDisplayBlock.Text -> applyTextSpans(sb, seg, block, style, onLinkClick, headingPaddingPx)
            is EpubDisplayBlock.Image -> applyImageSpan(sb, seg, block, markwonTheme, imageLoader, imageSizeResolver)
            is EpubDisplayBlock.Break -> Unit
        }
    }

    highlightRanges.forEach { range ->
        val s = range.start.coerceIn(0, sb.length)
        val e = range.end.coerceIn(s, sb.length)
        if (e > s) sb.setSpan(BackgroundColorSpan(range.color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return sb
}

private fun applyTextSpans(
    sb: SpannableStringBuilder,
    seg: EpubFlowSegment,
    block: EpubDisplayBlock.Text,
    style: EpubFlowStyle,
    onLinkClick: (EpubTextLink) -> Unit,
    headingPaddingPx: Int,
) {
    val start = seg.layoutStart
    val end = seg.layoutEnd
    if (end <= start) return

    // Heading: larger, bold, centered.
    val headingBoost = when (block.headingLevel) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        else -> null
    }
    if (headingBoost != null) {
        sb.setSpan(RelativeSizeSpan(headingBoost), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    // Indent / blockquote leading margin (uniform, all lines) + body first-line indent (Moon+ 首行缩进).
    val blockIndentPx = block.indentLevel * (24 * style.density).toInt() +
        if (block.kind == EpubTextKind.Blockquote) (18 * style.density).toInt() else 0
    // First-line indent applies only to plain body paragraphs: not headings (centered), not lists/
    // blockquotes/preformatted (own indent), not already block-indented. Matches Moon+ where the indent
    // IS the paragraph delimiter — headings & special blocks stay flush.
    val firstLineIndentPx = if (
        headingBoost == null &&
        block.kind == EpubTextKind.Body &&
        block.indentLevel == 0 &&
        style.firstLineIndentPx > 0
    ) style.firstLineIndentPx else 0
    if (blockIndentPx > 0 || firstLineIndentPx > 0) {
        sb.setSpan(
            LeadingMarginSpan.Standard(blockIndentPx + firstLineIndentPx, blockIndentPx),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    if (block.kind == EpubTextKind.Preformatted || block.kind == EpubTextKind.Table || block.isCodeBlock) {
        sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // Inline style spans (bold/italic/code/sup/sub) — offsets are paragraph-local = segment-local.
    block.styleSpans.forEach { span ->
        val s = (start + span.start)
        val e = (start + span.end)
        if (span.start < 0 || s >= e || e > end) return@forEach
        styleToAndroid(span.style).forEach { sb.setSpan(it, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    // Links.
    block.links.forEach { link ->
        val s = (start + link.start)
        val e = (start + link.end)
        if (link.start < 0 || s >= e || e > end) return@forEach
        sb.setSpan(
            object : android.text.style.ClickableSpan() {
                override fun onClick(widget: android.view.View) = onLinkClick(link)
                override fun updateDrawState(ds: android.text.TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = style.inkColor
                    ds.isUnderlineText = true
                }
            },
            s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

private fun applyImageSpan(
    sb: SpannableStringBuilder,
    seg: EpubFlowSegment,
    block: EpubDisplayBlock.Image,
    markwonTheme: MarkwonTheme,
    imageLoader: AsyncDrawableLoader,
    imageSizeResolver: ImageSizeResolver,
) {
    val start = seg.layoutStart
    val end = seg.layoutEnd
    if (end <= start) return
    // Markwon AsyncDrawable: decodes async into the attached TextView, sized by the resolver. No
    // eager bitmap retention (审计 M7). Anchored on the single U+FFFC char — never changes length.
    val asyncDrawable = AsyncDrawable(block.href, imageLoader, imageSizeResolver, null)
    val span = AsyncDrawableSpan(markwonTheme, asyncDrawable, AsyncDrawableSpan.ALIGN_CENTER, false)
    sb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun styleToAndroid(style: EpubTextStyle): List<Any> = when (style) {
    EpubTextStyle.Bold -> listOf(StyleSpan(Typeface.BOLD))
    EpubTextStyle.Italic -> listOf(StyleSpan(Typeface.ITALIC))
    EpubTextStyle.Code -> listOf(TypefaceSpan("monospace"))
    EpubTextStyle.Superscript -> listOf(SuperscriptSpan(), RelativeSizeSpan(0.8f))
    EpubTextStyle.Subscript -> listOf(SubscriptSpan(), RelativeSizeSpan(0.8f))
}
