package dev.readflow.features.settings

internal enum class SettingsLayoutMode {
    SINGLE_COLUMN,
    TWO_COLUMNS,
}

internal enum class SettingsCategory(val label: String) {
    READING("阅读"),
    SOURCE("书源"),
    DATA("数据"),
    ABOUT("关于"),
}

internal fun settingsCategoryOrder(): List<SettingsCategory> = listOf(
    SettingsCategory.READING,
    SettingsCategory.SOURCE,
    SettingsCategory.DATA,
    SettingsCategory.ABOUT,
)

internal fun settingsLayoutMode(availableWidthDp: Float): SettingsLayoutMode =
    if (availableWidthDp >= 780f) {
        SettingsLayoutMode.TWO_COLUMNS
    } else {
        SettingsLayoutMode.SINGLE_COLUMN
    }

internal fun String.displayBuildLabel(): String {
    val buildNumber = removePrefix("dev-")
        .substringBefore("-")
        .takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }
    return if (buildNumber != null) "构建 #$buildNumber" else this
}
