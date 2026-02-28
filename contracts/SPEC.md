# Contract Spec (v1)

This directory defines parity-critical behavior for the rewrite apps.

## Determinism Rules

- Every fixture must include:
  - `contract_version`
  - `suite`
  - `case_id`
- Time-sensitive suites must include `now_ms` and use injected clocks in implementation.
- Any randomness must be driven by an injected seeded RNG.
- Output ordering must be canonical and explicitly defined by each suite.

## Active Suites

- `player_machine`
  - Event-driven playback transitions and engine fallback behavior.
- `continue_watching`
  - Continue Watching planning: filter, dedupe, and canonical ordering for in-progress items and placeholders.
  - Dedupe: for the same episode/movie, prefer higher progress only if it is > 0.5 percentage points ahead; otherwise prefer newer `last_updated_ms`.
- `trakt_scrobble_policy`
  - Deterministic Trakt scrobble decisions (endpoint + watched/progress flags) based on stage and progress.
- `media_ids`
  - Nuvio-style ID normalization for `content_id` and episode `video_id`.
  - Canonical episode form is `${content_id}:${season}:${episode}`.
  - Internal `series:` wrappers are accepted but stripped during normalization.
- `id_prefixes`
  - Nuvio-style addon ID-prefix compatibility formatting.
  - Resource-level `idPrefixes` are preferred; addon-level prefixes are fallback.
  - Prefix matching uses `startsWith`; when no prefixes are declared, return best-effort normalized ID.
- `catalog_url_building`
  - Catalog requests must try deterministic URL forms in order.
  - For page 1 with no filters, try simple path first, then path-style extras, then legacy query style.
  - Path/query forms must include canonical `skip` and `limit`, preserve deterministic filter ordering, and keep addon query parameters.
- `search_ranking_and_dedup`
  - Normalize TMDB search results and preserve the upstream (TMDB) ordering.
  - Include `person` results (no filtering).
  - Type mapping: TMDB `movie` -> `movie`, TMDB `tv` -> `series`, TMDB `person` -> `person` (unknown media types are ignored).
  - Dedupe key is `(type, tmdb_id)`; keep the first occurrence.
  - Canonical id form is `tmdb:<id>`.
  - Image URLs use `w500` for movie/series posters and `h632` for person profiles.
  - `year` is parsed from the first 4 digits of `release_date`/`first_air_date` when present; invalid/missing yields `null`.
- `metadata_addon_primary`
  - Addon-first metadata merge with deterministic precedence.
  - Source ranking is `preferred_addon_id` first, then Cinemeta, then remaining addons.
- `metadata_tmdb_enhancer`
  - TMDB may only fill missing metadata fields; it must not override addon-provided values.
  - `tmdb:*` IDs may be bridged to IMDb (`tt...`) for addon retries.
- `sync_planner`
  - Canonicalize shared (household) vs per-profile cloud payloads.
  - Pull planning: `get_household_addons` is allowed only when there are no unsynced household changes.
  - Shared addons are normalized (trim URL, strip trailing `/`, default enabled=true, canonical sort).
  - Debounce planning: writes are delayed by `debounce_ms` using `now_ms` + `*_changed_at_ms`; `flush_requested` bypasses debounce.
  - Only owners may plan household addon writes; per-profile writes always include settings + Trakt + Simkl.
- `storage_v1`
  - Logical storage namespace/versioning and schema mismatch behavior.

## Breaking Changes

Breaking behavior changes are allowed when needed. Every breaking change must:

1) bump the suite fixture `contract_version`
2) update this spec
3) include migration notes in the PR description
