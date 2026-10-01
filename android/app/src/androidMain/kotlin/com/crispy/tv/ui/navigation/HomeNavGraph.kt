package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.crispy.tv.accounts.activeProfileLoader
import com.crispy.tv.catalog.CatalogRoute
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.details.DetailsRoute
import com.crispy.tv.home.CalendarEpisodeItem
import com.crispy.tv.home.CalendarRoute
import com.crispy.tv.home.CalendarSeriesItem
import com.crispy.tv.home.HomeRoute
import com.crispy.tv.home.calendarViewModelFactory
import com.crispy.tv.home.homeSelectorViewModelFactory
import com.crispy.tv.home.homeViewModelFactory
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.person.PersonDetailsRoute
import com.crispy.tv.player.CanonicalContinueWatchingItem

internal fun NavGraphBuilder.addHomeNavGraph(navController: NavHostController) {
    composable(AppRoutes.HomeRoute) { entry ->
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            // `HomeRoute` takes its two factories and its profile loader as slots, so
            // this is where the `Context` is read now. Nothing here *names*
            // `android.content.Context`: `NavHostController.context` is an Android
            // property reached through the expression, which is exactly the
            // capability this file has by being `androidMain` at all.
            //
            // The `remember` keys are load-bearing, not tidiness.
            // `activeProfileLoader`'s own KDoc records that its return value is a
            // `produceState` key, so a fresh lambda on every recomposition would
            // restart the profile load each time -- the same identity the deleted
            // comment was protecting when it read `LocalContext.current` here.
            val appContext = navController.context.applicationContext
            HomeRoute(
                viewModelFactory = remember(appContext) { homeViewModelFactory(appContext) },
                selectorViewModelFactory = remember(appContext) { homeSelectorViewModelFactory(appContext) },
                loadProfile = remember(appContext) { activeProfileLoader(appContext) },
                // The `< 600` threshold lived in `HomeStreamSelector` while it also read
                // `LocalConfiguration` itself. `LocalConfiguration` is Android-only --
                // `ui-android`'s `AndroidCompositionLocals_androidKt` -- so a
                // `commonMain` composable cannot name it and no static scan can see the
                // dependency at all. **So the value crosses and the decision stays here**,
                // which is the one place in this chain where a platform value is
                // available: this file already reads `navController.context`.
                isCompact = LocalConfiguration.current.screenWidthDp < 600,
                onHeroClick = { hero, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = hero.id,
                            itemType = hero.type,
                            artworkUrl = hero.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                onContinueWatchingOpenDetails = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.titleItemId,
                            itemType = item.type,
                            seasonNumber = item.season,
                            episodeNumber = item.episode,
                            absoluteEpisodeNumber = item.absoluteEpisodeNumber,
                            highlightEpisodeId = item.playbackItemId,
                            autoOpenEpisode = false,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                onThisWeekClick = { item, sharedElementKey ->
                    navController.navigateToCalendarEpisode(
                        item = item,
                        sharedElementKey = sharedElementKey,
                    )
                },
                onThisWeekSeeAllClick = {
                    navController.navigate(AppRoutes.CalendarRoute)
                },
                onCatalogItemClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                onCatalogSeeAllClick = { section ->
                    navController.navigate(AppRoutes.catalogListRoute(section))
                },
                onOpenAccountsProfiles = {
                    navController.navigate(AppRoutes.ProfileMenuRoute) {
                        launchSingleTop = true
                    }
                },
                onOpenPlayer = { identity, resumePositionMs, chosenStreamStableKey, chosenProviderId, chosenStreamHandoffKey ->
                    navController.navigate(
                        AppRoutes.playerRoute(
                            identity = identity,
                            resumePositionMs = resumePositionMs,
                            chosenStreamStableKey = chosenStreamStableKey,
                            chosenProviderId = chosenProviderId,
                            chosenStreamHandoffKey = chosenStreamHandoffKey,
                        )
                    )
                },
                scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
                onScrollToTopConsumed = {
                    entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
                },
            )
        }
    }

    composable(AppRoutes.CalendarRoute) {
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            // `CalendarRoute` is in `commonMain` and its only platform value was a
            // `Context` read to reach the factory, so the read moved here with the
            // factory. Same shape as the `HomeRoute` block above, and the
            // `remember` is the one `CalendarScreen` used to own, kept so this is a
            // port rather than a behaviour change: without it the service graph is
            // rebuilt on every recomposition.
            val appContext = navController.context.applicationContext
            CalendarRoute(
                onBack = { navController.popBackStack() },
                onEpisodeClick = { item, sharedElementKey ->
                    navController.navigateToCalendarEpisode(item = item, sharedElementKey = sharedElementKey)
                },
                onSeriesClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                viewModelFactory = remember(appContext) { calendarViewModelFactory(appContext) },
            )
        }
    }

    composable(
        route = AppRoutes.CatalogListRoutePattern,
        arguments =
            listOf(
                navArgument(AppRoutes.CatalogIdArg) { type = NavType.StringType },
                navArgument(AppRoutes.CatalogTitleArg) { type = NavType.StringType; defaultValue = "" }
            )
    ) { entry ->
        val args = entry.arguments
        val catalogId = args?.getString(AppRoutes.CatalogIdArg).orEmpty()
        val catalogIdentifier =
            com.crispy.tv.domain.home.parseHomeCatalogId(catalogId)
        val section =
            CatalogSectionRef(
                catalogId = catalogId,
                source = catalogIdentifier?.source ?: com.crispy.tv.domain.home.HomeCatalogSource.PERSONAL,
                kind = catalogIdentifier?.kind.orEmpty(),
                presentation = com.crispy.tv.domain.home.HomeCatalogPresentation.RAIL,
                title = args?.getString(AppRoutes.CatalogTitleArg).orEmpty(),
            )
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            CatalogRoute(
                section = section,
                onBack = { navController.popBackStack() },
                onItemClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                }
            )
        }
    }

    composable(
        route = AppRoutes.HomeDetailsRoutePattern,
        arguments = listOf(
                navArgument(AppRoutes.HomeDetailsItemTypeArg) { type = NavType.StringType },
                navArgument(AppRoutes.HomeDetailsItemIdArg) { type = NavType.StringType },
                navArgument(AppRoutes.HomeDetailsHighlightEpisodeIdArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsAutoOpenEpisodeArg) { type = NavType.BoolType; defaultValue = false },
                navArgument(AppRoutes.HomeDetailsRuntimeSeasonNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsArtworkUrlArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsSharedElementKeyArg) { type = NavType.StringType; defaultValue = "" },
            )
    ) { entry ->
        val itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg).orEmpty()
        val itemType = entry.arguments?.getString(AppRoutes.HomeDetailsItemTypeArg).orEmpty()
        val highlightEpisodeId = entry.arguments?.getString(AppRoutes.HomeDetailsHighlightEpisodeIdArg)?.ifBlank { null }
        val autoOpenEpisode = entry.arguments?.getBoolean(AppRoutes.HomeDetailsAutoOpenEpisodeArg) == true
        val runtimeEntry = RuntimeDetailsEntry(
            seasonNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeSeasonNumberArg)?.toIntOrNull(),
            episodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg)?.toIntOrNull(),
            absoluteEpisodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg)?.toIntOrNull(),
        ).takeIf {
            it.seasonNumber != null || it.episodeNumber != null || it.absoluteEpisodeNumber != null
        }
        val initialArtworkUrl = entry.arguments?.getString(AppRoutes.HomeDetailsArtworkUrlArg)?.ifBlank { null }
        val sharedElementKey = entry.arguments?.getString(AppRoutes.HomeDetailsSharedElementKeyArg)?.ifBlank { null }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            DetailsRoute(
                itemId = itemId,
                itemType = itemType,
                runtimeEntry = runtimeEntry,
                highlightEpisodeId = highlightEpisodeId,
                autoOpenEpisode = autoOpenEpisode,
                initialArtworkUrl = initialArtworkUrl,
                sharedElementKey = sharedElementKey,
                onBack = { navController.popBackStack() },
                onItemClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                onPersonClick = { personId, profileUrl -> navController.navigate(AppRoutes.personDetailsRoute(personId, profileUrl)) },
                onOpenPlayer = { identity, resumePositionMs, chosenStreamStableKey, chosenProviderId, chosenStreamHandoffKey ->
                    navController.navigate(
                        AppRoutes.playerRoute(
                            identity = identity,
                            resumePositionMs = resumePositionMs,
                            chosenStreamStableKey = chosenStreamStableKey,
                            chosenProviderId = chosenProviderId,
                            chosenStreamHandoffKey = chosenStreamHandoffKey,
                        )
                    )
                },
            )
        }
    }

    composable(
        route = AppRoutes.PersonDetailsRoutePattern,
        arguments = listOf(
            navArgument(AppRoutes.PersonDetailsPersonIdArg) { type = NavType.StringType },
            navArgument(AppRoutes.PersonDetailsProfileUrlArg) {
                type = NavType.StringType
                defaultValue = ""
            }
        )
    ) { entry ->
        val personId = entry.arguments?.getString(AppRoutes.PersonDetailsPersonIdArg).orEmpty()
        val profileUrl = entry.arguments?.getString(AppRoutes.PersonDetailsProfileUrlArg).orEmpty()
            .takeIf { it.isNotBlank() }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            PersonDetailsRoute(
                personId = personId,
                initialProfileUrl = profileUrl,
                onBack = { navController.popBackStack() },
                onItemClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                }
            )
        }
    }
}

private fun NavHostController.navigateToCalendarEpisode(item: CalendarEpisodeItem, sharedElementKey: String? = null) {
    navigate(
        AppRoutes.homeDetailsRoute(
            itemId = item.titleItemId,
            itemType = item.type,
            seasonNumber = item.season,
            episodeNumber = item.episode,
            absoluteEpisodeNumber = item.absoluteEpisodeNumber,
            highlightEpisodeId = item.highlightEpisodeId.takeIf { !item.isGroup },
            autoOpenEpisode = false,
            artworkUrl = item.artworkUrl,
            sharedElementKey = sharedElementKey,
        )
    )
}
