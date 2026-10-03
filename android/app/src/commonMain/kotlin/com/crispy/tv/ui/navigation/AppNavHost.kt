package com.crispy.tv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

private const val TopLevelNavigationDurationMillis = 200
private const val TopLevelNavigationOffsetDivisor = 8
private const val OverlayNavigationDurationMillis = 220

/**
 * The route -> index map the direction of every top-level transition is decided from.
 *
 * `internal` rather than `private`, and that is the whole point of it: **the direction
 * rule was already a named top-level function in this file, so it was already the most
 * testable thing here -- except that `private` put it out of reach of the module's own
 * `commonTest`.** Nothing had to be extracted to make it testable, only named. It was
 * named by an earlier landing that moved the graphs out and left this file behind; this
 * one is what pays that off.
 */
internal val topLevelRouteIndices: Map<String, Int> =
    TopLevelDestination.entries.mapIndexed { index, destination -> destination.route to index }.toMap()

/**
 * What kind of destination a route is, which is what the four `NavHost` transitions are
 * written against. `internal` for the same reason as [topLevelRouteIndices].
 */
internal enum class NavigationRole { TopLevel, Overlay, Detail }

/** `internal` for the same reason as [topLevelRouteIndices]. */
internal fun roleOf(route: String?): NavigationRole {
    return when {
        topLevelRouteIndices.containsKey(route) -> NavigationRole.TopLevel
        route == AppRoutes.SearchRoute -> NavigationRole.Overlay
        else -> NavigationRole.Detail
    }
}

/** `internal` for the same reason as [topLevelRouteIndices]. */
internal fun topLevelRouteIndex(route: String?): Int {
    return topLevelRouteIndices[route] ?: -1
}

/**
 * The app's navigation host.
 *
 * ## What this file is now
 *
 * It used to be the Android composition root for the whole app, and it is now a shared
 * file that decides two things and holds nothing: **which destinations exist, and which
 * direction each transition moves in.** Everything platform-shaped crossed out into
 * [AppNavHostDependencies] and the `androidMain` producer that builds it, which is the
 * same boundary `addonsSettingsViewModelFactory` and `SettingsNavDependencies` already
 * drew. Thirty-odd Android factories used to be named here by import; now the names of
 * thirty-odd *products* are named here as members, and this file's import list is eleven
 * lines long.
 *
 * The two assembly steps that stay are the ones that are not wiring:
 * [HomeNavDependencies] and [SettingsNavDependencies] are constructed from the bundle
 * here rather than on the other side of the line. Assembly is not dependency-injection --
 * it names no factory, reads no `Context`, and decides nothing -- and keeping it here is
 * what leaves the aliasing decision (`playbackSettingsRepository` is the *same* repository
 * instance the Home graph got) visible in the file where both consumers are registered.
 *
 * ## `dependencies` is a producer and not a value, and the reason is a composition local
 *
 * It could have been a plain `AppNavHostDependencies` parameter, and it is not: two of the
 * values it carries are read from `LocalConfiguration`, which is Android-only, so the
 * object cannot be built by the caller that does not have a context. A `@Composable () ->
 * AppNavHostDependencies` slot keeps the `remember`s in the file that knows how to build
 * each value while the decisions about *what* to build stay here -- and the call is made
 * inside `CrispySharedTransitionLayout` rather than in the `NavHost` builder lambda,
 * because **that lambda is not a composable scope** and a `remember` inside it would not
 * be one either. That is the same lesson the search graph's hoisting block already records,
 * and it is why the slot carries the *remembered* value rather than a lambda producing it.
 */
@Composable
internal fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onSignedOut: () -> Unit = {},
    dependencies: @Composable () -> AppNavHostDependencies,
) {
    CrispySharedTransitionLayout {
        val d = dependencies()

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
            addHomeNavGraph(
                navController = navController,
                dependencies = HomeNavDependencies(
                    homeViewModelFactory = d.homeViewModelFactory,
                    homeSelectorViewModelFactory = d.homeSelectorViewModelFactory,
                    loadProfile = d.homeLoadProfile,
                    calendarViewModelFactory = d.homeCalendarViewModelFactory,
                    catalogViewModelFactory = d.homeCatalogViewModelFactory,
                    detailsViewModelFactory = d.homeDetailsViewModelFactory,
                    personViewModelFactory = d.homePersonViewModelFactory,
                    detailsArguments = d.homeDetailsArguments,
                    personArguments = d.homePersonArguments,
                    catalogArguments = d.homeCatalogArguments,
                    playbackSettingsRepository = d.homePlaybackSettingsRepository,
                    shareText = d.homeShareText,
                    dateFormat = d.homeDateFormat,
                    timeFormat = d.homeTimeFormat,
                    clock = d.homeClock,
                    screenWidthDp = d.homeScreenWidthDp,
                    screenHeightDp = d.homeScreenHeightDp,
                    youtubeTrailerPlaybackSupported = d.homeYoutubeTrailerPlaybackSupported,
                    imageSeedColor = d.homeImageSeedColor,
                    heroTrailerLayer = d.homeHeroTrailerLayer,
                    reviewProviderBadge = d.homeReviewProviderBadge,
                    ratingBadgeLogo = d.homeRatingBadgeLogo,
                    youTubeExtraVideoDialog = d.homeYouTubeExtraVideoDialog,
                    formatBirthday = d.homeFormatBirthday,
                ),
            )
            addSearchNavGraph(
                navController = navController,
                searchViewModelFactory = d.searchViewModelFactory,
                loadProfile = d.searchLoadProfile,
            )
            addDiscoverNavGraph(
                navController = navController,
                viewModelFactory = d.discoverViewModelFactory,
                loadProfile = d.discoverLoadProfile,
            )
            addLibraryNavGraph(
                navController = navController,
                viewModelFactory = d.libraryViewModelFactory,
                monthName = d.libraryMonthName,
                clock = d.libraryClock,
                utcOffsetMillis = d.libraryUtcOffsetMillis,
                loadProfile = d.libraryLoadProfile,
                logger = d.libraryLogger,
            )
            addSettingsNavGraph(
                navController = navController,
                dependencies = SettingsNavDependencies(
                    pluginsUiSupported = d.pluginsUiSupported,
                    pluginsSettingsScreen = d.pluginsSettingsScreen,
                    profileListViewModelFactory = d.profileListFactory,
                    addonsSettingsViewModelFactory = d.addonsSettingsViewModelFactory,
                    imageSettingsRepository = d.imageSettingsRepository,
                    // The *same instance* the Home graph above was given, rather than a
                    // second build of it. This is the one place where the "each graph
                    // builds its own" rule above deliberately does not apply: a repository
                    // is a store, not a callback, so two stores for one app context would
                    // be two sources of truth for the same setting. The loaders cannot be
                    // aliased the same way precisely because they are callbacks and their
                    // identity is what a `produceState` keys on.
                    playbackSettingsRepository = d.homePlaybackSettingsRepository,
                    profileDataCloudSync = d.profileDataCloudSync,
                ),
            )
            addAccountNavGraph(
                navController = navController,
                onSignedOut = onSignedOut,
                profileListFactory = d.profileListFactory,
                accountSettingsFactory = d.accountSettingsFactory,
                loadProfile = d.accountLoadProfile,
            )
            d.addPlayerDestination(this, navController)
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