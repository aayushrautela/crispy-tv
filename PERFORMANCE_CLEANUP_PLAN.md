# Performance Cleanup Implementation Plan

## Goal

Make Crispy feel closer to NuvioMobile by replacing the messy performance-critical paths with simpler implementation-level components. Do not layer hacks on top of `HomeViewModel` or scattered image/list code.

## Hard rules

- Do not run Gradle.
- Do not add workaround layers over the current messy structure.
- Refactor ownership first, then optimize.
- Prefer deleting or moving responsibilities over adding more state to existing classes.
- Do not add comments unless explicitly requested.
- Do not commit secrets or add logs containing tokens, profile IDs, backend payloads, or user data.

## Implementation sequence

1. Split home layout building out of `HomeViewModel`.
2. Split home refresh/load orchestration out of `HomeViewModel`.
3. Make recommendation disk cache dispatcher-safe.
4. Remove data-path skeleton delay.
5. Add request deduplication and cached-first home publishing.
6. Normalize image request creation and migrate high-traffic images.
7. Add duplicate-safe lazy keys and content types.
8. Move repeated UI normalization into UI model creation.
9. Verify with static review and manual app checks only; do not run Gradle.

---

## Step 1: Extract home layout building

### Create file

`android/app/src/main/java/com/crispy/tv/home/HomeLayoutBuilder.kt`

### Move/implement

Move the layout-only logic from `HomeViewModel.updateLayout()` into a pure function:

```kotlin
internal fun buildHomeLayoutState(
    wideRails: Map<String, HomeWideRailSectionUi>,
    catalogSectionLayoutMeta: List<CatalogSectionLayoutMeta>,
    catalogStatusMessage: String,
): HomeLayoutState
```

### Required behavior

- Preserve current ordering:
  1. Continue Watching
  2. Up Next
  3. This Week
  4. catalog status if no catalog sections and status exists
  5. catalog rows / grouped collection shelves
- Preserve current collection grouping behavior.
- Keep it pure: no flows, no coroutines, no I/O, no mutable shared state.

### Edit

In `HomeViewModel.kt`:

- Delete `private fun updateLayout()` body and replace calls with:

```kotlin
_layoutState.value = buildHomeLayoutState(
    wideRails = _wideRailSectionsState.value,
    catalogSectionLayoutMeta = catalogSectionLayoutMeta,
    catalogStatusMessage = catalogStatusMessage,
)
```

- Or keep a tiny private `updateLayout()` wrapper that only calls `buildHomeLayoutState`.

### Acceptance check

`HomeViewModel` no longer contains the `while (index < catalogSectionLayoutMeta.size)` collection grouping algorithm.

---

## Step 2: Extract home refresh orchestration

### Create file

`android/app/src/main/java/com/crispy/tv/home/HomeRefreshCoordinator.kt`

### New types

```kotlin
internal data class HomeRefreshSnapshot(
    val primary: HomePrimarySnapshot,
    val watchActivity: HomeWatchActivitySnapshot,
    val thisWeek: HomeWideRailSectionUi,
)

internal class HomeRefreshCoordinator(
    private val recommendationCatalogService: RecommendationCatalogService,
    private val homeWatchActivityService: HomeWatchActivityService,
    private val watchHistoryService: BackendWatchHistoryService,
    private val calendarService: HomeCalendarService,
    private val suppressionStore: HomeSuppressionStore,
)
```

Use the actual existing service types/imports from `HomeViewModel.kt`.

### Move from `HomeViewModel`

Move these responsibilities into `HomeRefreshCoordinator`:

- `loadPrimarySnapshot()`
- `loadWatchActivitySnapshot()`
- `loadThisWeekSection()`
- `loadCanonicalContinueWatching()`
- suppression read during watch activity load
- provider suppression filtering if it can be made independent of ViewModel state

### Keep in `HomeViewModel`

Keep only state application:

- `applyPrimarySnapshot(...)`
- `applyWatchActivitySnapshot(...)`
- `applyThisWeekSection(...)`
- `updateWideRailSection(...)`
- `suppressKeys(...)` initially, unless suppression ownership is fully moved cleanly

### New coordinator method

```kotlin
suspend fun loadAll(): HomeRefreshSnapshot = coroutineScope {
    val primary = async { loadPrimarySnapshot() }
    val watchActivity = async { loadWatchActivitySnapshot() }
    val thisWeek = async { loadThisWeekSection() }
    HomeRefreshSnapshot(
        primary = primary.await(),
        watchActivity = watchActivity.await(),
        thisWeek = thisWeek.await(),
    )
}
```

### Edit `HomeViewModel.refresh()`

Replace the current three inline `async` blocks with:

```kotlin
val snapshot = refreshCoordinator.loadAll()
if (isCurrentRefresh(currentRefreshGeneration)) {
    applyPrimarySnapshot(snapshot.primary)
    applyWatchActivitySnapshot(snapshot.watchActivity)
    applyThisWeekSection(snapshot.thisWeek)
}
```

### Acceptance check

`HomeViewModel.refresh()` no longer knows how primary/watch/calendar data is loaded. It only controls refresh generation and applies snapshots.

---

## Step 3: Make recommendation cache safe by construction

### Edit file

`android/app/src/main/java/com/crispy/tv/home/RecommendationCatalogDiskCacheStore.kt`

### Add imports

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

### Change signatures

From:

```kotlin
fun read(cacheKey: String, maxAgeMs: Long? = null): CachedPayload?
fun write(cacheKey: String, payload: String, timestampMs: Long = System.currentTimeMillis())
```

To:

```kotlin
suspend fun read(cacheKey: String, maxAgeMs: Long? = null): CachedPayload?
suspend fun write(cacheKey: String, payload: String, timestampMs: Long = System.currentTimeMillis())
```

### Implementation shape

```kotlin
suspend fun read(cacheKey: String, maxAgeMs: Long?): CachedPayload? = withContext(Dispatchers.IO) {
    val file = cacheFile(cacheKey)
    val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@withContext null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
    ...
}
```

```kotlin
suspend fun write(cacheKey: String, payload: String, timestampMs: Long) = withContext(Dispatchers.IO) {
    val normalizedPayload = payload.trim()
    if (normalizedPayload.isEmpty()) return@withContext
    val file = cacheFile(cacheKey)
    val json = JSONObject()
        .put("timestamp_ms", timestampMs)
        .put("payload", normalizedPayload)
        .toString()
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(json, StandardCharsets.UTF_8)
    }
    Unit
}
```

### Edit file

`android/app/src/main/java/com/crispy/tv/home/RecommendationCatalogService.kt`

Change:

```kotlin
private fun writeCachedSnapshot(...)
private fun readCachedSnapshot(...)
```

To:

```kotlin
private suspend fun writeCachedSnapshot(...)
private suspend fun readCachedSnapshot(...)
```

### Acceptance check

Search results should show no direct public synchronous calls to `RecommendationCatalogDiskCacheStore.read` or `.write`.

---

## Step 4: Remove data-path skeleton delay

### Edit file

`android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`

Or if moved, edit:

`android/app/src/main/java/com/crispy/tv/home/HomeRefreshCoordinator.kt`

### Remove

- `delayForMinimumSkeletonVisibility(...)`
- calls after successful loads
- calls in error branches

### Replacement behavior

Loading indicators should be controlled only by state:

- `prepareForRefresh(showForegroundLoading)` sets loading state.
- successful/error snapshot publishing clears loading state immediately.

### Acceptance check

No function named `delayForMinimumSkeletonVisibility` remains in the home data-loading path.

---

## Step 5: Add recommendation request deduplication

### Edit file

`android/app/src/main/java/com/crispy/tv/home/RecommendationCatalogService.kt`

### Add fields

```kotlin
private val inFlightMutex = Mutex()
private val inFlightSnapshots = mutableMapOf<String, Deferred<HomeCatalogSnapshot>>()
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Use imports:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

### Refactor `loadSnapshot()`

Split into:

```kotlin
private suspend fun loadSnapshot(): HomeCatalogSnapshot
private suspend fun loadSnapshotUncached(backendContext: BackendContext?): HomeCatalogSnapshot
```

### Dedup key

- If backend context exists: `recommendations:${backendContext.profileId.trim()}`
- If no backend context: `recommendations:anonymous`

### Implementation shape

```kotlin
private suspend fun loadSnapshot(): HomeCatalogSnapshot {
    val backendContext = getBackendContext()
    val requestKey = backendContext?.profileId?.trim()?.takeIf { it.isNotBlank() }
        ?.let { "recommendations:$it" }
        ?: "recommendations:anonymous"

    val deferred = inFlightMutex.withLock {
        inFlightSnapshots[requestKey] ?: serviceScope.async {
            loadSnapshotUncached(backendContext)
        }.also { created ->
            inFlightSnapshots[requestKey] = created
        }
    }

    return try {
        deferred.await()
    } finally {
        inFlightMutex.withLock {
            if (inFlightSnapshots[requestKey] === deferred) {
                inFlightSnapshots.remove(requestKey)
            }
        }
    }
}
```

### Cancellation requirement

Do not swallow `CancellationException` in `loadSnapshotUncached`.

### Acceptance check

Two concurrent home/discover calls to recommendations for the same profile share one backend call.

---

## Step 6: Add cached-first home publishing

### Edit file

`android/app/src/main/java/com/crispy/tv/home/RecommendationCatalogService.kt`

### Add method

```kotlin
suspend fun loadCachedPrimaryHomeFeed(
    heroLimit: Int = 10,
    sectionLimit: Int = Int.MAX_VALUE,
): HomePrimaryFeedLoadResult?
```

Behavior:

- Resolve backend context.
- Try profile cache first, then global cache.
- Do not call network.
- Use existing `planPersonalHomeFeed(...)` mapping.

### Avoid duplicate mapping

Extract shared mapper:

```kotlin
private fun HomeCatalogSnapshot.toPrimaryHomeFeedLoadResult(
    heroLimit: Int,
    sectionLimit: Int,
): HomePrimaryFeedLoadResult
```

Use it from both:

- `loadPrimaryHomeFeed(...)`
- `loadCachedPrimaryHomeFeed(...)`

### Edit coordinator

In `HomeRefreshCoordinator`, add:

```kotlin
suspend fun loadCachedPrimarySnapshot(): HomePrimarySnapshot?
```

This calls `recommendationCatalogService.loadCachedPrimaryHomeFeed(...)` and maps to `HomePrimarySnapshot`.

### Edit `HomeViewModel.refresh()`

Before foreground network load:

1. If showing foreground loading and no content loaded, try cached primary snapshot.
2. If found and refresh generation is current, apply it immediately.
3. Continue network refresh in same refresh flow.

### Acceptance check

On app start with cache present, home catalog/hero can appear before network completes.

---

## Step 7: Clean refresh lifecycle without adding state mess

### Edit file

`android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`

### Add method

```kotlin
fun ensureLoaded() {
    if (hasLoadedHomeContent() || refreshJob?.isActive == true) return
    refresh(forceForegroundLoading = true)
}
```

### Change init

Remove:

```kotlin
init {
    refresh(forceForegroundLoading = true)
}
```

### Edit route/screen owner

Find where `HomeViewModel` is created/used. In the Home route composable, call:

```kotlin
LaunchedEffect(viewModel) {
    viewModel.ensureLoaded()
}
```

### Keep refresh controls

- Pull-to-refresh still calls `refresh(forceForegroundLoading = true)` or equivalent existing method.
- `refreshIfStale()` still uses the same coordinator path.

### Acceptance check

ViewModel creation no longer automatically starts heavy work before the screen lifecycle triggers it.

---

## Step 8: Centralize image request identity

### Edit file

`android/app/src/main/java/com/crispy/tv/ui/components/CrispyImage.kt`

### Change function signature

From:

```kotlin
internal fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
    tmdbSize: String? = null,
    enableCrossfade: Boolean = false,
): Any?
```

To:

```kotlin
internal fun rememberCrispyImageModel(
    url: String?,
    width: Dp,
    height: Dp,
    tmdbSize: String? = null,
    cacheKey: String? = null,
    enableCrossfade: Boolean = false,
): Any?
```

### Build stable keys

Inside the remembered `ImageRequest`:

```kotlin
val normalizedCacheKey = cacheKey?.trim()?.takeIf { it.isNotEmpty() }
```

Apply:

```kotlin
.normalizedCacheKey?.let { memoryCacheKey(it) }
.normalizedCacheKey?.let { placeholderMemoryCacheKey(it) }
.diskCacheKey(resolvedUrl)
```

Actual Coil builder syntax should be direct calls:

```kotlin
if (normalizedCacheKey != null) {
    memoryCacheKey(normalizedCacheKey)
    placeholderMemoryCacheKey(normalizedCacheKey)
}
diskCacheKey(resolvedUrl)
```

### Add helper functions

Same file or a small new file:

`android/app/src/main/java/com/crispy/tv/ui/components/CrispyImageKeys.kt`

```kotlin
internal fun crispyPosterImageKey(type: String?, id: String?): String?
internal fun crispyBackdropImageKey(type: String?, id: String?): String?
internal fun crispyLogoImageKey(type: String?, id: String?): String?
internal fun crispyAvatarImageKey(id: String?): String?
```

Return null if stable ID is blank.

### Acceptance check

The shared image helper supports memory placeholder reuse without every screen building custom Coil requests.

---

## Step 9: Migrate high-traffic image usage first

### Edit files first

- `android/app/src/main/java/com/crispy/tv/ui/components/PosterCard.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeCatalogComponents.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeCollectionComponents.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeHeroCarousel.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeArtwork.kt`

### Required changes

- Pass a stable `cacheKey` into `rememberCrispyImageModel`.
- For poster cards, prefer key shape: `poster:<type>:<id>`.
- For backdrops: `backdrop:<type>:<id>`.
- For logos: `logo:<type>:<id>`.
- Keep existing TMDB size choices unless clearly wrong.

### If `PosterCard` lacks type/id

Change `PosterCard` parameters to accept optional identity:

```kotlin
mediaType: String? = null,
mediaId: String? = null,
```

Update call sites in:

- `DiscoverScreen.kt`
- `LibraryScreen.kt`
- `CatalogScreen.kt`
- `SearchScreen.kt`
- home catalog components

### Acceptance check

Scrolling poster grids should reuse memory placeholders when items are revisited.

---

## Step 10: Add duplicate-safe lazy keys

### Create file

`android/app/src/main/java/com/crispy/tv/ui/components/DuplicateSafeLazyKeys.kt`

### Implement

```kotlin
internal data class DuplicateSafeLazyEntry<T>(
    val value: T,
    val lazyKey: Any,
)

internal fun <T> List<T>.withDuplicateSafeLazyKeys(key: (T) -> Any): List<DuplicateSafeLazyEntry<T>> {
    val keyCounts = groupingBy(key).eachCount()
    val occurrences = mutableMapOf<Any, Int>()

    return map { entry ->
        val baseKey = key(entry)
        val lazyKey = if (keyCounts[baseKey] == 1) {
            baseKey
        } else {
            val occurrence = occurrences.getOrElse(baseKey) { 0 }
            occurrences[baseKey] = occurrence + 1
            "$baseKey#$occurrence"
        }
        DuplicateSafeLazyEntry(value = entry, lazyKey = lazyKey)
    }
}
```

### Apply first to home non-paging lists

Files:

- `HomeScreen.kt`
- `HomeCatalogComponents.kt`
- `HomeCollectionComponents.kt`

Use for lists where data is already materialized in memory.

### Do not apply blindly to Paging items

For `LazyPagingItems`, keep `itemKey`, but improve the key expression if source/addon/catalog is available.

### Acceptance check

Non-paging home rails cannot collide if two items share the same media ID/type.

---

## Step 11: Add Lazy content types

### Edit files

- `android/app/src/main/java/com/crispy/tv/discover/DiscoverScreen.kt`
- `android/app/src/main/java/com/crispy/tv/library/LibraryScreen.kt`
- `android/app/src/main/java/com/crispy/tv/catalog/CatalogScreen.kt`
- `android/app/src/main/java/com/crispy/tv/search/SearchScreen.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeCatalogComponents.kt`
- `android/app/src/main/java/com/crispy/tv/home/HomeCollectionComponents.kt`

### Patterns

For paging grid posters:

```kotlin
items(
    count = pagingItems.itemCount,
    key = pagingItems.itemKey { item -> "${item.type}:${item.id}" },
    contentType = { "posterCard" },
) { index ->
    ...
}
```

For skeletons:

```kotlin
items(
    count = DISCOVER_SKELETON_COUNT,
    key = { index -> "discover-skeleton-$index" },
    contentType = { "posterSkeleton" },
) { ... }
```

For headers/filter/status:

```kotlin
item(
    key = "discover-filters",
    contentType = "discoverFilters",
    span = { GridItemSpan(maxLineSpan) },
) { ... }
```

### Acceptance check

Every high-volume `LazyVerticalGrid`, `LazyColumn`, and `LazyRow` item block has explicit keys and content types where Compose supports them.

---

## Step 12: Move repeated UI normalization into model creation

### Edit model creation in

`android/app/src/main/java/com/crispy/tv/home/HomeViewModel.kt`

Or extracted coordinator/model mapper files after earlier steps.

### Add fields to UI models where needed

For catalog/wide rail item UI models, add optional precomputed fields:

```kotlin
val imageUrl: String?
val imageCacheKey: String?
val displayTitle: String
val displaySubtitle: String?
```

Only add fields if they replace repeated composable work.

### Move logic out of composables

Examples to remove from composables where possible:

- `item.posterUrl ?: item.backdropUrl`
- repeated `.trim().ifBlank { null }`
- repeated type/id string key building
- repeated title/year/genre fallback formatting

### Acceptance check

`HomeCatalogComponents.kt` and `HomeCollectionComponents.kt` mostly render values they receive instead of deriving display values repeatedly.

---

## Step 13: Clean raw AsyncImage usage

### Search

Search for:

```text
AsyncImage(
rememberAsyncImagePainter(
```

### Prioritize

1. Home images
2. Discover/catalog/library/search poster images
3. Details hero/cast/episodes
4. Player sheets and low-frequency settings images

### Required decision per usage

For each usage:

- Convert to `rememberCrispyImageModel` if it is app media imagery.
- Keep raw only if it is one-off and not in a high-frequency list.
- Use correct image size and cache key role.

### Acceptance check

High-frequency lists do not use raw URL image models.

---

## Step 14: Static verification only

Do not run Gradle.

### Use file searches/static review

Check these manually or with search tools:

- `delayForMinimumSkeletonVisibility` no longer exists in home loading.
- `RecommendationCatalogDiskCacheStore.read` and `.write` are suspend functions using `Dispatchers.IO` internally.
- `HomeViewModel` no longer contains layout grouping logic.
- `HomeViewModel` constructor/init does not launch heavy refresh work.
- High-volume Lazy layouts include `contentType`.
- Duplicate-safe lazy helper is used for home non-paging rails.
- Most home poster/backdrop/logo images pass `cacheKey` to `rememberCrispyImageModel`.
- Raw `AsyncImage(model = url)` is not used in high-volume poster grids.

### Manual app checks

Manual app checks are allowed. Gradle is not.

- Cold open home with cache available.
- Cold open home without network.
- Scroll home rails quickly.
- Scroll Discover grid quickly.
- Open details, go back, open same details again.
- Switch tabs repeatedly.
- Pull to refresh while previous refresh is active.

### Expected behavior

- Cached home content appears quickly.
- No obvious image blanking when navigating back to already-seen media.
- Pull-to-refresh does not duplicate work or flicker all sections unnecessarily.
- Scrolling grids should feel less janky.

---

## Final definition of done

- `HomeViewModel` is smaller and mostly state/application logic.
- Home loading orchestration lives in a coordinator.
- Home layout assembly is pure and outside `HomeViewModel`.
- Recommendation cache I/O and JSON parsing are dispatcher-safe.
- Home publishes cached content before remote refresh where possible.
- Duplicate recommendation requests are deduped.
- Data-path skeleton delay is gone.
- Shared image helper supports stable memory placeholder keys.
- High-traffic media images use the shared helper with cache keys.
- High-volume lazy layouts use stable keys and content types.
- Repeated display normalization is moved out of hot composables.
- Gradle was not run.
