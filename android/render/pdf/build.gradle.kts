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
    implementation(project(":render:api"))
    implementation(libs.coroutines.core)
    implementation(libs.recyclerview)

    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
}
