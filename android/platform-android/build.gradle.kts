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
 * - `:android:watchhistory` needs `KeyValueStore`, `AppLogger` and `TimeSource`
 * - `:android:backend` needs `KeyValueStore` and `SecretStore`
 * - `:android:home` needs `AppLogger` and `TimeSource`
 * - `:android:app` needs `KeyValueStore` and `AppLogger`
 *
 * That list is measured, not asserted: `grep -rl --include=*.kt 'com.crispy.tv.platform.<Name>' android`
 * per interface. It changes as modules convert, so re-run it rather than trusting a copy —
 * an earlier version of this comment listed `:android:addons`, which uses none of the four.
 *
 * 22 files across the repository import `android.util.Log` today. Putting the
 * adapter in `:app` would mean every library module depends on the application,
 * which is the dependency direction the whole migration is trying to undo.
 *
 * ## Deliberately a plain `com.android.library`
 *
 * Not multiplatform, and not because of laziness: the implementations are
 * Android-only and there is nothing portable to put in a `commonMain`. The desktop
 * equivalents arrived in Phase 5, in `:android:platform-desktop`, against the same
 * `platform-core` interfaces. That is the whole point of the interfaces existing.
 *
 * ## This paragraph used to promise Apple equivalents in Phase 6, and it was wrong
 *
 * It said the Apple implementations "arrive in Phase 6, each against the same
 * `platform-core` interfaces". Measured 2026-10-01, nothing on Apple references any
 * of the six ports: `grep -rl <port> ios --include=*.swift` returns zero hits for
 * `SecretStore`, `KeyValueStore`, `AppLogger`, `TimeSource`,
 * `DistributionCapabilities` and `MonotonicClock` alike.
 *
 * **The reason is not an oversight in this module. It is that the Apple client is
 * not this codebase.** `ios/CrispyKit` is a 19-file Swift reimplementation of the
 * data layer with its own HTTP client, its own Supabase auth, its own session
 * store, its own JSON and its own seven view models, and `ios/project.yml` links
 * only that package and `ContractRunner` — the `CrispyUI` framework `:sharedUI`
 * exports is built by nothing and imported by nothing. So there is no Apple
 * consumer for a `platform-apple` module to serve, and writing one would be a
 * dependency with no consumer: the one thing this repository's own rules call
 * speculative.
 *
 * **The desktop implementation landed and the Apple one did not, and the difference
 * is a product decision rather than a technical block.** `platform-desktop` has
 * `desktopApp` as a real caller; Apple has no equivalent caller yet. The plan that
 * would create one is Phase 6, which wires `CrispyKit` to `CrispyUI` and reconciles
 * the six Swift files that duplicate shared code — and until that lands, the honest
 * statement is the one above.
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
