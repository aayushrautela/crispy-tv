# Jellyfin-First App Migration Plan

## Goal

Move `crispy-rewrite` to the server's Jellyfin-first public media API.

The app must stop treating provider-derived `mediaKey` strings as public routing identity. Normal app flows must carry server `BaseItemDto.Id` as the primary item identity and consume server-owned `BaseItemDto`, `BaseItemDtoQueryResult`, and embedded `UserData` shapes.

## Current server status

Server docs and OpenAPI now show the target shape is mostly ready:

- Public content identity is `PublicItemId`: 32-character lowercase dashless UUID hex.
- Metadata routes use `/v1/metadata/items/{itemId}` and `/v1/metadata/items/{itemId}/extras`.
- Watch/profile routes use `itemId`, `BaseItemDto`, `BaseItemDtoQueryResult`, and embedded `UserData`.
- Search title results are `BaseItemDto[]` buckets.
- Search suggestions have selectable `Id: PublicItemId` plus lightweight hint fields.
- Home uses `/v1/profiles/{profileId}/home` and returns `ProfileHomeResponseEnvelope` with `data.recommendations`.
- Regular and landscape home sections use `BaseItemDto[]`; hero and collection sections remain custom card shapes.

Remaining server-doc caveat:

- `/v1/playback/resolve` accepts `itemId` but its response is still documented as `GenericObject`.

## Migration rules

1. Do not add a provider-id resolver for normal app flow.
2. Do not call TMDB or any provider locally for normal catalog/search/details/home/calendar/watch flows.
3. Treat `BaseItemDto.Id`, `SeriesId`, `SeasonId`, and `UserData.ItemId` as opaque server item IDs.
4. Keep `ProviderIds` as metadata only, not routing identity.
5. Use server `UserData` as the source of watch/favorite/rating/progress state.
6. Episodes are first-class items; playback and watch events use the episode `Id` from `/extras`.
7. Keep addons/player provider-specific work out of this migration unless required to preserve compile/runtime boundaries.

## Phase 1: Contract/spec alignment

Update local contracts before broad app refactors.

### Files

- `contracts/SPEC.md`
- `contracts/fixtures/media_state_contract/`
- `contracts/fixtures/watch_collections_contract/`
- `contracts/fixtures/calendar_contract/`
- `android/contract-tests/`
- `ios/ContractRunner/`

### Work

- Replace the current public `mediaKey` contract with `itemId`/`PublicItemId` semantics.
- Bump `media_state_contract` from v4 to v5.
- Bump `watch_collections_contract` from v3 to v4.
- Bump `calendar_contract` from v3 to v4.
- Use 32-character public item IDs in fixtures.
- Change metadata route expectations from `/v1/metadata/titles/:mediaKey` to `/v1/metadata/items/:itemId`.
- Ensure fixtures model either:
  - post-envelope `data` payloads, if testing app parsers after `requireSuccess`, or
  - full `{ data, meta }` HTTP responses, if testing HTTP contracts.
- Document which level each suite validates.
- Keep Kotlin and Swift contract runners in lockstep.

### Acceptance

- No contract fixture uses provider-derived strings like `movie:tmdb:550` as public item identity.
- No contract expects `/v1/metadata/titles/*`.
- Contract runners parse `BaseItemDto.Id` as `item_id`, not `media_key`.

## Phase 2: Backend client model rename

Make the app's backend client reflect the server contract.

### Files

- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendClient.kt`
- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendParsers.kt`
- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendMetadataApi.kt`
- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendWatchApi.kt`

### Work

- Rename or replace `MediaItem.mediaKey` with `itemId`.
- Rename backend-facing `mediaType` concepts to `itemType` where they represent Jellyfin `Type`.
- Keep a temporary compatibility property only if it prevents a risky one-shot refactor; it must return `itemId` and be removed later.
- Update `SearchSuggestionItem` from stale TMDB fields to server fields:
  - `Id`
  - `Type`
  - `Name`
  - `ProductionYear`
  - `ImageTags`
  - `ProviderIds`
- Preserve tick conversions in one place:
  - server ticks: 100ns units
  - app seconds: UI/player convenience only

### Acceptance

- Backend DTOs no longer expose provider-derived `mediaKey` as the canonical field.
- Search suggestions can navigate using server `Id` directly.
- `ProviderIds` are parsed but never used to build public routes.

## Phase 3: HTTP route migration

Switch all public app API calls to server item routes.

### Metadata

Replace:

- `/v1/metadata/titles/{mediaKey}`
- `/v1/metadata/titles/{mediaKey}/extras`
- `/v1/profiles/{profileId}/metadata/titles/{mediaKey}/ratings`
- `/v1/metadata/resolve?...provider lookup...`

With:

- `/v1/metadata/items/{itemId}`
- `/v1/metadata/items/{itemId}/extras`
- `/v1/profiles/{profileId}/metadata/items/{itemId}/ratings`
- no normal-flow resolve call

### Watch/profile

Replace request fields and query params:

- `mediaKey` -> `itemId`
- provider media fields removed from watch event/mutation bodies

Update:

- watch events
- watch state lookup
- batch watch state lookup
- mark/unmark watched
- watchlist
- rating
- continue watching dismissal/removal

### Home

Replace:

- `/v1/profiles/{profileId}/recommendations`

With:

- `/v1/profiles/{profileId}/home`

Parse:

- `data.recommendations == null` as no home snapshot yet
- `sections[].items` as `BaseItemDto[]` for `regular` and `landscape`
- `hero` and `collection` as custom card sections

### Acceptance

- Grep finds no app runtime call to `/v1/metadata/titles`.
- Grep finds no app runtime call to `/v1/profiles/{profileId}/recommendations`.
- Watch requests send `itemId`, not `mediaKey`.
- Normal app flow does not call `/v1/metadata/resolve`.

## Phase 4: App navigation and UI identity

Carry server item IDs through UI and app state.

### Files

- `android/app/src/main/java/com/crispy/tv/catalog/`
- `android/app/src/main/java/com/crispy/tv/home/`
- `android/app/src/main/java/com/crispy/tv/search/`
- `android/app/src/main/java/com/crispy/tv/details/`
- `android/app/src/main/java/com/crispy/tv/metadata/`
- `android/app/src/main/java/com/crispy/tv/playerui/`
- `android/player/src/main/java/com/crispy/tv/player/WatchHistoryService.kt`

### Work

- Rename UI item fields from `mediaKey` to `itemId` where they represent server identity.
- Details screens load by title item ID.
- Search results and suggestions navigate by item ID.
- Home/catalog/cache rows store item ID as identity.
- Calendar item identity uses episode `Id`; series grouping uses `SeriesId`.
- Episode lists preserve episode `Id`, `SeasonId`, and `SeriesId` from `/extras`.
- Player launch state must receive playable item ID for movie/episode.
- Continue watching uses `UserData.ItemId` or item `Id`; do not synthesize episode keys.

### Acceptance

- App does not construct `episode:tmdb:{showId}:{season}:{episode}` for server calls.
- Details/player/watch paths can start from `BaseItemDto.Id` without provider lookup.
- Provider IDs are display/debug metadata only.

## Phase 5: UserData-native watch state

Use Jellyfin-style `UserData` everywhere for profile state.

### Work

- Continue watching progress from:
  - `UserData.PlaybackPositionTicks`
  - `UserData.RuntimeTicks`
  - `UserData.PlayedPercentage`
  - `UserData.LastPlayedDate`
- Watched state from:
  - `UserData.Played`
  - `UserData.PlayCount`
  - `UserData.LastPlayedDate`
- Watchlist/favorite from:
  - `UserData.IsFavorite`
- Ratings from:
  - `UserData.Rating`
- Dismissal from:
  - `UserData.DismissedFromContinueWatching`

### Acceptance

- Watch history service does not need separate provider-derived canonical state for normal backend items.
- UI state updates are based on refreshed `BaseItemDto.UserData` or item responses from mutation endpoints.

## Phase 6: Cleanup

Remove obsolete provider-routing code from normal app paths.

### Remove or quarantine

- `metadataLookupUrl` for public media routes.
- Synthetic episode key building for server API calls.
- `MediaLookupInput` for normal app metadata/watch flow.
- TMDB fallback in details/search/home normal paths.
- Stale `mediaKey` naming where the value is actually server item ID.

### Keep for later work only

- Addon/player provider integrations that are not part of normal Jellyfin-first catalog/search/details/watch flow.
- Provider metadata clients if still needed for isolated tooling or future migrations, but not wired into normal user flow.

## Phase 7: Verification

Run after contract/spec changes:

```sh
python3 scripts/validate_contracts.py
gradle :android:contract-tests:test
swift test --package-path ios/ContractRunner
```

Run after Android app code changes:

```sh
gradle :android:app:testDebugUnitTest
gradle :android:core-domain:test
gradle :android:app:lintDebug
```

Run before broad integration completion:

```sh
gradle :android:app:assembleDebug :android:app:assembleDebugAndroidTest :android:tv:assembleDebug
```

## Done criteria

- All normal public media routes use `itemId`.
- App catalog/search/home/calendar/watch/detail surfaces consume `BaseItemDto` or documented custom home card shapes.
- No normal-flow local provider lookup is required before opening details or sending watch state.
- Search suggestions are selectable by public item ID.
- Home uses `/v1/profiles/{profileId}/home`.
- Watch events and mutations send playable item ID.
- Contracts, Android, and Swift agree on the Jellyfin-first spec.
