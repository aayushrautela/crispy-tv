plugins {
    id("com.android.library")
}

android {
    namespace = "com.crispy.tv.watchhistory"
    compileSdk = 35

    defaultConfig {
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
