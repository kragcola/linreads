package dev.readflow.features.reader

import android.net.Uri
import dev.readflow.core.model.Locator
import dev.readflow.core.model.ThemeMode
import dev.readflow.render.api.ReadingMode

sealed interface ReaderIntent {
    data class OpenBook(val uri: Uri) : ReaderIntent
    data class OpenById(val bookId: String) : ReaderIntent
    data object CloseBook : ReaderIntent
    data class GoTo(val locator: Locator) : ReaderIntent
    data class SetFontSize(val sp: Float) : ReaderIntent
    data class SetMode(val mode: ReadingMode) : ReaderIntent
    data class SetTheme(val theme: ThemeMode) : ReaderIntent
    data object ToggleChrome : ReaderIntent
    data object FontPanel : ReaderIntent
    data object ThemePanel : ReaderIntent
}
