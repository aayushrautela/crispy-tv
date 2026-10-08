# Desktop reaches the shared shell — Plan (`crispy-rewrite`)

Status: Planning (not yet implemented).
Builds on the committed `jvmMain` landings: `d4ffe8b4` (watch history into the app graph) and `4b59ed2a` (view-model wiring into `jvmMain`). All 40 bundle members except the ones below now have a `jvmMain`/`desktopMain` answer.

## Goal

Make `:android:desktopApp` render the **shared shell** — `MainAppShell` / `AppNavHost` from `:app` `commonMain` — instead of its `private enum class DesktopScreen` + `when`. Concretely: build a desktop producer of `AppNavHostDependencies` (the 40-member bundle), install it through `DesktopAppRoot`, and delete the `when`/`DesktopScreen` block in `android/desktopApp/src/main/kotlin/com/crispy/tv/desktop/Main.kt`.

Reference discipline (binding, per AGENTS.md): Crispy-web = layout/IA only; Nuvio = desktop mechanics only; **never port files across**. Everything below is built from `:app`'s own `commonMain` components + Compose + Material3 + `CrispyPalette`.

## What already exists (measured)

- `jvmMain` holds 9 files: wiring for `catalog/discover/random/person/calendar/search` builders, plus whole functions `formatBirthdayDate`, `englishDisplayNameForTag`, `deviceUtcOffsetMillis`. Each has an `androidMain` `Class<T>` shim and a `desktopMain` `viewModelFactoryOf` twin.
- `DesktopAppServices` (`desktopMain`) builds every `AppServices` member: `logger`, `timeSource`, `monotonicClock`, `ioDispatcher` (`Dispatchers.IO`), `httpClient`/`aiHttpClient`, `keyValueStores`, `tokenStore`, `homeSnapshotCache` (over `FileSystem.SYSTEM`), `dataDirectoryPath`, `serviceScope`, `invalidateImageCache`, `createPluginAddonsSyncBridge = null`.
- `DesktopDistributionCapabilities` (`:platform-desktop`, `com.crispy.tv.platform.desktop`) already implements `:platform-core`'s commonMain `DistributionCapabilities` with all four flags `false` — the honest desktop capability answer is **done**.
- `PlayerStreamHandoff` is **`commonMain`** (`com.crispy.tv.playerui`), so `PlayerStreamHandoff::stash` is reachable from desktop with no work.

## The 40-member desktop answer table

Legend: ✅ = answered (wiring already in place); ⚠ = blocked, grouped into a landing below.

| # | member | desktop answer | status |
|---|---|---|---|
| 1 | `searchViewModelFactory` | `jvmMain` builder | ✅ |
| 2 | `searchLoadProfile` | `graph.activeProfileLoader()` | ✅ |
| 3 | `randomWheelViewModelFactory` | `jvmMain` builder | ✅ |
| 4 | `profileListFactory` | accounts `desktopMain` twin | ✅ |
| 5 | `accountSettingsFactory` | accounts twin + `openUrl = { Desktop.browse(it) }` | ✅ |
| 6 | `accountLoadProfile` | `graph.activeProfileLoader()` | ✅ |
| 7 | `libraryViewModelFactory` | watch-sync source (Landing A) | ⚠ A |
| 8 | `libraryMonthName` | `jvmMain` `LocaleDateFormatters.monthName` | ⚠ B |
| 9 | `libraryClock` | `System.currentTimeMillis()` (`jvmMain`-legal) | ✅ |
| 10 | `libraryUtcOffsetMillis` | `deviceUtcOffsetMillis()` (`jvmMain`) | ✅ |
| 11 | `libraryLoadProfile` | `graph.activeProfileLoader()` | ✅ |
| 12 | `libraryLogger` | `graph.services.logger` | ✅ |
| 13 | `discoverViewModelFactory` | `jvmMain` builder | ✅ |
| 14 | `discoverLoadProfile` | `graph.activeProfileLoader()` | ✅ |
| 15 | `homeViewModelFactory` | watch-sync source (Landing A) | ⚠ A |
| 16 | `homeSelectorViewModelFactory` | no-plugin `StreamResolver` + `PlayerStreamHandoff::stash` (Landing A) | ⚠ A |
| 17 | `homeLoadProfile` | `graph.activeProfileLoader()` | ✅ |
| 18 | `homeCalendarViewModelFactory` | `jvmMain` builder | ✅ |
| 19 | `homeCatalogViewModelFactory` | `jvmMain` catalog builder | ✅ |
| 20 | `homeDetailsViewModelFactory` | `jvmMain` split of `detailsViewModelFactory` (Landing C) | ⚠ C |
| 21 | `homePersonViewModelFactory` | `jvmMain` builder | ✅ |
| 22 | `homePlaybackSettingsRepository` | `graph.playbackSettingsRepository` | ✅ |
| 23 | `homeShareText` | desktop clipboard answer (Landing B) | ⚠ B |
| 24 | `homeDateFormat` | `jvmMain` `LocaleDateFormatters.date` | ⚠ B |
| 25 | `homeTimeFormat` | `jvmMain` `LocaleDateFormatters.time` | ⚠ B |
| 26 | `homeClock` | `System.currentTimeMillis()` | ✅ |
| 27 | `homeScreenWidthDp` | window dimensions (Landing B) | ⚠ B |
| 28 | `homeScreenHeightDp` | window dimensions (Landing B) | ⚠ B |
| 29 | `homeYoutubeTrailerPlaybackSupported` | `DesktopDistributionCapabilities.youtubeInHeroPlaybackSupported` (false) | ⚠ A |
| 30 | `homeImageSeedColor` | pure color-averaging utility or `null` (Landing B) | ⚠ B |
| 31 | `homeHeroTrailerLayer` | no-op `@Composable` — "no inline trailer on desktop" (Landing D) | ⚠ D |
| 32 | `homeReviewProviderBadge` | render `:ui-assets` SVG a desktop way (Landing D) | ⚠ D |
| 33 | `homeRatingBadgeLogo` | render `:ui-assets` SVG a desktop way (Landing D) | ⚠ D |
| 34 | `homeYouTubeExtraVideoDialog` | no-op `@Composable` (Landing D) | ⚠ D |
| 35 | `homeFormatBirthday` | `formatBirthdayDate` (`jvmMain`) | ✅ |
| 36 | `pluginsUiSupported` | `DesktopDistributionCapabilities.pluginsUiSupported` (false) | ⚠ A |
| 37 | `pluginsSettingsScreen` | `null` — no plugins UI on desktop (Landing A) | ⚠ A |
| 38 | `addonsSettingsViewModelFactory` | no-addon resolver — desktop has no plugin runtime (Landing A) | ⚠ A |
| 39 | `imageSettingsRepository` | `graph.imageSettingsRepository` | ✅ |
| 40 | `profileDataCloudSync` | `graph.createProfileDataCloudSync()` | ✅ |
| 41 | `addPlayerDestination` | a registered route; placeholder now, Nuvio later (Landing E) | ⚠ E |

(40 bundle members + `addPlayerDestination` counted = 41 rows; `addPlayerDestination` is the 40th, the others are 39 — the table is the full seam.)

## Locked decisions

- **The shared shell is `commonMain`; the desktop plugs into its seams, it does not fork them.** `AppRoot`/`MainAppShell`/`AppNavHost` stay `commonMain`.
- **Desktop capabilities come from `DesktopDistributionCapabilities`, all `false`.** `pluginsUiSupported`, `homeYoutubeTrailerPlaybackSupported`, `pluginsRuntimeAvailable`, `torrentPlaybackSupported` are honest no's, not stubs. This is *already written*; the landings only wire it through.
- **`addPlayerDestination` is called unconditionally** at `AppNavHost.kt:235`. So the player route must *exist* for any "play" navigation not to crash. Landing E registers a real destination whose screen renders an honest "desktop player not yet available in this build"; Landing F replaces it with Nuvio. **Decided with the user: register the honest placeholder route — not a no-op lambda and not a split member set.**
- **Badges render via portable assets. Decided with the user:** the `:ui-assets` provider-logo SVGs move to `composeResources` in `:sharedUI` behind a Coil SVG decoder rather than being hidden on desktop. D3 is therefore a two-commit move (asset migration, then the desktop badge composable), not a documentation-only landing.
- **Watch-sync and stream-resolving answers are built from the commonMain ports, not ported from Android.** `OkHttpWatchSyncSource`/`StreamResolverProvider`/`HomeViewModelFactory`/`LibraryViewModelFactory` stay `androidMain`; their desktop counterparts live in `desktopMain` over the same `AppServices`/`AppGraph` members.

---

## Landing A — Watch sync + stream resolution + distribution wiring

Unblocks home+library ViewModels, the home selector, addons settings, and the three `DistributionCapabilities` reads.

### A1. Desktop watch-sync source
- `:watchhistory` `OkHttpWatchSyncSource` is `androidMain` (owns OkHttp socket + okio reader + `org.json` parse) and has **no** desktop implementation. Per the repo rule, the JSON boundary is `kotlinx.serialization.json.JsonElement` (keeps a JSON `null` distinct from an absent key — the decision `org.json` forces).
- Move the watch-sync *port* interface (member signatures) to `commonMain` if it is not already; add a `desktopMain` `WatchSyncSource` backed by `graph.services.httpClient` + `graph.services.timeSource` + `graph.services.ioDispatcher`, parsing with `kotlinx.serialization`.
- Confirm the interface's KDoc claim ("stays in androidMain permanently because of org.json") is **rewritten** in the same commit — a corrected premise with two surviving copies is worse than the original.

### A2. Desktop `StreamResolver` (no plugins)
- `HomeSelectorViewModel` needs a `StreamResolver` (not `StreamResolverProvider`). Desktop has no plugin runtime, so provide a `CachingStreamResolver` whose `AddonStreamsService` returns an **empty** addon list — the honest "no plugins" answer, matching `DesktopDistributionCapabilities.pluginsRuntimeAvailable = false`.
- `pluginStreamLoader` = `null`; `pluginSyncBridge` = `null` (both already the answer `DesktopAppServices.createPluginAddonsSyncBridge` gives).

### A3. Desktop `AppDistribution` twin
- `AppDistribution` is a service-locator `object` in `androidMain`, pinned by `PlaybackDependencies` (media3). Desktop does **not** implement that interface (it references `Context` + `PlaybackDependencies`, neither reachable from desktop). Instead `desktopMain` supplies the *same values the bundle reads* directly: `capabilities = DesktopDistributionCapabilities()`, `pluginsSettingsScreen = null`, `pluginStreamLoader = { null }`. A small `DesktopDistribution` object (or a `jvmMain` `Distribution` interface the two source sets implement) is the seam; the compile proves which shape type-checks.

### A4. Desktop home/library/addons factory twins
- `desktopMain`: `homeViewModelFactory(graph)`, `libraryViewModelFactory(graph)`, `addonsSettingsViewModelFactory(graph)` — `viewModelFactoryOf { buildXxx(graph, <A1 source>, <A2 resolver>) }`.
- `jvmMain`: the `buildXxx` wiring taking `graph: AppGraph` + the two A1/A2 collaborators as slots.

**Files:** `:watchhistory` (port interface + desktop source), `:app jvmMain` (3 builders), `:app desktopMain` (3 factory twins + `DesktopDistribution`), `:platform-desktop` (none — capabilities already exist).
**Gate:** `:android:watchhistory:desktopTest`, `:app:compileCommonMainKotlinMetadata`, `:app:desktopTest`, `:desktopApp:compileKotlin`, `:app:testAndroidHostTest` (the composition-root gate), all with `--rerun-tasks`.

---

## Landing B — Platform value answers

Unblocks the date/time, share, window-size, and seed-color members.

- **B1. `jvmMain` `LocaleDateFormatters`** (`date`/`time`/`monthName`). Android follows the *device's* configured 12/24-hour + date-order (`android.text.format.DateFormat.getDateFormat(context)`); a JVM answer cannot read that, so it uses `java.time.format.DateTimeFormatter` over `Locale.getDefault()` and documents the difference ("desktop follows the OS locale, not a per-app 12/24-hour override"). `monthName` is already `DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())` on Android, so it is portable and can go straight to `jvmMain` unchanged; `date`/`time` get the `java.time` spelling.
- **B2. `homeShareText` desktop answer.** `shareOnCrispy` is `Intent.ACTION_SEND`. Desktop = copy-to-clipboard (`java.awt.Toolkit` `ClipboardOwner`, or a `DesktopShare` capability). A `@Composable` no-op is *not* acceptable (it silently drops the feature); clipboard is the honest answer.
- **B3. Window dimensions** for `homeScreenWidthDp`/`homeScreenHeightDp`. Confirm what CMP 1.11.1 exposes on desktop for container size (measure in `:app commonMain` before writing); if there is no `LocalConfiguration` counterpart by name, cross the two `Int`s as data from the window the desktop `Window`/`SwingWindow` already knows.
- **B4. `homeImageSeedColor` desktop answer.** `rememberSeedColor` is Coil-Palette (`androidMain`). Either a pure-Kotlin colour-averaging utility over the already-loaded `ImageBitmap`, or `null` (the screen applies its `?: fallbackSeed`). Measure whether any pure-Kotlin palette utility already exists before writing one.

**Files:** `:app jvmMain` (`LocaleDateFormatters` + share + seed colour + window-size reader), `:app desktopMain` (factory slots that bind them).
**Gate:** `:app:compileCommonMainKotlinMetadata` (the *only* task that catches a JVM-only symbol leaked into `commonMain` — `java.time` here is the test), `:app:desktopTest`, `:desktopApp:compileKotlin`, all `--rerun-tasks`.

---

## Landing C — Details factory split

- `homeDetailsViewModelFactory` reads `graph.detailsViewModelFactory(itemId, itemType, runtimeEntry)` on the `AndroidAppGraph`, which needs `Context`/`Locale`/`DateFormat`. Split it the way every other factory in this phase is split: the wiring → `jvmMain` `buildDetailsViewModel`, the `Class<T>` `create` shim stays `androidMain`, and the desktop path takes the builder through `viewModelFactoryOf`.

**Files:** `:app jvmMain` + `androidMain` (pair), `:app desktopMain` (twin).
**Gate:** same pair as the committed landings: `:app:compileAndroidMain` + `:app:compileKotlinDesktop` `--rerun-tasks`, then the composition-root `:app:testAndroidHostTest`.

---

## Landing D — The four `@Composable` slots, honestly

These are `@Composable` *bodies* that are Android-only; the slot's *signature* is common. Each gets a desktop body, not a `null`:

- **D1. `homeHeroTrailerLayer`** — a no-op `@Composable` that renders nothing, with a KDoc saying *why* (no inline Media3 trailer on desktop; the trailer data still flows through `YoutubeTrailerPlaybackSupported = false`, so the UI hides the affordance that would call it). This is a documented omission, not a stub.
- **D2. `homeYouTubeExtraVideoDialog`** — a no-op `@Composable` (nothing to play inline on desktop; the dialog is suppressed when `youtubeInHeroPlaybackSupported = false`).
- **D3. `homeReviewProviderBadge` + `homeRatingBadgeLogo`** — the `:ui-assets` provider-logo SVGs. `:ui-assets` is a plain `com.android.library` that a JVM target **cannot** consume, and CMP documents SVG as "supported on all platforms except Android." Decided: render them via **portable assets** — move the provider logos to `composeResources` in `:sharedUI` and render with a Coil SVG decoder on desktop. Two commits: (D3a) migrate the logos, (D3b) the desktop badge composable.

**Files:** `:app desktopMain` (4 slot bodies); `:sharedUI` `composeResources` + `:ui-assets` (D3a); Coil SVG decoder wiring (D3b).
**Gate:** `:desktopApp:compileKotlin` + visual smoke.

---

## Landing E — Player destination (registered, not yet real)

`addPlayerDestination` is called at `AppNavHost.kt:235`. Until a player exists, register a **real destination** whose route renders an honest "desktop player is not available in this build" screen rather than a blank or a crash. This keeps the shared nav contract satisfied so the shell boots. **Decided with the user: register this placeholder route (not a no-op lambda, not a split member set).**

**Files:** `:app desktopMain` (the `addPlayerDestination` lambda), a small desktop "player placeholder" composable.
**Gate:** `:desktopApp:compileKotlin`; manual — navigate to the player route, expect the placeholder screen, not a stack.

---

## Landing F — The real desktop player (separate, larger)

The Nuvio work: `libmpv` on three OSes, an AWT `JComponent` surface peered into Compose, `SwingWindow` (required for the native surface), PiP + fullscreen, window-chrome. ~3.3k lines across 13 files in Nuvio's `features/player/desktop`, `NativePlayerController.kt` alone ~1,872 lines. Backend the existing `:player` interfaces. **Budget this as its own multi-session phase; it replaces Landing E's placeholder and re-answers `addPlayerDestination`.**

---

## Sequencing (blast radius, dependencies)

1. **B** (pure JVM value answers, no cross-module pin) — lowest risk, unblocks the date/share/size/seed members and proves `jvmMain` date/seed code compiles off Android.
2. **A** (watch-sync + stream + distribution) — unblocks home/library/selector/addons; touches `:watchhistory`.
3. **C** (details factory) — pattern-complete by now.
4. **D** (four `@Composable` bodies) — mostly documentation + one product call.
5. **E** (register the player route with a placeholder) — completes the bundle so `AppNavHostDependenciesDesktop` compiles in full.
6. **Write `AppNavHostDependenciesDesktop.kt`** in `:app desktopMain` — the 40-member producer, `remember`ed per `AppNavHost`'s contract, with the "re-read on every composition" rules from the Android producer reproduced.
7. **Delete the `when` + `DesktopScreen` enum** in `android/desktopApp/.../Main.kt`, wire `onSignedOut` through `DesktopAppRoot`.

F (real player) is a follow-on phase, not part of "reaching the shell."

## Files touched (summary)

| File | Landing |
|------|---------|
| `:watchhistory` port interface + `desktopMain` source | A |
| `:app jvmMain` `LocaleDateFormatters` / share / seed / window-size | B |
| `:app jvmMain` home/library/addons/details builders + `androidMain`/`desktopMain` twins | A, C |
| `:app desktopMain` `DesktopDistribution` + 4 `@Composable` bodies | A, D |
| `:app desktopMain` `AppNavHostDependenciesDesktop.kt` | E (bundle) |
| `:app desktopMain` player placeholder composable | E |
| `android/desktopApp/.../Main.kt` — delete `when`/`DesktopScreen`, wire `onSignedOut` | final |

## Verification plan

Per landing, `--rerun-tasks` (stale incremental once gave false results):
- `:app:compileCommonMainKotlinMetadata` — the JVM-symbol-leak gate.
- `:app:compileAndroidMain` + `:app:compileKotlinDesktop` — the two-target proof for every `jvmMain` move.
- `:app:desktopTest`, `:app:testAndroidHostTest` (composition root; the gate before touching `PlaybackDependencies`, `AppDistribution`, `AppServices`, `AppGraph`).
- `:android:watchhistory:desktopTest` (after A).
- `:desktopApp:compileKotlin` — proves a `commonMain`/`jvmMain` change still renders off-Android.
- `:android:androidApp:compile{Sideload,Store}DebugKotlin` + `:tv:compileDebugKotlin` — the shipping targets still build.
- `python3 scripts/verify_kmp_outputs.py` **after** the compiles; `verify_kmp_structure.py` after every move (re-measure the `:app` counts and correct AGENTS.md in the same commit).

## Risks / open items

- **`OkHttpWatchSyncSource` has no desktop implementation today** (Landing A1). If the watch-sync port is more tightly coupled to Android than the signatures suggest, A1 grows into moving the port interface to `commonMain` — measure first, don't assume.
- **`addPlayerDestination` placeholder (E) is a real route, not a stub** — a no-op lambda would let a "play" click navigate to a missing route and crash. The placeholder *destination* must register; only the *player surface* is deferred to F.
- **Badges (D3) is a product call**, not a mechanical port — decide which desktop surfaces show which review-site badges before writing the answer.
- **Window-size reader (B3)** — confirm CMP's desktop container-size API in-file before writing; the two `Int`s cross as data, never as the `LocalConfiguration` local itself.
- **`LocaleDateFormatters` semantics differ on desktop** (no per-app 12/24-hour override) — document the difference at the site, since Android's device-config answer is not reachable off Android.
