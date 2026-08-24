package com.crispy.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.app.appGraph
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.addons.lookup.buildAddonEpisodeLookupId
import com.crispy.tv.addons.lookup.toMetadataLabMediaTypeOrNull
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.streams.SelectorCoordinator
import com.crispy.tv.streams.StreamResolverProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeStreamSelection(
    val identity: PlaybackIdentity,
    val resumePositionMs: Long,
    val chosenStreamStableKey: String?,
    val chosenProviderId: String?,
)

internal class HomeSelectorViewModel(
    private val appContext: Context,
) : ViewModel() {
    private val backendClient: CrispyBackendClient = BackendServicesProvider.backendClient(appContext)
    private val supabase = SupabaseServicesProvider.accountClient(appContext)
    private val streamResolver = StreamResolverProvider.get(appContext)
    private val userMediaRepository: UserMediaRepository = appContext.appGraph().userMediaRepository

    val coordinator =
        SelectorCoordinator(
            scope = viewModelScope,
            streamResolver = streamResolver,
            getMetadataItemDetail = { token, itemId ->
                backendClient.getMetadataItemDetail(accessToken = token, itemId = itemId)
            },
            sessionTokenProvider = { supabase.ensureValidSession()?.accessToken },
        )

    private val _playStream = MutableSharedFlow<HomeStreamSelection>(extraBufferCapacity = 1)
    val playStream: SharedFlow<HomeStreamSelection> = _playStream.asSharedFlow()

    fun openFor(item: CanonicalContinueWatchingItem) {
        val mediaType = item.type.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        val lookupId =
            when (mediaType) {
                MetadataLabMediaType.MOVIE -> item.imdbId ?: item.titleItemId
                else ->
                    buildAddonEpisodeLookupId(item.imdbId, item.season, item.episode)
                        ?: item.titleItemId
            }
        val target = StreamLookupTarget(mediaType = mediaType, lookupId = lookupId)

        val headerEpisode =
            MediaVideo(
                id = item.id,
                title = item.episodeTitle ?: "",
                season = item.season,
                episode = item.episode,
                released = null,
                overview = item.subtitle,
                thumbnailUrl = item.stillUrl,
                lookupId = lookupId,
                absoluteEpisodeNumber = item.absoluteEpisodeNumber,
            )

        val fallbackDetails =
            MediaDetails(
                id = item.id,
                itemId = item.titleItemId,
                imdbId = item.imdbId,
                itemType = item.itemType,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                logoUrl = item.logoUrl,
                year = null,
                runtime = null,
                certification = null,
                rating = null,
                description = item.subtitle,
                addonId = item.addonId,
                seasonNumber = item.season,
                episodeNumber = item.episode,
                absoluteEpisodeNumber = item.absoluteEpisodeNumber,
            )

        coordinator.open(
            target = target,
            headerEpisode = headerEpisode,
            fallbackDetails = fallbackDetails,
            itemIdForMetadata = item.id,
            onStreamSelected = { stream -> viewModelScope.launch { emitPlay(item, mediaType, stream) } },
        )
    }

    private suspend fun emitPlay(
        item: CanonicalContinueWatchingItem,
        mediaType: MetadataLabMediaType,
        stream: AddonStream,
    ) {
        val identity =
            PlaybackIdentity(
                itemId = item.id,
                seriesItemId = item.titleItemId,
                imdbId = item.imdbId,
                contentType = mediaType,
                season = item.season,
                episode = item.episode,
                title = item.title,
                showTitle = if (mediaType != MetadataLabMediaType.MOVIE) item.title else null,
                absoluteEpisodeNumber = item.absoluteEpisodeNumber,
            )
        val resumePositionMs =
            withContext(Dispatchers.IO) {
                userMediaRepository
                    .getLocalWatchProgress(identity)
                    ?.takeIf { it.progressPercent in 1.0..95.0 }
                    ?.let { (it.currentTimeSeconds * 1000.0).toLong() }
                    ?: 0L
            }
        _playStream.tryEmit(
            HomeStreamSelection(
                identity = identity,
                resumePositionMs = resumePositionMs,
                chosenStreamStableKey = stream.stableKey,
                chosenProviderId = stream.providerId,
            ),
        )
    }

    fun dismiss() = coordinator.dismiss()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeSelectorViewModel(appContext) as T
            }
        }
    }
}
