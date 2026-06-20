package dev.readflow.extensions.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Fire-and-forget reader events for extensions (§8.4). All carry a bookId. */
sealed interface ReaderEvent {
    val bookId: String

    data class BookOpened(override val bookId: String) : ReaderEvent
    data class BookClosed(override val bookId: String) : ReaderEvent
    data class PageChanged(override val bookId: String, val pageIndex: Int) : ReaderEvent
    data class BookmarkAdded(override val bookId: String, val bookmarkId: String) : ReaderEvent
    data class BookmarkRemoved(override val bookId: String, val bookmarkId: String) : ReaderEvent
    data class ThemeChanged(override val bookId: String) : ReaderEvent
    data class FontSizeChanged(override val bookId: String, val fontSize: Int) : ReaderEvent
}

/** SharedFlow-based event bus (§8.4). Phase 1 scaffold: emit/subscribe surface only. */
class ReaderEventBus {
    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    fun tryEmit(event: ReaderEvent): Boolean = _events.tryEmit(event)
}
