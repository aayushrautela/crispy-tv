package com.crispy.tv.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.home.RandomWheelRoute

/**
 * The random-pick wheel's destination graph.
 *
 * **This is a destination, not an overlay drawn inside home.** The wheel's first version was a
 * `remember`ed boolean in `HomeRoute` painting a clipped panel over a sibling scrim, and the
 * consequences were not local: it could not clear the status bar, it read as a sheet parked at
 * the top edge rather than a page, and it inherited none of the destination transitions the
 * search screen gets. Registering it here with `AppRoutes.RandomWheelRoute` and pointing
 * `roleOf()` at `NavigationRole.Overlay` is what makes it an overlay *in the sense this
 * codebase means* -- the same enum arm search uses, with the same slide-in-from-right transitions.
 *
 * **Both slots are required and neither has a default**, for the reason [addSearchNavGraph]'s
 * KDoc records: a defaulted capability lets a call site silently hide a row the build ships, and
 * both types are declared by [RandomWheelRoute] itself, so the slot types are the consumer's own
 * types rather than a second spelling of them.
 *
 * **This declaration is `public`, not `internal`** -- a `commonMain` declaration cannot be
 * `internal` to `androidMain`.
 */
fun NavGraphBuilder.addRandomWheelNavGraph(
    navController: NavHostController,
    randomWheelViewModelFactory: ViewModelProvider.Factory,
) {
    composable(AppRoutes.RandomWheelRoute) { entry ->
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            RandomWheelRoute(
                viewModelFactory = randomWheelViewModelFactory,
                onPlay = { candidate ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = candidate.itemId,
                            // The raw backend type, not the label the wheel draws: `anime` folds
                            // to "Show" for display, and folding it here too would send the
                            // details route a value it has never seen.
                            itemType = candidate.type,
                            artworkUrl = candidate.artworkUrl,
                            // No element on screen hands anything over -- the wheel is leaving
                            // as details arrives -- so there is no shared element to key on.
                            sharedElementKey = null,
                        ),
                    )
                },
                onClose = { navController.popBackStack() },
            )
        }
    }
}
