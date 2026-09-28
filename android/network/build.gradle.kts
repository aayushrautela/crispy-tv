plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    // No flavour axis, deliberately.
    //
    // This module used to carry `store` / `sideload` so that
    // `YouTubeTrailerExtractor` could have two implementations and
    // NewPipeExtractor could be declared `sideloadImplementation`. That was the
    // only reason for the axis, and it made the module unconsumable by
    // `:app` once `:app` became a Kotlin Multiplatform library: the KMP library
    // plugin is single-variant, so it expressed no preference between
    // `storeDebugApiElements` and `sideloadDebugApiElements` and Gradle failed
    // with an ambiguous-variant error.
    //
    // The axis now lives only in `:androidApp`. The interface is
    // `TrailerExtractor` in this module's main source set; the sideload-only
    // implementation is `:android:youtube-extractor`. Six modules depend on
    // this one, and none of them wants to know about a distribution.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.coroutines.android)

    api(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
}
