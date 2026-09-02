pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "r2h-ai-engine"

// ── Engine modules ────────────────────────────────────────────────────────────
include(":app")
include(":engine-api")
include(":engine-client-sdk")
include(":engine-core")
include(":engine-native")
include(":model-manager")
include(":security")
include(":telemetry")
