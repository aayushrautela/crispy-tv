package com.crispy.tv.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DetailsRoute(
    itemId: String,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit = {}
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: DetailsViewModel =
        viewModel(
            key = itemId,
            factory = remember(appContext, itemId) { DetailsViewModel.factory(appContext, itemId) }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DetailsScreen(
        uiState = uiState,
        onBack = onBack,
        onItemClick = onItemClick,
        onRetry = viewModel::reload,
        onSeasonSelected = viewModel::onSeasonSelected,
        onToggleWatchlist = viewModel::toggleWatchlist,
        onToggleWatched = viewModel::toggleWatched,
        onSetRating = viewModel::setRating
    )
}
