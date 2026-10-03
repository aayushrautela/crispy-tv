package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.crispy.tv.accounts.activeProfileLoader
import com.crispy.tv.catalog.CatalogRoute
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.catalog.catalogViewModelFactory
import com.crispy.tv.app.appGraph
import com.crispy.tv.details.DetailsRatingBadgeLogo
import com.crispy.tv.details.DetailsRoute
import com.crispy.tv.details.HeroTrailerLayer
import com.crispy.tv.details.ReviewProviderBadge
import com.crispy.tv.details.YouTubeExtraVideoDialog
import com.crispy.tv.details.localeDateFormatters
import com.crispy.tv.details.normalizedDetailsItemType
import com.crispy.tv.details.rememberSeedColor
import com.crispy.tv.details.shareOnCrispy
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.home.CalendarEpisodeItem
import com.crispy.tv.home.CalendarRoute
import com.crispy.tv.home.CalendarSeriesItem
import com.crispy.tv.home.HomeRoute
import com.crispy.tv.home.calendarViewModelFactory
import com.crispy.tv.home.homeSelectorViewModelFactory
import com.crispy.tv.home.homeViewModelFactory
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.person.PersonDetailsRoute
import com.crispy.tv.person.formatBirthdayDate
import com.crispy.tv.person.personDetailsViewModelFactory
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider

internal fun NavGraphBuilder.addHomeNavGraph(navController: NavHostController) {
    composable(AppRoutes.HomeRoute) { entry ->
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            // `HomeRoute` takes its two factories and its profile loader as slots, so
            // this is where the `Context` is read now. Nothing here *names*
            // `android.content.Context`: `NavHostController.context` is an Android
            // property reached through the expression, which is exactly the
            // capability this file has by being `androidMain` at all.
            //
            // The `remember` keys are load-bearing, not tidiness.
            // `activeProfileLoader`'s own KDoc records that its return value is a
            // `produceState` key, so a fresh lambda on every recomposition would
            // restart the profile load each time -- the same identity the deleted
            // comment was protecting when it read `LocalContext.current` here.
            val appContext = navController.context.applicationContext
            HomeRoute(
                viewModelFactory = remember(appContext) { homeViewModelFactory(appContext) },
                selectorViewModelFactory = remember(appContext) { homeSelectorViewModelFactory(appContext) },
                loadProfile = remember(appContext) { activeProfileLoader(appContext) },
                // The `< 600` threshold lived in `HomeStreamSelector` while it also read
                // `LocalConfiguration` itself. `LocalConfiguration` is Android-only --
                // `ui-android`'s `AndroidCompositionLocals_androidKt` -- so a
                // `commonMain` composable cannot name it and no static scan can see the
                // dependency at all. **So the value crosses and the decision stays here**,
                // which is the one place in this chain where a platform value is
                // available: this file already reads `navController.context`.
                isCompact = LocalConfiguration.current.screenWidthDp < 600,
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
            // `CalendarRoute` is in `commonMain` and its only platform value was a
            // `Context` read to reach the factory, so the read moved here with the
            // factory. Same shape as the `HomeRoute` block above, and the
            // `remember` is the one `CalendarScreen` used to own, kept so this is a
            // port rather than a behaviour change: without it the service graph is
            // rebuilt on every recomposition.
            val appContext = navController.context.applicationContext
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
                viewModelFactory = remember(appContext) { calendarViewModelFactory(appContext) },
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
        val args = entry.arguments
        val catalogId = args?.getString(AppRoutes.CatalogIdArg).orEmpty()
        val catalogIdentifier =
            com.crispy.tv.domain.home.parseHomeCatalogId(catalogId)
        val section =
            CatalogSectionRef(
                catalogId = catalogId,
                source = catalogIdentifier?.source ?: com.crispy.tv.domain.home.HomeCatalogSource.PERSONAL,
                kind = catalogIdentifier?.kind.orEmpty(),
                presentation = com.crispy.tv.domain.home.HomeCatalogPresentation.RAIL,
                title = args?.getString(AppRoutes.CatalogTitleArg).orEmpty(),
            )
        // `CatalogRoute` takes its view model's factory as a slot, so the `Context`
        // is read here -- the same shape as the `CalendarRoute` block above. The
        // `remember` keys include `section`, which the factory closes over, so a
        // route argument that changes the section rebuilds the factory with it.
        val appContext = navController.context.applicationContext
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            CatalogRoute(
                section = section,
                viewModelFactory = remember(appContext, section) { catalogViewModelFactory(appContext, section) },
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
        val itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg).orEmpty()
        val itemType = entry.arguments?.getString(AppRoutes.HomeDetailsItemTypeArg).orEmpty()
        val highlightEpisodeId = entry.arguments?.getString(AppRoutes.HomeDetailsHighlightEpisodeIdArg)?.ifBlank { null }
        val autoOpenEpisode = entry.arguments?.getBoolean(AppRoutes.HomeDetailsAutoOpenEpisodeArg) == true
        val runtimeEntry = RuntimeDetailsEntry(
            seasonNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeSeasonNumberArg)?.toIntOrNull(),
            episodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg)?.toIntOrNull(),
            absoluteEpisodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg)?.toIntOrNull(),
        ).takeIf {
            it.seasonNumber != null || it.episodeNumber != null || it.absoluteEpisodeNumber != null
        }
        val initialArtworkUrl = entry.arguments?.getString(AppRoutes.HomeDetailsArtworkUrlArg)?.ifBlank { null }
        val sharedElementKey = entry.arguments?.getString(AppRoutes.HomeDetailsSharedElementKeyArg)?.ifBlank { null }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            // `DetailsRoute` is `commonMain` now, so this file is where every platform
            // value it needs is read. `navController.context` is an Android property
            // reached through an expression rather than a named type, which is exactly
            // the capability this file has by being `androidMain` at all -- the same
            // shape as the `appContext` three blocks above, for `HomeRoute`.
            val appContext = navController.context.applicationContext
            // `remember`, because `LocaleDateFormatters` re-reads the device's date-order
            // and 12/24-hour settings when it is built, and the screen reads the two
            // formatters on every recomposition. A fresh instance per recomposition would
            // be a fresh formatter per recomposition.
            val localeFormatters = remember(appContext) { localeDateFormatters(appContext) }
            val configuration = LocalConfiguration.current
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
                // So the normalization is spelled on the right side of the `itemType =`.
                //
                // And the `remember` keys name the *normalized* value, not the raw one, to
                // keep the factory's identity byte-identical to what the route produced.
                // Keying on the raw argument would be harmless -- `viewModel()` caches on
                // `viewModelKey`, not on the factory, so a rebuilt factory is only ever
                // used on a cache miss and then behaves the same -- but "harmless" is a
                // claim, and one line is cheaper than having to argue it later.
                detailsViewModelFactory = remember(appContext, itemId, normalizedItemType, runtimeEntry) {
                    appContext.appGraph().detailsViewModelFactory(
                        itemId = itemId,
                        itemType = normalizedItemType,
                        runtimeEntry = runtimeEntry,
                    )
                },
                playbackSettingsRepository = remember(appContext) {
                    PlaybackSettingsRepositoryProvider.get(appContext)
                },
                // `appContext`, not a composition-local context: `shareOnCrispy`
                // documents that the chooser needs `FLAG_ACTIVITY_NEW_TASK` because a
                // composition-local context is not necessarily the application context.
                // The screen used to build its own inline copy of this call and read the
                // latter, which is what made that copy a latent crash.
                shareText = { text -> shareOnCrispy(context = appContext, text = text) },
                dateFormat = localeFormatters.date,
                timeFormat = localeFormatters.time,
                clock = { System.currentTimeMillis() },
                // Two deliberately separate decisions over one read. This file already
                // reads `LocalConfiguration` for `HomeRoute`'s `isCompact` three blocks
                // above, so the second read costs a line and not a dependency.
                isWideScreen = configuration.screenWidthDp >= 768 &&
                    configuration.screenHeightDp < configuration.screenWidthDp,
                isCompact = configuration.screenWidthDp < 600,
                screenHeightDp = configuration.screenHeightDp,
                youtubeTrailerPlaybackSupported = AppDistribution.current.capabilities
                    .youtubeInHeroPlaybackSupported,
                // `rememberSeedColor`'s own nullability meets the screen's
                // `?: fallbackSeed` here, so the screen never has to know it is nullable.
                imageSeedColor = { imageUrl, fallbackSeed ->
                    rememberSeedColor(imageUrl = imageUrl, fallbackSeed = fallbackSeed).value
                        ?: fallbackSeed
                },
                // The one place the Android trailer surface is named. The layer's own
                // nine parameters are reproduced verbatim, so this is an unpack rather
                // than a reshape: `HeroTrailerLayerArgs` exists only to keep the seam one
                // parameter wide.
                heroTrailerLayer = { args ->
                    HeroTrailerLayer(
                        modifier = args.modifier,
                        trailer = args.trailer,
                        viewportWidthPx = args.viewportWidthPx,
                        viewportHeightPx = args.viewportHeightPx,
                        shouldPlay = args.shouldPlay,
                        isMuted = args.isMuted,
                        onFirstFrameRendered = args.onFirstFrameRendered,
                        onPlaybackState = args.onPlaybackState,
                        onFocusLossPause = args.onFocusLossPause,
                    )
                },
                reviewProviderBadge = { provider -> ReviewProviderBadge(provider = provider) },
                ratingBadgeLogo = { logo -> DetailsRatingBadgeLogo(logo = logo) },
                youTubeExtraVideoDialog = { video, onDismiss ->
                    YouTubeExtraVideoDialog(video = video, onDismiss = onDismiss)
                },
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
        val personId = entry.arguments?.getString(AppRoutes.PersonDetailsPersonIdArg).orEmpty()
        // `NavHostController.context` is an Android property reached through the
        // expression, so nothing here *names* `android.content.Context` -- the
        // capability this file has by being `androidMain` at all.
        val appContext = navController.context.applicationContext
        val profileUrl = entry.arguments?.getString(AppRoutes.PersonDetailsProfileUrlArg).orEmpty()
            .takeIf { it.isNotBlank() }
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            PersonDetailsRoute(
                personId = personId,
                // The same two slots `HomeRoute` and `DetailsRoute` take, for the same
                // reason: `personDetailsViewModelFactory` needs a `Context` and
                // `formatBirthdayDate`'s pattern follows the device's language, so both
                // are resolved here and neither is named inside the route.
                //
                // `personId` is in the `remember` keys because the factory closes over it.
                // The original called it fresh on every recomposition, which was harmless
                // only because `viewModel()` keys on `personId` too -- so a cached factory
                // whose `personId` lagged is the thing the keys are here to prevent.
                viewModelFactory = remember(appContext, personId) {
                    personDetailsViewModelFactory(appContext, personId)
                },
                formatBirthday = ::formatBirthdayDate,
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
