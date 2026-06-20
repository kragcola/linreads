plugins {
    id("readflow.feature")
}

android {
    namespace = "dev.readflow.features.settings"
}

// Phase 2 — SettingsScreen/SettingsViewModel
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:prefs"))
    implementation(libs.bundles.koin)
    implementation(libs.compose.material.icons.extended)
}
