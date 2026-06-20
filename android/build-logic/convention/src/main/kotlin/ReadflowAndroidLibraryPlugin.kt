import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Layer 1 android-library modules (`:core:calibre`/`:core:database`/`:core:prefs`/
 * `:core:sync`/`:extensions:api`). compileSdk=36, minSdk=26, targetSdk=36, jvmTarget=17.
 * Compose intentionally NOT applied here (Layer 1 forbids Compose, v4 §3.2).
 */
class ReadflowAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
        }
        extensions.configure<LibraryExtension> {
            configureAndroidKotlin(this)
            defaultConfig.targetSdk = 36
        }
    }
}
