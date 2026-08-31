# Artwork Field Simplification Plan

## Goal
Replace separate `poster` + `backdrop` image fields with a single `artwork` field across the entire stack (server → contract → app). The app always prefers backdrop and only falls back to poster, so a single merged field simplifies the data model with no behavioral change.

## Current State Analysis

### App Side (Android)
Every image display uses `backdrop ?: poster` chain. Poster is **only** a fallback:

| File | Current Chain |
|------|---------------|
| `LandscapeCard.kt:67` | `backdrop?.low ?: poster?.low ?: backdropUrl ?: posterUrl` |
| `DetailsHero.kt:85` | `details?.backdropUrl ?: details?.posterUrl` |
| `LibraryRoute.kt:210` | `item.backdropUrl ?: item.posterUrl` |
| `HomeViewModel.kt:379,393` | `stillUrl ?: backdropUrl ?: posterUrl` |
| `PlayerSessionViewModel.kt:719` | `artworkUrl ?: details.backdropUrl ?: details.posterUrl` |
| `StreamSelectorContent.kt:237` | `details?.backdropUrl ?: details?.posterUrl` |
| `CalendarService.kt:255-257` | stores both `posterUrl` and `backdropUrl` |
| `UpNextService.kt:54-55` | stores both |
| `CatalogMappings.kt:12-13` | **requires** poster (rejects item if no poster) — this is the odd one |
| `AiInsightsStoryOverlay.kt:852` | backdrop → poster fallback |

### App Models with poster/backdrop
- `CatalogItem` (`CatalogModels.kt:29-52`) — `posterUrl`, `backdropUrl`, `poster`, `backdrop`
- `HomeCatalogItem` (`HomeCatalogPlanner.kt:5-20`) — `posterUrl`, `backdropUrl`, `poster`, `backdrop`
- `CanonicalContinueWatchingItem` (`WatchHistoryService.kt:17-38`) — `posterUrl`, `backdropUrl`
- `MediaDetails` (`MediaModels.kt:3-27`) — `posterUrl`, `backdropUrl`
- `MetaDetails` / `SeriesMetaEpisodes` (`CalendarMetaEpisodeService.kt:282-296`) — `posterUrl`, `backdropUrl`
- `CalendarEpisodeItem` / `CalendarSeriesItem` (`CalendarService.kt:29-43`) — `posterUrl`, `backdropUrl`
- `ClientMediaCard` via `ClientImages` (`CrispyBackendClient.kt:187-192`) — `poster`, `backdrop`, `logo`, `still`
- `PlayerUiState` (`PlayerSessionViewModel.kt:78-112`) — `backdropUrl`, `artworkUrl`

### TV App
- `DetailViewModel.kt:403-406` — `images.backdrop.large ?: ... ?: images.poster.medium`
- `HomeViewModel.kt:179` — `show?.images?.backdrop?.medium ?: show?.images?.poster?.medium`
- `CrispyCardItem` (`CrispyLandscapeCard.kt:42-55`) — already has single `imageUrl` (good pattern)

### Server Side
- `ClientImages` type (`client-home.types.ts:6`) — `poster`, `backdrop`, `logo`
- `toClientMediaCard` (`client-media-card.mapper.ts:37-42`) — maps all three
- `buildMetadataImages` (`metadata-builder.shared.ts:625-636`) — builds poster/backdrop separately
- `metadata-card.types.ts` — `MetadataArtwork`, `MetadataImages`, `RegularCardView` (poster-only), `LandscapeCardView` (poster+backdrop)
- `metadata-card.builders.ts:34-38` — `toCatalogItem` **requires** poster
- `metadata.ts:200-203` — search filters cards with poster (`hasSearchPoster`)
- DB: `poster_path` column in `tmdb.repo.ts` (internal storage, keep)

### Contracts
- `clientImagesSchema` (`shared.ts:294-304`) — requires `poster`, `backdrop`, `logo`
- `metadataArtworkSchema` (`shared.ts:406-415`) — requires `poster`, `backdrop`, `still`
- `metadataImagesSchema` (`shared.ts:417-427`) — requires all four
- Contract fixtures `home_catalogs/v7/*.json` — items have `poster_url` + `backdrop_url`
- `HomeCatalogsContractTest.kt:104-105` — parses both `poster_url` and `backdrop_url`

### Exceptions — Keep Separate
- **Person profile images** (`SearchNavGraph.kt:15`, `SearchRepositoryModels.kt:31`, `PersonDetailsRoute.kt:219`) — `profileUrl` is a person's face, NOT a media poster. Keep as-is.
- **Logo** — separate concept, keep as-is.
- **Still** (episode thumbnails) — separate concept, keep as-is.

## New Field Design

### Server Response
Replace `images: { poster, backdrop, logo, still }` with:
```
images: { artwork, logo, still }
```
Where `artwork` is built as `backdrop ?? poster` per size:
```typescript
artwork: {
  small: backdrop.small ?? poster.small,
  medium: backdrop.medium ?? poster.medium,
  large: backdrop.large ?? poster.large,
}
```

### App Models
Replace `posterUrl` + `backdropUrl` + `poster` + `backdrop` with single:
- `artworkUrl: String?` (for simple URL passing)
- `artwork: ResponsiveImageSet?` (for responsive images)

### Contract
- `clientImagesSchema`: `artwork` instead of `poster` + `backdrop`
- Fixtures: single `artwork_url` per item
- Bump `contract_version` to 8

## Implementation Steps

### Phase 1: Server
1. Update `buildMetadataImages` to merge poster into backdrop, output single `artwork` set
2. Update `ClientImages` type — replace `poster`/`backdrop` with `artwork`
3. Update `toClientMediaCard` — map `artwork: view.images.backdrop ?? view.images.poster`
4. Update `metadata-card.types.ts` — `MetadataArtwork` → single `artwork` field
5. Update `metadata-card.builders.ts` — `toCatalogItem` requires `artwork` (not poster)
6. Update `metadata.ts` search filter — `hasSearchArtwork` instead of `hasSearchPoster`
7. Update `clientImagesSchema` in `shared.ts`
8. Keep `poster_path` DB column (internal), just don't expose as wire field

### Phase 2: Contracts
1. Update `clientImagesSchema` — `artwork` (required) + `logo` + `still`
2. Update `metadataArtworkSchema` / `metadataImagesSchema`
3. Update all fixture files — replace `poster_url`/`backdrop_url` with `artwork_url`
4. Bump `contract_version` to 8 in fixtures
5. Update `HomeCatalogsContractTest.kt` — parse `artwork_url` only

### Phase 3: Android App
1. `CrispyBackendClient.kt` — `ClientImages.artwork` instead of `poster`/`backdrop`
2. `CrispyBackendParsers.kt` — parse `artwork` from response
3. `CatalogMappings.kt` — use `images.artwork.medium`, require artwork (not poster)
4. `CatalogModels.kt` — `CatalogItem.artworkUrl` + `artwork`
5. `HomeCatalogPlanner.kt` — `HomeCatalogItem.artworkUrl` + `artwork`
6. `HomeCatalogService.kt` — mapping + cache serialization
7. `LandscapeCard.kt` — single `artworkUrl`/`artwork` param, no poster fallback
8. `DetailsHero.kt` — `details.artworkUrl` only
9. `DetailsScreen.kt` — use `artworkUrl`
10. `LibraryRoute.kt` / `LibraryPagingSource.kt` — single artwork
11. `HomeViewModel.kt` — `stillUrl ?: artworkUrl`
12. `CalendarService.kt` / `CalendarMetaEpisodeService.kt` — single artwork
13. `UpNextService.kt` — single artwork
14. `MediaModels.kt` — `MediaDetails.artworkUrl`
15. `PlayerSessionViewModel.kt` — `artworkUrl` only
16. `StreamSelectorContent.kt` — single artwork
17. `AiInsightsStoryOverlay.kt` — single artwork
18. `HomeSelectorViewModel.kt` — single artwork
19. All nav graphs + screens passing poster → pass artwork
20. Remove poster-related params from `LandscapeCard`, `HomeCatalogPosterCard`, etc.

### Phase 4: TV App
1. `DetailViewModel.kt` — `images.artwork.large ?: ...`
2. `HomeViewModel.kt` — `show?.images?.artwork?.medium`

### Phase 5: Contract Tests (Kotlin + Swift)
1. Update `HomeCatalogsContractTest.kt` — parse `artwork_url`
2. Update Swift `ContractRunner` equivalent if it parses these fields
3. Run `python3 scripts/validate_contracts.py`
4. Run `gradle :android:contract-tests:test`
5. Run `swift test --package-path ios/ContractRunner`

### Phase 6: Cleanup
1. Remove all `poster` fields from models (except person `profileUrl`)
2. Remove poster fallback chains — single `artwork` reference
3. Remove `poster` from cache JSON serialization
4. Verify no dormant poster references remain

## Files to Modify

### Server (14 files)
- `src/modules/metadata/metadata-builder.shared.ts`
- `src/modules/metadata/metadata-card.builders.ts`
- `src/modules/metadata/metadata-card.types.ts`
- `src/modules/metadata/client-media-card.mapper.ts`
- `src/modules/recommendations/client-home.types.ts`
- `src/http/contracts/shared.ts`
- `src/http/routes/metadata.ts`
- `src/modules/calendar/calendar-builder.service.ts`
- `src/modules/metadata/metadata-projection.service.ts`
- `src/modules/watch/watch.types.ts`
- `src/http/contracts/account-public.ts`
- `src/modules/integrations/mdblist.types.ts`
- `src/modules/integrations/media-ref.types.ts`
- `src/modules/ai/ai-search.service.ts`

### Contracts (5 fixture files + schemas)
- `contracts/fixtures/home_catalogs/v7/*.json` (5 files)
- `contracts/schemas/*.json` (if separate from shared.ts)

### Android App (25+ files)
- `android/backend/src/main/kotlin/com/crispy/tv/backend/CrispyBackendClient.kt`
- `android/backend/src/main/kotlin/com/crispy/tv/backend/CrispyBackendParsers.kt`
- `android/app/src/main/java/com/crispy/tv/catalog/CatalogMappings.kt`
- `android/app/src/main/java/com/crispy/tv/catalog/CatalogModels.kt`
- `android/app/src/main/java/com/crispy/tv/catalog/CatalogScreen.kt`
- `android/app/src/main/java/com/crispy/tv/details/AiInsightsStoryOverlay.kt`
- `android/app/src/main/java/com/crispy/tv/details/DetailsHero.kt`
- `android/app/src/main/java/com/crispy/tv/details/DetailsScreen.kt`
- `android/app/src/main/java/com/crispy/tv/discover/DiscoverScreen.kt`
- `android/app/src/main/java/com/crispy/tv/home/CalendarMetaEpisodeService.kt`
- `android/app/src/main/java/com/crispy/tv/home/CalendarService.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeCalendarComponents.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeCatalogComponents.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeCatalogService.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeSelectorViewModel.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`
- `android/app/src/main/java/com/crispy/tv/home/UpNextService.kt`
- `android/app/src/main/java/com/crispy/tv/library/LibraryPagingSource.kt`
- `android/app/src/main/java/com/crispy/tv/library/LibraryRoute.kt`
- `android/app/src/main/java/com/crispy/tv/library/LibraryScreen.kt`
- `android/app/src/main/java/com/crispy/tv/playerui/PlayerSessionViewModel.kt`
- `android/app/src/main/java/com/crispy/tv/playerui/PlayerRoute.kt`
- `android/app/src/main/java/com/crispy/tv/playerui/PlayerOverlay.kt`
- `android/app/src/main/java/com/crispy/tv/streams/StreamSelectorContent.kt`
- `android/app/src/main/java/com/crispy/tv/ui/components/LandscapeCard.kt`
- `android/app/src/main/java/com/crispy/tv/core-domain/.../HomeCatalogPlanner.kt`
- `android/player/src/main/java/com/crispy/tv/player/WatchHistoryService.kt`
- `android/addons/src/main/kotlin/com/crispy/tv/addons/model/MediaModels.kt`
- `android/addons/src/main/kotlin/com/crispy/tv/addons/mapping/MediaDetailMappings.kt`
- `android/watchhistory/src/main/java/com/crispy/tv/watchhistory/BackendWatchHistoryService.kt`

### TV App (2 files)
- `android/tv/src/main/java/com/crispy/tv/tv/home/HomeViewModel.kt`
- `android/tv/src/main/java/com/crispy/tv/tv/ui/screens/detail/DetailViewModel.kt`

### Contract Tests
- `android/contract-tests/src/test/kotlin/com/crispy/tv/contracts/HomeCatalogsContractTest.kt`
- Swift `ContractRunner` (if applicable)

## Verification
1. `python3 scripts/validate_contracts.py` — contracts valid
2. `gradle :android:contract-tests:test` — Kotlin contract tests pass
3. `swift test --package-path ios/ContractRunner` — Swift contract tests pass
4. `gradle :android:app:assembleDebug` — app compiles
5. `gradle :android:tv:assembleDebug` — TV compiles
6. Search for remaining `poster` references — only person `profileUrl` should remain
