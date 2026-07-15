package dev.readflow.render.epub

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import dev.readflow.render.api.ReaderTextHighlightRange
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSizeResolver
import io.noties.markwon.image.ImageSize

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
    fullPageHrefs: Set<String> = emptySet(),
): SpannableStringBuilder {
    val sb = SpannableStringBuilder(flow.text)
    flow.segments.forEach { seg ->
        when (val block = seg.block) {
            is EpubDisplayBlock.Text -> applyTextSpans(sb, seg, block, style, onLinkClick)
            is EpubDisplayBlock.Image -> applyImageSpan(
                sb = sb,
                seg = seg,
                block = block,
                style = style,
                markwonTheme = markwonTheme,
                imageLoader = imageLoader,
                imageSizeResolver = imageSizeResolver,
                isFullPage = block.href in fullPageHrefs,
            )
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
) {
    val start = seg.layoutStart
    val end = seg.layoutEnd
    if (end <= start) return

    // Heading: larger, bold, centered.
    val headingBoost = if (block.blockStyle.headingStyleResolved) null else when (block.headingLevel) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        else -> null
    }
    if (headingBoost != null) {
        sb.setSpan(RelativeSizeSpan(headingBoost), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val alignment = block.blockStyle.textAlign?.toLayoutAlignment()
        ?: if (headingBoost != null) Layout.Alignment.ALIGN_CENTER else null
    if (alignment != null) {
        sb.setSpan(
            AlignmentSpan.Standard(alignment),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    // Indent / blockquote leading margin (uniform, all lines) + body first-line indent (Moon+ 首行缩进).
    val cssMarginLeftPx = block.blockStyle.margin.left.toPx(style)
    val cssPaddingLeftPx = block.blockStyle.padding.left.toPx(style)
    val hasCssLeftInset = cssMarginLeftPx > 0 || cssPaddingLeftPx > 0
    val blockIndentPx = block.indentLevel * (24 * style.density).toInt() +
        if (block.kind == EpubTextKind.Blockquote && !hasCssLeftInset) (18 * style.density).toInt() else 0
    val restLeadingPx = blockIndentPx + cssMarginLeftPx + cssPaddingLeftPx
    // First-line indent applies only to plain body paragraphs: not headings (centered), not lists/
    // blockquotes/preformatted (own indent), not already block-indented. Matches Moon+ where the indent
    // IS the paragraph delimiter — headings & special blocks stay flush.
    val readerFirstLineIndentPx = if (
        headingBoost == null &&
        block.kind == EpubTextKind.Body &&
        block.indentLevel == 0 &&
        style.firstLineIndentPx > 0
    ) style.firstLineIndentPx else 0
    val cssTextIndentPx = block.blockStyle.textIndent?.toPx(style)
    val firstLineIndentPx = cssTextIndentPx ?: readerFirstLineIndentPx
    if (restLeadingPx > 0 || firstLineIndentPx != 0) {
        sb.setSpan(
            LeadingMarginSpan.Standard(restLeadingPx + firstLineIndentPx, restLeadingPx),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    val boxSpan = EpubBlockBoxSpan.from(block.blockStyle, style, start, end)
    if (boxSpan != null) {
        sb.setSpan(boxSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    block.blockStyle.lineHeightMultiplier?.let { multiplier ->
        sb.setSpan(EpubCssLineHeightSpan(multiplier), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    if (block.kind == EpubTextKind.Preformatted || block.kind == EpubTextKind.Table || block.isCodeBlock) {
        sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // Inline style spans (bold/italic/code/sup/sub) — offsets are paragraph-local = segment-local.
    block.styleSpans.forEach { span ->
        val s = (start + span.start)
        val e = (start + span.end)
        if (span.start < 0 || s >= e || e > end) return@forEach
        val pairedBackground = block.styleSpans.lastOrNull {
            it.style == EpubTextStyle.BackgroundColor && it.start == span.start && it.end == span.end
        }?.color ?: block.blockStyle.backgroundColor
        val pairedForeground = block.styleSpans.lastOrNull {
            it.style == EpubTextStyle.ForegroundColor && it.start == span.start && it.end == span.end
        }?.color
        val safeColors = epubResolveSafeCssColors(pairedForeground, pairedBackground, style.inkColor)
        val safeForeground = if (span.style == EpubTextStyle.ForegroundColor) {
            safeColors.foreground
        } else {
            null
        }
        val safeBackground = if (span.style == EpubTextStyle.BackgroundColor) {
            safeColors.background
        } else {
            null
        }
        styleToAndroid(span, safeForeground, safeBackground).forEach {
            sb.setSpan(it, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
    style: EpubFlowStyle,
    markwonTheme: MarkwonTheme,
    imageLoader: AsyncDrawableLoader,
    imageSizeResolver: ImageSizeResolver,
    isFullPage: Boolean,
) {
    val start = seg.layoutStart
    val end = seg.layoutEnd
    if (end <= start) return
    // Markwon AsyncDrawable: decodes async into the attached TextView, sized by the resolver. No
    // eager bitmap retention (审计 M7). Anchored on the single U+FFFC char — never changes length.
    val styledResolver = if (isFullPage) {
        imageSizeResolver
    } else {
        EpubCssImageSizeResolver(imageSizeResolver, block.style, style)
    }
    val imageSize = if (isFullPage) null else block.style.toImageSize(style)
    val asyncDrawable = AsyncDrawable(block.href, imageLoader, styledResolver, imageSize)
    val span = AsyncDrawableSpan(markwonTheme, asyncDrawable, AsyncDrawableSpan.ALIGN_CENTER, false)
    sb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    block.style.alignment?.toLayoutAlignment()?.let { alignment ->
        sb.setSpan(AlignmentSpan.Standard(alignment), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private fun styleToAndroid(
    span: EpubTextStyleSpan,
    safeForeground: Int?,
    safeBackground: Int?,
): List<Any> = when (span.style) {
    EpubTextStyle.Bold -> listOf(StyleSpan(Typeface.BOLD))
    EpubTextStyle.Italic -> listOf(StyleSpan(Typeface.ITALIC))
    EpubTextStyle.Code -> listOf(TypefaceSpan("monospace"))
    EpubTextStyle.Superscript -> listOf(SuperscriptSpan(), RelativeSizeSpan(0.8f))
    EpubTextStyle.Subscript -> listOf(SubscriptSpan(), RelativeSizeSpan(0.8f))
    EpubTextStyle.Underline -> listOf(UnderlineSpan())
    EpubTextStyle.Strikethrough -> listOf(StrikethroughSpan())
    EpubTextStyle.ForegroundColor -> safeForeground?.let { listOf(ForegroundColorSpan(it)) }.orEmpty()
    EpubTextStyle.BackgroundColor -> safeBackground?.let { listOf(BackgroundColorSpan(it)) }.orEmpty()
    EpubTextStyle.RelativeSize -> span.scale?.let { listOf(RelativeSizeSpan(it)) }.orEmpty()
}

internal class EpubBlockBoxSpan private constructor(
    val backgroundColor: Int?,
    val borders: EpubCssBorders,
    val verticalInsetPx: Int,
    private val startOffset: Int,
    private val endOffset: Int,
    private val topInsetPx: Int,
    private val bottomInsetPx: Int,
    private val density: Float,
) : LineBackgroundSpan, LineHeightSpan {

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        if (start <= startOffset && startOffset < end) {
            fm.top -= topInsetPx
            fm.ascent -= topInsetPx
        }
        if (start < endOffset && endOffset <= end) {
            fm.bottom += bottomInsetPx
            fm.descent += bottomInsetPx
        }
    }

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth
        val oldPathEffect = paint.pathEffect
        backgroundColor?.let { color ->
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        }
        borders.left?.let { drawBorder(canvas, paint, it, left.toFloat(), top.toFloat(), left.toFloat(), bottom.toFloat()) }
        borders.right?.let { drawBorder(canvas, paint, it, right.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()) }
        if (start <= startOffset && startOffset < end) {
            borders.top?.let { drawBorder(canvas, paint, it, left.toFloat(), top.toFloat(), right.toFloat(), top.toFloat()) }
        }
        if (start < endOffset && endOffset <= end) {
            borders.bottom?.let { drawBorder(canvas, paint, it, left.toFloat(), bottom.toFloat(), right.toFloat(), bottom.toFloat()) }
        }
        paint.color = oldColor
        paint.style = oldStyle
        paint.strokeWidth = oldStrokeWidth
        paint.pathEffect = oldPathEffect
    }

    private fun drawBorder(
        canvas: Canvas,
        paint: Paint,
        border: EpubCssBorder,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        val width = when (border.width.unit) {
            EpubCssUnit.Px -> border.width.value * density
            EpubCssUnit.Em -> border.width.value * 16f * density
            EpubCssUnit.Percent -> border.width.value / 100f * 16f * density
        }.coerceAtLeast(1f)
        paint.color = border.color ?: return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.pathEffect = when (border.style) {
            EpubCssBorderStyle.Dashed -> DashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f)
            EpubCssBorderStyle.Dotted -> DashPathEffect(floatArrayOf(width, width * 1.5f), 0f)
            EpubCssBorderStyle.Solid, EpubCssBorderStyle.Double -> null
        }
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    companion object {
        fun from(
            blockStyle: EpubBlockStyle,
            flowStyle: EpubFlowStyle,
            startOffset: Int,
            endOffset: Int,
        ): EpubBlockBoxSpan? {
            val top = (blockStyle.margin.top.toPx(flowStyle) + blockStyle.padding.top.toPx(flowStyle)).coerceAtLeast(0)
            val bottom = (blockStyle.margin.bottom.toPx(flowStyle) + blockStyle.padding.bottom.toPx(flowStyle)).coerceAtLeast(0)
            val hasBorder = blockStyle.borders != EpubCssBorders()
            val safeBackground = epubSafeCssBackground(blockStyle.backgroundColor, flowStyle.inkColor)
            if (safeBackground == null && !hasBorder && top == 0 && bottom == 0) return null
            return EpubBlockBoxSpan(
                backgroundColor = safeBackground,
                borders = blockStyle.borders,
                verticalInsetPx = top + bottom,
                startOffset = startOffset,
                endOffset = endOffset,
                topInsetPx = top,
                bottomInsetPx = bottom,
                density = flowStyle.density,
            )
        }
    }
}

private class EpubCssLineHeightSpan(
    private val multiplier: Float,
) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val current = fm.descent - fm.ascent
        val target = (current * multiplier).toInt().coerceAtLeast(current)
        val extra = target - current
        fm.ascent -= extra / 2
        fm.top -= extra / 2
        fm.descent += extra - extra / 2
        fm.bottom += extra - extra / 2
    }
}

private class EpubCssImageSizeResolver(
    private val delegate: ImageSizeResolver,
    private val imageStyle: EpubImageStyle,
    private val flowStyle: EpubFlowStyle,
) : ImageSizeResolver() {
    override fun resolveImageSize(drawable: AsyncDrawable): android.graphics.Rect {
        val resolved = delegate.resolveImageSize(drawable)
        val maxWidth = imageStyle.maxWidth?.toPx(flowStyle, flowStyle.columnWidthPx) ?: flowStyle.columnWidthPx
        val maxHeight = imageStyle.maxHeight?.toPx(flowStyle, flowStyle.imageMaxHeightPx) ?: flowStyle.imageMaxHeightPx
        if (resolved.width() <= maxWidth && resolved.height() <= maxHeight) return resolved
        val scale = minOf(
            maxWidth.toFloat() / resolved.width().coerceAtLeast(1),
            maxHeight.toFloat() / resolved.height().coerceAtLeast(1),
        )
        return android.graphics.Rect(
            0,
            0,
            (resolved.width() * scale).toInt().coerceAtLeast(1),
            (resolved.height() * scale).toInt().coerceAtLeast(1),
        )
    }
}

private fun EpubImageStyle.toImageSize(style: EpubFlowStyle): ImageSize? {
    val widthDimension = width?.toImageDimension(style)
    val heightDimension = height?.toImageDimension(style)
    return if (widthDimension == null && heightDimension == null) null else ImageSize(widthDimension, heightDimension)
}

private fun EpubCssLength.toImageDimension(style: EpubFlowStyle): ImageSize.Dimension =
    when (unit) {
        EpubCssUnit.Px -> ImageSize.Dimension(value * style.density, "")
        EpubCssUnit.Em -> ImageSize.Dimension(value, "em")
        EpubCssUnit.Percent -> ImageSize.Dimension(value, "%")
    }

private fun EpubCssLength?.toPx(style: EpubFlowStyle, percentBasisPx: Int = style.columnWidthPx): Int =
    when (this?.unit) {
        EpubCssUnit.Px -> (value * style.density).toInt()
        EpubCssUnit.Em -> (value * style.fontSizeSp * style.density).toInt()
        EpubCssUnit.Percent -> (value / 100f * percentBasisPx).toInt()
        null -> 0
    }

private fun EpubTextAlign.toLayoutAlignment(): Layout.Alignment =
    when (this) {
        EpubTextAlign.Start, EpubTextAlign.Justify -> Layout.Alignment.ALIGN_NORMAL
        EpubTextAlign.Center -> Layout.Alignment.ALIGN_CENTER
        EpubTextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
    }

internal data class EpubSafeCssColors(val foreground: Int, val background: Int?)

internal fun epubResolveSafeCssColors(
    authorForeground: Int?,
    authorBackground: Int?,
    readerInkColor: Int,
): EpubSafeCssColors {
    val safeBackground = authorBackground?.takeIf { epubColorContrastRatio(readerInkColor, it) >= 4.5 }
    val safeForeground = if (
        safeBackground != null &&
        authorForeground != null &&
        epubColorContrastRatio(authorForeground, safeBackground) >= 4.5
    ) {
        authorForeground
    } else {
        readerInkColor
    }
    return EpubSafeCssColors(safeForeground, safeBackground)
}

internal fun epubSafeCssForeground(authorColor: Int?, backgroundColor: Int?, readerInkColor: Int): Int =
    epubResolveSafeCssColors(authorColor, backgroundColor, readerInkColor).foreground

internal fun epubSafeCssBackground(authorColor: Int?, foregroundColor: Int): Int? {
    return epubResolveSafeCssColors(null, authorColor, foregroundColor).background
}

private fun epubColorContrastRatio(first: Int, second: Int): Double {
    if ((first ushr 24) < 0xFF || (second ushr 24) < 0xFF) return 0.0
    val lighter = maxOf(epubRelativeLuminance(first), epubRelativeLuminance(second))
    val darker = minOf(epubRelativeLuminance(first), epubRelativeLuminance(second))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun epubRelativeLuminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val value = ((color ushr shift) and 0xFF) / 255.0
        return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
