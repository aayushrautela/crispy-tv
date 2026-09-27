plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.watchhistory"
    compileSdk = 37

    defaultConfig {
        missingDimensionStrategy("distribution", "sideload")
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":android:core-domain"))
    implementation(project(":android:player"))
    implementation(project(":android:network"))
    implementation(project(":android:backend"))

    implementation(libs.coroutines.android)
}
