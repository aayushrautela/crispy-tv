package com.crispy.tv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.lookup.buildAddonEpisodeLookupId
import com.crispy.tv.addons.lookup.toMetadataLabMediaTypeOrNull
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.backend.MetadataTitleDetailResponse
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.streams.PluginStreamLoader
import com.crispy.tv.streams.SelectorCoordinator
import kotlinx.coroutines.CoroutineDispatcher
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
    val chosenStreamHandoffKey: String?,
)

/**
 * Chooses a stream for a continue-watching row and hands it to the player.
 *
 * This used to take a single `Context` and build all five of its collaborators in
 * property initialisers, which made it a composition root wearing a class's clothes
 * rather than a class with one dependency. It now takes the collaborators and the
 * wiring lives in `homeSelectorViewModelFactory` (androidMain), the same shape as
 * `SearchViewModel` and `PersonDetailsViewModel`. None of the removed imports were
 * blockers; they left with the wiring.
 *
 * The one thing that could not move with them is the `SelectorCoordinator` itself, and
 * the reason is worth stating because it changes the shape: the coordinator is built
 * with `scope = viewModelScope`, and `viewModelScope` is an extension property *on a
 * ViewModel*, so it does not exist until this object does. The factory therefore hands
 * over the coordinator's **dependencies**, all of which are ports or function slots,
 * and the construction stays here. Handing over the finished coordinator instead would
 * have meant a nullable scope, a `Scope` factory parameter, or moving `viewModelScope`
 * to a `val` read in `init` — none of which is worth it.
 *
 * [stashHandoff] is a function slot rather than an interface because only the
 * producer needs to cross: `PlayerStreamHandoff.stash` is called from here and from
 * `DetailsViewModel`, while `PlayerStreamHandoff.consume` is called only from
 * `PlayerSessionViewModel`. The two halves are used from different source sets, so a
 * shared interface would have been a type with one implementation on either side.
 *
 * [ioDispatcher] exists for the same reason as on the other two ported viewmodels:
 * the load hops to IO around the repository read, and an injected dispatcher is what
 * lets a test drive it deterministically. It is defaulted so the factory needs no
 * extra argument.
 */
internal class HomeSelectorViewModel(
    streamResolver: StreamResolver,
    logger: AppLogger,
    getMetadataItemDetail: suspend (accessToken: String, itemId: String) -> MetadataTitleDetailResponse,
    sessionTokenProvider: suspend () -> String?,
    pluginStreamLoader: PluginStreamLoader?,
    private val userMediaRepository: UserMediaRepository,
    private val stashHandoff: (AddonStream, String) -> String?,
    /**
     * No default, deliberately.
     *
     * `Dispatchers.IO` does not exist in `commonMain`: on Kotlin/Native it is `internal`,
     * so a file that defaults to it does not compile for iOS at all. Defaulting to
     * `Dispatchers.Default` instead would be worse than not compiling -- it would put
     * blocking work on a CPU-sized pool and look correct. The caller is the composition
     * root, where `Dispatchers.IO` does exist and is the right answer.
     */
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    /**
     * Built here rather than injected because its `scope` is [viewModelScope], which
     * needs this `ViewModel` to exist. See the class KDoc.
     */
    val coordinator =
        SelectorCoordinator(
            scope = viewModelScope,
            streamResolver = streamResolver,
            logger = logger,
            getMetadataItemDetail = getMetadataItemDetail,
            sessionTokenProvider = sessionTokenProvider,
            pluginStreamLoader = pluginStreamLoader,
        )

    private val _playStream = MutableSharedFlow<HomeStreamSelection>(extraBufferCapacity = 1)
    val playStream: SharedFlow<HomeStreamSelection> = _playStream.asSharedFlow()

    private var lastLookupId: String = ""

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
        // Stored before the short-circuit below so the handoff stash sees the resolved
        // lookup id on both paths. The original androidMain file set it at this point
        // too; an earlier draft of this port blanked it here and the suite did not
        // notice, because the test stubs `stashHandoff` and so never inspected the id.
        lastLookupId = lookupId

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

        val current = coordinator.state.value
        if (
            current.lookupId == target.lookupId &&
            current.mediaType == target.mediaType &&
            current.providers.isNotEmpty()
        ) {
            coordinator.reshow(headerEpisode)
            return
        }

        val fallbackDetails =
            MediaDetails(
                id = item.id,
                itemId = item.titleItemId,
                imdbId = item.imdbId,
                itemType = item.itemType,
                title = item.title,
                artworkUrl = item.artworkUrl,
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
            withContext(ioDispatcher) {
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
                chosenStreamHandoffKey = stashHandoff(stream, lastLookupId),
            ),
        )
    }

    fun dismiss() = coordinator.dismiss()
}
