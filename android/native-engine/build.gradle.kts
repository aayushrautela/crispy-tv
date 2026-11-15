plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.nativeengine"
    compileSdk = 37

    defaultConfig {
        missingDimensionStrategy("distribution", "sideload")
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // `api`, not `implementation`: `NativePlaybackEnginePreference` and
    // `PlayerResizeMode` moved to :core-domain so that a KMP `commonMain` can
    // name them, and they appear in this module's own public signatures. With
    // `implementation` a consumer that only depends on :native-engine would see
    // an unresolvable type in those signatures.
    api(project(":android:core-domain"))
    implementation(project(":android:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    implementation(libs.libmpv)
}
