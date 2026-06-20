plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.txt"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":render:api"))
    implementation(libs.coroutines.core)
    implementation(libs.recyclerview)
}
