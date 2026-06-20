plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.core.prefs"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
}
