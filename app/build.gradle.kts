plugins {
    id("com.android.application")
}

// Task to download sherpa-onnx AAR if missing (for worktree builds)
tasks.register("downloadSherpaAar") {
    val aarFile = file("libs/sherpa-onnx-1.12.23.aar")
    val libsDir = file("libs")
    val urlString = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-1.12.23.aar"

    outputs.file(aarFile)

    doLast {
        if (!aarFile.exists()) {
            libsDir.mkdirs()
            println("Downloading sherpa-onnx AAR from $urlString...")
            // Use curl via Runtime.exec for reliable redirect handling
            val process = Runtime.getRuntime().exec(arrayOf("curl", "-L", "-o", aarFile.absolutePath, urlString))
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("Failed to download sherpa-onnx AAR (curl exit code: $exitCode)")
            }
            println("Downloaded sherpa-onnx AAR to ${aarFile.absolutePath}")
        } else {
            println("sherpa-onnx AAR already exists at ${aarFile.absolutePath}")
        }
    }
}

// Ensure AAR is downloaded before pre-build
tasks.named("preBuild") {
    dependsOn("downloadSherpaAar")
}

android {
    namespace = "com.ifautofab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ifautofab"
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = "1.20"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }

        val properties = java.util.Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        val groqKey = properties.getProperty("groq.api.key") ?: ""
        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release-key.jks")
            storePassword = "fentons4pammY"
            keyAlias = "key0"
            keyPassword = "fentons4pammY"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.media:media:1.7.0")

    // Coroutines (for async operations)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Sherpa-ONNX TTS (local AAR from official GitHub releases)
    // Contains OfflineTts, OfflineTtsConfig, and native .so libraries
    // Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-1.12.23.aar
    // Use providers.provider for lazy evaluation to allow downloadSherpaAar task to run first
    implementation(files(providers.provider { file("libs/sherpa-onnx-1.12.23.aar") }))

    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.26.0")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20230227")  // Standard JSON lib for unit tests
}
