package dev.readflow.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.prefs.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val calibreBaseUrl = settings.calibreBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val fontSize = settings.fontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 18)

    val themeMode = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setCalibreUrl(url: String) { viewModelScope.launch { settings.setCalibreBaseUrl(url) } }
    fun setFontSize(size: Int) { viewModelScope.launch { settings.setFontSize(size) } }
    fun setTheme(mode: ThemeMode) { viewModelScope.launch { settings.setThemeMode(mode) } }
}
