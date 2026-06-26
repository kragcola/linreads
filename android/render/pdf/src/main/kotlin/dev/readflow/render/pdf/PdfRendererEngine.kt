package dev.readflow.render.pdf

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.PagedReaderEngine
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.ZoomableReaderEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * PDF engine using system [PdfRenderer] (v4 lite §5.5).
 * Paged reading; one bitmap per page, lazy-rendered and cached.
 * Locator = Page(index, total).
 */
class PdfRendererEngine(private val context: Context) : PagedReaderEngine, ZoomableReaderEngine {

    override val id = "pdf-renderer"
    override val format = BookFormat.PDF
    override val priority = 0
    override val supportsSearch = false

    private val _pagingKind = MutableStateFlow(PagingKind.PAGED)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private val _zoomScale = MutableStateFlow(1f)
    override val zoomScale: StateFlow<Float> = _zoomScale.asStateFlow()

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var recyclerView: RecyclerView? = null
    private var pageRequestCallback: ((pageIndex: Int) -> Unit)? = null
    private val activePageViews = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    private var renderScope = pdfRenderScope()
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val pageBitmapAttachments = PdfBitmapAttachmentRegistry<Bitmap, ImageView>(
        attachedValue = { imageView -> (imageView.drawable as? BitmapDrawable)?.bitmap },
        clearAttachment = { imageView -> imageView.setImageDrawable(null) },
    )
    private var pageStore = createPageStore(renderScope)

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        recyclePageCacheOnMain()
        closeRendererResources()
        val tmp = File(context.cacheDir, "pdf_${uri.hashCode()}.pdf")
        if (!tmp.exists()) {
            context.contentResolver.openInputStream(uri)?.use { src ->
                tmp.outputStream().use { dst -> src.copyTo(dst) }
            }
        }
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        parcelFd = pfd
        val renderer = PdfRenderer(pfd)
        pdfRenderer = renderer
        val total = renderer.pageCount
        val toc = PdfOutlineParser.parse(tmp, total).ifEmpty { buildPdfFallbackToc(total) }
        val initialBitmap = if (total > 0) renderFirstPage() else null
        val initial = Locator(LocatorStrategy.Page(0, total), 0f, 0f)
        withContext(Dispatchers.Main) {
            ensureRenderScope()
            initialBitmap?.let { pageStore.put(0, it) }
            _pageCount.value = total
            _tableOfContents.value = toc
            _currentLocator.value = initial
            updateZoom(1f)
        }
        initial
    }

    override fun createView(): View {
        val renderer = pdfRenderer ?: error("openBook not called")
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = PdfPageAdapter(renderer, context)
            setBackgroundColor(paperColor(themeMode, resources.configuration))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = reportProgression(rv)
            })
        }.also { recyclerView = it }
    }

    override fun createPageView(pageIndex: Int): View {
        val total = _pageCount.value.coerceAtLeast(1)
        val safeIndex = pageIndex.coerceIn(0, total - 1)
        return ImageView(context).apply {
            tag = safeIndex
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(paperColor(themeMode, resources.configuration))
            contentDescription = "第 ${safeIndex + 1} 页，共 $total 页"
            pageStore.cached(safeIndex)?.let(::setImageBitmap)
            pageStore.load(safeIndex) { bitmap ->
                if (bitmap == null) return@load
                if (tag != safeIndex) return@load
                if (!activePageViews.contains(this)) return@load
                setImageBitmap(bitmap)
                applyPdfZoom(_zoomScale.value)
            }
            pageStore.prefetchAround(safeIndex, PDF_PAGE_CACHE_RADIUS, 0 until total)
            pageStore.retainAround(safeIndex, PDF_PAGE_CACHE_RADIUS)
        }.also(::trackPageView)
    }

    override fun setPageRequestCallback(callback: ((pageIndex: Int) -> Unit)?) {
        pageRequestCallback = callback
    }

    private fun reportProgression(rv: RecyclerView) {
        val total = _pageCount.value.takeIf { it > 0 } ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val ratio = first.toFloat() / total
        _currentLocator.value = Locator(
            strategy = LocatorStrategy.Page(first, total),
            progression = ratio,
            totalProgression = ratio,
        )
    }

    override suspend fun goTo(locator: Locator) {
        val total = _pageCount.value.coerceAtLeast(1)
        val idx = ((locator.strategy as? LocatorStrategy.Page)?.index ?: 0).coerceIn(0, total - 1)
        val target = Locator(
            strategy = LocatorStrategy.Page(idx, total),
            progression = idx.toFloat() / total,
            totalProgression = idx.toFloat() / total,
        )
        withContext(Dispatchers.Main) {
            val currentIdx = (_currentLocator.value.strategy as? LocatorStrategy.Page)?.index
            if (currentIdx != idx) {
                updateZoom(1f)
            }
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
            _currentLocator.value = target
            pageStore.prefetchAround(idx, PDF_PAGE_CACHE_RADIUS, 0 until total)
            pageStore.retainAround(idx, PDF_PAGE_CACHE_RADIUS)
            pageRequestCallback?.invoke(idx)
        }
    }

    override suspend fun close() {
        (recyclerView?.adapter as? PdfPageAdapter)?.recycle()
        recyclePageCacheOnMain()
        closeRendererResources()
        recyclerView = null
        pageRequestCallback = null
        activePageViews.clear()
        _zoomScale.value = 1f
        _tableOfContents.value = emptyList()
    }

    override suspend fun setFontSize(sp: Float) { /* not applicable for PDF */ }
    override suspend fun setZoom(scale: Float) {
        withContext(Dispatchers.Main) {
            updateZoom(scale.coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE))
        }
    }

    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        val paper = paperColor(mode, context.resources.configuration)
        recyclerView?.setBackgroundColor(paper)
        activePageViews.forEach { it.setBackgroundColor(paper) }
    }
    override suspend fun setMode(mode: ReadingMode) {
        _pagingKind.value = PagingKind.PAGED
    }

    private suspend fun renderFirstPage(): Bitmap? =
        withContext(renderDispatcher) { renderPageBlocking(0) }

    private fun renderPageBlocking(idx: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        val page = renderer.openPage(idx)
        return try {
            val width = context.resources.displayMetrics.widthPixels
            val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    private suspend fun closeRendererResources() {
        withContext(renderDispatcher) {
            pdfRenderer?.close()
            parcelFd?.close()
            pdfRenderer = null
            parcelFd = null
        }
    }

    private suspend fun recyclePageCacheOnMain() {
        withContext(Dispatchers.Main) {
            pageStore.clear()
        }
    }

    private fun trackPageView(imageView: ImageView) {
        activePageViews.add(imageView)
        pageBitmapAttachments.track(imageView)
        imageView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                val detached = view as? ImageView ?: return
                activePageViews.remove(detached)
                pageBitmapAttachments.untrack(detached)
                detached.setImageDrawable(null)
            }
        })
        imageView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            (view as? ImageView)?.applyPdfZoom(_zoomScale.value)
        }
        imageView.applyPdfZoom(_zoomScale.value)
    }

    private fun updateZoom(scale: Float) {
        _zoomScale.value = scale
        activePageViews.forEach { it.applyPdfZoom(scale) }
    }

    private fun ensureRenderScope() {
        if (renderScope.isActive) return
        renderScope = pdfRenderScope()
        pageStore = createPageStore(renderScope)
    }

    private fun createPageStore(scope: CoroutineScope): PdfPageBitmapStore<Bitmap> =
        PdfPageBitmapStore(
            scope = scope,
            renderDispatcher = renderDispatcher,
            maxEntries = PDF_PAGE_CACHE_MAX_PAGES,
            release = { bitmap -> pageBitmapAttachments.release(bitmap) { it.recycle() } },
            render = { pageIndex -> renderPageBlocking(pageIndex) },
        )

    private fun ImageView.applyPdfZoom(scale: Float) {
        if (scale <= MIN_ZOOM_SCALE) {
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageMatrix = Matrix()
            return
        }
        scaleType = ImageView.ScaleType.MATRIX
        imageMatrix = fittedPageMatrix(scale)
    }

    private fun ImageView.fittedPageMatrix(scale: Float): Matrix {
        val drawable = drawable
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        if (drawable == null || contentWidth <= 0 || contentHeight <= 0) {
            return Matrix().apply { setScale(scale, scale) }
        }

        val src = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat(),
        )
        val dst = RectF(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (paddingLeft + contentWidth).toFloat(),
            (paddingTop + contentHeight).toFloat(),
        )
        return Matrix().apply {
            setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
            postScale(scale, scale, dst.centerX(), dst.centerY())
        }
    }

    private companion object {
        const val MIN_ZOOM_SCALE = 1f
        const val MAX_ZOOM_SCALE = 4f
        const val PDF_PAGE_CACHE_RADIUS = 1
        const val PDF_PAGE_CACHE_MAX_PAGES = PDF_PAGE_CACHE_RADIUS * 2 + 1

        val PAPER_DAY = Color.rgb(0xED, 0xE6, 0xD6)
        val PAPER_NIGHT = Color.rgb(0x2A, 0x26, 0x20)
        val PAPER_LIGHT = Color.rgb(0xFA, 0xFA, 0xF8)
        val PAPER_SEPIA = Color.rgb(0xF5, 0xF0, 0xE8)

        private fun paperColor(mode: ThemeMode, configuration: Configuration): Int {
            val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return when (mode) {
                ThemeMode.LIGHT -> PAPER_LIGHT
                ThemeMode.DARK -> PAPER_NIGHT
                ThemeMode.SEPIA -> PAPER_SEPIA
                ThemeMode.SYSTEM -> if (systemNight) PAPER_NIGHT else PAPER_DAY
            }
        }
    }
}

private fun pdfRenderScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
