plugins {
    id("com.android.application")
}

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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crispy.tv.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
