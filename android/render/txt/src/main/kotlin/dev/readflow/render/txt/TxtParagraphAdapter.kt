package dev.readflow.render.txt

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Renders one paragraph per row. 排版按设计文档 §1.3：宋体（serif 占位，思源宋体待打包）、
 * 18sp、行高 1.75、墨字（ink #2A2620 / 夜间 #D8CFBC）。§3.2 最大行宽 680dp，宽屏居中、
 * 两侧留纸边，不铺满。横向内边距舒适。
 */
class TxtParagraphAdapter(
    private val paragraphs: List<String>,
    private var fontSizeSp: Float,
    private var inkColor: Int = INK_DAY,
) : RecyclerView.Adapter<TxtParagraphAdapter.ParagraphHolder>() {

    class ParagraphHolder(val container: FrameLayout, val textView: TextView) :
        RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParagraphHolder {
        val density = parent.resources.displayMetrics.density
        val maxLineWidthPx = (MAX_LINE_WIDTH_DP * density).toInt()

        val tv = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ).apply { maxWidth = maxLineWidthPx }
            val padH = (24 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            setLineSpacing(0f, 1.75f)
            gravity = Gravity.START
            setTextColor(inkColor)
            typeface = Typeface.SERIF
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
        holder.textView.setTextColor(inkColor)
        holder.textView.text = paragraphs[position]
    }

    override fun getItemCount(): Int = paragraphs.size

    fun updateFontSize(sp: Float) {
        fontSizeSp = sp
        notifyDataSetChanged()
    }

    fun updateInkColor(color: Int) {
        inkColor = color
        notifyDataSetChanged()
    }

    companion object {
        val INK_DAY = Color.rgb(0x2A, 0x26, 0x20)
        val INK_NIGHT = Color.rgb(0xD8, 0xCF, 0xBC)
        const val MAX_LINE_WIDTH_DP = 680f
    }
}
