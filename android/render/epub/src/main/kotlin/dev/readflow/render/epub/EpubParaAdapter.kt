package dev.readflow.render.epub

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

internal class EpubParaAdapter(
    private val paras: List<EpubPara>,
    private var fontSizeSp: Float,
) : RecyclerView.Adapter<EpubParaAdapter.VH>() {

    inner class VH(frame: FrameLayout, val tv: TextView) : RecyclerView.ViewHolder(frame)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val night = (parent.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val tv = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ).apply { maxWidth = (680 * d).toInt() }
            setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt())
            setLineSpacing(0f, 1.75f)
            gravity = Gravity.START
            setTextColor(if (night) Color.rgb(0xD8, 0xCF, 0xBC) else Color.rgb(0x2A, 0x26, 0x20))
            typeface = Typeface.SERIF
        }
        val frame = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(tv)
        }
        return VH(frame, tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        holder.tv.text = paras[position].text
    }

    override fun getItemCount() = paras.size

    fun updateFontSize(sp: Float) { fontSizeSp = sp; notifyDataSetChanged() }
}
