package com.crispy.tv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import coil3.compose.LocalPlatformContext
import com.crispy.tv.accounts.accountSettingsViewModelFactory
import com.crispy.tv.accounts.activeProfileLoader
import com.crispy.tv.accounts.profileListViewModelFactory
import com.crispy.tv.app.appGraph
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.catalog.catalogViewModelFactory
import com.crispy.tv.details.DetailsRatingBadgeLogo
import com.crispy.tv.details.HeroTrailerLayer
import com.crispy.tv.details.ReviewProviderBadge
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.details.YouTubeExtraVideoDialog
import com.crispy.tv.details.localeDateFormatters
import com.crispy.tv.details.rememberSeedColor
import com.crispy.tv.details.shareOnCrispy
import com.crispy.tv.discover.discoverViewModelFactory
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.home.calendarViewModelFactory
import com.crispy.tv.home.homeSelectorViewModelFactory
import com.crispy.tv.home.homeViewModelFactory
import com.crispy.tv.library.deviceUtcOffsetMillis
import com.crispy.tv.library.libraryViewModelFactory
import com.crispy.tv.person.formatBirthdayDate
import com.crispy.tv.person.personDetailsViewModelFactory
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.search.searchViewModelFactory
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider

private const val TopLevelNavigationDurationMillis = 200
private const val TopLevelNavigationOffsetDivisor = 8
private const val OverlayNavigationDurationMillis = 220

private val topLevelRouteIndices = TopLevelDestination.entries.mapIndexed { index, destination -> destination.route to index }.toMap()

private enum class NavigationRole { TopLevel, Overlay, Detail }

private fun roleOf(route: String?): NavigationRole {
    return when {
        topLevelRouteIndices.containsKey(route) -> NavigationRole.TopLevel
        route == AppRoutes.SearchRoute -> NavigationRole.Overlay
        else -> NavigationRole.Detail
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onSignedOut: () -> Unit = {},
) {
    CrispySharedTransitionLayout {
        // `addSearchNavGraph` is in `commonMain` now, so the two values it used
        // to build for itself are built here instead. They are read at *this*
        // level rather than inside `NavHost`'s builder lambda, because that
        // lambda is not a composable scope and a `remember` inside it would not
        // be one either. This is the same shape as `isWideScreen` and
        // `isCompact` crossing as data: the caller already has the value, so the
        // shared file receives the product rather than the factory.
        //
        // **The `remember`s are here for that reason and not merely nearby.** The
        // first attempt put them at the `addSearchNavGraph` call, inside the
        // builder lambda, and the compiler said `@Composable invocations can
        // only happen from the context of a @Composable function` at both lines.
        // So the slot carries the *remembered* value, not a lambda producing it:
        // `remember` belongs to the caller, and the caller is a composable.
        val platformContext = LocalPlatformContext.current
        val appContext = remember(platformContext) { platformContext.applicationContext }
        val searchFactory = remember(appContext) { searchViewModelFactory(appContext) }
        val profileLoader = remember(appContext) { activeProfileLoader(appContext) }

        // `addAccountNavGraph` is the same shape a second time: three `Context`
        // destinations, three factory call sites, one hoisted block. **These are
        // separate `remember`s rather than a reuse of `profileLoader` above, on
        // purpose.** Both graphs call `activeProfileLoader(appContext)`, and each
        // used to build its own; sharing one instance would make a single lambda
        // identity key two graphs' state, so a recomposition in one could cancel or
        // restart the other's load. Three remembered values, not one, is the
        // behaviour-preserving shape.
        val profileListFactory = remember(appContext) { profileListViewModelFactory(appContext) }
        val accountSettingsFactory = remember(appContext) { accountSettingsViewModelFactory(appContext) }
        val accountProfileLoader = remember(appContext) { activeProfileLoader(appContext) }

        // `addLibraryNavGraph` is the same shape a third time, and the two of its
        // seven values that are NOT plain products are the reason this block is
        // longer than the other two. `monthName` and `loadProfile` are remembered
        // lambdas because their identity is load-bearing -- `ProfileIconButton`
        // keys a `produceState` on `loadProfile`, and `LibraryRoute` re-reads
        // `monthName`'s receiver per composition. `clock` and `utcOffsetMillis`
        // are remembered lambdas that deliberately re-read on every *call*: the
        // offset is wrong if it is captured at composition time, for the hours
        // either side of a daylight-saving change, and this screen groups rows by
        // month. So the lambdas are stable and the reads inside them are not.
        //
        // `libraryProfileLoader` is a THIRD `activeProfileLoader(appContext)`
        // instance rather than a reuse of `profileLoader` or
        // `accountProfileLoader` above, on the same grounds: each graph used to
        // build its own, and sharing one would key three graphs' state to a
        // single lambda identity.
        val libraryFactory = remember(appContext) { libraryViewModelFactory(appContext) }
        val libraryMonthName = remember(appContext) { localeDateFormatters(appContext).monthName }
        val libraryClock = remember { { System.currentTimeMillis() } }
        val libraryUtcOffset = remember { { deviceUtcOffsetMillis() } }
        val libraryProfileLoader = remember(appContext) { activeProfileLoader(appContext) }
        val libraryLogger = remember(appContext) { AndroidAppLogger(appContext) }

        // `addDiscoverNavGraph` is the same shape a fourth time, and the smallest of
        // the four: one factory and one profile loader. `discoverProfileLoader` is a
        // FOURTH `activeProfileLoader(appContext)` instance rather than a reuse of the
        // three above, on exactly the grounds they each have their own -- the Discover
        // screen used to build its own loader inside its composable body, and
        // `ProfileIconButton` keys a `produceState` on the loader's identity, so
        // sharing one instance would let a recomposition on the Discover tab
        // restart a load another tab is waiting on. That build-in-the-screen shape
        // was also the last thing pinning `DiscoverRoute` and this graph to
        // `androidMain`; the graph never touched a platform type itself.
        val discoverFactory = remember(appContext) { discoverViewModelFactory(appContext) }
        val discoverProfileLoader = remember(appContext) { activeProfileLoader(appContext) }

        // `addHomeNavGraph` is the same shape a fifth time and by a wide margin the
        // largest: twenty-one crossings, because the Home graph is the one that
        // registers every screen the app can open. They arrive as one
        // `HomeNavDependencies` bundle rather than twenty-one parameters because
        // this graph is a *registration* function, not a component -- nobody builds
        // one per frame -- so the usual "a bundle keeps a constructor call
        // readable" argument is weak here, and the stronger one is that twenty-one
        // positional parameters is a list where a swapped pair still compiles.
        //
        // Every value below is `remember`ed for the same reasons the four blocks
        // above give, and the reasons differ per value:
        //
        //  - `homeProfileLoader` is a FIFTH `activeProfileLoader(appContext)`
        //    instance. Not a reuse of any of the four above: the Home graph built
        //    its own loader inside its own composable, and `ProfileIconButton` keys
        //    a `produceState` on the loader's identity, so one shared instance
        //    would let a recomposition on any tab restart a load another tab is
        //    waiting on. Five remembered values, not one, is the
        //    behaviour-preserving shape -- and the reason is the same every time.
        //  - `homeCatalogFactory`, `homeDetailsFactory` and `homePersonFactory`
        //    cross as *functions* rather than as values because each closes over a
        //    value read from a route argument, which this level does not have yet.
        //    The graph remembers each one on the context alone and calls it with
        //    the item, so the graph's `remember` keys stay the ones the route had.
        //  - `homeFormatters` is remembered because `LocaleDateFormatters` re-reads
        //    the device's date-order and 12/24-hour settings when it is built and
        //    the screen reads both formatters on every recomposition. Note this is a
        //    SECOND instance alongside `libraryMonthName` above; the library graph
        //    built its own too, so one shared formatter would be a behaviour change
        //    in the other direction.
        //  - `homeClock` is a remembered lambda that deliberately re-reads on every
        //    *call*, same as `libraryClock` above: the screen counts down against
        //    it, so the read cannot be captured at composition time.
        //  - The two `LocalConfiguration` reads are deliberately NOT remembered,
        //    exactly as they were not in the graph: a configuration change has to
        //    be visible on the next composition.
        //  - The four `@Composable` slots are rebuilt every composition rather than
        //    remembered, because that is what the graph did. They capture nothing,
        //    so a stable identity would buy nothing and a stale one would be a bug.
        val homeFactory = remember(appContext) { homeViewModelFactory(appContext) }
        val homeSelectorFactory = remember(appContext) { homeSelectorViewModelFactory(appContext) }
        val homeProfileLoader = remember(appContext) { activeProfileLoader(appContext) }
        val homeCalendarFactory = remember(appContext) { calendarViewModelFactory(appContext) }
        val homeCatalogFactory = remember(appContext) {
            { section: CatalogSectionRef -> catalogViewModelFactory(appContext, section) }
        }
        val homeDetailsFactory = remember(appContext) {
            { itemId: String, itemType: String, runtimeEntry: RuntimeDetailsEntry? ->
                appContext.appGraph().detailsViewModelFactory(
                    itemId = itemId,
                    itemType = itemType,
                    runtimeEntry = runtimeEntry,
                )
            }
        }
        val homePersonFactory = remember(appContext) {
            { personId: String -> personDetailsViewModelFactory(appContext, personId) }
        }
        val homePlaybackSettings = remember(appContext) {
            PlaybackSettingsRepositoryProvider.get(appContext)
        }
        // `appContext`, not a composition-local context: `shareOnCrispy` documents
        // that the chooser needs `FLAG_ACTIVITY_NEW_TASK` because a composition-local
        // context is not necessarily the application context. The Details screen used
        // to build its own inline copy of this call and read the latter, which is
        // what made that copy a latent crash.
        val homeShareText = remember(appContext) {
            { text: String -> shareOnCrispy(context = appContext, text = text) }
        }
        val homeFormatters = remember(appContext) { localeDateFormatters(appContext) }
        val homeClock = remember { { System.currentTimeMillis() } }
        val homeConfiguration = LocalConfiguration.current
        // `AppDistribution.current` is read once here rather than inside the graph,
        // and `SettingsNavGraph` already reads it on every `AppNavHost` composition,
        // so hoisting it changes how often a `check` in that object runs and nothing
        // a user can observe.
        val homeYoutubeTrailer = AppDistribution.current.capabilities.youtubeInHeroPlaybackSupported
        // The three route-argument readers, and the last thing pinning the Home graph to
        // `androidMain` for a reason of its own. `NavBackStackEntry` is declared in
        // `commonMain` -- the graph uses it as a type -- but its `arguments` member
        // returns `android.os.Bundle` on every platform, so a shared file cannot read a
        // route argument at all. **That is the whole explanation for the nav graphs: the
        // four that moved register no arguments.**
        //
        // These three closures decide nothing. Every rule about what a blank means, and
        // whether a runtime entry exists at all, is in the graph or in
        // `runtimeDetailsEntryOrNull`, because a rule on this side would be untestable.
        //
        // They are not remembered: nothing keys on them, and each is called once per
        // composition of the graph.
        val homeDetailsArguments: (NavBackStackEntry) -> HomeDetailsRouteArgs = { entry ->
            HomeDetailsRouteArgs(
                itemId = entry.arguments?.getString(AppRoutes.HomeDetailsItemIdArg),
                itemType = entry.arguments?.getString(AppRoutes.HomeDetailsItemTypeArg),
                highlightEpisodeId = entry.arguments?.getString(AppRoutes.HomeDetailsHighlightEpisodeIdArg),
                autoOpenEpisode = entry.arguments?.getBoolean(AppRoutes.HomeDetailsAutoOpenEpisodeArg) == true,
                runtimeSeasonNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeSeasonNumberArg),
                runtimeEpisodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeEpisodeNumberArg),
                runtimeAbsoluteEpisodeNumber = entry.arguments?.getString(AppRoutes.HomeDetailsRuntimeAbsoluteEpisodeArg),
                initialArtworkUrl = entry.arguments?.getString(AppRoutes.HomeDetailsArtworkUrlArg),
                sharedElementKey = entry.arguments?.getString(AppRoutes.HomeDetailsSharedElementKeyArg),
            )
        }
        val homePersonArguments: (NavBackStackEntry) -> HomePersonRouteArgs = { entry ->
            HomePersonRouteArgs(
                personId = entry.arguments?.getString(AppRoutes.PersonDetailsPersonIdArg),
                profileUrl = entry.arguments?.getString(AppRoutes.PersonDetailsProfileUrlArg),
            )
        }
        val homeCatalogArguments: (NavBackStackEntry) -> HomeCatalogRouteArgs = { entry ->
            HomeCatalogRouteArgs(
                catalogId = entry.arguments?.getString(AppRoutes.CatalogIdArg),
                title = entry.arguments?.getString(AppRoutes.CatalogTitleArg),
            )
        }
        val homeNavDependencies = HomeNavDependencies(
            homeViewModelFactory = homeFactory,
            homeSelectorViewModelFactory = homeSelectorFactory,
            loadProfile = homeProfileLoader,
            calendarViewModelFactory = homeCalendarFactory,
            catalogViewModelFactory = homeCatalogFactory,
            detailsViewModelFactory = homeDetailsFactory,
            personViewModelFactory = homePersonFactory,
            detailsArguments = homeDetailsArguments,
            personArguments = homePersonArguments,
            catalogArguments = homeCatalogArguments,
            playbackSettingsRepository = homePlaybackSettings,
            shareText = homeShareText,
            dateFormat = homeFormatters.date,
            timeFormat = homeFormatters.time,
            clock = homeClock,
            screenWidthDp = homeConfiguration.screenWidthDp,
            screenHeightDp = homeConfiguration.screenHeightDp,
            youtubeTrailerPlaybackSupported = homeYoutubeTrailer,
            // `rememberSeedColor`'s own nullability meets the screen's
            // `?: fallbackSeed` here, so the screen never has to know it is nullable.
            imageSeedColor = { imageUrl, fallbackSeed ->
                rememberSeedColor(imageUrl = imageUrl, fallbackSeed = fallbackSeed).value
                    ?: fallbackSeed
            },
            // The one place the Android trailer surface is named. The layer's own
            // nine parameters are reproduced verbatim, so this is an unpack rather
            // than a reshape: `HeroTrailerLayerArgs` exists only to keep the seam
            // one parameter wide.
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
            // `formatBirthdayDate`'s pattern follows the device's language, so it
            // cannot be replaced by a locale-invariant formatter and stays a slot.
            formatBirthday = ::formatBirthdayDate,
        )
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home.route,
            modifier = modifier,
            enterTransition = {
                when {
                    roleOf(targetState.destination.route) == NavigationRole.Overlay -> overlayEnterFromRight()
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(targetState.destination.route) > topLevelRouteIndex(initialState.destination.route)) {
                            tabEnterFromRight()
                        } else {
                            tabEnterFromLeft()
                        }
                    }
                    else -> EnterTransition.None
                }
            },
            exitTransition = {
                when {
                    roleOf(targetState.destination.route) == NavigationRole.Overlay -> ExitTransition.None
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(targetState.destination.route) > topLevelRouteIndex(initialState.destination.route)) {
                            tabExitToLeft()
                        } else {
                            tabExitToRight()
                        }
                    }
                    else -> ExitTransition.None
                }
            },
            popEnterTransition = {
                when {
                    roleOf(initialState.destination.route) == NavigationRole.Overlay -> EnterTransition.None
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(initialState.destination.route) < topLevelRouteIndex(targetState.destination.route)) {
                            tabEnterFromRight()
                        } else {
                            tabEnterFromLeft()
                        }
                    }
                    else -> EnterTransition.None
                }
            },
            popExitTransition = {
                when {
                    roleOf(initialState.destination.route) == NavigationRole.Overlay -> overlayExitToRight()
                    roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                        roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                        if (topLevelRouteIndex(initialState.destination.route) < topLevelRouteIndex(targetState.destination.route)) {
                            tabExitToLeft()
                        } else {
                            tabExitToRight()
                        }
                    }
                    else -> ExitTransition.None
                }
            },
        ) {
            addHomeNavGraph(
                navController = navController,
                dependencies = homeNavDependencies,
            )
            addSearchNavGraph(
                navController = navController,
                searchViewModelFactory = searchFactory,
                loadProfile = profileLoader,
            )
            addDiscoverNavGraph(
                navController = navController,
                viewModelFactory = discoverFactory,
                loadProfile = discoverProfileLoader,
            )
            addLibraryNavGraph(
                navController = navController,
                viewModelFactory = libraryFactory,
                monthName = libraryMonthName,
                clock = libraryClock,
                utcOffsetMillis = libraryUtcOffset,
                loadProfile = libraryProfileLoader,
                logger = libraryLogger,
            )
            addSettingsNavGraph(navController)
            addAccountNavGraph(
                navController = navController,
                onSignedOut = onSignedOut,
                profileListFactory = profileListFactory,
                accountSettingsFactory = accountSettingsFactory,
                loadProfile = accountProfileLoader,
            )
            addPlayerDestination(navController)
        }
    }
}

private fun tabEnterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        initialOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabEnterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        initialOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabExitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        targetOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabExitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        targetOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun overlayEnterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(OverlayNavigationDurationMillis),
        initialOffsetX = { fullWidth -> fullWidth },
    ) + fadeIn(animationSpec = tween(OverlayNavigationDurationMillis))

private fun overlayExitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(OverlayNavigationDurationMillis),
        targetOffsetX = { fullWidth -> fullWidth },
    ) + fadeOut(animationSpec = tween(OverlayNavigationDurationMillis))

private fun topLevelRouteIndex(route: String?): Int {
    return topLevelRouteIndices[route] ?: -1
}
