plugins {
    `kotlin-dsl`
}

group = "dev.readflow.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("readflowJvmLibrary") {
            id = "readflow.jvm.library"
            implementationClass = "ReadflowJvmLibraryPlugin"
        }
        register("readflowAndroidLibrary") {
            id = "readflow.android.library"
            implementationClass = "ReadflowAndroidLibraryPlugin"
        }
        register("readflowCompose") {
            id = "readflow.compose"
            implementationClass = "ReadflowComposePlugin"
        }
        register("readflowFeature") {
            id = "readflow.feature"
            implementationClass = "ReadflowFeaturePlugin"
        }
    }
}
