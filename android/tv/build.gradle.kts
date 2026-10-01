plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val supabaseUrl =
    (providers.gradleProperty("SUPABASE_URL").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val supabasePublishableKey =
    (providers.gradleProperty("SUPABASE_PUBLISHABLE_KEY").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val crispyBackendUrl =
    (providers.gradleProperty("CRISPY_BACKEND_URL").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val introDbApiUrl =
    (providers.gradleProperty("INTRODB_API_URL").orNull ?: "https://api.introdb.app")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val releaseKeystorePath = providers.gradleProperty("RELEASE_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull

val debugKeystorePath = providers.gradleProperty("DEBUG_KEYSTORE_PATH").orNull
val debugKeystorePassword = providers.gradleProperty("DEBUG_KEYSTORE_PASSWORD").orNull
val debugKeyAlias = providers.gradleProperty("DEBUG_KEY_ALIAS").orNull
val debugKeyPassword = providers.gradleProperty("DEBUG_KEY_PASSWORD").orNull

android {
    namespace = "com.crispy.tv.tv"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.crispy.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
        buildConfigField("String", "CRISPY_BACKEND_URL", "\"$crispyBackendUrl\"")
        buildConfigField("String", "INTRODB_API_URL", "\"$introDbApiUrl\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        val hasReleaseSigning =
            !releaseKeystorePath.isNullOrBlank() &&
                !releaseKeystorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()

        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }

        val hasDebugSigning =
            !debugKeystorePath.isNullOrBlank() &&
                !debugKeystorePassword.isNullOrBlank() &&
                !debugKeyAlias.isNullOrBlank() &&
                !debugKeyPassword.isNullOrBlank()

        if (hasDebugSigning) {
            getByName("debug") {
                storeFile = file(debugKeystorePath!!)
                storePassword = debugKeystorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }

        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(project(":android:core-domain"))
    implementation(project(":android:home"))
    implementation(project(":android:player"))
    implementation(project(":android:native-engine"))
    implementation(project(":android:network"))
    // TV is a sideload-only surface, so it takes the YouTube extractor directly
    // rather than through a distribution seam. It used to get it transitively
    // from :android:network's sideload variant; that flavour axis is gone.
    implementation(project(":android:youtube-extractor"))
    implementation(project(":android:watchhistory"))
    implementation(project(":android:platform-android"))
    implementation(project(":android:backend"))
    implementation(project(":android:addons"))
    // Owns the design drawables and the brand composables. `:ui-assets` still
    // supplies the launcher mipmaps, the splash colour and the provider-logo
    // SVGs, none of which can be composeResources.
    implementation(project(":android:sharedUI"))
    implementation(project(":android:ui-assets"))

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.material.kolor)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)

    implementation(libs.coroutines.android)

    implementation(libs.zxing.core)

    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // JUnit only, and deliberately not Robolectric. The one thing this module's tests
    // assert is the token mapping in `ui/theme/Theme.kt`, and that is a plain
    // `darkColorScheme(...)` call producing a `ColorScheme` data class from
    // `androidx.compose.ui.graphics.Color` — an inline value class over `ULong`. No
    // `Context`, no resource, no view. The mapping was recorded as untestable because
    // `:tv` is a plain `com.android.application`, and that was a claim nobody had
    // checked: the blocker was the `private` modifier on `CrispyTvDarkColors`, which
    // the same landing widened to `internal`.
    testImplementation(libs.junit4)
}
