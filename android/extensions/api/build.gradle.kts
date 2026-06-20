plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.extensions.api"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.coroutines.core)
}
