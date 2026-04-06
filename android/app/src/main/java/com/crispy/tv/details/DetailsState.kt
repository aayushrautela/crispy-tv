package com.crispy.tv.details

import androidx.compose.runtime.Immutable
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.playerui.PlayerLaunchSnapshot
import com.crispy.tv.streams.AddonStream

@Immutable
data class EpisodeWatchState(
    val progressPercent: Double = 0.0,
    val isWatched: Boolean = false,
)

@Immutable
data class DetailsUiState(
    val mediaKey: String,
    val isLoading: Boolean = true,
    val details: MediaDetails? = null,
    val titleDetail: CrispyBackendClient.MetadataTitleDetailResponse? = null,
    val titleReviews: CrispyBackendClient.MetadataTitleReviewsResponse? = null,
    val titleContent: CrispyBackendClient.MetadataTitleContentResponse? = null,
    val statusMessage: String = "",
    val aiIsLoading: Boolean = false,
    val aiInsights: AiInsightsResult? = null,
    val aiStoryVisible: Boolean = false,
    val isWatched: Boolean = false,
    val isInWatchlist: Boolean = false,
    val isRated: Boolean = false,
    val userRating: Int? = null,
    val isMutating: Boolean = false,
    val watchCta: WatchCta = WatchCta(),
    val continueVideoId: String? = null,
    val selectedSeason: Int? = null,
    val seasons: List<Int> = emptyList(),
    val seasonEpisodes: List<MediaVideo> = emptyList(),
    val episodeWatchStates: Map<String, EpisodeWatchState> = emptyMap(),
    val episodesIsLoading: Boolean = false,
    val episodesStatusMessage: String = "",
    val streamSelector: StreamSelectorUiState = StreamSelectorUiState(),
) {
    val selectedSeasonOrFirst: Int?
        get() = selectedSeason ?: seasons.firstOrNull()
}

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

sealed interface DetailsNavigationEvent {
    data class OpenPlayer(
        val playbackUrl: String,
        val playbackHeaders: Map<String, String> = emptyMap(),
        val title: String,
        val identity: PlaybackIdentity,
        val subtitle: String?,
        val artworkUrl: String?,
        val launchSnapshot: PlayerLaunchSnapshot?,
    ) : DetailsNavigationEvent
}
