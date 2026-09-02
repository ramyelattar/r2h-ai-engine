plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

group = "io.r2h.engine"
version = "0.1.0"

android {
    namespace = "io.r2h.engine.client"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
        singleVariant("debug")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":engine-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.r2h.engine"
                artifactId = "engine-client-sdk"
                version = project.version.toString()
                pom {
                    name.set("R2H Engine Client SDK")
                    description.set("Local-only Android client bridge for the R2H AI Engine AIDL API v2.")
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
