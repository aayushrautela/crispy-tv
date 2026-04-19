package com.crispy.tv.streams

import androidx.compose.runtime.Immutable
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType

@Immutable
data class StreamProviderUiState(
    val providerId: String,
    val providerName: String,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val streams: List<AddonStream> = emptyList(),
    val attemptedUrl: String? = null,
)

@Immutable
data class StreamSelectorUiState(
    val visible: Boolean = false,
    val mediaType: MetadataLabMediaType? = null,
    val lookupId: String? = null,
    val headerEpisode: MediaVideo? = null,
    val selectedProviderId: String? = null,
    val providers: List<StreamProviderUiState> = emptyList(),
    val isLoading: Boolean = false,
) {
    val totalStreamCount: Int
        get() = providers.sumOf { provider -> provider.streams.size }
}
