package com.crispy.tv.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.app.appGraph
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun DetailsRoute(
    itemId: String,
    itemType: String,
    runtimeEntry: RuntimeDetailsEntry? = null,
    highlightEpisodeId: String? = null,
    autoOpenEpisode: Boolean = false,
    initialBackdropUrl: String? = null,
    initialLogoUrl: String? = null,
    sharedElementKey: String? = null,
    onBack: () -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit = { _, _ -> },
    onPersonClick: (personId: String, profileUrl: String?) -> Unit = { _, _ -> },
        onOpenPlayer: (PlaybackIdentity, Long, String?, String?, String?) -> Unit = { _, _, _, _, _ -> },
) {
    val appContext = LocalContext.current.applicationContext

    val normalizedType = remember(itemType) {
        when (itemType.trim().lowercase(Locale.US)) {
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
    val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
        playbackSettings = playbackSettings,
        initialBackdropUrl = initialBackdropUrl,
        initialLogoUrl = initialLogoUrl,
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
        onRetryProvider = viewModel::onRetryProvider,
        onStreamSelected = viewModel::onStreamSelected,
        onToggleWatchlist = viewModel::toggleWatchlist,
        onToggleWatched = viewModel::toggleWatched,
        onSetRating = viewModel::setRating,
        onTrailerMutedChanged = playbackSettingsRepository::setTrailerMuted,
        onAiInsightsClick = viewModel::onAiInsightsClick,
        onDismissAiInsights = viewModel::dismissAiInsightsStory,
    )
}
