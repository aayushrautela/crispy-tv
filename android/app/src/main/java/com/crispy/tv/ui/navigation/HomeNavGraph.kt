package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.crispy.tv.catalog.CatalogRoute
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.details.DetailsRoute
import com.crispy.tv.home.HomeScreen
import com.crispy.tv.home.ThisWeekItem
import com.crispy.tv.person.PersonDetailsRoute
import com.crispy.tv.playerui.PlayerActivity

internal fun NavGraphBuilder.addHomeNavGraph(navController: NavHostController) {
    composable(AppRoutes.HomeRoute) {
        HomeScreen(
            onHeroClick = { hero ->
                navController.navigate(AppRoutes.homeDetailsRoute(hero.id, hero.type))
            },
            onContinueWatchingClick = { item ->
                navController.navigate(AppRoutes.homeDetailsRoute(item.contentId, item.type))
            },
            onThisWeekClick = { item ->
                navController.navigate(AppRoutes.homeDetailsRoute(item.seriesId, item.type))
            },
            onSearchClick = {
                navController.navigate(AppRoutes.SearchRoute)
            },
            onProfileClick = {
                navController.navigate(AppRoutes.AccountsProfilesRoute)
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
        )
    ) { entry ->
        val itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg).orEmpty()
        val mediaType = entry.arguments?.getString(AppRoutes.HomeDetailsMediaTypeArg).orEmpty()
        val context = LocalContext.current
        DetailsRoute(
            itemId = itemId,
            mediaType = mediaType,
            onBack = { navController.popBackStack() },
            onItemClick = { nextId, nextType -> navController.navigate(AppRoutes.homeDetailsRoute(nextId, nextType)) },
            onPersonClick = { personId -> navController.navigate(AppRoutes.personDetailsRoute(personId)) },
            onOpenPlayer = { playbackUrl, title, identity ->
                context.startActivity(PlayerActivity.intent(context, playbackUrl, title, identity))
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
