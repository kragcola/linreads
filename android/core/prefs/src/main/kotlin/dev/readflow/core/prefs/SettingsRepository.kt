package dev.readflow.core.prefs

import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
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

    suspend fun setCalibreBaseUrl(url: String)
    suspend fun setFontSize(size: Int)
    suspend fun setLineSpacing(multiplier: Float)
    suspend fun setReadingMode(mode: ReaderReadingMode)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setEngineOverride(format: BookFormat, engineId: String?)
}
