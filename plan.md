# Android TV app — home, stream picker, player (`crispy-rewrite`)

Status: **Implemented and built by CI.** The landings below have been written and compiled by the GitHub workflow
(there is no Gradle on the machine this was written on, so CI is the compiler): Android run `39732786947` and
Android Release run `39733319923`, both green on 2026-10-22. What CI cannot do is the **manual D-pad pass** — home →
card → picker → player → subtitles/audio/speed → change source → back — which still has to be done on a real TV.
Date: 2026-10-21 (updated 2026-10-22). Repo: `~/Downloads/crispy-tv`, branch `master`.

## What landed (2026-10-21)

**L1 — home.** "Play" from home no longer opens a dead-end player: it opens the source picker
(`TvApp.kt`), which works out the lookup from the item id alone (`TvSourcesViewModel.resolveLookupTarget`).

**L1 (continued) — play from a rail card, and the episode it means.** OK on a home card now **plays** it, and the
details are one long-press away (the card's menu offers play / open details / remove; the hero keeps both buttons).
What "play" means is a value, `TvHomePlayTarget`: a Continue Watching row names the episode the viewer was watching
(its imdb id becomes the episode lookup id `tt123:2:5`), a calendar row names the episode that aired (no imdb id on
that model, so the picker resolves the episode's id from the series' episode list, `resolveEpisodeLookupId`), and the
season and episode travel beside the lookup id in `TvPlaybackSelection` because a lookup id cannot always be built.
Without this the picker asked for the series' *next* episode and the viewer's episode was missed. Pinned by
`TvHomePlayTargetTest`.

**L2 — picker.** The picker carries the whole stream, not a URL: `SourceRowUi` holds the `AddonStream`, so
the request headers and the sidecar subtitles reach the player (`toDirectPlaybackSource`). Every row is a
focus target that says so (`TvSourcesScreen`). The order is a rule, not an accident of arrival
(`TvSourceOrdering`, pinned by `TvSourceOrderingTest`). The pick travels in process, named by an opaque id
(`TvPlaybackSelectionStore`), because a route cannot carry headers or subtitles.

**L3 — player.** The raw `ExoPlayer` is gone: the TV plays through the project's own engine,
`NativePlaybackController`, behind the `PlaybackSessionController` port. The player resumes from the
**shared** watch history, writes progress through the same service the phone uses, offers subtitles / audio /
speed / mute / change source in a D-pad sheet, and has an error card with retry. The chrome carries the
clock and a progress bar.

**Sharing.** The one rule that turns an `AddonStream` into a playable source now lives in `:addons`
`commonMain` (`resolvePlaybackSource`: a stream with a plain URL is played directly, a stream that only has a
magnet is handed to the torrent engine), and the phone calls it too (`PlayerSessionSupport`). The episode
lookup id can be read back (`parseAddonEpisodeLookupId`), so the TV can file progress under the same key
the phone uses. `:tv` still does not depend on `:app`.

**Torrents.** The TV now **ships the torrent engine**: `:android:torrent-engine` is a service and a resolver,
not a UI, and the TV already took the YouTube extractor unconditionally because it is a sideload-only surface.
A magnet row in the picker is offered and resolved at play time (`NativeTorrentResolver`), so the player shows
"preparing" and reports a failure the way every other failure is reported. The engine's native binary is
fetched by `.github/scripts/fetch-torrserver-binaries.sh` into the gitignored `android/native-engine/src/main/jniLibs/`.
A Play TV build would need the phone's `distribution` flavour axis — that is release work, not playback work.

**L0 — docs.** `android/tv/README.md` states the focus contract, the identity rule and the state inventory.

**L5 (part) — release.** The TV's `applicationId` is `com.crispy.tv.tv`, so it no longer collides with the
phone's store build. `:android:tv:test` now runs in CI and in `check-local.sh` — the module had a test suite
that nothing ran.

**Not landed:** a `MediaSession` for the TV player (it needs a manifest-registered `MediaSessionService`);
next-episode navigation; the Play listing, the 320×180 banner and the 16 KB page-size work, and the
`distribution` flavour axis a Play TV build would need — all release tasks rather than code.

## Goal

Make the TV app a real 10-foot app for three surfaces, in this order: **home**, **stream picker**, **player**.

"Basic" here means a specific, checkable bar, not "small":

- Every path is completable with a **D-pad only** — no touch, no gesture, no mouse.
- **No dead ends.** Every Play button reaches a playing video or an honest failure. Today the home rail's
  Play button lands on a player that says *"No stream source yet — source selection arrives with the addon
  integration."* and stops.
- The three surfaces share the **same data and session layer** the phone app uses, so a fix on either
  surface is not a second implementation.
- The player **resumes** where the user left off, **carries the stream's auth headers**, and can switch
  source and subtitle language without leaving playback.

Out of scope for this plan: Search / Library / Settings (still `PlaceholderScreens.kt` "Phase 5"),
PiP, cast, downloads, live TV/EPG, and the tvOS/Apple TV placeholder builds. (Torrent playback was listed
here too; it is **in** scope now — the TV ships the engine.)

## What already exists (measured, not assumed)

### The module

`:android:tv` is a **plain `com.android.application`** (not KMP), so it has no `commonMain` and cannot
consume a KMP `commonMain` directly — but it *can* consume the KMP modules' Android variants, and it
already does: `core-domain`, `home`, `player`, `native-engine`, `network`, `youtube-extractor`,
`watchhistory`, `platform-android`, `backend`, `addons`, `sharedUI`, `ui-assets`.

| fact | value | where |
|---|---|---|
| namespace / applicationId | `com.crispy.tv.tv` / **`com.crispy.tv`** — identical to the phone app's store build | `android/tv/build.gradle.kts:46,53` |
| sdk | min 26, target 36, compile 37 | `android/tv/build.gradle.kts:47-51` |
| version | `0.1.0`, versionCode `1` | `android/tv/build.gradle.kts:56-57` |
| abi splits | `armeabi-v7a`, `arm64-v8a`, `x86_64` + universal | `android/tv/build.gradle.kts:64-69` |
| manifest | `android.software.leanback` required, touchscreen **not** required, `LEANBACK_LAUNCHER` + banner | `android/tv/src/main/AndroidManifest.xml` |
| ui | Compose BOM + `androidx.tv.material` 1.1.0, media3 1.11.0 | `gradle/libs.versions.toml:130,148` |
| tests | JUnit only, one suite (`CrispyTvDarkColorsMappingTest`), no Robolectric | `android/tv/build.gradle.kts:200-207` |

### The three surfaces that exist today

**Home — the data layer is already shared, the UI is TV-local.**

`TvHomeViewModel` (340 lines) drives `:home`'s shared `HomeRefreshCoordinator` → `CachingHomeCatalogService`
→ `DiskHomeCatalogSnapshotCache`, plus `CalendarService`, `UpNextService`, `buildHomeLayoutState`, and
`HomeUiState` — **the same commonMain home model the phone uses**. `HomeScreen.kt` (625 lines) +
`TvHomeComponents.kt` (388) + `SidebarNavigation.kt` (351) are TV-local Compose over that state. Hero,
wide rails (continue watching / up next / this week / catalog sections), rail headers, skeletons, a
remove-item dialog and a hero preview are implemented. `errorEvents` is collected at `HomeScreen.kt:110`.

**Stream picker — real data, thin UI.**

`TvSourcesViewModel` (109 lines) calls `:addons`' `StreamResolver` (`CachingStreamResolver` →
`AddonStreamsService` → `metadataAddonRegistry`) and maps `AddonStream` → `SourceRowUi`. The resolver
port is shared with the phone. `TvSourcesScreen.kt` (183 lines) renders a `LazyColumn` of rows.

`AddonStream` carries far more than the TV uses today: `requestHeaders`, `subtitles`
(`StreamSubtitle(url, lang, name)`), `behaviorHints` (`bingeGroup`, `videoSize`, `filename`,
`proxyRequestHeaders`), `clientResolve`, `infoHash`. **TV drops all of it on the floor** — the TV
`SourceRowUi` keeps only url/title/description/cached/isTorrent.

**Player — a bare ExoPlayer, not the project's engine.**

`TvPlayerViewModel` (162 lines) builds a raw `ExoPlayer` inside `:tv` and plays a URL handed in through
the navigation route. `TvPlayerScreen.kt` (175 lines) embeds a Media3 `PlayerView` with
`useController = false` and draws its own chrome (title, S/E label, error card), with D-pad handling for
center/left/right/up/down and a 4-second chrome auto-hide. It posts `playback_progress` /
`playback_progress_snapshot` / `playback_completed` events to the backend.

The project already has the real engine: `:native-engine`'s `NativePlaybackController` (Media3 + libass
+ sidecar subtitles + custom HTTP headers) behind the **`PlaybackSessionController` port, which lives in
`:core-domain` commonMain** (`android/core-domain/src/commonMain/kotlin/com/crispy/tv/nativeengine/playback/PlaybackSessionController.kt`).
`:tv` depends on `:native-engine` **today** and does not use it.

### Also present (not part of this plan, but they constrain it)

`DetailScreen.kt` (1,061 lines) is a finished 10-foot detail screen: seasons, episodes, watchlist,
watched toggles, ratings pills, AI-insights story, hero trailer. `SignInScreen` supports **device login**
(`DeviceLoginClient`). The intro splash is `CrispyIntroSplash` from `:sharedUI`.

### CI / distribution today

`android.yml` runs `:android:tv:lintDebug` and `:android:tv:assembleDebug`; `android-release.yml` runs
`:android:tv:assembleRelease` and uploads the `tv-release` APK artifact. **No unit-test task for `:tv`
is wired into CI.** No Play listing, no GitHub release — the TV app is a sideload artifact only.

## The gaps, in order of how much they hurt

### Home

1. **Play from home is a dead end.** `TvApp.kt:145` routes `onPlayItem` to `play/{itemId}` with no
   stream; `HomeScreen.kt:177` (hero Play button) does the same. The player then cannot play and says so.
   Home Play must route to the **stream picker**, exactly like the detail screen's Play does
   (`TvApp.kt:213-220`).
2. **Continue Watching does not resume.** Nothing reads the saved position, so a "continue watching" card
   restarts from zero. `WatchHistoryService` (`:player`, commonMain) already has
   `getLocalWatchProgress(identity)` and `onPlaybackStarted/Progress/Stopped`.
3. **Focus contract is implicit.** `LocalTvContentFocusRequester` and `tvRailBringIntoViewSpec` exist,
   but there is no written rule for initial focus, focus restore after back, or focus return from the
   sidebar. A 10-foot app's whole usability is this contract, and it should be a document, not folklore.
4. **Rail states are thin** — loading skeletons exist, but there is no per-rail empty state, no
   "reconnect" affordance, and `errorEvents` shows a message without naming the rail that failed.

### Stream picker

5. **Rows are not TV rows.** `Modifier.clickable` on a `Row` gets D-pad focus by accident, not by design:
   no scale/border on focus, no focus-first semantics, no group headers, no per-provider status
   (pending / returned N streams / failed), no sorting, no cached/quality/subtitle labelling.
6. **The picked stream is smuggled through a nav route.** `TvApp.kt:169` navigates to
   `play/{itemId}?streamUrl=<url-encoded url>`. A media URL inside a route argument is fragile (encoding,
   length, and it lands in the nav back stack), and it is the only channel the player has.
7. **The picker is unreachable from the player.** The phone app can open the stream sheet mid-playback
   (`PlayerSessionViewModel.showStreams()`); TV cannot switch source without leaving the video.
8. **Subtitles and headers are dropped** — `AddonStream.subtitles` and `AddonStream.requestHeaders` never
   reach the player, so a stream that needs an auth header will not play, and there is no way to pick a
   subtitle language.

### Player

9. **Not the project's engine.** Raw ExoPlayer means: no `requestHeaders` (backend/signed URLs that need
   them will 401), no sidecar or addon subtitles, no libass rendering, no track selection, no resize modes.
10. **No resume.** The player reports progress to the backend but never seeks back to it.
11. **No scrubbing.** Chrome shows a title and an episode label; there is no position, no duration, no
    D-pad-friendly progress bar, no seek-to-time.
12. **No track/language controls**, no playback speed, no "next episode", no buffering state, no error
    taxonomy (a single text error), no retry.
13. **No media session** — Android TV's system "now playing" card and the remote's transport controls are
    a platform affordance TV users expect to work.

### Release-readiness (not UI, but it gates shipping this app anywhere)

14. **applicationId collides with the phone app** (`com.crispy.tv` for both) — the two APKs cannot coexist
    on a device and cannot both be published. The TV app should be `com.crispy.tv.tv`.
15. **Play's TV requirements**: 64-bit + **16 KB memory page size** support is required for TV as of
    1 Aug 2026; the banner must be 320×180; leanback and no-touchscreen are already declared. Verify the
    shipped banner asset's dimensions and the native libs' page-size alignment.
16. `:tv` has **no unit tests in CI** and only one suite, while the module holds real logic (source
    ordering, resume decisions, focus order).

## The decision that shapes everything: where does the shared logic live?

`:tv` is plain Android. `:app` is a KMP library (`com.android.kotlin.multiplatform.library`) that
**does publish an Android variant** — `:androidApp` consumes it today. So `:tv` *could* depend on
`:android:app` and reuse the phone's `PlayerSessionViewModel` (1,491 lines, `androidMain`) and
`StreamSelectorContent` (487 lines, `commonMain`).

**Option A — depend on `:app`.** Cheapest in code. But it drags the whole phone app graph (navigation,
settings, the 40-member `AppNavHostDependencies`, touch-first Compose components) into the TV APK, and
one giant session view model would then have to serve a touch surface and a D-pad surface at once. Every
TV-specific behaviour fights a phone assumption.

**Option B — keep `:tv` self-contained, share through the existing ports. (recommended)**
- The engine is already behind a portable port: `PlaybackSessionController` in `:core-domain` commonMain,
  implemented by `NativePlaybackController` in `:native-engine` (a plain Android library `:tv` can consume).
  **Use it from `:tv` and delete TV's raw ExoPlayer.**
- The data layer is already shared: `:home`'s home models, `:addons`' `StreamResolver`, `:player`'s
  `WatchHistoryService`. **Use them as-is**; do not fork them.
- The decisions that both surfaces should agree on (resume position, progress reporting, preferred
  audio/subtitle language, which stream to auto-pick) are the portable half of the phone's
  `PlayerSessionViewModel`. Lift *those* into `:player` commonMain and let both surfaces render their own UI.
- The UI stays TV-local: hero, rails, source rows, player chrome are all 10-foot components and do not
  belong in the phone's touch tree.

**Recommendation: Option B, in three steps** — (1) point TV at the existing engine port, (2) lift the
shared session decisions into `:player` only where a second caller appears, (3) never make `:tv` depend on
`:app`. If we later find the phone and TV session VMs are 90% identical, the answer is a **new KMP module
holding the session** (`player-session`), consumed by both — not a dependency from `:tv` onto `:app`.

## Landings

Each landing is independently shippable and leaves `:tv` building. Nothing here is written yet.

### L0 — Ground rules (docs only, no code)

- Write the **focus contract** as a document: initial focus on the hero; up/down moves between rails;
  left/right inside a rail; Back from detail restores the focused card; the sidebar takes focus while
  open and hands it back on close; nothing traps focus; a rail that is still loading is skipped, not
  landed on.
- Write the **TV identity decision** (applicationId `com.crispy.tv.tv`, versionCode scheme, banner size)
  and do it *before* any Play submission — changing applicationId breaks sideload upgrades.
- Inventory the states each of the three surfaces must handle: loading, empty, error, offline, signed-out,
  no-providers, no-playable-stream, playback-failed.

**Verify:** none (documents). **Gate:** the focus contract is reviewed before L1 starts.

### L1 — Home: a real 10-foot home

- Route every Play affordance to the **stream picker**; never to a player with no stream.
- Continue Watching **resumes** at the saved position (read via `WatchHistoryService.getLocalWatchProgress`).
- Implement the focus contract from L0 (initial focus, focus restore, sidebar focus hand-back).
- Per-rail empty and error states; a reconnect affordance for a failed rail.
- Keep `:home`'s `HomeUiState` as the single source of truth — no TV-local copy of the home model.

**Files:** `android/tv/.../ui/screens/HomeScreen.kt`, `ui/components/TvHomeComponents.kt`,
`ui/components/SidebarNavigation.kt`, `home/TvHomeViewModel.kt`, `ui/TvApp.kt` (routes).
**Verify:** `:android:tv:assembleDebug` + `:android:tv:lintDebug`; a manual D-pad pass over home →
detail → back → home, on a TV device or an emulator with `adb shell input keyevent DPAD_*`.

### L2 — Stream picker: D-pad first, and it stops dropping data

- Rows become **TV list rows**: focusable, scaled/bordered on focus, one provider group per section with
  a header, per-provider status line (pending / N streams / failed), and a deterministic order
  (recommended → cached → quality).
- **Stop dropping `AddonStream` data**: carry `requestHeaders`, `subtitles` (language + name), and
  `behaviorHints` (size, filename, binge group) into the row model and show the useful ones.
- **Hand the picked stream over in-process**, not through a route argument (a small in-memory holder
  keyed by item id, or a shared session object). The route keeps only the item id.
- Torrent rows play: the TV takes `:android:torrent-engine` (a service and a resolver, no UI) the way it
  already takes the YouTube extractor, and the magnet is resolved at play time through the shared
  `resolvePlaybackSource` rule.
- Empty state per case: no providers, providers returned nothing, providers returned only torrents.

**Files:** `ui/sources/TvSourcesScreen.kt`, `sources/TvSourcesViewModel.kt`, `ui/TvApp.kt` (routes).
**Verify:** as L1, plus: a source that requires a header plays (see L3), and a source with subtitles
offers them.

### L3 — Player: the project's engine, resume, tracks, scrubbing

- Replace the raw ExoPlayer with **`NativePlaybackController` behind `PlaybackSessionController`**
  (`:core-domain` commonMain port, `:native-engine` implementation, already a `:tv` dependency). This
  buys request headers, sidecar/addon subtitles, libass, track selection and resize modes in one landing.
- **Resume**: on prepare, read the saved position and seek; report progress through the same
  `WatchHistoryService` calls the phone uses (`onPlaybackStarted/Progress/Stopped`).
- **Chrome, D-pad shaped**: position + duration, a progress bar that seeks on left/right (fine steps,
  with a held-key fast-seek), play/pause on center, a subtitle-language sheet, an audio-language sheet,
  playback speed, "sources" (re-open the picker in place), "next episode" where the detail screen knows it.
- **Error taxonomy**: network, unauthorized (missing header), codec/unsupported, not found — each with a
  **Retry** that re-prepares rather than a dead message.
- **Lifecycle**: pause on `ON_PAUSE`, release on dispose, keep the position on Back, and treat Back from
  the player as "stop and return" rather than "silently lose position".
- **Media session**: publish a `MediaSession` so the system now-playing card and the remote's transport
  keys work.

**Files:** `player/TvPlayerViewModel.kt` (rewrite), `ui/screens/player/TvPlayerScreen.kt`,
`di/TvServices.kt` (engine wiring), plus a new TV-local chrome/sheet components file.
**Verify:** as L1, plus: play → Back → play again resumes at the same second; a header-protected stream
plays; a subtitle stream shows and can switch language; a broken URL shows a retry that works.

### L4 — Shared session decisions (only if a second caller appears)

Lift the portable decisions out of `:app`'s `PlayerSessionViewModel` into `:player` commonMain —
resume position, progress reporting, preferred audio/subtitle language, auto-pick rule — and let both
surfaces render their own UI. **Measure first:** list the decisions, mark each portable / Android-pinned,
and only move the portable ones. A decision that only the phone makes does not move.

**Verify:** `:android:app:testAndroidHostTest` and `:android:app:desktopTest` (the phone must not
regress), `:android:core-domain:desktopTest :android:core-domain:testAndroidHostTest`, and the TV
manual pass again.

### L5 — Release readiness for TV

- applicationId `com.crispy.tv.tv`; versionCode/versionName scheme for TV; release signing already wired.
- Verify the **320×180 banner** asset and the `tv_banner` drawable; verify **16 KB page size** support
  for any native libs in the TV APK (required for TV on Play since 1 Aug 2026) and 64-bit support.
- Play TV listing: screenshots, descriptions, category, and the D-pad/leanback declaration already in
  the manifest.
- Decide whether TV ever ships to Play at all, or stays a sideload artifact — that decision changes how
  much of L5 matters.

**Verify:** the release workflow produces an installable TV APK; install it on a device with the phone app
also installed (must coexist).

## Verification and gates (per AGENTS.md)

- `./gradlew :android:tv:assembleDebug :android:tv:lintDebug`
- `./gradlew :android:tv:test` — and **wire it into `android.yml`**, which today runs only lint + assemble
  for `:tv`.
- `./check-local.sh` (workflow YAML, commonMain purity, KMP structure/outputs, contracts).
- `:android:tv` has **no `commonTest`** (plain Android module), so per AGENTS §3, logic worth testing
  belongs in a KMP module where a `commonTest` suite can reach it. Rule for this plan: **the decision goes
  in `:player`/`:home`/`:addons` (testable), the rendering stays in `:tv` (untested by design).**
- **Manual D-pad pass is mandatory** for every landing — CI cannot judge a 10-foot app. Check: every
  control reachable with D-pad only, focus always visible, no focus traps, Back behaves, nothing
  clickable-only.

## Risks

| risk | why it is real | mitigation |
|---|---|---|
| `:tv` grows a second copy of phone logic | the phone's session VM is 1,491 lines and the temptation is to copy it | share through `PlaybackSessionController`, `StreamResolver`, `WatchHistoryService`; move decisions, not code |
| `:tv` starts depending on `:app` | it is one line in a build file and it "just works" | rule in AGENTS.md for this module: `:tv` depends on ports, never on `:app` |
| Shared `:home` changes land on the phone too | the home model is commonMain by design | that is the point; the TV *screen* stays local, so layout changes do not |
| The TV home is 625 lines of local Compose and will drift from the phone's | two renderings of one model | accept it; the phone's home is touch-first and the TV's is 10-foot |
| Play TV requirements (16 KB pages, 64-bit, banner) | verified only at release time | L5 before any Play submission |
| applicationId change breaks existing sideload installs | users' installed TV app becomes a different app | do it once, early, and say so in the release notes |
| Scope creep into Search / Library / Settings | they are one file of placeholders today | explicitly out of scope; one landing per surface, this plan only covers three |

## Open questions (need your call before L1)

1. **Sharing strategy** — Option B (ports, recommended) or Option A (`:tv` depends on `:app`)? This
   decides the shape of L3 and L4.
2. **Identity** — rename the TV app to `com.crispy.tv.tv` now, before any Play submission?
3. **Torrents on TV** — keep the honest "not available" notice, or bring torrent playback to TV?
   **Answered: bring it.** The engine is not UI, the TV is already sideload-only, and a Play TV build can
   take the phone's `distribution` axis later.
4. **TV home scope** — is the current hero + rails home the final information architecture, or should L1
   also reshape it (e.g. a "Continue Watching" row first, a live/"next up" row)?
5. **Apple TV** — tvOS is a placeholder build today. Does this plan need to keep the TV design portable
   to tvOS, or is TV an Android-only surface for now?

## Appendix — file inventory (`:android:tv`, 7,466 lines of Kotlin)

| file | lines | role in this plan |
|---|---|---|
| `ui/screens/HomeScreen.kt` | 625 | L1 — hero, rails, dead-end Play button |
| `ui/screens/detail/DetailScreen.kt` | 1,061 | done, not in scope |
| `ui/screens/detail/DetailViewModel.kt` | 581 | done, not in scope |
| `ui/screens/detail/AiInsightsStory.kt` | 502 | done, not in scope |
| `ui/components/SidebarNavigation.kt` | 351 | L1 — focus hand-back |
| `ui/components/TvHomeComponents.kt` | 388 | L1 — rail cards, skeletons |
| `ui/components/CrispyLandscapeCard.kt` | 211 | L1 — focus styling |
| `ui/screens/player/TvPlayerScreen.kt` | 175 | L3 — rewrite chrome |
| `player/TvPlayerViewModel.kt` | 162 | L3 — rewrite around the engine port |
| `ui/sources/TvSourcesScreen.kt` | 183 | L2 — rewrite rows |
| `sources/TvSourcesViewModel.kt` | 109 | L2 — carry headers/subtitles |
| `ui/TvApp.kt` | 246 | L1/L2/L3 — routes and nav args |
| `di/TvServices.kt` | 162 | L3 — engine + session wiring |
| `ui/screens/PlaceholderScreens.kt` | 42 | out of scope (Search/Library/Settings) |
| `test/.../CrispyTvDarkColorsMappingTest.kt` | 381 | the module's only suite |

Shared modules this plan leans on (not to be forked): `:home` (`HomeUiState`, `HomeRefreshCoordinator`,
`buildHomeLayoutState`), `:addons` (`StreamResolver`, `AddonStream`), `:player` (`WatchHistoryService`,
`PlaybackIdentity`), `:core-domain` (`PlaybackSessionController`, `NativePlaybackTypes`),
`:native-engine` (`NativePlaybackController`).
