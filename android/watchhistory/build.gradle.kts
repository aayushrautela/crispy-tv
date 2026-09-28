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
 * | `WatchProgressStore` | `androidMain` | already on `KeyValueStore`, `TimeSource`, `MonotonicClock` and `AppLogger`. Only `org.json` keeps it here |
 * | `BackendWatchHistoryService` | `androidMain` | already free of `Context`, `java.time` and `Log`, via `formatIso8601Instant`, `AppLogger` and `TimeSource`. It reaches `:android:backend` and `:android:player`, neither of which is portable yet |
 * | `WatchSyncSource` | `androidMain` | one concrete class over an OkHttp/okio server-sent-event stream. There is no interface to split out, and inventing one with a single implementation would be the "helper just to link to it" this plan exists to avoid. It becomes portable when the transport does, i.e. with `HttpClientPort` |
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
