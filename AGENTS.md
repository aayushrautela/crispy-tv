# Agent Guide (Build, Test, Style)

Android + iOS rewrite workspace with contract-driven parity. Keep Android (`android/core-domain`) and Swift (`ios/ContractRunner`) aligned with `contracts/SPEC.md`.

Repo agent rules:
- No `.cursor/rules/` or `.cursorrules` found.
- No `.github/copilot-instructions.md` found.

## Toolchain (match CI)

- JDK 21 **everywhere**, including `jvmToolchain` in `:android:core-domain` and `:android:contract-tests`. Do not reintroduce a 17 toolchain: neither module is published, `:contract-tests` is a leaf, and every consumer of `:core-domain` is an Android module already compiling at 21, so 17 bytecode bought nothing while making the build depend on a JDK CI does not install.
- Android SDK `platforms;android-37.0` + `build-tools;36.0.0`
- Gradle 9.7.1 via the committed wrapper: always use `./gradlew`, never a bare `gradle` (it is not installed, and a different Gradle version starts a second daemon that nothing reclaims)
- Python 3.12 + `jsonschema==4.23.0`
- Xcode + `xcodegen`; Swift tools 5.9

## Commands

Contracts (fast):
```sh
python3 -m pip install jsonschema==4.23.0
python3 scripts/validate_contracts.py
./gradlew :android:contract-tests:test
swift test --package-path ios/ContractRunner
```

Other useful tasks:
```sh
# JVM unit tests (if present)
./gradlew :android:core-domain:test
./gradlew :android:app:testDebugUnitTest

# Clean
./gradlew clean
```

Single test (important):
```sh
# Kotlin/JUnit5 (contract tests)
./gradlew :android:contract-tests:test --tests com.crispy.tv.contracts.PlayerMachineContractTest
./gradlew :android:contract-tests:test --tests com.crispy.tv.contracts.PlayerMachineContractTest.someTestName

# Kotlin/JUnit (unit tests in other modules)
./gradlew :android:core-domain:test --tests com.crispy.tv.domain.SomeUnitTest

# SwiftPM
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests.testSomeCaseName
```

Android builds/lint:
```sh
./gradlew :android:app:assemblePlayDebug :android:app:assembleFossDebug :android:tv:assembleDebug
./gradlew :android:app:assembleRelease :android:tv:assembleRelease
./gradlew :android:app:lintPlayDebug :android:app:lintFossDebug
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
- `:android:app`: main Android app (Compose)
- `:android:tv`: Android TV placeholder app (must compile)
- `:android:core-domain`: pure domain rules (no Android types/IO)
- `:android:contract-tests`: JUnit5 runner for `contracts/fixtures`
- `:android:player`, `:android:network`, `:android:watchhistory`, `:android:native-engine`: Android libraries
- `:android:torrent-engine`: sideload-only torrent engine. `:android:native-engine` holds the MPV/Media3 player and nothing else optional.

Golden screenshots (`:android:app:testPlayDebugUnitTest`):
- Robolectric + Roborazzi, running on a plain JVM. No emulator, no KVM, no `androidTest` device. This is the only rendering coverage in the repository.
- **Verify is the default**; re-record with `./gradlew :android:app:testPlayDebugUnitTest -Proborazzi.record=true`. Goldens are committed under `android/app/src/test/screenshots/`, so a missing golden fails the build.
- The Roborazzi Gradle plugin is deliberately NOT applied: 1.43.1 fails against AGP 9.3 with `Extension of type 'TestedExtension' does not exist`. Record/verify is driven by the `-Proborazzi.*` properties in `android/app/build.gradle.kts` instead.
- Screenshot tests must set `application = ScreenshotTestApplication::class`. Robolectric otherwise boots `CrispyApplication`, whose `onCreate` reaches an `AndroidKeyStore` that cannot exist on a JVM.
- Freeze the Compose clock (`mainClock.autoAdvance = false`) or animated content never matches.
- **Host prerequisite:** a *failing* screenshot needs a host font, because Roborazzi labels the diff with Java2D. On a host with no font stack the failure reports `Fontconfig head is null` instead of the real difference. It still fails the build, but the diff is unreadable. `android/app/src/test/fonts/` bundles Roboto and a generated fontconfig, which covers hosts that have libfontconfig but no fonts; a host with **no** `libfontconfig.so.1` at all (such as a bare container) needs `fontconfig` + a font package installed with root, since the JDK cannot load one.

Kotlin Multiplatform modules (in progress; see `check-local.sh`):
- `:android:platform-core`: platform-portability interfaces (`SecretStore`, `KeyValueStore`, `AppLogger`, `TimeSource`, `DistributionCapabilities`). `commonMain` must stay platform-free.
- `:android:core-domain`: KMP (Android + `desktop` JVM + `linuxX64` + `iosArm64` + `iosSimulatorArm64`). Its `commonMain` is free of `java.*`/`android.*`; `scripts/check_common_purity.py` enforces that with no allowlist. The `java.time` and `URLEncoder` call sites were replaced with portable equivalents pinned by unit tests against real JVM output, which is what unblocked declaring the Apple targets.
- `linuxX64` on both KMP modules is a **compile-only verification target**, never shipped and never run. It is the one Kotlin/Native target that builds on a Linux host, so `compileKotlinLinuxX64` in `check-local.sh` enforces the same "no JVM API" rule as the Apple targets in seconds instead of waiting for macOS CI. The import-based purity gate cannot see this class of bug: `"x".format(y)` is `kotlin.*`, so it passes the import scan and then fails only when an Apple target compiles. Prefer the native compile gate for anything touching `commonMain`.

GitHub Actions (`.github/workflows/`), all `workflow_dispatch`-only by deliberate choice:
- `android.yml` — verification for the app (both flavors) and the TV app: purity gate, fixtures, contract + domain + plugins tests, golden screenshots, lint, assemble, and the dex-level distribution assertion.
- `apple.yml` — the only place Apple can be verified: compiles the KMP Apple targets, runs their simulator tests, the Swift contract suite, and the iOS/tvOS Xcode gate.
- `android-release.yml` — signed release artefacts. Separate from `android.yml` because it alone needs the release signing secrets.
- `apple-release.yml` — unsigned sideloading IPA.
- Names are platform + intent, not Gradle build type. Do not reintroduce `debug`/`release` into workflow names; "debug CI" and "debug build" are different things.
- `verify_apk_distribution.py` reads the **dex** and is only valid on unminified builds, so it runs in `android.yml` (debug) and not in `android-release.yml`. Release asserts via `verifyDistributionExclusions`, which reads the dependency graph and is minification-proof.
- AGP's `com.android.kotlin.multiplatform.library` has **no** `productFlavors` at all (unlike `com.android.library`/`com.android.application`). Flavors live only in `:android:app`, `:android:network` and `:android:plugins`.
- Distribution is a permanent two-flavor axis: `store` (Play/App Store) and `sideload` (APK/IPA). Optional engines are excluded **structurally** — the torrent engine, the QuickJS plugin runtime and the YouTube extractor are separate modules that only `sideload` depends on. Never reintroduce a null-returning stub for something the store build should simply not contain. Two guards enforce this: `./gradlew :android:app:verifyDistributionExclusions` reads the resolved dependency graph, and `scripts/verify_apk_distribution.py` reads the built dex and asserts in both directions.
- `:app` stays a `com.android.application` with flavors. It is **not** becoming a flavor-less KMP library: the KMP plugin cannot carry flavors, and the flavor axis is a product requirement.
- Apple targets are declared but cannot compile on Linux. Never run aggregate tasks (`build`, `check`, `allTests`); they reach the Kotlin/Native targets and fail. Use `./check-local.sh` or targeted tasks.

Apple:
- `ios/ContractRunner`: SwiftPM contract runner (mirrors `android/core-domain` behavior)
- `ios/project.yml`: XcodeGen spec for placeholder iOS/tvOS apps (compile gate)

Contracts:
- `contracts/SPEC.md`: source of truth for heuristics + deterministic rules
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
- `./gradlew :android:contract-tests:test`
- `swift test --package-path ios/ContractRunner` (if Swift logic touched)
- Ensure `:android:tv` and tvOS placeholder builds still compile
