package com.crispy.tv.ui.navigation

import androidx.lifecycle.SavedStateHandle

/**
 * The Home graph's route arguments, read in the shared source set.
 *
 * ## Why these take a [SavedStateHandle] and not a `NavBackStackEntry`
 *
 * Until this file existed, the three readers lived in `androidMain` and their reason was
 * written down in four places as "`NavBackStackEntry.arguments` returns `android.os.Bundle`
 * on every platform". **That reason was wrong**, and the measurement that disproves it is
 * the interesting part of this file's history:
 *
 *  - In this project's navigation artifact (`androidx.navigation:navigation-common:2.10.0`,
 *    via the KMP fork `org.jetbrains.androidx.navigation:navigation-compose:2.10.0-beta01`)
 *    `NavBackStackEntry.arguments` is a **`SavedState?`** — a common `androidx.savedstate`
 *    type — not a `Bundle`. Forcing it with `val x: Int = entry.arguments` reports
 *    `Return type mismatch: expected 'Int', actual 'SavedState?'`, and no `Bundle` or
 *    `android.os` reference appears anywhere in that artifact's common metadata.
 *  - Its accessors *are* the unreachable part, but for a different reason:
 *    `androidx.savedstate:savedstate:1.5.0` declares them (`getString`, `getBoolean`,
 *    `getInt`, … and `…OrNull` variants) on `SavedStateReader`, marked `@PublishedApi`, so
 *    they are effectively internal to that library. `SavedState` is a read-only *bag* with
 *    no public readers — the readers are what the navigation library itself uses.
 *  - `NavBackStackEntry.savedStateHandle` has none of those problems. It compiles in
 *    `commonMain`, and `entry.savedStateHandle[key] = 0` / `.getStateFlow(key, …)` were
 *    already in production in four shared graphs before this file existed
 *    (`SearchNavGraph`, `LibraryNavGraph`, `DiscoverNavGraph`, `HomeNavGraph`) plus
 *    `AppRoot`. So the handle was never the blocked thing; only the *argument* read was.
 *  - The handle carries the arguments. `NavBackStackEntry.savedStateHandle` returns
 *    `ViewModelProvider(this, getNavResultSavedStateFactory()).get<SavedStateViewModel>().handle`
 *    behind two `check`s, and the default creation extras that seed it are filled from the
 *    entry's `immutableArgs` via `SavedStateHandleSupport.DEFAULT_ARGS_KEY`. That is the
 *    argument set, not a different one.
 *
 * ## What this changes about the behaviour, precisely
 *
 * Two deltas, both unreachable for these three destinations, and both recorded rather than
 * papered over:
 *
 *  1. **It throws where `arguments?.` did not.** The handle accessor requires the entry to
 *    be at least `CREATED`, and answers `IllegalStateException` with "You cannot access the
 *    NavBackStackEntry's SavedStateHandle until it is added to the NavController's back
 *    stack", plus a second `check` that it is not `DESTROYED`. Every one of these three
 *    destinations declares `navArgument(...)`, which is what adds the entry to the back
 *    stack, and each read happens inside its destination block.
 *  2. **`get<T>` is an unchecked cast, where `Bundle.getString` on a wrong-typed value
 *    answers `null`.** As declared, the types match their reads: the eight
 *    `HomeDetails*Arg`s are `NavType.StringType` and `HomeDetailsAutoOpenEpisodeArg` is
 *    `NavType.BoolType`, each with the `defaultValue` its read assumes. A caller that
 *    handed a `BoolType` key to `get<String>` would get a `ClassCastException` instead of a
 *    `null`, so the `NavType` declarations are part of this contract.
 *
 * ## Why a function taking a handle, and why these are testable at all
 *
 * The callers in [addHomeNavGraph] pass `entry.savedStateHandle`; the mapping below is
 * ordinary logic over a `Map`, so it lives in `commonTest` rather than in a
 * `commonMain`-invisible `NavBackStackEntry`. That is the whole reason this file exists
 * rather than three inline `remember`s in the graph: **a decision no test can call is a
 * decision no test can cover**, and a decision baked into the closure that was deleted here
 * was one the deleted `commonTest` could never have reached.
 *
 * The rules stay exactly where they were: every question about what a blank string means,
 * and whether a runtime entry exists at all, is [addHomeNavGraph]'s or
 * [runtimeDetailsEntryOrNull]'s. These three functions decide nothing — they are the
 * mapping, and only the mapping.
 */

/** Reads [HomeCatalogRouteArgs] out of the catalog destination's handle. */
internal fun catalogRouteArguments(handle: SavedStateHandle): HomeCatalogRouteArgs =
    HomeCatalogRouteArgs(
        catalogId = handle.get<String>(AppRoutes.CatalogIdArg),
        title = handle.get<String>(AppRoutes.CatalogTitleArg),
    )

/** Reads [HomeDetailsRouteArgs] out of the details destination's handle. */
internal fun detailsRouteArguments(handle: SavedStateHandle): HomeDetailsRouteArgs =
    HomeDetailsRouteArgs(
        itemId = handle.get<String>(AppRoutes.HomeDetailsItemIdArg),
        itemType = handle.get<String>(AppRoutes.HomeDetailsItemTypeArg),
        highlightEpisodeId = handle.get<String>(AppRoutes.HomeDetailsHighlightEpisodeIdArg),
        autoOpenEpisode = handle.get<Boolean>(AppRoutes.HomeDetailsAutoOpenEpisodeArg) == true,
        runtimeSeasonNumber = handle.get<String>(AppRoutes.HomeDetailsRuntimeSeasonNumberArg),
        runtimeEpisodeNumber = handle.get<String>(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg),
        runtimeAbsoluteEpisodeNumber = handle.get<String>(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg),
        initialArtworkUrl = handle.get<String>(AppRoutes.HomeDetailsArtworkUrlArg),
        sharedElementKey = handle.get<String>(AppRoutes.HomeDetailsSharedElementKeyArg),
    )

/** Reads [HomePersonRouteArgs] out of the person destination's handle. */
internal fun personRouteArguments(handle: SavedStateHandle): HomePersonRouteArgs =
    HomePersonRouteArgs(
        personId = handle.get<String>(AppRoutes.PersonDetailsPersonIdArg),
        profileUrl = handle.get<String>(AppRoutes.PersonDetailsProfileUrlArg),
    )