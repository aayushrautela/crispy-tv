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
import com.crispy.tv.accounts.activeProfileLoader
import com.crispy.tv.library.deviceUtcOffsetMillis

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
            // Three more platform values, for a route that is `commonMain` now. The
            // clock and the offset are lambdas rather than values on purpose: the
            // offset is read fresh per call because an offset remembered at
            // composition time is wrong either side of a daylight-saving change, and
            // this screen groups rows by month. `loadProfile` is a lambda because
            // `ProfileIconButton` uses it as a `produceState` key, so a fresh
            // instance each recomposition would restart the profile load -- see
            // `ProfileIconButton`'s own KDoc.
            clock = { System.currentTimeMillis() },
            utcOffsetMillis = { deviceUtcOffsetMillis() },
            loadProfile = remember(appContext) { activeProfileLoader(appContext) },
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
