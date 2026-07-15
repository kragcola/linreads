package dev.readflow.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ThemeProfile(
    val name: String = "我的主题",
    val fontSize: Int = ReaderTypographyRange.DEFAULT_FONT_SIZE,
    val lineSpacing: Float = ReaderTypographyRange.DEFAULT_LINE_SPACING,
    val themeMode: String = "SYSTEM",
    val fontChoice: String = "system_serif",
    val txtEncoding: String = "AUTO",
    val readingMode: String = "SCROLL",
) {
    fun encode(): String = json.encodeToString(this)
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Parse. Returns null on any failure (dirty JSON, wrong types, etc). */
        fun decode(raw: String): ThemeProfile? =
            runCatching { json.decodeFromString<ThemeProfile>(raw) }.getOrNull()

        /** Clamp numeric & validate enum fields. Always returns a safe profile. */
        fun validated(profile: ThemeProfile): ThemeProfile = profile.copy(
            fontSize = profile.fontSize.coerceIn(
                ReaderTypographyRange.MIN_FONT_SIZE,
                ReaderTypographyRange.MAX_FONT_SIZE,
            ),
            lineSpacing = profile.lineSpacing.coerceIn(
                ReaderTypographyRange.MIN_LINE_SPACING,
                ReaderTypographyRange.MAX_LINE_SPACING,
            ),
            themeMode = runCatching { ThemeMode.valueOf(profile.themeMode) }.getOrDefault(ThemeMode.SYSTEM).name,
            fontChoice = FontChoice.parse(profile.fontChoice).serialize(),
            txtEncoding = runCatching { TxtEncoding.valueOf(profile.txtEncoding) }.getOrDefault(TxtEncoding.AUTO).name,
            readingMode = runCatching { ReaderReadingMode.valueOf(profile.readingMode) }.getOrDefault(ReaderReadingMode.SCROLL).name,
        )
    }
}
