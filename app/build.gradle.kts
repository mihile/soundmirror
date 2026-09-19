plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePath = providers.environmentVariable("SOUNDMIRROR_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("SOUNDMIRROR_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SOUNDMIRROR_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SOUNDMIRROR_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.soundmirror.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.soundmirror.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.3"

        ndk {
            // arm64 covers virtually all modern phones; v7a for older 32-bit devices.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // The UI is Korean-only; drop the ~80 other locale resource tables that
        // Compose/Material3 pull in, shrinking the APK with no functional change.
        resourceConfigurations += listOf("en", "ko")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            // R8 shrink + obfuscate, and strip unused resources. The keep rules in
            // proguard-rules.pro protect the JNI entry points (NativeOpus) and the
            // Concentus fallback decoder from being renamed/removed.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = when {
                hasReleaseSigning -> signingConfigs.getByName("release")
                providers.gradleProperty("allowDebugSigning").orNull == "true" ->
                    signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Pure-Java Opus codec (no NDK) for the OPUS wire codec.
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
