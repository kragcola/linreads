import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val readflowPhase = (project.findProperty("readflow.phase") as String?)?.toInt() ?: 1
require(readflowPhase in 1..3) {
    "readflow.phase must be 1, 2, or 3"
}
val updateHelperProtocolVersion = providers.gradleProperty("readflow.updateHelperProtocolVersion")
    .get().toInt().also { require(it > 0) }

// Phase 1 and phase 2 intentionally share the Android module name and variant names, but their
// source sets are mutually exclusive. A normal incremental build cannot infer that a phase changed
// and can otherwise leave reader classes in a phase-1 APK (or drop them from a phase-2 APK). Keep a
// small marker in the module output and invalidate the whole app output on a phase transition.
val phaseOutputMarker = layout.buildDirectory.file(".readflow-phase")
tasks.named("preBuild").configure {
    doFirst {
        val marker = phaseOutputMarker.get().asFile
        val expected = readflowPhase.toString()
        val actual = marker.takeIf { it.isFile }?.readText()?.trim()
        if (actual != expected) {
            project.delete(layout.buildDirectory.get().asFile)
        }
        marker.parentFile.mkdirs()
        marker.writeText(expected)
    }
}

android {
    namespace = "dev.readflow"
    compileSdk = 36

    val buildTag = System.getenv("BUILD_TAG") ?: "dev-local"
    val buildVersionCode = System.getenv("BUILD_VERSION_CODE")?.let { rawValue ->
        rawValue.toIntOrNull()?.takeIf { it > 0 }
            ?: error("BUILD_VERSION_CODE must be a positive 32-bit integer")
    } ?: 1

    defaultConfig {
        applicationId = "dev.readflow"
        minSdk = 26
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_REPO", "\"kragcola/linreads\"")
        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")
        buildConfigField("int", "OTA_VERSION_CODE", buildVersionCode.toString())
        buildConfigField("int", "UPDATE_HELPER_PROTOCOL_VERSION", updateHelperProtocolVersion.toString())
        buildConfigField("String", "GITHUB_OTA_TOKEN", "\"${System.getenv("GITHUB_OTA_TOKEN") ?: ""}\"" )
        manifestPlaceholders["updateReceiverEnabled"] = readflowPhase >= 2
    }

    // Explicit signing so the key never silently changes between machines/CI runs.
    // CI: set KEYSTORE_BASE64 env var (base64-encoded JKS/PKCS12).
    // Local dev: falls back to the standard debug keystore.
    signingConfigs {
        create("linreads") {
            val ksB64 = System.getenv("KEYSTORE_BASE64")
            if (ksB64 != null) {
                val ksFile = rootProject.layout.buildDirectory.get().asFile.resolve("linreads-signing.jks")
                ksFile.parentFile.mkdirs()
                ksFile.writeBytes(Base64.getDecoder().decode(ksB64))
                storeFile = ksFile
                storePassword = System.getenv("STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            } else {
                storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("linreads") }
        release { signingConfig = signingConfigs.getByName("linreads") }
        create("ota") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("linreads")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
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
        getByName("test") {
            if (readflowPhase >= 2) {
                // Shared pure contracts for phase2 JVM + instrumentation harnesses.
                java.srcDir("src/phase2TestSupport/java")
                java.srcDir("src/phase2Test/java")
            }
        }
        getByName("androidTest") {
            if (readflowPhase >= 2) {
                java.srcDir("src/phase2TestSupport/java")
                java.srcDir("src/phase2AndroidTest/java")
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
        implementation(project(":render:md"))
        implementation(project(":render:cbz"))
        implementation(project(":render:animate"))
        implementation(project(":features:reader"))
        implementation(project(":features:settings"))
    }

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)
    implementation(libs.activity.compose)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.bundles.koin)
    implementation(libs.bundles.room)
    // Other render engines (epub/pdf/md) + settings: shells only, wired when implemented
    // Handwriting (:ink): Phase 3
    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
}
