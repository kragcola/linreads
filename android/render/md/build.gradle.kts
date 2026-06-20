plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.md"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":render:api"))
    implementation(libs.coroutines.core)
    implementation(libs.bundles.markwon)
}
