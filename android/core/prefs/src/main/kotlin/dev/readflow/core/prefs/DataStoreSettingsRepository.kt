package dev.readflow.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.PageFlipStyle
import dev.readflow.core.model.ReaderMenuConfig
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.model.FontChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("readflow_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    override val calibreBaseUrl: Flow<String?> =
        context.dataStore.data.map { it[KEY_CALIBRE_URL] }

    override val fontSize: Flow<Int> =
        typographyFlow { it[KEY_FONT_SIZE] ?: ReaderTypography.DEFAULT_FONT_SP }

    override val lineSpacing: Flow<Float> =
        typographyFlow { resolvedLineSpacingPreference(it[KEY_LINE_SPACING]) }

    override val readingMode: Flow<ReaderReadingMode> =
        context.dataStore.data.map {
            runCatching { ReaderReadingMode.valueOf(it[KEY_READING_MODE] ?: "") }
                .getOrDefault(ReaderReadingMode.SCROLL)
        }

    override val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map {
            runCatching { ThemeMode.valueOf(it[KEY_THEME] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        }

    override val deviceId: Flow<String> = flow {
        emit(readOrCreateDeviceId())
    }

    override val engineOverrides: Flow<Map<BookFormat, String>> = flowOf(emptyMap())

    override val useSourceHanFont: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_USE_SOURCE_HAN] ?: false }

    override val txtEncoding: Flow<TxtEncoding> =
        context.dataStore.data.map {
            runCatching { TxtEncoding.valueOf(it[KEY_TXT_ENCODING] ?: "AUTO") }
                .getOrDefault(TxtEncoding.AUTO)
        }

    override val fontChoice: Flow<FontChoice> =
        context.dataStore.data.map {
            FontChoice.parse(it[KEY_FONT_CHOICE])
        }

    override val readerGuideShown: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_READER_GUIDE_SHOWN] ?: false }

    override val pageFlipStyle: Flow<PageFlipStyle> =
        context.dataStore.data.map {
            runCatching { PageFlipStyle.valueOf(it[KEY_PAGE_FLIP_STYLE] ?: "") }
                .getOrDefault(PageFlipStyle.SLIDE)
        }

    override val readerMenuConfig: Flow<ReaderMenuConfig> =
        context.dataStore.data.map { preferences ->
            // Resolve on read only — do not rewrite the key unless a future migration requires it.
            resolvedReaderMenuConfig(preferences[KEY_READER_MENU_CONFIG])
        }

    override suspend fun ensureCurrentTypographyBaseline(): Boolean {
        var installed = false
        context.dataStore.edit { preferences ->
            val current = resolvedTypographyBaseline(
                storedFontSize = preferences[KEY_FONT_SIZE],
                storedLineSpacing = preferences[KEY_LINE_SPACING],
                storedVersion = preferences[KEY_TYPOGRAPHY_BASELINE_VERSION],
            )
            if (preferences[KEY_TYPOGRAPHY_BASELINE_VERSION] != current.version) {
                preferences[KEY_FONT_SIZE] = current.fontSize
                preferences[KEY_LINE_SPACING] = current.lineSpacing
                preferences[KEY_TYPOGRAPHY_BASELINE_VERSION] = current.version
                installed = true
            }
        }
        return installed
    }

    override suspend fun setCalibreBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_CALIBRE_URL] = url }
    }

    override suspend fun setFontSize(size: Int) {
        context.dataStore.edit { it[KEY_FONT_SIZE] = size }
    }

    override suspend fun setLineSpacing(multiplier: Float) {
        context.dataStore.edit { it[KEY_LINE_SPACING] = multiplier }
    }

    override suspend fun setReadingMode(mode: ReaderReadingMode) {
        context.dataStore.edit { it[KEY_READING_MODE] = mode.name }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    override suspend fun setEngineOverride(format: BookFormat, engineId: String?) {
        // Phase 3: per-format engine override UI
    }

    override suspend fun setUseSourceHanFont(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_SOURCE_HAN] = enabled }
    }

    override suspend fun setTxtEncoding(encoding: TxtEncoding) {
        context.dataStore.edit { it[KEY_TXT_ENCODING] = encoding.name }
    }

    override suspend fun setFontChoice(choice: FontChoice) {
        context.dataStore.edit { it[KEY_FONT_CHOICE] = choice.serialize() }
    }

    override suspend fun setReaderGuideShown(shown: Boolean) {
        context.dataStore.edit { it[KEY_READER_GUIDE_SHOWN] = shown }
    }

    override suspend fun setPageFlipStyle(style: PageFlipStyle) {
        context.dataStore.edit { it[KEY_PAGE_FLIP_STYLE] = style.name }
    }

    override suspend fun setReaderMenuConfig(config: ReaderMenuConfig) {
        val canonical = ReaderMenuConfig.resolve(config)
        context.dataStore.edit {
            it[KEY_READER_MENU_CONFIG] = ReaderMenuConfig.encode(canonical)
        }
    }

    private suspend fun readOrCreateDeviceId(): String {
        var value: String? = null
        context.dataStore.edit { preferences ->
            value = preferences[KEY_DEVICE_ID]
            if (value == null) {
                value = UUID.randomUUID().toString()
                preferences[KEY_DEVICE_ID] = value!!
            }
        }
        return value!!
    }

    private fun <T> typographyFlow(transform: (Preferences) -> T): Flow<T> = flow {
        ensureCurrentTypographyBaseline()
        emitAll(context.dataStore.data.map(transform))
    }

    private companion object {
        val KEY_CALIBRE_URL = stringPreferencesKey("calibre_url")
        val KEY_FONT_SIZE = intPreferencesKey("font_size")
        val KEY_LINE_SPACING = floatPreferencesKey("line_spacing")
        val KEY_READING_MODE = stringPreferencesKey("reading_mode")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_USE_SOURCE_HAN = booleanPreferencesKey("use_source_han_font")
        val KEY_TXT_ENCODING = stringPreferencesKey("txt_encoding")
        val KEY_FONT_CHOICE = stringPreferencesKey("font_choice")
        val KEY_READER_GUIDE_SHOWN = booleanPreferencesKey("reader_guide_shown")
        val KEY_PAGE_FLIP_STYLE = stringPreferencesKey("page_flip_style")
        val KEY_TYPOGRAPHY_BASELINE_VERSION = intPreferencesKey("typography_baseline_version")
        val KEY_READER_MENU_CONFIG = stringPreferencesKey("reader_menu_config")
    }
}

/** Pure adapter: DataStore string payload → resolved [ReaderMenuConfig] via model codec. */
internal fun resolvedReaderMenuConfig(stored: String?): ReaderMenuConfig =
    ReaderMenuConfig.decodeOrDefaults(stored)

internal fun resolvedLineSpacingPreference(stored: Float?): Float =
    stored ?: ReaderTypography.DEFAULT_LINE_SPACING

internal data class TypographyBaseline(
    val fontSize: Int,
    val lineSpacing: Float,
    val version: Int,
)

internal fun resolvedTypographyBaseline(
    storedFontSize: Int?,
    storedLineSpacing: Float?,
    storedVersion: Int?,
): TypographyBaseline = if ((storedVersion ?: 0) < ReaderTypography.BASELINE_VERSION) {
    TypographyBaseline(
        fontSize = ReaderTypography.DEFAULT_FONT_SP,
        lineSpacing = ReaderTypography.DEFAULT_LINE_SPACING,
        version = ReaderTypography.BASELINE_VERSION,
    )
} else {
    TypographyBaseline(
        fontSize = storedFontSize ?: ReaderTypography.DEFAULT_FONT_SP,
        lineSpacing = resolvedLineSpacingPreference(storedLineSpacing),
        version = storedVersion ?: ReaderTypography.BASELINE_VERSION,
    )
}
