package dev.readflow.features.settings

internal enum class SettingsLayoutMode {
    SINGLE_COLUMN,
    TWO_COLUMNS,
}

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
