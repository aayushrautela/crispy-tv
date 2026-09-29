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
 * In Phase 4, five more moved and the table below records what each file's stated
 * reason actually was. Three of the five had a **proxy** reason rather than the
 * cause, and in every case the real blocker had already been solved elsewhere in
 * the repo — `AppLogger` and `BackendApi` existed before anyone looked at this
 * module. That is the shape of the remaining work: not "find a blocker" but
 * "check whether the stated blocker is the one that is load-bearing".
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
 * | `HomeLayoutBuilder`, `HomeUiModels` | `commonMain` | Compose runtime only (`@Immutable` on the row models), and Compose runtime publishes for every target `:home` builds. The old row said "builds the UI model types in `HomeUiModels`, which are Compose-bound" and pinned both to `androidMain`; the annotation is not what made them Compose-*bound*, it is the same load-bearing hint `CatalogModels` carries, and the same dependency it already paid for |
 * | `ResponsiveImageSet`, `ImageQuality` | gone | both moved to `:android:core-domain`. The row used to say `androidMain` because the file held an extension on a backend type; that extension was the wire-to-model mapping, and it became unnecessary when the two identically shaped `ResponsiveImageSet`s were merged into one |
 * | `CalendarService`, `UpNextService` | `commonMain` | moved in Phase 4. The old row said "`org.json`, Compose, and the backend client", and **only the last of those three was real** — neither file parses JSON and neither touches Compose. The actual blockers were `android.util.Log` (three call sites) and the concrete client, and both were already solved elsewhere: `AppLogger` is a `:platform-core` interface the service now takes as a constructor parameter, and `BackendApi` is the port `CrispyBackendClient` implements. **Three files whose stated reason was a proxy rather than the cause** |
 * | `HomeSnapshotModels` | split | the models, the section keys, `defaultWideRailSection`, both `toWideRailItem` extensions and the two date/format helpers moved to `commonMain`; `ContinueWatchingSuppressionStore` stayed in `androidMain` and became its own file. The old row said "`org.json` and `Context`", and that was the *file's* truth and not the models' — the store was the only part that used either, so splitting by that boundary left the models portable without touching them |
 * | `HomeHeroItem` | new file, `commonMain` | lifted out of the top of `HomeCatalogService` because `HeroState` (in the moved `HomeSnapshotModels`) names it, and a nested type is as pinned as the file declaring it. Second instance of that rule in the repo |
 * | `HomeRefreshCoordinator` | `androidMain` | **its stated reason was "consumes `HomeCatalogService`, `CalendarService` and `UpNextService`, all of which are `androidMain`", and that is now two-thirds wrong** — `CalendarService` and `UpNextService` are `commonMain`. What still pins it is the two that remain: `HomeCatalogService` (`org.json`, OkHttp, `formatRating`) and `ContinueWatchingSuppressionStore` (`Context`, `org.json`). The injected clock already moved, and the file was already a coordinator over injected services, so it should follow the moment those two do |
 * | `HomeCatalogService` | `androidMain` | `org.json`, OkHttp, and `formatRating` from `:android:addons` |
 * | `CatalogModels` | `commonMain` | moved in Phase 4. The row used to read "`androidMain` / Compose runtime", which was **incomplete rather than a real blocker** -- the only Compose call in the file is the `@Immutable` annotation, and Compose runtime publishes for every target `:home` builds. What actually pinned it was `java.util.Locale`, on one line: `catalogId.trim().lowercase(Locale.US)`. That is the second time this file's stated reason was a proxy rather than the cause, and the difference matters -- the real blocker was a single call that had a portable equivalent, where the stated one implied giving up the annotation |
 * | `RecommendationCatalogDiskCacheStore` | `androidMain` | `Context`, `org.json`, `java.io` |
 *
 * The two `java.*` calls that moved out of `commonMain` files followed precedents already
 * set in this module and in `:core-domain`, rather than being reinvented: `lowercase(Locale.US)`
 * became `lowercase()` (Kotlin's is locale-invariant by definition) and
 * `String.format(Locale.US, "S%02dE%02d", ...)` became `padStart` (`%02d` on a non-negative
 * `Int` is zero-padding to width 2 and nothing else; a season past 99 is wider under both
 * forms, so neither truncates). Neither rewrite is "obviously right" in isolation, and both
 * are worth a test at the call site rather than a comment at the definition.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.home"
        compileSdk = 37
        minSdk = 26

        // `commonTest` alone runs on no Android target, and AGP only warns about it.
        // Both halves are needed: `commonTest` is what would catch
        // `CalendarService` reaching for a JVM API again, and this is what runs those
        // same cases on the platform the module actually ships.
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

            // For `@Immutable` on the catalog models in `commonMain`. This is the
            // only Compose dependency `:home` has, and it is the reason
            // `CatalogModels` could not simply be moved: the annotation is a load-
            // bearing Compose compiler hint, and `CatalogItem` is an 18-field data
            // class. Without it the compiler treats every item as unstable and
            // skipping stops working for each card in a catalog row. Dropping the
            // annotation to avoid a dependency would be a silent recomposition
            // regression, so the dependency is the honest cost.
            implementation(libs.androidx.compose.runtime)

            // For `BackendApi` and the wire types `CalendarService` maps
            // (`CalendarItem`, `UpNextItem`), which live in `:backend`'s
            // `commonMain`. The dependency is the interface, not the OkHttp client,
            // so nothing here reaches a JVM API -- `BackendApi` was declared
            // precisely so that `CalendarService` and `UpNextService` could leave
            // `androidMain` without a transport they cannot have. `:android:backend`
            // is already multiplatform and already a `commonMain` dependency of
            // `:android:app`, which is what makes this consistent rather than novel.
            implementation(project(":android:backend"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        androidMain.dependencies {
            // For `TimeSource` and the `Iso8601` helpers.
            api(project(":android:platform-android"))

            // Still `androidMain`: `HomeCatalogService` and
            // `RecommendationCatalogDiskCacheStore` use OkHttp and `org.json`, and
            // `HomeCatalogService` uses `formatRating` from `:android:addons`. These
            // move down with their consumers. `CalendarService` and `UpNextService`
            // used to be listed here for the backend client; both are `commonMain`
            // now and the reason they could go is above.
            implementation(project(":android:player"))
            implementation(project(":android:addons"))

            implementation(libs.coroutines.android)
        }
    }
}
