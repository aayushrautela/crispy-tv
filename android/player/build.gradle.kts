plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.player"
    compileSdk = 37

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
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.core)
}
