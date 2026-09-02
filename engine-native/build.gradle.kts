// engine-native — JNI bridge to the native inference runtime (llama.cpp).
// Owns: NativeInferenceEngine.kt, JNI C++ source, CMake configuration.
// No project dependencies. Only engine-core is permitted to call this module.
//
// NDK SETUP REQUIRED:
//   Install NDK via SDK Manager: "NDK (Side by side)" → version 27.2.12479018
plugins {
    alias(libs.plugins.android.library)
    // AGP 9+ auto-provides Kotlin support; do not apply 'org.jetbrains.kotlin.android' here.
}

android {
    namespace  = "io.r2h.engine.nativebridge"
    compileSdk = 37

    // Pin NDK version for reproducible native builds.
    // Install via: sdkmanager "ndk;27.2.12479018"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Production target: arm64-v8a (NEON available on all ARMv8 Android devices).
        // Add "x86_64" here if emulator support is needed during development.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        // CMake arguments forwarded to llama.cpp's build system.
        // LLAMA_NATIVE=OFF is critical for cross-compilation: it prevents the
        // host-native SIMD detection from emitting instructions unsupported on ARM.
        externalNativeBuild {
            cmake {
                arguments(
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_NATIVE=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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

    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(
                listOf(
                    "src/main/jniLibs",
                    "build/generated/runtime-jniLibs",
                )
            )
            java.setSrcDirs(
                listOf(
                    "src/main/java",
                )
            )
            kotlin.srcDir("../third_party/runtimes/sherpa-onnx/sherpa-onnx/kotlin-api")
        }
    }
}

val generatedRuntimeJniLibs = layout.buildDirectory.dir("generated/runtime-jniLibs")
val canonicalOnnxRuntimeVersion = "1.24.3"
val ncnnRuntimeFile = rootProject.file(
    "third_party/runtimes/ncnn/extracted/" +
        "ncnn-20260526-android-shared/arm64-v8a/lib/libncnn.so"
)
val sherpaRuntimeDirectory = rootProject.file(
    "third_party/runtimes/sherpa-onnx-prebuilt-android/extracted/" +
        "sherpa-onnx-v1.13.3-android/jniLibs/arm64-v8a"
)
val requiredSherpaRuntimeLibraries = listOf(
    "libsherpa-onnx-jni.so",
    "libsherpa-onnx-c-api.so",
    "libsherpa-onnx-cxx-api.so",
)

// Packages the prebuilt runtime JNI set for arm64-v8a.
//
// ONNX Runtime policy (single canonical implementation):
//   The sherpa-onnx v1.13.3 prebuilts natively DT_NEEDED "libonnxruntime.so".
//   They previously shipped a byte-patched private copy renamed to
//   "libort-runtime.so", producing TWO full ORT builds in every APK.
//   Dependency tracing (llvm-readelf) shows that sherpa's direct consumers
//   naturally import OrtGetApiBase@VERS_1.24.3. The managed
//   onnxruntime-android 1.24.3 provider exports the exact matching symbol, and
//   its arm64 libonnxruntime.so is byte-identical to sherpa's bundled provider.
//   The bundled duplicate is excluded; all sherpa consumers are copied without
//   binary rewriting.
val copyNativeRuntimeJniLibs by tasks.registering(Sync::class) {
    inputs.property("canonicalOnnxRuntimeVersion", canonicalOnnxRuntimeVersion)

    doFirst {
        check(ncnnRuntimeFile.isFile) {
            "Required arm64 NCNN runtime is missing: ${ncnnRuntimeFile.absolutePath}"
        }
        requiredSherpaRuntimeLibraries.forEach { libraryName ->
            val library = sherpaRuntimeDirectory.resolve(libraryName)
            check(library.isFile) {
                "Required arm64 sherpa-onnx runtime is missing: ${library.absolutePath}"
            }
        }
    }

    from(ncnnRuntimeFile) {
        into("arm64-v8a")
    }
    from(sherpaRuntimeDirectory) {
        into("arm64-v8a")
        exclude("libonnxruntime.so")
        exclude("README")
    }
    into(generatedRuntimeJniLibs)
}

tasks.matching { it.name in setOf("mergeDebugJniLibFolders", "mergeReleaseJniLibFolders") }.configureEach {
    dependsOn(copyNativeRuntimeJniLibs)
}

dependencies {
    // No project dependencies. This module is a pure JNI bridge.
    // Coroutines are used to wrap blocking JNI calls on Dispatchers.IO.
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$canonicalOnnxRuntimeVersion")

    testImplementation(libs.junit4)
}

