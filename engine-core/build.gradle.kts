// engine-core — inference orchestration, request queue, session management.
// Owns: IEngineService implementation, InferenceOrchestrator, RequestQueue, SessionManager.
// Calls engine-native for all JNI operations. Must not import :app.
plugins {
    alias(libs.plugins.android.library)
    // AGP 9+ auto-provides Kotlin support; do not apply 'org.jetbrains.kotlin.android' here.
}

android {
    namespace  = "io.r2h.engine.core"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // IPC contracts and all data classes crossing the process boundary.
    implementation(project(":engine-api"))

    // JNI bridge — engine-core is the only module that calls engine-native.
    implementation(project(":engine-native"))

    // Coroutine dispatchers, Channel, StateFlow for the request queue and orchestrator.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // LifecycleCoroutineScope for tying coroutine scopes to service lifecycle.
    implementation(libs.lifecycle.runtime.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
}

