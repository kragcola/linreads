plugins {
    id("readflow.feature")
}

android {
    namespace = "dev.readflow.features.library"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:calibre"))
    implementation(project(":core:database"))
    implementation(project(":core:prefs"))
    implementation(project(":extensions:api"))
    implementation(libs.coroutines.core)
    implementation(libs.bundles.koin)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
}
