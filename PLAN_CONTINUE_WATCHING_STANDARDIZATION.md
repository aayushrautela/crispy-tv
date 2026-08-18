# Plan: Continue Watching = Movie ∪ Episode (server + client)

Status: Code-checked plan. Supersedes the earlier "standardization" plan. The canonical
Continue Watching (CW) model is strictly **Movie or Episode**. There is no series-level ("show")
CW entry — and per code verification it is **impossible to create one** with current writers:
- Live playback (`recordPlaybackState`) stores the playable item's `entityType` → `'episode'` for TV.
- Trakt (`trakt-import.normalizer.ts:113-162`) and SIMKL (`simkl-import.normalizer.ts:39-48,191`)
  emit one per-episode event with `mediaType: 'episode'` + season/episode; shows with no episode
  breakdown are skipped.
- `local-provider-history-writer.ts:200-211` derives `season_number`/`episode_number` from the
  playable item's media key, so any TV playable item (always an episode) carries S/E.
A `'show'` (series-level) row in `user_state.playback_progress` can therefore only be **stale/legacy
data**. This matches Jellyfin (`/UserItems/Resume` is gated by `IsResumable`; Jellyfin web/Streamyfin
request `includeItemTypes=[Movie, Episode]`).

## Current state (after investigation)
- **Server** `mapContinueWatchingRow` already fixed (commit `ebe91c3`, backend `master`, not pushed):
  emits an **Episode**-level card whenever `season_number`/`episode_number` are present (covers both
  live `'episode'` rows and any legacy `'show'` rows that still carry S/E). Movie path unchanged.
  Because TV is always stored as `'episode'`+S/E, the only remaining `'show'` rows are legacy ones
  lacking S/E (invalid by construction).
- **Client** already handles Movie + Episode correctly:
  - `BackendWatchHistoryService.toCanonicalContinueWatchingItem` maps `season = parent?.seasonNumber`,
    `episode = parent?.episodeNumber`, `imdbId = providerIds?.imdb` (series imdb), `itemType = mediaType`
    (`"episode"`/`"movie"`). `localKey` appends `:s:e` for non-movie with S/E.
  - `HomeSelectorViewModel.openFor` (commit `44303e6`) uses `item.type` (`"episode"`→SERIES) and builds
    `buildAddonEpisodeLookupId(imdbId, season, episode)` → `stream/series/<seriesImdb>:<s>:<e>`.
- **Gap (proper-client):** `emitPlay` sets `PlaybackIdentity.itemId = item.titleItemId` (the **series**
  id for an episode CW item), so actual playback/resume targets the series, not the episode.

## Decisions (need confirm)
- **D1 — invalid legacy `'show'` rows (no S/E):** recommended **exclude from CW** (defensive; they
  cannot be produced by current writers, so this only cleans up stale data). No "keep series-level
  fallback" — that case is impossible by construction. Plan assumes EXCLUDE.
- All other behavior (movie, episode-with-S/E) is already correct.

## Canonical model
- **Movie** → `mediaType='movie'`, `imdbId` = movie imdb, lookup `stream/movie/<imdb>`.
- **Episode** → `mediaType='episode'`, `imdbId` = series imdb, `season`/`episode` set,
  `titleItemId` = series id, `id` = episode playable id, lookup `stream/series/<seriesImdb>:<s>:<e>`.
- No `'show'`-level card in CW.

## Server (backend repo: `/home/aayush/Downloads/crispy server`)
1. **(done)** `watch-read.mapper.ts: mapContinueWatchingRow` → episode-level when S/E present (`ebe91c3`).
2. **(D1)** Enforce Movie ∪ Episode: in `mapContinueWatchingRow`, if `mediaType` resolves to `'show'`
   and `season_number`/`episode_number` are null, return `null` (caller `listContinueWatchingPage`
   already maps rows via `mapContinueWatchingRow`, so skipping nulls drops the row). Keeps `'show'`+S/E
   → episode (already handled) and `'movie'` → movie. This removes the only series-level CW source.
3. **Test** `watch-read.mapper.test.ts`:
   - (done) `'show'`+S/E → `Type='Episode'` + `ParentIndexNumber`/`IndexNumber` + series `ProviderIds`.
   - (new) `'show'` without S/E → mapper returns `null` (row excluded).

## Client (Android repo: `/home/aayush/Downloads/crispy-rewrite`)
4. **(verify, no change)** `BackendWatchHistoryService.toCanonicalContinueWatchingItem` and
   `HomeSelectorViewModel.openFor` already produce correct Movie/Episode cards + `:s:e` lookup.
5. **(proper fix)** `HomeSelectorViewModel.emitPlay` (line ~114): for an episode CW item, set
   `PlaybackIdentity.itemId = item.id` (the episode playable id) instead of `item.titleItemId`
   (series id); keep `contentType = SERIES`. Ensures resume/play targets the specific episode.
   (Promoted from the previously-deferred item B — required for a correct client.)
6. **(verify)** Rail renders episode card via existing `subtitle` (`SxxExx`) + `stillUrl` wiring.

## Contracts
7. Backend unit test covers the server mapping (above).
8. Add a CW contract fixture + Kotlin (`android:contract-tests`) + Swift (`ios/ContractRunner`) test
   asserting a CW **episode** item resolves to `stream/series/<seriesImdb>:<s>:<e>` with a
   `PlaybackIdentity` carrying `season`/`episode`. (Cannot run locally — CI verifies; no JDK/Swift here.)

## Verification
- Backend: `node --import tsx --test "src/**/*.test.ts"` (watch-read mapper passes); `npx tsc --noEmit`.
- Client: `gradle :android:contract-tests:test`; `swift test --package-path ios/ContractRunner`.
- Manual: rebuild/deploy backend + Android; open a TV CW episode → logs show
  `stream/series/tt…:<s>:<e>.json`; pressing play resumes the episode, not the series.

## Implementation order
1. Server: D1 exclusion in `mapContinueWatchingRow` (#2) + tests (#3). Commit backend on `master`.
2. Client: `emitPlay` episode id fix (#5). Commit on `enhancements` (no push).
3. Contracts (#8) — Kotlin + Swift; CI verifies.
4. Confirm client mapping (#4, #6) needs no further change.

## Notes
- No data migration needed: read-layer normalization + null-skip cover live and imported rows.
- Imports (Trakt/SIMKL) unchanged; read-layer handles them.
