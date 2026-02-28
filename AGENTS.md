# Agent Guide (Build, Test, Style)

Android + iOS rewrite workspace with contract-driven parity. Keep Android (`android/core-domain`) and Swift (`ios/ContractRunner`) aligned with `contracts/SPEC.md`.

Repo agent rules:
- No `.cursor/rules/` or `.cursorrules` found.
- No `.github/copilot-instructions.md` found.

## Toolchain (match CI)

- JDK 21; Android SDK `platforms;android-35` + `build-tools;35.0.0`
- Gradle 9.3.1 via `gradle` (no wrapper checked in)
- Python 3.12 + `jsonschema==4.23.0`
- Xcode + `xcodegen`; Swift tools 5.9

## Commands

Contracts (fast):
```sh
python3 -m pip install jsonschema==4.23.0
python3 scripts/validate_contracts.py
gradle :android:contract-tests:test
swift test --package-path ios/ContractRunner
```

Other useful tasks:
```sh
# JVM unit tests (if present)
gradle :android:core-domain:test
gradle :android:app:testDebugUnitTest

# Clean
gradle clean
```

Single test (important):
```sh
# Kotlin/JUnit5 (contract tests)
gradle :android:contract-tests:test --tests com.crispy.tv.contracts.PlayerMachineContractTest
gradle :android:contract-tests:test --tests com.crispy.tv.contracts.PlayerMachineContractTest.someTestName

# Kotlin/JUnit (unit tests in other modules)
gradle :android:core-domain:test --tests com.crispy.tv.domain.SomeUnitTest

# Android instrumentation (connected device/emulator)
gradle :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.crispy.tv.PlaybackLabSmokeTest

# SwiftPM
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests.testSomeCaseName
```

Android builds/lint:
```sh
gradle :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:tv:assembleDebug
gradle :android:app:assembleRelease :android:tv:assembleRelease
gradle :android:app:lintDebug
gradle :android:tv:lintDebug
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

Apple:
- `ios/ContractRunner`: SwiftPM contract runner (mirrors `android/core-domain` behavior)
- `ios/project.yml`: XcodeGen spec for placeholder iOS/tvOS apps (compile gate)

Contracts:
- `contracts/SPEC.md`: source of truth for heuristics + deterministic rules
- `contracts/fixtures/` + `contracts/schemas/`: versioned JSON fixtures + schemas

## Configuration / Secrets

- Android app reads Gradle properties and injects them into `BuildConfig`; in CI these come from `ORG_GRADLE_PROJECT_*`.
- Do not commit secrets; use `~/.gradle/gradle.properties` for `TMDB_API_KEY`, Trakt/Simkl ids+secrets+redirect URIs, `SUPABASE_URL`, `SUPABASE_ANON_KEY`.
- Signing: release uses `RELEASE_KEYSTORE_*` if present; otherwise debug signing. Debug can be overridden via `DEBUG_KEYSTORE_*`.

## Code Style

General:
- Contracts drive behavior. If behavior changes, update `contracts/SPEC.md`, fixtures/schemas, Kotlin contract tests, and Swift ContractRunner.
- Determinism: pass `nowMs`/clock; inject seeded RNG; keep output ordering canonical.
- Gradle repos: `settings.gradle.kts` enforces `repositoriesMode = FAIL_ON_PROJECT_REPOS`; do not add repos in module `build.gradle.kts`.

Contracts:
- Fixtures include `contract_version`, `suite`, `case_id` (and `now_ms` when specified).
- Suites currently covered include: `player_machine`, `continue_watching`, `trakt_scrobble_policy`, `media_ids`, `id_prefixes`, `catalog_url_building`, `search_ranking_and_dedup`, `sync_planner`, `storage_v1`.
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
- When enriching/merging metadata, keep precedence stable (TMDB-first; fill missing only).

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
- `gradle :android:contract-tests:test`
- `swift test --package-path ios/ContractRunner` (if Swift logic touched)
- Ensure `:android:tv` and tvOS placeholder builds still compile
