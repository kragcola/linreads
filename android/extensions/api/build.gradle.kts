plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.extensions.api"
}

dependencies {
    implementation(project(":core:archive"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.coroutines.core)
    implementation(libs.documentfile)
    implementation(libs.exifinterface)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
