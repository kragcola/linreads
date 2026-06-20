package dev.readflow.render.txt

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

/**
 * Minimal TXT engine (v4 §5.3/§5.4). CONTINUOUS scroll via RecyclerView.
 * Minimal slice: reads the whole file as UTF-8 and splits into paragraphs; the
 * FileChannel 64KB streaming + ICU charset detection (§5.4) land later. Locator is
 * ByteOffset, byte-aligned because UTF-8 paragraph boundaries fall on char starts.
 */
class TxtVirtualPagerEngine(
    private val context: Context,
) : ReaderEngine {

    override val id: String = "txt-virtual-pager"
    override val format: BookFormat = BookFormat.TXT
    override val priority: Int = 0
    override val supportsSearch: Boolean = false

    private val _pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(
        Locator(strategy = LocatorStrategy.ByteOffset(0L, 0), progression = 0f, totalProgression = 0f),
    )
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private var paragraphs: List<String> = emptyList()
    private var fontSizeSp: Float = 18f
    private var recyclerView: RecyclerView? = null

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: ""
        // Paragraph split: blank-line separated, drop empties. Continuous mode → "pages" = paragraphs.
        paragraphs = text.split(Regex("\\r?\\n\\s*\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        _pageCount.value = paragraphs.size
        _currentLocator.value
    }

    override fun createView(): View {
        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = TxtParagraphAdapter(paragraphs, fontSizeSp)
            // 阅读区即纸：纸色背景（夜间暖褐压暗），呼应设计文档 §2.3。
            val night = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            setBackgroundColor(if (night) PAPER_NIGHT else PAPER_DAY)
            clipToPadding = false
            val padV = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    reportProgression(view)
                }
            })
        }
        recyclerView = rv
        return rv
    }

    private fun reportProgression(rv: RecyclerView) {
        val total = paragraphs.size
        if (total == 0) return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val ratio = first.toFloat() / total
        _currentLocator.value = Locator(
            strategy = LocatorStrategy.Section(spineIndex = 0, elementIndex = first, charOffset = 0),
            progression = ratio,
            totalProgression = ratio,
        )
    }

    override suspend fun goTo(locator: Locator) {
        val index = when (val s = locator.strategy) {
            is LocatorStrategy.Section -> s.elementIndex
            else -> 0
        }
        recyclerView?.let { rv ->
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
        }
    }

    override suspend fun close() {
        recyclerView = null
        paragraphs = emptyList()
    }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        (recyclerView?.adapter as? TxtParagraphAdapter)?.updateFontSize(sp)
    }

    override suspend fun setMode(mode: ReadingMode) {
        // Minimal slice: TXT stays CONTINUOUS. Runtime SCROLL↔PAGED (R-6) lands later.
    }

    private companion object {
        val PAPER_DAY = Color.rgb(0xED, 0xE6, 0xD6)
        val PAPER_NIGHT = Color.rgb(0x2A, 0x26, 0x20)
    }
}
