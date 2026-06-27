package dev.readflow.render.epub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Space
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
    inner class BreakVH(space: Space) : RecyclerView.ViewHolder(space)

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
            Space(parent.context).apply {
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
            is EpubDisplayBlock.Break -> Unit
        }
    }

    private fun bindText(holder: TextVH, block: EpubDisplayBlock.Text) {
        val density = holder.tv.resources.displayMetrics.density
        val horizontalPadding = (24 * density).toInt()
        val leadingPadding = horizontalPadding + (block.indentLevel * 24 * density).toInt() +
            if (block.kind == EpubTextKind.Blockquote) (18 * density).toInt() else 0
        val headingBoost = when (block.headingLevel) {
            1 -> 5f
            2 -> 3f
            3 -> 1.5f
            else -> 0f
        }

        if (block.isCodeBlock) {
            val codePaddingPx = (8 * density).toInt()
            holder.tv.setPadding(codePaddingPx, codePaddingPx, codePaddingPx, codePaddingPx)
            holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp * 0.9f)
            holder.tv.setLineSpacing(0f, lineSpacingMultiplier)
            holder.tv.setTextColor(inkColor)
            holder.tv.typeface = Typeface.MONOSPACE
            holder.tv.setHorizontallyScrolling(true)
            holder.tv.isHorizontalScrollBarEnabled = true
            holder.tv.setBackgroundColor(codeBlockBgColor)
            holder.tv.text = block.text.replace("\t", "    ")
        } else {
            holder.tv.setPadding(leadingPadding, (10 * density).toInt(), horizontalPadding, (10 * density).toInt())
            holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp + headingBoost)
            holder.tv.setLineSpacing(0f, lineSpacingMultiplier)
            holder.tv.setTextColor(inkColor)
            holder.tv.typeface = when {
                block.kind == EpubTextKind.Preformatted || block.kind == EpubTextKind.Table -> Typeface.MONOSPACE
                block.headingLevel != null -> Typeface.DEFAULT_BOLD
                else -> Typeface.SERIF
            }
            holder.tv.setHorizontallyScrolling(block.kind == EpubTextKind.Preformatted)
            holder.tv.isHorizontalScrollBarEnabled = block.kind == EpubTextKind.Preformatted
            holder.tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.tv.text = block.toSpannableText().withTextHighlightSpans(
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

        if (bitmap != null) {
            val newMaxWidth = calculateImageMaxWidth(
                holder.itemView.context,
                bitmap.width,
                bitmap.height
            )
            holder.image.maxWidth = newMaxWidth
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

    private fun EpubDisplayBlock.Text.toSpannableText(): CharSequence {
        if (links.isEmpty() && styleSpans.isEmpty()) return text
        val spannable = SpannableString(text)
        styleSpans.forEach { span ->
            if (span.start < 0 || span.end > text.length || span.start >= span.end) return@forEach
            span.toAndroidSpans().forEach { androidSpan ->
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

    private fun EpubTextStyleSpan.toAndroidSpans(): List<Any> =
        when (style) {
            EpubTextStyle.Bold -> listOf(StyleSpan(Typeface.BOLD))
            EpubTextStyle.Italic -> listOf(StyleSpan(Typeface.ITALIC))
            EpubTextStyle.Code -> listOf(TypefaceSpan("monospace"))
            EpubTextStyle.Superscript -> listOf(SuperscriptSpan(), RelativeSizeSpan(0.8f))
            EpubTextStyle.Subscript -> listOf(SubscriptSpan(), RelativeSizeSpan(0.8f))
        }
}
