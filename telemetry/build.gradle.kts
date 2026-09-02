// telemetry — local structured event logging. A passive sink with no outbound traffic.
// No project dependencies. Receives events; never initiates them.
// Must never log prompt or response content.
plugins {
    alias(libs.plugins.android.library)
    // AGP 9+ auto-provides Kotlin support; do not apply 'org.jetbrains.kotlin.android' here.
}

android {
    namespace  = "io.r2h.engine.telemetry"
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
    // File I/O helpers and Context for filesDir access.
    implementation(libs.androidx.core.ktx)

    // Fire-and-forget writes on Dispatchers.IO without blocking callers.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

