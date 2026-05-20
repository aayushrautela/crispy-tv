# Jellyfin-First Migration Spec

## Scope

This spec defines the target app contract for migrating `crispy-rewrite` to the server's Jellyfin-first public media API.

It applies to normal app flows:

- home
- catalog rails
- search results
- search suggestions
- metadata details
- seasons and episodes
- calendar
- continue watching
- watch history
- watchlist/favorites
- ratings
- playback progress/watch events

It does not complete addon/provider-specific player work. Those flows must not block the normal app migration.

## Source of truth

The server public API is the source of truth for app media data.

Relevant server docs:

- `/home/aayush/Downloads/crispy server/docs/client-app-migration.md`
- `/home/aayush/Downloads/crispy server/docs/specs/jellyfin-style-unified-item-dto.md`
- `/home/aayush/Downloads/crispy server/docs/specs/identity-v2-spec.md`
- `/home/aayush/Downloads/crispy server/docs/api/recommendations.md`
- `/home/aayush/Downloads/crispy server/openapi/public-app.v1.yaml`

## Identity

### Public item ID

All public content identity is a `PublicItemId`.

Format:

```text
^[0-9a-f]{32}$
```

Fields that carry public item IDs:

- `BaseItemDto.Id`
- `BaseItemDto.SeriesId`
- `BaseItemDto.SeasonId`
- `UserData.ItemId`
- route/query/body field `itemId`
- batch field `itemIds`
- home hero card `itemId`
- selectable search suggestion `Id`

### Rules

- App code must treat item IDs as opaque strings.
- App code must not parse item IDs for provider, media type, season, episode, or title data.
- Provider IDs are metadata only and live under `ProviderIds`.
- Provider-derived strings like `movie:tmdb:550` are not public app route identity.
- App UI may keep legacy field names temporarily only as aliases to item IDs; new code must use `itemId`.

## Canonical media item

The canonical media object is `BaseItemDto`.

Required public shape:

```ts
type BaseItemDto = {
  Id: PublicItemId;
  Type: 'Movie' | 'Series' | 'Season' | 'Episode' | 'Unknown';
  Name: string;
  OriginalTitle: string | null;
  Overview: string | null;
  Taglines: string[];
  ProductionYear: number | null;
  PremiereDate: string | null;
  CommunityRating: number | null;
  OfficialRating: string | null;
  Certification: string | null;
  Genres: string[];
  RunTimeTicks: number | null;
  Status: string | null;
  ProviderIds: Record<string, string | null>;
  ImageTags: BaseItemImageTags;
  ParentImageTags: ParentBaseItemImageTags | null;
  SeriesId: PublicItemId | null;
  SeriesName: string | null;
  SeasonId: PublicItemId | null;
  SeasonName: string | null;
  ParentIndexNumber: number | null;
  IndexNumber: number | null;
  AbsoluteIndexNumber: number | null;
  EpisodeTitle: string | null;
  AirDate: string | null;
  RemoteTrailers: RemoteTrailer[];
  PosterColor: string | null;
  BackdropColor: string | null;
  UserData: UserItemDataDto | null;
};
```

App internal models may normalize casing for Kotlin/Swift style, but must preserve the same semantics.

## User data

User state is embedded in `BaseItemDto.UserData`.

```ts
type UserItemDataDto = {
  ItemId: PublicItemId;
  IsFavorite: boolean;
  Played: boolean;
  PlayCount: number;
  PlaybackPositionTicks: number | null;
  RuntimeTicks: number | null;
  PlayedPercentage: number | null;
  LastPlayedDate: string | null;
  Rating: number | null;
  DismissedFromContinueWatching: boolean;
};
```

Rules:

- Continue watching derives from `PlaybackPositionTicks`, `RuntimeTicks`, `PlayedPercentage`, `LastPlayedDate`, and `DismissedFromContinueWatching`.
- Watched history derives from `Played`, `PlayCount`, and `LastPlayedDate`.
- Watchlist/favorite state derives from `IsFavorite`.
- Rating derives from `Rating`.
- App code converts ticks to seconds only at UI/player boundaries.
- API payloads use the server's expected seconds/ticks fields exactly as documented per endpoint.

## List shape

Media lists use `BaseItemDtoQueryResult` unless an endpoint explicitly defines a direct array.

```ts
type BaseItemDtoQueryResult = {
  Items: BaseItemDto[];
  StartIndex: number;
  TotalRecordCount: number;
  NextCursor: string | null;
  HasMore: boolean;
};
```

Runtime app client behavior:

- HTTP success responses are enveloped as `{ data, meta }`.
- `requireSuccess` unwraps `data` before endpoint parsers run.
- Contract fixtures must explicitly state whether they validate the full HTTP envelope or post-envelope `data` payload.

## Endpoint contracts

### Metadata detail

Use:

```http
GET /v1/metadata/items/{itemId}
```

Response data:

```ts
type MetadataTitleDetailResponse = {
  Item: BaseItemDto;
  NextEpisode: BaseItemDto | null;
  Videos: unknown[];
  Cast: unknown[];
  Directors: unknown[];
  Creators: unknown[];
  Production: unknown;
};
```

Rules:

- Details screens load by title item ID.
- Movie details use movie `Id`.
- Series details use series `Id`.
- Episode detail, if needed, uses episode `Id`.

### Metadata extras

Use:

```http
GET /v1/metadata/items/{itemId}/extras
```

Response data:

```ts
type MetadataTitleExtrasResponse = {
  Seasons: BaseItemDto[];
  Episodes: BaseItemDto[];
  Reviews: unknown[];
  Similar: BaseItemDto[];
  Collection: BaseItemDtoQueryResult | null;
};
```

Rules:

- Episode rows must preserve episode `Id`.
- Player/watch events for episodes use episode `Id`, not a synthetic key.
- Series grouping uses `SeriesId`.
- Season grouping uses `SeasonId` and `ParentIndexNumber`.

### Search titles

Use:

```http
GET /v1/search/titles
```

Response media buckets are raw `BaseItemDto[]`.

Rules:

- Search result navigation uses `BaseItemDto.Id`.
- `ProviderIds` may be displayed or cached as metadata but must not drive route identity.
- People search results are separate non-media result shapes.

### Search suggestions

Use:

```http
GET /v1/search/suggestions
```

Search suggestions are lightweight selectable hints, not full `BaseItemDto`.

```ts
type SearchSuggestionItem = {
  Id: PublicItemId;
  Type: string;
  Name: string;
  ProductionYear?: number | null;
  ImageTags?: {
    Primary?: ResponsiveImageSet | null;
  };
  ProviderIds?: ProviderIds | null;
};
```

Rules:

- Selecting a suggestion navigates by `Id`.
- App must not rebuild suggestion identity from TMDB IDs.
- Suggestion images use `ImageTags.Primary` when present.

### Home

Use:

```http
GET /v1/profiles/{profileId}/home
```

Response data:

```ts
type ProfileHomeResponse = {
  recommendations: ProfileHomeSnapshot | null;
};
```

Snapshot:

```ts
type ProfileHomeSnapshot = {
  profileId: string;
  sourceKey: string;
  historyGeneration: number;
  algorithmVersion: string;
  sourceCursor: string | null;
  generatedAt: string;
  expiresAt: string | null;
  source: string;
  updatedByKind: string;
  updatedById: string | null;
  sections: ProfileHomeSection[];
  updatedAt: string;
};
```

Section:

```ts
type ProfileHomeSection = {
  id: string;
  title: string;
  layout: 'regular' | 'landscape' | 'collection' | 'hero';
  items: Array<BaseItemDto | ProfileHomeCollectionCard | ProfileHomeHeroCard>;
  meta: Record<string, unknown>;
};
```

Rules:

- `recommendations == null` means no home snapshot exists yet.
- `regular` and `landscape` sections use `BaseItemDto[]` items.
- `hero` sections use `ProfileHomeHeroCard` items with `itemId`.
- `collection` sections use `ProfileHomeCollectionCard` items.
- Client UI owns rendering decisions beyond the documented section layout.

### Calendar

Use profile calendar endpoints documented in OpenAPI.

Rules:

- Calendar media items are `BaseItemDto`.
- Episode identity is item `Id`.
- Series grouping uses `SeriesId` and `SeriesName`.
- Do not synthesize episode route keys from provider IDs.

### Watch collections

Use profile watch endpoints documented in OpenAPI.

Rules:

- Continue watching, history, watchlist, and ratings return `BaseItemDtoQueryResult`.
- User state is embedded in `UserData`.
- Dismissal/removal uses `UserData.ItemId` or item `Id`.

### Watch state and mutations

All watch state routes use `itemId`.

Rules:

- Single state lookup uses query `itemId`.
- Batch state lookup uses body items with `itemId`.
- Watchlist/rating path params use item ID.
- Mark/unmark watched bodies use item ID.
- Watch event bodies use playable item ID.
- Do not send `mediaKey`, provider media type, season number, episode number, or absolute episode number as public identity fields.

### Playback resolve

Use only when the player needs server playback metadata beyond the item already available.

```http
GET /v1/playback/resolve?itemId={itemId}
```

Rules:

- Normal details/search/home/calendar navigation must not call playback resolve just to convert provider identity.
- The only accepted content identity is `itemId`.
- The response is currently documented as generic server-side; app code should isolate its parser behind a small adapter until OpenAPI is more specific.

## App model naming

Preferred app names:

- `itemId` for `BaseItemDto.Id`
- `itemType` for Jellyfin `Type`
- `seriesId` for `SeriesId`
- `seasonId` for `SeasonId`
- `userData` for `UserData`
- `providerIds` for `ProviderIds`

Deprecated names in normal app flow:

- `mediaKey`
- `titleMediaKey`
- `playbackMediaKey`
- provider-derived episode keys
- `tmdbId` as route identity

Temporary aliases are allowed only during staged refactors and must be removed before migration is complete.

## Contract versions

Required suite bumps:

- `media_state_contract`: v5
- `watch_collections_contract`: v4
- `calendar_contract`: v4

Suites that may remain local/pre-server if explicitly documented:

- `search_ranking_and_dedup`, only if still testing raw provider-result normalization outside normal app runtime.
- `home_catalogs`, only if replaced or reframed as a legacy/local planner contract; normal server home must follow this spec.

## Non-goals

- No compatibility layer for `/v1/metadata/titles/*`.
- No provider-key-to-itemId resolver in normal app flow.
- No local TMDB calls for normal app catalog/search/details/home/calendar/watch behavior.
- No universal `{ mediaItem, context, presentation }` wrapper.
- No per-item recommendation context wrappers in public recommendation items.

## Acceptance checklist

- App normal media identity is item ID end-to-end.
- App opens details from search/home/calendar/watch items without provider lookup.
- App sends watch events with playable item ID.
- App parses search suggestions as public item hints.
- App parses home from `/v1/profiles/{profileId}/home`.
- App no longer calls `/v1/profiles/{profileId}/recommendations`.
- App no longer calls `/v1/metadata/titles/*`.
- App no longer constructs provider-derived episode IDs for server routes.
- Kotlin and Swift contract runners validate the same item-id semantics.
- Contract fixtures use 32-character public item IDs for public media identity.
