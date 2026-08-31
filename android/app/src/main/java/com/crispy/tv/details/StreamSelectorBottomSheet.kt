@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.crispy.tv.details

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.streams.SelectorChrome
import com.crispy.tv.streams.SelectorCallbacks
import com.crispy.tv.streams.StreamSelectorModal
import com.crispy.tv.addons.streams.StreamSelectorUiState

@Composable
internal fun StreamSelectorBottomSheet(
    details: MediaDetails?,
    state: StreamSelectorUiState,
    palette: DetailsPaletteColors,
    onDismiss: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (com.crispy.tv.addons.streams.AddonStream) -> Unit,
) {
    if (!state.visible) return

    val chrome =
        SelectorChrome(
            accentColor = palette.accent,
            onAccentColor = palette.onAccent,
            useCrispyImageModel = false,
            showSkeletonChips = true,
            loadingIndicatorSize = 48.dp,
        )

    StreamSelectorModal(
        state = state,
        details = details,
        headerEpisode = state.headerEpisode,
        chrome = chrome,
        callbacks =
            SelectorCallbacks(
                onDismiss = onDismiss,
                onProviderSelected = onProviderSelected,
                onRetryProvider = onRetryProvider,
                onStreamSelected = onStreamSelected,
            ),
    )
}
