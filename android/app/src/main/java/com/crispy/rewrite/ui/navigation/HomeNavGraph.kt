package com.crispy.rewrite.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.crispy.rewrite.catalog.CatalogRoute
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.details.DetailsRoute
import com.crispy.rewrite.home.HomeScreen

internal fun NavGraphBuilder.addHomeNavGraph(navController: NavHostController) {
    composable(AppRoutes.HomeRoute) {
        HomeScreen(
            onHeroClick = { hero ->
                navController.navigate(AppRoutes.homeDetailsRoute(hero.id))
            },
            onContinueWatchingClick = { item ->
                navController.navigate(AppRoutes.homeDetailsRoute(item.contentId))
            },
            onCatalogItemClick = { catalogItem ->
                navController.navigate(AppRoutes.homeDetailsRoute(catalogItem.id))
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
            onItemClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id)) }
        )
    }

    composable(
        route = AppRoutes.HomeDetailsRoutePattern,
        arguments = listOf(navArgument(AppRoutes.HomeDetailsItemIdArg) { type = NavType.StringType })
    ) { entry ->
        val itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg).orEmpty()
        DetailsRoute(
            itemId = itemId,
            onBack = { navController.popBackStack() },
            onItemClick = { nextId -> navController.navigate(AppRoutes.homeDetailsRoute(nextId)) }
        )
    }
}
