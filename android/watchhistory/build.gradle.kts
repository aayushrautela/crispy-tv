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

            // `:android:player` and `:android:backend` moved DOWN with
            // `BackendWatchHistoryService.kt`, which moved down because it has
            // **zero platform pins** -- 0 `import android.*`, 0 okhttp3, 0
            // `System.currentTimeMillis`, and all 27 of its imports resolve in a
            // `commonMain` source set.
            //
            // The note these two replaced said the ordering constraint is "the
            // service that implements `WatchHistoryService` is in this source set
            // and cannot see it". **The constraint was real; that was not the
            // reason.** Both dependencies were declared here, in `androidMain`, so
            // the service could see them perfectly well. The note had conflated
            // *where a dependency is declared* with *where the file that consumes
            // it lives* -- a file in `androidMain` sees anything `androidMain`
            // declares, and only stops seeing it when the file itself moves. So
            // the two facts have to be kept apart when reasoning about which
            // dependency has to move first: the ordering rule is still "the
            // dependency moves down when the *consumer* moves down", and nothing
            // about it is a claim that an already-declared dependency is invisible
            // to the source set that declared it.
            //
            // `:android:backend` could not have moved down before `70ce5243` and
            // `a0e975b6`, because `CrispyBackendClient` was a *receiver* of this
            // file's parse calls and is only now in `commonMain`. **That is the
            // same ordering constraint the note described, applied to the module
            // it did not mention** -- so the note was right in shape and wrong in
            // subject, which is the harder kind of stale to notice.
            implementation(project(":android:player"))
            implementation(project(":android:backend"))
        }

        androidMain.dependencies {
            // The Android half of platform-core's contracts. `api` because callers
            // inject these types into their own constructors.
            api(project(":android:platform-android"))

            // `:android:player` and `:android:backend` used to be here, with a
            // note explaining why they could not move down. They have moved down
            // to `commonMain` alongside the service, which had no platform pin at
            // all; see the comment there for why the note's stated reason was not
            // the real one.
            //
            // `:android:network` is the last dependency here, and the reason is
            // the ordinary one after all: its only consumer in this module is
            // `OkHttpWatchSyncSource.kt`, **the one `androidMain` file that
            // remains**, so the dependency cannot move down until that file does.
            //
            // **What that file is actually blocked on is not OkHttp.** The note
            // here used to say it would be freed by "a second implementation over
            // `:network`'s `CrispyHttpClient` port, the same move
            // `OkHttpCrispyHttpClient` was" -- and that is not a thing that can be
            // written. `OkHttpWatchSyncSource` is an **SSE client**: it opens one
            // long-lived call and reads the body *line by line* off a
            // `BufferedSource`, dispatching on `event:` / `data:` frames, for up
            // to 30 minutes. `CrispyHttpClient` is a **request/response** port --
            // `get` reads the entire body and returns `CrispyHttpResponse(code,
            // body)` -- so **no member of it can express an unterminated stream.**
            //
            // **So the pin is the port's shape, not the port's existence**, and the
            // fix is a streaming member on the transport seam with exactly one
            // caller -- a product decision, not a mechanical move. `:network`
            // itself stays here for the ordinary ordering reason stated above.
            implementation(project(":android:network"))

            implementation(libs.coroutines.android)
        }
    }
}
