plugins {
    id("com.android.library")
}

android {
    namespace = "com.crispy.tv.ui.assets"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
