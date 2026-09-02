// security — IPC caller validation, allowlist management, signature verification, rate limiting.
// Standalone module. Must not import engine-core, engine-native, or model-manager.
// No UI. No network access. No business logic beyond security enforcement.
plugins {
    alias(libs.plugins.android.library)
    // AGP 9+ auto-provides Kotlin support; do not apply 'org.jetbrains.kotlin.android' here.
}

android {
    namespace  = "io.r2h.engine.security"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
        aidl        = false
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
    // PackageManager access, EncryptedSharedPreferences via security-crypto (add when implementing).
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}

