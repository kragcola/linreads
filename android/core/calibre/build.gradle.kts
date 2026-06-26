plugins {
    id("readflow.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.readflow.core.calibre"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.bundles.ktor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.coroutines.test)
}
