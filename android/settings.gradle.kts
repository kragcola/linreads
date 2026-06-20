pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.ghostscript.com")  // MuPDF
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.ghostscript.com")  // MuPDF
    }
}

rootProject.name = "LinReads"

// Phased include driven by the `readflow.phase` Gradle property (v4 §10.3 / P6).
// Default phase = 1. Override with -Preadflow.phase=2 (or in gradle.properties).
// Rule (F9): a module must sit in the SAME or an EARLIER phase than every module
// it compile-depends on, otherwise `-Preadflow.phase=N assembleDebug` fails with
// "project not found" during dependency resolution.
val readflowPhase = (extra.properties["readflow.phase"] as String?)?.toInt() ?: 1

fun phaseInclude(minPhase: Int, vararg paths: String) {
    if (readflowPhase >= minPhase) paths.forEach { include(it) }
}

// Phase 1 — local reading loop (Phase A). `:features:library`'s deps
// (`:core:ui`, `:core:database`) MUST be present this phase (F9).
phaseInclude(
    1,
    ":app", ":core:model", ":core:calibre", ":core:prefs",
    ":core:sync", ":core:database", ":core:ui",
    ":extensions:api", ":features:library",
)
// Phase 2 — render engines + reader (Phase B + C).
phaseInclude(
    2,
    ":render:api", ":render:epub", ":render:pdf", ":render:txt",
    ":render:md", ":render:animate",
    ":features:reader", ":features:settings",
)
// Phase 3 — premium add-ons (Phase D + E).
phaseInclude(
    3,
    ":render:mupdf", ":ink",
    ":extensions:tts", ":extensions:stats", ":extensions:opds",
)
