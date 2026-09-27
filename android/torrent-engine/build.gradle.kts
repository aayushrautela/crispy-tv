plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.torrentengine"
    compileSdk = 37

    defaultConfig {
        missingDimensionStrategy("distribution", "foss")
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":android:player"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
}
