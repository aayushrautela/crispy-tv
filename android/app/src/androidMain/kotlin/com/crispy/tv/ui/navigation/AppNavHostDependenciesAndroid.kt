package com.crispy.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.crispy.tv.accounts.accountSettingsViewModelFactory
import com.crispy.tv.accounts.launchUrl
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
import com.crispy.tv.home.randomWheelViewModelFactory
import com.crispy.tv.library.deviceUtcOffsetMillis
import com.crispy.tv.library.libraryViewModelFactory
import com.crispy.tv.person.formatBirthdayDate
import com.crispy.tv.person.personDetailsViewModelFactory
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.search.searchViewModelFactory
import com.crispy.tv.settings.addonsSettingsViewModelFactory

/**
 * The Android composition root for [AppNavHostDependencies].
 *
 * This is the same shape as `addonsSettingsViewModelFactory` and the other extracted
 * factories, with one difference forced by the content: **it is `@Composable`.** The
 * values it builds come from `LocalContext`, `LocalConfiguration` and `AppDistribution`,
 * and almost all of them are `remember`ed, so the producer has to be a composable
 * function rather than a plain one. `AppNavHost` therefore takes it as a
 * `@Composable () -> AppNavHostDependencies` slot and calls it inside its own scope,
 * which is what lets the `remember`s here be *this file's* remembers while the decisions
 * about what to build stay in the shared file.
 *
 * ## Why the `remember`s are here rather than over there
 *
 * They were always here: every graph that took a crossing used to build it inside its own
 * `composable` block, and this file is the block that hoisted them out. Moving the graphs
 * to `commonMain` did not move a single `remember` -- it moved the *question* of what each
 * crossing is, and the answer is a product. So the `remember`s stayed with the code that
 * knows how to build the value, which is what makes the split a boundary rather than a
 * relocation.
 *
 * `rememberNavController`-style hoisting is not available here because the builder is not
 * the composable that owns the navigation state; this one is.
 */
@Composable
internal fun appNavHostDependencies(): AppNavHostDependencies {
    // `LocalContext` rather than coil3's `LocalPlatformContext`: the value is needed
    // only as a `Context` to pass to the Android factories, and reading the Android
    // composition local directly keeps coil3 out of this file entirely. Nothing here
    // renders an image.
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    // The portable half, read off the `Application` rather than built here: one set of
    // services per process, and the factories below must agree about which set that is.
    val graph = remember(appContext) { appContext.appGraph().graph }

    // ---- search -----------------------------------------------------------------
    val searchViewModelFactory = remember(graph) { searchViewModelFactory(graph) }
    val searchLoadProfile = remember(appContext) { graph.activeProfileLoader() }

    // ---- random wheel -----------------------------------------------------------
    val randomWheelViewModelFactory = remember(graph) { randomWheelViewModelFactory(graph) }

    // ---- account ----------------------------------------------------------------
    // `accountLoadProfile` is a SECOND instance rather than a reuse of
    // `searchLoadProfile` above. Both graphs used to build their own loader inside their
    // own screen, and `ProfileIconButton` keys a `produceState` on the loader's identity,
    // so one shared instance would let a recomposition on one tab restart a load another
    // tab is waiting on. Separate remembers, deliberately, in every case below.
    val profileListFactory = remember(appContext) { profileListViewModelFactory(graph) }
    val accountSettingsFactory = remember(appContext) {
        // Opening the browser is a capability, so it crosses as a lambda rather than as the
        // `Context` it needs: the factory never casts it, and a desktop caller supplies
        // `Desktop.browse` in its place.
        accountSettingsViewModelFactory(graph, openUrl = { url -> launchUrl(appContext, url) })
    }
    val accountLoadProfile = remember(appContext) { graph.activeProfileLoader() }

    // ---- library ----------------------------------------------------------------
    // `monthName` and `loadProfile` are remembered lambdas because their identity is
    // load-bearing: `ProfileIconButton` keys a `produceState` on `loadProfile`, and
    // `LibraryRoute` re-reads `monthName`'s receiver per composition. `clock` and
    // `utcOffsetMillis` are remembered lambdas that deliberately re-read on every *call*:
    // the offset is wrong if it is captured at composition time, for the hours either
    // side of a daylight-saving change, and this screen groups rows by month. So the
    // lambdas are stable and the reads inside them are not.
    val libraryViewModelFactory = remember(appContext) { libraryViewModelFactory(appContext) }
    val libraryMonthName = remember(appContext) { localeDateFormatters(appContext).monthName }
    val libraryClock = remember { { System.currentTimeMillis() } }
    val libraryUtcOffsetMillis = remember { { deviceUtcOffsetMillis() } }
    val libraryLoadProfile = remember(appContext) { graph.activeProfileLoader() }
    val libraryLogger = remember(appContext) { AndroidAppLogger(appContext) }

    // ---- discover ---------------------------------------------------------------
    // The FOURTH loader instance. `DiscoverRoute` used to build its own inside its
    // composable body, and that build-in-the-screen shape was the last thing pinning
    // both it and its graph to `androidMain`; the graph never touched a platform type
    // itself.
    val discoverViewModelFactory = remember(appContext) { discoverViewModelFactory(graph) }
    val discoverLoadProfile = remember(appContext) { graph.activeProfileLoader() }

    // ---- home -------------------------------------------------------------------
    // `homeProfileLoader` is the FIFTH instance, on the same grounds as the four above.
    //
    // `homeCatalogViewModelFactory`, `homeDetailsViewModelFactory` and
    // `homePersonViewModelFactory` cross as *functions* rather than as values because
    // each closes over a value read from a route argument, which this level does not have
    // yet. The graph remembers each one on the context alone and calls it with the item,
    // so the graph's `remember` keys stay the ones the route had.
    //
    // `homeFormatters` is a SECOND `localeDateFormatters` instance alongside
    // `libraryMonthName` above: `LocaleDateFormatters` re-reads the device's date-order
    // and 12/24-hour settings when it is built, and the library graph built its own, so
    // sharing one would be a behaviour change in the other direction.
    val homeViewModelFactory = remember(appContext) { homeViewModelFactory(appContext) }
    val homeSelectorViewModelFactory = remember(appContext) { homeSelectorViewModelFactory(appContext) }
    val homeLoadProfile = remember(appContext) { graph.activeProfileLoader() }
    val homeCalendarViewModelFactory = remember(appContext) { calendarViewModelFactory(appContext) }
    val homeCatalogViewModelFactory = remember(appContext) {
        { section: CatalogSectionRef -> catalogViewModelFactory(graph, section) }
    }
    val homeDetailsViewModelFactory = remember(appContext) {
        { itemId: String, itemType: String, runtimeEntry: RuntimeDetailsEntry? ->
            appContext.appGraph().detailsViewModelFactory(
                itemId = itemId,
                itemType = itemType,
                runtimeEntry = runtimeEntry,
            )
        }
    }
    val homePersonViewModelFactory = remember(appContext) {
        { personId: String -> personDetailsViewModelFactory(appContext, personId) }
    }
    val homePlaybackSettingsRepository = remember(appContext) {
        graph.playbackSettingsRepository
    }
    // `appContext`, not the composition-local `context`: `shareOnCrispy` documents that
    // the chooser needs `FLAG_ACTIVITY_NEW_TASK` because a composition-local context is
    // not necessarily the application context. The Details screen used to build its own
    // inline copy of this call and read the latter, which is what made that copy a latent
    // crash.
    val homeShareText = remember(appContext) {
        { text: String -> shareOnCrispy(context = appContext, text = text) }
    }
    val homeFormatters = remember(appContext) { localeDateFormatters(appContext) }
    val homeClock = remember { { System.currentTimeMillis() } }

    // Deliberately NOT remembered. A configuration change has to be visible on the next
    // composition, and remembering this would hold the pre-change size until the process
    // restarted. It is read as two `Int`s rather than as the composition local itself
    // because `LocalConfiguration` has no `commonMain` counterpart by that name -- see
    // `AppNavHostDependencies`' KDoc.
    val configuration = LocalConfiguration.current

    val distribution = AppDistribution.current

    return AppNavHostDependencies(
        searchViewModelFactory = searchViewModelFactory,
        searchLoadProfile = searchLoadProfile,
        randomWheelViewModelFactory = randomWheelViewModelFactory,
        profileListFactory = profileListFactory,
        accountSettingsFactory = accountSettingsFactory,
        accountLoadProfile = accountLoadProfile,
        libraryViewModelFactory = libraryViewModelFactory,
        libraryMonthName = libraryMonthName,
        libraryClock = libraryClock,
        libraryUtcOffsetMillis = libraryUtcOffsetMillis,
        libraryLoadProfile = libraryLoadProfile,
        libraryLogger = libraryLogger,
        discoverViewModelFactory = discoverViewModelFactory,
        discoverLoadProfile = discoverLoadProfile,
        homeViewModelFactory = homeViewModelFactory,
        homeSelectorViewModelFactory = homeSelectorViewModelFactory,
        homeLoadProfile = homeLoadProfile,
        homeCalendarViewModelFactory = homeCalendarViewModelFactory,
        homeCatalogViewModelFactory = homeCatalogViewModelFactory,
        homeDetailsViewModelFactory = homeDetailsViewModelFactory,
        homePersonViewModelFactory = homePersonViewModelFactory,
        homePlaybackSettingsRepository = homePlaybackSettingsRepository,
        homeShareText = homeShareText,
        homeDateFormat = homeFormatters.date,
        homeTimeFormat = homeFormatters.time,
        homeClock = homeClock,
        homeScreenWidthDp = configuration.screenWidthDp,
        homeScreenHeightDp = configuration.screenHeightDp,
        homeYoutubeTrailerPlaybackSupported =
            distribution.capabilities.youtubeInHeroPlaybackSupported,
        // Rebuilt every composition rather than remembered, because that is what the
        // graphs used to do. They capture nothing, so a stable identity would buy nothing
        // and a stale one would be a bug.
        //
        // `rememberSeedColor`'s own nullability meets the screen's `?: fallbackSeed` here,
        // so the screen never has to know it is nullable.
        homeImageSeedColor = { imageUrl, fallbackSeed ->
            rememberSeedColor(imageUrl = imageUrl, fallbackSeed = fallbackSeed).value
                ?: fallbackSeed
        },
        // The one place the Android trailer surface is named. The layer's own nine
        // parameters are reproduced verbatim, so this is an unpack rather than a reshape:
        // `HeroTrailerLayerArgs` exists only to keep the seam one parameter wide.
        homeHeroTrailerLayer = { args ->
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
        homeReviewProviderBadge = { provider -> ReviewProviderBadge(provider = provider) },
        homeRatingBadgeLogo = { logo -> DetailsRatingBadgeLogo(logo = logo) },
        homeYouTubeExtraVideoDialog = { video, onDismiss ->
            YouTubeExtraVideoDialog(video = video, onDismiss = onDismiss)
        },
        // `formatBirthdayDate`'s pattern follows the device's language, so it cannot be
        // replaced by a locale-invariant formatter and stays a slot.
        homeFormatBirthday = ::formatBirthdayDate,
        // `AppDistribution` is read into one local rather than twice, so the object whose
        // `capabilities` getter can `check` is answered once for both halves. This was
        // previously two separate reads (one for the home graph, one for the settings
        // graph) plus a third for the plugins screen, and the reason is the same as the
        // reason the loaders above are separate: the two uses sat in different graphs and
        // one local spanning both would make the home graph's capabilities read depend on
        // where in this composition the settings block happened to be.
        pluginsUiSupported = distribution.capabilities.pluginsUiSupported,
        pluginsSettingsScreen = distribution.pluginsSettingsScreen,
        addonsSettingsViewModelFactory = remember(appContext) { addonsSettingsViewModelFactory(appContext) },
        imageSettingsRepository = remember(appContext) { graph.imageSettingsRepository },
        profileDataCloudSync = remember(appContext) {
            graph.createProfileDataCloudSync()
        },
        // `addPlayerDestination` is an *extension* on `NavGraphBuilder`, so
        // `::addPlayerDestination` does not resolve -- there is no one-argument function
        // here to take a reference to. The builder itself is the argument, which is also
        // the only shape that works: the extension receiver is `NavGraphBuilder` and not
        // `NavHostController`, and `NavHost`'s content lambda is where that receiver
        // exists at all.
        addPlayerDestination = { builder, navController ->
            builder.addPlayerDestination(navController)
        },
    )
}