package com.crispy.tv.details

import androidx.compose.runtime.Immutable
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.domain.optimistic.FieldSync
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.addons.streams.StreamSelectorUiState

@Immutable
data class EpisodeWatchState(
    val progressPercent: Double = 0.0,
    val isWatched: Boolean = false,
)

/** Per-field optimistic sync status surfaced to the UI (spinner / retry). */
@Immutable
data class OptimisticSyncBadge(
    val status: FieldSync = FieldSync.IDLE,
    val errorMessage: String? = null,
)

@Immutable
data class OptimisticSync(
    val watchlist: OptimisticSyncBadge = OptimisticSyncBadge(),
    val watched: OptimisticSyncBadge = OptimisticSyncBadge(),
    val rating: OptimisticSyncBadge = OptimisticSyncBadge(),
    val episodes: Map<String, OptimisticSyncBadge> = emptyMap(),
    val seasons: Map<Int, OptimisticSyncBadge> = emptyMap(),
)

@Immutable
data class DetailsUiState(
    val itemId: String,
    val isLoading: Boolean = true,
    val extrasIsLoading: Boolean = false,
    val ratingsIsLoading: Boolean = false,
    val details: MediaDetails? = null,
    val titleDetail: CrispyBackendClient.MetadataTitleDetailResponse? = null,
    val titleExtras: CrispyBackendClient.MetadataTitleExtrasResponse? = null,
    val titleRatings: CrispyBackendClient.MetadataTitleRatingsResponse? = null,
    val statusMessage: String = "",
    val extrasStatusMessage: String = "",
    val ratingsStatusMessage: String = "",
    val aiIsLoading: Boolean = false,
    val aiInsights: AiInsightsResult? = null,
    val aiStoryVisible: Boolean = false,
    val isWatched: Boolean = false,
    val isShowFullyWatched: Boolean = false,
    val isInWatchlist: Boolean = false,
    val isRated: Boolean = false,
    val userRating: Int? = null,
    val optimisticSync: OptimisticSync = OptimisticSync(),
    val watchCta: WatchCta = WatchCta(),
    val continueVideoId: String? = null,
    val selectedSeason: Int? = null,
    val seasons: List<Int> = emptyList(),
    val seasonItemIds: Map<Int, String> = emptyMap(),
    val seasonWatchStates: Map<Int, Boolean> = emptyMap(),
    val highlightedEpisodeId: String? = null,
    val seasonEpisodes: List<MediaVideo> = emptyList(),
    val episodeWatchStates: Map<String, EpisodeWatchState> = emptyMap(),
    val episodesIsLoading: Boolean = false,
    val episodesStatusMessage: String = "",
    val streamSelector: StreamSelectorUiState = StreamSelectorUiState(),
) {
    val selectedSeasonOrFirst: Int?
        get() = selectedSeason ?: seasons.firstOrNull()
}

sealed interface DetailsNavigationEvent {
    data class OpenPlayer(
        val identity: PlaybackIdentity,
        val resumePositionMs: Long = 0L,
        val chosenStreamStableKey: String? = null,
        val chosenProviderId: String? = null,
        val chosenStreamHandoffKey: String? = null,
    ) : DetailsNavigationEvent
}
