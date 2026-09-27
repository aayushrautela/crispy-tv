plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.crispy.tv.platform"
        compileSdk = 37
        minSdk = 26
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()
}
