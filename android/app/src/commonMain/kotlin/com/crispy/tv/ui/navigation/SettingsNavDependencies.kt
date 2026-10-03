package com.crispy.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.settings.ImageSettingsRepository
import com.crispy.tv.settings.PlaybackSettingsRepository
import com.crispy.tv.sync.ProfileDataCloudSync

/**
 * Everything [addSettingsNavGraph] used to build for itself, arriving from outside.
 *
 * ## Why this is a composition root wearing a graph's clothes
 *
 * [addSettingsNavGraph] is a *registration* function: it declares six destinations and
 * nothing else, and it reads no route argument of any of them. What it did do, for most
 * of this migration, is build its own dependencies — four `androidMain` factories and
 * providers, and three `LocalContext` / `LocalPlatformContext` reads to get an
 * application context for them. That was the whole of its remaining `androidMain`
 * claim, and it is the same shape [addHomeNavGraph] had: a graph is not a component,
 * so "keep the wiring near its only use" has no purchase here.
 *
 * **Every one of its four destination callees was already in `commonMain`** —
 * `SettingsScreen`, `ImageSettingsScreen`, `PlaybackSettingsScreen` and
 * `ProfileManagementRoute`. So this landing had no callee wall left to clear, and the
 * measurements that mattered were the *declared* return types of the four provider
 * functions rather than their locations: `ImageSettingsRepository` and
 * `PlaybackSettingsRepository` are `commonMain` interfaces, `ProfileDataCloudSync` is a
 * `commonMain` class whose one member this graph calls, and the two
 * `ViewModelProvider.Factory`s are the most platform-neutral type in the set. All seven
 * members cross unchanged.
 *
 * ## Why one bundle rather than seven parameters
 *
 * The same argument as [HomeNavDependencies], and the same counter-consideration: the
 * other graphs take three to seven plain parameters and this one could too, so a bundle
 * is a real choice rather than a requirement. It is a bundle because seven of them is
 * where the list stops reading as a list — and because the per-member note is where the
 * lifetime change below gets written down where it will be seen.
 *
 * ## The lifetime change, stated rather than absorbed
 *
 * Each destination used to build its own dependency inside its `composable` block,
 * under `remember(appContext)`. Those `remember`s now live in `AppNavHost`, which means
 * the two factories, the two repositories and the cloud sync are constructed **when the
 * graph is built** rather than on first navigation to the destination.
 *
 * For the two repositories and the cloud sync that is not a change at all: they were
 * already singletons per application context, so eager construction moves *when* the
 * one instance is made and not how many. For the two `ViewModelProvider.Factory`s it is
 * worth being precise about, because the `remember` looks load-bearing and is not:
 * `ViewModel` caches by class, so the key was never carrying the ViewModel's identity —
 * it was only avoiding rebuilding the factory on every recomposition, and `AppNavHost`
 * is a single composition, so it still does that.
 *
 * **What does change is the cost of a screen nobody opens.** Constructing a factory is
 * cheap — each of the four is a `remember` over an existing singleton — but that is a
 * claim about *today's* bodies, and it is the kind of claim a factory body invalidates
 * silently by growing. So the number of things built up front went from "zero, until
 * you navigate" to seven, and this KDoc is where a future reader should look when
 * settings-screen startup cost is the thing being measured.
 *
 * ## What is a product and what is a lambda
 *
 * The rule from the rest of this port applies member by member, and here every member
 * lands on the same side:
 *
 * - [imageSettingsRepository], [playbackSettingsRepository] and [profileDataCloudSync]
 *   are products. Each is used by a `collectAsStateWithLifecycle` and five or six
 *   change lambdas inside a composable, so a fresh instance per recomposition would
 *   restart a settings collect on every frame that changed anything.
 * - [profileListViewModelFactory] and [addonsSettingsViewModelFactory] are products
 *   too, and for the same reason. They are passed to `ProfileManagementRoute` and
 *   [com.crispy.tv.settings.AddonsSettingsRoute], which each hand them to `viewModel`,
 *   which keys nothing — so a fresh factory is merely wasteful, not incorrect. They are
 *   not lambdas because neither factory takes an argument: unlike
 *   [HomeNavDependencies]'s three factory members, which each close over a value read
 *   from *that* graph's route arguments, these two depend on nothing the graph knows.
 *   That is the concrete difference, and it is why hoisting them here needs no
 *   knowledge of any destination.
 * - [pluginsSettingsScreen] is a nullable composable slot and was already one — see the
 *   KDoc on [addSettingsNavGraph] for why the *registration rule* stays in the graph
 *   while the slot crosses.
 */
internal class SettingsNavDependencies(
    val pluginsUiSupported: Boolean,
    val pluginsSettingsScreen: (@Composable (onBack: () -> Unit) -> Unit)?,
    val profileListViewModelFactory: ViewModelProvider.Factory,
    val addonsSettingsViewModelFactory: ViewModelProvider.Factory,
    val imageSettingsRepository: ImageSettingsRepository,
    val playbackSettingsRepository: PlaybackSettingsRepository,
    val profileDataCloudSync: ProfileDataCloudSync,
)