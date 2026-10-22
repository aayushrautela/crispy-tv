# iOS player — mpv, native SwiftUI — Plan (`crispy-rewrite`)

Status: **Complete** for the seven original landings, all done and green on CI. Two follow-ups are
now in flight: 8 (addon subtitles reach the player) and 9 (addon management screens).

## Goal

Give the iOS app a real player: a native SwiftUI player screen in the **existing** SwiftUI
app (`ios/Apps/iOS`), backed by **libmpv** through the upstream SPM package. No Compose
Multiplatform host, no AVPlayer engine, no Kotlin dependency on the Apple side.

The iOS app today has **no player at all**: `ios/Apps/iOS/Features/Details/DetailsView.swift`
shows an alert whose text is literally "The player arrives with the next milestone.".

## Decisions (locked)

| Decision | Answer | Why |
|---|---|---|
| Host | Native SwiftUI in `ios/Apps/iOS` | The shell, routing and detail screen are already SwiftUI. A Compose host would mean rewriting the shell and dragging Kotlin into Apple — the desktop port already owns the "one shell" budget. |
| Engine | **mpv only** | The user chose mpv. It is the only engine that plays addon streams (MKV, ASS/SSA, arbitrary HTTP headers) on Apple. |
| Engine source | `https://github.com/mpvkit/MPVKit` (SPM) | Nuvio's `NuvioMedia/MPVKit` is a private fork of this. The upstream is public, has prebuilt `.xcframework` release assets, and is the honest dependency. |
| Licence | Product **`MPVKit`** (LGPL), never `MPVKit-GPL` | Upstream's own build script passes mpv `-Dgpl=false` and does not pass FFmpeg `--enable-gpl`; the GPL variant is opt-in. FFmpeg is built `-DBUILD_SHARED_LIBS=0` (static), so LGPL's relink obligation is the heavier variant: ship the source offer and the relinking instructions. |
| Bridge shape | **Events**, not polling | Nuvio's `NuvioPlayerBridge` is a flat polling getter interface, which only works because Compose recomposes on a timer. A SwiftUI client wants an `AsyncStream`/delegate. |

Not ports of Nuvio's files: the bridge is written from libmpv's own API (`mpv_create`,
`mpv_set_option_string`, `mpv_observe_property`, `mpv_wait_event`), per AGENTS.md "never port
files across". Nuvio is the reference for *mechanics* (mpv option set, the metal-layer
`wid` binding, the `wantsExtendedDynamicRangeContent` main-thread rule), not for code.

## What the engine needs to get right

Measured from Nuvio's iOS bridge and upstream's build flags:

- mpv options: `vo=gpu-next`, `gpu-api=vulkan`, `gpu-context=moltenvk`, `hwdec=videotoolbox`,
  `ao=default`, `audio-channels=auto`, `audio-fallback-to-null=yes`, `vulkan-swap-mode=fifo`,
  `vulkan-queue-count=1`, `vulkan-async-compute=no`, `vulkan-async-transfer=no`,
  `vulkan-disable-interop=yes`, `keep-open=yes`, `target-colorspace-hint=yes`,
  `tone-mapping=auto`, `hdr-compute-peak=yes`.
- The video output is a `CAMetalLayer`; mpv binds to it with `mpv_set_option_string(mpv, "wid", …)`.
  The layer's `drawableSize` is overridden because mpv's moltenvk context polls it and resizes
  its swapchain.
- `wantsExtendedDynamicRangeContent` must be set **on the main thread** — mpv's vo thread sets
  it while holding the core lock, so setting it off the main thread deadlocks.
- Upstream's Metal support is a patch version only, which is why the Vulkan/MoltenVK path is
  the one to use on Apple.
- **No Picture in Picture.** mpv renders its own frames; system PiP needs a sample-buffer
  layer. Nuvio has no `AVPictureInPictureController` anywhere, and its iOS PiP actual is `Unit`.
  The iOS player chrome therefore ships without a PiP button.

## Landings

| # | Landing | What it is | State |
|---|---|---|---|
| 1 | **Contract v3** (`player_machine`) | Terminal error phase + engine-fallback rules, in SPEC + fixtures + Kotlin `PlayerReducer` + Swift `reducePlayerState`, in lockstep | done |
| 2 | Seams | `PlaybackEngine` protocol, `PlayerSession` owning the contract reducer, `PlaybackSource` (url + headers + resume) | done |
| 3 | Engine | libmpv bridge: `mpv_create`, wakeup callback + `mpv_wait_event` on a background queue → `AsyncStream`; `UIViewRepresentable` over a `CAMetalLayer` | done |
| 4 | Chrome | Overlay, seek bar, ±10s, track sheet from mpv's `track-list`, buffering curtain, error state, Now Playing + remote commands (no PiP) | done |
| 5 | Progress | `sendWatchEvent` on `CrispyBackendClient` (iOS has none today — it can only read `getWatchState`) + a Swift `PlaybackProgressReporter` on `resolveProgressWrite`; resume from `WatchState.resumePositionSeconds` | done |
| 6 | Streams | A playable URL. iOS has no addon client and the backend has no resolve-streams endpoint | done |
| 7 | Smoke harness | A launch-argument-driven debug screen that plays one URL, so "does this actually decode" is one command | done |

### Follow-ups

| # | Landing | What it is | State |
|---|---|---|---|
| 8 | Addon subtitles | The subtitles a stream carries reach the player: `PlaybackSubtitle` on `PlaybackSource`, mpv `sub-add` for each of them | done |
| 9 | Addon management | Install, preview and remove an addon from inside the app — iOS can read the account's addon list but had no way to change it | in progress |

## Seams (landing 2) — what is in place now

`ios/CrispyKit/Sources/CrispyKit/Player/`:

- `PlaybackSource` — url, request headers, resume position in **milliseconds**, the unit the
  contract and the whole player use. A caller holding `WatchState.resumePositionSeconds`
  converts once, at the call site.
- `PlaybackEngine` — the protocol the session is written against: `engineId`, an event stream
  (`makeEventStream()`, called once — an `AsyncStream` is consumed once), and
  `open`/`play`/`pause`/`seek`/`shutdown`. The libmpv engine implements it; the chrome never
  sees it.
- `PlaybackEvent` — what an engine reports: position ticks, `firstFrame`, `ready`, `buffering`,
  `ended`, `codecError`, `failed(String)`. Every case maps onto exactly one contract action.
- `PlayerSession` — `@MainActor @Observable`. It owns the contract state (written only by
  `reducePlayerState`) and the transport state the contract has no vocabulary for (position,
  duration, buffered range, the last error message). The clock is injected, so the session is
  deterministic under test.

The session lives in `CrispyKit` because that package already depends on `ContractRunner` and
the app already links it; the chrome (landing 4) stays in `ios/Apps/iOS`.

`CrispyKit` had no test target, so it has one now (`ios/CrispyKit/Tests/CrispyKitTests`): a
`FakePlaybackEngine` records the commands the session issues and pushes events back, and
`PlayerSessionTests` covers the open path, the resume position, the event→phase mapping, the
terminal codec error and fatal error, the frozen intent, the transport ticks, play/pause/seek
and shutdown. `apple.yml` runs it as `swift test --package-path ios/CrispyKit`.

One consequence: `CrispyKit`'s macOS floor moved from 13 to **14**, because `Observation` (the
`@Observable` macro) needs macOS 14. The macOS platform exists only so the suite can run in
CI; the apps themselves are iOS and tvOS.

## Engine (landing 3) — what is in place now

The engine lives in the **iOS app target** (`ios/Apps/iOS/Player/`), not in `CrispyKit`. That is
deliberate: `CrispyKit` is a Swift package that CI also builds for macOS and that the tvOS app
links, and the `MPVKit` product carries ~35 binary xcframeworks. Keeping it in the app target
means the tvOS app does not link the binary and the package graph does not carry it either.

- `MPVMetalLayer` — a `CAMetalLayer` whose `drawableSize` drops 1×1 writes (MoltenVK writes a
  1×1 size before the real one, and the swapchain would resize to it) and whose
  `wantsExtendedDynamicRangeContent` is routed to the main thread, because mpv's vo thread sets
  it while holding the core lock and setting it off the main thread deadlocks.
- `MPVPlaybackEngine` — the `PlaybackEngine` implementation. It owns the metal layer, creates the
  mpv handle with `wid` bound to that layer, and runs a background queue blocking in
  `mpv_wait_event`. Events are delivered as a `PlaybackEvent` `AsyncStream`; the chrome consumes
  the stream, never the handle.
- `MPVPlayerSurface` — a `UIViewRepresentable` hosting the engine's metal layer and sizing it to
  the view.
- `MPVSmokeScreen` — a `#if DEBUG` screen (reached from Account settings → Developer) that plays
  one URL through a real `PlayerSession` and shows the contract phase.

The mpv option set is the one measured from Nuvio and upstream's build flags: `vo=gpu-next`,
`gpu-api=vulkan`, `gpu-context=moltenvk`, `hwdec=videotoolbox`, `ao=avfoundation`,
`audio-channels=auto`, `audio-fallback-to-null=yes`, `vulkan-swap-mode=fifo`,
`vulkan-queue-count=1`, `vulkan-async-compute=no`, `vulkan-async-transfer=no`,
`vulkan-disable-interop=yes`, `keep-open=yes`, `target-colorspace-hint=yes`, `video-rotate=no`,
`subs-match-os-language=yes`, `subs-fallback=yes`, `terminal=no`, `idle=yes`. Failures of
`vo`, `gpu-api` and `gpu-context` are fatal (there is no picture without a video output); every
other option failing is logged and ignored.

Two decisions worth stating, because both look like omissions:

- **The playhead is polled, the state is not.** Position, duration and buffered range are read
  with `mpv_get_property` on a 200 ms timer, because mpv exposes them as properties that change
  constantly, and an observer callback per change would wake the UI on every frame. Everything
  that is a *state transition* (file loaded, video reconfig, end of file, cache stall) comes
  through `mpv_wait_event` and is pushed on the stream.
- **There is no `mpv_observe_property` for the position**, so the position the chrome shows is
  up to 200 ms stale. The contract's `positionChanged` carries no timestamp, so nothing depends
  on it being exact.

`ios/project.yml` gains the package `MPVKit` (url `https://github.com/mpvkit/MPVKit`, exact
version `1.1.0-n9.0.2`) and links it — with `ContractRunner`, for the contract's phase names —
on the **iOS target only**. The tvOS target is untouched.

## Chrome (landing 4) — what is in place now

The chrome lives in the **iOS app target** (`ios/Apps/iOS/Features/Player/`), because the surface
it draws is the engine's metal layer and the engine is an app-target type.

- `PlayerScreen` — the chrome, written against the session and not against an engine: the video
  surface, the controls that fade out while the video plays, the seek bar, ±10s, the buffering
  curtain, and the error and ended curtains the contract's phase decides. The surface is a
  parameter, so the chrome does not know which engine it drives.
- `PlayerTrackSheet` — the track picker, fed by the engine's `track-list`.
- `NowPlayingController` — the lock screen, the control centre and the remote control buttons.
- `MPVPlayerScreen` — the composition root: it creates the engine and the session, opens the
  source, takes the audio session, and hosts the chrome.

The contract machine has no vocabulary for a track, so tracks are transport state, like the
playhead. The seam grew a third caller and the protocol grew with it: `PlaybackTrack`,
`PlaybackTrackKind`, `PlaybackTrackSelection`, a `tracksChanged` event and a `selectTrack`
engine method. The session holds the tracks and forwards a selection.

mpv keeps its tracks in a property tree, and the engine reads them one path at a time:
`track-list/count`, then `track-list/N/id`, `/type`, `/title`, `/lang`, `/default`, `/forced`.
The track that plays comes from `current-tracks/audio|sub|video/id`, with `aid`/`sid`/`vid` as
the fallback. Choosing a track sets mpv's `audio-track`, `sub-track` or `video-track` property,
and "no" turns a kind of track off. The list is read again when mpv reports a change to
`track-list`, `current-tracks` or the selection aliases, and the engine only reports a change
when the list actually differs.

There is **no Picture in Picture**: mpv draws its own frames, so the system's picture-in-picture
controller has no video layer to show, and Nuvio's iOS build has no `AVPictureInPictureController`
at all.

## Progress (landing 5) — what is in place now

iOS could **read** progress (`getWatchState`) but could not write any: `CrispyBackendClient` had
no `sendWatchEvent`, so nothing the player did reached the backend. It does now, and the player
reports what it did.

- `CrispyBackendClient.sendWatchEvent(accessToken:profileId:input:)` — posts to
  `/v1/profiles/{profileId}/watch/events`. The optional fields are left out of the payload
  rather than sent as null, because the backend reads "absent" and "null" differently.
- `PlaybackEventInput` — the event's shape, mirroring the Android `PlaybackEventInput`: the
  client event id, the event type, the item, the position and duration in seconds, the season
  and episode, the moment it happened, and a free-form payload.
- `PlaybackIdentity` — what is playing, in the shape the watch events carry. The Android
  identity carries more (the content type, the external ids, the year); only the fields that
  reach an event are here.
- `PlaybackProgressReporter` — owns every "the player moved" report, member for member with the
  Android `PlaybackProgressReporter`: `syncWatchHistory` (the poll), `scheduleProgressSyncAfterSeek`
  (the settle window) and `reportPlaybackStopped` (the end of playback, once). It applies the
  contract's `resolveProgressWrite` itself, because iOS has no `WatchHistoryService` port to
  apply it for it. The two clocks are injected — a wall clock for the event's instant and a
  monotonic clock for the settle window and the persist interval — so the reporter is
  deterministic under test.
- `PlaybackEventType` — the backend's vocabulary: `playback_progress`,
  `playback_progress_snapshot`, `playback_completed`.
- `WatchState.resumePositionMs` — the resume position in the milliseconds the player counts in,
  converted once at the boundary.

Two deliberate differences from Android, both documented in the code:

- Android sends **two** events when playback stops (a snapshot, then the completion). The two
  carry the same position and the backend needs one, so this sends one. The `player_progress`
  contract covers the gating rules, not the number of events.
- Android clears the settle window on a timer; here the first poll after the window clears it.
  Nothing is sent between polls, so the only thing the timer could do was clear the last-sync
  stamp — which that poll does too.

The app wiring lives in `MPVPlayerScreen`: it owns the reporter, polls the session every 500 ms
(the cadence the Android player polls on), arms the settle window when the session reports a
seek, and reports the stop when the player closes. The reporter's send resolves the backend
context when it sends, because the token may have been refreshed since the player opened. The
player also asks the backend where to resume: `getWatchState` gives the resume position, and a
position the caller already put on the source wins over it.

`PlaybackProgressReporterTests` covers the start, the minimum position, the missing duration,
the missing identity, the persist interval, the paused snapshot, the settle window, the stop
(latched once, pinned to the duration when the item completed), the client event id and the
instant.

## Streams (landing 6) — what is in place now

iOS had no way to get a playable URL: there was no addon client at all, and the backend has no
resolve-streams endpoint. The streams are resolved the way the Android app resolves them — against
the account's addons, over the addon's own protocol — with one difference: iOS reads the addon list
from the backend (`/v1/account/addons`) instead of keeping a local registry in sync with it.

- `CrispyBackendClient.listAddons(accessToken:)` — the account's addons.
- `AddonStreamsClient` — the resolver. It fetches each addon's manifest, reads what the manifest
  serves (`resources`, `types`, `idPrefixes`), builds the addon's stream resource URL
  (`{base}/stream/{type}/{id}.json`, with the id percent-encoded and the manifest's own query
  carried over) and parses the answer. The addons are asked in parallel and their answers are
  merged in the account's addon order.
- `StreamLookup` — the id an addon knows an item by: the IMDb id, with the season and the episode
  for an episode. A backend id that is not an IMDb id is not an addon's id, so it is not used.
- `StreamSource` — one stream: its title, its URL, the request headers the addon's behavior hints
  ask for, and the subtitles it offers. A stream with an info hash is a torrent, and iOS has no
  torrent engine, so the resolver does not offer it.
- `StreamMediaType` — movie, series or anime.
- `StreamPickerViewModel` — resolves the streams for one title and holds them.
- `StreamPickerSheet` — the picker, in the app target. It presents the player over itself, so
  choosing a stream and watching it is one gesture.

The details screen's Play button opens the picker. A show has no streams of its own — an addon
serves an episode, not a series — so the show's button plays the first episode of the selected
season, and each episode row plays its own episode. The identity the watch events carry is the
episode's, not the show's.

Two things the resolver deliberately does not do: it does not resolve torrents, and it does not
hand the addon's subtitles to the player. The subtitles are carried on the stream, and the player
does not use them yet.

## Smoke harness (landing 7) — what is in place now

The engine is only proven by playing something, so the app can be asked to play
one URL by a launch argument:

```sh
xcrun simctl launch booted com.crispy.rewrite.ios -mpvSmokeURL https://example.com/movie.mkv
```

- `SmokeLaunchArguments` reads the argument out of `ProcessInfo`'s arguments,
  once, at launch. A URL that is not there is not an error: the app starts
  normally.
- `CrispyRewriteiOSApp` presents the player over the root when the argument
  was given, so the harness works whatever the app was showing.
- `MPVSmokeScreen` is the manual half of the same thing: a debug screen
  (Account settings → Developer) with a URL field, for when the URL is not
  known in advance.

Both are `#if DEBUG`, so neither is in a release build.

## Addon subtitles (landing 8) — what is in place now

An addon offers subtitles next to the stream it offers — one URL per language,
inside the stream payload — and those now travel with the media:

- `PlaybackSubtitle` (`CrispyKit/Player/`) is a subtitle file: its URL, the
  provider's name for it, and its language. `PlaybackSubtitle(rawUrl:label:language:)`
  is the lenient door the addon payload comes through, and it refuses anything
  without a scheme.
- `PlaybackSource` carries `subtitles: [PlaybackSubtitle]`, and `resuming(at:)`
  keeps them — a replay has to send the same requests as the first open.
- `StreamSource.playbackSource` maps the addon's own `subtitles` payload into
  them, so choosing a stream in the picker chooses its subtitles with it. A
  subtitle entry whose URL does not parse is dropped, not failed on.
- `MPVPlaybackEngine.open` runs `sub-add <url> auto <title> <lang>` for each of
  them, right after `loadfile`. `auto` is mpv's "add it, but let the default
  selection decide": an embedded track that matches the user's language still
  wins, and the external one is listed in the track sheet either way (mpv
  reports external subtitle tracks through `track-list` like any other, so the
  sheet and the selection needed no change).
- The subtitle request uses the stream's own `http-header-fields`, which is
  what mpv applies to every request it makes for the file. An addon whose
  subtitles live behind a *different* header set than its streams is the known
  limit of this, and nothing in the addon protocol says otherwise; the way out
  would be `file-local-options/http-header-fields` around the `sub-add`, which
  mpv refuses to scope to a subtitle track alone.

Tests: `ios/CrispyKit/Tests/CrispyKitTests/PlaybackSourceTests.swift` pins what
travels with a stream (headers and subtitles survive `resuming(at:)`, the
addon's subtitles become `PlaybackSubtitle`s, an unparseable URL is dropped).

## Contract v3 (landing 1) — what changes and why

The contract reducer is used by exactly two callers: `android/core-domain` and
`ios/ContractRunner`. The Android app has its own player state (`PlayerUiState` in
`android/app/.../playerui`) and does not read the contract reducer, so this landing is
contained.

v2 rules (and their hole): `NATIVE_CODEC_ERROR` always moved to `loading_media` and swapped the
engine to `mpv` **only** when the engine was `exo`. On a platform whose engine *is* `mpv`
(iOS), a codec error therefore had no terminal state — the machine spun in `loading_media`
forever. `PlayerPhase.ERROR` existed in both enums and in `toContractValue()`, but no reducer
arm ever set it: the error phase was unreachable.

v3 rules:

1. `NATIVE_CODEC_ERROR` — engine is `exo` → phase `loading_media`, engine `mpv` (the Android
   fallback, unchanged). Any other engine → phase **`error`**, engine unchanged. There is no
   second engine to fall back to.
2. `NATIVE_FATAL_ERROR` (new event) → phase **`error`**, engine unchanged. The generic
   "the engine failed and cannot recover" event, kept distinct from a codec error so the
   Android app can report an mpv failure without mislabelling it.
3. `error` is **terminal**: once in it, only `OPEN_HTTP` / `OPEN_TORRENT` leave it. A late
   engine callback (a buffered event arriving after the failure) must not resurrect the player.

The engine for a session is not a new `initialPlayerState` parameter: `OPEN_HTTP` already
carries an optional `engine` and the reducer already honours it (`engine ?: state.engine`), so
iOS opens with engine `mpv` and the fixture asserts it. The schema needs no change
(`event.engine` and `expect.engine` are already allowed additional properties, and `error` is
already in the phase enum).

`contract_version` 2 → 3 for the `player_machine` suite; v2 fixtures are superseded and
removed, exactly as v1 was at v2. `ContractFixturesSanityTest` enforces one version directory
per suite, and `PlayerMachineContractTest` pins its version directory explicitly, so both are
updated in the same landing.

## Where the build is verified

This checkout has **no JDK and no Swift toolchain** (`java`, `javac`, `gradle`, `swift` are all
absent), so the local gates available here are the Python ones (`scripts/validate_contracts.py`
with `jsonschema==4.23.0` installed via `ensurepip`/`pip`). Everything else is proven by CI:

- `android.yml` — the Kotlin side (`:android:core-domain` tests, `:android:app` tests).
- `apple.yml` — the Swift side (`swift test --package-path ios/ContractRunner` for the contract
  suite, `swift test --package-path ios/CrispyKit` for the player's own suite), the Kotlin
  Apple targets, and the xcodebuild gate for `CrispyRewriteiOS` / `CrispyRewritetvOS`.

Both are `workflow_dispatch`-only by deliberate choice, so a change is verified by pushing the
branch and dispatching them; a dispatched run has to be *read* (204 means accepted, not green).

## Not in this plan

- Picture in Picture on iOS (see above).
- Torrent streams: the resolver drops a stream with an info hash because iOS has no torrent
  engine; only direct streams are offered.
- Playback settings (preferred audio/subtitle language, default quality) and the rest of the
  settings surface iOS does not have yet.
- A Compose Multiplatform Apple host (the KMP `CrispyUI` framework exists and is exported by
  `:sharedUI`, but no Apple app consumes it; that is a different, larger decision).
- Apple implementations of `:platform-core`'s six ports (still zero).
- The desktop work in `docs/plans/desktop-shell-plan.md`, which is separate and in flight.
