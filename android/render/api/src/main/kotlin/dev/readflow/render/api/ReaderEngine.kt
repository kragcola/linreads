package dev.readflow.render.api

import android.net.Uri
import android.view.View
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.Locator
import kotlinx.coroutines.flow.StateFlow

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

    suspend fun search(query: String): List<Locator> = emptyList()

    // Layout control (reflow formats)
    suspend fun setFontSize(sp: Float)
    suspend fun setMode(mode: ReadingMode)

    // View lifecycle / acceleration cache (semantic position lives in ReaderState.currentLocator)
    fun onViewAttached(view: View) {}
    fun onViewDetached(view: View) {}
    suspend fun saveState(): ByteArray = ByteArray(0)
    suspend fun restoreState(state: ByteArray) {}
}
