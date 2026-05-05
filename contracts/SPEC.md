# Contract Spec (v2)

This directory defines parity-critical behavior for the rewrite apps.

The spec version documents the contract surface. Each suite owns its own
`contract_version` and may evolve independently.

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
- `media_ids`
  - Nuvio-style ID normalization for `content_id` and episode `video_id`.
  - Canonical episode form is `${content_id}:${season}:${episode}`.
  - Internal `series:` wrappers are accepted but stripped during normalization.
- `metadata_tmdb_enhancer`
  - Derive season rows from valid episode metadata only when series metadata has no seasons.
  - Derived seasons sort ascending, count valid episode rows, and keep the first non-blank release per season as `air_date`.
  - Bridge candidate ids start with the normalized content id, append `:season:episode` suffixes when present, and add TMDB fallback ids from TMDB metadata when available.
  - Bridge candidate ids dedupe case-insensitively while preserving first-seen order.
- `omdb`
  - Normalize IMDb ids to trimmed lowercase `tt<digits>` form; invalid ids resolve to `null`.
  - Ignore blank and `N/A` fields case-insensitively.
  - Ratings dedupe by source case-insensitively while preserving first-seen order.
  - Append synthetic `Internet Movie Database` and `Metacritic` ratings from top-level OMDb fields only when those sources are otherwise absent.
- `home_catalogs`
  - Plan home-screen hero shelves, header sections, discover catalog refs, and paged catalog results from deterministic snapshot input.
  - `contract_version` 3 removes `member_shared` and uses canonical section ids in the form `source:kind:variant_key`.
  - `contract_version` 5 requires `media_key` on client-facing title items; provider identity is not part of client routing.
  - Section metadata is preserved end-to-end: `source`, `presentation`, `variant_key`, `name`, `heading`, `title`, and `subtitle`.
  - Hero selection prefers the first `presentation = hero` list; otherwise it falls back to the first list.
  - Hero items require `backdrop_url` or `poster_url`; fallback description is `subtitle`, then `heading`, then non-blank `title`, then `Recommended for you.`
  - Non-hero sections remain in feed order; `presentation` drives downstream `hero | pill | rail` UI decisions and unknown values normalize to `rail`.
  - Discover filtering accepts only `movie` and `series`, includes only `presentation = rail` sections, and page results use canonical attempted-url keys with source + kind + variant.
- `catalog_url_building`
  - Build deterministic addon catalog request URL variants from addon `base_url`, preserved manifest query params, media type, catalog id, pagination, and filters.
  - For first-page requests with no filters, try simple path first, then path-style extras, then legacy query style.
  - Path/query forms always include canonical `skip` and `limit`; filters trim blanks, drop empty entries, sort deterministically by key then value, and preserve duplicates.
  - Generated URLs keep addon query parameters and percent-encode path/query components consistently.
- `id_prefixes`
  - Nuvio-style addon ID-prefix compatibility formatting.
  - Resource-level `idPrefixes` are preferred; addon-level prefixes are fallback.
  - Prefix matching uses `startsWith`; when no prefixes are declared, return best-effort normalized ID.
- `search_ranking_and_dedup`
  - Normalize TMDB search results and preserve the upstream (TMDB) ordering.
  - Include `person` results (no filtering).
  - Type mapping: TMDB `movie` -> `movie`, TMDB `tv` -> `series`, TMDB `person` -> `person` (unknown media types are ignored).
  - Dedupe key is `(type, tmdb_id)`; keep the first occurrence.
  - `contract_version` 4 removes provider-derived route identity and emits opaque client `item_key` values. Title results use title-route keys, while people remain a separate route class with distinct keys.
  - Image URLs use `w500` for movie/series posters and `h632` for person profiles.
  - `year` is parsed from the first 4 digits of `release_date`/`first_air_date` when present; invalid/missing yields `null`.
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
  - Validate exact backend payload-shape rules from `CLIENT_SERVER_MEDIA_STATE_CONTRACT.md` for client-facing runtime and card-like metadata surfaces.
  - `contract_version` 3 requires `media.mediaKey` for every media-bearing title surface that can open title details. `mediaType` remains supporting metadata only.
  - Regular-card surfaces require `mediaKey`, `mediaType`, `title`, and `posterUrl`.
  - Landscape-card surfaces require the same fields plus `backdropUrl`.
  - Continue-watching items require `id`, `media`, `progress`, `lastActivityAt`, `origins`, and `dismissible`; they do not require `watchedAt`, and dismissal UI must honor backend `dismissible`.
  - Watched/watchlist/ratings items require `media` and `origins`, plus their relevant state fields.
  - Search results, `similar`, `collection.parts`, and other card-like title metadata items are `mediaKey`-based and must not require internal canonical ids.
  - Title metadata routes use `/v1/metadata/titles/:mediaKey` and nested title-content routes hang off that same public key rather than internal ids.
  - Home snapshot sections preserve exact backend `layout` values: `regular`, `landscape`, `collection`, `hero`.
- `watch_collections_contract`
  - Validate public `/v1/profiles/:profileId/watch/*` collection envelopes against the server contract.
  - Collection envelopes preserve exact server fields: `profileId`, `kind`, `source`, `generatedAt`, `items`, and `pageInfo`.
  - Collection `source` is `canonical_watch`; `kind` values are `continue-watching`, `history`, `watchlist`, and `ratings`.
  - Continue-watching items require landscape media plus `progress`, `lastActivityAt`, `origins`, and `dismissible`.
  - History/watchlist/ratings items require regular media plus their respective server state objects/fields.
- `calendar_contract`
  - Validate public `/v1/profiles/:profileId/calendar` and `/calendar/this-week` responses against the server contract.
  - Calendar envelopes preserve exact server fields: `profileId`, `source`, `generatedAt`, and `items`; `this-week` additionally requires `kind: this-week`.
  - Calendar items require `bucket`, landscape `media`, regular `relatedShow`, `airDate`, and `watched`.
  - Calendar `source` is `canonical_calendar`; valid buckets are `up_next`, `this_week`, `upcoming`, `recently_released`, and `no_scheduled`.

## Breaking Changes

Breaking behavior changes are allowed when needed. For every affected suite:

1) bump that suite's fixture `contract_version`
2) update this spec plus the relevant fixtures/schemas
3) keep Android + Swift contract runners in lockstep
4) include migration notes in the PR description
