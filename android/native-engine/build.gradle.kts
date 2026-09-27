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
    implementation(project(":android:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    api(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.extractor)
    api(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.effect)

    implementation(libs.libmpv)
    implementation(libs.ass.media)
}
