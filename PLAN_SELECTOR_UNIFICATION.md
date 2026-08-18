# Plan: Unified Stream Selector (no UX change) + Continue Watching direct entry

## Constraint (locked)
- No UX change anywhere. Selector stays a bottom-sheet ModalBottomSheet in every surface.
- Opens in exactly three places: Home (via Continue Watching), Details, Player. Nowhere else.
- `autoOpenEpisode` on `AppRoutes.homeDetailsRoute` is left as-is.

## Target architecture
One shared core, three modal surfaces — no standalone route.

### Shared (new files in `com.crispy.tv.streams`, one definition each)
1. `streams/SelectorCoordinator.kt` — single logic class. Holds `StreamSelectorUiState` as `MutableStateFlow`. One instance per VM (not a singleton).
   - `open(target, headerEpisode, fallbackDetails, itemIdForMetadata, session, onStreamSelected)`
   - On open(): in parallel -> `streamResolver.resolve(target, ...)` (always) + if `fallbackDetails == null && itemIdForMetadata != null && session != null` -> `getMetadataItemDetail(itemId)` (updates `enrichedDetails`).
   - `dismiss()`, `onProviderSelected(id)`, `onRetryProvider(id)`.
   - Exposes `state`, `details`, `headerEpisode` flows.
2. `streams/StreamSelectorContent.kt` — shared LazyColumn body (header, chips, empty/error, stream list, loading row). No ModalBottomSheet.
3. `streams/StreamSelectorModal.kt` — shared ModalBottomSheet wrapper around `StreamSelectorContent`. Gated by `state.visible`. Height 0.92f, scrimColor = Color.Transparent.
4. Shared sub-composables moved into `streams/` (delete both private copies): `StreamSheetHeader`, `ProviderChipsRow`, `StreamRow`, `ProviderErrorRow`, `LoadingMoreStreamsRow`, `episodeHeaderMetadata`, `formatEpisodeReleaseDate` (unifies `formatEpisodeDate`/`formatEpisodeHeaderDate`).

### Three surfaces (thin wrappers)
| Surface | File | Coordinator owner | Initial fallback |
|---|---|---|---|
| Player modal | `playerui/PlayerStreamsSheet.kt` (slim to ~25 lines) | `PlayerSessionViewModel` | `uiState.details` -> no fetch |
| Details modal | `details/StreamSelectorBottomSheet.kt` (slim to ~25 lines) | `DetailsViewModel` | `state.details` -> no fetch |
| Home modal (new) | `home/HomeStreamSelector.kt` | new `home/HomeSelectorViewModel` | `null` -> coordinator fetches |

## Metadata enrichment
- Player & Details: pass `fallbackDetails = already-loaded MediaDetails` -> coordinator skips fetch. Zero extra calls.
- Home (CW): `fallbackDetails = null` + `itemIdForMetadata = item.titleItemId` + `session = currentSession` -> coordinator fetches `getMetadataItemDetail(itemId)` in parallel with `streamResolver.resolve(target)`. Header shows route-arg fallback instantly; upgrades when fetch lands. No added latency.
- Failure: metadata fetch failure keeps route-arg fallback header; streams still resolve (addon call needs only lookupId).

## Home / Continue Watching wiring (locked: event emission)
1. `home/HomeSelectorViewModel.kt` (new) — deps: `StreamResolver`, `CrispyBackendClient`, session provider (`supabase.ensureValidSession()`). Holds one `SelectorCoordinator`. Exposes `coordinator.state/details/headerEpisode`. Emits `playStream: SharedFlow<PlaybackIdentity>` (option a) when stream picked. `openFor(item)`: builds minimal `MediaDetails` + `headerEpisode: MediaVideo` + `target = StreamLookupTarget(mediaType, buildAddonEpisodeLookupId(imdbId, season, episode))`, calls `coordinator.open(...)`.
2. `home/HomeStreamSelector.kt` (new, thin) — collects coordinator flows, renders `StreamSelectorModal`.
3. `home/HomeRoute` (`HomeScreen.kt`) — add `HomeSelectorViewModel`, render `HomeStreamSelector`, change `onContinueWatchingClick` -> `selectorViewModel.openFor(item)`, collect `playStream` -> navigate to `PlayerRoute(identity)` + `dismiss()`. `onContinueWatchingOpenDetails` stays unchanged.
4. No `AppRoutes` changes.

## Split-brains removed
- Delete both private copies of `StreamSheetHeader`, `ProviderChipsRow`, `StreamRow`, `ProviderErrorRow`, `LoadingMoreStreamsRow`, `episodeHeaderMetadata`, `formatEpisodeHeaderDate`/`formatEpisodeDate`.
- Remove duplicated `applyProviderResult`/`matchesTarget` call sites in `PlayerSessionViewModel` and `DetailsViewModel` (now inside coordinator).
- Remove unused `currentEpisodeForHeader` from `PlayerSessionViewModel`.
- Delete `formatEpisodeDate` from `PlayerEpisodeRow.kt`; point `episodeRowMeta` at shared `formatEpisodeReleaseDate`.

## Implementation order
1. `streams/SelectorCoordinator.kt` (+ factory).
2. Extract shared UI -> `streams/StreamSelectorContent.kt`, `StreamSelectorModal.kt`, `StreamSheetHeader`, `ProviderChipsRow`, `StreamRow`, `ProviderErrorRow`, `LoadingMoreStreamsRow`, `episodeHeaderMetadata`, `formatEpisodeReleaseDate`.
3. Slim `PlayerStreamsSheet.kt` -> `StreamSelectorModal` wrapper; wire `PlayerSessionViewModel` to own a `SelectorCoordinator`; remove duplicated selector state-handling; stream pick -> `switchPlayback`. Delete unused `currentEpisodeForHeader` + header-episode derivation.
4. Slim `StreamSelectorBottomSheet.kt` same way; wire `DetailsViewModel`.
5. Delete `formatEpisodeDate` from `PlayerEpisodeRow.kt`; point `episodeRowMeta` at shared formatter.
6. `home/HomeSelectorViewModel.kt` (+ factory), `home/HomeStreamSelector.kt`, minimal `MediaDetails` builder from `CanonicalContinueWatchingItem`.
7. `home/HomeRoute` wiring.
8. Verify: `python3 scripts/validate_contracts.py`, `gradle :android:contract-tests:test`, `gradle :android:app:lintDebug`.

## Decisions locked
- Event emission (option a) for HomeSelectorViewModel -> Player navigation.
- Coordinator is per-VM (not a singleton); state local to each surface.
- `scrimColor = Color.Transparent` on the modal.
- HomeSelectorViewModel reuses PlayerSessionViewModel's session pattern (`supabase.ensureValidSession()`).
- `autoOpenEpisode` left as-is.
