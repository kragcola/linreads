package dev.readflow.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("readflow_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    override val calibreBaseUrl: Flow<String?> =
        context.dataStore.data.map { it[KEY_CALIBRE_URL] }

    override val fontSize: Flow<Int> =
        context.dataStore.data.map { it[KEY_FONT_SIZE] ?: 18 }

    override val lineSpacing: Flow<Float> =
        context.dataStore.data.map { it[KEY_LINE_SPACING] ?: 1.75f }

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

    private companion object {
        val KEY_CALIBRE_URL = stringPreferencesKey("calibre_url")
        val KEY_FONT_SIZE = intPreferencesKey("font_size")
        val KEY_LINE_SPACING = floatPreferencesKey("line_spacing")
        val KEY_READING_MODE = stringPreferencesKey("reading_mode")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }
}
