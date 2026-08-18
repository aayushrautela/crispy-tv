@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.crispy.tv.playerui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.streams.SelectorChrome
import com.crispy.tv.streams.SelectorCallbacks
import com.crispy.tv.streams.StreamSelectorModal
import com.crispy.tv.streams.StreamSelectorUiState

@Composable
internal fun PlayerStreamsSheet(
    visible: Boolean,
    details: MediaDetails?,
    state: StreamSelectorUiState,
    palette: DetailsPaletteColors,
    onDismiss: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (com.crispy.tv.streams.AddonStream) -> Unit,
) {
    if (!visible) return

    val chrome =
        SelectorChrome(
            accentColor = palette.accent,
            onAccentColor = palette.onAccent,
            useCrispyImageModel = true,
            showSkeletonChips = false,
            loadingIndicatorSize = 32.dp,
            scrimColor = Color.Transparent,
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
