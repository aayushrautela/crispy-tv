plugins {
    id("com.android.library")
}

android {
    namespace = "com.crispy.tv.nativeengine"
    compileSdk = 35

    defaultConfig {
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("androidx.media3:media3-common:1.10.0")
    implementation("androidx.media3:media3-datasource:1.10.0")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.0")
    api("androidx.media3:media3-ui:1.10.0")
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
