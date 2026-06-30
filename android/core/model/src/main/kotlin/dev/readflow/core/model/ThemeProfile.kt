package dev.readflow.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ThemeProfile(
    val name: String = "我的主题",
    val fontSize: Int = 18,
    val lineSpacing: Float = 1.3f,
    val themeMode: String = "SYSTEM",
    val fontChoice: String = "source_han",
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
            fontSize = profile.fontSize.coerceIn(12, 32),
            lineSpacing = profile.lineSpacing.coerceIn(1.2f, 2.2f),
            themeMode = runCatching { ThemeMode.valueOf(profile.themeMode) }.getOrDefault(ThemeMode.SYSTEM).name,
            fontChoice = FontChoice.parse(profile.fontChoice).serialize(),
            txtEncoding = runCatching { TxtEncoding.valueOf(profile.txtEncoding) }.getOrDefault(TxtEncoding.AUTO).name,
            readingMode = runCatching { ReaderReadingMode.valueOf(profile.readingMode) }.getOrDefault(ReaderReadingMode.SCROLL).name,
        )
    }
}
