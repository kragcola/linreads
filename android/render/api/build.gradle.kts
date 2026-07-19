plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.api"

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.robolectric)
}
