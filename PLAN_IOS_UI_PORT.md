# iOS UI Port Plan — Android Compose → SwiftUI (Liquid Glass, iOS 26)

Copy the Android app UI to iOS as native SwiftUI, reusing `ios/ContractRunner` as the domain
layer and Apple frameworks only (no KMP/CrossPlatform UI). Target iOS 26 so Liquid Glass APIs
(`glassEffect`, glass buttons, glass tab bars) are used natively without availability guards.
We develop on Linux, so nothing in this plan can be compiled locally; CI
(`macos-26` + xcodegen + xcodebuild) is the compile gate. A manual workflow
(`ios-ipa.yml`) packages an **unsigned IPA** for sideloading (no Apple dev license).

## Decisions

| Decision | Choice |
| --- | --- |
| Domain logic | Link local SwiftPM package `ios/ContractRunner` (`HomeCatalogPlanner`, `CatalogUrlBuilder`, `ContinueWatchingPlanner`, …) — already contract-tested |
| Shared stack | `ios/CrispyKit` SwiftPM package (Foundation + Observation only): backend client, auth/session, environment, routes, view models, MediaCard — consumed by both iOS and future tvOS targets |
| UI | Pure SwiftUI, iOS 26 deployment target, system components styled with Liquid Glass |
| Backend | URLSession port of `CrispyBackendClient` + `SupabaseAccountClient` inside CrispyKit (envelope-compatible with `CrispyBackendParsers.kt`) |
| Config | `CRISPY_BACKEND_URL` + Supabase URL/key injected via Info.plist through XcodeGen settings; runtime overrides via launch arguments (`-crispyBackendURL/-supabaseURL/-supabaseKey`) |
| tvOS | Placeholder target links CrispyKit (deployment target 17.0); full tvOS shell comes later and reuses the same stack |

## Status legend: ✅ done · 🚧 partial · ⬜ pending

## Android → iOS mapping (shell)

| Android | iOS |
| --- | --- |
| `AppRoot` bootstrap gate (`Loading/NeedsAuth/NeedsProfileSelection/Ready`) | `AppRootView` switching on `BootstrapViewModel.state` |
| `MainAppShell` + custom `FloatingBottomBar` capsule + circular search button | Native `TabView` (iOS 26 floating glass tab bar) — tabs Home / Discover / Library plus a `.search` role tab replacing the search circle |
| `TopLevelScrollToTopRequestKey` savedStateHandle counter | Re-selecting the current tab pops each tab's `NavigationStack` to root |
| Nav graphs (home/search/discover/library/settings/account/player) | One `NavigationStack` per tab; value-based routing enums instead of string routes |
| Shared element transitions (`SharedTransitionLayout`, backdrop/logo keys) | Native iOS 18+ equivalent: `.matchedTransitionSource(id:in:)` on cards + `.navigationTransition(.zoom(sourceID:in:))` on details — system-provided, no custom anim code |
| Material3 dynamic color + yellow fallback | System materials/glass; dark-first like the Android dark palette; accent handled by asset catalog later |

## Pages (first pass scope)

1. **Home** — wordmark top bar + profile avatar button; header pill row; hero carousel;
   planned feed blocks from `planPersonalHomeFeed`: wide rails (Continue Watching, This Week),
   catalog rails ("See all" → Catalog List page), collection shelves.
2. **Discover** — type filter chip (All/Movies/Series) + catalog picker sheet;
   adaptive grid of 16:9 landscape cards; cursor paging; pull-to-refresh.
3. **Library** — History / Watchlist / Ratings segmented chips; grouped grid; cursor paging;
   mark-watched action (optimistic outbox comes later).
4. **Search** — search-role tab: suggestions, recent history, genre suggestions,
   results grid (`searchTitles`, `searchSuggestions`).

Deferred to later passes: Details, Person, Calendar full page, Settings subtree,
Profile menu/management, Player, AI insights, stream selector sheets.

## Architecture (mirrors architecture.md layers)

```
ios/CrispyKit/                        # SwiftPM package, no SwiftUI/UIKit
  Sources/CrispyKit/
    AppConfig.swift                   # Info.plist keys + launch-arg override
    Networking/{CrispyHttpClient,CrispyBackendModels,CrispyBackendClient,JsonHelpers}
    Auth/{SessionStore,SupabaseAccountClient,BackendContextResolver}
    App/{AppEnvironment,AppRoute}     # composition root + bootstrap VM + routes
    Features/*ViewModel.swift         # Home, Discover, Library, Search,
                                      # Details, Person, CatalogList
    MediaCard.swift, HomeSnapshotMapper.swift
ios/Apps/iOS/                         # SwiftUI only (imports CrispyKit)
  CrispyRewriteiOSApp.swift
  App/{AppRootView,MainShellView}.swift
  Features/*/…View.swift
  UI/Theme.swift + Components/{Cards,BrandAndProfile}
```

- ViewModels are `@Observable` classes receiving `AppEnvironment` per call.
- Determinism rules respected: no `Date()` inside planning logic; planners receive inputs.
- CrispyKit compiles for iOS + tvOS (+ macOS host) so Apple TV reuses it verbatim.

- ViewModels are `@Observable` classes receiving repositories from `AppEnvironment`
  (no singletons besides the environment injected at the root).
- Determinism rules respected: no `Date()` inside planning logic; planners receive inputs.
- Repositories live at the data edge; views never touch `CrispyBackendClient` directly.

## Data flow per page (backend-wired)

- Bootstrap: `SupabaseAccountClient.ensureValidSession()` → `GET /v1/me` profiles →
  active profile from Keychain store → `Ready`. Sign-in/up against Supabase REST
  (`/auth/v1/token?grant_type=password`, `/auth/v1/signup`).
- Home: `getHome(accessToken, profileId)` → map `ProfileHomeResponse` sections →
  `HomeCatalogSnapshot` (same mapping as `HomeCatalogService.toSnapshot()`) →
  `planPersonalHomeFeed` (ContractRunner) → render blocks. Continue-Watching rail:
  `listContinueWatching` → `planContinueWatching` (ContractRunner).
- Discover: snapshot → `listDiscoverCatalogs` (ContractRunner) → picker; pages via
  `buildCatalogPage` over the loaded snapshot (matches Android `fetchCatalogPage`).
- Library: `listWatchHistory/listWatchlist/listRatings` with `nextCursor` paging.
- Search: `searchTitles` + `searchSuggestions`; history persisted to UserDefaults.

## Milestones

- ✅ **M1 — Shell & foundation**: project.yml (target 26.0, package deps,
  Info.plist config), CrispyKit networking/auth (`CrispyHttpClient`,
  `CrispyBackendClient` subset, `SupabaseAccountClient`, Keychain session store),
  `AppEnvironment` composition root + bootstrap gate, glass `TabView` shell
  (Home / Discover / Library / search-role tab, re-tap pops to root),
  Home (hero carousel + Continue Watching rail + planned catalog rails),
  Discover (type filter + catalog picker + adaptive grid + paging),
  Library (History/Watchlist/Ratings + cursor paging + mark-watched),
  Search (debounced suggestions, history, results grid), profile menu sheet.
- 🚧 **M2 — Details & navigation depth** (core done): metadata endpoints ported
  (detail/extras/episodes/person), `AppRoute` per-tab stacks, Details screen
  (hero, meta, glass Play CTA placeholder alert, genres, overview, credits,
  cast rail → person, seasons picker + episode rows, More-like-this rail),
  Person screen, Catalog list page, Home header pills + rail See-all links,
  card taps wired everywhere. ⬜ native zoom transition (two modifiers) left for polish.
- ✅ **M3 — Accounts surface**: profile management screen (add/edit name, kids
  toggle, built-in avatar grid), account settings (email, Trakt/Simkl status +
  disconnect, sign out, delete account), profile menu links. Optimistic
  watchlist/mark-watched toggles with failure-refresh; durable outbox queue
  deferred to M5.
- ⬜ **M4 — Player**: stream lookup port (addon manifests/TorrServer) +
  AVPlayer-based player using ContractRunner `PlayerMachine` +
  `PlaybackProgressPolicy`; overlay controls with glass chrome.
- ⬜ **M5 — Settings subtree + addons management**, parity with Android settings screens.
- ⬜ **M6 — Polish**: native zoom transitions (`.matchedTransitionSource` on cards ↔ `.navigationTransition(.zoom)` on details), scroll-edge effects tuning, haptics,
  skeleton states parity, This Week calendar rail/page, tvOS shell.

## Validation without a Mac

- `python3 scripts/validate_contracts.py` passes locally (81 fixtures).
- App target compiles only in CI (`macos-26` runner, Xcode 26.x required for
  Liquid Glass symbols). Failures surface as deduped `error:` lines in the run
  Summary + full raw log artifact (xcbeautify github-actions renderer, quiet).
- Unsigned device IPA: manual **iOS unsigned IPA** workflow → archive with
  signing disabled → Payload zip → artifact. Install via Sideloadly/AltStore
  (free Apple ID re-sign) or TrollStore; requires an iOS 26 device.
- Runtime config: `xcodebuild CRISPY_BACKEND_URL=... SUPABASE_URL=... SUPABASE_PUBLISHABLE_KEY=...`
  overrides, or launch arguments in dev.
- Behavior changes stay contract-driven; this port is UI-only and must not alter any
  fixture/planner behavior.

## Progress log

- M1 committed through `d818df4c` (shell + 4 pages wired to backend).
- CI hardening: package path fix (`d4ecf237`), compile fixes (`168235c9`),
  xcbeautify quiet logging + error summary, unsigned IPA packaging.
- M2 committed as `c7c39954`; CrispyKit extraction for tvOS reuse as
  `3774280a`; public-surface export (`0a2f07ca`, `8af612b2`).
- M3 committed as `b6128a46`.

## Risks

- CI Xcode must be 26.x for Liquid Glass symbols; if macos-latest lags, pin `xcode-version`
  in the workflow or temporarily guard glass modifiers behind one small compat layer.
- DTO drift between Kotlin org.json parsing and Swift Codable — mitigate by porting field
  names/options exactly (optional-vs-default) and reusing fixtures when adding tests later.
- Keychain is unavailable on Linux; auth code paths only execute on device/simulator.
