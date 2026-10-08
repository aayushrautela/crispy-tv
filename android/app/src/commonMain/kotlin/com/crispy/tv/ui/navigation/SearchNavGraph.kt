package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.search.SearchRoute

/**
 * The search destination graph.
 *
 * **This file is in `commonMain` because its two `Context`-derived values now
 * arrive from outside**, and the KDoc that said otherwise was written when this
 * was an `androidMain` file:
 *
 *  - It used to read `coil3.compose.LocalPlatformContext.current` and
 *    `remember { it.applicationContext }` itself, and then built both factories
 *    inline at the two call sites. `coil3`'s `commonMain` *does* declare
 *    `LocalPlatformContext` — so the import was never the pin. The pin was
 *    `.applicationContext` and the two `androidMain` factory functions, and both
 *    of those are decisions the platform edge has to make anyway.
 *  - So [AppNavHost] — which is `androidMain`, and which had **no `Context` of
 *    its own** until this change — now reads the platform context in *its* body,
 *    before [NavHost]'s builder lambda, because that lambda is not a composable
 *    scope and a `remember` inside it would not be one either.
 *
 * **The first attempt got the placement half right and the type half wrong, and
 * the compiler said so in three lines.** `remember` at the *call* site inside
 * the builder lambda produced `@Composable invocations can only happen from the
 * context of a @Composable function` twice, and the slot typed
 * `() -> ViewModelProvider.Factory` produced `Argument type mismatch: actual
 * type is 'ViewModelProvider.Factory'`. Both errors are the same sentence: **the
 * caller `remember`s, and the shared file receives the remembered value.** So
 * the slot is `ViewModelProvider.Factory`, exactly what [SearchRoute] declares —
 * which is why the KDoc's claim that the slot types are the consumer's own types
 * rather than a second spelling of them was *false* until this shape was used.
 *
 * **Both slots are required and neither has a default.** A defaulted capability
 * lets a call site silently hide a row the build ships, and both types are
 * already declared by [SearchRoute] in `commonMain`
 * (`viewModelFactory: ViewModelProvider.Factory`,
 * `loadProfile: suspend () -> ActiveProfileInfo?`), so the slot types are the
 * consumer's own types rather than a second spelling of them. The Android
 * implementations match verbatim: `activeProfileLoader(context)` is declared as
 * `suspend () -> ActiveProfileInfo?`.
 *
 * **This declaration is `public`, not `internal`** — a `commonMain` declaration
 * cannot be `internal` to `androidMain`, and widening it is a real consequence
 * of the move rather than a stylistic choice.
 */
fun NavGraphBuilder.addSearchNavGraph(
    navController: NavHostController,
    searchViewModelFactory: ViewModelProvider.Factory,
    loadProfile: suspend () -> ActiveProfileInfo?,
) {
    composable(AppRoutes.SearchRoute) { entry ->
        PredictivePeelContainer {
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
                viewModelFactory = searchViewModelFactory,
                loadProfile = loadProfile,
            )
        }
        }
    }
}
