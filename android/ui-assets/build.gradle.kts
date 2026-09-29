plugins {
    alias(libs.plugins.android.library)
}

// Android-only assets that cannot be composeResources: the launcher mipmaps
// (referenced from AndroidManifest.xml), the splash colour and the two splash
// drawables (referenced from `windowSplashScreenAnimatedIcon` in a theme), and
// the nine provider-logo SVGs, which Compose Multiplatform documents as
// unsupported on Android. The design drawables and the brand composables moved
// to `:android:sharedUI`.
android {
    namespace = "com.crispy.tv.ui.assets"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}
