package com.crispy.tv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import coil3.compose.LocalPlatformContext
import com.crispy.tv.accounts.accountSettingsViewModelFactory
import com.crispy.tv.accounts.activeProfileLoader
import com.crispy.tv.accounts.profileListViewModelFactory
import com.crispy.tv.details.localeDateFormatters
import com.crispy.tv.library.deviceUtcOffsetMillis
import com.crispy.tv.library.libraryViewModelFactory
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.search.searchViewModelFactory

private const val TopLevelNavigationDurationMillis = 200
private const val TopLevelNavigationOffsetDivisor = 8
private const val OverlayNavigationDurationMillis = 220

private val topLevelRouteIndices = TopLevelDestination.entries.mapIndexed { index, destination -> destination.route to index }.toMap()

private enum class NavigationRole { TopLevel, Overlay, Detail }

private fun roleOf(route: String?): NavigationRole {
    return when {
        topLevelRouteIndices.containsKey(route) -> NavigationRole.TopLevel
        route == AppRoutes.SearchRoute -> NavigationRole.Overlay
        else -> NavigationRole.Detail
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onSignedOut: () -> Unit = {},
) {
    CrispySharedTransitionLayout {
        // `addSearchNavGraph` is in `commonMain` now, so the two values it used
        // to build for itself are built here instead. They are read at *this*
        // level rather than inside `NavHost`'s builder lambda, because that
        // lambda is not a composable scope and a `remember` inside it would not
        // be one either. This is the same shape as `isWideScreen` and
        // `isCompact` crossing as data: the caller already has the value, so the
        // shared file receives the product rather than the factory.
        //
        // **The `remember`s are here for that reason and not merely nearby.** The
        // first attempt put them at the `addSearchNavGraph` call, inside the
        // builder lambda, and the compiler said `@Composable invocations can
        // only happen from the context of a @Composable function` at both lines.
        // So the slot carries the *remembered* value, not a lambda producing it:
        // `remember` belongs to the caller, and the caller is a composable.
        val platformContext = LocalPlatformContext.current
        val appContext = remember(platformContext) { platformContext.applicationContext }
        val searchFactory = remember(appContext) { searchViewModelFactory(appContext) }
        val profileLoader = remember(appContext) { activeProfileLoader(appContext) }

        // `addAccountNavGraph` is the same shape a second time: three `Context`
        // destinations, three factory call sites, one hoisted block. **These are
        // separate `remember`s rather than a reuse of `profileLoader` above, on
        // purpose.** Both graphs call `activeProfileLoader(appContext)`, and each
        // used to build its own; sharing one instance would make a single lambda
        // identity key two graphs' state, so a recomposition in one could cancel or
        // restart the other's load. Three remembered values, not one, is the
        // behaviour-preserving shape.
        val profileListFactory = remember(appContext) { profileListViewModelFactory(appContext) }
        val accountSettingsFactory = remember(appContext) { accountSettingsViewModelFactory(appContext) }
        val accountProfileLoader = remember(appContext) { activeProfileLoader(appContext) }

        // `addLibraryNavGraph` is the same shape a third time, and the two of its
        // seven values that are NOT plain products are the reason this block is
        // longer than the other two. `monthName` and `loadProfile` are remembered
        // lambdas because their identity is load-bearing -- `ProfileIconButton`
        // keys a `produceState` on `loadProfile`, and `LibraryRoute` re-reads
        // `monthName`'s receiver per composition. `clock` and `utcOffsetMillis`
        // are remembered lambdas that deliberately re-read on every *call*: the
        // offset is wrong if it is captured at composition time, for the hours
        // either side of a daylight-saving change, and this screen groups rows by
        // month. So the lambdas are stable and the reads inside them are not.
        //
        // `libraryProfileLoader` is a THIRD `activeProfileLoader(appContext)`
        // instance rather than a reuse of `profileLoader` or
        // `accountProfileLoader` above, on the same grounds: each graph used to
        // build its own, and sharing one would key three graphs' state to a
        // single lambda identity.
        val libraryFactory = remember(appContext) { libraryViewModelFactory(appContext) }
        val libraryMonthName = remember(appContext) { localeDateFormatters(appContext).monthName }
        val libraryClock = remember { { System.currentTimeMillis() } }
        val libraryUtcOffset = remember { { deviceUtcOffsetMillis() } }
        val libraryProfileLoader = remember(appContext) { activeProfileLoader(appContext) }
        val libraryLogger = remember(appContext) { AndroidAppLogger(appContext) }
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home.route,
            modifier = modifier,
            enterTransition = {
                when {
                    roleOf(targetState.destination.route) == NavigationRole.Overlay -> overlayEnterFromRight()
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(targetState.destination.route) > topLevelRouteIndex(initialState.destination.route)) {
                            tabEnterFromRight()
                        } else {
                            tabEnterFromLeft()
                        }
                    }
                    else -> EnterTransition.None
                }
            },
            exitTransition = {
                when {
                    roleOf(targetState.destination.route) == NavigationRole.Overlay -> ExitTransition.None
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(targetState.destination.route) > topLevelRouteIndex(initialState.destination.route)) {
                            tabExitToLeft()
                        } else {
                            tabExitToRight()
                        }
                    }
                    else -> ExitTransition.None
                }
            },
            popEnterTransition = {
                when {
                    roleOf(initialState.destination.route) == NavigationRole.Overlay -> EnterTransition.None
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(initialState.destination.route) < topLevelRouteIndex(targetState.destination.route)) {
                            tabEnterFromRight()
                        } else {
                            tabEnterFromLeft()
                        }
                    }
                    else -> EnterTransition.None
                }
            },
            popExitTransition = {
                when {
                    roleOf(initialState.destination.route) == NavigationRole.Overlay -> overlayExitToRight()
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(initialState.destination.route) < topLevelRouteIndex(targetState.destination.route)) {
                            tabExitToLeft()
                        } else {
                            tabExitToRight()
                        }
                    }
                    else -> ExitTransition.None
                }
            },
        ) {
            addHomeNavGraph(navController)
            addSearchNavGraph(
                navController = navController,
                searchViewModelFactory = searchFactory,
                loadProfile = profileLoader,
            )
            addDiscoverNavGraph(navController)
            addLibraryNavGraph(
                navController = navController,
                viewModelFactory = libraryFactory,
                monthName = libraryMonthName,
                clock = libraryClock,
                utcOffsetMillis = libraryUtcOffset,
                loadProfile = libraryProfileLoader,
                logger = libraryLogger,
            )
            addSettingsNavGraph(navController)
            addAccountNavGraph(
                navController = navController,
                onSignedOut = onSignedOut,
                profileListFactory = profileListFactory,
                accountSettingsFactory = accountSettingsFactory,
                loadProfile = accountProfileLoader,
            )
            addPlayerDestination(navController)
        }
    }
}

private fun tabEnterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        initialOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabEnterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        initialOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabExitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        targetOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabExitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        targetOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun overlayEnterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(OverlayNavigationDurationMillis),
        initialOffsetX = { fullWidth -> fullWidth },
    ) + fadeIn(animationSpec = tween(OverlayNavigationDurationMillis))

private fun overlayExitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(OverlayNavigationDurationMillis),
        targetOffsetX = { fullWidth -> fullWidth },
    ) + fadeOut(animationSpec = tween(OverlayNavigationDurationMillis))

private fun topLevelRouteIndex(route: String?): Int {
    return topLevelRouteIndices[route] ?: -1
}
