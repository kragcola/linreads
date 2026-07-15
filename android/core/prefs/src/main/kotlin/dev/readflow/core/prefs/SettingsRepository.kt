package dev.readflow.core.prefs

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import kotlinx.coroutines.flow.Flow

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
    /** 阅读器首次手势引导是否已展示过（一次性）。 */
    val readerGuideShown: Flow<Boolean>
    /** 分页模式翻页动画风格（滑动/仿真/无）。 */
    val pageFlipStyle: Flow<PageFlipStyle>

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
    suspend fun setReaderGuideShown(shown: Boolean)
    suspend fun setPageFlipStyle(style: PageFlipStyle)
}
