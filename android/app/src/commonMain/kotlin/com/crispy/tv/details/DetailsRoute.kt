package com.crispy.tv.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.backend.MetadataVideoView
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.settings.PlaybackSettingsRepository
import kotlinx.coroutines.flow.collectLatest

/**
 * The route argument is user data, so it is trimmed and case-folded before the four-way
 * mapping. The `else -> ""` sentinel is load-bearing: the route reads it as
 * `isBlank()` and navigates back, which is how an unknown `itemType` leaves the screen
 * instead of loading a ViewModel keyed on a shape nothing recognises.
 *
 * Named in production rather than left inline because `remember(itemType)` wrapped a
 * five-line `when` inside a composable body, so no test in any source set could reach it.
 */
internal fun normalizedDetailsItemType(itemType: String): String =
    when (itemType.trim().lowercase()) {
        "movie" -> "movie"
        "series", "show", "tv" -> "show"
        "anime" -> "anime"
        else -> ""
    }

/**
 * `internal`, which it was not in `androidMain` and now must be.
 *
 * The move exposed `HeroTrailerLayerArgs` -- an `internal` class -- through this
 * signature, and Kotlin refuses a `public` function with an `internal` type in it:
 *
 *     'public' function exposes its 'internal' parameter type argument
 *     'HeroTrailerLayerArgs'
 *
 * Narrowing is the right direction rather than the alternative, which is widening the
 * class to `public`. **Both of this file's siblings are already `internal`** --
 * `DetailsScreen` in this package and `HomeRoute` in `com.crispy.tv.home`, whose caller
 * `HomeNavGraph` is `androidMain` in the same module and imports it. So `internal` costs
 * nothing (there is exactly one caller, in this module) and makes the three consistent;
 * `public` on the class would have widened a type no consumer outside `:app` can name.
 */
@Composable
internal fun DetailsRoute(
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
    /**
     * The factory used to be built here from `LocalContext.current.applicationContext`,
     * via `appGraph().detailsViewModelFactory(...)`. It crosses as the product because
     * `viewModel()` keys its cache on the factory's identity, so a caller that rebuilt it
     * on a route-argument change would silently drop the ViewModel and reload.
     *
     * The caller keeps the extra `remember` keys -- `itemId`, `normalizedType` and
     * `runtimeEntry` alongside the context -- because those keys are what make the
     * identity stable across a recomposition that does not change the route.
     *
     * `detailsViewModelFactory` itself stays in `androidMain`: a `Context` used for
     * *wiring* belongs in the factory, so it is the call sites that move, not the factory.
     */
    detailsViewModelFactory: ViewModelProvider.Factory,
    /**
     * The product, not a lambda, because the route only ever hands it to the screen,
     * which uses it as a bound reference for `onTrailerMutedChanged`.
     */
    playbackSettingsRepository: PlaybackSettingsRepository,
    /**
     * Twelve crossings this file used to *supply* to `DetailsScreen`, now forwarded.
     *
     * They are the same twelve the screen already takes, so this signature is now the
     * screen's signature a second time. That is a real cost and it is the same shape as
     * `DetailsScreen`'s 43 parameters and `PlayerOverlay`'s 21 callbacks: a callback graph
     * wearing a parameter list. The request-object alternative is deliberately NOT taken
     * here -- it is product-shaped, it changes call sites in two modules, and the two
     * instances already recorded in this repo (`PlayerActions`, `HeroTrailerLayerArgs`)
     * reached opposite conclusions for opposite reasons.
     *
     * Every one has **no default**, so a call site cannot forget one.
     */
    shareText: (String) -> Unit,
    dateFormat: (Long) -> String,
    timeFormat: (Long) -> String,
    clock: () -> Long,
    isWideScreen: Boolean,
    isCompact: Boolean,
    screenHeightDp: Int,
    youtubeTrailerPlaybackSupported: Boolean,
    imageSeedColor: @Composable (imageUrl: String?, fallbackSeed: Color) -> Color?,
    heroTrailerLayer: @Composable (HeroTrailerLayerArgs) -> Unit,
    reviewProviderBadge: @Composable (String) -> Unit,
    ratingBadgeLogo: @Composable (RatingBadgeLogo) -> Unit,
    youTubeExtraVideoDialog: @Composable (MetadataVideoView?, () -> Unit) -> Unit,
) {
    val normalizedType = remember(itemType) { normalizedDetailsItemType(itemType) }
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
            factory = detailsViewModelFactory
        )

    val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectorState by viewModel.coordinator.state.collectAsStateWithLifecycle()
    val selectorDetails by viewModel.coordinator.details.collectAsStateWithLifecycle()
    val selectorHeaderEpisode by viewModel.coordinator.headerEpisode.collectAsStateWithLifecycle()

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
        shareText = shareText,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        clock = clock,
        isWideScreen = isWideScreen,
        isCompact = isCompact,
        youtubeTrailerPlaybackSupported = youtubeTrailerPlaybackSupported,
        screenHeightDp = screenHeightDp,
        imageSeedColor = imageSeedColor,
        heroTrailerLayer = heroTrailerLayer,
        reviewProviderBadge = reviewProviderBadge,
        ratingBadgeLogo = ratingBadgeLogo,
        youTubeExtraVideoDialog = youTubeExtraVideoDialog,
    )
}
