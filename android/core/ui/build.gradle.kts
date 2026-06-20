plugins {
    id("readflow.compose")
}

android {
    namespace = "dev.readflow.core.ui"

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.coil.compose)

    testImplementation(libs.junit5)
}
