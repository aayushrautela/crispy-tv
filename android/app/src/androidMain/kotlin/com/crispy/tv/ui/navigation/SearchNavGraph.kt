package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import com.crispy.tv.accounts.activeProfileLoader
import com.crispy.tv.search.SearchRoute
import com.crispy.tv.search.searchViewModelFactory

internal fun NavGraphBuilder.addSearchNavGraph(navController: NavHostController) {
    composable(AppRoutes.SearchRoute) { entry ->
        val context = LocalPlatformContext.current
        val appContext = remember(context) { context.applicationContext }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            SearchRoute(
            onItemClick = { item, sharedElementKey ->
                if (item.type.equals("person", ignoreCase = true)) {
                    navController.navigate(AppRoutes.personDetailsRoute(item.id, item.artworkUrl))
                } else {
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
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
                viewModelFactory = remember(appContext) { searchViewModelFactory(appContext) },
                loadProfile = remember(appContext) { activeProfileLoader(appContext) },
            )
        }
    }
}
