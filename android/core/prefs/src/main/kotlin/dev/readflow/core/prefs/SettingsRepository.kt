package dev.readflow.core.prefs

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * User settings contract (Layer 1). Phase 1 scaffold: field surface declared —
 * DataStore-backed read/write implementation lands with feature work.
 *
 * `deviceId` (F6) is a stable replica id generated once and persisted under key
 * `device_id`; `engineOverrides` lets the user pin a specific engine per format.
 */
interface SettingsRepository {
    val calibreBaseUrl: Flow<String?>
    val fontSize: Flow<Int>
    val lineSpacing: Flow<Float>
    val readingMode: Flow<ReaderReadingMode>
    val themeMode: Flow<ThemeMode>
    val deviceId: Flow<String>
    val engineOverrides: Flow<Map<BookFormat, String>>
    val useSourceHanFont: Flow<Boolean>
    val txtEncoding: Flow<TxtEncoding>
    val fontChoice: Flow<FontChoice>
    /** Global EPUB CSS family -> reader font id replacement table. */
    val epubFontReplacements: Flow<Map<String, String>>
        get() = flowOf(emptyMap())
    /**
     * Per-book EPUB CSS family -> reader font id maps, keyed by stable book identity.
     * Outer key is [bookId]; inner map uses the same family canonicalization as global replacements.
     */
    val epubBookFontReplacements: Flow<Map<String, Map<String, String>>>
        get() = flowOf(emptyMap())
    /** Fonts whose settings references are cleared but whose private files may still need deletion. */
    val pendingImportedFontDeletions: Flow<Set<FontChoice.Custom>>
        get() = flowOf(emptySet())
    /** 阅读器首次手势引导是否已展示过（一次性）。 */
    val readerGuideShown: Flow<Boolean>
    /** 分页模式翻页动画风格（滑动/仿真/无）。 */
    val pageFlipStyle: Flow<PageFlipStyle>
    /**
     * Resolved reader bottom-menu order/visibility.
     * Default implementation emits v1 catalog defaults so legacy fakes stay source-compatible.
     */
    val readerMenuConfig: Flow<ReaderMenuConfig>
        get() = flowOf(ReaderMenuConfig.v1Defaults())

    /** Installs this release's typography baseline once; later user changes remain untouched. */
    suspend fun ensureCurrentTypographyBaseline(): Boolean = false

    suspend fun setCalibreBaseUrl(url: String)
    suspend fun setFontSize(size: Int)
    suspend fun setLineSpacing(multiplier: Float)
    suspend fun setReadingMode(mode: ReaderReadingMode)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setEngineOverride(format: BookFormat, engineId: String?)
    suspend fun setUseSourceHanFont(enabled: Boolean)
    suspend fun setTxtEncoding(encoding: TxtEncoding)
    suspend fun setFontChoice(choice: FontChoice)
    /** Atomically clears every reference and records a durable pending file deletion. */
    suspend fun removeImportedFont(choice: FontChoice.Custom) = Unit
    /** Clears the durable deletion marker after the private font file is gone. */
    suspend fun completeImportedFontDeletion(choice: FontChoice.Custom) = Unit
    suspend fun setEpubFontReplacements(replacements: Map<String, String>) = Unit
    /**
     * Replace the entire book-scoped EPUB font map for [bookId].
     * Empty [replacements] removes the book entry. Default no-op for legacy fakes.
     */
    suspend fun setEpubBookFontReplacements(bookId: String, replacements: Map<String, String>) = Unit
    suspend fun setReaderGuideShown(shown: Boolean)
    suspend fun setPageFlipStyle(style: PageFlipStyle)
    /** Persist resolved menu config; default no-op for legacy fakes. */
    suspend fun setReaderMenuConfig(config: ReaderMenuConfig) = Unit
}
