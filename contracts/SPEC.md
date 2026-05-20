# Contract Spec (v2)

This directory defines parity-critical behavior for the rewrite apps.

The spec version documents the contract surface. Each suite owns its own
`contract_version` and may evolve independently.

## Item Identity

All public media identifiers are opaque server-assigned item IDs.
The app treats them as strings without parsing or deriving provider information.
Server `BaseItemDto.Id`, `SeriesId`, `SeasonId`, and `UserData.ItemId` are all
public item IDs (32-character lowercase dashless UUID hex for server-originated items).

Provider-derived strings like `movie:tmdb:550` are no longer public route identity.
Local pre-server contracts (e.g. `home_catalogs` input snapshots) may still carry
provider-key strings for planning purposes, but these are never sent to the server.

## Determinism Rules

- Every fixture must include:
  - `contract_version`
  - `suite`
  - `case_id`
- Time-sensitive suites must include `now_ms` and use injected clocks in implementation.
- Any randomness must be driven by an injected seeded RNG.
- Output ordering must be canonical and explicitly defined by each suite.
- Breaking behavior changes are suite-scoped: bump only the affected suite's
  `contract_version`.

## Active Suites

- `player_machine`
  - Event-driven playback transitions and engine fallback behavior.
- `continue_watching`
  - Continue Watching planning: filter, dedupe, and canonical ordering for in-progress items and placeholders.
  - Dedupe: for the same episode/movie, prefer higher progress only if it is > 0.5 percentage points ahead; otherwise prefer newer `last_updated_ms`.
- `next_episode`
  - Determine the next released episode after the current season/episode.
  - Skip watched episodes using both raw and `tt`-prefixed show ids.
  - Release parsing accepts full ISO instants or `YYYY-MM-DD`; invalid or blank release values are unreleased.
  - Time-sensitive comparisons use fixture-provided `now_ms`.
- `trakt_scrobble_policy`
  - Deterministic Trakt scrobble decisions (endpoint + watched/progress flags) based on stage and progress.
- `trakt_scrobble_policy`
  - Normalize IMDb ids to trimmed lowercase `tt<digits>` form; invalid ids resolve to `null`.
  - Ignore blank and `N/A` fields case-insensitively.
  - Ratings dedupe by source case-insensitively while preserving first-seen order.
  - Append synthetic `Internet Movie Database` and `Metacritic` ratings from top-level OMDb fields only when those sources are otherwise absent.
- `home_catalogs`
  - Plan home-screen hero shelves, header sections, discover catalog refs, and paged catalog results from deterministic snapshot input.
  - `contract_version` 3 removes `member_shared` and uses canonical section ids in the form `source:kind:variant_key`.
  - `contract_version` 6 replaces `media_key` with opaque `item_id` on client-facing title items.
  - Section metadata is preserved end-to-end: `source`, `presentation`, `variant_key`, `name`, `heading`, `title`, and `subtitle`.
  - Hero selection prefers the first `presentation = hero` list; otherwise it falls back to the first list.
  - Hero items require `backdrop_url` or `poster_url`; fallback description is `subtitle`, then `heading`, then non-blank `title`, then `Recommended for you.`
  - Non-hero sections remain in feed order; `presentation` drives downstream `hero | pill | rail` UI decisions and unknown values normalize to `rail`.
  - Discover filtering accepts only `movie` and `show`, includes only `presentation = rail` sections, and page results use canonical attempted-url keys with source + kind + variant.
- `catalog_url_building`
  - Build deterministic addon catalog request URL variants from addon `base_url`, preserved manifest query params, media type, catalog id, pagination, and filters.
  - For first-page requests with no filters, try simple path first, then path-style extras, then legacy query style.
  - Path/query forms always include canonical `skip` and `limit`; filters trim blanks, drop empty entries, sort deterministically by key then value, and preserve duplicates.
   - Generated URLs keep addon query parameters and percent-encode path/query components consistently.
- `sync_planner`
  - Canonicalize shared (household) vs per-profile cloud payloads.
  - Pull planning: `get_household_addons` is allowed only when there are no unsynced household changes.
  - Shared addons are normalized (trim URL, strip trailing `/`, default enabled=true, canonical sort).
  - Debounce planning: writes are delayed by `debounce_ms` using `now_ms` + `*_changed_at_ms`; `flush_requested` bypasses debounce.
  - `contract_version` 2 removes provider auth from profile-sync payloads; per-profile writes now include only settings + catalog prefs.
  - Only owners may plan household addon writes.
- `storage_v1`
  - Logical storage namespace/versioning and schema mismatch behavior.
- `media_state_contract`
  - Validate exact backend payload-shape rules for client-facing runtime and card-like metadata surfaces.
  - `contract_version` 5 migrated to server `PublicItemId` identity (32-character lowercase dashless UUID hex).
    `BaseItemDto.Id`, `SeriesId`, `SeasonId`, and `UserData.ItemId` are all public item IDs.
    Provider-derived strings like `movie:tmdb:550` are no longer public route identity.
  - Continue-watching items derive state from `UserData.PlayedPercentage` (progress), `UserData.LastPlayedDate` (activity), and `UserData.DismissedFromContinueWatching` (dismissible).
  - Watched items derive state from `UserData.LastPlayedDate`.
  - Search results, recommendations, and other card-like title metadata items are raw `BaseItemDto`.
  - Title metadata routes use `/v1/metadata/items/:itemId`.
  - Home snapshot sections preserve exact backend `layout` values: `regular`, `landscape`, `collection`, `hero`.
- `watch_collections_contract`
  - Validate public `/v1/profiles/:profileId/watch/*` responses against the server contract.
  - `contract_version` 4 uses `PublicItemId` for all item identity.
  - Responses follow `BaseItemDtoQueryResult` shape: `Items`, `StartIndex`, `TotalRecordCount`, `NextCursor`, `HasMore`.
  - Items are raw `BaseItemDto` arrays; user state is embedded per-item in `UserData`.
- `calendar_contract`
  - Validate public `/v1/profiles/:profileId/calendar` and `/calendar/this-week` responses against the server contract.
  - `contract_version` 4 uses `PublicItemId` for all item identity.
  - Calendar envelopes: `profileId`, `source`, `generatedAt`, `items: BaseItemDto[]`.
  - Calendar items are raw `BaseItemDto` (no wrapper objects).

## Breaking Changes

Breaking behavior changes are allowed when needed. For every affected suite:

1) bump that suite's fixture `contract_version`
2) update this spec plus the relevant fixtures/schemas
3) keep Android + Swift contract runners in lockstep
4) include migration notes in the PR description

### Migration: Jellyfin-first identity (all suites, 2026-05)

All suites migrated from provider-derived `mediaKey` strings to opaque server-assigned
item IDs. The app no longer parses or constructs `{type}:{provider}:{id}` format strings.
Server `BaseItemDto.Id`, `SeriesId`, `SeasonId`, and `UserData.ItemId` are all public
item IDs. The TMDB provider stack was removed from normal app DI; provider IDs remain
only as passive metadata in `ProviderIds`/`externalIds`.
Home catalog fixtures now use `item_id` instead of `media_key`.
The `search_ranking_and_dedup` contract was removed — it was a pre-server TMDB normalization
contract and no longer has a runtime caller after TMDB provider removal.
