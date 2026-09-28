plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * The home feed: catalog, calendar, up-next, and the refresh coordinator.
 *
 * The last Phase 2 module, and the one with the highest proportion of genuinely
 * portable code — four of fourteen files were genuinely standalone, and the two that looked portable
 * but were not both hid a same-package reference that no import scan could see.
 *
 * ## What `java.time` cost here
 *
 * Four files used it. Rather than write a replacement, the existing one was reused:
 * `core-domain` already had `parseIso8601InstantToEpochMillis`, and this step added
 * the two things `:home` actually needed and `core-domain` lacked —
 * `parseIso8601DateToEpochMillis` (a bare `YYYY-MM-DD` read as midnight **UTC**,
 * not local time, which is the behaviour the calendar had) and `iso8601MonthLabel`
 * (the `Mar 14` badge text, previously derived from `LocalDate.month.name`).
 *
 * Both are pinned by `Iso8601Test` against values generated from a real JDK, including
 * the rejections — `2024-02-31` must stay null, or a nonsense date would start
 * rendering a plausible-looking badge.
 *
 * ## The clock
 *
 * `HomeRefreshCoordinator` read `System.currentTimeMillis()` four times, and its own
 * comment said so. Worse, the three calls in `loadContinueWatching` were independent,
 * so one refresh could filter entries against one instant and then render them
 * against another, a millisecond later. It is now a single injected `TimeSource`
 * reading, taken once per operation.
 *
 * `RecommendationCatalogDiskCacheStore` had the worse version: `System.currentTimeMillis()`
 * as a **default argument**, on both `ageMs` and `write`. That is invisible at every
 * call site and untestable — `:android:player` shipped one and the Kotlin/Native
 * compile gate is what caught it. Both now require an explicit instant.
 *
 * | file | where | why |
 * |---|---|---|
 * | `HomeRefreshBus`, `HomeTop10`, `HomeWatchActivityService`, `ImageQuality` | `commonMain` | no platform coupling. `HomeWatchActivityService` needs only `CanonicalContinueWatchingItem` / `...Result`, which live in `:android:player`'s `commonMain`, so that dependency is portable |
 * | `HomeLayoutBuilder` | `androidMain` | builds the UI model types in `HomeUiModels`, which are Compose-bound. Same-package, so no import revealed the dependency -- it only surfaced when the file failed to compile |
 * | `ResponsiveImageSet`, `ImageQuality` | gone | both moved to `:android:core-domain`. The row used to say `androidMain` because the file held an extension on a backend type; that extension was the wire-to-model mapping, and it became unnecessary when the two identically shaped `ResponsiveImageSet`s were merged into one |
 * | `HomeUiModels` | `androidMain` | Compose runtime only; moves in Phase 4 with the UI |
 * | `HomeRefreshCoordinator` | `androidMain` | consumes `HomeCatalogService`, `CalendarService` and `UpNextService`, all of which are `androidMain`. The injected clock is the part that had to change, and it did |
 * | `CalendarService`, `HomeCatalogService`, `UpNextService` | `androidMain` | `org.json`, Compose, and the backend client |
 * | `HomeSnapshotModels` | `androidMain` | `org.json` and `Context` |
 * | `CatalogModels` | `androidMain` | Compose runtime |
 * | `RecommendationCatalogDiskCacheStore` | `androidMain` | `Context`, `org.json`, `java.io` |
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.home"
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

            // For `TimeSource` and the `Iso8601` helpers. The contracts, not the
            // Android implementations: depending on `:android:platform-android` here
            // would reintroduce exactly the platform leak the interfaces exist to
            // remove, and it is the single most common way a KMP module silently stops
            // being multiplatform.
            api(project(":android:platform-core"))

            // For `CanonicalContinueWatchingItem` and `CanonicalContinueWatchingResult`,
            // which `HomeWatchActivityService` returns. `:android:player` is already
            // multiplatform and these live in its `commonMain`, so this is a portable
            // dependency — unlike the ones below.
            api(project(":android:player"))

            api(libs.coroutines.core)
        }

        androidMain.dependencies {
            // For `TimeSource` and the `Iso8601` helpers.
            api(project(":android:platform-android"))

            // Still `androidMain`: `HomeCatalogService` and `CalendarService` use
            // OkHttp and `org.json`, and `HomeCatalogService` uses `formatRating`
            // from `:android:addons`. These move down with their consumers.
            implementation(project(":android:player"))
            implementation(project(":android:backend"))
            implementation(project(":android:addons"))

            // Compose runtime only — `@Immutable` on the UI model types. Note the
            // BOM: this is a source-set dependency handler, and `KotlinDependencyHandler`
            // has no `platform()`, so the BOM has to be pinned per artifact here.
            // Phase 4 replaces this with the `compose.*` accessors.
            implementation(libs.androidx.compose.runtime)

            implementation(libs.coroutines.android)
        }
    }
}
