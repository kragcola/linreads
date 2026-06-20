plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.api"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.coroutines.core)
}
