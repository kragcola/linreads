plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.ink"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":render:api"))
    implementation(libs.bundles.ink)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
}
