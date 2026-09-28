plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Watch progress persistence and the backend watch-history service.
 *
 * The second-largest Phase 2 module and the first one that genuinely uses
 * `platform-core`'s contracts, so the split here is uneven on purpose: the
 * *types* a portable caller needs are in `commonMain`, and everything that touches
 * `SharedPreferences`, `android.util.Log`, `java.time` or OkHttp stays in
 * `androidMain` until the specific work below removes it.
 *
 * | file | where | why |
 * |---|---|---|
 * | `WatchHistoryConfig` | `commonMain` | a data class, portable as-is |
 * | `WatchProgressStore` | `androidMain` | `SharedPreferences` + `SystemClock.elapsedRealtime()`; the migration is onto `KeyValueStore` and an injected `MonotonicClock` |
 * | `BackendWatchHistoryService` | `androidMain` | `Context`, `java.time.Instant`, `Log`; the migration is onto an `Iso8601` formatter, `AppLogger` and `TimeSource` |
 * | `WatchSyncSource` | `androidMain` | OkHttp and okio; `commonMain` keeps only the interface once the implementation splits off |
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.watchhistory"
        compileSdk = 37
        minSdk = 26
    }

    jvm("desktop")

    // Compile-only verification target, never shipped and never run. See
    // :android:core-domain for why it exists and why the import-based purity gate
    // cannot substitute for it.
    linuxX64()

    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":android:core-domain"))
            api(libs.coroutines.core)
        }

        androidMain.dependencies {
            // The Android half of platform-core's contracts. `api` because callers
            // inject these types into their own constructors.
            api(project(":android:platform-android"))

            // :android:player is now multiplatform, so it could in principle move
            // down to commonMain. It does not yet, because the service that
            // implements `WatchHistoryService` is in this source set and cannot see
            // it. That is the same ordering constraint as `HttpClientPort`: the
            // dependency moves down when the *consumer* moves down.
            implementation(project(":android:player"))
            implementation(project(":android:network"))
            implementation(project(":android:backend"))

            implementation(libs.coroutines.android)
        }
    }
}
