# Android player — mpv only — Plan (`crispy-rewrite`)

Status: **in progress** on branch `mpv-only-player`. Landings 1 (mpv options), 2 (chapters
port), 3 (subtitle overlay) and 6 (PiP vo switch) are coded; 4 (delete Exo), 5 (settings
cleanup) and 7 (tests/contract v4) remain. All external facts re-verified 2026-11-09.
Local compile/test is not possible in this container (Robolectric native loader, K/N
host) — `android.yml` on GitHub Actions is the verification gate.

## Goal

Make libmpv the only playback engine on Android and Android TV, replacing ExoPlayer/Media3
entirely, while keeping: zero-copy video (`hwdec=mediacodec`, never the current
`hwdec=auto` which resolves to `mediacodec-copy`), Picture-in-Picture, native HDR fullscreen
and on TV (`vo=mediacodec_embed`), app-drawn subtitle overlay, and chapters from mpv's
`chapter-list`.

## Fresh facts (verified 2026-11-09, not stale)

| Fact | Source | Consequence |
|---|---|---|
| `dev.jdtech.mpv:libmpv` latest is **1.0.0** (2026-04-06); repo already pins it. Bundles mpv **0.41.0**, ffmpeg 8.1, libass 0.17.4, libplacebo 7.360.1, minSdk 26. Instance API `MPVLib.create(ctx)` + `mpv.init()` — repo already uses it | Maven Central / jarnedemeulemeester/libmpv-android v1.0.0 | No dependency bump needed |
| mpv upstream **0.42.0** released 2026-11-05; highlights contain no Android vo/hwdec/HDR changes | mpv-player/mpv release | AAR stays on 0.41.0; nothing to chase |
| Media3 latest stable **1.11.1** (1.12.0-alpha02 exists). 1.11.x added chapter extraction for **MP4 (Nero+QuickTime) and Matroska only**, exposed as `Metadata.Chapter`, no HLS/DASH chapters, no chapter UI | developer.android.com/jetpack/androidx/releases/media3 | The "exo has no chapters" premise is outdated for MP4/MKV, but mpv's `chapter-list` covers every container ffmpeg parses and we build the UI ourselves anyway. Repo pins 1.11.0 → 1.11.1 is a drop-in if Media3 stays for anything |
| AOSP mixed SDR/HDR: `minimumHdrPercentOfScreen` tunable since Android 13 (was fixed 50%), but default still gates high-brightness HDR to large screen coverage → **PiP windows stay SDR** | source.android.com/docs/core/display/mixed-sdr-hdr | User accepted: HDR fullscreen/TV only, PiP is SDR |
| mpv PiP issues (mpv-android #1242, #1000) are app-level (aspect ratio, re-enter crash); no evidence `vo=mediacodec_embed` breaks PiP rendering. Bitmovin's PiP black-screen report is ExoPlayer-based | GitHub issues 2024-2026 | PiP + tunneled vo must be verified on-device (matrix below); plan includes automatic `vo` switch while in PiP |
| mpv has no CDM (no Widevine); repo has zero DRM usage | mpv#8286/#17063, repo grep | Nothing breaks today; known limitation if DRM ever appears |

## Locked decisions (user)

- mpv only, both Android apps. `hwdec=mediacodec` explicitly.
- Fullscreen + TV: `vo=mediacodec_embed` + `hwdec=mediacodec` → tunneled decoder, zero copy, native HDR (AOSP: only tunneled decoders are guaranteed HDR). mpv overlays (subs/OSD) are dead in this mode → **subtitles are an app-drawn overlay** (user: "subs will be overlay").
- PiP: SDR tone-mapped — accepted ("ofc hdr wont work on pip").
- Fallback chain when tunneling is unsupported/black-screens on a device: `vo=gpu` + `hwdec=mediacodec` (still no CPU copy; libass renders subs on GPU there), last resort `mediacodec-copy`. Avoid `gpu-next` on 10-bit HDR (mpvEx #817 corruption).
- Impossible combo accepted: mpv-rendered subs + native HDR simultaneously does not exist on Android.

## Subtitle overlay design (the key mechanism)

`sub-visibility=no` always. mpv still selects and times the track (`sid`, `sub-add` for
external), and **`sub-text` still updates** even when rendering is off — so the Compose
overlay observes `sub-text` (event or ~100 ms poll, same as today's
`ExoSidecarSubtitleController` loop) and draws the text itself. This covers embedded and
external tracks uniformly, in both `mediacodec_embed` and `gpu` vo modes. Plain text first;
ASS/SSA styling (external .ass files) needs libass rasterization on our side — flagged as
the riskiest piece, can land after the first cut. Reuse `sniffSubtitleFormat` /
`subtitleFormatFromUrl` (core-domain commonMain) for parsing.

## Repo touchpoints (measured 2026-11-09)

- `:android:native-engine` — `NativePlaybackController.kt` (970 lines, Exo+mpv dual),
  `MpvPlaybackRuntime.kt` (657; `hwdec="auto"` at :229, SurfaceView at :185), `LibassCompat.kt`
  (hangs off `ExoPlayer`), `ExoSidecarSubtitleController.kt` (Media3 `SubtitleView` + download),
  `CrispyPlaybackNetworking.kt` (OkHttp for Exo), `PlaybackMediaItems.kt` (`MediaItem`
  subtitle configs); media3 deps at build.gradle.kts:38-47. libmpv at libs.versions.toml:269.
- Media3 outside native-engine — `:app`: `HeroTrailerLayer.kt` (ExoPlayer + `PlayerView`),
  `hero_trailer_player_view.xml`, `exo_player_view.xml`, `PlayerMediaSessionManager.kt`
  (media3-session MediaSession for lock screen/BT), `PlaybackSessionControllerPlayer.kt`
  (`SimpleBasePlayer` adapter backing that MediaSession), `PlayerSessionViewModel.kt` /
  `PlayerRoute.kt` (`PlayerView`); deps at app/build.gradle.kts:495-498. `:tv`:
  `TvHeroTrailer.kt`, `TvPlayerScreen.kt`, `TvPlayerViewModel.kt` (`bindExoPlayerView`),
  `TvServices.kt`; deps at tv/build.gradle.kts:211-213.
- Port: `PlaybackSessionController` (core-domain commonMain, 13 members) — `play(source, engine)`
  collapses, add `chapters`/`selectChapter`; `bindExoPlayerView` lives on the native-engine
  `PlaybackController` and becomes a SurfaceView bind.
- Settings/selection: `PlaybackSettingsRepository.playbackEnginePreference`
  (Auto/ExoPlayer/Libmpv), `PlaybackSettingsScreen` engine picker (:195-220),
  `SettingsNavGraph.kt:130`, `PlayerSessionDecisions.resolveInitialEngine`/`shouldFallBackToMpv`,
  `PlayerUiState.activeEngine`, `NativePlaybackEngine` enum, tests:
  `PlayerSessionDecisionsTest`, `KeyValueStorePlaybackSettingsRepositoryTest`,
  `NativePlaybackTypesTest`, `ProfileDataCloudSyncTest` fake.
- Contract: `contracts/SPEC.md` `player_machine` `contract_version` 3 — `NATIVE_CODEC_ERROR`
  falls back to `mpv` only while engine is `exo`, else terminal. With mpv-only on Android
  **and** iOS (iOS is already mpv-only per `docs/plans/ios-mpv-player-plan.md`), that fallback
  branch is dead everywhere → contract v4 simplification, Kotlin + Swift in lockstep.

## Landings

| # | Landing | What |
|---|---|---|
| 1 | mpv option set | `hwdec=mediacodec`, `vo=mediacodec_embed` default with runtime probe + fallback to `gpu` (+ last-resort `mediacodec-copy`), `sub-visibility=no`, keep demuxer cache/keep-open/idle; port Exo's HTTP behavior (user-agent, referer, auth headers, cookies, range) from `CrispyPlaybackNetworking.kt` to mpv `http-header-fields` — audit header parity first |
| 2 | Chapters | observe `chapter-list` in `MpvPlaybackRuntime` → `NativeChapter` data class in core-domain commonMain → extend `PlaybackSessionController` port (`chapters()`, `selectChapter(i)` → mpv seek) → chapter UI in the `:app` player chrome (commonMain Compose) and `:tv`; contract v4 (drop dead exo→mpv fallback, bump `contract_version`, SPEC + fixtures + Kotlin `PlayerReducer` + Swift `reducePlayerState` lockstep) |
| 3 | Subtitle overlay | download external subs port-neutrally (reuse OkHttp, move out of `ExoSidecarSubtitleController`), `sub-add` + observe `sub-text`, Compose overlay above the `SurfaceView` in `PlayerHost`; delete Media3 `SubtitleView` path. ASS styling via libass = follow-up |
| 4 | Delete Exo | single-engine `NativePlaybackController`; delete `LibassCompat.kt`, `ExoSidecarSubtitleController.kt`, `CrispyPlaybackNetworking.kt` (after header audit), `PlaybackMediaItems.kt`; replace `PlayerView` XML with `SurfaceView` in a `FrameLayout`; hero trailer (`HeroTrailerLayer.kt`, `TvHeroTrailer.kt`) switches to mpv; replace media3-session with platform `android.media.session.MediaSession` in `PlayerMediaSessionManager` + rewrite `PlaybackSessionControllerPlayer` onto it; strip media3 deps from native-engine/app/tv build files |
| 5 | Selection/settings cleanup | `resolveInitialEngine` → always MPV, delete `shouldFallBackToMpv`, collapse `NativePlaybackEngine`, remove engine picker row from `PlaybackSettingsScreen`, keep or migrate the stored preference key deliberately (not silently) |
| 6 | PiP | keep `SurfaceView` PiP (`PlayerPipController`); while in PiP switch `vo` to `gpu` (SDR anyway, and avoids tunneled-in-PiP black-screen risk), restore `mediacodec_embed` on exit; aspect ratio from mpv video params into `PlayerRoute` bounds as today |
| 7 | Tests & gates | update the four test files above; add chapter-mapping + subtitle-timing tests in core-domain/commonTest; contract suites Kotlin + Swift; then `check-local.sh` gates: `validate_contracts.py`, `:android:core-domain:testAndroidHostTest`, `:android:app:testAndroidHostTest`, `:android:app:compileCommonMainKotlinMetadata`, `:android:backend:testAndroidHostTest`, `verify_kmp_outputs.py`, `verify_apk_distribution.py` (store build must not contain media3), `:android:tv:assembleDebug`, mutation drivers |

## Device verification matrix (manual, before sign-off)

Phone fullscreen HDR (Pixel/Samsung), Android TV box (tunneled HDR, no green screen —
the mpv-android #853/#978 failure mode was `vo=gpu`), PiP with `vo` switched (black-screen
check), 10-bit HDR content, a device without tunneling (fallback chain), low-end TV,
seek/playback-speed while tunneled (mpv may need `vo=gpu` for speed control — verify),
lock-screen/BT controls via the platform MediaSession, hero trailer playback + audio focus.

## Risks

- `vo=mediacodec_embed` in PiP or on some TVs → black screen; mitigated by landing 6's
  runtime `vo` switch and the fallback chain, but needs the device matrix.
- Tunneled playback + playback-speed/seek quirks; verify, fall back to `vo=gpu` per-session.
- ASS styling in the overlay is the biggest open piece (libass rasterization on Android).
- Platform `MediaSession` replacement must keep Now Playing/lock-screen/BT parity.
- Contract v4 touches Swift too — keep `apple.yml` green (`swift test --package-path ios/ContractRunner`).
- Store vs sideload distribution is unaffected (media3 removal helps both), but
  `verifyDistributionExclusions`/`verify_apk_distribution.py` must stay green.
