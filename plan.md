# Crispy Rewrite Plan (Android Kotlin + iOS Swift + Android TV + tvOS)

This repo (`/home/aayush/Downloads/crispy-rewrite`) is the rewrite workspace. The existing Expo app in `/home/aayush/Downloads/crispy-native/` remains the reference implementation and must not be modified.

## Goals

- Android: native Kotlin app.
- iOS: native Swift app.
- Android TV + tvOS: placeholder targets that compile/run early (shell only day 1).
- Feature parity across Kotlin + Swift enforced by tests (not shared UI code).
- No self-hosted backend: Supabase stays; third-party APIs are called from the client (Edge Functions optional if secrets must be hidden).
- Start with Android: ship a production-grade player + torrent engine integration first.
- Metadata migration: addon-first metadata is the primary source of truth; TMDB becomes an enhancer.

## Non-goals (early)

- Copying the Expo UI. The native apps should use platform conventions.
- Building a custom component library. Use official Google Material 3 components and patterns.
- Perfect local-storage key compatibility with Expo (breaking changes allowed). Cloud compatibility (Supabase) should be preserved unless we explicitly migrate it.

## Key Decisions (Confirmed)

- Supabase remains for auth/households/profiles/sync.
- Breaking changes are acceptable (we will version contracts and document migrations).
- Reuse the existing Kotlin playback/torrent code from the Expo module (`modules/crispy-native-core/`) if it integrates cleanly and can be made production-grade.
- Android UI: Jetpack Compose + Material 3 (Expressive). Use official components from https://m3.material.io/components/

## Parity Enforcement Strategy (Kotlin + Swift)

### Baseline: Contract fixtures + test runners (recommended)

Parity is enforced by a set of versioned, deterministic contracts:

- `contracts/fixtures/**/*.json`: inputs/expected outputs.
- `contracts/schemas/**/*.schema.json`: validate fixture shape.
- `contracts/SPEC.md`: human-readable rules.

Both apps implement the same rules and must pass the same fixtures:

- Android: JUnit tests load fixtures.
- iOS: XCTest tests load fixtures.

Why this works here:

- Kotlin and Swift cannot share UI code.
- Much of parity-critical behavior is pure logic (IDs, normalization, sync planning, state machines).
- We can use the existing TS packages (e.g., `@crispy-streaming/media-core`) as a reference to generate or validate fixtures later.

### Optional later: Shared executable core

If maintaining some logic twice becomes too costly, promote only pure logic into a shared core:

- Kotlin Multiplatform (KMP) is the most natural option now (Android is Kotlin). iOS consumes a generated framework.
- Do NOT attempt to share the player/torrent stack via KMP; that should remain platform-native.

Default plan: start with contract tests, revisit KMP after Android MVP stabilizes.

## Parity Contracts to Freeze First (Rewrite v1)

Breaking changes are allowed, so we define rewrite contracts as v1, while still preserving Supabase cloud semantics unless explicitly migrated.

### 1) Player state machine (highest priority)

Source reference: Expo reducer in `src/features/player/state/playerMachine.ts`.

Contract covers:

- Torrent boot -> localhost poll -> media load phases.
- Intent-driven play/pause with pending versioning.
- Codec error heuristic switching Exo -> VLC.

Test approach:

- Step fixtures: event sequences + timestamps -> expected state after each step.
- Deterministic time: inject a fake clock in tests.

### 2) Torrent pipeline contract (Android-only initially)

Contract covers:

- Session lifecycle: start, progress, readiness, stop.
- Localhost stream URL resolution rules.
- Error mapping (timeout, no peers, file selection invalid, etc.).

Test approach:

- Unit tests for the planner/state (no network).
- Manual/integration tests on device for actual torrent I/O.

### 3) Media IDs and normalization

Source reference: `@crispy-streaming/media-core` + Expo Trakt rules (`src/core/stores/traktStore.ts`).

Contract covers:

- Base/strict/episode ID forms.
- Canonical normalization rules and equality.

Test approach:

- Fixture sets: parse/normalize/serialize across many cases.

### 4) Metadata resolver: addon-first (new)

Reference behavior: `/Downloads/Crispy-webui`.

Contract covers:

- Canonical addon media identity and mapping to Trakt/TMDB.
- Merge/ranking rules (field precedence, de-dupe, stable ordering).
- TMDB enhancer rules (optional enrichment only; never primary identity).

Test approach:

- Recorded addon payload fixtures -> expected resolved canonical entity.
- Deterministic sorting and tie-breakers.

### 5) Sync planner + Supabase RPC payloads

Source reference: Expo `src/core/services/SyncService.tsx`.

Contract covers:

- Payload shapes for existing RPCs:
  - `get_household_addons`
  - `replace_household_addons`
  - `upsert_profile_data`
- Addon normalization/sorting fingerprinting.
- 2s debounced writes (simulated with a fake scheduler).
- Owner-only household writes.
- Conflict avoidance gates.

Test approach:

- Planner fixtures: given local/remote snapshots + ownership flags + time -> expected RPC calls and canonical JSON payloads.

### 6) Storage (rewrite v1)

Because breaking changes are allowed, we define a new logical storage contract that is cross-platform and testable, then implement it using native storage.

Contract covers:

- Scoping: per-account and per-profile namespaces.
- Schema versioning and migration policy.
- Canonical encoding rules (especially for sync-relevant objects).

Implementation guidance:

- Android:
  - Preferences/state: DataStore (Proto preferred for schema evolution).
  - Structured local data: Room.
  - Secrets: Android Keystore + encrypted storage wrapper.
- iOS:
  - Preferences/state: Codable persisted to file (or UserDefaults for trivial values).
  - Structured local data: SQLite/CoreData if needed.
  - Secrets: Keychain.

Test approach:

- Contract tests run against in-memory storage adapters validating scoping/versioning/encoding.

## Fixture Determinism Rules

Every fixture must include:

- `contract_version` (int)
- `suite`, `case_id`
- `now_ms` (epoch millis)
- optional `seed`

Rules:

- All domain logic must take an injected `Clock` (no direct system time in core-domain).
- Any randomness must use an injected deterministic RNG seeded from the fixture.
- Output ordering must be canonical (explicit sort keys defined in `contracts/SPEC.md`).
- JSON comparisons must use canonicalization rules (key ordering, null/empty normalization).

## Android Architecture (Kotlin)

### Modules (recommended)

- `android/app/`: Android phone/tablet app.
- `android/tv/`: Android TV placeholder (separate app module or product flavor).
- `android/core-domain/`: pure Kotlin rules (IDs, normalization, reducers, sync planner). No Android deps.
- `android/core-data/`: Supabase client, repositories, storage adapters.
- `android/player/`: orchestration, playback UI state, binding to native engines.
- `android/native-engine/`: extracted production playback+torrent implementation (from Expo module) with minimal changes.
- `android/contract-tests/`: loads `contracts/fixtures` and executes Kotlin parity tests.

### UI (Compose + M3)

- Use official Material 3 components (Expressive) instead of recreating Expo UI.
- Compose navigation: keep it idiomatic and simple; player is a dedicated destination.
- Player UI can be custom where necessary (surface + controls), but still follow M3 patterns for sheets/dialogs/menus.

## iOS Architecture (Swift)

- `ios/App/`: iOS app (SwiftUI recommended).
- `ios/tvOSApp/`: tvOS placeholder target.
- `ios/CoreDomain/`: pure Swift parity logic.
- `ios/CoreData/`: Supabase + storage.
- `ios/ContractTests/`: XCTest runner loading fixtures.

Start iOS once Android contracts stabilize (player/torrent first).

## TV Placeholder Targets (Compile/Run Early)

- Android TV:
  - Minimal launcher activity, focusable UI, single placeholder screen.
  - Must compile in CI from day 1 (even if feature flags disable most features).
- tvOS:
  - Minimal tvOS app target that compiles in CI; placeholder UI only.

## CI / GitHub Actions (Critical)

You cannot build locally, so CI must produce installable artifacts on every PR.

### Required checks (Android)

- Validate contract fixture JSON against schemas.
- `./gradlew test` (unit + contract tests).
- `./gradlew :android:app:assembleDebug`.
- `./gradlew :android:tv:assembleDebug`.
- Upload APK artifacts for download.

### Optional (release signing)

- Store keystore + passwords in GitHub Secrets.
- Build signed AAB/APK on tags or manual dispatch.

### iOS (later)

- Compile-only gate early: `xcodebuild` for iOS + tvOS simulators.
- Add XCTest contract suite once Swift domain exists.

## Phase Plan (Android First)

### Phase 0: Repo scaffolding + contracts + CI (must be first)

Deliverables:

- Create directory layout (`android/`, `ios/`, `contracts/`).
- Add contract harness + a small initial fixture set for:
  - `player_machine`
  - `media_ids`
  - `metadata_addon_primary` (seeded with a few web-ui derived cases)
- GitHub Actions workflow building `app-debug.apk` and uploading artifacts.
- Android TV placeholder module builds.

Acceptance:

- Every PR produces a downloadable Android APK artifact.

### Phase 1: Android Playback Lab (player + torrent engine vertical slice)

Goal:

- A minimal Android app that proves production-grade integration of the existing Kotlin player/torrent engine.

Hardcoded HTTP sample video:

- `https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4`

Torrent test input:

- Do NOT hardcode magnets in public source control.
- Provide a debug screen text field to paste a magnet at runtime.
- Optional convenience: allow magnet injection via CI secret/build config (still not committed).

Deliverables:

- Extract `modules/crispy-native-core/` Android code into `android/native-engine/`.
- Build `android/player/` facade API:
  - start/stop torrent
  - observe progress/status
  - resolve localhost stream URL
  - play URL with Exo primary + VLC fallback
- Compose UI: a single “Playback Lab” screen using M3 components.

Acceptance:

- CI APK installs and plays the HTTP sample.
- Torrent pipeline can reach “ready to stream” and hand off a localhost URL for playback.

### Phase 2: Player machine parity (core-domain + fixtures)

Deliverables:

- Kotlin port of the player reducer/state machine into `android/core-domain/`.
- Contract fixtures for the key transition sequences.
- Wire Playback Lab UI to intent-driven state machine (UI intent -> reducer -> side effects -> native engine).

Acceptance:

- `player_machine` fixtures pass.
- Engine switching heuristics behave as specified.

### Phase 3: Metadata addon-first (Android)

Deliverables:

- Define the canonical addon-based media identity format.
- Implement the resolver + merge rules in `android/core-domain/`.
- TMDB enhancer as an optional enrichment layer with deterministic merge behavior.
- Expand `metadata_addon_primary` fixtures derived from `/Downloads/Crispy-webui`.

Acceptance:

- Metadata fixtures pass; UI can display resolved metadata.

### Phase 4: Supabase auth + profiles + households (Android)

Deliverables:

- Supabase client integration.
- Auth lifecycle + secure session storage.
- Household membership + profile selection rules.

Acceptance:

- End-to-end login + profile selection works.

### Phase 5: Sync planner + cloud sync (Android)

Deliverables:

- Implement sync planner rules (debounce, ownership gates, conflict avoidance).
- Implement RPC calls and canonical payload generation.
- Add `sync_planner` fixtures.

Acceptance:

- Sync fixtures pass; household/profile data sync is stable.

### Phase 6: Expand Android app (native M3 UI)

Deliverables:

- Add navigation structure and screens using standard M3 components.
- Implement route guards (auth/profile gating) in an idiomatic Kotlin way.
- Add offline caching and performance tuning where needed.

Acceptance:

- Core product flows work on Android with native UI patterns.

### Phase 7: iOS + tvOS scaffolds + contracts

Deliverables:

- iOS + tvOS projects compile in CI.
- Swift contract runner loads the same fixtures.
- Implement the same parity-critical domains (IDs, metadata resolver, sync planner) before full UI.

Acceptance:

- Swift contract tests pass for shared suites.

### Phase 8: Android TV placeholder

Deliverables:

- TV shell app compiles and runs; later shares core-domain/data as appropriate.

## Drift Prevention Rules

- Any parity-critical change must:
  1) update `contracts/SPEC.md` and fixtures
  2) pass Kotlin contract tests
  3) pass Swift contract tests once iOS implementation exists

## Immediate Next Step

- Create `contracts/` + Android Gradle skeleton + GitHub Actions that produces an APK artifact.
