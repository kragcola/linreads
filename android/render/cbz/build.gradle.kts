plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.cbz"

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core:archive"))
    implementation(project(":core:model"))
    implementation(project(":render:api"))
    implementation(libs.coroutines.core)
    implementation(libs.exifinterface)
    implementation(libs.recyclerview)
    implementation(libs.zoomimage.view)

    testImplementation(libs.junit5)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
