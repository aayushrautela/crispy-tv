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
  - `contract_version` 2 renames the fallback engine identifier from `vlc` to `mpv` (libVLC replaced by libmpv). v1 fixtures are superseded.
- `player_progress`
  - Deterministic gating for reporting player progress to our backend (pure rules in `android/core-domain`, mirrored in Swift ContractRunner).
  - Positions below `MIN_PROGRESS_POSITION_MS` (1000ms) are dropped unless the item is already complete — this filters transient engine 0s during seeks/buffering/engine fallback, not intentional stops.
  - On completion (>=85% of duration) the stored and event position is pinned to the full `duration_ms`, so a trailing 0 from the engine cannot wipe the resume point.
  - Unknown duration (0ms) is allowed as long as the position is >= the minimum; completion requires a known duration.
- `continue_watching`
  - Continue Watching planning: filter, dedupe, and canonical ordering for in-progress items and placeholders.
  - Dedupe: for the same episode/movie, prefer higher progress only if it is > 0.5 percentage points ahead; otherwise prefer newer `last_updated_ms`.
- `next_episode`
  - Determine the next released episode after the current season/episode.
  - Skip watched episodes using both raw and `tt`-prefixed show ids.
  - Release parsing accepts full ISO instants or `YYYY-MM-DD`; invalid or blank release values are unreleased.
  - Time-sensitive comparisons use fixture-provided `now_ms`.
- `trakt_scrobble_policy`
  - Normalize IMDb ids to trimmed lowercase `tt<digits>` form; invalid ids resolve to `null`.
  - Ignore blank and `N/A` fields case-insensitively.
  - Ratings dedupe by source case-insensitively while preserving first-seen order.
  - Append synthetic `Internet Movie Database` and `Metacritic` ratings from top-level OMDb fields only when those sources are otherwise absent.
- `home_catalogs`
  - Plan home-screen hero shelves, header sections, discover catalog refs, and paged catalog results from deterministic snapshot input.
  - `contract_version` 3 removes `member_shared` and uses canonical section ids in the form `source:kind:variant_key`.
  - `contract_version` 6 replaces `media_key` with opaque `item_id` on client-facing title items.
  - `contract_version` 7 removes the hero shelf limit; hero items include every valid item from the selected list (no `hero_limit` cap).
  - Section metadata is preserved end-to-end: `source`, `presentation`, `variant_key`, `name`, `heading`, `title`, and `subtitle`.
  - Hero selection uses the first `presentation = hero` list; if no list has `presentation = hero`, the hero result is empty (no fallback to non-hero lists).
  - Hero items require `artwork_url`; fallback description is `subtitle`, then `heading`, then non-blank `title`, then `Recommended for you.`
  - Non-hero sections remain in feed order; `presentation` drives downstream `hero | pill | collection_shelf | rail` UI decisions and unknown values normalize to `rail`.
  - Wire-level `sectionType` ∈ {`categoryTabs`, `heroCarousel`, `contentRail`, `collectionRail`} is mapped to `presentation` deterministically: `categoryTabs` → `pill`, `heroCarousel` → `hero`, `collectionRail` → `collection_shelf`, else → `rail`.
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
  - **Runtime is canonical from TMDB metadata, not the playing file.** `UserData.RuntimeTicks` and `UserData.PlayedPercentage` are derived server-side by joining the item to its TMDB `runtime` (`movie`) or `episode_run_time` (`show`, average) — torrent/addy file lengths are unreliable, so client-reported `durationSeconds` is only a last-resort fallback. The client reports **position only** (`positionSeconds` + `lastPlayedAt`); it must not be the source of duration.
  - Continue-watching entries are shown whenever a resume position exists (`PlaybackPositionTicks > 0` / `LastPlayedDate` present), even if `PlayedPercentage` is momentarily null (metadata runtime missing). A null percent is surfaced as "Continue" without a progress bar, never dropped.
- `watch_sync`
  - Real-time cross-device sync for continue-watching: a server-pushed invalidation channel plus deterministic client connection/refetch policy.
  - Server transport: `GET /v1/profiles/:profileId/watch/stream` (SSE), guarded by the same auth + profile-unlock guard as other watch routes.
  - Channel: Redis pub/sub `cw:{accountId}`; server filters messages by `profileId` before writing to the client.
  - Message envelope: `id`, `event: watch_changed`, `data: { profileId, kind, at_ms }`, `retry`.
  - Server coalescing: progress ticks are debounced per profile (`cw-dirty:{accountId}:{profileId}`, ~5s window); `playback_completed` and dismiss bypass the debounce (force publish). Reconnect + refetch covers any gap.
  - Client policy (deterministic reducer, mirrored in `android/core-domain` and Swift ContractRunner): open the stream when the continue-watching surface is visible/foreground; close it when hidden/backgrounded; on any `watch_changed` while connected (or on (re)connect) → refetch the continue-watching page; force reconnect on `max_duration_elapsed`. DB is the source of truth; the stream is an invalidation trigger only.
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
- `optimistic_state`
  - Pure, deterministic merge of local user intent on top of last-known server truth, plus outbox scheduling.
  - Local intent is modeled as a `UserMutation` (one of `watchlist`, `title_watched`, `episode_watched`, `season_watched`, `rating`) carrying a client nonce `id` for idempotency, an `entity_id` for coalescing, `attempt`, `status`, and `next_attempt_ms`.
  - `derive` operation: `deriveUserState(snapshot, mutations)` returns the display state. Within a `(kind, entity_id)` group the most recently created mutation wins. `pending`/`inflight` show the local `desired` value with `sync = syncing`; `failed`/`conflict` fall back to server truth with `sync = error`; no active mutation yields server truth with `sync = idle`.
  - Rapid double-toggles collapse: two `watchlist` mutations for the same `entity_id` resolve to the latest `desired`.
  - `plan_outbox` operation: `planOutbox(mutations, now_ms)` returns `OutboxAction`s for `pending` mutations whose `next_attempt_ms <= now_ms`, ordered by `(created_at_ms, id)`. `failed`/`conflict`/`inflight` are never scheduled by this step (the processor re-queues failed writes with a new backoff).
  - Backoff is `base * 2^(attempt-1)` capped at `max_delay_ms` (`nextBackoffDelayMs`).

## Breaking Changes

Breaking behavior changes are allowed when needed. For every affected suite:

1) bump that suite's fixture `contract_version`
2) update this spec plus the relevant fixtures/schemas
3) keep Android + Swift contract runners in lockstep
4) include migration notes in the PR description

### Migration: player_machine v2 (2026-07)

The `player_machine` suite migrated from `contract_version` 1 to 2. The fallback
engine identifier changed from `vlc` to `mpv` because the Android fallback
engine switched from libVLC to libmpv (Findroid's `dev.jdtech.mpv:libmpv`
AAR from Maven Central). v1 fixtures were removed; v2 fixtures live under
`contracts/fixtures/player_machine/v2/`. The Kotlin `PlayerReducer` and the
Swift `reducePlayerState` both return `"mpv"` when the active engine is `"exo"`
and a `NATIVE_CODEC_ERROR` event is reduced.

### Migration: Jellyfin-first identity (all suites, 2026-05)

All suites migrated from provider-derived `mediaKey` strings to opaque server-assigned
item IDs. The app no longer parses or constructs `{type}:{provider}:{id}` format strings.
Server `BaseItemDto.Id`, `SeriesId`, `SeasonId`, and `UserData.ItemId` are all public
item IDs. The TMDB provider stack was removed from normal app DI; provider IDs remain
only as passive metadata in `ProviderIds`/`externalIds`.
Home catalog fixtures now use `item_id` instead of `media_key`.
The `search_ranking_and_dedup` contract was removed — it was a pre-server TMDB normalization
contract and no longer has a runtime caller after TMDB provider removal.
