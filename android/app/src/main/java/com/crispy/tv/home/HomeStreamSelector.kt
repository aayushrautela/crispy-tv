package com.crispy.tv.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crispy.tv.streams.SelectorCallbacks
import com.crispy.tv.streams.SelectorChrome
import com.crispy.tv.streams.StreamSelectorModal

@Composable
internal fun HomeStreamSelector(viewModel: HomeSelectorViewModel) {
    val state by viewModel.coordinator.state.collectAsStateWithLifecycle()
    val details by viewModel.coordinator.details.collectAsStateWithLifecycle()
    val headerEpisode by viewModel.coordinator.headerEpisode.collectAsStateWithLifecycle()

    val chrome =
        SelectorChrome(
            accentColor = MaterialTheme.colorScheme.primaryContainer,
            onAccentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            showSkeletonChips = true,
            loadingIndicatorSize = 48.dp,
        )

    StreamSelectorModal(
        state = state,
        details = details,
        headerEpisode = headerEpisode,
        chrome = chrome,
        callbacks =
            SelectorCallbacks(
                onDismiss = viewModel::dismiss,
                onProviderSelected = viewModel.coordinator::onProviderSelected,
                onRetryProvider = viewModel.coordinator::onRetryProvider,
                onStreamSelected = viewModel.coordinator::onStreamSelected,
            ),
    )
}
