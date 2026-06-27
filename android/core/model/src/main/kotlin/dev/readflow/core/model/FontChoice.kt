package dev.readflow.core.model

/** 正文字体选择。SYSTEM=系统Serif；SOURCE_HAN=内置思源；CUSTOM=用户导入(filesDir/fonts/<fileName>)。 */
sealed interface FontChoice {
    object System : FontChoice
    object SourceHan : FontChoice
    data class Custom(val fileName: String) : FontChoice

    fun serialize(): String = when (this) {
        System -> "system"
        SourceHan -> "source_han"
        is Custom -> "custom:$fileName"
    }
    companion object {
        fun parse(raw: String?): FontChoice = when {
            raw == null || raw == "source_han" -> SourceHan
            raw == "system" -> System
            raw.startsWith("custom:") -> Custom(raw.removePrefix("custom:"))
            else -> SourceHan
        }
    }
}
