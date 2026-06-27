package dev.readflow.render.txt

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.render.api.ReaderTextHighlightRange
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.withTextHighlightSpans

/**
 * Renders one paragraph per row. 排版按设计文档 §1.3：宋体（serif 占位，思源宋体待打包）、
 * 18sp、行高 1.75、墨字（ink #2A2620 / 夜间 #D8CFBC）。§3.2 最大行宽 680dp，宽屏居中、
 * 两侧留纸边，不铺满。横向内边距舒适。
 */
class TxtParagraphAdapter(
    private val paragraphCount: Int,
    private val paragraphProvider: (Int) -> String,
    private var fontSizeSp: Float,
    private var lineSpacingMultiplier: Float,
    private var inkColor: Int = INK_DAY,
    private var highlightRangesProvider: (paragraphIndex: Int) -> List<ReaderTextHighlightRange> = { emptyList() },
    private var onSelectionChanged: (paragraphIndex: Int, start: Int, end: Int) -> Unit = { _, _, _ -> },
    private var typeface: Typeface = Typeface.SERIF,
) : RecyclerView.Adapter<TxtParagraphAdapter.ParagraphHolder>() {

    class ParagraphHolder(val container: FrameLayout, val textView: SelectionAwareTextView) :
        RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParagraphHolder {
        val density = parent.resources.displayMetrics.density
        val maxLineWidthPx = (MAX_LINE_WIDTH_DP * density).toInt()

        val tv = SelectionAwareTextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ).apply { maxWidth = maxLineWidthPx }
            val padH = (24 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setLineSpacing(0f, lineSpacingMultiplier)
            gravity = Gravity.START
            setTextColor(inkColor)
            typeface = this@TxtParagraphAdapter.typeface
            setTextIsSelectable(true)
        }
        val container = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(tv)
        }
        return ParagraphHolder(container, tv)
    }

    override fun onBindViewHolder(holder: ParagraphHolder, position: Int) {
        holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        holder.textView.setLineSpacing(0f, lineSpacingMultiplier)
        holder.textView.setTextColor(inkColor)
        holder.textView.typeface = typeface
        holder.textView.text = paragraphProvider(position)
            .withTextHighlightSpans(highlightRangesProvider(position))
        holder.textView.onSelectionRangeChanged = { start, end ->
            onSelectionChanged(position, start, end)
        }
    }

    override fun getItemCount(): Int = paragraphCount

    fun updateFontSize(sp: Float) {
        fontSizeSp = sp
        notifyDataSetChanged()
    }

    fun updateLineSpacing(multiplier: Float) {
        lineSpacingMultiplier = multiplier
        notifyDataSetChanged()
    }

    fun updateInkColor(color: Int) {
        inkColor = color
        notifyDataSetChanged()
    }

    fun updateTypeface(tf: Typeface) {
        typeface = tf
        notifyDataSetChanged()
    }

    fun updateTextAnnotations() {
        notifyDataSetChanged()
    }

    companion object {
        val INK_DAY = Color.rgb(0x2A, 0x26, 0x20)
        val INK_NIGHT = Color.rgb(0xD8, 0xCF, 0xBC)
        const val MAX_LINE_WIDTH_DP = 680f
    }
}
