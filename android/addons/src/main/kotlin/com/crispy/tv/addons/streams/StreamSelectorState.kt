package com.crispy.tv.addons.streams

import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale

data class StreamProviderUiState(
    val providerId: String,
    val providerName: String,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val streams: List<AddonStream> = emptyList(),
    val attemptedUrl: String? = null,
)

data class StreamSelectorUiState(
    val visible: Boolean = false,
    val mediaType: MetadataLabMediaType? = null,
    val lookupId: String? = null,
    val headerEpisode: MediaVideo? = null,
    val selectedProviderId: String? = null,
    val providers: List<StreamProviderUiState> = emptyList(),
    val pluginsPending: Boolean = false,
) {
    val totalStreamCount: Int
        get() = providers.sumOf { provider -> provider.streams.size }

    /** True while any addon is still resolving or plugin results are still arriving. */
    val isFetching: Boolean
        get() = pluginsPending || providers.any { provider -> provider.isLoading }
}

/** Providers worth rendering: finished providers must have streams or an error. */
fun List<StreamProviderUiState>.visibleProviders(): List<StreamProviderUiState> =
    filter { it.streams.isNotEmpty() || it.errorMessage != null }

/**
 * Merges expected providers in as loading placeholders, keeping already-arrived
 * results untouched. Later results replace their placeholder via applyProviderResult.
 */
fun List<StreamProviderUiState>.seedProviders(descriptors: List<StreamProviderDescriptor>): List<StreamProviderUiState> {
    if (descriptors.isEmpty()) return this
    val knownIds = map { provider -> provider.providerId.lowercase(Locale.US) }.toSet()
    val seeded =
        descriptors
            .filter { descriptor -> !knownIds.contains(descriptor.providerId.lowercase(Locale.US)) }
            .map { descriptor ->
                StreamProviderUiState(
                    providerId = descriptor.providerId,
                    providerName = descriptor.providerName,
                    isLoading = true,
                )
            }
    return this + seeded
}
