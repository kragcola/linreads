plugins {
    id("readflow.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.readflow.core.database"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.core)
}
