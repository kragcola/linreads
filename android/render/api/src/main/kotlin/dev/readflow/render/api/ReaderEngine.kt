package dev.readflow.render.api

import android.net.Uri
import android.view.View
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ChapterInfo
import dev.readflow.core.model.Locator
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TocEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadingMode { SCROLL, PAGED }

enum class PagingKind { PAGED, CONTINUOUS }

/**
 * THREADING CONTRACT (v4 §5.1, P0-C):
 *   - All methods are CALLED on Dispatchers.Main; engines assume the main thread on entry.
 *   - Heavy work (parsing, FileChannel scan, JNI) MUST move off-main via withContext,
 *     then switch back to Main before touching the View.
 */
interface ReaderEngine {
    // Identity
    val id: String
    val format: BookFormat
    val priority: Int
    val pagingKind: StateFlow<PagingKind>
    val supportsSearch: Boolean
    suspend fun supports(uri: Uri): Boolean

    // Lifecycle
    suspend fun openBook(uri: Uri): Locator   // heavy work here, NOT in constructor
    fun createView(): View                    // called after openBook(); engine owns the View
    suspend fun close()

    // Navigation
    suspend fun goTo(locator: Locator)
    val currentLocator: StateFlow<Locator>
    val pageCount: StateFlow<Int>

    /** Chapter info for the bottom chrome progress bar. Emits on each position change. */
    val chapterInfo: StateFlow<ChapterInfo>
        get() = kotlinx.coroutines.flow.MutableStateFlow(
            ChapterInfo(0, 1, "", 0f)
        ).asStateFlow()

    /** Jump to next (+1) or previous (-1) chapter. No-op if at boundary. */
    suspend fun goToAdjacentChapter(delta: Int) {}

    /** Navigable document outline for the reader TOC panel. */
    val tableOfContents: StateFlow<List<TocEntry>>
        get() = MutableStateFlow(emptyList<TocEntry>()).asStateFlow()

    /** Jump to a table-of-contents entry. Default delegates to [goTo]. */
    suspend fun goToTocEntry(entry: TocEntry) = goTo(entry.locator)

    suspend fun search(query: String): List<Locator> = emptyList()

    // Layout control (reflow formats)
    suspend fun setFontSize(sp: Float)
    suspend fun setTheme(mode: ThemeMode) {}
    suspend fun setMode(mode: ReadingMode)

    // View lifecycle / acceleration cache (semantic position lives in ReaderState.currentLocator)
    fun onViewAttached(view: View) {}
    fun onViewDetached(view: View) {}
    suspend fun saveState(): ByteArray = ByteArray(0)
    suspend fun restoreState(state: ByteArray) {}
}
