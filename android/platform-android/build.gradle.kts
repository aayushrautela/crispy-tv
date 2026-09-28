plugins {
    alias(libs.plugins.android.library)
}

/**
 * The Android implementations of `:android:platform-core`'s contracts.
 *
 * `:android:platform-core` holds `SecretStore`, `KeyValueStore`, `AppLogger` and
 * `TimeSource` in `commonMain` so every target can see the *types*. This module
 * holds the Android *implementations*, because those are by definition Android:
 * `SharedPreferences`, `android.util.Log`, `System.currentTimeMillis`.
 *
 * ## Why it is a module and not a package inside an existing one
 *
 * Every module that converts to multiplatform in Phase 2 needs some of these, and
 * none of them should own them:
 *
 * - `:android:watchhistory` needs `KeyValueStore` and `AppLogger`
 * - `:android:backend` needs all four
 * - `:android:home` needs `AppLogger` and `TimeSource`
 * - `:android:addons` needs `KeyValueStore` and `AppLogger`
 *
 * 26 files across the repository import `android.util.Log` today. Putting the
 * adapter in `:app` would mean every library module depends on the application,
 * which is the dependency direction the whole migration is trying to undo.
 *
 * ## Deliberately a plain `com.android.library`
 *
 * Not multiplatform, and not because of laziness: the implementations are
 * Android-only and there is nothing portable to put in a `commonMain`. The desktop
 * equivalents arrive with the desktop app in Phase 5 and the Apple equivalents in
 * Phase 6, each against the same `platform-core` interfaces. That is the whole
 * point of the interfaces existing.
 */
android {
    namespace = "com.crispy.tv.platform.android"
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
    // The interfaces being implemented. `api` because consumers inject these types
    // into their own constructors, so the type has to be on their classpath.
    api(project(":android:platform-core"))
}
