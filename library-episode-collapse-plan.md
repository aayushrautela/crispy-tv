# Plan: Collapse episode library cards into their show (History, monthly)

> Status: proposed — not yet implemented.
> Environment note: this environment has **no Gradle** (and no Android SDK wiring for a
> full app build), so the Android app cannot be compiled here. Do **not** attempt
> `gradle :android:app:assembleDebug` in this workspace. Also: **do not push** this
> commit anywhere. This plan document is the only deliverable to be committed;
> no code changes are made as part of recording the plan.

## Decisions
- **Scope:** History section only (it already buckets items by month).
- **Show name source:** small server change to populate `parent.seriesTitle`.
- **Collapsed card:** show poster + show title + an `"N episodes"` badge.

## 1. Server change (small, additive — no contract bump)
File: `crispy server/src/modules/watch/watch-card-hydrator.service.ts` → `toClientMediaCard`.

The `parent` object is built from `cardView` but never sets `seriesTitle`, even though the
contract (`shared.ts:447` `seriesTitle`) and `ClientMediaCard.parent`
(`client-home.types.ts:23`) already support it. Add it from the Jellyfin episode's
`SeriesName`:

```ts
parent: ... ? {
  seriesItemId: cardView.seriesItemId ?? undefined,
  seriesTitle: item.SeriesName ?? cardView.seriesTitle ?? undefined,  // NEW
  seasonItemId: cardView.seasonItemId ?? undefined,
  seasonNumber: cardView.seasonNumber,
  episodeNumber: cardView.episodeNumber,
} : null
```

This is additive/optional, so **no `contract_version` bump** and no schema change.
(Poster/backdrop on the episode card already resolve to the show's art — confirmed in
`metadata-card.types.ts:62` `artwork` vs `still` — so no image change needed.)

## 2. Android data model
File: `android/app/src/main/java/com/crispy/tv/catalog/CatalogModels.kt`
(`CatalogItem`, line 29).
- Add `val episodeCount: Int? = null` to `CatalogItem`.

## 3. Mapping: episode → show identity + navigation target
File: `android/app/src/main/java/com/crispy/tv/library/LibraryPagingSource.kt` →
`libraryCatalogItemFromProgress()` / `toCatalogItem` (lines 95–132).

When `card.mediaType == "episode"` and `card.parent?.seriesItemId != null`:
- `title = card.parent.seriesTitle ?: card.title`
- `itemId = card.parent.seriesItemId`  ← makes the existing `onItemClick`
  (`LibraryNavGraph.kt:20`) navigate to **show** details via
  `homeDetailsRoute(itemId, itemType="show")`. No nav change needed.
- `type` already maps to `"show"` (line 161).
- `episodeCount = 1` (marker so the collapse step can find/merge episodes).
- Leave `poster/backdrop` as-is (show art).

## 4. Collapse within the month (deterministic, pure)
File: `android/app/src/main/java/com/crispy/tv/library/LibraryScreen.kt`.

Add a pure function and apply it to history items **before** `buildHistoryMonthSections`
(so a show stays split per month, matching "monthly"):

```kotlin
fun collapseEpisodesByShow(items: List<CatalogItem>): List<CatalogItem> {
    val out = mutableListOf<CatalogItem>()
    val byShow = linkedMapOf<String, MutableList<CatalogItem>>() // preserve first-seen order
    for (item in items) {
        if (item.episodeCount == null) { out += item; continue }
        byShow.getOrPut(item.itemId) { mutableListOf() } += item
    }
    for ((_, group) in byShow) {
        val rep = group.first()
        out += rep.copy(episodeCount = group.size)
    }
    return out
}
```

Apply: `val monthSections = buildHistoryMonthSections(collapseEpisodesByShow(loadedItems))`
in `historyItems` (line 352). Non-episode items pass through untouched.

## 5. Badge UI
File: `android/app/src/main/java/com/crispy/tv/ui/components/LandscapeCard.kt` (line 43).
- Add optional `badge: String? = null`. When non-null, render a small pill (top-start
  overlay) e.g. `"$episodeCount episodes"`.
- In `LibraryScreen.kt` `historyItems` (line 375), pass
  `badge = if (item.episodeCount != null && item.episodeCount > 1) "${item.episodeCount} episodes" else null`.

## 6. Parity / contract note
This is Android **app-level UI**; the iOS side has no library screen in `ContractRunner`
(it mirrors `core-domain` only). So no Swift change is required now. Keep
`collapseEpisodesByShow` pure/deterministic (it is) so it can be mirrored later if iOS
builds a library. No new contract suite needed for this UI-only concern.

## 7. Tests / verification (deferred — no Gradle in this env)
- New unit test for `collapseEpisodesByShow` (determinism: order preserved, counts
  correct, non-episodes untouched). Place in `android:app` test source (make the function
  `internal`). Cannot be run here (no Gradle).
- `gradle :android:app:assembleDebug` — compile check (cannot run here).
- `gradle :android:contract-tests:test` — confirms no contract regressions (server change
  is additive; cannot run here).
- Manual: open Library → History → confirm episodes of one show in a month show as a
  single card with poster + show title + "N episodes", and tapping opens the show detail
  page.

## Out of scope (per decisions)
- Watchlist / Ratings sections (stay flat for episodes).
- Episode-specific screen, auto-scroll to S/E, and cast on episodes.
