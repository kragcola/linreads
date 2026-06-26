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
    implementation(project(":core:calibre"))
    implementation(project(":core:sync"))
    implementation(project(":core:database"))
    implementation(libs.bundles.koin)
    implementation(libs.compose.material.icons.extended)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
}
