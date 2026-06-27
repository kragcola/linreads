plugins {
    id("readflow.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.readflow.render.epub"

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            providers.systemProperty("readflow.epubCorpusDir").orNull?.let { corpusDir ->
                it.systemProperty("readflow.epubCorpusDir", corpusDir)
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))   // FontProvider (思源宋体加载)
    implementation(project(":render:api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.coroutines.core)
    implementation(libs.jsoup)
    implementation(libs.recyclerview)

    testImplementation(libs.junit5)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
