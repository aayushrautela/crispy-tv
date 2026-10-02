package com.crispy.tv.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.app.appGraph
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DetailsRoute(
    itemId: String,
    itemType: String,
    runtimeEntry: RuntimeDetailsEntry? = null,
    highlightEpisodeId: String? = null,
    autoOpenEpisode: Boolean = false,
    initialArtworkUrl: String? = null,
    sharedElementKey: String? = null,
    onBack: () -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit = { _, _ -> },
    onPersonClick: (personId: String, profileUrl: String?) -> Unit = { _, _ -> },
    onOpenPlayer: (PlaybackIdentity, Long, String?, String?, String?) -> Unit = { _, _, _, _, _ -> },
) {
    val appContext = LocalContext.current.applicationContext

    val normalizedType = remember(itemType) {
        when (itemType.trim().lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "show"
            "anime" -> "anime"
            else -> ""
        }
    }
    if (normalizedType.isBlank()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // Important: itemId alone is not guaranteed to be collision-free across differing route classes.
    // Keep itemType in the key to prevent ViewModel reuse across different title shapes.
    val viewModelKey = remember(itemId, normalizedType) {
        "$normalizedType:$itemId"
    }
    val viewModel: DetailsViewModel =
        viewModel(
            key = viewModelKey,
            factory = remember(appContext, itemId, normalizedType, runtimeEntry) {
                appContext.appGraph().detailsViewModelFactory(itemId, normalizedType, runtimeEntry)
            }
        )
    val playbackSettingsRepository = remember(appContext) {
        PlaybackSettingsRepositoryProvider.get(appContext)
    }

    // `remember`, because the lambdas below are read by `DetailsHeader` on every
    // recomposition and `LocaleDateFormatters` re-reads the device's date-order and
    // 12/24-hour settings when it is built. A fresh instance per recomposition would
    // be a fresh formatter per recomposition.
    //
    // The two cross as *function values* rather than as the class: the class is
    // declared in androidMain, so naming it in a commonMain signature would re-create
    // the pin the landing exists to remove.
    val localeFormatters = remember(appContext) { localeDateFormatters(appContext) }
    val configuration = LocalConfiguration.current
    val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectorState by viewModel.coordinator.state.collectAsStateWithLifecycle()
    val selectorDetails by viewModel.coordinator.details.collectAsStateWithLifecycle()
    val selectorHeaderEpisode by viewModel.coordinator.headerEpisode.collectAsStateWithLifecycle()

    val resolvedKey = sharedElementKey?.takeIf { it.isNotBlank() } ?: itemId

    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collectLatest { event ->
            when (event) {
                is DetailsNavigationEvent.OpenPlayer -> {
                    onOpenPlayer(
                        event.identity,
                        event.resumePositionMs,
                        event.chosenStreamStableKey,
                        event.chosenProviderId,
                        event.chosenStreamHandoffKey,
                    )
                }
            }
        }
    }

    LaunchedEffect(viewModel, highlightEpisodeId, autoOpenEpisode) {
        viewModel.requestEpisodeNavigation(
            highlightEpisodeId = highlightEpisodeId,
            autoOpenEpisode = autoOpenEpisode,
        )
    }

    DetailsScreen(
        uiState = uiState,
        selectorState = selectorState,
        selectorDetails = selectorDetails,
        selectorHeaderEpisode = selectorHeaderEpisode,
        playbackSettings = playbackSettings,
        initialArtworkUrl = initialArtworkUrl,
        sharedElementKey = sharedElementKey,
        onBack = onBack,
        onItemClick = onItemClick,
        onPersonClick = onPersonClick,
        onRetry = viewModel::reload,
        onSeasonSelected = viewModel::onSeasonSelected,
        onOpenStreamSelector = viewModel::onOpenStreamSelector,
        onEpisodeClick = viewModel::onOpenStreamSelectorForEpisode,
        onToggleEpisodeWatched = viewModel::toggleEpisodeWatched,
        onToggleSeasonWatched = viewModel::toggleSeasonWatched,
        onDismissStreamSelector = viewModel::onDismissStreamSelector,
        onProviderSelected = viewModel::onProviderSelected,
        onStreamSelected = viewModel::onStreamSelected,
        onToggleWatchlist = viewModel::toggleWatchlist,
        onToggleWatched = viewModel::toggleWatched,
        onSetLiked = viewModel::setLiked,
        onTrailerMutedChanged = playbackSettingsRepository::setTrailerMuted,
        onAiInsightsClick = viewModel::onAiInsightsClick,
        onDismissAiInsights = viewModel::dismissAiInsightsStory,
        shareText = { text -> shareOnCrispy(context = appContext, text = text) },
        dateFormat = localeFormatters.date,
        timeFormat = localeFormatters.time,
        // `appContext`, not `LocalContext.current`: `shareOnCrispy` documents that
        // the chooser needs `FLAG_ACTIVITY_NEW_TASK` because a composition-local
        // context is not necessarily the application context. The screen used to
        // read the latter here, which is what made its own inline copy of the same
        // call a latent crash.
        clock = { System.currentTimeMillis() },
        isWideScreen = configuration.screenWidthDp >= 768 &&
            configuration.screenHeightDp < configuration.screenWidthDp,
        isCompact = configuration.screenWidthDp < 600,
        youtubeTrailerPlaybackSupported = AppDistribution.current.capabilities
            .youtubeInHeroPlaybackSupported,
        screenHeightDp = configuration.screenHeightDp,
        // Same package, so `rememberSeedColor` needs no import: this is the only
        // place the seed is sampled. The `?.value ?: White` is `rememberSeedColor`'s
        // own nullability meeting the screen's `?: fallbackSeed`.
        imageSeedColor = { imageUrl, fallbackSeed ->
            rememberSeedColor(imageUrl = imageUrl, fallbackSeed = fallbackSeed).value
                ?: fallbackSeed
        },
        // The one place the Android trailer surface is named. The layer's own
        // nine parameters are reproduced verbatim, so this is an unpack rather than
        // a reshape: `HeroTrailerLayerArgs` exists only to keep the seam one
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
