package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import com.crispy.tv.catalog.CatalogRoute
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.details.DetailsRoute
import com.crispy.tv.home.CalendarEpisodeItem
import com.crispy.tv.home.CalendarRoute
import com.crispy.tv.home.CalendarSeriesItem
import com.crispy.tv.home.HomeRoute
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.person.PersonDetailsRoute
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.playerui.PlayerActivity

internal fun NavGraphBuilder.addHomeNavGraph(navController: NavHostController) {
    composable(AppRoutes.HomeRoute) { entry ->
        HomeRoute(
            onHeroClick = { hero ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = hero.provider,
                        providerId = hero.providerId,
                        mediaType = hero.type,
                    )
                )
            },
            onContinueWatchingClick = { item ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = item.provider,
                        providerId = item.providerId,
                        mediaType = item.type,
                        seasonNumber = item.season,
                        episodeNumber = item.episode,
                        absoluteEpisodeNumber = item.absoluteEpisodeNumber,
                    )
                )
            },
            onThisWeekClick = { item ->
                navController.navigateToCalendarEpisode(item)
            },
            onThisWeekSeeAllClick = {
                navController.navigate(AppRoutes.CalendarRoute)
            },
            onCatalogItemClick = { item ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = item.provider,
                        providerId = item.providerId,
                        mediaType = item.type,
                    )
                )
            },
            onCatalogSeeAllClick = { section ->
                navController.navigate(AppRoutes.catalogListRoute(section))
            },
            onOpenAccountsProfiles = {
                navController.navigate(AppRoutes.AccountsProfilesRoute) {
                    launchSingleTop = true
                }
            },
            scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
            onScrollToTopConsumed = {
                entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
            },
        )
    }

    composable(AppRoutes.CalendarRoute) {
        CalendarRoute(
            onBack = { navController.popBackStack() },
            onEpisodeClick = { item -> navController.navigateToCalendarEpisode(item) },
            onSeriesClick = { item ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = item.provider,
                        providerId = item.providerId,
                        mediaType = item.type,
                    )
                )
            },
        )
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
        val section =
            CatalogSectionRef(
                catalogId = args?.getString(AppRoutes.CatalogIdArg).orEmpty(),
                source = com.crispy.tv.domain.home.resolveHomeCatalogSource(args?.getString(AppRoutes.CatalogIdArg).orEmpty()),
                presentation = com.crispy.tv.domain.home.HomeCatalogPresentation.RAIL,
                title = args?.getString(AppRoutes.CatalogTitleArg).orEmpty(),
            )
        CatalogRoute(
            section = section,
            onBack = { navController.popBackStack() },
            onItemClick = { item ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = item.provider,
                        providerId = item.providerId,
                        mediaType = item.type,
                    )
                )
            }
        )
    }

    composable(
        route = AppRoutes.HomeDetailsRoutePattern,
        arguments = listOf(
                navArgument(AppRoutes.HomeDetailsMediaTypeArg) { type = NavType.StringType },
                navArgument(AppRoutes.HomeDetailsItemIdArg) { type = NavType.StringType },
                navArgument(AppRoutes.HomeDetailsHighlightEpisodeIdArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsAutoOpenEpisodeArg) { type = NavType.BoolType; defaultValue = false },
                navArgument(AppRoutes.HomeDetailsRuntimeProviderArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeProviderIdArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeSeasonNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg) { type = NavType.StringType; defaultValue = "" },
            )
    ) { entry ->
        val itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg).orEmpty()
        val mediaType = entry.arguments?.getString(AppRoutes.HomeDetailsMediaTypeArg).orEmpty()
        val highlightEpisodeId = entry.arguments?.getString(AppRoutes.HomeDetailsHighlightEpisodeIdArg)?.ifBlank { null }
        val autoOpenEpisode = entry.arguments?.getBoolean(AppRoutes.HomeDetailsAutoOpenEpisodeArg) == true
        val runtimeProvider = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeProviderArg)?.ifBlank { null }
        val runtimeProviderId = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeProviderIdArg)?.ifBlank { null }
        val runtimeEntry =
            if (runtimeProvider != null && runtimeProviderId != null) {
                RuntimeDetailsEntry(
                    provider = runtimeProvider,
                    providerId = runtimeProviderId,
                    mediaType = mediaType,
                    seasonNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeSeasonNumberArg)?.toIntOrNull(),
                    episodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg)?.toIntOrNull(),
                    absoluteEpisodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg)?.toIntOrNull(),
                )
            } else {
                null
            }
        val context = LocalContext.current
        DetailsRoute(
            itemId = itemId,
            mediaType = mediaType,
            runtimeEntry = runtimeEntry,
            highlightEpisodeId = highlightEpisodeId,
            autoOpenEpisode = autoOpenEpisode,
            onBack = { navController.popBackStack() },
            onItemClick = { item ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = item.provider,
                        providerId = item.providerId,
                        mediaType = item.type,
                    )
                )
            },
            onPersonClick = { personId -> navController.navigate(AppRoutes.personDetailsRoute(personId)) },
            onOpenPlayer = { playbackUrl, playbackHeaders, title, identity, subtitle, artworkUrl, launchSnapshot ->
                context.startActivity(
                    PlayerActivity.intent(
                        context = context,
                        playbackUrl = playbackUrl,
                        playbackHeaders = playbackHeaders,
                        title = title,
                        identity = identity,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
                        launchSnapshot = launchSnapshot,
                    )
                )
            },
        )
    }

    composable(
        route = AppRoutes.PersonDetailsRoutePattern,
        arguments = listOf(
            navArgument(AppRoutes.PersonDetailsPersonIdArg) { type = NavType.StringType }
        )
    ) { entry ->
        val personId = entry.arguments?.getString(AppRoutes.PersonDetailsPersonIdArg).orEmpty()
        PersonDetailsRoute(
            personId = personId,
            onBack = { navController.popBackStack() },
            onItemClick = { item ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = item.provider,
                        providerId = item.providerId,
                        mediaType = item.type,
                    )
                )
            }
        )
    }
}

private fun NavHostController.navigateToCalendarEpisode(item: CalendarEpisodeItem) {
    navigate(
        AppRoutes.runtimeDetailsRoute(
            provider = item.provider,
            providerId = item.providerId,
            mediaType = item.type,
            seasonNumber = item.season,
            episodeNumber = item.episode,
            absoluteEpisodeNumber = item.absoluteEpisodeNumber,
            highlightEpisodeId = item.highlightEpisodeId.takeIf { !item.isGroup },
            autoOpenEpisode = item.isReleased && !item.isGroup,
        )
    )
}
