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

        // This module had no test source set at all -- not a `commonTest`, not
        // an `androidHostTest` -- and an earlier sweep recorded it as a
        // measured non-finding on the grounds that its `commonMain` is two files
        // and a suite there would re-assert the compiler. That reasoning was
        // about the wrong file. `WatchProgressStore.kt` is 405 lines of
        // `androidMain` logic, it has **no `android.*` import at all** -- only
        // the four `:platform-core` ports, coroutines, `org.json` and
        // `kotlin.math` -- and nothing was testing any of it.
        //
        // The suite started in an `androidHostTest` under Robolectric, because
        // the code was in `androidMain` and pinned there by `org.json`, which is
        // a class of the Android platform and absent from every other target's
        // classpath. **Both reasons are gone.** `WatchProgressStore` is in
        // `commonMain` and parses with `JsonElement`, so the suite is in
        // `commonTest`, needs no Robolectric, and runs on every target --
        // desktop JVM and Android host included, which is the two-halves rule
        // the repo records. `implementation(libs.robolectric)` was removed
        // here rather than left for a suite that does not exist.
        withHostTest {}
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
        commonTest.dependencies {
            implementation(kotlin("test"))

            // `UnconfinedTestDispatcher`, for the recorded reason: a class that
            // takes a `CoroutineScope` and would otherwise build its own is only
            // testable once the scope is injected, and `Dispatchers.setMain` is
            // not the answer -- it replaces `Dispatchers.Main` and the class
            // never uses it. Unconfined runs the body eagerly on the calling
            // thread, so `WatchProgressStoreHostTest` needs no
            // `advanceUntilIdle` and nothing in it waits out a debounce.
            implementation(libs.coroutines.test)
        }

        commonMain.dependencies {
            api(project(":android:core-domain"))
            api(libs.coroutines.core)

            // **Declared here because the consumer moved here.**
            // `WatchProgressStore` was in `androidMain` and took the four ports
            // as constructor parameters, and it got them from
            // `:platform-android` in `androidMain.dependencies` -- which is a
            // plain `com.android.library`, so nothing above this line can see
            // it. The file now lives in `commonMain` and names `KeyValueStore`,
            // `TimeSource`, `MonotonicClock` and `AppLogger` directly, so the
            // dependency moves down with it. This is the recorded ordering
            // constraint: **a dependency moves down when the consumer moves
            // down**, and a file's dependency is declared in the source set the
            // file is in, not where it used to be.
            api(project(":android:platform-core"))

            // **The node type, and it is decided rather than open.**
            // `WatchProgressStore` was pinned to `androidMain` solely by
            // `org.json`, and `org.json` is a class of the Android platform
            // supplied by `android.jar` -- it is not a dependency of this
            // project, so there is no version to bump and no artifact to swap.
            //
            // `JsonElement` is the replacement because of one measured
            // property: **a JSON null and an absent key are different events**,
            // and `optNullableString` depends on exactly that. A bare
            // `Any?`/`Map<String, Any?>` tree cannot carry the distinction --
            // this file's own `toAnyMap`-style helpers collapse it -- and
            // writing a node type of our own is a parser we would then have to
            // test as carefully as the one being replaced.
            //
            // It was already in `libs.versions.toml` with zero consumers, so
            // this is a declaration rather than a version resolution.
            implementation(libs.serialization.json)
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
