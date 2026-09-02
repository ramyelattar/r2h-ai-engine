// model-manager — model download, SHA-256 validation, local storage, hot-swap.
// Standalone module. No dependency on engine-core or engine-native.
// Depends on engine-api only for shared public DTOs such as ModelInfo.
// All model file paths are resolved exclusively through this module.
plugins {
    alias(libs.plugins.android.library)
    // AGP 9+ auto-provides Kotlin support; do not apply 'org.jetbrains.kotlin.android' here.
}

android {
    namespace = "io.r2h.engine.model"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
        aidl = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":engine-api"))

    // Context access for filesDir, DownloadManager, ContentResolver.
    implementation(libs.androidx.core.ktx)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // StateFlow for download progress; IO dispatcher for validation and file ops.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android provides org.json in production; the JVM suite needs the same API.
    testImplementation("org.json:json:20240303")
}
