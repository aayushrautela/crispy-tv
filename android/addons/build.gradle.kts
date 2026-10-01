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
 * turned out not to be what blocks portability. The blockers are `Context`, OkHttp
 * and `org.json`, exactly like every other module in this phase.
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
 * | `AddonStreamsService` | `androidMain` | `Context`, OkHttp, `org.json` |
 * | `StreamResolver` | `commonMain` | a type-level port over `CachingStreamResolver`, whose caching is the only thing the class adds and only `cachedStreams` can observe. It is **not** a transport abstraction: `AddonStreamsService` wraps `CrispyHttpClient`, which leaks `okhttp3` |
 * | `CachingStreamResolver` | `androidMain` | OkHttp, and `AddonStreamsService` is a final class built from a `Context` and an `okhttp3` client. It is also the class the port's KDoc points at — the port is the half that could travel, the cache is the half that cannot. Its `System.currentTimeMillis()` calls are a second, independent reason it could not move on its own merits |
 * | `BackendEpisodeListProvider` | `commonMain` | moved once both of its parameters became `BackendApi` / `AccountApi` ports. It had no Android type of its own |
 * | `MetadataAddonRegistry`, `RemoteMetadataLabDataSource`, `RemoteSupabaseSyncLabService` | `androidMain` | `Context` and `org.json` |
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
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(project(":android:network"))
            implementation(project(":android:backend"))

            implementation(libs.coroutines.android)
        }
    }
}
