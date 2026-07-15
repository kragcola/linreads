package dev.readflow.core.model

/** 正文字体选择。系统字体不携带二进制；CUSTOM=用户导入(filesDir/fonts/<fileName>)。 */
sealed interface FontChoice {
    object System : FontChoice
    object SystemSans : FontChoice
    object SystemMonospace : FontChoice
    data class Custom(val fileName: String) : FontChoice

    fun serialize(): String = when (this) {
        System -> "system_serif"
        SystemSans -> "system_sans"
        SystemMonospace -> "system_monospace"
        is Custom -> "custom:$fileName"
    }

    companion object {
        /** 旧源码兼容别名；当前没有捆绑思源字体。 */
        @Deprecated("No bundled Source Han asset; use System")
        val SourceHan: FontChoice get() = System

        fun parse(raw: String?): FontChoice = when {
            raw == null || raw == "system" || raw == "source_han" || raw == "system_serif" -> System
            raw == "system_sans" -> SystemSans
            raw == "system_monospace" -> SystemMonospace
            raw.startsWith("custom:") -> Custom(raw.removePrefix("custom:"))
            else -> System
        }
    }
}
