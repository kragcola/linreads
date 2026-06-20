plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.md"
}

// Phase 2 placeholder shell — MarkwonEngine lands later.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":render:api"))
    implementation(libs.coroutines.core)
}
