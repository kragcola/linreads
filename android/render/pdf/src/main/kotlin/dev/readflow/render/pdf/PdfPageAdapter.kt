package dev.readflow.render.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Renders one PDF page per row. Bitmaps are cached lazily on first bind and
 * kept within a small page window. PdfRenderer is not thread-safe, so all
 * renders happen on the main thread via [renderPage].
 */
internal class PdfPageAdapter(
    private val renderer: PdfRenderer,
    private val context: Context,
) : RecyclerView.Adapter<PdfPageAdapter.VH>() {

    private val boundViews = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    private val bitmapAttachments = PdfBitmapAttachmentRegistry<Bitmap, ImageView>(
        attachedValue = { imageView -> (imageView.drawable as? BitmapDrawable)?.bitmap },
        clearAttachment = { imageView -> imageView.setImageDrawable(null) },
    )
    private val cache = PdfPageBitmapCache<Bitmap>(
        maxEntries = PDF_PAGE_CACHE_MAX_PAGES,
        release = { bitmap -> bitmapAttachments.release(bitmap) { it.recycle() } },
    )

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
        boundViews.add(holder.iv)
        bitmapAttachments.track(holder.iv)
        holder.iv.setImageBitmap(
            cache.getOrPut(position) { renderPage(position) }
        )
        cache.retainAround(position, PDF_PAGE_CACHE_RADIUS)
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onViewRecycled(holder: VH) {
        boundViews.remove(holder.iv)
        bitmapAttachments.untrack(holder.iv)
        holder.iv.setImageDrawable(null)
    }

    private fun renderPage(idx: Int): Bitmap {
        val page = renderer.openPage(idx)
        val w = context.resources.displayMetrics.widthPixels
        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    fun recycle() {
        cache.clear()
        boundViews.forEach { it.setImageDrawable(null) }
        boundViews.clear()
    }

    private companion object {
        const val PDF_PAGE_CACHE_RADIUS = 1
        const val PDF_PAGE_CACHE_MAX_PAGES = PDF_PAGE_CACHE_RADIUS * 2 + 1
    }
}
