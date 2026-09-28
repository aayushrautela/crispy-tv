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
- `:android:app`: the shared UI and presentation layer, a Kotlin Multiplatform library whose sources are still all in `androidMain`
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
- **Host prerequisite:** a *failing* screenshot needs a host font, because Roborazzi labels the diff with Java2D. On a host with no font stack the failure reports `Fontconfig head is null` instead of the real difference. It still fails the build, but the diff is unreadable. `test-fonts/` at the repository root bundles Roboto and a generated fontconfig, which covers hosts that have libfontconfig but no fonts; a host with **no** `libfontconfig.so.1` at all needs the library supplied some other way, and root is not required: `dnf download --resolve --alldeps mesa-libGL libX11 fontconfig` then extract each rpm with `rpm2cpio | cpio -idm` into a scratch directory and point `LD_LIBRARY_PATH` at it. That is how the bare Fedora container this was last verified on runs both rendering suites. Note that `cpio -idm` applies the payload's directory modes, so a single shared target directory makes the *second* rpm fail on a read-only directory — stage each one separately and merge.

Kotlin Multiplatform modules (in progress; see `check-local.sh`):
- `:android:platform-core`: platform-portability interfaces (`SecretStore`, `KeyValueStore`, `AppLogger`, `TimeSource`, `DistributionCapabilities`). `commonMain` must stay platform-free.
- `:android:core-domain`: KMP (Android + `desktop` JVM + `linuxX64` + `iosArm64` + `iosSimulatorArm64`). Its `commonMain` is free of `java.*`/`android.*`; `scripts/check_common_purity.py` enforces that with no allowlist. The `java.time` and `URLEncoder` call sites were replaced with portable equivalents pinned by unit tests against real JVM output, which is what unblocked declaring the Apple targets.
- `linuxX64` on the pure-Kotlin KMP modules is a **compile-only verification target**, never shipped and never run. It is the one Kotlin/Native target that builds on a Linux host, so `compileKotlinLinuxX64` in `check-local.sh` enforces the same "no JVM API" rule as the Apple targets in seconds instead of waiting for macOS CI. The import-based purity gate cannot see this class of bug: `"x".format(y)` is `kotlin.*`, so it passes the import scan and then fails only when an Apple target compiles. Prefer the native compile gate for anything touching `commonMain`.
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

GitHub Actions (`.github/workflows/`), all `workflow_dispatch`-only by deliberate choice:
- `android.yml` — verification for the app (both flavors) and the TV app: purity gate, fixtures, contract + domain + plugins tests, golden screenshots, lint, assemble, and the dex-level distribution assertion.
- `apple.yml` — the only place Apple can be verified: compiles the KMP Apple targets, runs their simulator tests, the Swift contract suite, and the iOS/tvOS Xcode gate.
- `android-release.yml` — signed release artefacts. Separate from `android.yml` because it alone needs the release signing secrets.
- `apple-release.yml` — unsigned sideloading IPA.
- **`kotlin.srcDir(...)` must be given a TaskProvider, never a directory.** `layout.buildDirectory.dir("...")` is a `Provider<Directory>` and carries no task information, so nothing orders the generating task before compilation: the task silently never runs, and the build only succeeds if a *previous* run left the generated files behind. This bit `:android:platform-core`'s `generateAppConfig`, so `AppConfig` was unresolved on every clean machine and fine locally for weeks. `kotlin.srcDir(generateAppConfig)` registers the task's outputs as the source dir and infers the dependency. All four generator tasks are now wired this way or with an explicit `dependsOn`; when adding a fifth, use the TaskProvider.
- **Workflow YAML must parse with no duplicate keys** (`scripts/validate_workflows.py`, run first in `check-local.sh` and as the first step of every workflow). A duplicate key makes a workflow fail to *load*, which produces a run that fails in under a second with an empty log. The symptom reads as "the tests failed" when no test ever ran, and nothing inside the workflow can report it, because it never started. This happened: two `run:` lines under one step in `android.yml` and `android-release.yml` produced two instant-failure runs and no test output. `yaml.safe_load` takes the last value for a repeated key and reports nothing, so the validator loads through a constructor that raises on the second occurrence, at any depth.
- **`BUILD SUCCESSFUL` is not evidence that the output matches the sources.** An incremental Kotlin compile can leave a class behind that no declaration produces any more, and the migration provokes this constantly because its most common edit is moving a file between source sets. `git mv` preserves mtime, so Gradle's up-to-date check can treat a moved file as unchanged and skip it; and the Kotlin incremental compiler does not reliably delete the class of a declaration *removed from* a file it still considers current. Either way a stale class binds a reference that should have failed to compile, which makes **every other gate meaningless** — a later green build certifies nothing. This happened during the extraction of the 52 backend response types: `CrispyBackendClient$ResponsiveImageSet.class` sat in the output seven hours after the nested type stopped existing, found only by reading an `ls -l` timestamp. `scripts/verify_kmp_outputs.py` now asserts no compiled class has no source declaration, and runs at the end of `check-local.sh` and in `android.yml`; it reads `build/classes/kotlin`, so it must run *after* the compile tasks. A CI runner is always clean and never sees this, which is exactly why the check belongs to the local gate. After moving a file between source sets, verify the class actually exists in the new output.
- **Never rewrite source with a regex.** A regex applied to source files cannot see inside comments or string literals, so it silently corrupts or deletes code. This cost a full revert during the backend extraction: a "sound" cleanup rule matched any `com.crispy.tv.backend.X` import, self-matched on the import's own package, and deleted 140 live imports; a separate heuristic matched `User` inside the string `"User-Agent"` and added eight phantom imports. Neither failed loudly — the compiler did, which is the only reason the damage was caught. For a mechanical change, either let the compiler find the affected sites and fix them by hand, or parse properly (see the lexer in `scripts/verify_kmp_outputs.py` for what "properly" means: a character scanner, not a substitution chain). A regex is fine for *matching* a single line you then read; it is not fine for *rewriting*.
- Names are platform + intent, not Gradle build type. Do not reintroduce `debug`/`release` into workflow names; "debug CI" and "debug build" are different things.
- `verify_apk_distribution.py` reads the **dex** and is only valid on unminified builds, so it runs in `android.yml` (debug) and not in `android-release.yml`. Release asserts via `verifyDistributionExclusions`, which reads the dependency graph and is minification-proof.
- **Flavors live only in `:androidApp`, and that is not negotiable.** AGP's `com.android.kotlin.multiplatform.library` has **no** `productFlavors` at all (unlike `com.android.library`/`com.android.application`), and a KMP library cannot even *consume* a flavored `com.android.library`: the library plugin is single-variant, so it states no preference between a dependency's `store*` and `sideload*` variants and Gradle fails with an ambiguous-variant error naming every candidate. Two modules were forced off that axis by this and both losses turned out to be dead weight: `:android:network` (the YouTube extractor, now the sideload-only module `:android:youtube-extractor` reached through the `TrailerExtractor` interface) and `:android:plugins`, whose entire `store` source set was one unread `internal val PluginsRuntimeSupported = false` that was never even compiled. Do not reintroduce `matchingFallbacks` to paper over this — it would compile `:app` against the store variant while a sideload APK shipped the sideload one, the same class of lie as the unwired torrent resolver fixed in `911f8d75`.
- Distribution is a permanent two-flavor axis: `store` (Play/App Store) and `sideload` (APK/IPA). Optional engines are excluded **structurally** — the torrent engine, the QuickJS plugin runtime and the YouTube extractor are separate modules that only `sideload` depends on. Never reintroduce a null-returning stub for something the store build should simply not contain. Two guards enforce this: `./gradlew :android:androidApp:verifyDistributionExclusions` reads the resolved dependency graph, and `scripts/verify_apk_distribution.py` reads the built dex and asserts in both directions.
- The flavour axis does not stop the migration; it moves with the entry point. `:app` *is* now a flavor-less KMP library, because the KMP plugin cannot carry `productFlavors` while the flavour axis is a product requirement. `:androidApp` is the `com.android.application` that declares them, and `:app` reaches the variant through the `DistributionComponents` seam rather than through source sets.
- Apple targets are declared but cannot compile on Linux. Never run aggregate tasks (`build`, `check`, `allTests`); they reach the Kotlin/Native targets and fail. Use `./check-local.sh` or targeted tasks.

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
