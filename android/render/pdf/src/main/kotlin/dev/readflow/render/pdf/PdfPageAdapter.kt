package dev.readflow.render.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.util.SparseArray
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * Renders one PDF page per row. Bitmaps are cached lazily on first bind;
 * eviction deferred to [close]. PdfRenderer is not thread-safe, so all
 * renders happen on the main thread via [renderPage].
 */
internal class PdfPageAdapter(
    private val renderer: PdfRenderer,
    private val context: Context,
) : RecyclerView.Adapter<PdfPageAdapter.VH>() {

    private val cache = SparseArray<Bitmap>()

    class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            adjustViewBounds = true
        }.let { VH(it) }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.iv.setImageBitmap(
            cache[position] ?: renderPage(position)?.also { cache.put(position, it) }
        )
    }

    override fun getItemCount(): Int = renderer.pageCount

    private fun renderPage(idx: Int): Bitmap? {
        val page = renderer.openPage(idx)
        val w = context.resources.displayMetrics.widthPixels
        val h = (w * page.height.toFloat() / page.width).toInt()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
        }
    }

    fun recycle() {
        for (i in 0 until cache.size()) cache.valueAt(i).recycle()
        cache.clear()
    }
}
