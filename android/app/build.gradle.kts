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
    namespace = "com.crispy.tv"
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

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
        }
    }

    splits {
        abi {
            isEnable = project.hasProperty("buildFossApks")
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
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
    implementation(project(":android:watchhistory"))
    implementation(project(":android:backend"))
    implementation(project(":android:addons"))
    implementation(project(":android:ui-assets"))
    "fossImplementation"(project(":android:plugins"))

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.google.material)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.core)
    implementation(libs.material.kolor)
    implementation(libs.metrics.performance)

    implementation(libs.androidyoutubeplayer)

    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation(libs.coroutines.android)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
