plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.youtubetractor"
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
    // The interface and the `TrailerPlaybackSource` data type live here.
    implementation(project(":android:network"))

    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    // JitPack git-tag version, not a normal release — left out of the catalog.
    // Its presence in this module is the whole reason the module is
    // sideload-only, so the store exclusion is structural.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
