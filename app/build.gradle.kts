// app — foreground service host, engine status UI, model download UI, security management UI.
// This is the Android application module for the engine process.
// Must not contain inference logic, IPC implementation, or model storage logic.
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

// ─── Release signing contract (documented in BUILDING.md) ─────────────────────
//
// One canonical property-naming scheme:
//   R2H_RELEASE_STORE_FILE, R2H_RELEASE_STORE_PASSWORD,
//   R2H_RELEASE_KEY_ALIAS,  R2H_RELEASE_KEY_PASSWORD
//
// Sources (standard Gradle precedence, command line wins):
//   1. Gradle properties (-PR2H_RELEASE_* …)            — CI / ad-hoc overrides
//   2. <GRADLE_USER_HOME>/gradle.properties              — developer-local secrets
//
// The repository does not read a signing-properties file from inside the
// checkout. No real credential value belongs in this build script, the root
// gradle.properties, local.properties, or any other shared project file.
//
// R2H_RELEASE_STORE_FILE may be absolute, or relative to the REPOSITORY ROOT.
// Relative paths are deliberately resolved from rootProject.projectDir — never
// against this module directory.

fun releaseSigningProperty(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseSigningProperty("R2H_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperty("R2H_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperty("R2H_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperty("R2H_RELEASE_KEY_PASSWORD")

val releaseSigningConfigured =
    !releaseStoreFilePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

val releaseStoreFile: File? = releaseStoreFilePath?.let { raw ->
    val candidate = File(raw)
    if (candidate.isAbsolute) candidate else rootProject.file(raw)
}

android {
    namespace   = "io.r2h.engine"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.r2h.engine"
        minSdk        = 28
        targetSdk     = 35
        versionCode   = 1
        versionName   = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // All first-party and prebuilt native runtimes in this product
        // configuration are arm64-only. Apply the filter at the application
        // boundary so transitive AAR JNI payloads are filtered as well.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl        = false
        viewBinding = true
    }

    androidResources {
        noCompress += listOf(
            "json",
            "txt",
            "yaml"
        )
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = requireNotNull(releaseStoreFile)
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable    = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable    = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Production models are external user-selected files. Only the generated,
            // explicitly allowlisted metadata/test-input directory is packaged.
            assets.setSrcDirs(listOf("build/generated/packaged-assets"))
        }
        getByName("debug") {
            assets.setSrcDirs(listOf("src/debug/assets", "build/generated/runtime-smoke-assets"))
        }
    }
}

val verifyReleaseSigningConfiguration by tasks.registering {
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is required but not configured.\n" +
                "Add the canonical keys to <GRADLE_USER_HOME>/gradle.properties or supply " +
                "them as Gradle properties: R2H_RELEASE_STORE_FILE, " +
                "R2H_RELEASE_STORE_PASSWORD, R2H_RELEASE_KEY_ALIAS, R2H_RELEASE_KEY_PASSWORD. " +
                "See BUILDING.md for setup instructions."
        }

        val configuredStore = requireNotNull(releaseStoreFile)
        check(configuredStore.isFile) {
            "Configured release keystore does not exist: ${configuredStore.absolutePath}"
        }
    }
}

tasks.matching {
    it.name in setOf(
        "packageRelease",
        "bundleRelease",
        "signReleaseBundle",
        "assembleRelease",
        "installRelease",
    )
}.configureEach {
    dependsOn(verifyReleaseSigningConfiguration)
}

val forbiddenModelAssetPatterns = listOf(
    "**/*.gguf",
    "**/*.onnx",
    "**/*.pt",
    "**/*.pth",
    "**/*.safetensors",
    "**/*.bin",
    "**/*.task",
    "**/model-pack/**",
    "**/models/**",
)

val preparePackagedAssets by tasks.registering(Sync::class) {
    from("src/main/assets") {
        include("descriptors/**", "labels/**", "test-inputs/**")
        forbiddenModelAssetPatterns.forEach { exclude(it) }
    }
    into(layout.buildDirectory.dir("generated/packaged-assets"))
}

val copyRuntimeSmokeAssets by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/runtime-smoke-assets/runtime-smoke"))

    from(rootProject.file("third_party/runtimes/whisper.cpp/samples/jfk.wav")) {
        into("whisper")
    }
    from(rootProject.file("third_party/runtimes/llama.cpp/tools/mtmd/test-1.jpeg")) {
        into("qwen-image")
    }
    forbiddenModelAssetPatterns.forEach { exclude(it) }
}

tasks.matching { it.name in setOf("mergeDebugAssets", "mergeDebugAndroidTestAssets") }.configureEach {
    dependsOn(preparePackagedAssets, copyRuntimeSmokeAssets)
}
val releasePackagedAssetConsumers = setOf(
    "mergeReleaseAssets",
    "generateReleaseLintModel",
    "generateReleaseLintReportModel",
    "generateReleaseLintVitalReportModel",
    "lintAnalyzeRelease",
    "lintReportRelease",
    "lintRelease",
    "lintVitalAnalyzeRelease",
    "lintVitalReportRelease",
    "lintVitalRelease",
)

tasks.matching { it.name in releasePackagedAssetConsumers }.configureEach {
    dependsOn(preparePackagedAssets)
}

val verifyDebugModelPackaging by tasks.registering {
    dependsOn("mergeDebugAssets")
    doLast {
        val forbiddenExtension = Regex("\\.(gguf|onnx|pt|pth|safetensors|bin|task)$", RegexOption.IGNORE_CASE)
        val forbiddenSegment = Regex("(^|[/\\\\])(model-pack|models)([/\\\\]|$)", RegexOption.IGNORE_CASE)
        val packagedModels = tasks.named("mergeDebugAssets").get().outputs.files.asFileTree.files.filter { file ->
            forbiddenExtension.containsMatchIn(file.name) || forbiddenSegment.containsMatchIn(file.invariantSeparatorsPath)
        }
        check(packagedModels.isEmpty()) {
            "Heavy model assets must not be packaged:\n${packagedModels.joinToString("\n") { it.absolutePath }}"
        }
    }
}

tasks.matching { it.name == "packageDebug" }.configureEach {
    dependsOn(verifyDebugModelPackaging)
}

// ─── Release native-library packaging guard (P0 regression test) ─────────────
// Fails the build if the release APK ever ships non-arm64 native libraries,
// loses a required runtime, or reintroduces the duplicate ONNX Runtime
// payload (libort-runtime.so). See BUILDING.md.

val expectedArm64NativeLibraries = setOf(
    "libr2h_native.so",
    "libncnn.so",
    "libomp.so",
    "libonnxruntime.so",
    "libonnxruntime4j_jni.so",
    "libsherpa-onnx-jni.so",
    "libsherpa-onnx-c-api.so",
    "libsherpa-onnx-cxx-api.so",
)

val verifyReleaseNativeLibraryPackaging by tasks.registering {
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apks = apkDir.listFiles { f: File -> f.isFile && f.extension.equals("apk", ignoreCase = true) }.orEmpty()
        check(apks.isNotEmpty()) { "Release APK not found for native-library packaging verification." }
        val apk = apks.maxByOrNull { it.lastModified() }!!
        ZipFile(apk).use { zip ->
            val libEntries = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { it.startsWith("lib/") }
                .toList()

            val forbiddenAbis = setOf("armeabi-v7a", "x86", "x86_64")
            val wrongAbiEntries = libEntries.filter { entry -> forbiddenAbis.any { entry.startsWith("lib/$it/") } }
            check(wrongAbiEntries.isEmpty()) {
                "Release APK must ship arm64-v8a native libraries only; found:\n" +
                    wrongAbiEntries.joinToString("\n")
            }

            val arm64Names = libEntries
                .filter { it.startsWith("lib/arm64-v8a/") }
                .map { it.substringAfterLast('/') }
                .toSet()

            val missing = expectedArm64NativeLibraries - arm64Names
            check(missing.isEmpty()) {
                "Release APK is missing required arm64-v8a native libraries:\n" +
                    missing.joinToString("\n")
            }

            check("libort-runtime.so" !in arm64Names) {
                "Duplicate ONNX Runtime payload 'libort-runtime.so' must not be packaged; " +
                    "sherpa-onnx binds to the canonical 'libonnxruntime.so'."
            }
        }
    }
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    finalizedBy(verifyReleaseNativeLibraryPackaging)
}

dependencies {
    // AIDL stubs and shared IPC data classes.
    implementation(project(":engine-api"))

    // Inference orchestration layer — the app starts/stops the service; core runs inside it.
    implementation(project(":engine-core"))

    // Debug-only runtime smoke runner calls the JNI bridge directly.
    debugImplementation(project(":engine-native"))

    // Model lifecycle management.
    implementation(project(":model-manager"))

    // Caller validation and allowlist management.
    implementation(project(":security"))

    // Local telemetry reads (stats screen).
    implementation(project(":telemetry"))

    // Android UI baseline.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // LifecycleService base class for EngineService.
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    // Coroutines for non-blocking service operations.
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(project(":engine-native"))
}
