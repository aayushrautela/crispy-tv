plugins {
    id("com.android.library")
}

android {
    namespace = "com.crispy.tv.home"
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
    implementation(project(":android:core-domain"))
    implementation(project(":android:backend"))
    implementation(project(":android:addons"))
    implementation(project(":android:player"))

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.runtime:runtime")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
