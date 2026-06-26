plugins {
    id("readflow.android.library")
}

android {
    namespace = "dev.readflow.render.txt"

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
    implementation(libs.juniversalchardet)
    implementation(libs.recyclerview)

    testImplementation(libs.junit5)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
