plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.animate"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":render:api"))
    implementation(libs.coroutines.core)
}
