# Mark / Unmark Watched — Android TV UI Plan (`crispy-rewrite`)

Status: Planning (not yet implemented).
Related server work (already merged): `4267b07` remove-from-history cascade, `22d0795` mark/unmark watched season/show cascade.

## Goal

Expose mark/unmark-watched from the Android TV UI on four surfaces:

1. **Details page — "Mark watched" quick action for whole SHOWS** (currently movie-only).
2. **Season pills long-press** (Details) → whole season (+ its episodes, via server cascade).
3. **Library cards long-press** (History / Watchlist / Ratings) → that title.
4. **Episode cards long-press** → already implemented; verify only.

## Interaction rule (locked)

> **Every long-press mark/unmark (season pill, library card, episode card) ALWAYS opens a bottom sheet** that offers an explicit **Mark watched / Mark unwatched** choice. Never apply the mutation directly from the long-press.
>
> The **Details "Watched" quick-action button** is a *tap*, not a long-press, and stays a direct toggle (matches the existing movie behavior). It is the one exception to the sheet rule.

The episode-card long-press already follows this pattern (`DetailsScreen.kt` → inline `ModalBottomSheet` → `onToggleEpisodeWatched`). Season pills and library cards must mirror it.

## Server contract (verified)

- Mark/unmark-watched request body = `itemId` + optional `seasonNumber`/`episodeNumber` (+ `occurredAt`/`rating`/`payload`). **No `mediaType` field.**
- The server **re-resolves `mediaType` from the itemId** (`contentIdentityRepo.findContentItemById`), so the client's `contentType` is advisory/ignored.
- Cascade behavior:
  - movie `itemId` → that movie
  - episode `itemId` + `seasonNumber`/`episodeNumber` → that one episode
  - **season `itemId` → whole season + its episodes**
  - **show `itemId` → whole show + all episodes**
- Both routes publish a `continue_watching` invalidation.

**Implication:** the client only needs the correct `itemId`. mediaType is derived server-side.

## Locked decisions

- **Library show-level removal is OK.** A long-press on a collapsed episode group marks the **whole show** (its `itemId` is already the series itemId). Intended behavior.
- **Episode groups are flattened at month level** (`collapseEpisodesByShow` runs per `historyMonthKey` bucket). Same series in different months → separate cards. Unchanged.
- **Long-press → always a bottom sheet** (explicit Mark/Unmark). No direct toggles from long-press.
- Episode-card long-press is already done (bottom-sheet → `toggleEpisodeWatched`).

---

## Surface 1 — Details "Mark whole show watched" button (tap, direct toggle)

Currently the Watched quick action is gated to movies.

### Changes

**`details/DetailsHeader.kt`**
- Line 461: `showWatched = details.itemType == "movie"` → show for shows too, e.g. `details.itemType != "movie"` (or `details.itemType == "movie" || details.itemType == "series"`). Movie behavior unchanged.

**`details/DetailsViewModel.kt` — `toggleWatched()` (line 937)**
- Remove the hard guard:
  ```kotlin
  if (mediaType != MetadataLabMediaType.MOVIE) {
      _uiState.update { it.copy(statusMessage = "Marking an entire episodic title as watched isn't supported yet...") }
      return
  }
  ```
- `updateWatched(details, desired)` already builds `WatchHistoryRequest(itemId = details.itemId, ...)` via `buildTitleWatchHistoryRequest` (`DetailsUseCases.kt:448`) → `details.itemId` is the **show** itemId. Server resolves `show` and cascades to all episodes.
- Existing post-mutation `resolveProviderState` / `resolveWatchCta` / `isWatched` refresh keeps the UI in sync. No further change.

**Files:** `DetailsHeader.kt`, `DetailsViewModel.kt`.
**Verify:** Open a show → Watched action visible → tap → whole show marked (server cascade); season-grid episodes reflect watched; snackbar confirms.

---

## Surface 2 — Season pills long-press → bottom sheet (Details)

Season itemIds exist on the API (`MetadataSeasonView.itemId`) but are **currently dropped** — `DetailsUiState.seasons` / `DetailsScreenLoadResult.seasons` are `List<Int>` (season numbers only).

### Step A — Carry season itemIds

**`details/DetailsUseCases.kt`**
- `DetailsScreenLoadResult.seasons` (line 33, `List<Int>`) → keep, and add `seasonItemIds: Map<Int, String> = emptyMap()`.
- At the load-site (`DetailsViewModel.kt` ~line 207–235 where `extrasSeasons = titleExtras?.seasons?.map { it.seasonNumber }...`), also compute:
  ```kotlin
  val seasonItemIds = titleExtras?.seasons
      ?.filter { it.seasonNumber > 0 }
      ?.associate { it.seasonNumber to it.itemId }
      .orEmpty()
  ```
  and set `seasonItemIds = seasonItemIds` in the `state.copy(...)`.

**`details/DetailsState.kt`**
- Add `val seasonItemIds: Map<Int, String> = emptyMap()` and `val seasonWatchStates: Map<Int, Boolean> = emptyMap()` to `DetailsUiState`.

**`details/DetailsViewModel.kt`**
- Initialize both to empty in the reset state (line ~104) and pass through in the loaded copy.

### Step B — Wire long-press on the season chip

**`details/DetailsBody.kt`**
- `detailsBodyContent(...)` already takes `onSeasonSelected: (Int) -> Unit`. Add `onSeasonLongPress: (seasonItemId: String, seasonNumber: Int) -> Unit`.
- Season `FilterChip` (line 226): add `onLongClick = { onSeasonLongPress(seasonItemIds[season] ?: return@FilterChip, season) }`.
- Show a check/active tint on the chip when `seasonWatchStates[season] == true`.

**`details/DetailsScreen.kt`**
- Add param `onSeasonLongPress: (String, Int) -> Unit`; pass through to `detailsBodyContent` (near line 306).
- Add screen-local state mirroring the episode pattern:
  ```kotlin
  var selectedSeasonAction by remember { mutableStateOf<Pair<String, Int>?>(null) }
  val seasonSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ```
  and set `onSeasonLongPress = { itemId, num -> selectedSeasonAction = itemId to num }`.
- Add an inline `ModalBottomSheet` (same shape as the episode one at lines 436–475):
  - Title: `"Season $seasonNumber"`.
  - "Open season" button → `onSeasonSelected(seasonNumber)`.
  - "Mark season $seasonNumber watched" / "Mark season $seasonNumber unwatched" `TextButton` → `onToggleSeasonWatched(seasonItemId, seasonNumber)`, then dismiss. Label derived from `seasonWatchStates[seasonNumber]` (default to "watched" when unknown).

**`details/DetailsRoute.kt`**
- Add `onSeasonLongPress = viewModel::<setter>` (the lambda that stores `selectedSeasonAction`) and keep `onToggleSeasonWatched = viewModel::toggleSeasonWatched`.

### Step C — ViewModel + use case

**`details/DetailsUseCases.kt`** — add next to `updateEpisodeWatched`:
```kotlin
suspend fun updateSeasonWatched(details: MediaDetails, seasonItemId: String, desired: Boolean): DetailsMutationResult {
    val request = WatchHistoryRequest(
        itemId = seasonItemId,
        contentType = MetadataLabMediaType.SERIES, // advisory; server re-resolves from itemId
        title = details.title,
    )
    val result = if (desired) userMediaRepository.markWatched(request)
                 else userMediaRepository.unmarkWatched(request)
    return DetailsMutationResult(details = details, success = mutationSucceeded(result), statusMessage = result.statusMessage)
}
```

**`details/DetailsViewModel.kt`** — add:
```kotlin
fun toggleSeasonWatched(seasonItemId: String, seasonNumber: Int) {
    val details = uiState.value.details ?: return
    val currentlyWatched = uiState.value.seasonWatchStates[seasonNumber] ?: false
    val desired = !currentlyWatched
    viewModelScope.launch {
        _uiState.update { it.copy(statusMessage = if (desired) "Marking season $seasonNumber watched..." else "Marking season $seasonNumber unwatched...") }
        val result = withContext(Dispatchers.IO) { detailsUseCases.updateSeasonWatched(details, seasonItemId, desired) }
        if (result.success) {
            _uiState.update { it.copy(seasonWatchStates = it.seasonWatchStates + (seasonNumber to desired)) }
            if (uiState.value.selectedSeasonOrFirst == seasonNumber) {
                detailsUseCases.clearEpisodeWatchStateCache()
                val refreshed = _uiState.value.seasonEpisodes
                val states = withContext(Dispatchers.IO) { detailsUseCases.resolveEpisodeWatchStates(result.details, refreshed) }
                _uiState.update { it.copy(episodeWatchStates = states) }
            }
            HomeRefreshBus.emit(HomeRefreshEvent.WatchlistChanged)
        }
        _uiState.update { it.copy(statusMessage = result.statusMessage) }
    }
}
```

### Step D — Season watched state (checkmark + sheet label)

- `seasonWatchStates: Map<Int, Boolean>` in `DetailsUiState`.
- For the **selected** season: when its episodes load (`loadEpisodeWatchStatesForSeason`, line ~253), derive watched = `seasonEpisodes.isNotEmpty() && seasonEpisodes.all { episodeWatchStates[it.id]?.isWatched == true }` and store into `seasonWatchStates`.
- Long-press still opens the sheet; the sheet's label uses `seasonWatchStates[seasonNumber]` (unknown → "Mark watched"). The actual mutation goes through the sheet's explicit action, never directly.

**Files:** `DetailsUseCases.kt`, `DetailsState.kt`, `DetailsBody.kt`, `DetailsScreen.kt`, `DetailsRoute.kt`, `DetailsViewModel.kt`.

---

## Surface 3 — Library cards long-press → bottom sheet

### Step A — `LandscapeCard` long-press

**`ui/components/LandscapeCard.kt`** (currently `onClick: () -> Unit` only, line 44/47)
- Add `onLongPress: () -> Unit = {}` and switch the `Box` modifier from `.clickable(onClick = onClick)` to `.combinedClickable(onClick = onClick, onLongClick = onLongPress)`.
- Only used by the library, so a defaulted param is safe.

### Step B — Thread long-press + bottom sheet through library

**`library/LibraryScreen.kt`**
- `historyItems` / `ratingsItems` / `watchlistItems` (lines ~372/425/477) take `onItemClick: (CatalogItem, String?) -> Unit`. Add `onItemLongPress: (CatalogItem) -> Unit` and pass `onLongPress = { onItemLongPress(item) }` to each `LandscapeCard`.
- (Optional, recommended) Harden `collapseEpisodesByShow` (line 159) to merge by `itemId` **regardless of `episodeCount`**, summing `episodeCount` when present. Prevents a missing-merge duplicate if the server ever returns a whole-show history entry alongside collapsed episodes in the same month.

**`library/LibraryRoute.kt`**
- Add screen-local state:
  ```kotlin
  var selectedLibraryItem by remember { mutableStateOf<CatalogItem?>(null) }
  val librarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ```
  and pass `onItemLongPress = { selectedLibraryItem = it }` into the three section composables.
- Add an inline `ModalBottomSheet` (mirror the Details episode sheet):
  - Title: `item.title`.
  - Subtitle/label: `if (item.episodeCount != null) "$episodeCount episodes" else item.type`.
  - "Open" button → `onItemClick(item, sharedElementKeyFor(item))`.
  - "Mark watched" / "Mark unwatched" `TextButton` → `viewModel.setWatched(item, desired)` where `desired = item.watchedAt == null` (or inverse for unwatched), then dismiss. Show both labels based on `item.watchedAt != null`.
  - Needs `ModalBottomSheet` / `rememberModalBottomSheetState` imports.

### Step C — ViewModel plumbing

**`library/LibraryViewModel.kt`**
- Add `userMediaRepository: UserMediaRepository` to the constructor + `factory` (mirror how `DetailsUseCases` holds it; source via the existing repository provider).
- Add:
  ```kotlin
  fun setWatched(item: CatalogItem, desired: Boolean) {
      viewModelScope.launch {
          val contentType = if (item.type == "movie") MetadataLabMediaType.MOVIE else MetadataLabMediaType.SERIES
          val request = WatchHistoryRequest(itemId = item.itemId, contentType = contentType, title = item.title)
          val result = withContext(Dispatchers.IO) {
              if (desired) userMediaRepository.markWatched(request) else userMediaRepository.unmarkWatched(request)
          }
          if (result.accepted) {
              HomeRefreshBus.emit(HomeRefreshEvent.WatchlistChanged)
              items.refresh() // refresh paging to reflect new state
          }
      }
  }
  ```
  - For **collapsed episode groups**: `item.itemId` is already the **series** itemId (see Episode-group handling) → server marks the whole show. No special-casing.
  - `desired` is chosen by the sheet (Mark vs Unmark), not auto-derived in the VM.
- **Feedback:** `LibraryRoute` has no snackbar today. Show a `Toast` after the action (or reuse a status line). Keep minimal.

### Episode-group handling (recap, confirmed)
- Server returns episodes individually; each `ClientMediaCard` carries `parent.seriesItemId`/`seriesTitle`.
- `ClientMediaCard.toCatalogItem` (`LibraryPagingSource.kt:95`): for an episode, `itemId = showItemId` (series), `title = seriesTitle`, `type` forced to `"show"`, `episodeCount = 1`, real episode id kept in `CatalogItem.id`.
- `collapseEpisodesByShow` merges same-`itemId` (series) items **within each month bucket** into one card with an `"N episodes"` badge.
- Therefore a long-press on any library card operates on a series/movie itemId. For a collapsed group this = whole show. **Show-level removal is acceptable** (decided).

**Files:** `LandscapeCard.kt`, `LibraryScreen.kt`, `LibraryRoute.kt`, `LibraryViewModel.kt`, `LibraryPagingSource.kt` (collapse hardening only).

---

## Surface 4 — Episode cards long-press (verify only)

Already implemented and matches the locked rule:
- `EpisodeCard` has `onLongPress` → `onEpisodeLongPress(video)` (`DetailsEpisodesSection.kt:50/54`).
- `DetailsScreen.kt:313` wires it to a bottom sheet (`selectedEpisodeAction`).
- Sheet's "Mark as watched/unwatched" → `toggleEpisodeWatched` → `DetailsUseCases.updateEpisodeWatched` (show itemId + season + episode) → server narrows to the one episode.

**Verify:** long-press an episode → sheet → Mark/Unmark → single episode toggles; server cascade not triggered.

---

## Cross-cutting

- **Refresh:** after every successful mutation, `HomeRefreshBus.emit(HomeRefreshEvent.WatchlistChanged)` (details already does; add to library).
- **Feedback:** details uses `SnackbarHostState` driven by `uiState.statusMessage` (`DetailsScreen.kt:219–229`); reuse for show + season messages. Library: add a `Toast`/status (new).
- **mediaType is advisory** on the client for mark/unmark; server is source of truth. Don't rely on client `contentType` for correct cascade.
- **Access token / profile:** mark/unmark require a signed-in profile; the existing `userMediaRepository` path already resolves `BackendContextResolver`. No new auth work.
- **Long-press never mutates directly** — all mark/unmark from long-press flows through a bottom sheet's explicit action.

## Files touched (summary)

| File | Change |
|------|--------|
| `details/DetailsHeader.kt` | Show Watched action for shows (not just movies) |
| `details/DetailsViewModel.kt` | Unblock `toggleWatched` for shows; add `toggleSeasonWatched`; add `seasonItemIds`/`seasonWatchStates` state; capture season itemIds on load |
| `details/DetailsUseCases.kt` | Add `updateSeasonWatched`; carry `seasonItemIds` in result |
| `details/DetailsState.kt` | Add `seasonItemIds`, `seasonWatchStates` |
| `details/DetailsBody.kt` | Season chip `onLongClick` → `onSeasonLongPress`; watched check |
| `details/DetailsScreen.kt` | Thread `onSeasonLongPress`; add `selectedSeasonAction` + season `ModalBottomSheet` (mirror episode sheet) |
| `details/DetailsRoute.kt` | Wire `onSeasonLongPress` / `onToggleSeasonWatched` |
| `ui/components/LandscapeCard.kt` | Add `onLongPress` via `combinedClickable` |
| `library/LibraryScreen.kt` | Thread `onItemLongPress`; harden `collapseEpisodesByShow` |
| `library/LibraryRoute.kt` | Add `selectedLibraryItem` + `ModalBottomSheet`; pass `onItemLongPress`; Toast feedback |
| `library/LibraryViewModel.kt` | Inject `UserMediaRepository`; add `setWatched(item, desired)`; refresh paging |
| `library/LibraryPagingSource.kt` | (collapse hardening only) |

## Verification plan

Manual (emulator / ATV):
1. Movie details → Watched taps movie on/off (unchanged).
2. Show details → Watched action visible → taps → whole show marked; season grid episodes reflect watched.
3. Season pill long-press → bottom sheet → "Mark season N watched" → season episodes marked; checkmark on pill; sheet "Mark season N unwatched" reverses.
4. Library History: long-press a show card → sheet → Mark → whole show marked; collapsed "N episodes" group marks whole show; refresh shows updated state; no duplicate card for same series in same month.
5. Library Watchlist / Ratings: long-press → sheet → Mark/Unmark.
6. Episode card long-press → sheet → single episode toggle (existing).

Unit (where feasible):
- `updateSeasonWatched` builds request with season itemId.
- `toggleSeasonWatched` flips `seasonWatchStates` and refreshes selected-season episode states.
- `collapseEpisodesByShow` merges whole-show + episode entries by `itemId` without duplicate.
- `LibraryViewModel.setWatched` calls mark/unmark with `item.itemId` and the sheet-chosen `desired`.

## Risks / open items
- **Whole-show history entry** could surface a duplicate card in the same month if the server returns both a series row and episode rows — mitigated by the `collapseEpisodesByShow` hardening (Step B optional). Confirm server history shape before/after implementing.
- **Library feedback** has no snackbar; a Toast is the minimal path. Consider a shared status component later.
- **Sheet for the Details tap "Watched" button** is intentionally NOT added (tap stays a direct toggle, matching the existing movie button). Flag if you want the button sheeted too.
