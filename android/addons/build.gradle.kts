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
 * | `MediaDetailMappings` | `androidMain` | every function is an extension *on* a `CrispyBackendClient` nested type, so it belongs with its receiver, not above it. The bodies are pure mapping code; the receiver is what pins it |
 * | `LookupIds`, `StreamLookupSupport`, `StreamSelectorState` | `commonMain` | used `Locale.US` in `lowercase`, which is exactly what Kotlin's locale-independent `lowercase()` already does |
 * | `RatingFormats` | `commonMain` | `String.format` is JVM-only; now uses `core-domain`'s `formatOneDecimal`, pinned against real `%.1f` output |
 * | `AddonStreamsService` | `androidMain` | `Context`, OkHttp, `org.json` |
 * | `StreamResolver` | `androidMain` | OkHttp |
 * | `MetadataAddonRegistry`, `RemoteMetadataLabDataSource`, `RemoteSupabaseSyncLabService`, `BackendEpisodeListProvider` | `androidMain` | `Context` and `org.json` |
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.addons"
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

        androidMain.dependencies {
            implementation(project(":android:network"))
            implementation(project(":android:backend"))

            implementation(libs.coroutines.android)
        }
    }
}
