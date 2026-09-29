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
 * `commonMain` one vertical slice at a time, and 42 of the 160 files are there.
 *
 * The remaining 118 are held by three things, and only three: a type that
 * cannot be named off Android (a `Context`, `SharedPreferences`, `org.json`,
 * `androidx.paging`, media3, Coil), a composition root that by definition needs
 * a platform to resolve against, and screen code that is not yet split
 * factory-from-viewmodel. The measurements that decide which is which are in
 * kmp-migration-plan.md; the two rules that decide what is worth changing are
 * in AGENTS.md under *A type-level port is the lever that moves files*.
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
        iosMain.get().dependsOn(appUi)

        // What every target needs. These are the dependencies a `commonMain`
        // file is *allowed* to reach for, so the set is deliberately small: it
        // grows when a file moves, not before, because a dependency declared
        // here with no consumer yet is a portability claim nothing backs.
        commonMain.dependencies {
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
            // `:home`'s *services* (`HomeCatalogService`, `CalendarService`,
            // `UpNextService`) are still `androidMain` and still unreachable from here.
            // Declaring the module does not grant that, because `commonMain` sees only
            // `commonMain`.
            implementation(project(":android:home"))
            // NOT `:android:native-engine`. It is a plain `com.android.library`,
            // so it publishes no JVM variant and cannot be consumed from a KMP
            // `commonMain` at all -- the same constraint `:ui-assets` hit. The one
            // file that needs it, `PlayerSessionSupport`, stays in `androidMain`.
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
            // in the same graph, and the goldens would then be verifying a version
            // no other target resolves.

            implementation(project(":android:home"))
            implementation(project(":android:player"))
            implementation(project(":android:native-engine"))
            implementation(project(":android:network"))
            implementation(project(":android:watchhistory"))
            implementation(project(":android:platform-android"))
            implementation(project(":android:backend"))
            implementation(project(":android:addons"))
            implementation(project(":android:ui-assets"))

            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.activity.compose)

            implementation(libs.androidx.navigation.compose)

            implementation(libs.androidx.paging.runtime)
            implementation(libs.androidx.paging.compose)

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
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.coil.svg)
            implementation(libs.coil.core)
            implementation(libs.material.kolor)
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
        // covers the settings repositories, the account repositories and nothing
        // in the composition root, while `androidHostTest` below covers exactly
        // the reverse.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // `runTest`, for the suspend-shaped ports. The settings tests are all
            // synchronous and did not need it; the account repositories are not.
            implementation(libs.coroutines.test)
        }

        // The composition root's own tests. `withHostTest {}` above is what creates
        // this source set and the `testAndroidHostTest` task; it is not named on
        // the `sourceSets` container, so `getByName` is the only way to reach it.
        //
        // Robolectric, and only for a `Context`. Nothing here inflates a view or
        // reads a resource, so `@Config(manifest = Config.NONE)` is enough and the
        // merged manifest and the real app theme are not needed -- which is why
        // these can live in `:app` while the golden screenshots must stay in
        // `:androidApp`. Robolectric is already a dependency of this repository.
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
