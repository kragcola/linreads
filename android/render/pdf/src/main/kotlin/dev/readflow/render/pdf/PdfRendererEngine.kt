package dev.readflow.render.pdf

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF engine using system [PdfRenderer] (v4 lite §5.5).
 * Continuous scroll; one bitmap per page, lazy-rendered and cached.
 * Locator = Page(index, total).
 */
class PdfRendererEngine(private val context: Context) : ReaderEngine {

    override val id = "pdf-renderer"
    override val format = BookFormat.PDF
    override val priority = 0
    override val supportsSearch = false

    private val _pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var recyclerView: RecyclerView? = null

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.Main) {
            _pageCount.value = total
            _tableOfContents.value = buildPageToc(total)
        }
        Locator(LocatorStrategy.Page(0, total), 0f, 0f)
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

    private fun buildPageToc(total: Int): List<TocEntry> =
        (0 until total).map { index ->
            TocEntry(
                title = "第 ${index + 1} 页",
                locator = Locator(
                    strategy = LocatorStrategy.Page(index, total),
                    totalProgression = if (total > 0) index.toFloat() / total else 0f,
                ),
            )
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
        val idx = (locator.strategy as? LocatorStrategy.Page)?.index ?: 0
        withContext(Dispatchers.Main) {
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
        }
    }

    override suspend fun close() {
        (recyclerView?.adapter as? PdfPageAdapter)?.recycle()
        pdfRenderer?.close()
        parcelFd?.close()
        pdfRenderer = null
        parcelFd = null
        recyclerView = null
        _tableOfContents.value = emptyList()
    }

    override suspend fun setFontSize(sp: Float) { /* not applicable for PDF */ }
    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        recyclerView?.setBackgroundColor(paperColor(mode, context.resources.configuration))
    }
    override suspend fun setMode(mode: ReadingMode) { /* CONTINUOUS only */ }

    private companion object {
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
