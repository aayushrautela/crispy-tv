package com.crispy.tv.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.discover.DiscoverRoute

/**
 * The Discover tab's graph, and the fourth of the eight nav graphs to reach `commonMain`.
 *
 * It is in `commonMain` for the same reason [SearchNavGraph] and [AuthNavGraph] are, and
 * it is worth being precise about what the pin was: **not the import.** This file never
 * imported `Context` and never read a platform context. Its only reason to be `androidMain`
 * was that [DiscoverRoute] was, so it was carrying its callee's pin rather than its own.
 * That is the third time this series has had that shape, and it is the reason a graph's
 * pin list has to include every type it *calls* — a same-package or same-module callee is
 * invisible to any import scan.
 *
 * The two parameters are the `DiscoverRoute` slots, forwarded verbatim. Both are supplied
 * by `AppNavHost`, and the types are the **consumer's own**, not this file's opinion:
 * `ViewModelProvider.Factory` because `viewModel()` wants a product it does not re-key,
 * and a suspend lambda because `ProfileIconButton` keys a `produceState` on its identity.
 * `internal` is unchanged and needs no widening — the androidMain and commonMain source
 * sets of `:app` are one compilation unit, so an `internal` in either is visible from here.
 */
internal fun NavGraphBuilder.addDiscoverNavGraph(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    loadProfile: suspend () -> ActiveProfileInfo?,
) {
    composable(AppRoutes.DiscoverRoute) { entry ->
        PredictivePeelContainer {
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            DiscoverRoute(
                viewModelFactory = viewModelFactory,
                loadProfile = loadProfile,
                scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
                onScrollToTopConsumed = {
                    entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
                },
                onOpenAccountsProfiles = {
                    navController.navigate(AppRoutes.ProfileMenuRoute) {
                        launchSingleTop = true
                    }
                },
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
            )
        }
        }
    }
}
