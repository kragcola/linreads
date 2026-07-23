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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    override val epubFontReplacements: Flow<Map<String, String>> =
        context.dataStore.data.map { preferences ->
            resolvedEpubFontReplacements(preferences[KEY_EPUB_FONT_REPLACEMENTS])
        }

    override val epubBookFontReplacements: Flow<Map<String, Map<String, String>>> =
        context.dataStore.data.map { preferences ->
            resolvedEpubBookFontReplacements(preferences[KEY_EPUB_BOOK_FONT_REPLACEMENTS])
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

    override suspend fun setEpubFontReplacements(replacements: Map<String, String>) {
        context.dataStore.edit {
            it[KEY_EPUB_FONT_REPLACEMENTS] = encodeEpubFontReplacements(replacements)
        }
    }

    override suspend fun setEpubBookFontReplacements(bookId: String, replacements: Map<String, String>) {
        val normalizedBookId = canonicalBookIdentity(bookId) ?: return
        context.dataStore.edit { preferences ->
            val current = resolvedEpubBookFontReplacements(preferences[KEY_EPUB_BOOK_FONT_REPLACEMENTS])
            val nextInner = canonicalEpubFontReplacements(replacements)
            val nextOuter = if (nextInner.isEmpty()) {
                current - normalizedBookId
            } else {
                current + (normalizedBookId to nextInner)
            }
            preferences[KEY_EPUB_BOOK_FONT_REPLACEMENTS] = encodeEpubBookFontReplacements(nextOuter)
        }
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
        val KEY_EPUB_FONT_REPLACEMENTS = stringPreferencesKey("epub_font_replacements")
        val KEY_EPUB_BOOK_FONT_REPLACEMENTS = stringPreferencesKey("epub_book_font_replacements")
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

internal fun encodeEpubFontReplacements(replacements: Map<String, String>): String =
    Json.encodeToString(canonicalEpubFontReplacements(replacements))

internal fun resolvedEpubFontReplacements(stored: String?): Map<String, String> {
    if (stored.isNullOrBlank()) return emptyMap()
    val decoded = runCatching { Json.decodeFromString<Map<String, String>>(stored) }.getOrNull()
        ?: return emptyMap()
    return canonicalEpubFontReplacements(decoded)
}

internal fun encodeEpubBookFontReplacements(byBook: Map<String, Map<String, String>>): String =
    Json.encodeToString(canonicalEpubBookFontReplacements(byBook))

internal fun resolvedEpubBookFontReplacements(stored: String?): Map<String, Map<String, String>> {
    if (stored.isNullOrBlank()) return emptyMap()
    val decoded = runCatching {
        Json.decodeFromString<Map<String, Map<String, String>>>(stored)
    }.getOrNull() ?: return emptyMap()
    return canonicalEpubBookFontReplacements(decoded)
}

/**
 * Effective EPUB family replacements for one open book.
 * Priority: book-scoped map > global map (same family key; book wins).
 */
fun mergedEpubFontReplacements(
    global: Map<String, String>,
    bookScoped: Map<String, String>,
): Map<String, String> {
    val globalCanonical = canonicalEpubFontReplacements(global)
    val bookCanonical = canonicalEpubFontReplacements(bookScoped)
    if (bookCanonical.isEmpty()) return globalCanonical
    if (globalCanonical.isEmpty()) return bookCanonical
    return (globalCanonical + bookCanonical).toSortedMap()
}

internal fun canonicalEpubFontReplacements(replacements: Map<String, String>): Map<String, String> =
    buildMap {
        replacements.forEach { (rawFamily, rawFontId) ->
            val family = canonicalEpubFontFamilyKey(rawFamily) ?: return@forEach
            val fontId = canonicalReplacementFontId(rawFontId) ?: return@forEach
            put(family, fontId)
        }
    }.toSortedMap()

internal fun canonicalEpubBookFontReplacements(
    byBook: Map<String, Map<String, String>>,
): Map<String, Map<String, String>> =
    buildMap {
        byBook.forEach { (rawBookId, inner) ->
            val bookId = canonicalBookIdentity(rawBookId) ?: return@forEach
            val canonicalInner = canonicalEpubFontReplacements(inner)
            if (canonicalInner.isNotEmpty()) put(bookId, canonicalInner)
        }
    }.toSortedMap(compareBy { it })

/** Stable book identity for per-book prefs: trimmed non-blank, length-capped, no control chars. */
fun canonicalBookIdentity(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.length > 256 || value.any(Char::isISOControl)) return null
    return value
}

/** Same family key rules as global replacements (quotes, case, whitespace). */
fun canonicalEpubFontFamilyKey(raw: String?): String? {
    val family = raw.orEmpty().trim().trim('"', '\'')
        .replace(Regex("\\s+"), " ")
        .lowercase()
    return family.takeIf { it.isNotEmpty() && it.length <= 96 && it.none(Char::isISOControl) }
}

private fun canonicalReplacementFontId(raw: String): String? {
    val value = raw.trim()
    if (value in setOf("system_serif", "system_sans", "system_monospace")) return value
    if (!value.startsWith("custom:")) return null
    val fileName = value.removePrefix("custom:")
    if (
        fileName.isBlank() || fileName.length > 128 ||
        fileName == "." || fileName == ".." ||
        '/' in fileName || '\\' in fileName || '\u0000' in fileName
    ) return null
    if (fileName.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return null
    return "custom:$fileName"
}

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
