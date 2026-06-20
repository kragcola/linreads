import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Layer 5 (`:core:ui`): android-library + Compose + serialization.
 * Wires the Compose BOM + bundle so UI modules don't repeat the platform() dance.
 */
class ReadflowComposePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
            apply("org.jetbrains.kotlin.plugin.compose")
            apply("org.jetbrains.kotlin.plugin.serialization")
        }
        extensions.configure<LibraryExtension> {
            configureAndroidKotlin(this)
            defaultConfig.targetSdk = 36
            buildFeatures.compose = true
        }
        val libs: VersionCatalog = libs
        dependencies {
            val bom = libs.findLibrary("compose-bom").get()
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
            libs.findBundle("compose").ifPresent { add("implementation", it) }
            libs.findBundle("compose-debug").ifPresent { add("debugImplementation", it) }
        }
    }
}
