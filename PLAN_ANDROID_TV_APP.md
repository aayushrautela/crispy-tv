# Plan: Android TV app (`:android:tv`) — full build-out

## Status: Phase 0 ✅ + Phase 1 ✅ + Phase 2 in progress — Phases 3+ NOT started

## Locked decisions
- **applicationId**: `com.crispy.tv` on both phone and TV apps (same Play listing,
  multiple-APK delivery; phone gets mobile APKs, TV gets leanback APK).
- **Backend client**: extracted from `:android:app` into shared `:android:backend`
  library (`CrispyBackendClient`, account/metadata/watch APIs, parsers, JSON extensions,
  `ai/AiInsightsModels`). URL parameterized via constructor (`baseUrl`) instead of
  BuildConfig import; `@Immutable` dropped (strong-skipping compiler infers stability;
  avoids pulling Compose runtime into the lib). Session/profile glue
  (`BackendServicesProvider`, `BackendContextResolver*`, accounts/, AI repository) stays
  in the app modules.
- **Torrent playback**: TV depends on `:android:native-engine` like phone (ABI splits +
  jniLibs packaging mirrored).
- **Auth**: TV is sign-in only — no signup UI/flow in any phase.

## Goal
Turn the placeholder `:android:tv` module into a full Android TV / Google TV app,
**without touching `:android:app`** (phone). Shared logic stays in library modules;
the TV module contains only UI + navigation + TV-specific integrations.

## Research summary (2026-08)

### Google TV home-screen rails (Continue Watching / Up Next)
Two mechanisms exist for surfacing our rails on the Google TV / Android TV launcher:

| | **TvProvider Watch Next** (chosen for v1) | Engage SDK |
|---|---|---|
| Artifact | `androidx.tvprovider:tvprovider:1.0.0` + `WRITE_EPG_DATA` | `com.google.android.engage:engage-tv:1.0.6` + `WRITE_EPG_DATA` |
| Row shown | "Play Next" / "Continue Watching" row of system launcher | Same row, server-side sorted by Google |
| Reach | All Android TV 8+ devices, incl. sideloaded & Fire TV | Google-certified surfaces only; service may be absent when sideloaded |
| Enrollment | None | Play listing + Google program enrollment; verification-app testing |
| Limits | Our own row management | Max 5 continuation entities per partner; publish is a full upsert |

Decision: **TvProvider Watch Next first** (works everywhere, zero certification),
Engage SDK later as an optional Phase 6b behind `AppEngagePublishClient.isServiceAvailable()`.
Reference implementation studied: NuvioTV `core/recommendations/TvRecommendationManager.kt`
— gates on `FEATURE_LEANBACK`, upserts `WATCH_NEXT_TYPE_CONTINUE`/`NEXT`, fingerprints each
program (title/poster/season/episode/duration, position ±2s) and only writes to the provider on
real change so the launcher isn't woken needlessly; deletes stale programs.

### Compose for TV (UI toolkit)
- Leanback Views are deprecated → use `androidx.tv:tv-material:1.1.0`.
- Do NOT mix mobile `material3` theme with TV `tv-material` theme in one UI.
- `tv-foundation` lazy layouts deprecated → standard `LazyRow`/`LazyColumn`
  (Compose Foundation 1.7+) handle D-pad focus scrolling natively.
- NuvioTV UI patterns worth copying: left sidebar navigation, hero backdrop/carousel +
  catalog rails, continue-watching rail with progress bars + watched markers, focus-ring /
  motion / spacing token theming, D-pad utilities (key throttle, fast scroll, focus restore
  after back-navigation), single-activity Compose navigation.

### Card style decision (user)
Cards reuse the **phone app's landscape card look** (`CardStyle.LandscapeAspectRatio`,
16:9 backdrop-style tiles) — no portrait poster variant needed on TV.

### Play compliance notes (for eventual distribution)
AAB mandatory; minSdk ≤31 recommended (TV-PS); touchscreen not-required manifest entry
required for Play visibility; D-pad navigability (TV-DP); video must pause when app
backgrounds (TV-NP); 64-bit + 16 KB page size required from Aug 2026 (TV-G6).

## What already exists (do NOT rebuild)
- `:android:tv` module registered in `settings.gradle.kts`; placeholder `TvMainActivity.kt`;
  manifest has LEANBACK_LAUNCHER + banner (needs touchscreen entry + `leanback required=true`).
- Domain rules in `:android:core-domain` — player machine, continue-watching, sync planner
  (contract-tested against `contracts/fixtures` via Kotlin + Swift runners).
- `:android:player`, `:android:network`, `:android:watchhistory` libraries shared with phone.
- Phone app home rails to mirror: Continue Watching, This Week (calendar), Up Next
  (see `PLAN_UP_NEXT_CALENDAR.md` — `episodic-follow` backend endpoint), catalog rails.
- Phone landscape card style reference: `HomeCatalogComponents.kt` (`CardStyle.LandscapeAspectRatio`).
- Backend endpoints already used by phone (continue-watching, calendar/this-week,
  episodic-follow/up-next, catalogs) — TV app calls the same ones via `:android:network`.

## Module layout (target)
```
:android:tv                     (app; UI only — screens, nav, theme, D-pad utils)
 ├─ :android:core-domain        (pure rules; unchanged)
 ├─ :android:network            (backend/catalog clients; unchanged)
 ├─ :android:watchhistory       (unchanged)
 └─ :android:player             (playback engine; unchanged)

:android:tvservices             (new small android lib, optional in Phase 6)
 └─ WatchNext publisher (TvProvider mapping, fingerprint diffing)
    (keeps tvprovider/EPG deps out of the UI module; testable in isolation)
```

## Phases

### Phase 0 — Module foundation  ✅ done
- [x] `android/tv/build.gradle.kts`: compose plugin + BOM 2026.08.00, `tv-material:1.1.0`,
      activity/navigation-compose, lifecycle, coil3, coroutines; BuildConfig fields mirroring
      phone (SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, CRISPY_BACKEND_URL, INTRODB_API_URL,
      METADATA_ADDON_URLS); desugaring; ABI splits + jniLibs packaging; release minify+shrink;
      signing props pattern; testInstrumentationRunner.
- [x] `applicationId = "com.crispy.tv"` (identical to phone).
- [x] New shared module `:android:backend` (extraction above); registered in
      `settings.gradle.kts`; phone app depends on it.
- [x] Manifest: INTERNET permission; `touchscreen required=false`;
      `leanback required=true`; LEANBACK_LAUNCHER + banner retained.
- [x] CI debug workflow: added `:android:app:lintDebug :android:tv:lintDebug` gate.
- [x] Verified locally: `gradle :android:app:assembleDebug :android:tv:assembleDebug` compiles;
      CI lint initially flagged missing `POST_NOTIFICATIONS` (fixed in `14d1a386`).

### Phase 1 — App shell  ✅ done (commit `23b7c7c7` + import fix)
- [ ] Single-activity `TvMainActivity`: Compose `NavHost`, back-stack handling that restores
      focus (NuvioTV `FocusRestoreUtils` pattern).
- [ ] Left sidebar navigation (Home / Search / Library / Settings) with D-pad focus handling;
      content area swaps screens.
- [ ] TV theme: port phone palette into tv-material `MaterialTheme`; spacing/shape/focus-ring
      tokens; scale type for 10-foot viewing.
- [ ] Shared components: landscape `ContentCard` (mirrors phone `CardStyle`), rail section
      container (`LazyRow` + focus bring-into-view), progress bar overlay, watched marker,
      shimmer placeholders.

### Phase 2 — Home screen (in progress)
- [x] Auth core moved to `:android:backend` (`Session`, `SecureTokenStore`, `ActiveProfileStore`,
      `SupabaseAccountClient`, `BackendContextResolver`) — packages unchanged, zero phone churn.
- [x] TV session gate: `TvServices` provider, `TvSessionViewModel`
      (Loading → SignedOut → NeedsProfile → SignedIn), minimal sign-in screen
      (email+password only — no signup per decision) + profile picker.
- [x] `HomeViewModel`: parallel load of Continue Watching / Up Next / This Week /
      profile-home sections via shared backend client; mapped to landscape cards with
      progress fractions + S/E subtitles.
- [ ] CW item dismiss (options overlay), hero/backdrop area for focused rail item,
      catalog pagination.

### Phase 3 — Detail & episodes
- [ ] Detail screen: hero backdrop, metadata, Resume/Play, watchlist toggle, tracked-state.
- [ ] Season/episode browser (side panel or full-screen list) with watched ticks + progress.
- [ ] Cast + more-like-this rails reusing network clients.

### Phase 4 — Player
- [ ] Reuse `:android:player` end-to-end; TV control surface (D-pad seek ±10s/±30s,
      play/pause, audio/subtitle tracks overlay).
- [ ] Overlays: stream-sources side panel, subtitle settings, skip-intro, next-episode prompt.
- [ ] `MediaSession` wiring; pause video on `onStop` (TV-NP); frame-rate API optional.

### Phase 5 — Search / Library / Settings
- [ ] Search: on-screen keyboard + voice (`ACTION_RECOGNIZE_SPEECH`) intent; results grid.
- [ ] Library: watchlist/history grids with same landscape cards.
- [ ] Settings: accounts/profiles (Trakt/Simkl flows reused from network layer), playback prefs,
      about. Keep scope minimal for v1.

### Phase 6 — Launcher rails (Google TV / Android TV home)
- [ ] New `:android:tvservices` lib: `WatchNextProgramMapper` (pure mapping:
      canonical CW item → `WatchNextProgram` builder fields; CONTINUE vs NEXT type selection),
      `WatchNextPublisher` (upsert/delete with fingerprint diff, mutex, IO dispatcher).
- [ ] Pure mapper lives beside domain rules → unit-testable without Android
      (contract-test friendly; fixtures only if behavior becomes contract-worthy).
- [ ] Publish triggers: after progress save, after item removal, on app exit (`onStop`),
      on profile switch.
- [ ] Deep-link intent URIs (`crispy://watch/<itemId>`) handled by TV activity → resume playback.
- [ ] Phase 6b (optional, Play-listed future): Engage SDK continuation cluster behind
      `isServiceAvailable()`, WorkManager scheduling, verification-app testing.

### Phase 7 — QA & hardening
- [ ] Instrumented tests on TV emulator (leanback image): sidebar nav, rail focus traversal,
      deep link launch, CW removal syncs launcher row.
- [ ] `lintDebug` clean; baseline profile (TV-BP) deferred post-v1.
- [ ] Manual pass on real device: D-pad-only completion of browse → play → resume flow.

## Contract parity guardrails
- No new heuristics invented in TV UI: continue-watching gating/ordering stays in
  `core-domain` (fixtures: `continue_watching`, `player_progress`). Any behavior change goes
  through SPEC + fixtures + Kotlin/Swift runners per AGENTS.md.
- WatchNext mapping is presentation-layer; keep it deterministic (no clock reads — take
  timestamps from progress records).

## Out of scope (v1)
- Engage SDK enrollment/recommendations clusters; Cast Connect; Engage cross-device sync;
  dual layout modes (single modern layout only); portrait posters.
