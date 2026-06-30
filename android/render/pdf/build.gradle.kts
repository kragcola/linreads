plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.pdf"

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))   // readerPaperBackground (纸质质感背景)
    implementation(project(":render:api"))
    implementation(libs.coroutines.core)
    implementation(libs.recyclerview)

    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
}
