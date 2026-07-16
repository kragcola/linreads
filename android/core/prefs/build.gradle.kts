plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.core.prefs"

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
