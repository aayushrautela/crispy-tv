# Plan: Up Next + Calendar rails (episode surfacing)

## Status: investigation complete — implementation NOT started

## Problem
Episodes the user is mid-watching don't appear anywhere on the home screen:
- They aren't in **Continue Watching** because imported episodes are stored at 0% progress
  (Trakt sends no resume point) and the CW gate drops `progressPercent <= 0`
  (`BackendWatchHistoryService.kt`, `toCanonicalContinueWatchingItem`).
- They aren't in **Up Next** because the `UP_NEXT` home rail is declared but **never wired to data**.
- The **Calendar / This Week** rail (air-date based, unaired episodes) already works.

Nuvio/Jellyfin surface these via a separate "Next Up" rail. We have all the data to do the same.

## What already exists (do NOT rebuild)
- Server `EpisodicFollowService.listForProfile(client, profileId, limit)`
  (`src/modules/watch/episodic-follow.service.ts`) computes, for every show a profile is
  following (from continue-watching / history / watchlist), the **next episode** to watch, via
  `MetadataProjectionService.resolveNextEpisodes` → `tmdb_tv_episodes` ordering + air dates.
- Endpoint `GET /admin/api/accounts/:accountId/profiles/:profileId/episodic-follow`
  (`src/http/routes/admin-api.ts:291`) returns `{ kind: 'episodic-follow', items: EpisodicFollowView[] }`.
  Same `requireAdmin` guard as `continue-watching` (`:213`) and `calendar/this-week` (`:315`),
  both of which the TV app already calls successfully — so **no auth change needed**.
- DB has the data: 1,296 completed-episode events across 11 series + `tmdb_tv_episodes`
  (11,980 rows / 84 shows). `EpisodicFollowView` (`watch-episodic-follow.types.ts:21`) carries
  `show` (series `MetadataCardView`), `nextEpisodeItemId`, `nextEpisodeSeasonNumber`,
  `nextEpisodeEpisodeNumber`, `nextEpisodeTitle`, `nextEpisodeAirDate`, `reason`.
- Client already has `HomeWideRailSectionKind.UP_NEXT` and `UP_NEXT_SECTION_KEY = "upNext"`
  (`HomeViewModel.kt:62-70`), plus the working `continueWatching` + `thisWeek` loaders as templates.

## Decision
Reuse `episodic-follow` for the Up Next rail (no new server next-episode logic — avoids layering).
Calendar (This Week, air-date based) is already implemented; leave it, only verify.

## Server-side changes — minimal / likely none
1. **Verify** `EpisodicFollowService` candidates include the 0%-progress imported episodes.
   `queryContinueWatching` (`episodic-follow.service.ts:140`) pulls from `playback_progress`;
   the 11 imported rows have `media_type='episode'` + season/episode, so they should seed a
   follow entry. If they are excluded, extend the candidate query (or rely on `queryHistory`
   completed-episode events, which already exist) — small tweak only if verification fails.
2. No new endpoint, no new next-episode computation.

## Client changes (the actual work)
Mirror the existing `continueWatching` / `thisWeek` wiring:

1. **Backend client** (`android/app/.../backend/CrispyBackendClient.kt`, ~line 833 by
   `getCalendarThisWeek`): add
   - `suspend fun getUpNext(accessToken, profileId, limit): UpNextResponse`
   - `UpNextResponse` data class parsing `EpisodicFollowView` (show card + nextEpisode* fields).
   - API call `getUpNextApi` to `/admin/api/accounts/{accountId}/profiles/{profileId}/episodic-follow`.

2. **Home refresh coordinator** (`android/app/.../home/HomeRefreshCoordinator.kt`, by
   `loadThisWeekSection` at `:126`): add `suspend fun loadUpNextSection(): HomeWideRailSectionUi`
   that calls `backendClient.getUpNext(...)`, maps each `EpisodicFollowView` to a
   `CanonicalContinueWatchingItem`:
   - `id = nextEpisodeItemId` (episode playable id)
   - `titleItemId = show.id` (series id)
   - `itemType = "episode"`, `season = nextEpisodeSeasonNumber`, `episode = nextEpisodeEpisodeNumber`
   - `title = show.title`, `episodeTitle = nextEpisodeTitle`
   - `playbackItemId = nextEpisodeItemId`
   then `items.map { it.toWideRailItem(nowMs) }` into the `upNext` rail (same shape as
   `continueWatching` at `:107-114`).

3. **Wire into home refresh** (`HomeViewModel.kt` / `HomeRefreshCoordinator`): call
   `loadUpNextSection()` alongside `loadContinueWatching()` and `loadThisWeekSection()` and apply
   to `snapshot.upNext` (already a field at `HomeViewModel.kt:62`).

4. **Click/play reuse**: `CanonicalContinueWatchingItem` flows through the existing
   `HomeSelectorViewModel.openFor` → `emitPlay` (which now uses `item.id` = episode id after the
   earlier fix), so clicking an Up Next episode opens/plays the correct next episode with no new
   playback code.

5. **Optional dedup**: if a show is already in Continue Watching (resume), optionally hide it in
   Up Next to avoid two cards for the same series. Low priority; can ship without it.

## Files to touch
- `android/app/src/main/java/com/crispy/tv/backend/CrispyBackendClient.kt` (add `getUpNext`)
- `android/app/src/main/java/com/crispy/tv/home/HomeRefreshCoordinator.kt` (add `loadUpNextSection`)
- `android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt` (invoke + apply; `upNext` field exists)
- `android/app/src/main/java/com/crispy/tv/home/HomeUiModels.kt` (mapping helper if needed)
- Server: only if verification fails — `src/modules/watch/episodic-follow.service.ts` candidate query.

## Verification
- Backend (no JDK, but TS): `node --import tsx --test "src/modules/watch/episodic-follow.service.test.ts"`
  (add/extend a test asserting next-episode resolution for a watched series).
- Client: **cannot compile locally (no JDK)** — CI validates on push. Commit Android on `enhancements`, never push.
- Manual: deploy, open home, confirm Up Next rail shows next episode per in-progress series and
  clicking it plays the correct episode. Confirm This Week (calendar) still renders unaired episodes.

## Notes
- This is the proper fix for "episodes missing from home": CW stays strict resume (0%<p<100%),
  Up Next surfaces the next episode from watch history — matching Nuvio/Jellyfin.
- No new server logic = no layering; we reuse `EpisodicFollowService`.
