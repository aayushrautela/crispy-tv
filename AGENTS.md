# Agent Guide (Build, Test, Style)

Android + iOS rewrite workspace with contract-driven parity. Keep Android (`android/core-domain`) and Swift (`ios/ContractRunner`) aligned with `contracts/SPEC.md`.

Repo agent rules:
- No `.cursor/rules/` or `.cursorrules` found.
- No `.github/copilot-instructions.md` found.

## Toolchain (match CI)

- JDK 21 **everywhere**, including `jvmToolchain` in `:android:core-domain` and `:android:app`. Do not reintroduce a 17 toolchain: neither module is published and every consumer is already compiling at 21, so 17 bytecode bought nothing while making the build depend on a JDK CI does not install.
- Android SDK `platforms;android-37.0` + `build-tools;36.0.0`
- Gradle 9.7.1 via the committed wrapper: always use `./gradlew`, never a bare `gradle` (it is not installed, and a different Gradle version starts a second daemon that nothing reclaims)
- Python 3.12 + `jsonschema==4.23.0`
- Xcode + `xcodegen`; Swift tools 5.9

## Commands

Contracts (fast):
```sh
python3 -m pip install jsonschema==4.23.0
python3 scripts/validate_contracts.py
./gradlew :android:core-domain:desktopTest :android:core-domain:testAndroidHostTest
swift test --package-path ios/ContractRunner
```

Other useful tasks:
```sh
# JVM unit tests (if present)
./gradlew :android:core-domain:test
./gradlew :android:androidApp:testStoreDebugUnitTest

# Clean
./gradlew clean
```

Single test (important):
```sh
# Contract suite (all 96 fixtures). It is one suite compiled for every target, so
# a single test filter is applied to the target you want:
./gradlew :android:core-domain:desktopTest --tests 'com.crispy.tv.contracts.PlayerMachineContractTest'
./gradlew :android:core-domain:desktopTest --tests 'com.crispy.tv.contracts.PlayerMachineContractTest.playerMachineFixtures'
./gradlew :android:core-domain:testAndroidHostTest --tests 'com.crispy.tv.contracts.PlayerMachineContractTest'

# SwiftPM
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests.testSomeCaseName
```

Android builds/lint:
```sh
./gradlew :android:androidApp:assembleStoreDebug :android:androidApp:assembleSideloadDebug :android:tv:assembleDebug
./gradlew :android:androidApp:assembleStoreRelease :android:androidApp:assembleSideloadRelease :android:tv:assembleRelease
./gradlew :android:androidApp:lintStoreDebug :android:androidApp:lintSideloadDebug
./gradlew :android:tv:lintDebug
```

Apple placeholder compile gate:
```sh
xcodegen generate --spec ios/project.yml
xcodebuild -scheme CrispyRewriteiOS -destination 'platform=iOS Simulator,name=iPhone 16' build
xcodebuild -scheme CrispyRewritetvOS -destination 'platform=tvOS Simulator,name=Apple TV' build
```

Optional native deps (CI parity):
```sh
bash .github/scripts/fetch-torrserver-binaries.sh
```

## Project Layout

Gradle modules (common targets):
- `:android:androidApp`: the Android entry point (`com.android.application`) — manifest, `res/` that only the application needs, signing, ProGuard, ABI splits, the `store`/`sideload` flavours, the golden-screenshot tests
- `:android:app`: the shared UI and presentation layer, a Kotlin Multiplatform library. Its `commonMain` now holds the design-agnostic UI (routes, `ui/components`, `ui/navigation`, `ui/edge_to_edge`, `ui/utils`, the seek/gesture feedback surfaces) and `androidMain` holds the rest; `commonMain` is a strict subset of the Android build, so the phone app and the desktop app compile the same files.
- `:android:sharedUI`: the design system **and the design assets** (Phase 4 Step 1) — `composeResources`, the theme tokens, the brand composables
- `:android:ui-assets`: Android-only assets that cannot be `composeResources` — launcher mipmaps, the splash colour and its two drawables, the nine provider-logo SVGs
- `:android:youtube-extractor`: sideload-only YouTube stream extraction (NewPipeExtractor)
- `:android:tv`: Android TV placeholder app (must compile)
- `:android:core-domain`: pure domain rules (no Android types/IO), **and** the contract suite in `commonTest` (see below)
- `:android:player`, `:android:network`, `:android:watchhistory`, `:android:native-engine`: Android libraries
- `:android:torrent-engine`: sideload-only torrent engine. `:android:native-engine` holds the MPV/Media3 player and nothing else optional.

Golden screenshots (`:android:androidApp:testStoreDebugUnitTest`):
- Robolectric + Roborazzi, running on a plain JVM. No emulator, no KVM, no `androidTest` device. This is the only rendering coverage in the repository.
- **Verify is the default**; re-record with `./gradlew :android:androidApp:testStoreDebugUnitTest -Proborazzi.record=true`. Goldens are committed under `android/androidApp/src/test/screenshots/`, so a missing golden fails the build.
- The tests live in `:androidApp`, not `:app`, and that is not arbitrary: they need `isIncludeAndroidResources = true` to inflate the real app theme, which only an application module has. They still render composables that live in `:app`.
- The Roborazzi Gradle plugin is deliberately NOT applied: 1.43.1 fails against AGP 9.3 with `Extension of type 'TestedExtension' does not exist`. Record/verify is driven by the `-Proborazzi.*` properties in `android/androidApp/build.gradle.kts` instead.
- Screenshot tests must set `application = ScreenshotTestApplication::class`. Robolectric otherwise boots `CrispyApplication`, whose `onCreate` reaches an `AndroidKeyStore` that cannot exist on a JVM.
- Freeze the Compose clock (`mainClock.autoAdvance = false`) or animated content never matches.
- **Host prerequisite:** a *failing* screenshot needs a host font, because Roborazzi labels the diff with Java2D. On a host with no font stack the failure reports `Fontconfig head is null` instead of the real difference. It still fails the build, but the diff is unreadable. `test-fonts/` at the repository root bundles Roboto and a generated fontconfig, which covers hosts that have libfontconfig but no fonts; a host with **no** `libfontconfig.so.1` at all needs the library supplied some other way, and root is not required: `dnf download --resolve --alldeps mesa-libGL libX11 fontconfig` then extract each rpm with `rpm2cpio | cpio -idm` into a scratch directory and point `LD_LIBRARY_PATH` at it. That is how the bare Fedora container this was last verified on runs both rendering suites. Three traps in that sequence, all of which produce a failure that looks like a code regression and is not:
  - `dnf download` fetches **both** `i686` and `x86_64` packages, and merging them puts a 32-bit `libfontconfig.so.1` on the path. Skiko then dies with `UnsatisfiedLinkError: libfontconfig.so.1: wrong ELF class: ELFCLASS32`, surfacing as `ExceptionInInitializerError` from `SkiaGraphicsContext` and then as four `NoClassDefFoundError: RenderNodeContext` — which reads as "the desktop seam proof is broken" and is not. Extract only `*x86_64*.rpm` and check the ELF class before trusting the result.
  - `cpio -idm` applies the payload's directory modes, so a single shared target directory makes the *second* rpm fail on a read-only directory — stage each one separately and merge. A `cp -a` that fails this way is silent under `2>/dev/null`, so verify the three critical libraries are actually present afterwards rather than assuming the loop worked.
  - `rm -rf` on the previous merge fails on the same read-only directories, leaving stale libraries behind. Build a fresh directory and check the merged output for 32-bit artefacts.

Kotlin Multiplatform modules (in progress; see `check-local.sh`):
- `:android:platform-core`: platform-portability interfaces (`SecretStore`, `KeyValueStore`, `AppLogger`, `TimeSource`, `DistributionCapabilities`). `commonMain` must stay platform-free.
- `:android:core-domain`: KMP (Android + `desktop` JVM + `linuxX64` + `iosArm64` + `iosSimulatorArm64`). Its `commonMain` is free of `java.*`/`android.*`; `scripts/check_common_purity.py` enforces that with no allowlist. The `java.time` and `URLEncoder` call sites were replaced with portable equivalents pinned by unit tests against real JVM output, which is what unblocked declaring the Apple targets.
- `linuxX64` on the pure-Kotlin KMP modules is a **compile-only verification target**, never shipped and never run. It is the one Kotlin/Native target that builds on a Linux host, so `compileKotlinLinuxX64` in `check-local.sh` enforces the same "no JVM API" rule as the Apple targets in seconds instead of waiting for macOS CI. The import-based purity gate cannot see this class of bug: `"".format(y)` is `kotlin.*`, so it passes the import scan and then fails only when an Apple target compiles. Prefer the native compile gate for anything touching `commonMain`.
- `:android:desktopApp`: the desktop entry point and the **seam proof** (plan §3) — one JVM module for Windows, macOS and Linux. Renders `:android:sharedUI`'s design system over `:android:core-domain`'s real `planContinueWatching`, seeded from a real contract fixture. It is a semantic-assertion test, not a golden: a Skia raster varies by Skia version and font availability, and the Android Roborazzi gate is already the rendering gate. **Host prerequisites:** Skia needs `libGL.so.1`, `libX11.so.6` and `libfontconfig.so.1`, and needs at least one font. The font is bundled in `test-fonts/` (repository root, shared with `:android:androidApp`) and reached through a generated `fonts.conf`. Without those the test reports a Skiko native-load error or `IllegalStateException: Could not load font` — neither says anything about the seam. CI's `ubuntu-latest` has all of them.
- `:android:sharedUI`: KMP + Compose Multiplatform `1.11.1`, targeting Android + `desktop` JVM + `iosArm64` + `iosSimulatorArm64`, and producing the `CrispyUI` iOS framework. Holds the design system in `commonMain`. Three rules that are expensive to relearn:
  - **`android { }` is current; `androidLibrary { }` is deprecated** as of Kotlin `2.4.10`, which says so outright: *"'androidLibrary' block is deprecated. Please use 'android' instead."* Earlier guidance — the JetBrains migration guide, and earlier revisions of this file — said the opposite and described a real failure with `android { }`. That failure is gone; JetBrains converged the two blocks. Write `android { }` on new code. `:sharedUI` still uses `androidLibrary { }` and compiles, with a deprecation warning.
  - **CMP 1.11.x ships `androidx.compose.*`, not `org.jetbrains.compose.*`.** Verified by unzipping the resolved AARs: 790 `androidx/compose` classes in `runtime-android`, 1336 in `ui-android`, and **zero** `org/jetbrains/compose` in any of them. The `org.jetbrains.compose.*` coordinates are thin aliases. So moving a Compose file from `:app` to `:sharedUI` changes the **artifact coordinates in `build.gradle.kts`**, never the imports in the file, and `import org.jetbrains.compose.*` does not compile anywhere.
  - **No `linuxX64` on Compose modules.** CMP publishes no linuxX64 artifacts — its targets are Android, iOS and Desktop (JVM) only — so declaring it makes every `compose.*` dependency fail to resolve. `jvm("desktop")` is the local purity gate for Compose modules instead. This is why the `linuxX64` rule above is scoped to pure-Kotlin modules.
  - `LocalConfiguration` is Android-only even under CMP (it lives in `AndroidCompositionLocals_androidKt`). Use `LocalWindowInfo.current.containerDpSize` — `containerSize` is `IntSize` px, `containerDpSize` is `DpSize`.
  - **Material3 Expressive is available on every target, and is used on every target. Settled — do not re-litigate, and do not "fix" it by dropping it.** The whole blocker was one line in a build file. This repo recorded, in three places, that Phase 4 "has to drop or replace Material3 Expressive" because `androidx.compose.material3:1.5.0-alpha26` publishes no `material3-desktop` artifact. That was inferred from a coordinate mismatch and was **wrong**, and acting on it would have deleted a shipping design feature to work around a version pin.
    The fix is to declare **`org.jetbrains.compose.material3:material3`** (`libs.compose.material3`) instead of either the androidx coordinate or the `compose.material3` alias. That coordinate is a thin alias that delegates per target, verified by resolving the graph: on Android it becomes `androidx.compose.material3:material3-android:1.5.0-alpha27` — genuine AndroidX, one alpha *forward* of what the app shipped — and on desktop/iOS it becomes `org.jetbrains.compose.material3:material3-desktop:1.13.0-alpha01`, the real fork carrying `LoadingIndicator`, `MaterialShapes` and `WavyProgressIndicator`. So there is exactly one material3 on any classpath, the `androidx.compose.material3` package and imports are identical everywhere, and **no `expect`/`actual` seam is needed**. The 12 Expressive call sites are untouched.
    **The `compose.material3` alias cannot be used, and this is the part that is easy to get wrong.** It deliberately tracks the latest *stable* Material3, which is 1.4.0 and has no Expressive at all — CMP decoupled Material3 versioning in 1.9.0 precisely so a project can opt forward. Gradle now deprecates it too. Always take material3 from `libs.compose.material3`.
    **Version choice: forward, never backward.** `composeMaterial3 = 1.13.0-alpha01` is the only published JetBrains material3 at or ahead of the AndroidX alpha the app was on. The nearby `1.12.0-alpha03` would have been a **downgrade on two axes** — AndroidX material3 to `1.5.0-alpha22` *and* the whole Compose stack to `1.12.0-beta01`, which is *older* than the `1.12.0` stable it replaces (publication order is alpha01..alpha03, beta01, beta02, rc01, 1.12.0). Do not take it. The accepted cost is that the Compose stack leaves stable for `1.13.0-alpha02`; `composeAndroidx` is pinned to match it and must be bumped together with `composeMaterial3`, never alone.
    Verified rather than assumed: the goldens were re-run after the bump, and `PlayerLoadingCurtainScreenshotTest` — which renders an Expressive `LoadingIndicator` — is **byte-identical** across `1.5.0-alpha26 → 1.5.0-alpha27`, with no golden re-recorded.
    Two related traps. **14 files carry `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` but only 6 touch an Expressive symbol** — the other 8 annotate while using only ordinary Material3 such as `MaterialTheme`, so their annotation is vestigial and can be deleted as those files move. Grepping for the annotation is not the same as finding Expressive usage. And **never read an AAR's size to decide whether a version has a feature**: `androidx.compose.material3:material3` is a ~4.7 KB stub holding only a manifest and a licence at every version, with the real code in the `material3-android` sub-module. Read the per-target artifact, or the `*.module` metadata, or just resolve the dependency graph.
  - **`platform(libs.androidx.compose.bom)` does not exist on a KMP source set.** `KotlinDependencyHandler` has no `platform()`, so a KMP library cannot apply a BOM and must pin versions itself. A plain Android module like `:androidApp` can, and does.
  - Pin CMP `1.11.1` to Kotlin `2.4.10`; bump them together or not at all.

## Compose resources (Phase 4 Step 1)

The 99 design drawables, the 12 genre rasters, the Archivo font, and `CrispyBrand`/`CrispyIntroSplash` now live in `:android:sharedUI` as `composeResources`. `:ui-assets` keeps only what Compose Multiplatform cannot carry. **These four facts are the expensive part; each was learned the hard way and the cheap-looking alternative is wrong.**

- **Every accessor needs its own import.** `Res.drawable.ic_search` is a *top-level extension property* on `Res.drawable`, not a member. `import com.crispy.tv.ui.resources.Res` alone brings in the nested `object drawable` and nothing else, so `Res` and `Res.drawable` resolve while every accessor reads `Unresolved reference`. That exact symptom was misdiagnosed here as a CMP limitation, and cost an hour and a revert. Look it up before concluding anything.
- **`publicResClass = true` is the supported switch, and the accessors come out `public`.** `Res` is `internal` by default. Set it in `:sharedUI/build.gradle.kts` alongside `packageOfResClass = "com.crispy.tv.ui.resources"` — the default would be `crispy_rewrite.android.sharedui.generated.resources`, which leaks the Gradle coordinates into every import. Do **not** use the `doLast` block that rewrites `internal object Res` in the generated file; upstream labels it a temporary workaround and it patches generated output. In CMP 1.11.1 the generated accessors are `public val Res.drawable.x`, so cross-module use needs nothing but the import.
- **`org.jetbrains.compose.resources.painterResource` replaces `androidx.compose.ui.res.painterResource` wherever a drawable is involved.** They are different overloads: the Android one takes an `Int`, the CMP one a `DrawableResource`. The same applies to `Font` (`org.jetbrains.compose.resources.Font` takes a `FontResource`). `Font(FontResource)` is **`@Composable`** — it reads the resource bytes during composition — so a top-level `val FontFamily(Font(Res.font.x))` no longer compiles and needs a composable property getter. `PlayerOverlayControls.kt` keeps the *Android* import too: it is the one place still passing an Android drawable, `com.crispy.tv.app.R.drawable.ic_player_aspect_ratio`, which stays in `:app`'s own `res/`.
- **Deciding which import a file needs is not a textual problem, so let the compiler decide.** A file can pass a `DrawableResource` to `painterResource` while never naming `Res.` — it receives the value as a parameter, exactly as `ItemActionSheet` and `SidebarNavigation` do. No source-level rule distinguishes that from a genuine `Int`, and a rule that guesses wrong leaves a file that either does not compile or silently keeps a dead import. Loop on the compiler's `actual type is 'DrawableResource', but 'Int' was expected` and add the import to exactly the files it blames.

**What stayed in `:ui-assets`, and why — do not "finish" the move by dragging these across:**

| Asset | Why it cannot be `composeResources` |
|---|---|
| 40 launcher mipmaps | referenced from two `AndroidManifest.xml` files and one notification icon; a manifest names an Android resource, never a composeResources one. Each app should own its own icon. |
| `splash_mark`, `splash_mark_animated` | named by `windowSplashScreenAnimatedIcon` in the `:androidApp` and `:tv` themes. An XML theme can only name an Android drawable. |
| `splash_background` colour | `windowSplashScreenBackground` in the same themes. |
| 9 provider-logo SVGs | **CMP documents SVG as "supported on all platforms except Android"**, so `painterResource(Res.drawable.trakt)` would fail silently on the platform that matters most. These are `@RawRes Int?` passed to Coil. The portable route is `composeResources/files` + `Res.readBytes(path)`, but Coil then needs an SVG decoder. This is an open design decision with product consequences, not a rename. |

`ic_star` was deleted: it had zero references (`ic_star_filled` is the one in use) and the Kotlin-only reference count is what proved it. Note that a Kotlin-only count is also what *nearly* proved `splash_mark_animated` dead — it is referenced from XML, which that count cannot see.

`:ui-assets` no longer needs `kotlin.compose`, the Compose dependencies, or `missingDimensionStrategy("distribution", "sideload")`. All three were removed and the whole graph still resolves; the flavour axis was never the constraint it appeared to be.

GitHub Actions (`.github/workflows/`), all `workflow_dispatch`-only by deliberate choice:
- `android.yml` — verification for the app (both flavors) and the TV app: purity gate, fixtures, contract + domain + plugins tests, golden screenshots, lint, assemble, and the dex-level distribution assertion.
- `apple.yml` — the only place Apple can be verified: compiles the KMP Apple targets, runs their simulator tests, the Swift contract suite, and the iOS/tvOS Xcode gate.
- `android-release.yml` — signed release artefacts. Separate from `android.yml` because it alone needs the release signing secrets.
- `apple-release.yml` — unsigned sideloading IPA.
- **`kotlin.srcDir(...)` must be given a TaskProvider, never a directory.** `layout.buildDirectory.dir("...")` is a `Provider<Directory>` and carries no task information, so nothing orders the generating task before compilation: the task silently never runs, and the build only succeeds if a *previous* run left the generated files behind. This bit `:android:platform-core`'s `generateAppConfig`, so `AppConfig` was unresolved on every clean machine and fine locally for weeks. `kotlin.srcDir(generateAppConfig)` registers the task's outputs as the source dir and infers the dependency. All four generator tasks are now wired this way or with an explicit `dependsOn`; when adding a fifth, use the TaskProvider.
- **Workflow YAML must parse with no duplicate keys** (`scripts/validate_workflows.py`, run first in `check-local.sh` and as the first step of every workflow). A duplicate key makes a workflow fail to *load*, which produces a run that fails in under a second with an empty log. The symptom reads as "the tests failed" when no test ever ran, and nothing inside the workflow can report it, because it never started. This happened: two `run:` lines under one step in `android.yml` and `android-release.yml` produced two instant-failure runs and no test output. `yaml.safe_load` takes the last value for a repeated key and reports nothing, so the validator loads through a constructor that raises on the second occurrence, at any depth.
- **A class file in the output can outlive the declaration that produced it, and the cause is not established.** During the extraction of the 52 backend response types, `CrispyBackendClient$ResponsiveImageSet.class` sat in `build/classes/kotlin` with a timestamp seven hours older than the change that removed the nested type, and a `clean` followed by a normal build left it visible for a while. A stale class binds a reference that should have failed to compile, so a later green build certifies nothing and **every other gate in this repository becomes unreliable**. That risk is real even though the mechanism is unexplained. **The two obvious explanations were tested and are both false**, so do not repeat them as if they were established: (1) removing a nested declaration from a file and recompiling incrementally deletes its class correctly, and (2) `git mv`-ing a file between source sets with mtime preserved did not cause Gradle to skip it — the task reran and produced the class. `scripts/verify_kmp_outputs.py` therefore exists as a *detector* rather than a fix: it asserts no compiled class has no source declaration, and it is proven by injecting both a top-level orphan and a nested one. It runs at the end of `check-local.sh` and in `android.yml`; it reads `build/classes/kotlin`, so it must run *after* the compile tasks. A CI runner is always clean, which is why the check belongs to the local gate. If you ever see this fire on an artefact you did not inject, that is the observation that explains it — capture it rather than reaching for a mechanism.
- **The detector has to know how Kotlin names a file facade when the file name contains a dot.** It infers `<FileName>Kt` from the source stem, and the compose-resources plugin names its generated accessors `Drawable0.commonMain.kt`, whose facade is `Drawable0_commonMainKt`. Using the stem verbatim reported all 111 migrated resources as orphans — indistinguishable from the stale-output breakage the gate exists to catch, on a tree that was entirely clean. **A safety gate that fires on correct code gets switched off, so keep its notion of "correct" in step with the code generators it reads.** Re-prove it after any change by injecting all three orphan shapes: a top-level class, a nested class whose owner does not name it, and a facade whose name contains a dot.
- **Never rewrite source with a regex.** A regex applied to source files cannot see inside comments or string literals, so it silently corrupts or deletes code. This cost a full revert during the backend extraction: a "sound" cleanup rule matched any `com.crispy.tv.backend.X` import, self-matched on the import's own package, and deleted 140 live imports; a separate heuristic matched `User` inside the string `"User-Agent"` and added eight phantom imports. Neither failed loudly — the compiler did, which is the only reason the damage was caught. For a mechanical change, either let the compiler find the affected sites and fix them by hand, or parse properly (see the lexer in `scripts/verify_kmp_outputs.py` for what "properly" means: a character scanner, not a substitution chain). A regex is fine for *matching* a single line you then read; it is not fine for *rewriting*.
- Names are platform + intent, not Gradle build type. Do not reintroduce `debug`/`release` into workflow names; "debug CI" and "debug build" are different things.
- `verify_apk_distribution.py` reads the **dex** and is only valid on unminified builds, so it runs in `android.yml` (debug) and not in `android-release.yml`. Release asserts via `verifyDistributionExclusions`, which reads the dependency graph and is minification-proof.
- **Flavors live only in `:androidApp`, and that is not negotiable.** AGP's `com.android.kotlin.multiplatform.library` has **no** `productFlavors` at all (unlike `com.android.library`/`com.android.application`), and a KMP library cannot even *consume* a flavored `com.android.library`: the library plugin is single-variant, so it states no preference between a dependency's `store*` and `sideload*` variants and Gradle fails with an ambiguous-variant error naming every candidate. Two modules were forced off that axis by this and both losses turned out to be dead weight: `:android:network` (the YouTube extractor, now the sideload-only module `:android:youtube-extractor` reached through the `TrailerExtractor` interface) and `:android:plugins`, whose entire `store` source set was one unread `internal val PluginsRuntimeSupported = false` that was never even compiled. Do not reintroduce `matchingFallbacks` to paper over this — it would compile `:app` against the store variant while a sideload APK shipped the sideload one, the same class of lie as the unwired torrent resolver fixed in `911f8d75`.
- Distribution is a permanent two-flavor axis: `store` (Play/App Store) and `sideload` (APK/IPA). Optional engines are excluded **structurally** — the torrent engine, the QuickJS plugin runtime and the YouTube extractor are separate modules that only `sideload` depends on. Never reintroduce a null-returning stub for something the store build should simply not contain. Two guards enforce this: `./gradlew :android:androidApp:verifyDistributionExclusions` reads the resolved dependency graph, and `scripts/verify_apk_distribution.py` reads the built dex and asserts in both directions.
- The flavour axis does not stop the migration; it moves with the entry point. `:app` *is* now a flavor-less KMP library, because the KMP plugin cannot carry `productFlavors` while the flavour axis is a product requirement. `:androidApp` is the `com.android.application` that declares them, and `:app` reaches the variant through the `DistributionComponents` seam rather than through source sets.
- Apple targets are declared but cannot compile on Linux. Never run aggregate tasks (`build`, `check`, `allTests`); they reach the Kotlin/Native targets and fail. Use `./check-local.sh` or targeted tasks.
- **A plain `com.android.library` cannot be consumed from a KMP `commonMain` at all.** Only a `com.android.kotlin.multiplatform.library` publishes a JVM variant. `:android:native-engine` and `:ui-assets` are both plain libraries, so neither can be a `commonMain` dependency — the same wall that stopped `:ui-assets` being used from a shared module. Check `plugins { }` in the module's `build file` before adding it to a common source set's `dependencies`; the compiler error is an ambiguous-variant listing with no hint that the cause is the module *type*.
- **`git mv` preserves mtime, so Gradle's incremental Kotlin compile can skip a moved file and report `BUILD SUCCESSFUL` with no class produced.** This bit the move of `PlayerStreamHandoff` back from `commonMain` to `androidMain`: the class file was already in `build/classes/kotlin/android/main`, so the compile reported failure while the output was correct-looking, and the errors (`Unresolved reference` in three files, plus `Destructuring of type 'Any'` cascades) read as a missing declaration. **Always run a moved file's compilation with `--rerun-tasks` once, and treat any `Unresolved reference` for a class you can see in `build/classes` as this.** The `verify_kmp_outputs.py` gate is the detector, not a fix.

Apple:
- `ios/ContractRunner`: SwiftPM contract runner (mirrors `android/core-domain` behavior)
- `ios/project.yml`: XcodeGen spec for placeholder iOS/tvOS apps (compile gate)

Contracts:
- `contracts/SPEC.md`: source of truth for heuristics + deterministic rules
- Fixtures are **compiled in, not read from disk**. `generateContractFixtures` in
  `android/core-domain/build.gradle.kts` turns `contracts/fixtures/**/*.json`
  into a Kotlin source file under `build/generated/`, wired as a source directory
  of `commonTest`. Do not "simplify" this back to `java.nio.file` or okio: the
  suite is in `commonTest` so it runs on Android, desktop and the Apple targets,
  and there is no single path to the fixtures that is correct on all of them. The
  old `:android:contract-tests` module worked around that by walking up from the
  working directory looking for `settings.gradle.kts`.
- The generator sorts by relative path, so identical inputs give a byte-identical
  file and there is no spurious diff between machines.
- `scripts/validate_contracts.py` stays the authority on fixture *validity* (JSON
  Schemas). The generated file is only about getting the bytes readable on any
  target. `ContractFixturesSanityTest` is the guard that the fixtures actually
  reached the compilation, which Python cannot check.
- `compileTestKotlinLinuxX64` is the gate that proves `commonTest` holds no JVM
  API. The import-based purity gate only scans `commonMain`, so it cannot see a
  `java.nio.file` in test code, and the Apple targets would fail on macOS instead.
- `contracts/fixtures/` + `contracts/schemas/`: versioned JSON fixtures + schemas

## Configuration / Secrets

- Android app reads Gradle properties and injects them into `BuildConfig`; in CI these come from `ORG_GRADLE_PROJECT_*`.
- Do not commit secrets; use `~/.gradle/gradle.properties` for Trakt/Simkl ids+redirect URIs, `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, and `CRISPY_BACKEND_URL`.
- GitHub Android workflows pass the same values through `ORG_GRADLE_PROJECT_*`; set repository secret `CRISPY_BACKEND_URL` so CI builds embed the backend base URL in `BuildConfig.CRISPY_BACKEND_URL`.
- Signing: release uses `RELEASE_KEYSTORE_*` if present; otherwise debug signing. Debug can be overridden via `DEBUG_KEYSTORE_*`.
- Daemon memory/lifetime (heap, metaspace, idle timeouts) is tuned in `~/.gradle/gradle.properties`, which overrides this repo's `org.gradle.jvmargs`; that is deliberate, so leave the committed `-Xmx4g` alone.

## Code Style

General:
- Contracts drive behavior. If behavior changes, update `contracts/SPEC.md`, fixtures/schemas, Kotlin contract tests, and Swift ContractRunner.
- Determinism: pass `nowMs`/clock; inject seeded RNG; keep output ordering canonical.
- Gradle repos: `settings.gradle.kts` enforces `repositoriesMode = FAIL_ON_PROJECT_REPOS`; do not add repos in module `build.gradle.kts`.

Code quality principles:
- Keep solutions simple: follow KISS, DRY, and YAGNI.
- Refactor existing logic instead of layering new code on top.
- Remove obsolete, unused, or commented-out code.
- Keep functions focused on a single responsibility.
- Avoid premature abstractions; solve the immediate problem first.
- Prefer self-documenting names over comments.
- Reuse existing utilities before creating new ones.

Contracts:
- Fixtures include `contract_version`, `suite`, `case_id` (and `now_ms` when specified).
- Suites currently covered include: `player_machine`, `continue_watching`, `sync_planner`, `storage_v1`, `player_progress`.
- If you change behavior: bump `contract_version` (per SPEC), update fixtures/schemas, and keep Kotlin + Swift implementations in lockstep.

Kotlin (Android + JVM):
- Formatting: official Kotlin style (`kotlin.code.style=official`); 4-space indent.
- Imports: no wildcard imports; group stdlib -> Android/AndroidX -> third-party -> internal.
- Types: use nullability for optional values; normalize early (trim; treat blank as missing).
- Naming: `UpperCamelCase` types, `lowerCamelCase` functions/vars, `SCREAMING_SNAKE_CASE` consts; prefer contract domain terms.
- Architecture: keep pure rules in `android/core-domain` (no Android types/IO); use immutable state + reducers where it fits.
- Errors: no exceptions for normal control flow in domain; return explicit results; preserve coroutine cancellation (don’t swallow `CancellationException`).

Kotlin details (common patterns in this repo):
- Model actions/events as `sealed interface` + `data class`/`data object`.
- Prefer explicit mapping helpers for contract string values (canonical casing/format).
- Avoid nondeterminism: no `System.currentTimeMillis()` in domain; no iteration over unordered maps when output order matters.
- When enriching/merging metadata, keep precedence stable (server-first; fill missing only).

Swift (ContractRunner + placeholders):
- Keep APIs small and explicit; prefer `struct`.
- Imports: minimal; `Foundation` first.
- Fixture/test parsing: use `guard` + descriptive thrown errors; avoid force unwraps.
- Determinism: do not read system time directly; mirror contract heuristics exactly.

Swift details:
- Parse JSON fixtures into dictionaries/structs with explicit required/optional helpers; throw `LocalizedError` with fixture name and missing key.
- Keep output stable (ordering, tie-breakers) to match fixture expectations.

Python (tooling):
- Hermetic, deterministic scripts; non-zero exit on failure; errors include fixture path + JSON location.

## Single-change checklist

- `python3 scripts/validate_contracts.py`
- `python3 scripts/validate_workflows.py`
- `./gradlew :android:core-domain:desktopTest :android:core-domain:testAndroidHostTest`
- `python3 scripts/verify_kmp_outputs.py` (after any compile; catches a stale class a green build cannot)
- `swift test --package-path ios/ContractRunner` (if Swift logic touched)
- Ensure `:android:tv` and tvOS placeholder builds still compile
