plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val readflowPhase = (project.findProperty("readflow.phase") as String?)?.toInt() ?: 1

android {
    namespace = "dev.readflow"
    compileSdk = 36

    val buildTag = System.getenv("BUILD_TAG") ?: "dev-local"

    defaultConfig {
        applicationId = "dev.readflow"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GITHUB_REPO", "\"kragcola/linreads\"")
        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Phase-specific app wiring: phase1 = foundation shell, phase>=2 = TXT reader slice.
    // The two source sets carry mutually-exclusive AppModules/ReadflowApp so phase1
    // never references render:* modules (F9 — keep phase1 self-contained & buildable).
    sourceSets {
        getByName("main") {
            if (readflowPhase >= 2) {
                java.srcDir("src/phase2/java")
            } else {
                java.srcDir("src/phase1/java")
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:calibre"))
    implementation(project(":core:database"))
    implementation(project(":core:prefs"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(project(":extensions:api"))
    implementation(project(":features:library"))

    // Phase 2 (TXT minimal slice): reader + render contracts/impls wired via DI
    if (readflowPhase >= 2) {
        implementation(project(":render:api"))
        implementation(project(":render:txt"))
        implementation(project(":render:epub"))
        implementation(project(":render:pdf"))
        implementation(project(":render:animate"))
        implementation(project(":features:reader"))
        implementation(project(":features:settings"))
    }

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.bundles.compose.debug)
    implementation(libs.activity.compose)
    implementation(libs.bundles.koin)
    implementation(libs.bundles.room)
    // Other render engines (epub/pdf/md) + settings: shells only, wired when implemented
    // Handwriting (:ink): Phase 3
    // Testing:
    // testImplementation(libs.bundles.test)
}
