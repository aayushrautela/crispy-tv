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
import com.crispy.tv.person.PersonDetailsRoute
import com.crispy.tv.playerui.PlayerActivity

internal fun NavGraphBuilder.addHomeNavGraph(navController: NavHostController) {
    composable(AppRoutes.HomeRoute) {
        HomeRoute(
            onHeroClick = { hero ->
                navController.navigate(AppRoutes.homeDetailsRoute(hero.id, hero.type))
            },
            onContinueWatchingClick = { item ->
                navController.navigate(AppRoutes.homeDetailsRoute(item.contentId, item.type))
            },
            onThisWeekClick = { item ->
                navController.navigateToCalendarEpisode(item)
            },
            onThisWeekSeeAllClick = {
                navController.navigate(AppRoutes.CalendarRoute)
            },
            onProfileClick = {
                navController.navigate(AppRoutes.AccountsProfilesRoute)
            },
            onCatalogItemClick = { item ->
                navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type))
            },
            onCollectionMovieClick = { item ->
                navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type))
            },
            onCatalogSeeAllClick = { section ->
                navController.navigate(AppRoutes.catalogListRoute(section))
            }
        )
    }

    composable(AppRoutes.CalendarRoute) {
        CalendarRoute(
            onBack = { navController.popBackStack() },
            onEpisodeClick = { item -> navController.navigateToCalendarEpisode(item) },
            onSeriesClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type)) },
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
                title = args?.getString(AppRoutes.CatalogTitleArg).orEmpty(),
                catalogId = args?.getString(AppRoutes.CatalogIdArg).orEmpty()
            )
        CatalogRoute(
            section = section,
            onBack = { navController.popBackStack() },
            onItemClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type)) }
        )
    }

    composable(
        route = AppRoutes.HomeDetailsRoutePattern,
        arguments = listOf(
            navArgument(AppRoutes.HomeDetailsMediaTypeArg) { type = NavType.StringType },
            navArgument(AppRoutes.HomeDetailsItemIdArg) { type = NavType.StringType },
            navArgument(AppRoutes.HomeDetailsSeasonArg) { type = NavType.IntType; defaultValue = -1 },
            navArgument(AppRoutes.HomeDetailsEpisodeArg) { type = NavType.IntType; defaultValue = -1 },
            navArgument(AppRoutes.HomeDetailsAutoOpenEpisodeArg) { type = NavType.BoolType; defaultValue = false },
        )
    ) { entry ->
        val itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg).orEmpty()
        val mediaType = entry.arguments?.getString(AppRoutes.HomeDetailsMediaTypeArg).orEmpty()
        val initialSeason = entry.arguments?.getInt(AppRoutes.HomeDetailsSeasonArg)?.takeIf { it > 0 }
        val initialEpisode = entry.arguments?.getInt(AppRoutes.HomeDetailsEpisodeArg)?.takeIf { it > 0 }
        val autoOpenEpisode = entry.arguments?.getBoolean(AppRoutes.HomeDetailsAutoOpenEpisodeArg) == true
        val context = LocalContext.current
        DetailsRoute(
            itemId = itemId,
            mediaType = mediaType,
            initialSeason = initialSeason,
            initialEpisode = initialEpisode,
            autoOpenEpisode = autoOpenEpisode,
            onBack = { navController.popBackStack() },
            onItemClick = { nextId, nextType -> navController.navigate(AppRoutes.homeDetailsRoute(nextId, nextType)) },
            onPersonClick = { personId -> navController.navigate(AppRoutes.personDetailsRoute(personId)) },
            onOpenPlayer = { playbackUrl, title, identity, subtitle, artworkUrl ->
                context.startActivity(
                    PlayerActivity.intent(
                        context = context,
                        playbackUrl = playbackUrl,
                        title = title,
                        identity = identity,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
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
            onItemClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type)) }
        )
    }
}

private fun NavHostController.navigateToCalendarEpisode(item: CalendarEpisodeItem) {
    navigate(
        AppRoutes.homeDetailsRoute(
            itemId = item.seriesId,
            mediaType = item.type,
            initialSeason = item.season,
            initialEpisode = item.episode.takeIf { !item.isGroup },
            autoOpenEpisode = item.isReleased && !item.isGroup,
        )
    )
}
