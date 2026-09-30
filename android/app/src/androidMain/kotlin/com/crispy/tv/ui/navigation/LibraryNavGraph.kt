package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.runtime.CompositionLocalProvider
import android.util.Log
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.library.libraryViewModelFactory
import com.crispy.tv.details.localeDateFormatters
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext

import com.crispy.tv.library.LibraryRoute

internal fun NavGraphBuilder.addLibraryNavGraph(navController: NavHostController) {
    composable(AppRoutes.LibraryRoute) { entry ->
        // Read in the composable body, not inside the `composable` content lambda: the
        // builder is not a @Composable scope. `LocalPlatformContext` is Coil's own
        // composition local, and its Android value is the same `LocalContext` this
        // used to read by name.
        val context = LocalPlatformContext.current
        val appContext = remember(context) { context.applicationContext }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            LibraryRoute(
            viewModelFactory = remember(appContext) { libraryViewModelFactory(appContext) },
            monthName = remember(appContext) { localeDateFormatters(appContext).monthName },
            onItemClick = { item, sharedElementKey ->
                Log.d(
                    "LibraryNav",
                    "onItemClick title=${item.title} itemId=${item.itemId} type=${item.type} sharedElementKey=$sharedElementKey",
                )
                navController.navigate(
                    AppRoutes.homeDetailsRoute(
                        itemId = item.itemId,
                        itemType = item.type,
                        artworkUrl = item.artworkUrl,
                        sharedElementKey = sharedElementKey,
                    )
                )
            },
                onOpenCalendar = {
                    navController.navigate(AppRoutes.CalendarRoute) {
                        launchSingleTop = true
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
            )
        }
    }
}
