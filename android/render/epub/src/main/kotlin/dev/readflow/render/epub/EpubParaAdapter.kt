package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.RelativeSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.withTextHighlightSpans

internal class EpubParaAdapter(
    private val blockCount: Int,
    private val blockProvider: (Int) -> EpubDisplayBlock?,
    private val imageLoader: (String) -> Bitmap?,
    private val onLinkClick: (EpubTextLink) -> Unit,
    private val onTextSelectionChanged: (paragraphIndex: Int, start: Int, end: Int) -> Unit,
    private val highlightRangesProvider: (paragraphIndex: Int) -> List<ReaderTextHighlightRange> = { emptyList() },
    private var fontSizeSp: Float,
    private var lineSpacingMultiplier: Float,
    private var inkColor: Int = INK_DAY,
    private var codeBlockBgColor: Int = CODE_BLOCK_BG_DAY,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    inner class TextVH(frame: FrameLayout, val tv: SelectionAwareTextView) : RecyclerView.ViewHolder(frame)
    inner class ImageVH(frame: FrameLayout, val image: ImageView) : RecyclerView.ViewHolder(frame)
    inner class BreakVH(val divider: View) : RecyclerView.ViewHolder(divider)

    override fun getItemViewType(position: Int): Int = when (blockAt(position)) {
        is EpubDisplayBlock.Text -> VIEW_TYPE_TEXT
        is EpubDisplayBlock.Image -> VIEW_TYPE_IMAGE
        is EpubDisplayBlock.Break -> VIEW_TYPE_BREAK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_IMAGE -> createImageViewHolder(parent)
            VIEW_TYPE_BREAK -> createBreakViewHolder(parent)
            else -> createTextViewHolder(parent)
        }

    private fun createTextViewHolder(parent: ViewGroup): TextVH {
        val d = parent.resources.displayMetrics.density
        val tv = SelectionAwareTextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ).apply { maxWidth = (680 * d).toInt() }
            setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt())
            setLineSpacing(0f, lineSpacingMultiplier)
            gravity = Gravity.START
            setTextColor(inkColor)
            typeface = Typeface.SERIF
            setTextIsSelectable(true)
        }
        val frame = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(tv)
        }
        return TextVH(frame, tv)
    }

    private fun createImageViewHolder(parent: ViewGroup): ImageVH {
        val d = parent.resources.displayMetrics.density
        val image = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            )
            adjustViewBounds = true
            maxWidth = (680 * d).toInt()
            maxHeight = (760 * d).toInt()
            minimumHeight = (48 * d).toInt()
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val frame = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(image)
        }
        return ImageVH(frame, image)
    }

    private fun createBreakViewHolder(parent: ViewGroup): BreakVH {
        val d = parent.resources.displayMetrics.density
        return BreakVH(
            View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (16 * d).toInt(),
                )
            },
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val block = blockAt(position)) {
            is EpubDisplayBlock.Text -> bindText(holder as TextVH, block)
            is EpubDisplayBlock.Image -> bindImage(holder as ImageVH, block)
            is EpubDisplayBlock.Break -> bindBreak(holder as BreakVH, block)
        }
    }

    private fun bindBreak(holder: BreakVH, block: EpubDisplayBlock.Break) {
        val density = holder.divider.resources.displayMetrics.density
        val params = holder.divider.layoutParams as RecyclerView.LayoutParams
        if (block.kind == EpubBreakKind.HorizontalRule) {
            params.height = density.toInt().coerceAtLeast(1)
            params.setMargins(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt(),
            )
            holder.divider.setBackgroundColor(Color.argb(0x4D, Color.red(inkColor), Color.green(inkColor), Color.blue(inkColor)))
        } else {
            params.height = (16 * density).toInt()
            params.setMargins(0, 0, 0, 0)
            holder.divider.setBackgroundColor(Color.TRANSPARENT)
        }
        holder.divider.layoutParams = params
    }

    private fun bindText(holder: TextVH, block: EpubDisplayBlock.Text) {
        val density = holder.tv.resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val cssMarginLeft = block.blockStyle.margin.left.toViewPx(holder.tv, fontSizeSp)
        val cssMarginRight = block.blockStyle.margin.right.toViewPx(holder.tv, fontSizeSp)
        val cssPaddingLeft = block.blockStyle.padding.left.toViewPx(holder.tv, fontSizeSp)
        val cssPaddingRight = block.blockStyle.padding.right.toViewPx(holder.tv, fontSizeSp)
        val cssTop = (block.blockStyle.margin.top.toViewPx(holder.tv, fontSizeSp) +
            block.blockStyle.padding.top.toViewPx(holder.tv, fontSizeSp)).coerceAtLeast(0)
        val cssBottom = (block.blockStyle.margin.bottom.toViewPx(holder.tv, fontSizeSp) +
            block.blockStyle.padding.bottom.toViewPx(holder.tv, fontSizeSp)).coerceAtLeast(0)
        val hasCssLeading = cssMarginLeft > 0 || cssPaddingLeft > 0
        val leadingPadding = horizontalPadding + cssMarginLeft + cssPaddingLeft +
            (block.indentLevel * 24 * density).toInt() +
            if (block.kind == EpubTextKind.Blockquote && !hasCssLeading) (18 * density).toInt() else 0
        val trailingPadding = horizontalPadding + cssMarginRight + cssPaddingRight
        val headingBoost = if (block.blockStyle.headingStyleResolved) 0f else when (block.headingLevel) {
            1 -> 5f
            2 -> 3f
            3 -> 1.5f
            else -> 0f
        }

        if (block.isCodeBlock) {
            val codePaddingPx = (8 * density).toInt()
            holder.tv.setPadding(codePaddingPx, codePaddingPx, codePaddingPx, codePaddingPx)
            holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * 0.9f)
            holder.tv.setLineSpacing(0f, block.blockStyle.lineHeightMultiplier ?: lineSpacingMultiplier)
            holder.tv.setTextColor(inkColor)
            holder.tv.typeface = Typeface.MONOSPACE
            holder.tv.setHorizontallyScrolling(true)
            holder.tv.isHorizontalScrollBarEnabled = true
            holder.tv.setBackgroundColor(codeBlockBgColor)
            holder.tv.gravity = block.blockStyle.textAlign.toGravity()
            holder.tv.text = block.toSpannableText(density)
        } else {
            holder.tv.setPadding(
                leadingPadding,
                (10 * density).toInt() + cssTop,
                trailingPadding,
                (10 * density).toInt() + cssBottom,
            )
            holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp + headingBoost)
            holder.tv.setLineSpacing(0f, block.blockStyle.lineHeightMultiplier ?: lineSpacingMultiplier)
            holder.tv.setTextColor(inkColor)
            holder.tv.typeface = when {
                block.kind == EpubTextKind.Preformatted || block.kind == EpubTextKind.Table -> Typeface.MONOSPACE
                block.headingLevel != null && !block.blockStyle.headingStyleResolved -> Typeface.DEFAULT_BOLD
                else -> Typeface.SERIF
            }
            holder.tv.setHorizontallyScrolling(block.kind == EpubTextKind.Preformatted)
            holder.tv.isHorizontalScrollBarEnabled = block.kind == EpubTextKind.Preformatted
            holder.tv.gravity = block.blockStyle.textAlign.toGravity(
                fallback = if (block.headingLevel != null) Gravity.CENTER_HORIZONTAL else Gravity.START,
            )
            holder.tv.setBackgroundColor(
                epubSafeCssBackground(block.blockStyle.backgroundColor, inkColor) ?: android.graphics.Color.TRANSPARENT,
            )
            holder.tv.text = block.toSpannableText(density).withTextHighlightSpans(
                highlightRangesProvider(block.paragraphIndex),
            )
        }
        holder.tv.onSelectionRangeChanged = { start, end ->
            onTextSelectionChanged(block.paragraphIndex, start, end)
        }
        holder.tv.movementMethod = if (block.links.isEmpty()) null else LinkMovementMethod.getInstance()
        holder.tv.linksClickable = block.links.isNotEmpty()
    }

    private fun bindImage(holder: ImageVH, block: EpubDisplayBlock.Image) {
        holder.image.contentDescription = block.altText?.takeIf { it.isNotBlank() } ?: "图片"
        val bitmap = imageLoader(block.href)
        holder.image.setImageBitmap(bitmap)

        val metrics = holder.image.resources.displayMetrics
        val availableWidth = (metrics.widthPixels - 48 * metrics.density).toInt().coerceAtLeast(1)
        val availableHeight = (760 * metrics.density).toInt()
        val params = holder.image.layoutParams as FrameLayout.LayoutParams
        params.gravity = block.style.alignment.toGravity(Gravity.CENTER_HORIZONTAL)
        params.width = block.style.width.toImageViewPx(holder.image, fontSizeSp, availableWidth)
            ?: FrameLayout.LayoutParams.MATCH_PARENT
        params.height = block.style.height.toImageViewPx(holder.image, fontSizeSp, availableHeight)
            ?: FrameLayout.LayoutParams.WRAP_CONTENT
        holder.image.layoutParams = params
        holder.image.maxHeight = block.style.maxHeight.toImageViewPx(holder.image, fontSizeSp, availableHeight)
            ?: availableHeight

        if (bitmap != null) {
            val intrinsicMaxWidth = calculateImageMaxWidth(
                holder.itemView.context,
                bitmap.width,
                bitmap.height
            )
            holder.image.maxWidth = block.style.maxWidth.toImageViewPx(holder.image, fontSizeSp, availableWidth)
                ?: intrinsicMaxWidth
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImageVH) {
            holder.image.setImageDrawable(null)
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = blockCount

    fun updateFontSize(sp: Float) { fontSizeSp = sp; notifyDataSetChanged() }

    fun updateLineSpacing(multiplier: Float) {
        lineSpacingMultiplier = multiplier
        notifyDataSetChanged()
    }

    fun updateInkColor(color: Int) {
        inkColor = color
        notifyDataSetChanged()
    }

    fun updateCodeBlockBgColor(color: Int) {
        codeBlockBgColor = color
        notifyDataSetChanged()
    }

    fun updateTextAnnotations() {
        notifyDataSetChanged()
    }

    companion object {
        private const val VIEW_TYPE_TEXT = 1
        private const val VIEW_TYPE_IMAGE = 2
        private const val VIEW_TYPE_BREAK = 3

        val INK_DAY = Color.rgb(0x2A, 0x26, 0x20)
        val INK_NIGHT = Color.rgb(0xD8, 0xCF, 0xBC)
        val CODE_BLOCK_BG_DAY = Color.rgb(0xF5, 0xF5, 0xF5)
        val CODE_BLOCK_BG_NIGHT = Color.rgb(0x2A, 0x2A, 0x2A)

        /**
         * 动态计算图片最大宽度
         * 横版图(aspect≥1.2)占92%，竖版图保持680dp
         */
        private fun calculateImageMaxWidth(
            context: android.content.Context,
            imageWidth: Int,
            imageHeight: Int
        ): Int {
            val metrics = context.resources.displayMetrics
            val density = metrics.density
            val screenWidthDp = metrics.widthPixels / density

            val aspectRatio = if (imageHeight > 0) {
                imageWidth.toFloat() / imageHeight.toFloat()
            } else {
                1.0f
            }

            return when {
                aspectRatio >= 1.2f -> {
                    (screenWidthDp * 0.92f * density).toInt()
                }
                else -> {
                    kotlin.math.min(
                        (680 * density).toInt(),
                        (screenWidthDp * 0.90f * density).toInt()
                    )
                }
            }
        }
    }

    private fun blockAt(position: Int): EpubDisplayBlock =
        blockProvider(position) ?: EpubDisplayBlock.Break(paragraphIndex = 0)

    private fun EpubDisplayBlock.Text.toSpannableText(density: Float): CharSequence {
        val textIndentPx = blockStyle.textIndent.toViewPxOrNull(fontSizeSp, 680, density)
        if (links.isEmpty() && styleSpans.isEmpty() && textIndentPx == null) return text
        val spannable = SpannableString(text)
        textIndentPx?.let { indent ->
            spannable.setSpan(
                LeadingMarginSpan.Standard(indent, 0),
                0,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        styleSpans.forEach { span ->
            if (span.start < 0 || span.end > text.length || span.start >= span.end) return@forEach
            val pairedBackground = styleSpans.lastOrNull {
                it.style == EpubTextStyle.BackgroundColor && it.start == span.start && it.end == span.end
            }?.color ?: blockStyle.backgroundColor
            val pairedForeground = styleSpans.lastOrNull {
                it.style == EpubTextStyle.ForegroundColor && it.start == span.start && it.end == span.end
            }?.color
            val safeColors = epubResolveSafeCssColors(pairedForeground, pairedBackground, inkColor)
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
            span.toAndroidSpans(safeForeground, safeBackground).forEach { androidSpan ->
                spannable.setSpan(androidSpan, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        links.forEach { link ->
            if (link.start < 0 || link.end > text.length || link.start >= link.end) return@forEach
            spannable.setSpan(
                object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        onLinkClick(link)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = inkColor
                        ds.isUnderlineText = true
                    }
                },
                link.start,
                link.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }

    private fun EpubTextStyleSpan.toAndroidSpans(safeForeground: Int?, safeBackground: Int?): List<Any> =
        when (style) {
            EpubTextStyle.Bold -> listOf(StyleSpan(Typeface.BOLD))
            EpubTextStyle.Italic -> listOf(StyleSpan(Typeface.ITALIC))
            EpubTextStyle.Code -> listOf(TypefaceSpan("monospace"))
            EpubTextStyle.Superscript -> listOf(SuperscriptSpan(), RelativeSizeSpan(0.8f))
            EpubTextStyle.Subscript -> listOf(SubscriptSpan(), RelativeSizeSpan(0.8f))
            EpubTextStyle.Underline -> listOf(UnderlineSpan())
            EpubTextStyle.Strikethrough -> listOf(StrikethroughSpan())
            EpubTextStyle.ForegroundColor -> safeForeground?.let { listOf(ForegroundColorSpan(it)) }.orEmpty()
            EpubTextStyle.BackgroundColor -> safeBackground?.let { listOf(BackgroundColorSpan(it)) }.orEmpty()
            EpubTextStyle.RelativeSize -> scale?.let { listOf(RelativeSizeSpan(it)) }.orEmpty()
            EpubTextStyle.FontFamily -> emptyList()
        }

    private fun EpubTextAlign?.toGravity(fallback: Int = Gravity.START): Int =
        when (this) {
            EpubTextAlign.Start, EpubTextAlign.Justify -> Gravity.START
            EpubTextAlign.Center -> Gravity.CENTER_HORIZONTAL
            EpubTextAlign.End -> Gravity.END
            null -> fallback
        }

    private fun EpubCssLength?.toViewPx(view: View, fontSizeSp: Float): Int =
        toViewPxOrNull(fontSizeSp, view.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels) ?: 0

    private fun EpubCssLength?.toImageViewPx(
        view: View,
        fontSizeSp: Float,
        percentageBasisPx: Int,
    ): Int? = toViewPxOrNull(fontSizeSp, percentageBasisPx, view.resources.displayMetrics.density)

    private fun EpubCssLength?.toViewPxOrNull(
        fontSizeSp: Float,
        percentageBasisPx: Int,
        density: Float = 1f,
    ): Int? =
        when (this?.unit) {
            EpubCssUnit.Px -> (value * density).toInt()
            EpubCssUnit.Em -> (value * fontSizeSp * density).toInt()
            EpubCssUnit.Percent -> (value / 100f * percentageBasisPx).toInt()
            null -> null
        }
}
