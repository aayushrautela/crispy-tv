package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.library.LibraryRoute
import com.crispy.tv.platform.AppLogger

/**
 * The library destination. `commonMain`, and the seventh no-default-slot call
 * site in a row -- `SearchNavGraph`, `AuthNavGraph` and `LibraryRoute` all
 * already had this shape, so the method is applied rather than designed.
 *
 * **Its own callee is what made it movable**: `LibraryRoute` reached
 * `commonMain` in `c6bb5d32`, and a file can move once its *callees* are in
 * `commonMain`, because `androidMain` callers can call `commonMain` callees
 * freely. So this graph moves *up* from the route without dragging
 * `AppNavHost` with it -- the graph-to-route edge is one-way. (`AppNavHost`
 * has since moved up too: it is `commonMain` now, and it names **eight**
 * registrations -- the seven graphs plus `d.addPlayerDestination` -- so the
 * "still `androidMain`, still six graphs" version of this sentence was wrong
 * twice over. It is a `commonMain` file making a placement claim about a
 * sibling `commonMain` file, which is the failure this paragraph is about.)
 *
 * **All seven slots are required, with no defaults, and none of them is a
 * factory lambda.** `viewModelFactory` is the product, because `LibraryRoute`
 * hands it straight to `viewModel(factory = ...)`. The other five are the
 * route's own parameters verbatim, and their types were not re-chosen here --
 * `LibraryRoute` already declares each of them, so the two signatures agree by
 * construction rather than by my having read it carefully:
 *
 *  - `monthName: (String) -> String` and `loadProfile: suspend () -> ActiveProfileInfo?`
 *    are lambdas, and must be *remembered by the caller*. `ProfileIconButton`
 *    takes `loadProfile` as a `produceState(initialValue = null, loadProfile,
 *    refreshKey)` key, so a fresh instance each recomposition restarts the
 *    profile load; `AppNavHost` therefore `remember`s its own.
 *  - `clock` and `utcOffsetMillis` are lambdas **on purpose, and the reason is
 *    the opposite of stability**: `deviceUtcOffsetMillis()` is read fresh per
 *    call, because an offset remembered at composition time is silently wrong
 *    for the hours either side of a daylight-saving change, and this screen
 *    groups rows by month. So `AppNavHost` remembers the *lambdas* and the
 *    lambdas re-read the value each time they are invoked.
 *
 * **`logger` replaces `android.util.Log`, and that was a port rather than a
 * pin.** `Log.d(tag, message)` is not a hard pin in a `commonMain` file
 * because `:platform-core`'s `commonMain` already declares
 * `interface AppLogger { fun debug(tag: String, message: String) ... }` and
 * `:android:platform-core` is on `:app`'s `commonMain` classpath, so
 * `AndroidAppLogger(context)` is the exact drop-in. *A replacement for a JVM
 * library call goes in the file that already replaces that library* -- which
 * is why the trace line is kept rather than deleted, and why the slot has no
 * default: a defaulted logger would be a silent no-op sink.
 */
fun NavGraphBuilder.addLibraryNavGraph(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    monthName: (String) -> String,
    clock: () -> Long,
    utcOffsetMillis: () -> Long,
    loadProfile: suspend () -> ActiveProfileInfo?,
    logger: AppLogger,
) {
    composable(AppRoutes.LibraryRoute) { entry ->
        PredictivePeelContainer {
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            LibraryRoute(
                viewModelFactory = viewModelFactory,
                monthName = monthName,
                clock = clock,
                utcOffsetMillis = utcOffsetMillis,
                loadProfile = loadProfile,
                onItemClick = { item: CatalogItem, sharedElementKey: String? ->
                    logger.debug(
                        "LibraryNav",
                        "onItemClick title=${item.title} itemId=${item.itemId} type=${item.type} sharedElementKey=$sharedElementKey",
                    )
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                onOpenCalendar = {
                    navController.navigate(AppRoutes.CalendarRoute) {
                        launchSingleTop = true
                    }
                },
                onOpenAccountsProfiles = {
                    navController.navigate(AppRoutes.ProfileMenuRoute) {
                        launchSingleTop = true
                    }
                },
                scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
                onScrollToTopConsumed = {
                    entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
                },
            )
        }
        }
    }
}
