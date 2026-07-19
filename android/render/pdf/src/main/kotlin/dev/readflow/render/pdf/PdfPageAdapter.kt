package dev.readflow.render.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Renders one PDF page per row. Bitmaps are cached lazily on first bind and
 * kept within a small page window. PdfRenderer is not thread-safe, so all
 * renders happen on the main thread via [renderPage].
 *
 * Overlay providers:
 * - [highlightProvider] — transient search rectangles
 * - [annotationProvider] — persistent annotation rectangles
 * - [selectionProvider] — live selection rectangles
 *
 * Layers are independent; clearing one never clears the others.
 */
internal class PdfPageAdapter(
    private val renderer: PdfRenderer,
    private val context: Context,
    private val highlightProvider: (pageIndex: Int) -> List<PdfRect> = { emptyList() },
    private val annotationProvider: (pageIndex: Int) -> List<PdfColoredRect> = { emptyList() },
    private val selectionProvider: (pageIndex: Int) -> List<PdfRect> = { emptyList() },
    private val selectionListener: PdfPageSelectionListener? = null,
    private val contentDescriptionProvider: ((pageIndex: Int) -> String)? = null,
) : RecyclerView.Adapter<PdfPageAdapter.VH>() {

    private val boundHosts = Collections.newSetFromMap(WeakHashMap<PdfSearchPageHost, Boolean>())
    private val bitmapAttachments = PdfBitmapAttachmentRegistry<Bitmap, android.widget.ImageView>(
        attachedValue = { imageView -> (imageView.drawable as? BitmapDrawable)?.bitmap },
        clearAttachment = { imageView -> imageView.setImageDrawable(null) },
    )
    private val cachePolicy = pdfPageCachePolicy(
        pageWidthPx = context.resources.displayMetrics.widthPixels,
        pageHeightPx = firstPageRenderedHeightPx(),
    )
    private val cache = PdfPageBitmapCache<Bitmap>(
        maxEntries = cachePolicy.maxPages,
        release = { bitmap -> bitmapAttachments.release(bitmap) { it.recycle() } },
    )

    class VH(val host: PdfSearchPageHost) : RecyclerView.ViewHolder(host)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        PdfSearchPageHost(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // The adapter row is wrap-content; let the bitmap-backed ImageView expose its
            // intrinsic aspect height instead of resolving MATCH_PARENT against an unmeasured row.
            imageView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            this.selectionListener = selectionListener
        }.let { VH(it) }

    override fun onBindViewHolder(holder: VH, position: Int) {
        boundHosts.add(holder.host)
        holder.host.tag = position
        holder.host.selectionListener = selectionListener
        bitmapAttachments.track(holder.host.imageView)
        holder.host.imageView.setImageBitmap(
            cache.getOrPut(position) { renderPage(position) },
        )
        cache.retainAround(position, cachePolicy.radius)
        applyOverlays(holder.host, position)
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onViewRecycled(holder: VH) {
        boundHosts.remove(holder.host)
        holder.host.tag = null
        holder.host.selectionListener = null
        bitmapAttachments.untrack(holder.host.imageView)
        holder.host.imageView.setImageDrawable(null)
        holder.host.clearAllPaint()
    }

    /** Rebind transient search paint on currently bound rows (selected hit changed). */
    fun notifySearchHighlightChanged() {
        boundHosts.forEach { host ->
            val position = host.tag as? Int ?: return@forEach
            host.setSearchRects(highlightProvider(position))
            host.rebindHighlightPaint()
        }
    }

    /** Rebind all overlay layers (search + annotation + selection) after bind/zoom/selection. */
    fun notifyAllOverlaysChanged() {
        boundHosts.forEach { host ->
            val position = host.tag as? Int ?: return@forEach
            applyOverlays(host, position)
        }
    }

    private fun applyOverlays(host: PdfSearchPageHost, position: Int) {
        host.setSearchRects(highlightProvider(position))
        host.setAnnotationRects(annotationProvider(position))
        host.setSelectionRects(selectionProvider(position))
        val total = renderer.pageCount
        val description = contentDescriptionProvider?.invoke(position)
            ?: "第 ${position + 1} 页，共 $total 页"
        host.contentDescription = description
        host.imageView.contentDescription = description
        host.rebindHighlightPaint()
    }

    private fun renderPage(idx: Int): Bitmap {
        val page = renderer.openPage(idx)
        val w = context.resources.displayMetrics.widthPixels
        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
        return try {
            // Keep the legacy adapter compatible with Android 16 PdfRenderer as well.
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    fun recycle() {
        cache.clear()
        boundHosts.forEach {
            it.imageView.setImageDrawable(null)
            it.clearAllPaint()
            it.selectionListener = null
        }
        boundHosts.clear()
    }

    private fun firstPageRenderedHeightPx(): Int {
        if (renderer.pageCount <= 0) return context.resources.displayMetrics.heightPixels
        val page = renderer.openPage(0)
        return try {
            val width = context.resources.displayMetrics.widthPixels
            (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
        } finally {
            page.close()
        }
    }
}
