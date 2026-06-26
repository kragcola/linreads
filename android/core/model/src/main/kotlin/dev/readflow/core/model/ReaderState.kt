package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ReaderReadingMode {
    SCROLL,
    PAGED,
}

/**
 * Fully serializable reader UI/session state (§7.2, P0-A). Holds NO View reference.
 * Transits SavedStateHandle as a JSON String (F4); engine accelerator caches live
 * elsewhere (EngineStateStore, §7.2 F5).
 */
@Serializable
data class ReaderState(
    val bookId: String,
    val bookMeta: BookMeta? = null,
    val format: BookFormat = BookFormat.UNKNOWN,
    val loadingState: LoadingState = LoadingState.Idle,
    val currentLocator: Locator? = null,
    val totalPages: Int = 0,
    val currentPageIndex: Int = 0,
    val fontSize: Int = 18,
    val readingMode: ReaderReadingMode = ReaderReadingMode.SCROLL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val zoomLevel: Float = 1.0f,
    val panOffset: Offset = Offset.Zero,
    val isUiVisible: Boolean = true,
    val transition: TransitionType = TransitionType.SLIDE,
    val error: ReadflowError? = null,
)
