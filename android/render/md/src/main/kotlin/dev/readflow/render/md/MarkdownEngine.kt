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

    private val _tableOfContents = MutableStateFlow<List<TocEntry>>(emptyList())
    override val tableOfContents: StateFlow<List<TocEntry>> = _tableOfContents.asStateFlow()

    private var rawMarkdown: String = ""
    private var lineCount: Int = 0
    private var fontSizeSp: Float = 18f
    private var themeMode: ThemeMode = ThemeMode.SYSTEM
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
        lineCount = rawMarkdown.lineSequence().count().coerceAtLeast(1)
        _tableOfContents.value = buildToc(rawMarkdown)
        _currentLocator.value
    }

    override fun createView(): View {
        val palette = paletteFor(themeMode, context.resources.configuration)
        val tv = TextView(context).apply {
            textSize = fontSizeSp
            setPadding(48, 48, 48, 48)
            setTextColor(palette.ink)
        }
        markwon.setMarkdown(tv, rawMarkdown)
        textView = tv

        val sv = ScrollView(context).apply {
            setBackgroundColor(palette.paper)
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

    private fun buildToc(markdown: String): List<TocEntry> {
        if (markdown.isBlank()) return emptyList()
        var lineIndex = 0
        val entries = mutableListOf<TocEntry>()
        markdown.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            val hashes = trimmed.takeWhile { it == '#' }.length
            if (hashes in 1..6 && trimmed.getOrNull(hashes) == ' ') {
                val title = trimmed.drop(hashes).trim()
                if (title.isNotEmpty()) {
                    entries += TocEntry(
                        title = title.take(80),
                        locator = Locator(
                            strategy = LocatorStrategy.Section(0, lineIndex, 0),
                            totalProgression = lineIndex.toFloat() / lineCount,
                        ),
                        level = hashes - 1,
                    )
                }
            }
            lineIndex += 1
        }
        return entries.ifEmpty { listOf(TocEntry("正文", Locator(LocatorStrategy.Section(0, 0, 0), totalProgression = 0f))) }
    }

    override suspend fun goTo(locator: Locator) {
        scrollView?.post {
            val sv = scrollView ?: return@post
            val tv = textView ?: return@post
            val maxScroll = (tv.height - sv.height).coerceAtLeast(0)
            val y = when (val s = locator.strategy) {
                is LocatorStrategy.ByteOffset -> s.offset.toInt().coerceIn(0, maxScroll)
                is LocatorStrategy.Section -> {
                    val ratio = s.elementIndex.toFloat() / lineCount.coerceAtLeast(1)
                    (maxScroll * ratio).toInt().coerceIn(0, maxScroll)
                }
                else -> 0
            }
            sv.scrollTo(0, y)
        }
    }

    override suspend fun close() {
        scrollView = null
        textView = null
        rawMarkdown = ""
        lineCount = 0
        _tableOfContents.value = emptyList()
    }

    override suspend fun setFontSize(sp: Float) {
        fontSizeSp = sp
        textView?.textSize = sp
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

private data class ReaderPalette(val paper: Int, val ink: Int)
