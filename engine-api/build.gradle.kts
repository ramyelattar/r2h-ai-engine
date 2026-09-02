// engine-api — the single module that all client apps import.
// Owns: AIDL interfaces, Parcelable data classes, EngineError codes.
// Must never depend on any other project module.
plugins {
    alias(libs.plugins.android.library)
    // Do NOT apply 'org.jetbrains.kotlin.android' with AGP 9+ — AGP provides Kotlin support.
    // Keep parcelize plugin (kotlin.parcelize) because this module owns Parcelable data classes.
    alias(libs.plugins.kotlin.parcelize)
    id("maven-publish")
}

// Published coordinates used by r2h-magician via composite build substitution.
group   = "io.r2h"
version = "0.1.0"

android {
    namespace  = "io.r2h.engine.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // AIDL generation is required for IEngineService and IInferenceCallback.
        aidl      = true
        // No BuildConfig needed in a pure-contract module.
        buildConfig = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    publishing {
        singleVariant("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    // No project dependencies. This module must remain standalone.
    // Android SDK provides Parcelable and IBinder — no additional imports needed.

    testImplementation(libs.junit4)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "engine-api"
                version = project.version.toString()
                pom {
                    name.set("R2H Engine API")
                    description.set("Official AIDL and Parcelable API contract for the R2H AI Engine.")
                }
            }
        }
        repositories {
            maven {
                name = "localR2hEngine"
                url = rootProject.layout.buildDirectory.dir("local-maven").get().asFile.toURI()
            }
            maven {
                name = "phase2Reports"
                url = rootProject.layout.projectDirectory.dir("reports/client-engine-integration/sdk-artifacts/maven").asFile.toURI()
            }
        }
    }
}

