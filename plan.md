# Android Local-First Remnant Removal Plan

Do not run `gradle` or `./gradlew` for this plan. Gradle is not installed in this environment.

This plan is for removing old local-first / provider-first Android behavior, not layering another fix on top.

## Goal

Make the Android app a thin consumer of canonical server responses for personal-media behavior.

Server should own:
- continue-watching membership and ordering
- watched state and `watchedEpisodeKeys`
- calendar bucketing and episodic follow shaping
- provider/import connection state

Android should only:
- fetch
- decode
- map to UI models
- render

Android should stop:
- deriving continue watching from local progress
- deriving up-next locally
- merging local state into canonical server state
- filtering valid canonical rows by local heuristics
- slicing canonical data back into provider-specific feeds
- using provider/cache fallback as canonical feed behavior

## Do Not Run

- Do not run `gradle`
- Do not run `./gradlew`

Use file reads, grep, and call-site tracing until a proper Android build environment is available.

## Server Truth

Cross-checked server endpoints and builders that already provide the canonical shape Android should trust:

- `crispy server/src/http/routes/watch.ts`
- `crispy server/src/http/contracts/watch.ts`
- `crispy server/src/modules/watch/watch-state.service.ts`
- `crispy server/src/modules/watch/watch-row-product.mapper.ts`
- `crispy server/src/modules/watch/watch-query.service.ts`
- `crispy server/src/http/routes/calendar.ts`
- `crispy server/src/http/contracts/calendar.ts`
- `crispy server/src/modules/calendar/calendar.service.ts`
- `crispy server/src/modules/calendar/calendar-builder.service.ts`
- `crispy server/src/http/routes/profiles.ts`
- `crispy server/CLIENT_SERVER_MEDIA_STATE_CONTRACT.md`

These already cover:
- canonical continue watching
- title watch state and `watchedEpisodeKeys`
- calendar / this-week / up-next buckets
- watchlist / ratings / history
- import/provider connection state

## Phases

## Phase 0: Freeze Boundaries

Objective:
- stop introducing more client-side decision logic
- treat server contracts as fixed truth for read paths

Files:

1. `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendClient.kt`
- Decision: `KEEP`
- Why: transport model; already aligned to server watch/calendar shape
- Action:
  - preserve `CalendarItem.relatedShow`
  - preserve `CalendarItem.airDate`
  - preserve `CalendarResponse.kind`

2. `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendParsers.kt`
- Decision: `SIMPLIFY`
- Why: parser should decode contract, not silently redefine feed membership
- Action:
  - remove silent row-dropping where possible
  - stop using parser strictness as feed filtering policy
  - prefer explicit decode failure semantics over `continue`-based disappearance for canonical feeds

3. `android/core-domain/src/main/kotlin/com/crispy/tv/domain/media/PublicPersonalMediaContract.kt`
- Decision: `KEEP`
- Why: contract validation layer, not runtime legacy fallback logic
- Action:
  - keep as safety rail
  - do not mix this with runtime removal work

## Phase 1: Remove Canonical Read Fallbacks

Objective:
- canonical reads must be pure server reads
- no local progress/history/cache/provider fallback in canonical paths

Files:

1. `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- Decision: `SIMPLIFY`, then `DELETE` legacy pieces
- Delete:
  - canonical-path use of `getCachedContinueWatching(...)`
  - canonical-path use of local progress fallback
  - canonical-path use of local watched-history fallback
- Simplify:
  - keep only canonical server-backed `getCanonicalContinueWatching(...)`
  - keep only canonical server-backed `listWatchedEpisodeRecords()`
  - keep canonical watch-state fetch logic
- Verify before deleting extra pieces:
  - `getCanonicalContinueWatching(...)` no longer falls back locally
  - `listWatchedEpisodeRecords()` no longer falls back locally

2. `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/cache/WatchHistoryCache.kt`
- Decision: `VERIFY THEN DELETE`
- Why: cache is now legacy/provider-path support, not canonical-path support
- Delete when:
  - no active read path depends on `getCachedContinueWatching(...)`

3. `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/RemoteWatchHistoryService.kt`
- Decision: `VERIFY THEN DELETE`
- Why: appears to be legacy provider/local-first path
- Delete when:
  - constructor/call graph confirms app no longer instantiates it for runtime behavior

## Phase 2: Remove Local Continue-Watching / Up-Next Derivation

Objective:
- stop building feed semantics from local progress
- stop deriving up-next client-side

Files:

1. `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- Decision: `DELETE` specific functions/blocks
- Delete:
  - `listContinueWatchingFromLocalProgress(...)`
  - local movie/series reconstruction from `WatchProgressStore`
  - stale/progress filtering used to decide continue-watching membership for canonical UI
  - placeholder generation with `isUpNextPlaceholder = true`
  - local max-watched-episode tracking used to derive next episode
- Keep temporarily:
  - mutation-related helpers
  - server-backed canonical state fetches

2. `android/core-domain/src/main/kotlin/com/crispy/tv/domain/watch/FindNextEpisode.kt`
- Decision: `VERIFY THEN DELETE`
- Why: still has live references in provider/legacy paths and tests
- Delete when:
  - all runtime usages are removed
  - contract test strategy is updated or retired for this planner-era logic

3. `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/trakt/TraktWatchHistoryProvider.kt`
- Decision: `SIMPLIFY`, later possibly `DELETE` references to local next-episode derivation
- Why: still calls `findNextEpisode(...)`
- Action:
  - remove next-episode derivation if provider-specific continue-watching path is retired

4. `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/RemoteWatchHistoryService.kt`
- Decision: `SIMPLIFY`, later possibly `DELETE`
- Remove:
  - local up-next derivation
  - local/provider merge behavior
  - dependency on `findNextEpisode(...)`

## Phase 3: Remove Provider-Shaped Read APIs

Objective:
- keep provider logic out of read-path APIs
- make read interfaces canonical-only

Files:

1. `android/player/src/main/java/com/crispy/tv/player/WatchHistoryService.kt`
- Decision: `SIMPLIFY`, then `DELETE` legacy API
- Delete:
  - `listContinueWatching(limit, nowMs, source: WatchProvider?)`
  - `getCachedContinueWatching(...)`
  - `ContinueWatchingResult` once no callers remain
- Keep:
  - canonical read APIs
  - mutation APIs until mutation cleanup phase

2. `android/app/src/main/java/com/crispy/tv/domain/repository/UserMediaRepository.kt`
- Decision: `SIMPLIFY`
- Remove:
  - any provider-shaped read API still remaining
- Keep temporarily:
  - `preferredProvider()` only if mutation layer still depends on it

3. `android/app/src/main/java/com/crispy/tv/data/repository/DefaultUserMediaRepository.kt`
- Decision: `SIMPLIFY`
- Remove:
  - forwarding for deleted legacy read APIs
- Keep temporarily:
  - mutation-related provider selection forwarding if still needed

4. `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- Decision: `DELETE` legacy read path pieces
- Delete when read callers are gone:
  - `listContinueWatching(limit, nowMs, source)`
  - `listCanonicalBackendContinueWatching(source, limit, nowMs)`
  - `toProviderContinueWatchingEntries(...)`
  - `connectContinueWatchingMessage(...)`
  - read-path provider auth gating helpers

## Phase 4: Remove Client-Side Feed Filtering That Server Already Owns

Objective:
- do not locally decide feed membership/order when server already did

Files:

1. `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- Decision: `DELETE` or `SIMPLIFY`
- Delete:
  - stale membership filtering in canonical continue-watching path
- Why:
  - server already decides what is in continue watching

2. `android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`
- Decision: `SIMPLIFY`
- Remove:
  - placeholder-based split between `Continue Watching` and `Up Next`
  - any local suppression/filter logic if dismissal is fully server-owned
- Keep:
  - UI composition only

3. `android/app/src/main/java/com/crispy/tv/home/HomeWatchActivityService.kt`
- Decision: `SIMPLIFY` or `DELETE`
- If it remains only a thin pass-through mapper, inline or keep minimal
- Remove any residual feed-shaping semantics

## Phase 5: Calendar Cleanup

Objective:
- stop client-side semantic filtering of server calendar rows
- keep only UI projection

Files:

1. `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendParsers.kt`
- Decision: `SIMPLIFY`
- Remove:
  - silent item dropping that acts as feed filtering
- Keep:
  - decoding into transport DTOs

2. `android/app/src/main/java/com/crispy/tv/home/CalendarService.kt`
- Decision: `SIMPLIFY`
- Remove:
  - semantic filtering that decides whether a server row counts as a real calendar item
  - aggressive `mapNotNull { it.toCalendarEpisodeItem(...) }` as feed membership logic
  - bucket reinterpretation that changes server meaning
- Keep:
  - UI projection to screen/home card models
  - presentation-only grouping if still desired
- Verify before simplifying further:
  - whether home needs grouped same-day episode cards
  - whether `This Week` narrowing is still a product choice or should be server-owned

3. `android/app/src/main/java/com/crispy/tv/home/CalendarScreen.kt`
- Decision: `KEEP`
- Why: presentation screen
- Simplify only if screen still depends on old assumptions after service cleanup

## Phase 6: Remove Client-Side Watched-Episode Key Derivation

Objective:
- trust server `watchedEpisodeKeys`
- stop reconstructing watched identity locally where possible

Files:

1. `android/app/src/main/java/com/crispy/tv/details/EpisodeWatchStateResolver.kt`
- Decision: `SIMPLIFY`
- Remove:
  - watched-history fallback path if server `watchedEpisodeKeys` is sufficient everywhere this resolver is used
- Keep temporarily:
  - fallback only until coverage of server state is fully verified

2. `android/app/src/main/java/com/crispy/tv/watchhistory/WatchSupport.kt`
- Decision: `SIMPLIFY`, later `DELETE` specific helpers
- Delete when no longer used:
  - `episodeWatchKeyCandidates(...)`
  - `addEpisodeKey(...)`
- Keep temporarily:
  - `preferredWatchProvider(...)` only for mutation flows

3. `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- Decision: `KEEP` current `mediaKey`-first watched record identity, then simplify later if watched-history fallback is deleted

## Phase 7: Normalize Details Identity

Objective:
- remove downstream identity heuristics caused by inconsistent `MediaDetails.id`

Files:

1. `android/app/src/main/java/com/crispy/tv/home/RecommendationCatalogService.kt`
- Decision: `SIMPLIFY`
- Action:
  - document and normalize identity conventions for `MediaDetails`

2. `android/app/src/main/java/com/crispy/tv/metadata/MetadataViewMappings.kt`
- Decision: `SIMPLIFY`
- Action:
  - prefer `mediaKey` as primary details identity when backend metadata exists

3. `android/app/src/main/java/com/crispy/tv/playerui/PlayerLaunchSnapshot.kt`
- Decision: `SIMPLIFY`
- Action:
  - align `MediaDetails.id` / `mediaKey` handling with backend identity strategy

4. `android/app/src/main/java/com/crispy/tv/playerui/PlayerSessionViewModel.kt`
- Decision: `SIMPLIFY`
- Action:
  - same identity normalization as above

5. `android/app/src/main/java/com/crispy/tv/metadata/tmdb/TmdbMediaDetailsMapper.kt`
- Decision: `SIMPLIFY`
- Action:
  - keep fallback mapping, but align with the same identity rule

6. `android/app/src/main/java/com/crispy/tv/details/WatchCtaResolver.kt`
- Decision: `SIMPLIFY`
- Remove later:
  - identity heuristics by `id` / provider / providerId if `mediaKey` becomes consistent
- Prefer:
  - title-scoped server watch state over feed scanning where possible

## Phase 8: Provider State / Cache Cleanup

Objective:
- remove provider read-state persistence and provider-shaped read-side infrastructure

Files:

1. `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- Decision: `VERIFY THEN DELETE`
- Delete when legacy provider read path is gone:
  - persisted provider connection flags/usernames in prefs
  - read-side provider auth gating helpers

2. `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/cache/WatchHistoryCache.kt`
- Decision: `DELETE`
- Delete after:
  - `BackendWatchHistoryService` and `RemoteWatchHistoryService` no longer depend on it for continue watching

3. `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/RemoteWatchHistoryService.kt`
- Decision: `DELETE` if unused by app graph
- Otherwise `SIMPLIFY` to mutation/support-only responsibilities

## Phase 9: Mutation Layer Revisit

Objective:
- after read paths are server-native, decide how much provider-specific mutation logic still belongs in app/domain layer

Files:

1. `android/app/src/main/java/com/crispy/tv/details/DetailsUseCases.kt`
- Decision: `KEEP FOR NOW`, later `SIMPLIFY`
- Keep now because:
  - mutations still use provider choice and Simkl IMDb policy
- Later simplify if:
  - backend-native mutations fully replace provider-selected mutation behavior

2. `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- Decision: `SIMPLIFY LATER`
- Remove later if backend-native mutations become authoritative:
  - provider-specific watchlist/rating mutation branches
  - provider-specific sync result messages on mutation flows

3. `android/app/src/main/java/com/crispy/tv/watchhistory/WatchSupport.kt`
- Decision: `KEEP TEMPORARILY`
- Keep `preferredWatchProvider(...)` only as long as mutation flows require it

## Phase 10: Planner / Legacy Contract Cleanup

Objective:
- remove dead planner-era code once runtime paths no longer depend on it

Files:

1. `android/core-domain/src/main/kotlin/com/crispy/tv/domain/watch/ContinueWatchingPlanner.kt`
- Decision: `VERIFY THEN DELETE`
- Current state:
  - appears referenced by contract tests, not runtime app flow
- Delete when:
  - planner-era contract suite is retired or replaced

2. `android/contract-tests/src/test/kotlin/com/crispy/tv/contracts/ContinueWatchingContractTest.kt`
- Decision: `VERIFY THEN DELETE` or rewrite
- Why:
  - if planner runtime path is removed, this test suite should not keep dead planner behavior alive

3. `android/contract-tests/src/test/kotlin/com/crispy/tv/contracts/NextEpisodeContractTest.kt`
- Decision: `VERIFY THEN DELETE` or rewrite
- Why:
  - if `FindNextEpisode.kt` is removed from runtime and provider stacks, planner-era next-episode contract coverage should be retired too

## File Decision Summary

### Delete
- `android/core-domain/src/main/kotlin/com/crispy/tv/domain/watch/FindNextEpisode.kt` (after call sites removed)
- `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/cache/WatchHistoryCache.kt` (after legacy users removed)
- `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/RemoteWatchHistoryService.kt` (if unused)
- legacy read APIs in `android/player/src/main/java/com/crispy/tv/player/WatchHistoryService.kt` (`listContinueWatching`, `getCachedContinueWatching`, `ContinueWatchingResult`) after call graph cleanup
- planner-era contract tests if planner paths are retired

### Simplify
- `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`
- `android/app/src/main/java/com/crispy/tv/domain/repository/UserMediaRepository.kt`
- `android/app/src/main/java/com/crispy/tv/data/repository/DefaultUserMediaRepository.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeWatchActivityService.kt`
- `android/app/src/main/java/com/crispy/tv/home/CalendarService.kt`
- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendParsers.kt`
- `android/app/src/main/java/com/crispy/tv/details/EpisodeWatchStateResolver.kt`
- `android/app/src/main/java/com/crispy/tv/watchhistory/WatchSupport.kt`
- `android/app/src/main/java/com/crispy/tv/details/WatchCtaResolver.kt`
- `android/app/src/main/java/com/crispy/tv/home/RecommendationCatalogService.kt`
- `android/app/src/main/java/com/crispy/tv/metadata/MetadataViewMappings.kt`
- `android/app/src/main/java/com/crispy/tv/playerui/PlayerLaunchSnapshot.kt`
- `android/app/src/main/java/com/crispy/tv/playerui/PlayerSessionViewModel.kt`
- `android/app/src/main/java/com/crispy/tv/metadata/tmdb/TmdbMediaDetailsMapper.kt`

### Keep
- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendClient.kt`
- `android/app/src/main/java/com/crispy/tv/home/CalendarScreen.kt`
- `android/core-domain/src/main/kotlin/com/crispy/tv/domain/media/PublicPersonalMediaContract.kt`
- mutation parts of `android/app/src/main/java/com/crispy/tv/details/DetailsUseCases.kt` for now

## Verification Rules For Execution

- Do not run Gradle in this environment.
- Before deleting any file, grep all call sites first.
- Before removing any client-side behavior, verify the corresponding server endpoint already provides the needed truth.
- If a path still supports mutation-only behavior, isolate it rather than mixing it with canonical reads.
- If a file is only used by contract tests, decide whether the contract suite should be retired before deleting the file.

## Success Criteria

- Android does not decide continue-watching membership locally.
- Android does not derive up-next from local progress.
- Android does not merge local watched state into canonical server state.
- Android does not silently drop valid calendar rows due to client semantic filtering.
- Home/details/watch-state behavior is explainable directly from server responses.
- Remaining provider-specific logic is mutation-only, not read-path logic.
