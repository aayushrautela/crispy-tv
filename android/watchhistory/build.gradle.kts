plugins {
    id("com.android.library")
}

android {
    namespace = "com.crispy.rewrite.watchhistory"
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
    implementation(project(":android:player"))
    implementation(project(":android:network"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
