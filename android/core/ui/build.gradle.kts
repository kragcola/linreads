plugins {
    id("readflow.compose")
}

android {
    namespace = "dev.readflow.core.ui"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.coil.compose)
}
