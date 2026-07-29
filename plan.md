# Migration Plan: All Screens to CrispyScreen

> **IMPORTANT: Do NOT compile during this migration.** Verification is via grep audits + visual review only. Compiling will waste time on unrelated errors.

## Goal

Replace per-screen `Scaffold` + `Box.padding/consumeWindowInsets` + `PullToRefreshBox` + `LazyColumn`/`LazyVerticalGrid` boilerplate with calls to `CrispyScreen` (for list screens) or raw `LazyVerticalGrid + safeBottomPadding()` (for grid screens, following the reference app's Catalog pattern).

**Key principle: code reduction, not layering.** Every screen must lose boilerplate lines, not gain them. The `CrispyScreen` wrapper bakes in: `Scaffold`, `contentWindowInsets`, `Box.padding/consumeWindowInsets`, optional `PullToRefreshBox` + `Indicator`, and `LazyColumn` with `safeBottomPadding()` in `contentPadding`. Screens pass a `LazyListScope` lambda.

## What CrispyScreen bakes in (already built)

```kotlin
@Composable
fun CrispyScreen(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    nestedScrollConnection: NestedScrollConnection? = null,
    pullToRefreshState: PullToRefreshState? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    horizontalPadding: Dp = Dimensions.PageHorizontalPaddingCompact,
    topPadding: Dp = Dimensions.PageTopPadding,
    bottomPaddingExtra: Dp = Dimensions.PageBottomPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
)
```

It returns a `Scaffold` with `contentWindowInsets = WindowInsets(0,0,0,0)`, optional `topBar`, a `Box(padding/consumeWindowInsets)` child, optional `PullToRefreshBox` wrapping the inner `LazyColumn`. The `LazyColumn` has `contentPadding` with `bottom = safeBottomPadding(bottomPaddingExtra)`.

## Screen Categories

### Category A: List screens with collapsing TopAppBar + PullToRefresh (CrispyScreen candidates)
- HomeScreen.kt
- CalendarScreen.kt

### Category B: List screens with collapsing TopAppBar, NO PullToRefresh (CrispyScreen candidates)
- LibraryRoute.kt (wraps LibraryScreen.kt content)
- SearchScreen.kt (has custom SearchTopBar, no scrollBehavior, has imePadding)

### Category C: Settings screens with plain Column + verticalScroll (NOT CrispyScreen candidates — but can delete Scaffold boilerplate)
- SettingsScreen.kt
- PlaybackSettingsScreen.kt
- ImageSettingsScreen.kt
- AddonsSettingsScreen.kt

### Category D: Grid screens (NOT CrispyScreen candidates — use raw LazyVerticalGrid + safeBottomPadding)
- CatalogScreen.kt
- DiscoverScreen.kt

### Category E: Auth screens (deferred — not part of main app shell)
- AuthScreens.kt

### Category F: Detail screens (deferred — custom layout)
- DetailsScreen.kt + DetailsBody.kt

## What to DELETE after migration

After all screens are migrated, these become dead code if no screen uses them directly:

1. **`StandardTopAppBar.kt`** — only if every screen passes its top bar as a lambda to `CrispyScreen(topBar = { ... })` instead of using `StandardTopAppBar` in a Scaffold slot. Screens can still use `StandardTopAppBar` *inside* the `topBar` lambda. So `StandardTopAppBar.kt` stays unless we also inline the top bar composable. **Decision: keep `StandardTopAppBar.kt`** — it's a reusable header component, not screen boilerplate.

2. **`AppBarScrollBehavior.kt`** — used by screens that collapse the top bar. Since `CrispyScreen` accepts `nestedScrollConnection`, screens still call `appBarScrollBehavior()` and pass `scrollBehavior.nestedScrollConnection`. **Decision: keep `AppBarScrollBehavior.kt`** — it powers the collapse behavior.

3. **Per-screen `Scaffold` + `contentWindowInsets` + `Box.padding/consumeWindowInsets` + manual `LazyColumn` setup** — this is the boilerplate that DELETES from every screen. Each screen loses ~15-25 lines.

4. **`Dimensions.PageBottomPadding` raw usage** — already mostly replaced with `safeBottomPadding()`. After migration, `CrispyScreen` bakes it in, so screens don't reference `Dimensions.PageBottomPadding` at all. The constant becomes only used inside `CrispyScreen.kt` and `ScreenInsets.kt`. Keep the constant — it's the default param.

---

## Step-by-step migration

### Step 1: HomeScreen.kt (Category A)

**Current boilerplate (HomeRoute, lines 107-155):**
```
Scaffold(
    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = { StandardTopAppBar(...) },
) { paddingValues ->
    Box(modifier = Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues)) {
        HomeScreen(...)  // contains PullToRefreshBox + LazyColumn
    }
}
```

**Current boilerplate (HomeScreen, lines 191-214):**
```
PullToRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    modifier = Modifier.fillMaxSize(),
    state = pullToRefreshState,
    indicator = { Indicator(...) },
) {
    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 0.dp, top = Dimensions.PageTopPadding, end = 0.dp, bottom = safeBottomPadding(Dimensions.PageBottomPadding)),
        verticalArrangement = Arrangement.spacedBy(HomeContentSectionSpacing),
    ) { items { ... } }
}
```

**After migration (HomeRoute):**
```kotlin
CrispyScreen(
    topBar = {
        StandardTopAppBar(
            title = { CrispyWordmark(...) },
            actions = { ProfileIconButton(onClick = onOpenAccountsProfiles) },
            scrollBehavior = scrollBehavior,
            colors = topLevelAppBarColors(),
        )
    },
    nestedScrollConnection = scrollBehavior.nestedScrollConnection,
    pullToRefreshState = pullToRefreshState,
    isRefreshing = isRefreshing,
    onRefresh = viewModel::refresh,
    horizontalPadding = 0.dp,
    topPadding = 0.dp,
    bottomPaddingExtra = Dimensions.PageBottomPadding,
    verticalArrangement = Arrangement.spacedBy(HomeContentSectionSpacing),
    listState = lazyListState,
) {
    item(key = "topHeader") { ... }
    items(...) { ... }
}
```

**Changes:**
- DELETE: `Scaffold` block (lines 107-128)
- DELETE: `Box(paddingValues/consumeWindowInsets)` wrapper (lines 129-134, 155)
- DELETE: `PullToRefreshBox` + `Indicator` (lines 191-203) — baked into CrispyScreen
- DELETE: `LazyColumn` setup (lines 204-214) — baked into CrispyScreen
- DELETE: `pullToRefreshState` declaration (move to HomeRoute, pass to CrispyScreen)
- KEEP: `scrollBehavior = appBarScrollBehavior()` (line 88)
- KEEP: `StandardTopAppBar` call (move into `topBar` lambda)
- MERGE: `HomeRoute` and `HomeScreen` can merge — `HomeScreen`'s content lambda becomes the `CrispyScreen` content. The `HomeScreen` private function may be inlined or kept as a helper that returns items.
- Net: ~30 lines removed, ~2 levels of nesting removed.

**Imports to remove from HomeScreen.kt:**
- `androidx.compose.foundation.layout.WindowInsets`
- `androidx.compose.foundation.layout.consumeWindowInsets`
- `androidx.compose.material3.Scaffold`
- `androidx.compose.material3.pulltorefresh.PullToRefreshBox`
- `androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator`
- `androidx.compose.material3.pulltorefresh.rememberPullToRefreshState`
- `androidx.compose.foundation.layout.PaddingValues` (if no longer used directly)
- `androidx.compose.foundation.lazy.LazyColumn` (if no longer used directly)
- `androidx.compose.ui.input.nestedscroll.nestedScroll` (if no longer used directly)
- Add: `import com.crispy.tv.ui.components.CrispyScreen`

### Step 2: CalendarScreen.kt (Category A)

**Current boilerplate (lines 138-172):**
```
Scaffold(
    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = { StandardTopAppBar(...) },
) { innerPadding ->
    val contentPadding = PaddingValues(start = horizontalPadding, top = innerPadding.calculateTopPadding() + 16.dp, end = horizontalPadding, bottom = innerPadding.calculateBottomPadding() + 16.dp + safeBottomPadding())
    PullToRefreshBox(...) {
        when {
            isLoading -> CalendarLoadingSkeleton(contentPadding)
            isEmpty -> Column(...) { ... }
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(22.dp)) { items { ... } }
        }
    }
}
```

**After migration:**
```kotlin
CrispyScreen(
    topBar = {
        StandardTopAppBar(
            title = { Text("Calendar") },
            navigationIcon = { ... },
            scrollBehavior = scrollBehavior,
        )
    },
    nestedScrollConnection = scrollBehavior.nestedScrollConnection,
    pullToRefreshState = pullToRefreshState,
    isRefreshing = uiState.isRefreshing,
    onRefresh = viewModel::refresh,
    horizontalPadding = horizontalPadding,
    topPadding = 16.dp,  // safeTopPadding handled by Scaffold innerPadding
    bottomPaddingExtra = 0.dp,
    verticalArrangement = Arrangement.spacedBy(22.dp),
    listState = ...,
) {
    when {
        isLoading -> item { CalendarLoadingSkeleton() }
        isEmpty -> item { CalendarEmptyState(...) }
        else -> items(uiState.sections, key = { it.key.name }) { section -> ... }
    }
}
```

**Changes:**
- DELETE: `Scaffold` block (lines 138-151)
- DELETE: `contentPadding` manual computation (lines 153-158)
- DELETE: `PullToRefreshBox` + `Indicator` (lines 161-172)
- DELETE: `LazyColumn` setup (lines 200-203)
- MERGE: `CalendarLoadingSkeleton` currently takes `contentPadding` — remove the param, use a plain `item { Box.skeletonElement() }` inside the CrispyScreen scope.
- Net: ~20 lines removed.
- **Note:** The loading skeleton and empty state are currently separate branches wrapping content. Inside `CrispyScreen`'s `LazyListScope`, these become `item { }` or `items { }` blocks. The `Column` empty state becomes an `item { Column { ... } }`.

**Imports to remove:**
- `androidx.compose.foundation.layout.WindowInsets`
- `androidx.compose.material3.Scaffold`
- `androidx.compose.material3.pulltorefresh.PullToRefreshBox`
- `androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator`
- `androidx.compose.material3.pulltorefresh.rememberPullToRefreshState`
- `androidx.compose.foundation.layout.PaddingValues` (if no longer used)
- Add: `import com.crispy.tv.ui.components.CrispyScreen`

### Step 3: LibraryRoute.kt + LibraryScreen.kt (Category B)

**Current boilerplate (LibraryRoute.kt, lines 50-91):**
```
Scaffold(
    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = { StandardTopAppBar(title = CrispySectionAppBarTitle("Library"), actions = { ... }) },
) { paddingValues ->
    Box(modifier = Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues)) {
        LibraryRouteContent(...)
    }
}
```

**Current boilerplate (LibraryScreen.kt, lines 418-429):**
```
PullToRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    modifier = Modifier.fillMaxSize(),
    state = pullToRefreshState,
    indicator = { Indicator(...) },
) { ... three LazyColumns (History/Ratings/Watchlist) ... }
```

**After migration:**
`LibraryRoute.kt` becomes a thin wrapper:
```kotlin
@Composable
internal fun LibraryRoute(...) {
    val viewModel = ...
    val scrollBehavior = appBarScrollBehavior()

    CrispyScreen(
        topBar = {
            StandardTopAppBar(
                title = { CrispySectionAppBarTitle(label = "Library") },
                actions = { ... },
                scrollBehavior = scrollBehavior,
                colors = topLevelAppBarColors(),
            )
        },
        nestedScrollConnection = scrollBehavior.nestedScrollConnection,
        pullToRefreshState = pullToRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        horizontalPadding = 0.dp,
        topPadding = 0.dp,
        listState = listState,
    ) {
        // content from LibraryRouteContent, but as LazyListScope items
    }
}
```

**Changes:**
- DELETE: `Scaffold` in LibraryRoute.kt (lines 50-73)
- DELETE: `Box(paddingValues/consumeWindowInsets)` in LibraryRoute.kt (lines 74-79)
- DELETE: `PullToRefreshBox` + `Indicator` in LibraryScreen.kt (lines 418-429)
- MERGE: `LibraryRouteContent` becomes the `CrispyScreen` content lambda. The three `LazyColumn` (History/Ratings/Watchlist) calls become items inside the single `CrispyScreen` `LazyColumn`.
- **Special concern:** The three content variants (History, Ratings, Watchlist) each had their own `LazyColumn` with `contentPadding = PaddingValues(bottom = 12.dp + safeBottomPadding())`. Inside `CrispyScreen`, they become `items { }` blocks within the single outer `LazyColumn`. The per-variant `contentPadding` is no longer needed — `CrispyScreen` handles bottom padding. Pass `bottomPaddingExtra = 12.dp` to `CrispyScreen`.
- Net: ~25 lines removed across the two files. `LibraryRouteContent` may simplify to just a `when` block inside the content lambda.

**Imports to remove from LibraryRoute.kt:**
- `androidx.compose.foundation.layout.WindowInsets`
- `androidx.compose.foundation.layout.consumeWindowInsets`
- `androidx.compose.foundation.layout.fillMaxSize`
- `androidx.compose.foundation.layout.padding`
- `androidx.compose.material3.Scaffold`
- Add: `import com.crispy.tv.ui.components.CrispyScreen`

**Imports to remove from LibraryScreen.kt:**
- `androidx.compose.foundation.lazy.LazyColumn` (if no longer used directly — the three variants become items)
- `androidx.compose.material3.pulltorefresh.PullToRefreshBox`
- `androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator`
- `androidx.compose.material3.pulltorefresh.rememberPullToRefreshState`
- `androidx.compose.foundation.layout.PaddingValues` (if no longer used)

### Step 4: SearchScreen.kt (Category B) — SKIP (already clean)

**Current boilerplate (SearchRoute, lines 98-129):**
```
Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = { SearchTopBar(...) },
) { paddingValues ->
    val contentModifier = Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues).imePadding()
    SearchContent(... modifier = contentModifier)
}
```

**SearchContent** branches into three cases: results (LazyColumn), suggestions (Column), browse (LazyVerticalGrid).

**After migration:**
`SearchRoute` does NOT easily fit `CrispyScreen` because:
1. The top bar is a custom `SearchTopBar`, not `StandardTopAppBar`.
2. The screen has NO `nestedScrollConnection` (no collapsing).
3. It uses `.imePadding()` which `CrispyScreen` doesn't bake in.
4. It branches between `LazyColumn` and `LazyVerticalGrid` — `CrispyScreen` is `LazyColumn` only.

**Decision:** For the results branch (LazyColumn), use `CrispyScreen`. For the browse branch (LazyVerticalGrid), keep raw `LazyVerticalGrid + safeBottomPadding()` (Category D pattern). For the suggestions branch (plain Column), keep inline.

But this means SearchRoute keeps a `Scaffold` for the top bar, and only the inner results `LazyColumn` migrates to using `safeBottomPadding()` (already done). 

**Better decision:** SearchScreen is the most complex case. The `CrispyScreen` wrapper doesn't fit cleanly because the screen branches between list and grid. **Keep SearchScreen as-is** — it already uses `safeBottomPadding()` correctly. The `Scaffold` + `Box.padding/consumeWindowInsets` boilerplate is the cost of the branching. Touching this screen risks breaking search.

**Re-evaluation (post-migration):** Confirmed permanently skipped. CrispyScreen wraps a single Scaffold+LazyColumn; nesting it for one of three branches would create stacked Scaffolds. The search bar Row is rendered inside the same Column as the branch content, so even extracting just the LazyColumn to CrispyScreen would require restructuring the search bar (moving it into CrispyScreen's topBar slot, which would require a custom topBar that renders only in the results branch). The branching cost makes any CrispyScreen migration net-additive, not reductive. Touching this screen risks breaking search.

**Net change: 0 lines.** Mark as "already uses safeBottomPadding, does not benefit from CrispyScreen."

### Step 5: Settings screens (Category C) — SKIP (already clean)

These use `Column + verticalScroll`, NOT `LazyColumn`. `CrispyScreen` bakes in `LazyColumn`, so it doesn't fit.

**Options:**
1. Build a `CrispyScrollScreen` variant that bakes in `Scaffold` + `Column + verticalScroll + safeBottomPadding` instead of `LazyColumn`.
2. Leave settings screens as-is — they already use `safeBottomPadding()`.

**Decision: Option 2 — leave as-is.** Building a second wrapper for 4 small screens is premature abstraction (YAGNI). The settings screens are already clean: `Scaffold + StandardTopAppBar + Column(padding(safeBottomPadding())) { settingsGroups }`. No PullToRefresh, no `Box.padding/consumeWindowInsets`. The boilerplate is minimal.

**Net change: 0 lines.** Mark as "already clean."

### Step 6: CatalogScreen.kt (Category D — grid screen) — SKIP (already clean)

`CrispyScreen` is `LazyColumn`-only. Catalog uses `LazyVerticalGrid`. Not a candidate.

**Current pattern:**
```
Scaffold(nestedScroll, topBar = StandardTopAppBar) { innerPadding ->
    PullToRefreshBox { Box { LazyVerticalGrid(contentPadding = ... safeBottomPadding()) { items } } }
}
```

**Decision:** Keep `Scaffold` for top bar + keep `PullToRefreshBox`. The grid `contentPadding` already uses `safeBottomPadding()`. This screen is already clean from our earlier pass.

**Alternative if we want to follow the reference app exactly:** Delete `Scaffold`, overlay a header `Box` on top of the grid (reference app's `CatalogScreen` pattern), use `windowInsetsPadding(statusBars)` for top. But this loses the collapsing TopAppBar behavior. **Deferred — not in this migration.**

**Net change: 0 lines.**

### Step 7: DiscoverScreen.kt (Category D — grid screen) — SKIP (already clean)

Same as Catalog — main scrollable is `LazyVerticalGrid`. Not a `CrispyScreen` candidate. Already uses `safeBottomPadding()`. Keep as-is.

**Net change: 0 lines.**

### Step 8: Audit + cleanup — DONE

Audit confirms:
- `Scaffold` — only in CrispyScreen + AppRoot + kept screens (Catalog, Discover, Search, Settings×4, Auth×4 deferred).
- `PullToRefreshBox` — only in CrispyScreen + grid screens (Catalog, Discover).
- `contentWindowInsets = WindowInsets(0,0,0,0)` — only in CrispyScreen + AppRoot + kept screens.
- `consumeWindowInsets` — only in CrispyScreen + AppRoot + kept screens.
- `safeBottomPadding` — in ScreenInsets.kt (def) + CrispyScreen + all kept screens. Library no longer references it directly.
- Library migration: removed `LazyColumn`, `PullToRefreshBox`, `Indicator`, `rememberPullToRefreshState`, `safeBottomPadding`, `LaunchedEffect`, `getValue`, `collectAsStateWithLifecycle`, `rememberLazyListState`, `LazyPagingItems`, `ExperimentalMaterial3Api`, `fillMaxSize`, `responsivePageHorizontalPadding` imports from `LibraryScreen.kt`. Removed `LazyPagingItems`, `LazyListScope`, `Dp` imports from `LibraryRoute.kt`.

---

## Summary

| Step | File | Category | Action | Lines removed | Lines added | Net |
|------|------|----------|--------|---------------|-------------|-----|
| 1 | HomeScreen.kt | A (list+PTR) | Migrate to CrispyScreen | ~30 | ~10 | -20 |
| 2 | CalendarScreen.kt | A (list+PTR) | Migrate to CrispyScreen | ~20 | ~10 | -10 |
| 3 | LibraryRoute.kt + LibraryScreen.kt | B (list+PTR) | Migrate to CrispyScreen — DONE | ~340 | ~155 | -185 |
| 4 | SearchScreen.kt | B (branching) | Keep as-is | 0 | 0 | 0 |
| 5 | Settings×4 | C (Column) | Keep as-is | 0 | 0 | 0 |
| 6 | CatalogScreen.kt | D (grid) | Keep as-is | 0 | 0 | 0 |
| 7 | DiscoverScreen.kt | D (grid) | Keep as-is | 0 | 0 | 0 |
| 8 | Audit | — | Grep + remove unused imports | 0 | 0 | 0 |
| | | | | | **Total** | **-215** |

## What we do NOT do in this migration

- Do NOT build `CrispyGridScreen` — grid screens (Catalog, Discover) keep their inline `Scaffold + LazyVerticalGrid`. The reference app's Catalog uses raw `LazyVerticalGrid` with no Scaffold and an overlaid header, but that loses our collapsing TopAppBar behavior. Deferred.
- Do NOT build `CrispyScrollScreen` — settings screens use `Column + verticalScroll`, not `LazyColumn`. They're already clean. Building a second wrapper is YAGNI.
- Do NOT migrate SearchScreen — it branches between list and grid, doesn't fit the wrapper cleanly. Already uses `safeBottomPadding()`.
- Do NOT migrate AuthScreens — they're shown before the main app shell (no floating bottom bar). Deferred.
- Do NOT migrate DetailsScreen — custom hero/stretch layout. Deferred.
- Do NOT delete `StandardTopAppBar.kt` or `AppBarScrollBehavior.kt` — they're still used inside `CrispyScreen`'s `topBar` lambda.
- Do NOT compile during this migration.
