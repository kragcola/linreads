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

    testImplementation(libs.junit5)
}
