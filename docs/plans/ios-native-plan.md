# iOS stays SwiftUI, KMP becomes its engine — Plan

Status: **Decided with the user (2026-10-23), not implemented.** Supersedes the "Apple wiring" direction in
`AGENTS.md` and the `ios/` line in `architecture.md`, which both assumed a Swift shell over the Compose
`CrispyUI` framework. That direction was wrong; **both documents are already corrected** (done in the
desktop-removal landing, not as a separate Landing 0 commit).

This plan was written while a desktop app still existed. It has since been removed — there is no
`:android:desktopApp`, no `:android:platform-desktop`, no `desktopMain` anywhere and no `jvm("desktop")`
target — so every reference to a desktop implementation below is historical. **`:platform-android` is now
the only implementation of `:platform-core`'s ports**, and `apple.yml` is the only place Kotlin/Native
compiles.

## Goal

iOS and tvOS render **native SwiftUI** — `TabView`, `NavigationStack`, sheets, Liquid Glass — because a
Compose UI on iOS draws Material, not UIKit, and "looks iOS-native" cannot be reached by re-skinning it.

KMP therefore stops trying to be the UI on Apple and becomes the **engine** the SwiftUI shells call:

- one implementation of the wire layer, the domain rules, watch-sync and addons (today iOS re-implements
  the first and cannot reach the rest at all);
- native screens remain Swift, and new iOS screens are written in SwiftUI;
- Android and TV keep the shared Compose shell they already have.

Accepted cost, named up front: **every screen exists twice** (Compose for Android/TV, SwiftUI for
iOS/tvOS) and the two must be kept in step by hand. Feature parity is bought back at the data layer,
where duplication is silent and expensive, not at the view layer, where it is visible and cheap.

## What already exists (measured)

- **No `iosMain` anywhere in `android/`.** `find android -path '*iosMain*' -name '*.kt'` returns nothing.
  There are zero Kotlin/Native platform implementations in this repository today, so nothing below is a
  port — all of it is new code on the Apple side.
- **`:platform-core` is interfaces only** (8 files, 307 LOC: `AppLogger`, `KeyValueStore`,
  `KeyValueStoreFactory`, `SecretStore`, `SecretFormat`, `TimeSource`, `MonotonicClock`,
  `DistributionCapabilities`). `:platform-android` implements them, and it is the **only** implementation
  (a desktop one existed and went with the desktop app). There is no Apple implementation of any of them.
- **`:backend` `commonMain` is already portable**: `CrispyBackendClient`, `SupabaseAccountClient`,
  `CrispyBackendParsers` (on `kotlinx.serialization.json`), `BackendApi`/`AccountApi`,
  `BackendTypes`, `BackendContextResolver`, `ActiveProfileStore`. `androidMain` holds **one** file,
  `SecureTokenStore.kt` (Android keystore). The module's own build-file table still describes the
  pre-port world — correct it while landing, not before.
- **`:network` `commonMain` holds the `CrispyHttpClient` interface**; the only implementation is OkHttp,
  which lives in **`androidMain`** (`OkHttpCrispyHttpClient`, `CrispyOkHttpFactory`, `OkHttpCallAwait`,
  beside `AppHttp.kt`) after the `jvmCommonMain`/`desktopMain` split was unwound. **There is no iOS
  transport.** This is the single hard prerequisite for everything else.
- **Ten modules declare `iosArm64`/`iosSimulatorArm64`** and `apple.yml` compiles all ten; eight of them
  also declare `linuxX64` (`:addons`, `:backend`, `:core-domain`, `:home`, `:network`, `:platform-core`,
  `:player`, `:watchhistory`), which is the local purity gate. `:app` and `:sharedUI` are the two Compose
  modules without it.
- **`:sharedUI` exports a static `CrispyUI` framework that no Swift file imports**; `ios/project.yml`
  depends only on local Swift packages (`CrispyKit`, `ContractRunner`). So the framework is a claim
  nothing consumes.
- **Swift duplication to delete**: `ios/CrispyKit/Sources/CrispyKit/Networking/` is 1,382 LOC
  (`CrispyBackendClient.swift` 731, `CrispyBackendModels.swift` 486, `CrispyHttpClient.swift` 67,
  `JsonHelpers.swift` 98), plus `Auth/SupabaseAccountClient.swift` (261), `Auth/SessionStore.swift` (91),
  `Auth/BackendContextResolver.swift` (31).
- **The iOS app is already native and already real**: 38 Swift files under `ios/Apps/iOS`, a working
  Supabase auth + profile-selection gate, and Home / Discover / Search / Library / Details / Person /
  Catalog list screens over the live backend.
- **What iOS cannot reach**: no player (no `AVPlayer` anywhere), no addons, no AI insights, no watch-sync,
  no intro skip, no settings. Kotlin has them in `commonMain` already — `:addons` 3,807 LOC, `:home`
  2,272, `:watchhistory` 1,222, `:player` 509 (`EpisodeListProvider`, `TorrentResolver`,
  `WatchHistoryService`, `PlayerStreamHandoff` is `commonMain` in `:app`).
- **`:app` `commonMain` ViewModels are Compose-free** except `androidx.compose.runtime.Immutable`;
  four of them sit inside screen files (`DiscoverScreen`, `CalendarScreen`, `LibraryScreen`,
  `AddonsSettingsScreen`) and would need extracting before any of that logic could cross to Swift.
- **The Apple CI job is `workflow_dispatch`-only** (last green run 2026-10-03, 4m26s). Kotlin/Native
  cannot compile on Linux, so **iosMain code is compile-verified only by a dispatched macOS run** — there
  is no local gate for it, unlike `commonMain`, which `linuxX64` covers in seconds.

## Landing 0 — write the decision down (do this first, alone)

Three files stated the superseded premise; a corrected premise with surviving copies is worse than the
original, and one of the copies is a build file. **The `AGENTS.md` and `architecture.md` copies are
already corrected**; what remains is the build file's "No `binaries.framework` here" paragraph:

| copy | says | becomes |
|---|---|---|
| `architecture.md:539` | `ios/ the CrispyUI framework and the Swift shells over it` | `ios/ the SwiftUI apps and CrispyKit, over the KMP engine framework` |
| `AGENTS.md` Goal + the reference-projects section | `:sharedUI` "produces the `CrispyUI` iOS framework"; Apple wiring is `:app`'s shared shell | iOS/tvOS UI is SwiftUI; `:app`'s shell is Android/TV only |
| `android/app/build.gradle.kts:224-226` | "the `CrispyUI` framework that the Swift shell imports" | no Swift shell imports it; state what the framework is actually for, or delete it |

Also decide `:sharedUI`'s framework binary. Under this plan nothing will ever import `CrispyUI`, and a
framework nobody links is an unverifiable claim. Recommended: **drop `binaries.framework` and keep the two
Apple targets**, because the iOS compile of the design system is what asserts it has no JVM/Android leak
— but say so in the build file, or the next reader will restore the framework.

## Landing 1 — the Apple platform implementation

New module `:android:platform-ios` (mirroring the `platform-android` naming), with
`iosArm64` + `iosSimulatorArm64` and no Linux target (it can have none). Contents, each one interface
from `:platform-core`:

- `CrispyHttpClient` over **NSURLSession** — the blocking prerequisite for `:backend`.
- `KeyValueStore` over `NSUserDefaults` (`SecretFormat` already carries the shape).
- `SecretStore` over **Keychain** — the iOS twin of `SecureTokenStore`.
- `AppLogger` over `os.Logger`; `TimeSource`/`MonotonicClock` over `NSDate`/`mach_absolute_time`;
  `DistributionCapabilities` with honest answers for what iOS cannot do (no sideload, no external
  engines).
- `KeyValueStoreFactory` so the composition root reads like Android's.

**Risk to check in-file, not assume:** `:platform-core`'s `KeyValueStore` semantics (atomicity, key
scoping, and what a `SecretStore` read returns for a missing entry) must be read before the Keychain
implementation is written — the Android implementation is the specification.

**Gate:** a dispatched `apple.yml` run. Budget for a slow loop: nothing here compiles locally.

## Landing 2 — `:backend` as an iOS framework, and the Swift wire layer dies

- Add `binaries.framework { baseName = "CrispyEngine"; isStatic = true }` to `:backend`'s Apple targets.
  Two modules cannot export the same framework name, which is why this and `CrispyUI` cannot both exist —
  Landing 0's decision resolves that.
- Wire it into `ios/project.yml`: an `embedAndSignAppleFrameworkForXcode`-style run-script phase (or an
  XCFramework built by Gradle) plus `FRAMEWORK_SEARCH_PATHS`. Today the Xcode project has **no** Gradle
  linkage at all — this is the integration decision that shapes the developer loop, so make it explicitly
  and write down what a Swift-only change now costs.
- Delete `ios/CrispyKit/Sources/CrispyKit/Networking/` (1,382 LOC) and the three auth files that
  duplicate `:backend` (383 LOC), replacing them with calls through the framework.
- **Interop surface:** `suspend fun` exports to Swift as `async`, so the client's shape crosses cleanly.
  `Flow` does not — no call site should cross one. `BackendTypes` is 52 plain data classes; check what
  `parseAiInsightsSlides(JsonArray?)` looks like once exported (`Kotlinx_serialization_json...`) and put a
  facade in front of it if it is unusable.

## Landing 3 — presentation: SwiftUI keeps its ViewModels, or Kotlin supplies them

Two coherent answers; pick one and record it:

- **(a) Swift ViewModels over a Kotlin engine (recommended first).** The Swift app keeps its
  `@Observable` view models (already written, ~1.2k LOC) and only its *data* layer moves to Kotlin.
  Cheapest, no Compose-free refactor, and it captures the duplication that actually drifts.
- **(b) Kotlin ViewModels shared to Swift.** Requires extracting the four screen-embedded ViewModels and
  an interop-safe facade per screen (Compose types out of the public surface). Worth doing only after (a)
  has paid for itself, and only if a real drift incident justifies it.

The `ContractRunner` mirror must be revisited in the same commit either way: if the app calls
`:core-domain` through the framework, the Swift mirror's job changes from "the implementation iOS runs"
to "the independent cross-check the agent rules require" — which is a reason to keep it, not delete it,
but the file-level claim about what it is must be updated.

## Landing 4 — iOS features that do not exist yet, natively

In rough order of user impact:

- **Player**: `:player`'s seams plus `PlayerStreamHandoff` (already `commonMain`) behind an **AVPlayer**
  surface in SwiftUI — a native surface, not a Compose one.
- **Watch-sync** (`:watchhistory` `WatchSyncSource`) and the durable mutation behaviour
  (`UserMutationOutbox` is `:app` `commonMain`) — iOS currently has optimistic toggles with no outbox.
- **Addons** (`:addons`, 3,807 LOC) and AI insights (`AiInsightsModels` is already `:backend`
  `commonMain`).
- **Settings**, including the playback-settings repository the bundle already exposes to other targets.

## Landing 5 — tvOS

`ios/Apps/tvOS` is a 29-line placeholder today. Under this plan tvOS is a SwiftUI app of its own, not a
Compose surface, so it inherits the same framework and the same engine — and it needs a 10-foot design of
its own, which `:tv`'s Compose work cannot give it.

## Sequencing

1. **Landing 0** (docs + the `CrispyUI` decision) — alone, so the contradiction never survives a commit.
2. **Landing 1** `CrispyHttpClient` first, alone, proven by a dispatched CI run. Everything else waits.
3. **Landing 2** framework + Xcode wiring + the Swift deletion — the first landing a user can feel.
4. **Landing 3(a)**, then **Landing 4** in impact order.
5. **Landing 5** last.

## Risks / open items

- **No local compile for `iosMain`.** A green dispatched run is the only proof, and `apple.yml` is
  manual. Every landing here is therefore slower than its Android equivalent; do not plan two in
  one sitting without a dispatched run between them.
- **Xcode↔Gradle linkage changes the developer loop.** `xcodegen` + `xcodebuild` is currently a
  Kotlin-free path; after Landing 2 a Swift-only change pays for a framework build.
- **A framework's exported surface is its public surface.** Anything Compose-typed, `Flow`-typed or
  `Nothing`-taking that ends up `public` becomes a Swift-visible wart — the compiler will not warn, Swift
  simply cannot use it.
- **Two UIs, kept in step by hand.** This is the price of the decision; the mitigation is that the engine
  below them is single-sourced, so the two can only diverge in layout, never in behaviour.
- **`architecture.md`'s Phase 7** ("Align Android and iOS: mirror repository interfaces and domain
  models") is superseded in spirit — the mirror becomes a *call through a framework* for everything below
  the view layer. Rewrite it in Landing 0 rather than leaving it as a plan someone will follow.
