# Android Legacy Cleanout Plan

## Goal
Migrate all Android code to use only `ClientMediaCard` — delete all legacy card types.
The server already returns `ClientMediaCard` for every metadata endpoint.

## Verified Server Response Shapes (all lowercase camelCase)

### 1. Show Detail (`GET /v1/metadata/items/:itemId`)
```json
{
  "Item": { "itemId": "...", "mediaType": "...", "title": "...", "images": {...}, "progress": {...}, "parent": {...}, "providerIds": {...} },
  "NextEpisode": { "itemId": "...", ... } | null,
  "Videos": [...], "Cast": [...], "Directors": [...], "Creators": [...], "Production": {...}
}
```

### 2. Extras (`GET /v1/metadata/items/:itemId/extras`)
```json
{
  "Seasons": [ { "itemId": "...", ... } ],
  "Reviews": [...],
  "Similar": [ { "itemId": "...", ... } ],
  "Collection": { "Items": [ { "itemId": "...", ... } ], "StartIndex": 0, "TotalRecordCount": N, "NextCursor": null, "HasMore": false } | null
}
```

### 3. Series Episodes (`GET /v1/metadata/shows/:itemId/episodes`)
```json
{ "Items": [ { "itemId": "...", ... } ], "StartIndex": 0, "TotalRecordCount": N, "NextCursor": null, "HasMore": false, "Creators": [...] }
```

### 4. Playback Resolve (`GET /v1/playback/resolve`)
```json
{ "Item": { "itemId": "...", ... }, "Show": { "itemId": "...", ... } | null, "Season": { "itemId": "...", ... } | null }
```

### 5. Person Detail (`GET /v1/metadata/people/:itemId`)
```json
{ "personId": "...", "knownFor": [ { "itemId": "...", ... } ] }
```

### 6. Search (`GET /v1/search`)
```json
{ "query": "...", "movies": [ { "itemId": "...", ... } ], "series": [ { "itemId": "...", ... } ], "people": [...] }
```

### 7. Calendar (`GET /v1/profiles/:profileId/calendar`)
```json
{ "items": [ { "itemId": "...", "mediaType": "episode", "title": "...", "airDate": "...", "bucket": "...", "images": {...}, "parent": {...} } ] }
```

### 8. Episodic Follow (`GET /v1/profiles/:profileId/watch/episodic-follow`)
```json
{ "items": [ { "show": { "itemId": "...", ... }, "nextEpisode": { "itemId": "...", ... } | null, "nextEpisodeAirDate": "...", "lastInteractedAt": "...", "reason": "..." } ] }
```

---

## Server ↔ Android Verification

| Endpoint | Server JSON keys | Android Parse (edited) | Match? |
|---|---|---|---|
| Show detail `Item` | `itemId`, `mediaType`, `title`, `overview`, `year`, `releaseDate`, `rating`, `maturityRating`, `genres`, `runtimeSeconds`, `images`, `progress`, `parent`, `providerIds` | `parseClientMediaCard` | ✅ |
| Show detail `NextEpisode` | same as Item | `parseClientMediaCard` | ✅ |
| Extras `Seasons` | array of ClientMediaCard | `parseClientMediaCards` | ✅ |
| Extras `Similar` | array of ClientMediaCard | `parseClientMediaCards` | ✅ |
| Extras `Collection` | `ClientMediaCardQueryResult \| null` (with `Items` key) | `json.optJSONObject("Collection")?.optJSONArray("Items")` | ✅ |
| Episodes `Items` | array of ClientMediaCard | `parseClientMediaCard` | ✅ |
| Playback `Item`/`Show`/`Season` | `ClientMediaCard` / `\| null` | `parseClientMediaCard` / `.let(::parseClientMediaCard)` | ✅ |
| Person `knownFor` | array of ClientMediaCard | `parseClientMediaCards` | ✅ |
| Search `movies`/`series` | array of ClientMediaCard | `parseClientMediaCards` | ✅ |
| Calendar `items` | `CalendarItemDto` (ClientMediaCard + airDate/bucket) | `parseClientMediaCards` | ✅ |
| Episodic-follow `items` | `{show: ClientMediaCard, nextEpisode: ClientMediaCard\|null, nextEpisodeAirDate, lastInteractedAt, reason}` | `parseUpNextItems` (updated) | ✅ |

**All parser changes match server response shapes.**

---

## What Changed (Done)

### Completed
1. **`UpNextItem`** → `show: ClientMediaCard? + nextEpisode: ClientMediaCard?` — deleted `nextEpisodeTitle`, `showItemId`, `showTitle`, `showPosterUrl`, `showBackdropUrl`, `nextEpisodeItemId`, `nextEpisodeSeasonNumber`, `nextEpisodeEpisodeNumber`
2. **`parseUpNextItems`** → parses `show` + `nextEpisode` as `ClientMediaCard`
3. **`MetadataTitleDetailResponse`** → `item: ClientMediaCard`, `nextEpisode: ClientMediaCard?`
4. **`MetadataTitleExtrasResponse`** → `seasons/similar/collection: List<ClientMediaCard>`
5. **`MetadataSeriesEpisodesResponse`** → `items: List<ClientMediaCard>`
6. **`PlaybackResolveResponse`** → `item/show/season: ClientMediaCard`
7. **`MetadataPersonDetail`** → `knownFor: List<ClientMediaCard>`
8. **`SearchResultsResponse`** → `movies/series: List<ClientMediaCard>`
9. **`CalendarResponse`** → `items: List<ClientMediaCard>` ← **CHANGED to ClientMediaCard**

### Not Changed (still legacy)
- `CalendarService` — still parses calendar as `MediaItem` → needs update
- All consumer/view code that uses legacy types

---

## Remaining Work

### Phase 1: Legacy Data Class Deletion (backend/src)
Delete from `CrispyBackendClient.kt`:
- `MetadataView` (line 382)
- `MetadataSeasonView` (line 411)
- `MetadataEpisodeView` (line 424)
- `MetadataEpisodePreview` (line 364)
- `MetadataCardView` (line 468)
- `MetadataCollectionView` (line 533)
- `MediaItem` (line ~126)
- `MetadataImages` (only `MetadataImagesDto` remains — shared)

Delete from `CrispyBackendParsers.kt`:
- `parseMetadataView` (line 564)
- `parseMetadataCardView` (line ~633)
- `parseMetadataSeasonView` (line ~664)
- `parseMetadataEpisodeView` (line ~693)
- `parseMetadataCollectionView` (line ~838)
- `parseMediaItem` (line ~281)
- `parseMediaItems` (line ~271)
- `parseCalendarItems` (line ~481)

Delete unused imports from `CrispyBackendParsers.kt` + `CrispyBackendMetadataApi.kt`

### Phase 2: Consumer Updates
Files that consume legacy types — update each to use `ClientMediaCard`:

**app/catalog/CatalogMappings.kt**
- `MetadataCardView.toCatalogItem()` → `ClientMediaCard.toCatalogItem()`
- `MediaItem.toCatalogItem()` → `ClientMediaCard.toCatalogItem()`

**app/details/DetailsBody.kt**
- `collection` (was `MetadataCollectionView`) → `List<ClientMediaCard>`
- `similar` (was `List<MetadataCardView>`) → `List<ClientMediaCard>`
- `seasons` (was `List<MetadataSeasonView>`) → `List<ClientMediaCard>`

**app/details/DetailsState.kt**
- `titleExtras` → `MetadataTitleExtrasResponse` (already updated types)

**app/details/DetailsUseCases.kt**
- `MetadataView::toMediaVideo` → `ClientMediaCard.toMediaVideo()` (or delete)
- `media` reference in detail flow → `ClientMediaCard`

**app/home/CalendarService.kt**
- `List<MediaItem>.toCalendarSections` → `List<ClientMediaCard>.toCalendarSections`
- `MediaItem.toCalendarEpisodeItem` → `ClientMediaCard.toCalendarEpisodeItem`
- `MediaItem.toCalendarSeriesItem` → `ClientMediaCard.toCalendarSeriesItem`

**app/search/BackendSearchRepository.kt**
- `MediaItem.toCatalogItem` → `ClientMediaCard.toCatalogItem`

**tv/ui/screens/detail/DetailViewModel.kt**
- `val seasons: List<MetadataSeasonView>` → `List<ClientMediaCard>`
- `val similar: List<CrispyCardItem>` → from `List<ClientMediaCard>`
- `val collectionItems: List<CrispyCardItem>` → from `List<ClientMediaCard>`
- `MetadataCardView.toCardItem` → `ClientMediaCard.toCardItem`

**tv/home/HomeViewModel.kt**
- `MediaItem.toCardItem` → `ClientMediaCard.toCardItem` (for calendar section)

**addons/mapping/MediaDetailMappings.kt**
- `MetadataView.toMediaDetails` → `ClientMediaCard.toMediaDetails()` (or inline)
- `MetadataView.toMediaVideo` → `ClientMediaCard.toMediaVideo()` (or delete)
- `MetadataEpisodeView.toMediaVideo` → delete
- `MetadataEpisodePreview.toMediaVideo` → delete
- `MetadataCardView.normalizedCatalogMediaType` → `ClientMediaCard.normalizedCatalogMediaType`

**native-engine/NativePlaybackController.kt**
- `PlaybackResolveResponse.Season` → `ClientMediaCard?` (was `MetadataSeasonView?`)

**native-engine/PlaybackMediaItems.kt**
- Same pattern — check and update

**tv/player/TvPlayerViewModel.kt**
- Any `MetadataView`/`MediaItem` usage → `ClientMediaCard`

**playerui/PlaybackSessionControllerPlayer.kt**
- Any `MediaItem` usage → `ClientMediaCard`

**tv/ui/screens/detail/TvHeroTrailer.kt**
- Any `MetadataView`/`MediaItem` usage → `ClientMediaCard`

### Phase 3: MediaStateContract Deletion
- Delete `core-domain/.../media/MediaStateContract.kt`
- Delete `contract-tests/.../MediaStateContractTest.kt`
- Delete `contract-tests/.../WatchCollectionsContractTest.kt` (if only tests old shape)
- Delete `normalizeBaseItemDto` + `normalizeBaseItemDtoQueryResult` from `PublicPersonalMediaContract.kt`

### Phase 4: iOS
- Verify iOS does NOT need changes (it reads raw JSON, no model dependency on old shape)
- If iOS has legacy models: delete

---

## Risks
1. **MediaItem has 47 consumers** — must touch each one; risk of missing one
2. **MetadataView has 22 consumers** — same risk
3. **MediaDetailMappings** has complex mappings — must map field-by-field carefully
4. **CalendarService** — `CalendarItemDto` has `airDate`/`bucket` which ClientMediaCard doesn't have — need to handle lost fields
5. **No compile check possible** — all changes are read-only verification

## Verification (no compilation)
- Search for remaining references after each phase
- Ensure no type name references exist in consumer files
- Ensure imports are removed
