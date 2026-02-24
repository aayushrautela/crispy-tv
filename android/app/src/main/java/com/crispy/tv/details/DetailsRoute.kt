package com.crispy.tv.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun DetailsRoute(
    itemId: String,
    mediaType: String,
    onBack: () -> Unit,
    onItemClick: (String, String) -> Unit = { _, _ -> },
    onOpenPlayer: (String, String) -> Unit = { _, _ -> },
) {
    val appContext = LocalContext.current.applicationContext

    val normalizedType = remember(mediaType) {
        when (mediaType.trim().lowercase(Locale.US)) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> ""
        }
    }
    if (normalizedType.isBlank()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // Important: itemId alone is not globally unique (e.g. TMDB movie vs TV IDs can collide).
    // Keep mediaType in the key to prevent ViewModel reuse across different titles.
    val viewModelKey = remember(itemId, normalizedType) {
        "$normalizedType:$itemId"
    }
    val viewModel: DetailsViewModel =
        viewModel(
            key = viewModelKey,
            factory = remember(appContext, itemId, normalizedType) { DetailsViewModel.factory(appContext, itemId, normalizedType) }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collectLatest { event ->
            when (event) {
                is DetailsNavigationEvent.OpenPlayer -> {
                    onOpenPlayer(event.playbackUrl, event.title)
                }
            }
        }
    }

    DetailsScreen(
        uiState = uiState,
        onBack = onBack,
        onItemClick = onItemClick,
        onRetry = viewModel::reload,
        onSeasonSelected = viewModel::onSeasonSelected,
        onOpenStreamSelector = viewModel::onOpenStreamSelector,
        onEpisodeClick = viewModel::onOpenStreamSelectorForEpisode,
        onDismissStreamSelector = viewModel::onDismissStreamSelector,
        onProviderSelected = viewModel::onProviderSelected,
        onRetryProvider = viewModel::onRetryProvider,
        onStreamSelected = viewModel::onStreamSelected,
        onToggleWatchlist = viewModel::toggleWatchlist,
        onToggleWatched = viewModel::toggleWatched,
        onSetRating = viewModel::setRating,
        onAiInsightsClick = viewModel::onAiInsightsClick,
        onDismissAiInsights = viewModel::dismissAiInsightsStory,
    )
}
