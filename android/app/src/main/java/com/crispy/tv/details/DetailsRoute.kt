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
import com.crispy.tv.playerui.PlayerLaunchSnapshot
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun DetailsRoute(
    mediaKey: String,
    mediaType: String,
    runtimeEntry: RuntimeDetailsEntry? = null,
    highlightEpisodeId: String? = null,
    autoOpenEpisode: Boolean = false,
    onBack: () -> Unit,
    onItemClick: (CatalogItem) -> Unit = {},
    onPersonClick: (String) -> Unit = {},
    onOpenPlayer: (String, Map<String, String>, String, PlaybackIdentity, String?, String?, PlayerLaunchSnapshot?) -> Unit = { _, _, _, _, _, _, _ -> },
) {
    val appContext = LocalContext.current.applicationContext

    val normalizedType = remember(mediaType) {
        when (mediaType.trim().lowercase(Locale.US)) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            "anime" -> "anime"
            else -> ""
        }
    }
    if (normalizedType.isBlank()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // Important: mediaKey alone is not guaranteed to be collision-free across differing route classes.
    // Keep mediaType in the key to prevent ViewModel reuse across different title shapes.
    val viewModelKey = remember(mediaKey, normalizedType) {
        "$normalizedType:$mediaKey"
    }
    val viewModel: DetailsViewModel =
        viewModel(
            key = viewModelKey,
            factory = remember(appContext, mediaKey, normalizedType, runtimeEntry) {
                appContext.appGraph().detailsViewModelFactory(mediaKey, normalizedType, runtimeEntry)
            }
        )
    val playbackSettingsRepository = remember(appContext) {
        PlaybackSettingsRepositoryProvider.get(appContext)
    }
    val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collectLatest { event ->
            when (event) {
                is DetailsNavigationEvent.OpenPlayer -> {
                    onOpenPlayer(
                        event.playbackUrl,
                        event.playbackHeaders,
                        event.title,
                        event.identity,
                        event.subtitle,
                        event.artworkUrl,
                        event.launchSnapshot,
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
        onBack = onBack,
        onItemClick = onItemClick,
        onPersonClick = onPersonClick,
        onRetry = viewModel::reload,
        onSeasonSelected = viewModel::onSeasonSelected,
        onOpenStreamSelector = viewModel::onOpenStreamSelector,
        onEpisodeClick = viewModel::onOpenStreamSelectorForEpisode,
        onToggleEpisodeWatched = viewModel::toggleEpisodeWatched,
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
