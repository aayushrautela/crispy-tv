package com.crispy.tv.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.crispy.tv.catalog.CatalogRoute
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.details.DetailsRoute
import com.crispy.tv.details.normalizedDetailsItemType
import com.crispy.tv.home.CalendarEpisodeItem
import com.crispy.tv.home.CalendarRoute
import com.crispy.tv.home.HomeRoute
import com.crispy.tv.person.PersonDetailsRoute

internal fun NavGraphBuilder.addHomeNavGraph(
    navController: NavHostController,
    dependencies: HomeNavDependencies,
) {
    composable(AppRoutes.HomeRoute) { entry ->
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            // All three of `HomeRoute`'s crossings arrive in `dependencies`, built by
            // `AppNavHost` -- the last graph still reading a `Context` for itself, and
            // the same shape the other four took before it. The `remember` keys that
            // used to sit here are the caller's now, and `AppNavHost`'s comment says
            // why its `activeProfileLoader` instances are separate rather than shared.
            HomeRoute(
                viewModelFactory = dependencies.homeViewModelFactory,
                selectorViewModelFactory = dependencies.homeSelectorViewModelFactory,
                loadProfile = dependencies.loadProfile,
                // `LocalConfiguration` is Android-only -- it lives in `ui-android`'s
                // `AndroidCompositionLocals_androidKt`, so a `commonMain` composable
                // cannot name it and no static scan can see the dependency at all.
                // **So the value crosses and the decision stays**, as the named
                // threshold it has become: the width arrives, and `isCompactWidth`
                // answers. The details block calls the same rule again, separately.
                isCompact = isCompactWidth(dependencies.screenWidthDp),
                onHeroClick = { hero, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = hero.id,
                            itemType = hero.type,
                            artworkUrl = hero.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                onContinueWatchingOpenDetails = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.titleItemId,
                            itemType = item.type,
                            seasonNumber = item.season,
                            episodeNumber = item.episode,
                            absoluteEpisodeNumber = item.absoluteEpisodeNumber,
                            highlightEpisodeId = item.playbackItemId,
                            autoOpenEpisode = false,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                onThisWeekClick = { item, sharedElementKey ->
                    navController.navigateToCalendarEpisode(
                        item = item,
                        sharedElementKey = sharedElementKey,
                    )
                },
                onThisWeekSeeAllClick = {
                    navController.navigate(AppRoutes.CalendarRoute)
                },
                onCatalogItemClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                // The same route `onCatalogItemClick` opens. `sharedElementKey` is null
                // because the wheel did not come from the row it replaces, so there is no
                // element on screen for the transition to hand over to.
                onRandomPick = { candidate ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = candidate.itemId,
                            itemType = candidate.type,
                            artworkUrl = candidate.artworkUrl,
                            sharedElementKey = null,
                        )
                    )
                },
                onCatalogSeeAllClick = { section ->
                    navController.navigate(AppRoutes.catalogListRoute(section))
                },
                onOpenAccountsProfiles = {
                    navController.navigate(AppRoutes.ProfileMenuRoute) {
                        launchSingleTop = true
                    }
                },
                onOpenPlayer = { identity, resumePositionMs, chosenStreamStableKey, chosenProviderId, chosenStreamHandoffKey ->
                    navController.navigate(
                        AppRoutes.playerRoute(
                            identity = identity,
                            resumePositionMs = resumePositionMs,
                            chosenStreamStableKey = chosenStreamStableKey,
                            chosenProviderId = chosenProviderId,
                            chosenStreamHandoffKey = chosenStreamHandoffKey,
                        )
                    )
                },
                scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
                onScrollToTopConsumed = {
                    entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
                },
            )
        }
    }

    composable(AppRoutes.CalendarRoute) {
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            // `CalendarRoute` is in `commonMain` and its only crossing was the factory
            // it used to reach through a `Context`, so the factory arrives in
            // `dependencies` instead. Same shape as the `HomeRoute` block above; the
            // `remember` that guarded the service graph is the caller's now, and it is
            // still a `remember` -- without one the graph is rebuilt every recomposition.
            CalendarRoute(
                onBack = { navController.popBackStack() },
                onEpisodeClick = { item, sharedElementKey ->
                    navController.navigateToCalendarEpisode(item = item, sharedElementKey = sharedElementKey)
                },
                onSeriesClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                },
                viewModelFactory = dependencies.calendarViewModelFactory,
            )
        }
    }

    composable(
        route = AppRoutes.CatalogListRoutePattern,
        arguments =
            listOf(
                navArgument(AppRoutes.CatalogIdArg) { type = NavType.StringType },
                navArgument(AppRoutes.CatalogTitleArg) { type = NavType.StringType; defaultValue = "" }
            )
    ) { entry ->
        // The first of the three destinations that reads arguments. This block used to
        // bind the bundle to a local before reading it, so an import-shaped guard found
        // nothing here at all -- and it used to hand the read to a crossing in
        // `androidMain`. Both are gone: the mapping is `catalogRouteArguments`, and
        // `entry.savedStateHandle` carries the same argument set.
        val catalogArgs = catalogRouteArguments(entry.savedStateHandle)
        val catalogId = catalogArgs.catalogId.orEmpty()
        val catalogIdentifier =
            com.crispy.tv.domain.home.parseHomeCatalogId(catalogId)
        val section =
            CatalogSectionRef(
                catalogId = catalogId,
                source = catalogIdentifier?.source ?: com.crispy.tv.domain.home.HomeCatalogSource.PERSONAL,
                kind = catalogIdentifier?.kind.orEmpty(),
                presentation = com.crispy.tv.domain.home.HomeCatalogPresentation.RAIL,
                title = catalogArgs.title.orEmpty(),
            )
        // `CatalogRoute` takes its view model's factory as a slot, and that factory
        // closes over `section` -- a value read from *this* route's arguments. So the
        // crossing is a function of the section rather than a product, and the
        // `remember` below keys on `section`, so a route argument that changes it
        // rebuilds the factory with it.
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            CatalogRoute(
                section = section,
                viewModelFactory = remember(dependencies.catalogViewModelFactory, section) {
                    dependencies.catalogViewModelFactory(section)
                },
                onBack = { navController.popBackStack() },
                onItemClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                }
            )
        }
    }

    composable(
        route = AppRoutes.HomeDetailsRoutePattern,
        arguments = listOf(
                navArgument(AppRoutes.HomeDetailsItemTypeArg) { type = NavType.StringType },
                navArgument(AppRoutes.HomeDetailsItemIdArg) { type = NavType.StringType },
                navArgument(AppRoutes.HomeDetailsHighlightEpisodeIdArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsAutoOpenEpisodeArg) { type = NavType.BoolType; defaultValue = false },
                navArgument(AppRoutes.HomeDetailsRuntimeSeasonNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsArtworkUrlArg) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoutes.HomeDetailsSharedElementKeyArg) { type = NavType.StringType; defaultValue = "" },
            )
    ) { entry ->
        // Every line below the reader is the rule that was already here: a missing id is
        // an empty string, a blank optional is absent, and the runtime entry exists only
        // if one of its three numbers was reported. Nothing is decided by the reader --
        // which is now `detailsRouteArguments`, over the handle, and not a crossing.
        // See `HomeRouteArguments` for why the read moved and what it changed.
        val detailsArgs = detailsRouteArguments(entry.savedStateHandle)
        val itemId = detailsArgs.itemId.orEmpty()
        val itemType = detailsArgs.itemType.orEmpty()
        val highlightEpisodeId = detailsArgs.highlightEpisodeId?.ifBlank { null }
        val autoOpenEpisode = detailsArgs.autoOpenEpisode
        val runtimeEntry = runtimeDetailsEntryOrNull(
            seasonNumber = detailsArgs.runtimeSeasonNumber,
            episodeNumber = detailsArgs.runtimeEpisodeNumber,
            absoluteEpisodeNumber = detailsArgs.runtimeAbsoluteEpisodeNumber,
        )
        val initialArtworkUrl = detailsArgs.initialArtworkUrl?.ifBlank { null }
        val sharedElementKey = detailsArgs.sharedElementKey?.ifBlank { null }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            // `DetailsRoute` is `commonMain`, and this block used to be where all
            // thirteen of the values it takes were read. They arrive in
            // `dependencies` now, so this is the fourth block above that names
            // nothing platform-shaped at all.
            //
            // `normalizedDetailsItemType` is the one decision that stays here rather
            // than crossing: it is pure, it was extracted when the route moved, and the
            // factory below is keyed on its answer.
            val normalizedItemType = remember(itemType) { normalizedDetailsItemType(itemType) }
            DetailsRoute(
                itemId = itemId,
                itemType = itemType,
                runtimeEntry = runtimeEntry,
                highlightEpisodeId = highlightEpisodeId,
                autoOpenEpisode = autoOpenEpisode,
                initialArtworkUrl = initialArtworkUrl,
                sharedElementKey = sharedElementKey,
                onBack = { navController.popBackStack() },
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
                onPersonClick = { personId, profileUrl -> navController.navigate(AppRoutes.personDetailsRoute(personId, profileUrl)) },
                onOpenPlayer = { identity, resumePositionMs, chosenStreamStableKey, chosenProviderId, chosenStreamHandoffKey ->
                    navController.navigate(
                        AppRoutes.playerRoute(
                            identity = identity,
                            resumePositionMs = resumePositionMs,
                            chosenStreamStableKey = chosenStreamStableKey,
                            chosenProviderId = chosenProviderId,
                            chosenStreamHandoffKey = chosenStreamHandoffKey,
                        )
                    )
                },
                // The `remember` keys are load-bearing and are NOT the route's own.
                // The route used to build this factory keyed on `itemId,
                // normalizedType, runtimeEntry` as well as the context, because
                // `viewModel()` caches on the factory's identity: a factory rebuilt on a
                // route-argument change drops the ViewModel and reloads the screen. The
                // route's `normalizedType` is its own local, so the same mapping is
                // applied here to keep the two keys in step -- and
                // `normalizedDetailsItemType` is the one function that decides it.
                // `itemType`, NOT `normalizedType`: the original call was positional --
                // `detailsViewModelFactory(itemId, normalizedType, runtimeEntry)` -- and
                // `AppGraph.detailsViewModelFactory` names its second parameter
                // `itemType` while receiving the already-normalized value. Turning that
                // into a named argument with `normalizedType =` does not fail; it quietly
                // passes the RAW argument, and the compiler is the only thing that says so:
                //
                //     No parameter with name 'normalizedType' found.
                //     No value passed for parameter 'itemType'.
                //
                // So the normalization is spelled in the SECOND POSITIONAL SLOT, which is
                // the one the member names `itemType` -- and note that the crossing is a
                // value of function type now, which cannot take named arguments at all.
                //
                // And the `remember` keys name the *normalized* value, not the raw one, to
                // keep the factory's identity byte-identical to what the route produced.
                // Keying on the raw argument would be harmless -- `viewModel()` caches on
                // `viewModelKey`, not on the factory, so a rebuilt factory is only ever
                // used on a cache miss and then behaves the same -- but "harmless" is a
                // claim, and one line is cheaper than having to argue it later.
                //
                // The crossing is a function rather than a product because the factory
                // closes over the item, which is read from this route's arguments.
                detailsViewModelFactory = remember(
                    dependencies.detailsViewModelFactory,
                    itemId,
                    normalizedItemType,
                    runtimeEntry,
                ) {
                    dependencies.detailsViewModelFactory(
                        itemId,
                        normalizedItemType,
                        runtimeEntry,
                    )
                },
                playbackSettingsRepository = dependencies.playbackSettingsRepository,
                // `shareText` is built by the caller with an *application* context.
                // `shareOnCrispy`'s own KDoc records that the chooser needs
                // `FLAG_ACTIVITY_NEW_TASK` because a composition-local context is not
                // necessarily the application context -- which is what made the screen's
                // own inline copy of this call, that read the latter, a latent crash.
                shareText = dependencies.shareText,
                dateFormat = dependencies.dateFormat,
                timeFormat = dependencies.timeFormat,
                clock = dependencies.clock,
                // Two deliberately separate decisions, over one crossing rather than the
                // one read they shared before it. The home block above asks
                // `isCompactWidth` the same question and gets the same answer, but the
                // two screens may move their thresholds independently and both rules now
                // live here, where a test can reach them.
                isWideScreen = isWideScreenLayout(
                    screenWidthDp = dependencies.screenWidthDp,
                    screenHeightDp = dependencies.screenHeightDp,
                ),
                isCompact = isCompactWidth(dependencies.screenWidthDp),
                screenHeightDp = dependencies.screenHeightDp,
                youtubeTrailerPlaybackSupported = dependencies.youtubeTrailerPlaybackSupported,
                imageSeedColor = dependencies.imageSeedColor,
                // The Android surfaces -- the Media3 trailer layer and the three badge
                // composables -- are named by the caller now. `HeroTrailerLayerArgs`
                // exists only to keep that seam one parameter wide, so the unpack that
                // used to sit here is over there, unchanged.
                heroTrailerLayer = dependencies.heroTrailerLayer,
                reviewProviderBadge = dependencies.reviewProviderBadge,
                ratingBadgeLogo = dependencies.ratingBadgeLogo,
                youTubeExtraVideoDialog = dependencies.youTubeExtraVideoDialog,
            )
        }
    }

    composable(
        route = AppRoutes.PersonDetailsRoutePattern,
        arguments = listOf(
            navArgument(AppRoutes.PersonDetailsPersonIdArg) { type = NavType.StringType },
            navArgument(AppRoutes.PersonDetailsProfileUrlArg) {
                type = NavType.StringType
                defaultValue = ""
            }
        )
    ) { entry ->
        // The fifth destination, and the third that reads arguments; see the details
        // block for the read. This one uses
        // `isNotBlank` where the details block uses `ifBlank { null }`, which is not the
        // same shape for the same reason twice -- the profile url is passed to
        // `PersonDetailsRoute` as a fallback for a value the backend may replace, so an
        // empty string is a *worse* answer than no string, while the details block's
        // optionals are simply absent when blank.
        val personArgs = personRouteArguments(entry.savedStateHandle)
        val personId = personArgs.personId.orEmpty()
        val profileUrl = personArgs.profileUrl.orEmpty().takeIf { it.isNotBlank() }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            PersonDetailsRoute(
                personId = personId,
                // The same two crossings `HomeRoute` and `DetailsRoute` take, for the
                // same reason: `personDetailsViewModelFactory` needs a `Context` and
                // `formatBirthdayDate`'s pattern follows the device's language, so both
                // are resolved by the caller and neither is named inside the route.
                //
                // `personId` is in the `remember` keys because the factory closes over it.
                // The original called it fresh on every recomposition, which was harmless
                // only because `viewModel()` keys on `personId` too -- so a cached factory
                // whose `personId` lagged is the thing the keys are here to prevent.
                viewModelFactory = remember(dependencies.personViewModelFactory, personId) {
                    dependencies.personViewModelFactory(personId)
                },
                formatBirthday = dependencies.formatBirthday,
                initialProfileUrl = profileUrl,
                onBack = { navController.popBackStack() },
                onItemClick = { item, sharedElementKey ->
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                }
            )
        }
    }
}

private fun NavHostController.navigateToCalendarEpisode(item: CalendarEpisodeItem, sharedElementKey: String? = null) {
    navigate(
        AppRoutes.homeDetailsRoute(
            itemId = item.titleItemId,
            itemType = item.type,
            seasonNumber = item.season,
            episodeNumber = item.episode,
            absoluteEpisodeNumber = item.absoluteEpisodeNumber,
            highlightEpisodeId = item.highlightEpisodeId.takeIf { !item.isGroup },
            autoOpenEpisode = false,
            artworkUrl = item.artworkUrl,
            sharedElementKey = sharedElementKey,
        )
    )
}
