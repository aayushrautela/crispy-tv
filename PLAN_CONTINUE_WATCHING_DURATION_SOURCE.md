# Plan: Continue Watching duration source = TMDB metadata (server + client root-cause fix)

Status: Investigation-complete plan. This is a **root-cause** fix, not another layer on top.
It addresses why app-watched items (a valid resume position) never appear in the Android
Continue Watching rail or the details "Continue" CTA, while the web app shows them fine.

Backend lives in a separate repo: `/home/aayush/Downloads/crispy server`
(repo display name "Crispy"); Android client is this repo (`crispy-rewrite`).

## Diagnosis (evidence)

- Android log proves the shape the server returns for app-watched items:
  `CWParse : drop(itemId=…, name=Star Trek Beyond): progressPercent null (pos=3539, dur=null)`
  → server sends `positionSeconds` but `durationSeconds = null` **and** `percent = null`.
- Android drop logic:
  - `BackendWatchHistoryService.toCanonicalContinueWatchingItem` (`:570-585`): drops when
    `progressPercent == null` or `<= 0` or `>= 85`.
  - `CrispyBackendParsers.kt:498-503`: `progressPercent = progress.percent ?: (position / duration)`.
    With `percent=null` and `duration=null`, result is `null` → item dropped.
- Web app does **not** require a percent: `src/lib/cardMappers.ts:45` reads
  `card.progress?.percent ?? undefined` and the CW row is shown off the server's already-curated
  list using the **resume position** (`positionSeconds` / `lastPlayedAt`). Web never needs a percent.
- Android's details "Continue" CTA (`WatchCtaResolver`) reads `getTitleWatchState` →
  `toCanonicalWatchStateSnapshot` which uses the **same** `progressPercent` field, so it fails for the
  same reason → shows "Watch now".
- The app **does** send `durationSeconds` in playback events
  (`BackendWatchHistoryService.onPlaybackProgress` → `sendPlaybackEvent`, `:313-352`, `:423-447`),
  but torrent/addy file lengths are unreliable, so client-reported duration is a bad source of truth.
  The server is not returning it in the CW/watch-state response (it is not joined from item metadata).

Root cause: **duration is not sourced from canonical metadata.** The server has a valid resume
position but no duration, so `progressPercent` is uncomputable; Android then over-strictly drops the
item. Web tolerates this; Android does not.

## Canonical design (principles)

1. **Duration is canonical from TMDB metadata**, not from the playing file. Torrent/addy sources vary
   in length, so client-reported duration is unreliable.
2. **Client reports position only**: `positionSeconds` + `lastPlayedAt` (how much was watched).
   It should not be the source of duration.
3. **Server joins the item to its TMDB `runtime`**, returns `durationSeconds` **and** `percent` in
   both the Continue Watching (`/v1/profiles/{profileId}/watch/continue-watching`) and watch-state
   (`/v1/profiles/{profileId}/watch/state`) responses.
4. **Android must not drop an item that has a valid resume position.** A `positionSeconds > 0`
   (or `lastPlayedAt` present) is sufficient to show "Continue", even if duration is momentarily
   unavailable (show without a progress bar, or with the TMDB-derived bar once available).

## Decisions (assumptions, confirm)

- **D1 — Duration source of truth = TMDB metadata cache** (per title, shared across users).
  TMDB `runtime` = movie minutes; TV `episode_run_time` = average episode minutes (acceptable
  approximation for episodes — per-episode runtime is not reliably available from TMDB).
- **D2 — Client sends position only.** Remove reliance on `durationSeconds` in playback events
  (keep the field optional/ignored server-side to avoid a breaking wire change).
- **D3 — Server computes and returns `durationSeconds` + `percent`.** Response field names stay
  compatible with Android `ClientProgress` (`percent`, parser `CrispyBackendParsers.kt:446-458`):
  `percent`, `positionSeconds`, `durationSeconds`, `lastPlayedAt`, `played`, `playCount`.
- **D4 — Android stops dropping on missing percent/duration** when a resume position exists.
- **D5 — Episodes use TMDB `episode_run_time` (average).** Documented approximation; good enough for
  a progress bar and for the (0,85) in-progress window.

## Server (`/home/aayush/Downloads/crispy server`)

1. **Add a title-runtime cache** keyed by item id / TMDB id (Redis or in-memory), **shared across
   users**. Same title → one cached value. Cost scales with number of *titles*, not *users*.
2. **Resolve TMDB `runtime` lazily** at metadata load or on first watch of a title; store
   `runtimeSeconds` **denormalized** onto the title/watch record so the hot read path needs no join.
3. **Populate `durationSeconds` + `percent`** in:
   - `mapContinueWatchingRow` (Continue Watching response)
   - the watch-state mapper (single-item + list states)
   using `percent = clamp(positionSeconds / runtimeSeconds * 100, 0, 100)` when both are present.
4. **Backfill lazily**, per title, on first request/play — never a full-library scan.
5. Keep response field names identical to today so the Android `ClientProgress` parser is unchanged.

## Client (Android, this repo)

6. **Playback events: send `positionSeconds` + `lastPlayedAt` only.** Stop depending on
   `durationSeconds` (server ignores it). Ensure `onPlaybackProgress` still reports position even
   when the player reports an unknown duration (do not early-return solely on `dur <= 0`).
7. **CW mapper resilience:** in `toCanonicalContinueWatchingItem`, when `durationSeconds`/`percent`
   are missing but `positionSeconds > 0` (or `lastPlayedAt` present), keep the item and surface a
   "Continue" entry with `progressPercent` null (UI renders no bar, or an approximate bar once the
   server supplies `durationSeconds`).
8. **Details CTA resilience:** `WatchCtaResolver` should also accept `positionSeconds > 0` as
   in-progress (defensive), in addition to the server `titleProgressPercent`. Once the server returns
   `percent` (step 3), the existing `titleProgressPercent` path already yields "Continue".
9. **UI:** `HomeWideRailItemUi.progressFraction` and details progress bar already accept null → render
   without a fill. Verify no NPE/path assumes a non-null percent.

## Contracts / fixtures (this repo)

10. Update `media_state_contract`, `watch_collections_contract`, and `continue_watching` fixtures +
    schemas to **require `durationSeconds` and `percent` present (non-null)** on CW / watch-state
    items whenever a resume position exists; assert `percent ∈ (0, 85)` for in-progress items.
11. **Bump the affected suite `contract_version`** and update `contracts/SPEC.md` "Continue Watching"
    section to state: duration source = TMDB metadata cache (canonical), client reports position only.
12. Keep **Kotlin + Swift ContractRunner in lockstep** — the watch-state/CW response shape is shared;
    the Swift side must assert the same `durationSeconds`/`percent` presence. (Note: the
    `ContinueWatchingPlanner` is currently unused in production; this plan does not change it.)

## Performance / scale

- Runtime is **static per title and identical for all users** → cached once, shared.
- Resolve at **write/metadata time**, denormalized onto the record → the CW read is a single cheap
  lookup, **no TMDB call on the hot path**.
- TMDB rate limits (≈50 req/s) are irrelevant: one call per *title ever*, served from cache after.
- Load stays flat as users grow (scales with titles, not requests).

## Rollout order

1. **Server first** (steps 1-5): returns `durationSeconds` + `percent`. This **unblocks both the rail
   and the details CTA with zero Android code change** (Android's parser already computes the percent
   correctly when duration is present).
2. **Client cleanup** (steps 6-9): position-only reporting + drop-resilience (defensive hardening so a
   missing duration can never hide an item again).
3. **Contracts** (steps 10-12): lock the new shape in fixtures so regressions are caught.

## Verification

- logcat `CWParse` no longer emits `progressPercent null (pos=…, dur=null)` for app-watched items.
- `media_state_contract` / `watch_collections_contract` tests assert `durationSeconds` + `percent`
  present for in-progress items.
- Manual: watch a movie and an episode mid-way on Android → rail shows both, details says "Continue"
  (not "Watch now"), progress bar reflects `position ÷ TMDB runtime`.
- Cross-client: web app continues to show the same items (no regression).
