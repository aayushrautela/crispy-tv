# iOS player — mpv, native SwiftUI — Plan (`crispy-rewrite`)

Status: In progress. Landing 1 (contract v3) is the current work.

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
| 1 | **Contract v3** (`player_machine`) | Terminal error phase + engine-fallback rules, in SPEC + fixtures + Kotlin `PlayerReducer` + Swift `reducePlayerState`, in lockstep | **current** |
| 2 | Seams | `PlaybackEngine` protocol, `PlayerSession` owning the contract reducer, `PlaybackSource` (url + headers + resume) | pending |
| 3 | Engine | libmpv bridge: `mpv_create`, wakeup callback + `mpv_wait_event` on a background queue → `AsyncStream`; `UIViewRepresentable` over a `CAMetalLayer` | pending |
| 4 | Chrome | Overlay, seek bar, ±10s, track sheet from mpv's `track-list`, buffering curtain, error state, Now Playing + remote commands (no PiP) | pending |
| 5 | Progress | `sendWatchEvent` on `CrispyBackendClient` (iOS has none today — it can only read `getWatchState`) + a Swift `PlaybackProgressReporter` on `resolveProgressWrite`; resume from `WatchState.resumePositionSeconds` | pending |
| 6 | Streams | A playable URL. iOS has no addon client and the backend has no resolve-streams endpoint | pending |
| 7 | Smoke harness | A launch-argument-driven debug screen that plays one URL, so "does this actually decode" is one command | pending |

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
- `apple.yml` — the Swift side (`swift test --package-path ios/ContractRunner`), the Kotlin
  Apple targets, and the xcodebuild gate for `CrispyRewriteiOS` / `CrispyRewritetvOS`.

Both are `workflow_dispatch`-only by deliberate choice, so a change is verified by pushing the
branch and dispatching them; a dispatched run has to be *read* (204 means accepted, not green).

## Not in this plan

- Picture in Picture on iOS (see above).
- A Compose Multiplatform Apple host (the KMP `CrispyUI` framework exists and is exported by
  `:sharedUI`, but no Apple app consumes it; that is a different, larger decision).
- Apple implementations of `:platform-core`'s six ports (still zero).
- The desktop work in `docs/plans/desktop-shell-plan.md`, which is separate and in flight.
