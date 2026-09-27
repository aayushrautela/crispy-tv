plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.home"
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
    implementation(project(":android:backend"))
    implementation(project(":android:addons"))
    implementation(project(":android:player"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.coroutines.android)
}
