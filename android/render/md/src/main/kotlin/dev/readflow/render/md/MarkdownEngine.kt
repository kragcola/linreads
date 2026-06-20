package dev.readflow.render.md

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReadingMode
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class MarkdownEngine(private val context: Context) : ReaderEngine {

    override val id: String = "md-markwon"
    override val format: BookFormat = BookFormat.MD
    override val priority: Int = 0
    override val supportsSearch: Boolean = false

    private val _pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(
        Locator(strategy = LocatorStrategy.ByteOffset(0L, 0), progression = 0f, totalProgression = 0f),
    )
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(1)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private var rawMarkdown: String = ""
    private var fontSizeSp: Float = 18f
    private var scrollView: ScrollView? = null
    private var textView: TextView? = null

    private val markwon by lazy {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        rawMarkdown = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
        _currentLocator.value
    }

    override fun createView(): View {
        val tv = TextView(context).apply {
            textSize = fontSizeSp
            setPadding(48, 48, 48, 48)
        }
        markwon.setMarkdown(tv, rawMarkdown)
        textView = tv

        val sv = ScrollView(context).apply {
            addView(tv)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val maxScroll = (tv.height - height).coerceAtLeast(1)
                val ratio = (scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
                _currentLocator.value = Locator(
                    strategy = LocatorStrategy.ByteOffset(scrollY.toLong(), 0),
                    progression = ratio,
                    totalProgression = ratio,
                )
            }
        }
        scrollView = sv
        return sv
    }

    override suspend fun goTo(locator: Locator) {
        val y = when (val s = locator.strategy) {
            is LocatorStrategy.ByteOffset -> s.offset.toInt()
            else -> 0
        }
        scrollView?.scrollTo(0, y)
    }

    override suspend fun close() {
        scrollView = null
        textView = null
        rawMarkdown = ""
    }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        textView?.textSize = sp
    }

    override suspend fun setMode(mode: ReadingMode) = Unit
}
