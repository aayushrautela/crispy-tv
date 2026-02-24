package com.crispy.tv.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DetailsRoute(
    itemId: String,
    mediaType: String? = null,
    onBack: () -> Unit,
    onItemClick: (String, String?) -> Unit = { _, _ -> },
    onOpenPlayer: (String, String) -> Unit = { _, _ -> },
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: DetailsViewModel =
        viewModel(
            key = itemId,
            factory = remember(appContext, itemId, mediaType) { DetailsViewModel.factory(appContext, itemId, mediaType) }
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
        onItemClick = { id -> onItemClick(id, null) },
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
