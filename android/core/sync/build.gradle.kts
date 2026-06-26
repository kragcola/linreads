plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.core.sync"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit4)
}
