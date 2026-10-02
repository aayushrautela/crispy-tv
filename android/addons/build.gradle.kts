plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Addon metadata, stream resolution, and the stream value model.
 *
 * The module the plan flagged as the statefulness risk, so here is the answer.
 * `AddonStreamsService` is 1,068 lines and holds five mutable fields, but they are
 * per-request state — a cancellation flag and in-flight bookkeeping — not shared
 * service state. It is a stateful class, not a singleton, and the statefulness
 * turned out not to be what blocks portability. This paragraph used to name the blockers as
 * `Context`, OkHttp and `org.json`, and **all three were false**: no file in the module
 * imports `okhttp3` (the transport is `CrispyHttpClient`, which is `commonMain` and carries
 * only `String` and `Map<String, String>`), no file names `org.json` (it is
 * `kotlinx.serialization`), and `Context` was a constructor parameter or a slot. What actually
 * blocked them, in every case, was a *default import* -- `android.util.Log`,
 * `kotlin.jvm.*`, `java.util.Locale` -- which no import scan sees.
 *
 * So the split is by *file*, not by interface. The first 242 lines of
 * `AddonStreamsService.kt` were data classes and pure string helpers sharing a file
 * with the service; they are now `StreamModels.kt` in `commonMain`, in the same
 * package, so no call site in 17 files changed an import.
 *
 * | file | where | why |
 * |---|---|---|
 * | `StreamModels` | `commonMain` | the 12 data classes plus magnet/torrent parsing; `MetadataLabMediaType` comes from `:android:player`'s `commonMain` |
 * | `MediaModels` | `commonMain` | data classes |
 * | `StreamStableKey` | `commonMain` | pure string rules |
 * | `MediaDetailMappings` | `commonMain` | was `androidMain` for the reason in this row's own earlier wording: every function is an extension *on* a nested backend type, and the nested types were pinned. The nested types were then lifted into `:android:backend` as `BackendTypes`, which is what freed the file — the extension receiver became a portable type. The bodies were always pure mapping code |
 * | `LookupIds`, `StreamLookupSupport`, `StreamSelectorState` | `commonMain` | used `Locale.US` in `lowercase`, which is exactly what Kotlin's locale-independent `lowercase()` already does |
 * | `RatingFormats` | `commonMain` | `String.format` is JVM-only; now uses `core-domain`'s `formatOneDecimal`, pinned against real `%.1f` output |
 * | `AddonStreamsService` | `commonMain` | listed here for "`Context`, OkHttp and `org.json`" and **none of the three was true**; the pins were `Log` (9 sites), `Dispatchers.IO` (3 `withContext` + 2 `async`), `@Volatile`, one `lowercase(Locale.US)` argument, and a `Context` read once to build the registry. All four are slots now, and the file is 904 lines in `commonMain`
     * | `AddonStreamsLoader` | `commonMain` | new. `AddonStreamsService` is a **final class**, so the seam had to be a type rather than `open`. That was affordable only because the census found **1 caller, 3 members and 0 extension receivers** -- contrast `CrispyBackendClient`, whose 39 `internal fun CrispyBackendClient.parseX(...)` receivers are exactly what makes *its* receiver expensive. The interface carries `loadStreams`' defaults, so the three members are plain `override`s
     * | `synchronized(lock) { }` | -- | not a file. `kotlin.synchronized` is an `actual`: present on the JVM, absent from common metadata, and needing no import of its own. `compileKotlinLinuxX64` is the only target in this module that says so, and both sites sat inside a `private suspend fun`, so `Mutex` + `withLock` cost no signature change |
 * | `StreamResolver` | `commonMain` | a type-level port over `CachingStreamResolver`, whose caching is the only thing the class adds and only `cachedStreams` can observe. It is **not** a transport abstraction, and this row used to justify that by claiming `CrispyHttpClient` "leaks `okhttp3` types in its own signature". It does not -- `CrispyHttpResponse(code, body)` and `HttpRequest(..., url: String, headers: Map<String, String>, ...)` name no OkHttp type, and `git grep -l 'import okhttp3' | grep '/src/commonMain/'` returns nothing. The reason is simpler: it is a caching seam, not a transport one |
 * | `CachingStreamResolver` | `commonMain` | this row claimed the blocker was OkHttp and that the class was "built from a `Context` and an `okhttp3` client` -- **both false**. The real pins were `AddonStreamsService` being a final class in the same package (hence a *type*, above), `Log`, `lowercase(Locale.US)`, and two `System.currentTimeMillis()` calls that became a `nowMs` slot -- which is what makes the TTL testable at all. Its eviction of an expired entry needed `internal fun cachedEntryCount()` to be observable at all, because `cachedStreams` answers `null` for a stale key whether or not the entry was removed | -- and `StreamResolver`'s KDoc had already cited this row's invented reason ("`CrispyHttpClient` leaks `okhttp3` types in its own signature") as a reason the port could not be implemented |
 * | `BackendEpisodeListProvider` | `commonMain` | moved once both of its parameters became `BackendApi` / `AccountApi` ports. It had no Android type of its own |
 * | `RemoteSupabaseSyncLabService` | `commonMain` | was listed here for "`Context` and `org.json`", and **neither was ever true**: it named no `org.json` type, and the `Context` was a parameter the class did not read, under a `@Suppress("UNUSED_PARAMETER")` that said so on the class itself. A parameter nobody consults is not a dependency, so deleting it -- rather than slotting it -- is what moved the file. `Dispatchers.IO` became an `ioDispatcher` with no default, and it is the second member of the interface that does *not* enter it (`syncNow`), which `commonTest` pins |
     * | `MetadataAddonRegistry` | `commonMain` | moved once its four pins were measured rather than described. `Context` -> `KeyValueStore` (`:platform-core`'s existing port; `metadataAddonRegistry(context)` in `androidMain` is the only place `SharedPreferences` is named), `System.currentTimeMillis()` -> a `nowMs` slot with no default -- **it needs no import, so no token scan can see it**, and it is the pin that survives a mechanical read of the import list -- `MessageDigest("SHA-1")` + `StandardCharsets` -> `ByteString.encodeUtf8().sha1()`, and `android.net.Uri` -> the `ManifestUri` below. Its `installationId` is **half of every persisted addon identity**, so merely-equivalent was not good enough and the ten goldens in `MetadataAddonRegistryTest` were produced by running the `MessageDigest` code on a JVM and printing it |
     * | `ManifestUri` | `commonMain` | new. The only URL parser in the repository, and it exists because there had to be one: no `commonMain` had any, and `core-domain`'s `normalizeAddonUrl` is a stricter *rule*, not a parser. Written against `UriBehaviourHostTest`'s 26-row table -- **`Uri.toString()` is the identity on every shape, so it carries the normalized string through and has no re-renderer**, which removes the whole class of bug where a port rebuilds a subtly different string from the same components |
     * | `JvmSynchronized` | `commonMain` + `androidMain` | `@OptionalExpectation`, typealiased to `kotlin.jvm.Synchronized` on Android. `kotlin.concurrent.Synchronized` **does not resolve at all** in Kotlin 2.4.10 and `kotlin.jvm.Synchronized` is rejected as an *error* by `compileKotlinLinuxX64`; this is the `Dispatchers.IO` rule through a different symbol |
     * | `RemoteMetadataLabDataSource` | `androidMain` | `Context`, `URLEncoder`, `StandardCharsets`. **The last file here that is not a deliberate platform seam** -- everything else still in this module's `androidMain` is `ManifestUri`-shaped or two lines wide |
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.addons"
        compileSdk = 37
        minSdk = 26

        // This module had no test source set at all, so its `commonMain` went
        // untested on every target -- including the 171 lines of pure lookup and
        // subtitle logic in `StreamLookupSupport.kt`, which is exactly the kind of
        // code that belongs in `commonMain` precisely because it is pure and needs
        // no harness. The block has to be here and not merely implied by a
        // `commonTest` source directory: without it the directory exists, nothing
        // compiles or runs it on Android, and AGP only *warns*.
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
        commonMain.dependencies {
            api(project(":android:core-domain"))

            // For `MetadataLabMediaType`, which the stream model uses. `api` because
            // it appears in the public shape of `StreamModels`.
            api(project(":android:player"))

            api(libs.coroutines.core)

            // For `MediaDetailMappings.kt`: six wire->model extensions whose receivers
            // are all backend response types. Those types already live in `:backend`'s
            // `commonMain` as `BackendTypes.kt`, so the mappings are portable -- but the
            // file could not be moved while `:backend` was reachable only from
            // `androidMain`. `normalizedCatalogMediaType` alone blocked 8 `:app` files.
            implementation(project(":android:backend"))

            // For `MetadataAddonRegistry`'s `KeyValueStore`. `api` because the type
            // appears in the class's own public constructor: a consumer that holds a
            // `MetadataAddonRegistry` has to be able to see what it was built from,
            // and `:platform-core` is a plain KMP module that publishes a JVM variant
            // so `commonMain` *can* see it -- which is the whole difference between
            // this and `:platform-android` one block down.
            api(project(":android:platform-core"))

            // For `AddonStreamsService`: `CrispyHttpClient` is **`:network`'s own
            // `commonMain` interface**, over
            // `data class CrispyHttpResponse(val code: Int, val body: String)`, and
            // `git grep -l 'import okhttp3' | grep '/src/commonMain/'` returns zero
            // hits repo-wide -- so the module declares `api(libs.okhttp)` in its own
            // `androidMain` "because there is no Native artifact to resolve", which
            // is *why the transport is portable* rather than a reason it is not.
            // It was reachable only from `androidMain` until this move, which is the
            // same defect as `JsonAccessors.kt`'s `kotlinx.serialization`: pinning a
            // dependency to a source set that no longer holds the file claims a
            // portability nothing can see.
            implementation(project(":android:network"))

            // For `MetadataAddonRegistry.installationId`: `MessageDigest("SHA-1")`
            // plus `StandardCharsets.UTF_8` is now `ByteString.encodeUtf8().sha1()`.
            // **okio reaches `:app` through `coil3` with no dependency line of its
            // own, and `:addons` has no coil3** -- so this module states it
            // explicitly. `sha1()` and `.hex()` are what make the twelve hex digits
            // of `installationId` reproducible rather than merely equivalent.
            implementation(libs.okio.core)

            // For `JsonAccessors.kt`, which was declared in `androidMain` for
            // exactly as long as its three consumers were -- and no longer is.
            // **A file's dependency belongs in the source set the file is in, not
            // where it used to be**: that file named not one `android.*` type, so
            // the source set it sat in was the only thing pinning it, and pinning
            // the dependency to a source set that no longer holds the file claims
            // a portability nothing can see. Moving it here is what makes the
            // eleven accessors reachable from `commonMain` *and* testable from
            // `commonTest`, which is the whole value -- see the KDoc there.
            implementation(libs.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))

            // `runTest` and `UnconfinedTestDispatcher`, for
            // `RemoteSupabaseSyncLabService`. Nine of its ten members are suspend and it
            // has a second decision that only a dispatcher can observe: eight members
            // enter the injected dispatcher and `syncNow` does not. Neither is testable
            // from a synchronous harness, so the block has to name this.
            implementation(libs.coroutines.test)
        }

        // `UriBehaviourHostTest` measures `android.net.Uri`, which is a class of the
        // Android platform rather than a dependency of this project -- so it cannot
        // go in `commonTest`, which compiles for `linuxX64` and both Apple
        // targets. It needs Robolectric for `android-all`, and **that jar is the
        // shipping implementation here**: `android.net.Uri` is pure Java in
        // AOSP's `libcore`, not native code, which is the opposite of `org.json`
        // (two implementations that disagree -- see `:backend`'s
        // `JsonAccessorPolicyHostTest`). The distinction is what decides whether
        // a measurement can be trusted, so it is worth a dependency.
        //
        // `@Config(sdk = [35])` and nothing else: no view is inflated and no
        // resource is read, so `isIncludeAndroidResources` is not needed and the
        // manifest is not either.
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
        }

        androidMain.dependencies {

            implementation(project(":android:backend"))

            // `:platform-android` is a plain `com.android.library`, so it publishes
            // no JVM variant and `commonMain` cannot see it -- which is why
            // `MetadataAddonRegistry` takes `:platform-core`'s `KeyValueStore` and
            // `metadataAddonRegistry(context)` builds the `SharedPreferences`
            // implementation here. `:app` has the same split for the same reason.
            implementation(project(":android:platform-android"))

            implementation(libs.coroutines.android)
        }
    }
}
