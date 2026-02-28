plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val tmdbApiKey =
    (providers.gradleProperty("TMDB_API_KEY").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val traktClientId =
    (providers.gradleProperty("TRAKT_CLIENT_ID").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val traktClientSecret =
    (providers.gradleProperty("TRAKT_CLIENT_SECRET").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val traktRedirectUri =
    (providers.gradleProperty("TRAKT_REDIRECT_URI").orNull ?: "crispy://auth/trakt")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val simklClientId =
    (providers.gradleProperty("SIMKL_CLIENT_ID").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val simklClientSecret =
    (providers.gradleProperty("SIMKL_CLIENT_SECRET").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val simklRedirectUri =
    (providers.gradleProperty("SIMKL_REDIRECT_URI").orNull ?: "crispy://auth/simkl")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val supabaseUrl =
    (providers.gradleProperty("SUPABASE_URL").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val supabaseAnonKey =
    (providers.gradleProperty("SUPABASE_ANON_KEY").orNull ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val introDbApiUrl =
    (providers.gradleProperty("INTRODB_API_URL").orNull ?: "https://api.introdb.app")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val metadataAddonUrls =
    (providers.gradleProperty("METADATA_ADDON_URLS").orNull ?: "")
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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crispy.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientId\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"$traktClientSecret\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"$traktRedirectUri\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"$simklClientId\"")
        buildConfigField("String", "SIMKL_CLIENT_SECRET", "\"$simklClientSecret\"")
        buildConfigField("String", "SIMKL_REDIRECT_URI", "\"$simklRedirectUri\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "INTRODB_API_URL", "\"$introDbApiUrl\"")
        buildConfigField("String", "METADATA_ADDON_URLS", "\"$metadataAddonUrls\"")
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
            isMinifyEnabled = false
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
    implementation(project(":android:player"))
    implementation(project(":android:native-engine"))
    implementation(project(":android:network"))
    implementation(project(":android:watchhistory"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.7")

    implementation("androidx.paging:paging-runtime:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha14")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.materialkolor:material-kolor:4.1.1")

    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.work:work-runtime-ktx:2.10.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
