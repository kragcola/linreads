package dev.readflow.core.prefs

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("readflow_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    override val calibreBaseUrl: Flow<String?> =
        context.dataStore.data.map { it[KEY_CALIBRE_URL] }

    override val fontSize: Flow<Int> =
        context.dataStore.data.map { it[KEY_FONT_SIZE] ?: 18 }

    override val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map {
            runCatching { ThemeMode.valueOf(it[KEY_THEME] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        }

    override val deviceId: Flow<String> = flowOf(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "local"
    )

    override val engineOverrides: Flow<Map<BookFormat, String>> = flowOf(emptyMap())

    override suspend fun setCalibreBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_CALIBRE_URL] = url }
    }

    override suspend fun setFontSize(size: Int) {
        context.dataStore.edit { it[KEY_FONT_SIZE] = size }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    override suspend fun setEngineOverride(format: BookFormat, engineId: String?) {
        // Phase 3: per-format engine override UI
    }

    private companion object {
        val KEY_CALIBRE_URL = stringPreferencesKey("calibre_url")
        val KEY_FONT_SIZE = intPreferencesKey("font_size")
        val KEY_THEME = stringPreferencesKey("theme_mode")
    }
}
