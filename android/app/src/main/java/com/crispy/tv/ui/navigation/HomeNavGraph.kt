package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.crispy.tv.catalog.CatalogRoute
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.details.DetailsRoute
import com.crispy.tv.home.HomeScreen
import com.crispy.tv.playerui.PlayerRoute

internal fun NavGraphBuilder.addHomeNavGraph(navController: NavHostController) {
    composable(AppRoutes.HomeRoute) {
        HomeScreen(
            onHeroClick = { hero ->
                navController.navigate(AppRoutes.homeDetailsRoute(hero.id, hero.type))
            },
            onContinueWatchingClick = { item ->
                navController.navigate(AppRoutes.homeDetailsRoute(item.contentId, item.type))
            },
            onCatalogItemClick = { catalogItem ->
                navController.navigate(AppRoutes.homeDetailsRoute(catalogItem.id, catalogItem.type))
            },
            onCatalogSeeAllClick = { section ->
                navController.navigate(AppRoutes.catalogListRoute(section))
            }
        )
    }

    composable(
        route = AppRoutes.CatalogListRoutePattern,
        arguments =
            listOf(
                navArgument(AppRoutes.CatalogMediaTypeArg) { type = NavType.StringType },
                navArgument(AppRoutes.CatalogIdArg) { type = NavType.StringType },
                navArgument(AppRoutes.CatalogTitleArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.CatalogAddonIdArg) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(AppRoutes.CatalogBaseUrlArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.CatalogQueryArg) { type = NavType.StringType; defaultValue = "" }
            )
    ) { entry ->
        val args = entry.arguments
        val section =
            CatalogSectionRef(
                title = args?.getString(AppRoutes.CatalogTitleArg).orEmpty(),
                catalogId = args?.getString(AppRoutes.CatalogIdArg).orEmpty(),
                mediaType = args?.getString(AppRoutes.CatalogMediaTypeArg).orEmpty(),
                addonId = args?.getString(AppRoutes.CatalogAddonIdArg).orEmpty(),
                baseUrl = args?.getString(AppRoutes.CatalogBaseUrlArg).orEmpty(),
                encodedAddonQuery =
                    args?.getString(AppRoutes.CatalogQueryArg)?.takeIf { it.isNotBlank() }
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
            navArgument(AppRoutes.HomeDetailsItemIdArg) { type = NavType.StringType },
            navArgument(AppRoutes.HomeDetailsMediaTypeArg) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        )
    ) { entry ->
        val itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg).orEmpty()
        val mediaType = entry.arguments?.getString(AppRoutes.HomeDetailsMediaTypeArg)
        DetailsRoute(
            itemId = itemId,
            mediaType = mediaType,
            onBack = { navController.popBackStack() },
            onItemClick = { nextId, nextType -> navController.navigate(AppRoutes.homeDetailsRoute(nextId, nextType)) },
            onOpenPlayer = { playbackUrl, title ->
                navController.navigate(AppRoutes.playerRoute(playbackUrl, title))
            },
        )
    }

    composable(
        route = AppRoutes.PlayerRoutePattern,
        arguments =
            listOf(
                navArgument(AppRoutes.PlayerUrlArg) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(AppRoutes.PlayerTitleArg) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
    ) { entry ->
        val playbackUrl = entry.arguments?.getString(AppRoutes.PlayerUrlArg).orEmpty()
        if (playbackUrl.isBlank()) {
            navController.popBackStack()
            return@composable
        }

        PlayerRoute(
            playbackUrl = playbackUrl,
            title = entry.arguments?.getString(AppRoutes.PlayerTitleArg).orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }
}
