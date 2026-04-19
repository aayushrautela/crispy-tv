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
                    AppRoutes.homeDetailsRoute(
                        mediaKey = hero.id,
                        mediaType = hero.type,
                    )
                )
            },
            onContinueWatchingClick = { item ->
                navController.navigate(
                    AppRoutes.homeDetailsRoute(
                        mediaKey = item.titleMediaKey,
                        mediaType = item.type,
                        seasonNumber = item.season,
                        episodeNumber = item.episode,
                        absoluteEpisodeNumber = item.absoluteEpisodeNumber,
                        autoOpenEpisode = true,
                    )
                )
            },
            onContinueWatchingOpenDetails = { item ->
                navController.navigate(
                    AppRoutes.homeDetailsRoute(
                        mediaKey = item.titleMediaKey,
                        mediaType = item.type,
                        seasonNumber = item.season,
                        episodeNumber = item.episode,
                        absoluteEpisodeNumber = item.absoluteEpisodeNumber,
                        highlightEpisodeId = "${item.season}:${item.episode}",
                        autoOpenEpisode = false,
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
                    AppRoutes.homeDetailsRoute(
                        mediaKey = item.mediaKey,
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
                    AppRoutes.homeDetailsRoute(
                        mediaKey = item.mediaKey,
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
                    AppRoutes.homeDetailsRoute(
                        mediaKey = item.mediaKey,
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
                navArgument(AppRoutes.HomeDetailsMediaKeyArg) { type = NavType.StringType },
                navArgument(AppRoutes.HomeDetailsHighlightEpisodeIdArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsAutoOpenEpisodeArg) { type = NavType.BoolType; defaultValue = false },
                navArgument(AppRoutes.HomeDetailsRuntimeSeasonNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg) { type = NavType.StringType; defaultValue = "" },
            )
    ) { entry ->
        val mediaKey = entry.arguments?.getString(AppRoutes.HomeDetailsMediaKeyArg).orEmpty()
        val mediaType = entry.arguments?.getString(AppRoutes.HomeDetailsMediaTypeArg).orEmpty()
        val highlightEpisodeId = entry.arguments?.getString(AppRoutes.HomeDetailsHighlightEpisodeIdArg)?.ifBlank { null }
        val autoOpenEpisode = entry.arguments?.getBoolean(AppRoutes.HomeDetailsAutoOpenEpisodeArg) == true
        val runtimeEntry = RuntimeDetailsEntry(
            seasonNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeSeasonNumberArg)?.toIntOrNull(),
            episodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg)?.toIntOrNull(),
            absoluteEpisodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg)?.toIntOrNull(),
        ).takeIf {
            it.seasonNumber != null || it.episodeNumber != null || it.absoluteEpisodeNumber != null
        }
        val context = LocalContext.current
        DetailsRoute(
            mediaKey = mediaKey,
            mediaType = mediaType,
            runtimeEntry = runtimeEntry,
            highlightEpisodeId = highlightEpisodeId,
            autoOpenEpisode = autoOpenEpisode,
            onBack = { navController.popBackStack() },
            onItemClick = { item ->
                navController.navigate(
                    AppRoutes.homeDetailsRoute(
                        mediaKey = item.mediaKey,
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
                    AppRoutes.homeDetailsRoute(
                        mediaKey = item.mediaKey,
                        mediaType = item.type,
                    )
                )
            }
        )
    }
}

private fun NavHostController.navigateToCalendarEpisode(item: CalendarEpisodeItem) {
    navigate(
        AppRoutes.homeDetailsRoute(
            mediaKey = item.titleMediaKey,
            mediaType = item.type,
            seasonNumber = item.season,
            episodeNumber = item.episode,
            absoluteEpisodeNumber = item.absoluteEpisodeNumber,
            highlightEpisodeId = item.highlightEpisodeId.takeIf { !item.isGroup },
            autoOpenEpisode = false,
        )
    )
}
