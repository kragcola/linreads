package dev.readflow.render.epub

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
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
 * EPUB native reflow engine (v4 §12.3 ADR-EPUB-Engine).
 * ZipFile + jsoup → flat paragraph list → RecyclerView continuous scroll.
 * No WebView / epubjs / CFI.
 * Locator = Section(spineIndex, elementIndex=flatPos, charOffset=0).
 */
class EpubReflowEngine(private val context: Context) : ReaderEngine {

    override val id = "epub-reflow"
    override val format = BookFormat.EPUB
    override val priority = 0
    override val supportsSearch = false

    private val _pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(Locator(LocatorStrategy.Unknown))
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private var paras: List<EpubPara> = emptyList()
    private var fontSizeSp: Float = 18f
    private var recyclerView: RecyclerView? = null

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "epub_${uri.hashCode()}.epub")
        if (!tmp.exists()) {
            context.contentResolver.openInputStream(uri)?.use { src ->
                tmp.outputStream().use { dst -> src.copyTo(dst) }
            }
        }
        paras = EpubParser().parse(tmp)
        withContext(Dispatchers.Main) { _pageCount.value = paras.size }
        _currentLocator.value
    }

    override fun createView(): View {
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = EpubParaAdapter(paras, fontSizeSp)
            setBackgroundColor(if (night) Color.rgb(0x2A, 0x26, 0x20) else Color.rgb(0xED, 0xE6, 0xD6))
            clipToPadding = false
            val padV = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = reportProgression(rv)
            })
        }.also { recyclerView = it }
    }

    private fun reportProgression(rv: RecyclerView) {
        val total = paras.size.takeIf { it > 0 } ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val para = paras.getOrNull(first) ?: return
        val ratio = first.toFloat() / total
        _currentLocator.value = Locator(
            strategy = LocatorStrategy.Section(para.spineIndex, first, 0),
            progression = ratio,
            totalProgression = ratio,
        )
    }

    override suspend fun goTo(locator: Locator) {
        val idx = (locator.strategy as? LocatorStrategy.Section)?.elementIndex ?: 0
        withContext(Dispatchers.Main) {
            (recyclerView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(idx, 0)
        }
    }

    override suspend fun close() { recyclerView = null; paras = emptyList() }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        (recyclerView?.adapter as? EpubParaAdapter)?.updateFontSize(sp)
    }

    override suspend fun setMode(mode: ReadingMode) { /* CONTINUOUS only in v4 lite */ }
}
