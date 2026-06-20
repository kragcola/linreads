import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Layer 6 feature modules (`:features:library` in phase1). Inherits the Compose
 * convention, then adds viewmodel/navigation Compose integration.
 */
class ReadflowFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("readflow.compose")
        dependencies {
            libs.findLibrary("lifecycle-viewmodel-compose").ifPresent {
                add("implementation", it.get())
            }
            libs.findLibrary("navigation-compose").ifPresent {
                add("implementation", it.get())
            }
        }
    }
}
