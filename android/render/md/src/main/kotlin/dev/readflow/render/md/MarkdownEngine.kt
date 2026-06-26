package dev.readflow.render.md

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import dev.readflow.render.api.PagingKind
import dev.readflow.render.api.ReaderEngine
import dev.readflow.render.api.ReaderTextAnnotation
import dev.readflow.render.api.ReaderTextSelection
import dev.readflow.render.api.ReadingMode
import dev.readflow.render.api.SelectionAwareTextView
import dev.readflow.render.api.TextAnnotatableReaderEngine
import dev.readflow.render.api.TextSelectableReaderEngine
import dev.readflow.render.api.withTextHighlightSpans
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class MarkdownEngine(private val context: Context) : ReaderEngine, TextSelectableReaderEngine, TextAnnotatableReaderEngine {

    override val id: String = "md-markwon"
    override val format: BookFormat = BookFormat.MD
    override val priority: Int = 0
    override val supportsSearch: Boolean = true

    private val _pagingKind = MutableStateFlow(PagingKind.CONTINUOUS)
    override val pagingKind: StateFlow<PagingKind> = _pagingKind.asStateFlow()

    private val _currentLocator = MutableStateFlow(
        Locator(strategy = LocatorStrategy.ByteOffset(0L, 0), progression = 0f, totalProgression = 0f),
    )
    override val currentLocator: StateFlow<Locator> = _currentLocator.asStateFlow()

    private val _pageCount = MutableStateFlow(1)
    override val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private val _currentTextSelection = MutableStateFlow<ReaderTextSelection?>(null)
    override val currentTextSelection: StateFlow<ReaderTextSelection?> = _currentTextSelection.asStateFlow()

    private var document: MarkdownDocument = MarkdownDocument.parse("")
    private var fontSizeSp: Float = 18f
    private var lineSpacingMultiplier: Float = 1.75f
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var textAnnotations: List<ReaderTextAnnotation> = emptyList()
    private var scrollView: ScrollView? = null
    private var textView: TextView? = null
    private var suppressLocatorUpdates = false

    private val markwon by lazy {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    override suspend fun supports(uri: Uri): Boolean = true

    override suspend fun openBook(uri: Uri): Locator = withContext(Dispatchers.IO) {
        val markdown = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: ""
        val parsed = MarkdownDocument.parse(markdown)
        val initial = parsed.locatorForOffset(0)
        document = parsed
        withContext(Dispatchers.Main) {
            _tableOfContents.value = parsed.tableOfContents
            _currentLocator.value = initial
        }
        initial
    }

    override fun createView(): View {
        val palette = paletteFor(themeMode, context.resources.configuration)
        val tv = SelectionAwareTextView(context).apply {
            textSize = fontSizeSp
            setLineSpacing(0f, lineSpacingMultiplier)
            setPadding(48, 48, 48, 48)
            setTextColor(palette.ink)
            setTextIsSelectable(true)
            onSelectionRangeChanged = ::updateTextSelection
        }
        markwon.setMarkdown(tv, document.markdown)
        applyTextAnnotations(tv)
        textView = tv

        val sv = ScrollView(context).apply {
            setBackgroundColor(palette.paper)
            addView(tv)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                if (suppressLocatorUpdates) return@setOnScrollChangeListener
                _currentLocator.value = document.locatorForRenderedOffset(
                    renderedOffset = tv.characterOffsetForScrollY(scrollY),
                    renderedText = tv.text,
                )
            }
        }
        scrollView = sv
        return sv
    }

    override suspend fun goTo(locator: Locator) {
        val offset = document.offsetFor(locator)
        scrollView?.post {
            val sv = scrollView ?: return@post
            val tv = textView ?: return@post
            val renderedOffset = document.renderedOffsetFor(locator, tv.text)
            val maxScroll = (tv.height - sv.height).coerceAtLeast(0)
            val y = tv.scrollYForCharacterOffset(renderedOffset).coerceIn(0, maxScroll)
            sv.scrollTo(0, y)
            _currentLocator.value = document.locatorForOffset(offset)
        }
    }

    override suspend fun search(query: String): List<Locator> = withContext(Dispatchers.Default) {
        document.search(query)
    }

    override fun clearTextSelection() {
        _currentTextSelection.value = null
        val selectionAwareTextView = textView as? SelectionAwareTextView ?: return
        val sv = scrollView ?: return
        suppressLocatorUpdates = true
        try {
            selectionAwareTextView.clearNativeTextSelection()
        } finally {
            suppressLocatorUpdates = false
        }
        _currentLocator.value = document.locatorForRenderedOffset(
            renderedOffset = selectionAwareTextView.characterOffsetForScrollY(sv.scrollY),
            renderedText = selectionAwareTextView.text,
        )
    }

    override fun setTextAnnotations(annotations: List<ReaderTextAnnotation>) {
        textAnnotations = annotations
        textView?.let(::applyTextAnnotations)
    }

    private fun applyTextAnnotations(view: TextView) {
        val highlightedText = view.text.withTextHighlightSpans(document.highlightRanges(textAnnotations, view.text))
        val sv = scrollView
        if (sv == null) {
            view.text = highlightedText
            return
        }

        val previousScrollY = sv.scrollY
        val previousLocator = _currentLocator.value
        suppressLocatorUpdates = true
        try {
            view.text = highlightedText
            val immediateMaxScroll = (view.height - sv.height).coerceAtLeast(0)
            val immediateRestoreScrollY = previousScrollY.coerceIn(0, immediateMaxScroll)
            if (immediateRestoreScrollY != sv.scrollY) {
                sv.scrollTo(0, immediateRestoreScrollY)
            }
        } finally {
            suppressLocatorUpdates = false
        }
        sv.post {
            val activeScrollView = scrollView ?: return@post
            val activeTextView = textView ?: return@post
            val maxScroll = (activeTextView.height - activeScrollView.height).coerceAtLeast(0)
            val locatorScrollY = activeTextView.scrollYForCharacterOffset(
                document.renderedOffsetFor(previousLocator, activeTextView.text),
            ).coerceIn(0, maxScroll)
            val restoreScrollY = when {
                previousScrollY > 0 -> previousScrollY.coerceIn(0, maxScroll)
                else -> locatorScrollY
            }
            suppressLocatorUpdates = true
            try {
                activeScrollView.scrollTo(0, restoreScrollY)
            } finally {
                suppressLocatorUpdates = false
            }
            _currentLocator.value = document.locatorForRenderedOffset(
                renderedOffset = activeTextView.characterOffsetForScrollY(restoreScrollY),
                renderedText = activeTextView.text,
            )
        }
    }

    private fun updateTextSelection(start: Int, end: Int) {
        val displayedText = textView?.text?.toString().orEmpty()
        _currentTextSelection.value = document.selectionForRenderedOffsets(start, end, displayedText)
    }

    override suspend fun close() {
        scrollView = null
        textView = null
        document = MarkdownDocument.parse("")
        _currentTextSelection.value = null
        textAnnotations = emptyList()
        _tableOfContents.value = emptyList()
    }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        textView?.textSize = sp
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        lineSpacingMultiplier = multiplier
        textView?.setLineSpacing(0f, multiplier)
    }

    override suspend fun setTheme(mode: ThemeMode) {
        themeMode = mode
        val palette = paletteFor(mode, context.resources.configuration)
        scrollView?.setBackgroundColor(palette.paper)
        textView?.setTextColor(palette.ink)
    }

    override suspend fun setMode(mode: ReadingMode) = Unit

    private companion object {
        val PAPER_DAY = Color.rgb(0xED, 0xE6, 0xD6)
        val PAPER_NIGHT = Color.rgb(0x2A, 0x26, 0x20)
        val PAPER_LIGHT = Color.rgb(0xFA, 0xFA, 0xF8)
        val PAPER_SEPIA = Color.rgb(0xF5, 0xF0, 0xE8)
        val INK_DAY = Color.rgb(0x2A, 0x26, 0x20)
        val INK_NIGHT = Color.rgb(0xD8, 0xCF, 0xBC)

        private fun paletteFor(mode: ThemeMode, configuration: Configuration): ReaderPalette {
            val systemNight = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return when (mode) {
                ThemeMode.LIGHT -> ReaderPalette(PAPER_LIGHT, INK_DAY)
                ThemeMode.DARK -> ReaderPalette(PAPER_NIGHT, INK_NIGHT)
                ThemeMode.SEPIA -> ReaderPalette(PAPER_SEPIA, INK_DAY)
                ThemeMode.SYSTEM -> if (systemNight) {
                    ReaderPalette(PAPER_NIGHT, INK_NIGHT)
                } else {
                    ReaderPalette(PAPER_DAY, INK_DAY)
                }
            }
        }
    }
}

private fun TextView.characterOffsetForScrollY(scrollY: Int): Int {
    val layout = layout ?: return 0
    val vertical = (scrollY - totalPaddingTop).coerceAtLeast(0)
    val visualLine = layout.getLineForVertical(vertical)
    return layout.getLineStart(visualLine).coerceIn(0, text.length)
}

private fun TextView.scrollYForCharacterOffset(offset: Int): Int {
    val layout = layout ?: return 0
    val safeOffset = offset.coerceIn(0, text.length)
    val visualLine = layout.getLineForOffset(safeOffset)
    return layout.getLineTop(visualLine) + totalPaddingTop
}

private data class ReaderPalette(val paper: Int, val ink: Int)
