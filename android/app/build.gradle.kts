plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)

    // Deliberately NOT alias(libs.plugins.compose.multiplatform) yet. It would
    // only be used for the `compose.*` dependency accessors, and this module
    // cannot use them: they resolve material3 to 1.4.0 while the app is written
    // against 1.5.0-alpha26's Material3 Expressive APIs. :android:sharedUI is
    // where CMP is applied, and `:app` depends on it. The plugin comes back in
    // Phase 4, with the material3 dependency that goes with it.
}

/**
 * The shared UI and presentation layer.
 *
 * Created by the Phase 1 split. `:app` used to be the Android application and
 * owned the manifest, `res/`, signing, flavours and 159 source files; all of
 * that is now in `:androidApp`, which is a thin entry point on top of this.
 *
 * ## What is in `commonMain`, and what still is not
 *
 * The split itself was the deliberate part: it proved the module graph compiles
 * before any code crossed a source-set boundary, because moving 31k lines of
 * Compose at the same time as restructuring the modules would have made a
 * failure impossible to attribute. Phase 4 then moves the screens into
 * `commonMain` one vertical slice at a time, and 113 of the 193 main-source files are there.
 *
 * The remaining 80 are held by three things: a type that cannot be named off
 * Android (a `Context`, `SharedPreferences`, `org.json`,
 * `:android:native-engine`'s own types), a composition root that by definition
 * needs a platform to resolve against, and screen code still split
 * factory-from-viewmodel. `androidx.paging` left that first list when
 * `CatalogPagingSource` moved -- `paging-common` is on the `commonMain`
 * classpath at :328 -- and it went for a reason worth keeping: **the two halves
 * of the family answer differently**, so "paging is KMP" names no answer and
 * the artifact has to be the question. The third of those is the smallest: the viewmodels
 * themselves are already in `commonMain`, so what is left is the factories and
 * the nav graph that resolve them. The rules that decide what is worth changing
 * are in AGENTS.md under *Rules* sections 1 and 2. The two counts above are a
 * `find` away and are re-measured rather than trusted as the module moves --
 * **and they have been wrong once already**: the "104 of the 189" that shipped in
 * 45e70d69 was a miscount, because a file that moved in an earlier landing was
 * double-counted against its own destination. `find ... | wc -l` next to
 * `git ls-files <dir> | grep -c '\.kt$'` is the check that would have caught it.
 *
 * ### The three are not three things, they are seven named hubs
 *
 * The paragraph above groups the remainder, but "a type that cannot be named
 * off Android" is not a blocker you can go and look at -- and an import audit
 * cannot find it, because `:app` and `:home` and `:addons` **share package
 * names**, so a declaration in another module's `androidMain` is reachable from
 * here with no import at all. This was measured, not reasoned: of the 104, an
 * audit found **35 files / 7,514 lines (48% of the remaining lines) with no
 * `android.*`, `java.*`, `org.json` or `nativeengine` import whatsoever**,
 * `git mv`-ed all 34 of them to `commonMain` in one batch, and **all 34
 * failed**. Not one. So the 35 are worth naming individually, by the hub that
 * actually holds each, because that is the thing to go and change:
 *
 * | hub | declared in | holds | what freeing it costs |
 * |---|---|---|---|
 * | the viewmodels | `AccountViewModels`, `CatalogViewModel`, `HomeViewModel`, `HomeSelectorViewModel`, `LibraryScreen`, `SearchViewModel`, `AppBootstrapViewModel` | the `Route`/`Screen` files, ~4,000 lines | **not a factory/viewmodel split — that premise was false.** `ViewModelProvider.Factory` is reachable from `commonMain` (see the `lifecycle-viewmodel-compose` comment 277 lines below, which already says so and is right, and the 14 `ViewModelProvider*` classes in that artifact's `-desktop` jar). A route composable can therefore take its factories *as values*. What actually blocked `HomeRoute` was three `Context` reads, and those cross as **no-default slots** on a signature that already carried eleven: `viewModelFactory`, `selectorViewModelFactory`, `loadProfile` — and `HomeStreamSelector` came with it, its `LocalConfiguration` read crossing as `isCompact: Boolean`. **2 files / 359 lines freed, 0 new ports.** The factories keep taking a `Context` and stay in `androidMain`, because a `Context` used for *wiring* belongs in the factory — so it is the call sites that move, not the factories. **That is the fifth time a blocker in this table turned out to be an untested premise.** |
 * | ~~`DetailsPalette.kt`~~ | **freed** | `AiInsightsStoryOverlay`, `DetailsBody`, `DetailsRatingsSection` | **none left -- all three moved.** The three reasons this row claimed were all wrong, and each is worth recording because two of them were premises rather than measurements. `com.materialkolor` 5.0.0 is a genuine KMP artifact (it publishes `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`, `macosArm64`, `js`, `wasmJs`), so `rememberDynamicColorScheme` and `themeColor` were never blockers. `LocalContext` is Coil's own `coil3.compose.LocalPlatformContext` in `commonMain`. And the bitmap is not a blocker either, it is the *return type* of an `expect`: `coil3.toBitmap` yields `android.graphics.Bitmap` on Android and `org.jetbrains.skia.Bitmap` elsewhere, so no `commonMain` signature can name it -- the extraction takes Compose's `ImageBitmap`, which every target shares, and only the two loader composables stayed behind for `Context`. That is the fourth time a blocker in this table turned out to be an untested premise |
 * | the composition root | **`AppGraph`** (now `commonMain`, built from one `AppServices`), `AndroidAppGraph` (the three members still needing `PlaybackDependencies`/player seams), `PlaybackDependencies`, `DistributionComponents` | `ProfileMenuRoute`, `CalendarScreen`, `SettingsScreen`, `SettingsNavGraph`, `AppDistribution` | these *are* the root; a screen resolves them in `androidMain` and needs a seam for the values, not for the graph. **The four service providers and the two settings `…RepositoryProvider`s this row used to name are deleted** -- the five cached `object` providers moved into `commonMain/.../app/AppGraph.kt` and the graph is the only place a store name, an HTTP client or a snapshot cache is built |
 * | `androidx.navigation` | **now on the `commonMain` classpath**, as `libs.jb.navigation.compose` | **0 of 9 files moved** | **swapped, measured 5/5, and it frees nothing — the sixth untested premise in this table.** The JetBrains fork `org.jetbrains.androidx.navigation:navigation-compose:2.10.0-beta01` publishes `androidJvm`/`desktop`/`iosArm64`/`iosSimulatorArm64`, keeps the `androidx.navigation.compose` package, and resolves through Google's own now-KMP `navigation-runtime:2.10.0`; Google's `navigation-compose:2.9.8` publishes `android` plus `jvmStubs`, which are dokka artifacts. So the classpath blocker is gone. **What remains is a `Context` that arrives through a call, which an import scan cannot see:** `AppNavHost` calls all six graphs by name, and each graph calls a `Context`-taking factory, so the layer is mutually referencing and moves as a unit or not at all. The `6 files` this row used to name was also wrong: the layer is **9** — 7 graphs in `ui/navigation` (`Auth`, `Discover`, `Home`, `Library`, `Player`, `Search`, `Settings`) plus `AppRoot` and `AppNavHost` |
 * | `androidx.paging` | `paging-common` **and** `paging-compose` are on the `commonMain` classpath; `paging-runtime` is not | `LibraryPagingSource`, `BrowsePagingSource` (`CatalogPagingSource` moved to `commonMain`) | measured per artifact, and the two halves of the family answer differently: `paging-common-3.5.1` and `paging-compose-3.5.1` both publish `metadataApiElements` with platform.type=common plus `iosArm64`/`iosSimulatorArm64`/`linuxX64`/`linuxArm64`/`desktop`, while `paging-runtime-3.5.1` publishes four variants carrying a single `.aar` with platform.type=- and no `available-at` and depends on `androidx.recyclerview` — so `paging` is not "KMP", it is **three artifacts with three different answers**, which is why `paging-runtime` alone is the one left in `androidMain` |
 * | `StreamResolver` | `androidMain/.../addons` | `SelectorCoordinator`, `HomeStreamSelector` | the same port treatment as `BackendApi`, applied to a type the project owns |
 * | `R.raw` | `:ui-assets` | `DetailsRatingsSection` (7 logos) | see the rule below: split the file, and push the name matching to `commonMain` the way `ReviewProviderOrNull` did |
 *
 * | ~~`DetailsHeader`~~ | **freed** | `PlayerInfoSheet`, `PlayerEpisodeRow` | **none left on this row.** `DetailsHeader` was held by five separate things at once -- `java.time`'s `Instant`/`YearMonth`, `Locale`, a `Context` for the share `Intent`, `LocalConfiguration` for the wide-screen branch, and an Android-only `DateFormat` -- and five no-default slots plus three portable month-key helpers in `:core-domain` cleared all five. The two `playerui` files it used to hold are still `androidMain`, but for a different and much smaller reason, now written down |
 *
 * `StreamSelectorContent` is the whole of what that smaller hub became, and it
 * is held by **one function at the bottom of its own file**:
 * `formatEpisodeReleaseDate`, a `LocalDate.parse` +
 * `DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)` pair. That single
 * helper is what `PlayerInfoSheet` and `PlayerEpisodeRow` reach for, which is
 * why freeing `DetailsHeader` did not free them -- and it is the fourth time
 * one unremarkable formatter at the bottom of a screen has held more than its
 * own file (`formatLongDate` held two details screens, this holds three files).
 * The replacement is *easier* than `formatLongDate`'s was, because the pattern
 * is pinned to `Locale.US`: the output is the same English string on every
 * device, so a portable month-abbreviation helper in the same `Iso8601.kt` is
 * byte-identical and needs no locale slot at all.
 *
 * `IntroSkipService` is a tenth, smaller one (`IntroSkipButtonOverlay`).
 * `PlayerSessionViewModel` is pinned by an eleventh and is not a hub at all:
 * ten of its types are declared only in `:android:native-engine`, which is a
 * plain `com.android.library` and therefore cannot be named from a KMP
 * `commonMain` whatever we write here.
 * `PlayerTrackSheet` is behind the **same** wall for the **same** reason -- it
 * names `NativeTrack` and `externalSubtitleTrackId`, both from
 * `:android:native-engine` -- and checking that before planning the move is what
 * turned its landing from a batch into an extraction: the sheet's language
 * labelling is pure data over a string and moved to
 * `commonMain/playerui/LanguageLabels.kt` with a no-default
 * `englishDisplayName: (String) -> String` slot, while 450 lines of composition
 * stayed behind. **The file count is the misleading number here: `PlayerTrackSheet`
 * still contains no `java.*` import, and it still cannot move.** What tells the
 * two apart is a type, not an import, so check `grep -rn "class NativeTrack"`
 * against the module type in `plugins { }` before planning any file the audit
 * calls clean.
 * Both are Phase 5/6 decisions about the media engine.
 *
 * `PlayerGestureController` was a hub of exactly the kind this table exists to
 * name, and freeing it took a port rather than a move. `PlayerGestures.kt` used it
 * with no import at all -- same package -- which is why an import audit called the
 * file clean and the compiler produced **15 of the 17** errors in the batch that
 * moved it. The class reaches `Activity.window.attributes`, `WindowManager`,
 * `AudioManager` and `Settings.System`, so it stays; the `interface
 * PlayerGestureController` and its `AudioLevel` moved to
 * `commonMain/playerui/PlayerGestureController.kt`, the implementation became
 * `AndroidPlayerGestureController`, and no consumer needed an import edit because
 * **the interface took the class's name**. `AudioLevel` had to be lifted out of
 * the class body to do it: a nested type is as pinned as the file declaring it,
 * because `PlayerGestures.kt` names it as a parameter type. **The last five files
 * that the audit called clean were all this in different costumes -- run the
 * audit, then read the errors once, and reduce them by distinct unresolved
 * *names* rather than by line.**
 *
 * The method that produced this table is the one to repeat: move the batch,
 * let the compiler name the hubs, revert what fails. It has now been run four
 * times -- 8 files (1 moved), 12 (10 moved), 2 (2 moved) and 35 (0 moved) --
 * and the yield falls as the easy wins are taken, which is the expected shape.
 *
 * A fourth held a surprising number of files before this: `java.time`. It was
 * one helper -- `formatLongDate`, a `LocalDate` + `DateTimeFormatter` call at
 * the bottom of `DetailsMetadataSection` -- and it pinned two details screens
 * between them, because a portable replacement has to be written before the
 * file it lives in can move. The replacement did not go next to the caller:
 * `:core-domain`'s `Iso8601.kt` had already replaced `java.time` for the
 * calendar, so the formatter went there and only the fallback policy stayed
 * here. Look for the existing replacement before writing a second one.
 *
 * Coil used to be the fourth, and was the largest of the four: 15 files reached
 * it, 4,378 lines in total, and every one of them was blocked by
 * `CrispyImage.kt` reading `LocalContext`. The fix was not ours to design --
 * Coil's own `commonMain` already declares `expect val LocalPlatformContext`,
 * which *is* `LocalContext` on Android, and `expect abstract class
 * PlatformContext`, which on Android is a typealias for `Context`. The
 * repository's rule is that a seam is worth building only when the upstream
 * library has not already shipped one, and this was the largest file in
 * `:app`'s `androidMain` sitting behind a seam that already existed.
 *
 * A fifth is now a *rule* rather than a count, and it is the one to reach for
 * before building anything. An `R` class cannot be named off Android at all, so
 * a `res/raw` reference blocks a file completely -- but it usually blocks only
 * a few lines of it. `DetailsCastSection.kt` imported `com.crispy.tv.ui.assets.R`
 * for one `when` mapping and was otherwise portable, so the 25 lines that need
 * the identifiers moved into `ReviewProviderBadge.kt` and the other 216 stayed.
 * The name matching went the other way and into `commonMain` as
 * `ReviewProvider` + `reviewProviderOrNull`, because that half is pure and
 * testable and leaving it beside the `R` lookups would have made it untestable:
 * `R$raw` is not on a JVM test classpath, so a test calling the combined
 * function failed with `NoClassDefFoundError` rather than with a wrong answer.
 * **Push the pure part toward `commonMain` and the platform part toward
 * `androidMain`, even when the platform part is the smaller one.** The badge
 * receives the result as a composable-slot parameter with no default, so a
 * call site cannot quietly forget it -- the same choice
 * `ImageSettingsRepository(onQualityChanged:)` already made.
 *
 * ## What this module deliberately does not know
 *
 * The build variant. It is a single-variant library, so it has no `store` /
 * `sideload` source sets and depends on neither `:android:plugins` nor
 * `:android:torrent-engine`. The five things a variant used to supply now come
 * through `DistributionComponents`, installed by `:androidApp`. `:app` holds
 * the interface and reads through `AppDistribution`; neither flavour's
 * implementation is on its classpath.
 *
 * ## `androidLibrary { }`, not `android { }`
 *
 * `org.jetbrains.compose` wires itself to `androidLibrary` specifically. With
 * `android { }` the compose dependencies resolve onto `androidCompileClasspath`
 * and are then invisible to the Kotlin compiler, which fails with
 * `Unresolved reference 'org.jetbrains.compose'`. The Gradle task succeeds and
 * only compilation fails, so it presents as a dependency bug and is not one.
 *
 * ## No `linuxX64` here
 *
 * Unlike :core-domain and :platform-core, this is a Compose module. Compose
 * Multiplatform publishes no `linuxX64` artifacts -- its targets are Android,
 * iOS and Desktop (JVM) only -- so declaring it makes every `compose.*`
 * dependency fail to resolve. `jvm("desktop")` is the local purity gate
 * instead.
 */
kotlin {
    jvmToolchain(21)

    android {
        // `com.crispy.tv.app`, not `com.crispy.tv`. The Android namespace decides
        // the package of the generated `R` class, and AGP rejects two modules
        // sharing one. `:androidApp` holds the real `com.crispy.tv` namespace
        // because that is the applicationId in the shipped APK; this module is a
        // library beneath it and only had `com.crispy.tv` by inheritance from
        // when it *was* the application.
        //
        // The Kotlin packages are unaffected: they are still `com.crispy.tv.*`.
        // Five files referenced `com.crispy.tv.R` and now use `com.crispy.tv.app.R`.
        namespace = "com.crispy.tv.app"
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = true
        }
        // Gives this KMP library a host unit test compilation, and with it the
        // `testAndroidHostTest` task. Without it `:app` has no way to be tested at
        // all: `desktopTest` sees only `commonMain` + `appUi`, and the composition
        // root -- the thing every future migration step unwinds -- lives in
        // `androidMain`. `:core-domain` declares the same block.
        withHostTest {}
    }

    jvm("desktop")

    // No linuxX64(), unlike :core-domain and :platform-core. This is a Compose
    // module, and Compose Multiplatform publishes no linuxX64 artifacts -- its
    // targets are Android, iOS and Desktop (JVM) only -- so declaring it makes
    // every compose dependency fail to resolve. `jvm("desktop")` is the local
    // purity gate instead.

    listOf(iosArm64(), iosSimulatorArm64())

    // No `binaries.framework` here. `:android:sharedUI` already exports the
    // `CrispyUI` framework that the Swift shell imports, and two modules
    // cannot export the same framework name. When Phase 4 moves screens into
    // `commonMain` and the Swift shell needs *those* APIs, the framework moves
    // to this module and `:sharedUI` stops exporting one.

    // The default hierarchy template builds commonMain -> nativeMain ->
    // appleMain -> iosMain automatically. Writing dependsOn by hand cancels it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // Compose UI shared by phone, tablet and desktop -- everything except
        // the 10-foot TV surface, which :android:tv owns outright.
        val appUi = create("appUi") { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(appUi)
        jvmMain.get().dependsOn(appUi)

        // The JVM layer BOTH JVM targets compile. `applyDefaultHierarchyTemplate`
        // registers `jvmMain` and hangs it off `appUi`, but by default nothing
        // dependsOn it, so it is a source set the build never compiles -- and a
        // file placed there is silently inert rather than an error. Android and
        // desktop are both JVM targets, so these two edges are what turn it
        // into the shared layer for everything pinned by `java.*`/`Dispatchers.IO`
        // rather than Android alone.
        //
        // `named("desktopMain")` and not `desktopMain`: `jvm("desktop")` renames its
        // own source set, so there is no typed accessor for it. `androidMain` DOES
        // have one. Proven in both directions with a probe calling across the edge
        // and a negative test with each edge removed.
        named("desktopMain").get().dependsOn(jvmMain.get())
        androidMain.get().dependsOn(jvmMain.get())
        iosMain.get().dependsOn(appUi)

        // What every target needs. These are the dependencies a `commonMain`
        // file is *allowed* to reach for, so the set is deliberately small: it
        // grows when a file moves, not before, because a dependency declared
        // here with no consumer yet is a portability claim nothing backs.
        commonMain.dependencies {

        // **The node type, and it is declared here rather than in `androidMain`
        // because that is where the files now are.**
        // `LibraryDiskCacheJsonAccessors.kt` and `ProfileDataShadowJsonAccessors.kt`
        // moved to `commonMain` in the same change that made their two suites
        // move to `commonTest`, and `JsonAccessorPolicyHostTest` in `:backend`
        // and `WatchProgressStoreHostTest` in `:watchhistory` already run this
        // way. A file's dependency belongs in the source set the file is in, not
        // where it used to be -- declaring it in `androidMain` would have
        // compiled the accessors and left `commonTest` unable to see the very
        // declarations it exists to cover.
        implementation(libs.serialization.json)
            implementation(project(":android:core-domain"))
            implementation(project(":android:platform-core"))
            implementation(project(":android:sharedUI"))
            // Its `MediaModels.kt` (MediaDetails, MediaVideo) is in commonMain
            // and is referenced by the player UI in `commonMain`. Declared here
            // rather than in `androidMain` because the consumer is a common
            // source set; `androidMain` still gets it transitively.
            implementation(project(":android:addons"))
            // The player UI in commonMain also reads `PlaybackIdentity` and
            // `TorrentResolver`, both in `:android:player`'s commonMain.
            implementation(project(":android:player"))
            // The repository interfaces in `commonMain` name the backend response
            // types, which live in `:android:backend`'s `commonMain` as
            // `BackendTypes.kt`. The *client* is still `androidMain` -- it speaks
            // OkHttp and `org.json` -- but its data types are portable, and they are
            // what these interfaces refer to. Without this a domain interface cannot
            // name the response it maps onto, which is what kept `UserMediaRepository`
            // and `CatalogRepository` in `androidMain` long after their own bodies
            // were already pure.
            implementation(project(":android:backend"))
            // For `CatalogItem` / `CatalogSectionRef` / `CatalogPageResult`, the home
            // feed's catalog models. They sat in `:home`'s `androidMain` on the stated
            // ground of "Compose runtime" -- which was not the blocker at all, since
            // Compose runtime publishes for every target. The real blocker was one
            // `java.util.Locale` on one line, and the models moved once that did. It
            // blocked 27 `:app` files, more than any other single upstream type.
            //
            // `:home`'s services travel through interfaces now (`HomeCatalogService`)
            // or moved outright (`HomeRefreshCoordinator`,
            // `ContinueWatchingSuppressionStore`, `CalendarService`,
            // `UpNextService`); `commonMain` sees only `commonMain`, so only the
            // portable half is visible here, which is the whole point.
            implementation(project(":android:home"))
            // For the `WatchSyncSource` interface `HomeViewModel` drives. The
            // OkHttp implementation stays in `:watchhistory`'s `androidMain`;
            // this dependency is the interface, not the socket.
            implementation(project(":android:watchhistory"))
            // `CrispyHttpClient`, the transport interface `RemoteIntroSkipService`
            // takes, is in `:network`'s `commonMain` -- the OkHttp socket
            // (`OkHttpCrispyHttpClient`) and `AppHttp` are in its `androidMain`,
            // and `:app`'s own `androidMain` still sees those through the Android
            // variant. Declared here rather than in `androidMain` because the
            // consumer is a `commonMain` source set: with the line below the
            // androidMain one, `IntroSkipService.kt` resolved neither `network` on
            // its import line nor `CrispyHttpClient`, which is the same
            // member-level failure `:platform-core` recorded and it moves the same way.
            implementation(project(":android:network"))
            // Coil, for `CrispyImage.kt` and the card composables that render
            // through it. `coil-compose` and `coil-core` both publish
            // android, jvm, iosArm64, iosSimulatorArm64, macosArm64, js and
            // wasmJs, which covers every target this module declares, and
            // neither is Compose Multiplatform -- so they are named directly
            // rather than through a `compose.*` accessor.
            //
            // What makes the image layer portable is `coil3.PlatformContext`
            // (an `expect abstract class` in Coil's commonMain, an `actual`
            // typealias for `Context` on Android) together with
            // `coil3.compose.LocalPlatformContext`, Coil's own portable
            // replacement for the Android-only `LocalContext`. Coil's own
            // `AsyncImage` reads that local for the same reason, so this is
            // upstream's supported path and not a workaround.
            //
            // `coil-network-okhttp` and `coil-svg` stay in `androidMain` below:
            // they are Android/JVM artifacts and a Phase 5/6 runtime
            // configuration question, not a compile one.
            implementation(libs.coil.compose)
            implementation(libs.coil.core)

            // MaterialKolor 5.0.0 is a genuine KMP artifact: its published
            // `.module` carries `android`, `iosArm64`, `iosSimulatorArm64`,
            // `jvm`, `macosArm64`, `js` and `wasmJs` variants. `DetailsPalette.kt`
            // sat in `androidMain` partly because `rememberDynamicColorScheme`
            // was believed to be Android-only -- it is not, and reading
            // `available-at` targets is what settles that. It moves here for the
            // same reason `coil-compose` did: the file it is used from is now in
            // `commonMain`.
            implementation(libs.material.kolor)

            // `paging-common` is the multiplatform half of paging, and it is a
            // real KMP artifact: 3.5.1 publishes android, desktop, iosArm64,
            // iosSimulatorArm64, macosArm64, linuxX64, js and wasmJs. That is
            // every target this module declares, so `PagingSource`,
            // `PagingState`, `LoadParams` and `LoadResult` all resolve in
            // `commonMain`.
            //
            // `paging-compose` is ALSO on the `commonMain` classpath, which this
            // comment used to deny. `paging-compose:3.5.1` publishes
            // `metadataApiElements` with platform.type=common plus androidJvm,
            // desktop, iosArm64, iosSimulatorArm64, linuxX64, macosArm64, js and
            // wasmJs -- the Kotlin 2.x target names, not the `uikit*` ones that
            // made navigation's stable 2.9.2 unusable. So `LazyPagingItems`,
            // `collectAsLazyPagingItems` and `itemKey` are reachable from
            // `commonMain` at the version this module already declares, with no
            // fork and no beta.
            //
            // `paging-runtime` really is the Android-only half and really does
            // stay in `androidMain` below, and the difference is in its own
            // dependency list rather than in a stubs trick: 3.5.1 publishes four
            // variants, the two publication ones carrying a single
            // `paging-runtime-3.5.1.aar` (89,262 bytes) with platform.type=- and
            // no `available-at`, and it depends on `androidx.recyclerview`,
            // `paging-common-ktx` and `kotlinx-coroutines-android`.
            //
            // This used to read "This is NOT the situation with
            // `androidx.navigation`, which looks symmetric and is not ...
            // the six `ui/navigation` files stay blocked." Every clause of that
            // was true and all of it is now false, so it is replaced rather than
            // amended. Measured, link by link, against the resolved `.module`
            // files (the five links are written out in gradle/libs.versions.toml):
            //
            //  - `org.jetbrains.androidx.navigation:navigation-compose:2.10.0-beta01`
            //    publishes `androidJvm`, `desktop`, `iosArm64` and
            //    `iosSimulatorArm64`. Google's `navigation-compose:2.9.8` publishes
            //    `android` plus `jvmStubs` and `linuxx64Stubs`, which are
            //    javadoc/dokka artifacts rather than compilable KMP variants --
            //    the `jvmStubs` name is the tell, and a stubs variant satisfies a
            //    `commonMain` dependency *declaration* without satisfying a
            //    `commonMain` compile.
            //  - The fork keeps the `androidx.navigation.compose` package, so
            //    the navigation files need no import change.
            //  - The stable `2.9.2` is unusable: real KMP, but it publishes only
            //    `uikit*` target names, which Kotlin 2.x no longer has.
            //
            // So the two androidx families are now symmetric, and that symmetry is
            // the whole difference: `paging-common` carries the types this module's
            // `PagingSource`s need and `paging-compose` carries `LazyPagingItems`,
            // and BOTH are on the `commonMain` classpath.
            implementation(libs.androidx.paging.common)
            implementation(libs.androidx.paging.compose)

    // The JetBrains KMP fork of navigation, in `commonMain` for the first time in
    // this module's history. The alias name carries the provider on purpose --
    // see gradle/libs.versions.toml.
    //
    // **It is a precondition and it moves no file.** The navigation layer is a set
    // of mutually referencing `androidMain` files: `AppNavHost` calls all six
    // graphs by name (`:100-105`), and every graph calls a `Context`-taking
    // factory, so the layer moves as a unit or not at all -- and it cannot move as
    // a unit until those factories are reachable from `commonMain`. A pin that
    // arrives through a *call* is invisible to an import scan, which is why five
    // censuses of this layer each found a different artifact and each found it was
    // not the pin. The remedy is the no-default-slot pattern, not this coordinate.
    implementation(libs.jb.navigation.compose)
    // `androidx.navigationevent:navigationevent-compose`, pinned explicitly in
    // the catalog. The fork re-exports it on metadata+android but ships hollow
    // non-Android variants, so without this line `commonMain` can name the
    // package on Android and nowhere else. Read-only use only: this module
    // never registers a handler, it only observes `transitionState`.
    implementation(libs.androidx.navigationevent.compose)
    // androidx.lifecycle:lifecycle-viewmodel is a genuine KMP artifact at 2.11.0 - this was
    // measured by declaring it here and compiling the `desktop` target, not read from a
    // doc. ViewModel, ViewModelProvider and viewModelScope are therefore all reachable
    // from commonMain, which is what lets a viewmodel move there whole instead of being
    // split factory-from-viewmodel.
    implementation(libs.androidx.lifecycle.viewmodel)
    // `lifecycle-viewmodel-compose` is the fifth premise in this file that did not
    // survive checking. The comment above used to say it "stays in androidMain" as one of
    // the "Android-flavoured halves of the same family". Its `.module` publishes android,
    // desktop, iosArm64, iosSimulatorArm64, js, linuxArm64, linuxX64, macosArm64,
    // mingwX64, both tvos targets, wasmJs and the watchos targets -- so the composable
    // `viewModel()` and `ViewModelProvider.Factory` are reachable from commonMain, which
    // is what lets a route composable live there and take a factory as a value.
    // (`lifecycle-viewmodel-ktx` is the one that really did go away: 2.11 folded it into
    // `lifecycle-viewmodel`.)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

            // NOT `:android:native-engine`. It is a plain `com.android.library`,
            // so it publishes no JVM variant and cannot be consumed from a KMP
            // `commonMain` at all -- the same constraint `:ui-assets` hit. The one
            // file that needs it, `PlayerSessionSupport`, stays in `androidMain`.
        }

        // The desktop side of the ports, for `DesktopAppServices` in this module's
        // `desktopMain`. It is declared **here and not at module level** because
        // `:android:platform-desktop` is a plain `kotlin.jvm` module -- it publishes
        // no metadata variant, so a `commonMain` line asking for it would fail to
        // resolve the same way `:android:native-engine` does two blocks below. This
        // is the desktop mirror of the `platform-android` edge that
        // `AndroidAppServices` already uses, and the two implementations of the
        // same interface sit at the same level in the hierarchy on purpose.
        //
        // Note what is *not* added: `:android:network` is already a `commonMain`
        // dependency above, so `DesktopHttpClients` -- the desktop half of the
        // transport `AppServices.httpClient` names -- is visible here without a
        // second line, and adding one would be a claim nothing needs.
        // `named("desktopMain")` rather than a `desktopMain` accessor: `jvm("desktop")`
        // does not publish a typed accessor for its own source set, and
        // `:android:network` reaches the same set the same way. The name is the
        // target's, not a compilation's -- there is no `jvmMain` here to hang a
        // custom set off.
        named("desktopMain").get().dependencies {
            implementation(project(":android:platform-desktop"))
        }

        // Everything the current code actually needs. Listed as `androidMain`
        // rather than at module level so it is obvious which of these are the
        // ones Phase 4 has to replace with a portable equivalent.
        androidMain.dependencies {
            // AndroidX Compose, pinned explicitly, NOT via `compose.*` and NOT
            // via `platform(libs.androidx.compose.bom)`. Two independent
            // reasons, either of which alone would be enough:
            //
            //  - A KMP source set's dependency handler has no `platform()`, so
            //    the BOM cannot be applied here. See the `composeAndroidx` entry
            //    in gradle/libs.versions.toml.
            //  - The `compose.*` accessors resolve material3 to 1.4.0, and this
            //    app is written against Material3 Expressive in 1.5.0-alpha26:
            //    LoadingIndicator, MaterialShapes, rememberBottomSheetState and
            //    ExperimentalMaterial3ExpressiveApi all fail to resolve against
            //    1.4.0. The catalog's `material3` version is the authoritative
            //    one and the BOM agrees with it on the other three artifacts.
            //
            // Phase 4 replaces this block with the `compose.*` accessors, which
            // is also when Material3 Expressive has to be dropped or replaced
            // with something that exists on desktop and iOS.
            implementation(libs.androidx.compose.runtime)
            implementation(libs.androidx.compose.foundation)
            implementation(libs.androidx.compose.ui)
            // No material3 here on purpose. It is declared once, as `api`, by
            // :android:sharedUI, and this module already depends on it. Declaring
            // the androidx coordinate here as well is what would put two material3
            // implementations -- this one at 1.5.0-alpha26 and the shared one --
            // in the same graph, which would put the runtime on a version
            // no other target resolves.

            implementation(project(":android:home"))
            implementation(project(":android:player"))
            implementation(project(":android:native-engine"))
            implementation(project(":android:watchhistory"))
            implementation(project(":android:platform-android"))
            implementation(project(":android:backend"))
            implementation(project(":android:addons"))
            implementation(project(":android:ui-assets"))

            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.activity.compose)

            implementation(libs.androidx.paging.runtime)

            // No `platform(libs.androidx.compose.bom)` here: `platform()` is not
            // available on a KMP source set's dependency handler, and declaring
            // the AndroidX Compose artifacts alongside the `compose.*` accessors
            // would double-declare the same modules. The accessors resolve to the
            // same androidx artifacts at the version the plugin pins, so they are
            // the only declaration needed. :androidApp keeps the BOM for its own
            // test dependencies, where `platform()` does work.
            //
            // The old `ui-tooling-preview` dependency is gone: no file under
            // src/androidMain imports @Preview, and CMP 1.11.1 does not expose a
            // `compose.uiToolingPreview` accessor to replace it with. Add it back
            // with an explicit version if a @Preview is ever actually used.
            implementation(libs.google.material)
            // `coil-compose` and `coil-core` are declared in `commonMain` above,
            // because the image layer moved there. These two stay here: they are
            // Android/JVM artifacts, and which fetcher and which decoder the
            // desktop and iOS builds configure is a Phase 5/6 runtime question.
            implementation(libs.coil.network.okhttp)
            implementation(libs.coil.svg)
            implementation(libs.metrics.performance)

            implementation(libs.androidyoutubeplayer)

            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.media3.session)
            implementation(libs.coroutines.android)
        }

        // The `commonMain` half of this module's tests, and it is what
        // `desktopTest` runs. The two halves are complementary and neither
        // substitutes for the other: `commonTest` cannot see androidMain, so it
        // covers the settings repositories, the account repositories, the
        // `EpisodeWatchStateResolver` and nothing in the composition root, while
        // `androidHostTest` below covers exactly the reverse.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // `runTest`, for the suspend-shaped ports. The settings tests are all
            // synchronous and did not need it; the account repositories are not.
            implementation(libs.coroutines.test)
            // okio's in-memory filesystem, for `FileBackedPendingMutationStore`.
            // The store takes a `FileSystem` precisely so its wire format can be
            // exercised on every target; the production artifact `okio` reaches
            // `commonMain` through `coil3` and carries no fake, so the test half
            // has to be asked for by name. It is version-matched to the `okio`
            // that resolves (3.17.0, via `coil-core` 3.5.0) rather than pinned
            // independently -- a `FileSystem` subclass compiled against a
            // different okio is an abstract-method error at best.
            implementation(libs.okio.fakefilesystem)
        }

        // The composition root's own tests. `withHostTest {}` above is what creates
        // this source set and the `testAndroidHostTest` task; it is not named on
        // the `sourceSets` container, so `getByName` is the only way to reach it.
        //
        // Robolectric, and only for a `Context`. Nothing here inflates a view or
        // reads a resource, so `@Config(manifest = Config.NONE)` is enough and the
        // merged manifest and the real app theme are not needed -- which is why
        // these can live in `:app` while the Robolectric tests that need them
        // stay in `:androidApp`. Robolectric is already a dependency of this repository.
        //
        // Note what this does NOT give the tests: Robolectric reuses one sandbox
        // classloader per `@Config` across every test class in the worker JVM, so
        // the singletons these tests assert on are shared with each other. A test
        // of a process-lifetime object has to account for that rather than assume
        // a fresh process -- see the comments in the test sources.
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core.ktx)
        }
    }
}
