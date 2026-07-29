import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val updateHelperProtocolVersion = providers.gradleProperty("readflow.updateHelperProtocolVersion")
    .get().toInt().also { require(it > 0) }

android {
    namespace = "dev.readflow.updaterhelper"
    compileSdk = 36

    val buildVersionCode = System.getenv("BUILD_VERSION_CODE")?.let { rawValue ->
        rawValue.toIntOrNull()?.takeIf { it > 0 }
            ?: error("BUILD_VERSION_CODE must be a positive 32-bit integer")
    } ?: 1

    defaultConfig {
        applicationId = "dev.readflow.updaterhelper"
        minSdk = 26
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = "0.1.0"
        resValue("string", "app_name", "LinReads 更新服务")
        buildConfigField("int", "UPDATE_HELPER_PROTOCOL_VERSION", updateHelperProtocolVersion.toString())
        manifestPlaceholders["updateHelperProtocolVersion"] = updateHelperProtocolVersion
    }

    signingConfigs {
        create("linreads") {
            val encodedKeystore = System.getenv("KEYSTORE_BASE64")
            if (encodedKeystore != null) {
                val keystore = rootProject.layout.buildDirectory.file("linreads-signing.jks").get().asFile
                keystore.parentFile.mkdirs()
                keystore.writeBytes(Base64.getDecoder().decode(encodedKeystore))
                storeFile = keystore
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

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation(libs.junit5)
}
