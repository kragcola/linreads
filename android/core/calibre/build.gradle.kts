plugins {
    id("readflow.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.readflow.core.calibre"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:prefs"))
    implementation(project(":extensions:api"))
    implementation(libs.bundles.ktor)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.jsoup)

    testImplementation(libs.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.coroutines.test)
    // Real XmlPullParser for JVM unit tests (Android framework API is stubbed otherwise).
    testImplementation(libs.kxml2)
}
