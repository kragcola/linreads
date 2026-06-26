plugins {
    id("readflow.feature")
}

android {
    namespace = "dev.readflow.features.reader"

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":core:sync"))
    implementation(project(":core:prefs"))
    implementation(project(":render:api"))   // abstraction only — NOT any render:* impl (C3)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.koin)

    testImplementation(libs.junit5)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
