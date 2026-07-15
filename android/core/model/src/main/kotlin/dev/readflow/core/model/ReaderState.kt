package dev.readflow.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ReaderReadingMode {
    SCROLL,
    PAGED,
}

/**
 * 分页模式下的翻页动画风格（静读天下「翻页方式」对应项）。仅在 PAGED 阅读模式生效。
 * - [SLIDE]      硬件加速覆盖滑动（默认，GPU 合成，任意分辨率 60fps）
 * - [SIMULATION] 仿真书页翻动（Canvas 网格卷曲，开销更高，按需开启）
 * - [NONE]       无动画，瞬时切页
 */
enum class PageFlipStyle {
    SLIDE,
    SIMULATION,
    NONE,
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
    val fontSize: Int = ReaderTypographyRange.DEFAULT_FONT_SIZE,
    val lineSpacing: Float = ReaderTypographyRange.DEFAULT_LINE_SPACING,
    val readingMode: ReaderReadingMode = ReaderReadingMode.SCROLL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val zoomLevel: Float = 1.0f,
    val panOffset: Offset = Offset.Zero,
    val isUiVisible: Boolean = true,
    val transition: TransitionType = TransitionType.SLIDE,
    val error: ReadflowError? = null,
)
