plugins {
    id("readflow.feature")
}

android {
    namespace = "dev.readflow.features.reader"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":core:sync"))
    implementation(project(":render:api"))   // abstraction only — NOT any render:* impl (C3)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.koin)
}
