# TMDB-Only Android Migration Plan

Prepared from static code analysis of:
- app repo: `/home/aayush/Downloads/crispy-rewrite`
- server repo: `/home/aayush/Downloads/crispy server`

## Environment Note

- [x] Verified `gradle` is not installed in this environment via `which gradle`.
- [x] Did not run `gradle`.
- [x] Did not run `./gradlew`.
- [ ] Run Android and contract verification later on a machine or CI image that has Gradle installed.

## Goal

Make the Android app use the same canonical identity model the server already uses:
- server canonical identity: TMDB-backed `mediaKey`
- server transitional lookup compatibility: `tmdbId`, and currently `imdbId`
- app canonical runtime identity after migration: `mediaKey` first, TMDB-only fallback where strictly necessary

## Non-Goals

- Do not delete shared multi-ID normalization in `android/core-domain/src/main/kotlin/com/crispy/tv/domain/metadata/MediaIds.kt` yet.
- Do not delete `contracts/fixtures/media_ids`, `contracts/fixtures/id_prefixes`, Android contract tests, or Swift ContractRunner until runtime app code no longer depends on broader ID normalization.
- Do not remove provider-sync fields such as `remoteImdbId` from local progress storage unless Trakt/Simkl sync paths are also being retired.

## Confirmed Baseline

### Server state today

- `src/http/routes/metadata.ts` `registerMetadataRoutes()` accepts `mediaKey`, `tmdbId`, `imdbId`, `mediaType`, `seasonNumber`, and `episodeNumber` for `/v1/metadata/resolve` and `/v1/playback/resolve`.
- `src/modules/metadata/metadata-detail.service.ts` `resolveIdentity()`, `resolveShowTmdbId()`, and `resolveTitleTmdbId()` already canonicalize to TMDB identity.
- `src/modules/metadata/playback-resolve.service.ts` `resolveIdentity()`, `resolveShowTmdbId()`, and `resolveTitleTmdbId()` do the same for playback.
- `src/http/contracts/watch.ts` still exposes `provider`, `providerId`, `parentProvider`, and `parentProviderId` on watch write contracts.
- `src/http/routes/watch.ts` `mapMutationBody()` still maps provider tuple fields, and `parseOptionalProvider()` still parses them.
- Effective provider validation is already TMDB-only because `parseOptionalProvider()` ultimately goes through `ensureSupportedProvider()` in the server identity layer.

### App state today

- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendClient.kt` still exposes generic lookup and write models through `MediaLookupInput`, `WatchMutationInput`, and `PlaybackEventInput`.
- `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt` still uses IMDb and provider tuple fallbacks in:
  - `resolveMediaKey()`
  - `sendPlaybackEvent()`
  - `buildClientEventId()`
  - `progressKeyParts()`
  - `WatchHistoryRequest.toProviderLookupInput()`
  - `buildWatchMutationInput()`
  - `PlaybackIdentity.toPlaybackLookupInput()`
  - `canonicalWatchedEpisodeRecords()`
- `android/player/src/main/java/com/crispy/tv/player/WatchHistoryService.kt` still models legacy identity in `WatchHistoryRequest`, `PlaybackIdentity`, and `CanonicalContinueWatchingItem`.
- `android/app/src/main/java/com/crispy/tv/details/DetailsUseCases.kt` still constructs watch mutations with IMDb and provider tuple fields in `updateEpisodeWatched()` and `buildTitleWatchHistoryRequest()`.
- `android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt` still carries provider tuple identity in `CanonicalContinueWatchingItem.toPlaybackIdentity()` and suppression keys in `continueWatchingContentKey(...)`.
- `android/app/src/main/java/com/crispy/tv/details/WatchCtaResolver.kt` still matches continue-watching entries by `mediaKey`, then provider tuple, then raw `id`.
- `android/app/src/main/java/com/crispy/tv/details/EpisodeWatchStateResolver.kt` and `android/app/src/main/java/com/crispy/tv/watchhistory/WatchSupport.kt` still derive episode watch keys from `details.id` and provider-history fallbacks.
- `android/app/src/main/java/com/crispy/tv/metadata/MetadataViewMappings.kt` and `android/app/src/main/java/com/crispy/tv/playback/StreamLookupSupport.kt` still derive stream lookup IDs from `provider:providerId`.
- `android/app/src/main/java/com/crispy/tv/search/BackendSearchRepository.kt` and `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendParsers.kt` still treat `provider` and `providerId` as required for some backend cards/items.
- `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/progress/WatchProgressStore.kt` still keys local data as `type:id[:episodeId]` and stores `remoteImdbId` in progress JSON.

## Migration Rules

- `mediaKey` becomes the primary identity for app-server traffic, local watch state, continue-watching suppression, episode watched keys, and UI matching.
- `tmdbId` is allowed only as a transitional resolver input when `mediaKey` is not available yet at a call site.
- `contentId`, `imdbId`, `provider`, `providerId`, `parentProvider`, and `parentProviderId` stop being canonical runtime identifiers.
- Any temporary fallback reads for old local state must be migration-only and must not become the new steady-state contract.
- Server cleanup that removes `imdbId` compatibility or provider tuple fields must happen only after the Android app no longer sends or requires them.

## Implementation Order

- [ ] Phase 1: narrow shared app identity models
- [ ] Phase 2: canonicalize backend lookup and write payloads
- [ ] Phase 3: migrate watch-history mutation and playback event flows
- [ ] Phase 4: migrate local storage keys and suppression keys
- [ ] Phase 5: migrate continue-watching, watched CTA, and episode watched-state matching
- [ ] Phase 6: migrate stream lookup IDs away from provider tuples
- [ ] Phase 7: relax parser and UI assumptions about provider/providerId
- [ ] Phase 8: remove server compatibility surface after Android rollout
- [ ] Phase 9: optional cleanup of broader multi-ID code only if product scope truly requires it

## Phase 1: Narrow Shared App Identity Models

### `android/player/src/main/java/com/crispy/tv/player/WatchHistoryService.kt`

- [ ] `data class WatchHistoryRequest`
  End state should represent canonical title or episode identity.
  Keep: `mediaKey`, `contentType`, `season`, `episode`, `title`, `absoluteEpisodeNumber` if still needed.
  Transitional only: TMDB-derived fallback fields if some callers still resolve before they have a `mediaKey`.
  Remove after all call sites migrate: `contentId`, `remoteImdbId`, `provider`, `providerId`, `parentProvider`, `parentProviderId`.

- [ ] `data class PlaybackIdentity`
  Make `mediaKey` the primary identifier for local progress and playback event identity.
  Keep `tmdbId` only if some lookup path still needs a TMDB fallback before `mediaKey` is available.
  Remove after migration: `contentId`, `imdbId`, `provider`, `providerId`, `parentProvider`, `parentProviderId`.

- [ ] `data class CanonicalContinueWatchingItem`
  Verify the backend always supplies `media.mediaKey` for continue-watching rows.
  If confirmed, make the app treat `mediaKey` as required and stop treating provider tuple fields as identity.
  Replace `localKey` semantics so they are derived from canonical identity, not provider tuple fallback.

### Exit criteria

- [ ] All downstream call sites can build watch-history and playback identities without IMDb or provider tuples.
- [ ] No new code depends on `contentId` or provider tuple identity for canonical Android flows.

## Phase 2: Canonicalize Backend Lookup and Write Payloads

### `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendClient.kt`

- [ ] `data class MediaLookupInput`
  Reduce to the fields the server actually needs for canonical resolution.
  Keep: `mediaKey`, `mediaType`, `tmdbId`, `showTmdbId` only if an episode call site still needs it, `seasonNumber`, and `episodeNumber`.
  Remove after call-site migration: `id`, `imdbId`, `tvdbId`, `provider`, `providerId`, `parentProvider`, `parentProviderId`, `absoluteEpisodeNumber` unless a verified server path still consumes it.

- [ ] `data class WatchMutationInput`
  Keep: `mediaKey`, `mediaType`, `seasonNumber`, `episodeNumber`, `absoluteEpisodeNumber` only if backend still requires it, `occurredAt`, `rating`, `payload`.
  Remove: `provider`, `providerId`, `parentProvider`, `parentProviderId` once all callers stop sending them.

- [ ] `data class PlaybackEventInput`
  Keep only canonical identity plus playback timing fields.
  Remove provider tuple fields after `BackendWatchHistoryService.sendPlaybackEvent()` is updated.

- [ ] `internal fun metadataLookupUrl(...)`
  Stop adding query parameters for `id`, `imdbId`, `provider`, `providerId`, `parentProvider`, and `parentProviderId` once all callers are canonicalized.
  End state query shape should match server TMDB-only intent: `mediaKey`, optional `tmdbId`, `mediaType`, `seasonNumber`, `episodeNumber`.

### Exit criteria

- [ ] App lookup and write transport models no longer normalize or synthesize provider tuple query/body fields.
- [ ] No Android caller depends on `metadataLookupUrl(...)` to convert TMDB IDs into fake `provider=tmdb` query parameters.

## Phase 3: Migrate Watch-History Mutation and Playback Event Flows

### `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`

- [ ] `resolveMediaKey(accessToken, request)`
  Replace `request.toProviderLookupInput()` with a canonical lookup builder.
  Lookup priority should be `mediaKey` first, then verified TMDB fallback only if the request genuinely does not have a `mediaKey` yet.

- [ ] `private fun WatchHistoryRequest.toProviderLookupInput()`
  Rename to something canonical such as `toCanonicalLookupInput()`.
  Stop populating `id`, `imdbId`, and provider tuple fields.

- [ ] `buildWatchMutationInput(request, backendContext)`
  Keep the direct `mediaKey` fast path for title mutations.
  For episode mutations, resolve canonical playback identity using `mediaKey` or TMDB-only fallback and stop copying provider tuple fields from the resolved item into the mutation body.

- [ ] `sendPlaybackEvent(identity, positionMs, durationMs, eventType)`
  Send canonical event identity only.
  Remove `provider`, `providerId`, `parentProvider`, and `parentProviderId` from `PlaybackEventInput` creation.

- [ ] `buildClientEventId(identity, eventType)`
  Build the suffix from canonical fields.
  Preferred inputs: `mediaKey`, `season`, `episode`, and only TMDB-derived fallback if `mediaKey` is still missing during the migration window.
  Stop using `contentId`, `imdbId`, and provider tuple values in the event key.

- [ ] `progressKeyParts(identity)`
  Re-key local progress from `contentId` or IMDb to canonical `mediaKey`.
  If a dual-read migration window is required, keep legacy-key reads temporarily but write only canonical keys.

- [ ] `canonicalWatchedEpisodeRecords()`
  Remove the fallback `item.media.providerId` path.
  Episode records should come from canonical media identity only.

- [ ] `PlaybackIdentity.toPlaybackLookupInput()`
  Stop considering provider tuple or IMDb identity sufficient.
  End state should require canonical `mediaKey`, with TMDB fallback only where a caller is still migrating.

### `android/app/src/main/java/com/crispy/tv/details/DetailsUseCases.kt`

- [ ] Title watched mutation path at lines around `330-347`
  Stop calling `ensureImdbId(...)` as a prerequisite for watched mutations once canonical request builders are in place.

- [ ] `updateEpisodeWatched(details, video, desired)`
  Build the request from canonical title and episode identity.
  Stop copying `remoteImdbId`, `provider`, `providerId`, `parentProvider`, and `parentProviderId` into the request.

- [ ] `buildTitleWatchHistoryRequest(details)`
  Stop filling IMDb and provider tuple fields.
  Keep canonical title identity only.

- [ ] `updateRating(details, rating)`
  This path already uses `mediaKey`; remove any unnecessary IMDb enrichment once watched mutations no longer require it.

### `android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`

- [ ] `CanonicalContinueWatchingItem.toPlaybackIdentity()`
  Stop deriving IMDb from `provider == "imdb"`.
  Build playback identity from canonical fields.

### Exit criteria

- [ ] Title watched, episode watched, rating, and playback event writes no longer require IMDb or provider tuple fields.
- [ ] Backend playback resolve is called with canonical inputs only.

## Phase 4: Migrate Local Storage Keys and Suppression Keys

### `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/progress/WatchProgressStore.kt`

- [ ] `getWatchProgressPrefKey(id, type, episodeId)`
  Change the persisted key strategy so `id` represents canonical identity, not provider/IMDb identity.

- [ ] `getContentDurationPrefKey(id, type, episodeId)`
  Apply the same canonical key strategy as watch progress.

- [ ] `buildWpKeyString(id, type, episodeId)`
  Standardize the canonical storage key format used for progress, tombstones, and continue-watching removal markers.

- [ ] `maybeRestoreContinueWatchingVisibility(id, type, episodeId, timestampEpochMs)`
  Update restore/removal matching to use canonical title and episode keys.

- [ ] `mergeWithProviderProgress(...)`
  Decouple provider sync metadata from local storage identity.
  Keep `remoteImdbId` only if Trakt/Simkl sync still needs it.
  Do not let provider sync reintroduce IMDb-based keying.

- [ ] `WatchProgressJson.fromJson(...)` and `WatchProgress.toJson()`
  Preserve backward compatibility for old stored JSON during the migration window.
  If `remoteImdbId` is kept for external sync, it should remain a payload attribute, not the persisted key source.

### `android/app/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`

- [ ] `progressKeyParts(identity)`
  Implement dual-read logic if needed.
  Recommendation: read old keys and new keys during one migration window, but write only new canonical keys.

### `android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`

- [ ] `continueWatchingContentKey(entry)`
  Make `mediaKey` the canonical suppression key.

- [ ] `continueWatchingContentKey(type, provider, providerId)`
  Keep only during the migration window for reading old suppressions.
  Delete after old suppression entries have been migrated or expired.

### Migration strategy decision

- [ ] Implement either a one-time migration job or a dual-read migration window.
- [ ] Recommended: dual-read old keys, write new keys, remove legacy reads after one release cycle.

### Exit criteria

- [ ] Existing users do not lose resume progress during the upgrade.
- [ ] Existing continue-watching suppression entries still work or are safely migrated.

## Phase 5: Continue-Watching, Watch CTA, and Episode Watched-State

### `android/app/src/main/java/com/crispy/tv/details/WatchCtaResolver.kt`

- [ ] `resolveProviderState(details, itemId)`
  Consider renaming to reflect canonical server watch state once provider tuple identity is gone.

- [ ] `resolveContinueWatchingEntry(details, expectedType, nowMs)`
  End state matching should be `mediaKey`-based.
  Provider tuple and raw `id` fallback should exist only during the migration window if needed for old local entries.

- [ ] `ensureImdbId(details)`
  Remove from watched/rating flows once no canonical path requires IMDb enrichment.

### `android/app/src/main/java/com/crispy/tv/details/EpisodeWatchStateResolver.kt`

- [ ] `resolve(details, videos)`
  Build `PlaybackIdentity` for local progress using canonical identity.
  Stop filling provider tuple fields into local progress lookups.

- [ ] `resolveWatchKeys(details)`
  Keep server `watchedEpisodeKeys` as the primary source of truth.
  Remove `listWatchedEpisodeRecords()` fallback once canonical watch-state coverage is verified for all details paths.

### `android/app/src/main/java/com/crispy/tv/watchhistory/WatchSupport.kt`

- [ ] `episodeWatchKeyCandidates(details, season, episode)`
  Rebuild around canonical `mediaKey` or canonical episode key rules.

- [ ] `addEpisodeKey(contentId, season, episode)`
  Replace `contentId` semantics with canonical title identity semantics.

### Exit criteria

- [ ] Details page continue CTA still works after migration.
- [ ] Episode watched badges still work for upgraded users with old local data.
- [ ] Watch CTA logic no longer relies on provider tuple identity.

## Phase 6: Stream Lookup Identity

### Confirm this before implementation

- [ ] Verify what the addon or stream lookup stack expects as its canonical lookup ID.
- [ ] Decide whether the stream stack can consume TMDB-backed `mediaKey` or another TMDB-only canonical form directly.
- [ ] If the stream stack still expects legacy `provider:providerId[:season:episode]`, plan a compatibility bridge instead of a straight deletion.

### `android/app/src/main/java/com/crispy/tv/metadata/MetadataViewMappings.kt`

- [ ] `MetadataEpisodeView.toMediaVideo()`
  Stop building `lookupId` from `canonicalProviderLookupId(parentProvider, parentProviderId)`.
  Replace with a canonical TMDB/mediaKey-based episode lookup ID.

- [ ] `MetadataEpisodePreview.toMediaVideo()`
  Apply the same canonical lookup ID logic.

- [ ] `MetadataCardView.toCatalogItem()`
  Stop requiring provider tuple identity if `mediaKey` is already present.

- [ ] `MediaDetails.providerBaseLookupId()`
  Delete or replace after stream lookup migration is complete.

- [ ] `MetadataView.providerBaseLookupId()`
  Delete or replace after stream lookup migration is complete.

- [ ] `canonicalProviderLookupId(provider, providerId)`
  Delete when no caller remains.

### `android/app/src/main/java/com/crispy/tv/playback/StreamLookupSupport.kt`

- [ ] `resolveStreamLookupTarget(details, selectedSeason, seasonEpisodes, fallbackMediaType)`
  Stop using `details.providerBaseLookupId()`.
  Use canonical title or episode lookup identity instead.

- [ ] `buildEpisodeLookupId(details, season, episode)`
  Rebuild from canonical identity.

- [ ] `findEpisodeForLookupId(...)`
  Verify it still matches against the new canonical episode lookup format.

### Exit criteria

- [ ] Playback stream lookup works without provider/providerId-derived lookup IDs.
- [ ] Details and player episode selection use the same canonical episode lookup identity.

## Phase 7: Relax Parser and UI Requirements Around Provider Tuples

### `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendParsers.kt`

- [ ] `parseMetadataItem(json)`
  Stop throwing when `provider` or `providerId` is missing if `mediaKey` and other UI-required fields are present.

- [ ] `parseMetadataView(json)`
  Keep provider tuple fields optional/passive during migration.
  Do not let them remain required for app logic.

- [ ] `parseMetadataEpisodePreview(json)`
  Keep provider tuple fields optional/passive during migration.

- [ ] `parseMetadataSeasonView(json)`
  Same treatment as above.

- [ ] `parseMetadataEpisodeView(json)`
  Same treatment as above.

- [ ] `parseMetadataExternalIds(json)`
  Keep parsing only as long as server responses still include them or some UI still explicitly needs them.
  Remove `tvdb` and `kitsu` only after no UI/runtime code reads them.

### `android/app/src/main/java/com/crispy/tv/search/BackendSearchRepository.kt`

- [ ] `BackendMetadataItem.toCatalogItem(defaultGenre)`
  Stop returning `null` solely because `provider` or `providerId` is missing.
  Search cards should survive the backend contract cleanup as long as canonical identity and required artwork are present.

### `android/app/src/main/java/com/crispy/tv/home/CalendarService.kt`

- [ ] `toCalendarEpisodeItem(nowMs)`
  Replace provider-based `watchedKey` and `localKey` generation with canonical media or canonical episode keys.

- [ ] `toCalendarSeriesItem()`
  Replace provider-based `localKey` with canonical identity.

- [ ] `projectHomeThisWeekItems(...)`
  Verify grouping still behaves correctly once `localKey` and watched-key semantics become canonical.

### Exit criteria

- [ ] Search and home/calendar UI do not silently drop TMDB-only backend rows.
- [ ] Provider tuple fields can be removed from backend responses without breaking Android rendering.

## Phase 8: Server Cleanup After Android Rollout

Do not start this phase until Android no longer sends or requires the compatibility fields below.

### `~/Downloads/crispy server/src/http/contracts/metadata.ts`

- [ ] `MetadataResolveQuery`
  Remove `imdbId` once Android no longer sends it.

- [ ] `metadataResolveQuerystringSchema`
  Remove `imdbId` from the allowed query shape.

- [ ] `metadataExternalIdsSchema`
  Reduce only after Android parser and UI no longer depend on `imdb` or `tvdb` values.

- [ ] `metadataEpisodePreviewSchema`
  Stop requiring `provider`, `providerId`, `parentProvider`, and `parentProviderId` after Android parser and stream lookup no longer require them.

- [ ] `metadataViewSchema`, `metadataSeasonViewSchema`, and `metadataEpisodeViewSchema`
  Apply the same removal of provider tuple requirements.

### `~/Downloads/crispy server/src/http/routes/metadata.ts`

- [ ] `registerMetadataRoutes()`
  Stop reading and forwarding `imdbId` after app rollout is complete.

### `~/Downloads/crispy server/src/modules/metadata/metadata-detail.service.ts`

- [ ] `resolveIdentity()`
  Keep `mediaKey` as the canonical fast path.

- [ ] `resolveShowTmdbId(client, input)`
  Remove IMDb fallback when no caller relies on it.

- [ ] `resolveTitleTmdbId(client, input, mediaType)`
  Remove IMDb fallback when no caller relies on it.

### `~/Downloads/crispy server/src/modules/metadata/playback-resolve.service.ts`

- [ ] `resolveIdentity()`
  Keep `mediaKey` fast path and remove IMDb-only compatibility branches after Android rollout.

- [ ] `resolveShowTmdbId(client, input)`
  Remove IMDb fallback.

- [ ] `resolveTitleTmdbId(client, input, mediaType)`
  Remove IMDb fallback.

### `~/Downloads/crispy server/src/http/contracts/watch.ts`

- [ ] `WatchEventBody`
  Remove `provider`, `providerId`, `parentProvider`, and `parentProviderId` from the public contract.

- [ ] `WatchMutationBody`
  Remove the same provider tuple fields.

### `~/Downloads/crispy server/src/http/routes/watch.ts`

- [ ] `mapMutationBody(body)`
  Stop mapping provider tuple fields.

- [ ] `parseOptionalProvider(value)`
  Delete when unused.

### Exit criteria

- [ ] Server metadata and playback resolve endpoints no longer accept `imdbId`.
- [ ] Server watch write contracts no longer expose provider tuple compatibility fields.
- [ ] Android app still functions against the simplified server contract.

## Phase 9: Optional Wider Cleanup

Only do this after Phases 1 through 8 are complete and verified.

### App candidates

- [ ] Remove unused `MetadataExternalIds.tvdb` and `MetadataExternalIds.kitsu` in `CrispyBackendClient.kt` if no UI or provider sync path still reads them.
- [ ] Remove leftover multi-provider helpers in `CrispyBackendClient.kt` and `CrispyBackendParsers.kt` once no server response carries them.
- [ ] Remove old provider tuple helpers from `HomeViewModel`, `WatchSupport`, `MetadataViewMappings`, and `StreamLookupSupport` once migration fallbacks are gone.

### Shared contracts and cross-platform candidates

- [ ] Re-evaluate `android/core-domain/src/main/kotlin/com/crispy/tv/domain/metadata/MediaIds.kt`.
- [ ] Re-evaluate `contracts/fixtures/media_ids` and `contracts/fixtures/id_prefixes`.
- [ ] Re-evaluate `android/contract-tests` media-id suites.
- [ ] Re-evaluate `ios/ContractRunner` parity only if product scope truly wants TMDB-only normalization everywhere, not just at the app-server boundary.

## Risks To Actively Guard Against

- [ ] Resume-progress loss caused by changing `WatchProgressStore` keys without a migration path.
- [ ] Continue-watching suppression regressions caused by old provider-based suppression keys no longer matching.
- [ ] Episode watched badge regressions caused by `details.id` and provider-history fallbacks disappearing before canonical watched keys are trusted everywhere.
- [ ] Search result loss caused by `BackendSearchRepository.toCatalogItem()` continuing to reject rows missing provider tuple fields.
- [ ] Stream lookup regressions caused by changing `lookupId` format without confirming addon expectations first.

## Final Acceptance Checklist

- [ ] Title watched mutations work with canonical identity only.
- [ ] Episode watched mutations work with canonical identity only.
- [ ] Rating updates still work and do not require IMDb enrichment.
- [ ] Playback events are emitted without IMDb or provider tuple fields.
- [ ] Local progress survives upgrade for existing users.
- [ ] Continue watching survives upgrade for existing users.
- [ ] Episode watched badges remain correct after migration.
- [ ] Search results still render when backend stops sending provider/providerId.
- [ ] Calendar/home item keys are canonical and stable.
- [ ] Stream lookup works with the new canonical lookup ID format.
- [ ] Android no longer sends `imdbId` to `/v1/metadata/resolve` or `/v1/playback/resolve`.
- [ ] Android no longer sends provider tuple fields to watch write endpoints.
- [ ] Server can remove `imdbId` and provider tuple compatibility fields without breaking the Android app.

## Verification To Run Later When Gradle Exists

Do not run these in the current environment. `gradle` is not installed here.

- [ ] `python3 scripts/validate_contracts.py`
- [ ] `gradle :android:contract-tests:test`
- [ ] `gradle :android:core-domain:test`
- [ ] `gradle :android:app:testDebugUnitTest`
- [ ] `gradle :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:tv:assembleDebug`
- [ ] `gradle :android:app:lintDebug`
- [ ] `gradle :android:tv:lintDebug`
- [ ] `swift test --package-path ios/ContractRunner` if any shared contract behavior changes

## Static Verification Already Completed

- [x] Compared Android identity and watch-history code paths against server metadata and watch contracts.
- [x] Confirmed server canonical identity is TMDB-only while API compatibility still exists at the edge.
- [x] Confirmed the app still has active runtime dependencies on IMDb and provider tuple identity.
- [x] Confirmed `gradle` is not installed, so no Gradle build or test task was run while preparing this plan.
