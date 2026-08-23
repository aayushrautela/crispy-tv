# Plan: Player self-fetches metadata + palette/skeleton gating (builds on selector extraction)

## Scope & relationship to existing plans
- **Selector extraction** (resolution out of the player) is already specified in
  `PLAN_SELECTOR_UNIFICATION.md`. That plan makes `PlayerSessionViewModel` own a
  `SelectorCoordinator` and deletes the duplicated selector state-handling. **This
  plan assumes that lands (or lands in parallel)** and focuses on the *new* asks:
  1. Player fetches its own display metadata; caller stops passing `title`/`subtitle`/`artworkUrl`.
  2. Player starts in the **default** colour and recomputes the palette only after metadata loads.
  3. **Info button disabled** until metadata loads.
  4. **Loading skeletons** for the main player top bar (title/subtitle), not just the info panel.
- `PLAN_PLAYER_FIXES.md` (R2.0 palette pills, R2.4 info→top-right, R2.6 narrower
  info panel) is complementary; this plan adds the *gating + skeleton* pieces those
  don't cover. Where they touch the same composables, implement together.

---

## Verified current behaviour (read, not assumed)
- **Caller contract** (`PlayerActivity.kt`): `intent(...)` passes `EXTRA_TITLE`,
  `EXTRA_SUBTITLE`, `EXTRA_ARTWORK_URL` (`:290-292`, `:338-340`) plus the full
  `PlaybackIdentity`. `parseIdentityFromIntent` (`:357-392`) reconstructs identity.
  The VM factory (`PlayerSessionViewModel.kt:1370-1397`) takes `title/subtitle/artworkUrl`.
- **VM init** builds `initialDetails = buildFallbackDetails(...)` from caller
  args (`PlayerSessionViewModel.kt:170-176`, `:1401-1441`). That fallback hard-codes
  `title`, `posterUrl/backdropUrl = artworkUrl`, `imdbId = null`, everything else empty.
- **Resolution is duplicated in the player**: `startPlayback` (`:399-479`),
  `openStreamSelector` (`:894-997`), `showStreams` (`:531-541`), `showStreamsForEpisode`
  (`:544-564`), `onRetryProvider` (`:576-641`) all re-roll `SelectorCoordinator`
  logic that already exists at `streams/SelectorCoordinator.kt` (used by
  `HomeSelectorViewModel.kt:46-54`).
- **Resolution keys on caller identity**: `resolveStreamLookupTargetFromIdentity`
  (`StreamLookupSupport.kt:47-60`) using `imdbId ?: itemId`. The player uses it at
  `:404` and `:536`. `resolveStreamLookupTarget(details, …)` (details-based, uses
  `details.imdbId`) exists but is only a *fallback* (`:658-668`).
- **Palette**: `PlayerRoute.kt:47-54` computes `imageUrl = uiState.backdropUrl ?:
  uiState.artworkUrl`; when null → `seedColor = fallbackSeed = MaterialTheme.colorScheme.primary`
  (default). Currently `artworkUrl` is the caller poster, so it's non-null → bad colour/artwork.
- **Info button**: `PlayerOverlayControls.kt:109-115` `IconButton(onClick = onShowInfo)`
  is always enabled; `PlayerTopBar` (`:56-117`) shows `title`/`subtitle` text directly.
- **Call sites passing display args** (must update):
  - `details/DetailsRoute.kt:32` — `onOpenPlayer` signature
    `(PlaybackIdentity, String, String?, String?, Long, String?, String?)` (identity, title, subtitle, artworkUrl, resume, chosenKey, chosenProvider).
  - `ui/navigation/HomeNavGraph.kt:197-208` — lambda + `PlayerActivity.intent(title=, subtitle=, artworkUrl=)`.
  - `home/HomeScreen.kt:81-90` — `PlayerActivity.intent(title=, subtitle=, artworkUrl=)`.
- **Metadata fetch** (`loadInitialMetadata`, `:736-777`) already calls
  `backendClient.getMetadataItemDetail` and on success sets `details`, `backdropUrl`,
  `artworkUrl = state.artworkUrl ?: backdrop ?: poster`. On failure (`itemId` invalid,
  e.g. the `2ebd2ce…` 400 in the logs) it `return`s early, leaving fallback data.
  The response **does** carry `imdbId` (`MetadataViewMappings.kt:35` `imdbId = externalIds.imdb`).
- **Media session** republishes every 500 ms in `pollPlaybackState` via
  `publishMediaSessionFromUiState()` using `uiState.artworkUrl/title`
  (`PlayerSessionViewModel.kt:1133-1189`), and `PlayerMediaSessionManager.loadArtwork`
  caps at `.size(960, 540)` (`PlayerMediaSessionManager.kt:165`).

---

## Design decisions (locked)
- **Identifiers stay; display assets go.** Caller still passes `PlaybackIdentity`
  (incl. `imdbId`/`itemId`) — that's a functional key, not display data, and resolution
  keys on `imdbId` (reliable even when backend `itemId` is invalid). `title`,
  `subtitle`, `artworkUrl` are removed from the contract.
- **Player fetches metadata itself** (already does via `loadInitialMetadata`). After
  fetch it owns `title`/`subtitle`/`backdrop`/`poster`/`logo`/`imdbId`.
- **Palette starts at default** (no seed image) and changes once `backdropUrl` arrives.
- **Info disabled until `isMetadataLoaded`** (true only on successful fetch). If the
  fetch fails, info stays disabled but streams still resolve via `imdbId`.
- **Resolution lives in `SelectorCoordinator`** (per `PLAN_SELECTOR_UNIFICATION.md`);
  the player only (a) opens it with a target and (b) reacts to a chosen stream.

---

## Implementation

### 1. Trim the caller contract (`PlayerActivity.kt`)
- Remove `EXTRA_TITLE`, `EXTRA_SUBTITLE`, `EXTRA_ARTWORK_URL` constants (`:290-292`)
  and their `putExtra`/`getStringExtra` lines (`:338-340`, `:357-392`).
- `intent(...)` signature: drop `title/subtitle/artworkUrl` params (`:327-336`).
- `PlayerSessionViewModel.factory(...)` (`:1370-1397`): drop `title/subtitle/artworkUrl`.

### 2. Update call sites (3 places)
- `details/DetailsRoute.kt:32`: `onOpenPlayer` becomes
  `(identity: PlaybackIdentity, resumePositionMs: Long, chosenStreamStableKey: String?, chosenProviderId: String?) -> Unit`.
- `ui/navigation/HomeNavGraph.kt:197-208`: drop the 3 args from the lambda and the `intent(...)` call.
- `home/HomeScreen.kt:81-90`: same — drop `title/subtitle/artworkUrl` from `intent(...)`.
- (Find any `DetailsScreen` → `onOpenPlayer(...)` call that supplies those args and trim it.)

### 3. `PlayerSessionViewModel` cleanup
- **Constructor**: remove `title/subtitle/artworkUrl`; keep `identity`,
  `resumePositionMs`, `chosenStreamStableKey`, `chosenProviderId`, `restorePlaybackIntent`.
- **Initial state** (`:182-198`):
  - `title = ""` (placeholder; UI shows skeleton until loaded).
  - `artworkUrl = null`, `backdropUrl = null`, `details = null`.
  - Add `isMetadataLoaded = false`.
  - Remove `initialDetails`/`buildFallbackDetails` usage. **Delete `buildFallbackDetails` (`:1401-1441)`.**
- **`loadInitialMetadata` (`:736-777`)**: on success set
  `details`, `backdropUrl`, `artworkUrl = backdrop ?: poster`, `title = details.title`,
  `subtitle`, `isMetadataLoaded = true`, then `mediaSessionManager.updateMetadata(...)`
  with the real values. On failure: log, leave `isMetadataLoaded = false`,
  `details = null` (info disabled); **do not** fall back to caller poster.
- **SelectorCoordinator ownership** (mirror `HomeSelectorViewModel.kt:46-54`):
  - Construct one coordinator with `scope = viewModelScope`, `streamResolver`,
    `getMetadataItemDetail`, `sessionTokenProvider = { supabase.ensureValidSession()?.accessToken }`.
  - Collect `coordinator.state` → `_uiState.update { copy(streamSelector = it) }`.
  - `onStreamChosen(stream)`: resolve source (`resolvePlaybackSource`) → `switchPlayback`.
    Wire `coordinator.open(..., onStreamSelected = ::onStreamChosen)`.
- **Remove duplicated resolution**: delete `openStreamSelector` (`:894-997`),
  `startPlayback` body (`:399-479` → replaced), `showStreams`/`showStreamsForEpisode`
  rework to `coordinator.open(target, …)`, `onRetryProvider` → `coordinator.onRetryProvider`,
  `onProviderSelected` → `coordinator.onProviderSelected`, drop `streamSelectorSession`/
  `streamSelectorJob` (now inside coordinator). Keep `resolvePlaybackSource`,
  `playResolvedStream`/`switchPlayback`, track/subtitle/watch-history/PIP/gesture code.
- **Target building (next-episode ready)**:
  - Initial auto-play target: build from `identity` via `resolveStreamLookupTargetFromIdentity`
    (immediate, works even if metadata fetch fails because it uses `imdbId`).
  - After `details` loads, prefer `resolveStreamLookupTarget(details, …)`; if different,
    `coordinator.open(...)` again with the details-based target (no UX flap if same).
  - `showStreamsForEpisode(videoId)` (`:544-564`): build
    `StreamLookupTarget(mediaType, episode.lookupId)` → `coordinator.open(...)`.
    This is exactly the hook future **Next Episode** uses — open coordinator for the
    next episode's `lookupId`, no `PlaybackIdentity` mutation required.
- **Auto-select**: in the `coordinator.state` collector, detect the first transition
  `isLoading: true → false` with providers present and no stream chosen yet → apply
  `chosenStreamStableKey`/`playbackSettingsRepository.autoSelectStream` (top stream) →
  `onStreamChosen`. (Replaces the `startPlayback` auto-select block `:458-478`.)
- Keep `rawPlaybackId`/`buildPlaybackRawId` + watch-history reporting as-is.

### 4. `PlayerRoute.kt` — palette + skeleton + info gating
- `imageUrl = uiState.backdropUrl ?: uiState.artworkUrl` already yields `null` until
  metadata loads (artworkUrl no longer caller-supplied, backdropUrl set only on fetch)
  → `seedColor = fallbackSeed` (default colour). **No forced change needed**, but gate
  explicitly: `val imageUrl = if (uiState.isMetadataLoaded) uiState.backdropUrl ?: uiState.artworkUrl else null`
  so a stale/empty URL never produces a wrong seed.
- Pass `metaLoaded = uiState.isMetadataLoaded` into `PlayerOverlay` → `PlayerTopBar`.

### 5. `PlayerOverlayControls.kt` — `PlayerTopBar`
- Add param `isMetadataLoaded: Boolean` (default the info button on it).
- When `!isMetadataLoaded`:
  - Render `PlayerTextSkeleton` placeholders for title + subtitle (shimmer) instead of
    the `Text` composables (`:80-106`).
  - `Info` `IconButton` (`:109-115`): `enabled = isMetadataLoaded`,
    `tint = if (isMetadataLoaded) palette.onPillBackground else palette.onPillBackground.copy(alpha = 0.4f)`.
- New small composable `PlayerTextSkeleton(modifier, palette)` — rounded-rect shimmer
  using `palette.pillBackground` (distinct from `DetailsHero`/`PlayerInfoSheet` skeletons;
  keep it tiny, ~200dp × 16dp for title, ~140dp × 12dp for subtitle).

### 6. Media session / notification (quality side-effect)
- Initial `mediaSessionManager.updateMetadata(title = "Loading…", subtitle = null, artworkUrl = null)`
  (`:203-207`) — no caller poster. The 500 ms poll loop already republishes with
  `uiState.artworkUrl/title` once metadata loads, so the notification **upgrades** to the
  fetched backdrop/poster (high-res source, not the old low-res caller poster). Keep the
  `.size(960, 540)` cap. This directly fixes the "bad notification image" complaint.

---

## Files
**Modify**
- `playerui/PlayerActivity.kt` (drop 3 extras + intent/factory params)
- `playerui/PlayerSessionViewModel.kt` (constructor trim; delete `buildFallbackDetails`,
  `openStreamSelector`, `startPlayback` resolution, duplicated selector handlers; add
  `SelectorCoordinator` ownership + `isMetadataLoaded`; keep playback/tracks/PIP/gesture)
- `playerui/PlayerRoute.kt` (palette gate on `isMetadataLoaded`)
- `playerui/PlayerOverlay.kt` (pass `metaLoaded`)
- `playerui/PlayerOverlayControls.kt` (`PlayerTopBar` skeleton + info-disable + `PlayerTextSkeleton`)
- `details/DetailsRoute.kt` (`onOpenPlayer` signature)
- `ui/navigation/HomeNavGraph.kt`, `home/HomeScreen.kt` (drop display args)
- `PlayerUiState` (add `isMetadataLoaded`; `title` init `""`)

**Depends on** `PLAN_SELECTOR_UNIFICATION.md` (coordinator shared UI/state) — do that
first or together; this plan's §3 coordinator wiring replaces the player's selector code
that unification deletes.

**No new modules** beyond what unification adds (`streams/SelectorCoordinator`,
shared `StreamSelector*` UI). Skeleton is a tiny local composable in `playerui`.

---

## Verification
- Update all 3 `PlayerActivity.intent(...)` call sites; confirm no remaining
  `title/subtitle/artworkUrl` passed.
- `python3 scripts/validate_contracts.py`
- `gradle :android:contract-tests:test`
- `gradle :android:app:lintDebug`
- Manual:
  - Open from **Details**: meta loads → palette shifts from default to backdrop-derived;
    Info becomes enabled; top-bar skeleton replaced by title.
  - Open from **Home Continue Watching** with a *bad* `itemId` (like the `2ebd2ce…` 400):
    info stays disabled, but streams still resolve via `imdbId`; notification shows
    placeholder then upgrades to fetched backdrop.
  - Notification artwork quality visibly better (backdrop source, not caller poster).
  - (Future) Next Episode reuses `showStreamsForEpisode` → `coordinator.open(nextEpisodeLookupId)`
    with no identity mutation.
