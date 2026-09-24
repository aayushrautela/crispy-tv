package com.crispy.tv.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crispy.tv.streams.StreamSelectorSheet

@Composable
internal fun HomeStreamSelector(viewModel: HomeSelectorViewModel) {
    val state by viewModel.coordinator.state.collectAsStateWithLifecycle()
    val details by viewModel.coordinator.details.collectAsStateWithLifecycle()
    val headerEpisode by viewModel.coordinator.headerEpisode.collectAsStateWithLifecycle()

    StreamSelectorSheet(
        visible = state.visible,
        state = state,
        details = details,
        headerEpisode = headerEpisode,
        accentColor = MaterialTheme.colorScheme.primary,
        onAccentColor = MaterialTheme.colorScheme.onPrimary,
        onDismiss = viewModel::dismiss,
        onProviderSelected = viewModel.coordinator::onProviderSelected,
        onStreamSelected = viewModel.coordinator::onStreamSelected,
    )
}
