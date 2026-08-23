package com.crispy.tv.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.FieldSync
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.UserStateSnapshot
import com.crispy.tv.domain.optimistic.WatchlistMutation
import com.crispy.tv.domain.optimistic.deriveUserState
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.toMediaVideo
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.optimistic.toContentType
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.playerui.PlayerStreamHandoff
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.StreamSelectorUiState
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.playback.StreamLookupTarget
import com.crispy.tv.playback.findEpisodeForLookupId
import com.crispy.tv.playback.resolveStreamLookupTarget
import com.crispy.tv.playback.parseLookupId
import com.crispy.tv.playback.matchesTarget
import com.crispy.tv.playback.toUiState
import com.crispy.tv.playback.applyProviderResult
import com.crispy.tv.playback.finalizeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DetailsViewModel internal constructor(
    private val itemId: String,
    private val itemType: String,
    private val runtimeEntry: RuntimeDetailsEntry?,
    private val detailsUseCases: DetailsUseCases,
    private val outbox: UserMutationOutbox,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState(itemId = itemId))
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<DetailsNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<DetailsNavigationEvent> = _navigationEvents.asSharedFlow()

    private val requestedMediaType: MetadataLabMediaType =
        checkNotNull(itemType.toMetadataLabMediaTypeOrNull()) { "Unsupported itemType: $itemType" }

    private var aiJob: Job? = null
    private var streamLoadJob: Job? = null
    private var streamSelectorSession = 0L
    private var episodesJob: Job? = null
    private var reloadJob: Job? = null
    private var extrasJob: Job? = null
    private var ratingsJob: Job? = null
    private var allSeasonsWatchJob: Job? = null
    private val seasonEpisodesCache = mutableMapOf<Int, List<MediaVideo>>()
    private var pendingEpisodeNavigation: PendingEpisodeNavigation? = null
    @Volatile
    private var reloadGeneration: Long = 0L

    /** Last known server truth for this title; optimistic pending mutations are merged on top. */
    private var serverSnapshot: UserStateSnapshot = UserStateSnapshot()

    init {
        viewModelScope.launch {
            var previous = outbox.mutationsForItem(itemId)
            outbox.observeItem(itemId).collect { mutations ->
                val currentIds = mutations.map { it.id }.toSet()
                // A mutation whose id vanished did so because it flushed
                // successfully (the outbox removes on success). The server now
                // agrees with its desired value, so write that straight into the
                // local source of truth — no re-fetch. Failures stay in the list
                // (Failed/Conflict) and are excluded here, so they keep showing
                // server truth plus an error badge until retried.
                val completed = previous.filter { it.id !in currentIds }
                    .filter { it.status == MutationStatus.Pending || it.status == MutationStatus.Inflight }
                previous = mutations
                if (completed.isNotEmpty()) {
                    applyCompleted(completed)
                }
                recomputeDerived()
            }
        }
        reload()
    }

    /**
     * Re-derive display state from the server snapshot plus any in-flight
     * mutations. This is what makes the toggle buttons feel instant: the UI
     * reflects local intent before the network call resolves, and reverts to
     * server truth (with an error badge) only if a write fails.
     */
    private fun recomputeDerived() {
        val mutations = outbox.mutationsForItem(itemId)
        _uiState.update { state ->
            val episodeServer = state.episodeWatchStates.mapValues { it.value.isWatched }
            val combined = serverSnapshot.copy(
                episodeWatched = episodeServer,
                seasonWatched = state.seasonWatchStates,
            )
            val derived = deriveUserState(combined, mutations)
            val newEpisodeWatchStates = state.episodeWatchStates.mapValues { (key, ews) ->
                derived.episodeWatched[key]?.first?.let { ews.copy(isWatched = it) } ?: ews
            }
            val newSeasonWatchStates = state.seasonWatchStates.mapValues { (key, value) ->
                derived.seasonWatched[key]?.first ?: value
            }
            val isShowFullyWatched =
                state.seasons.isNotEmpty() && state.seasons.all { derived.seasonWatched[it]?.first == true }
            state.copy(
                isInWatchlist = derived.watchlist.first,
                isWatched = derived.titleWatched.first,
                isRated = derived.rating.first != null,
                userRating = derived.rating.first,
                isShowFullyWatched = isShowFullyWatched,
                episodeWatchStates = newEpisodeWatchStates,
                seasonWatchStates = newSeasonWatchStates,
                optimisticSync = OptimisticSync(
                    watchlist = toBadge(derived.watchlist.second),
                    watched = toBadge(derived.titleWatched.second),
                    rating = toBadge(derived.rating.second),
                    episodes = derived.episodeWatched.mapValues { (_, view) -> toBadge(view.second) },
                    seasons = derived.seasonWatched.mapValues { (_, view) -> toBadge(view.second) },
                ),
            )
        }
    }

    private fun toBadge(view: com.crispy.tv.domain.optimistic.MutationSyncView): OptimisticSyncBadge =
        OptimisticSyncBadge(status = view.status, errorMessage = view.errorMessage)

    /**
     * A successful flush means the server now agrees with the mutation's desired
     * value. Write that straight into the local display sources so the UI keeps
     * showing the right value with no pending override and no re-fetch. Header
     * fields live in [serverSnapshot]; episode/season fields live in [UiState]
     * because they are resolved per-season.
     */
    private fun applyCompleted(completed: List<UserMutation>) {
        var snapshot = serverSnapshot
        val episodeUpdates = mutableMapOf<String, Boolean>()
        val seasonUpdates = mutableMapOf<Int, Boolean>()
        for (mutation in completed) {
            when (mutation) {
                is WatchlistMutation -> snapshot = snapshot.copy(isInWatchlist = mutation.desired)
                is TitleWatchedMutation -> snapshot = snapshot.copy(isWatched = mutation.desired)
                is RatingMutation -> snapshot = snapshot.copy(isRated = mutation.desired != null, userRating = mutation.desired)
                is EpisodeWatchedMutation -> episodeUpdates[mutation.videoId] = mutation.desired
                is SeasonWatchedMutation -> seasonUpdates[mutation.seasonNumber] = mutation.desired
            }
        }
        serverSnapshot = snapshot
        if (episodeUpdates.isNotEmpty() || seasonUpdates.isNotEmpty()) {
            _uiState.update { state ->
                state.copy(
                    episodeWatchStates = if (episodeUpdates.isEmpty()) state.episodeWatchStates
                    else state.episodeWatchStates.mapValues { (key, ews) ->
                        episodeUpdates[key]?.let { ews.copy(isWatched = it) } ?: ews
                    },
                    seasonWatchStates = if (seasonUpdates.isEmpty()) state.seasonWatchStates
                    else state.seasonWatchStates.mapValues { (key, value) -> seasonUpdates[key] ?: value },
                )
            }
        }
    }

    fun reload() {
        reloadJob?.cancel()
        val generation = ++reloadGeneration
        reloadJob = viewModelScope.launch {
            val nowMs = System.currentTimeMillis()

            aiJob?.cancel()
            streamLoadJob?.cancel()
            streamSelectorSession++
            episodesJob?.cancel()
            extrasJob?.cancel()
            ratingsJob?.cancel()
            seasonEpisodesCache.clear()
            detailsUseCases.clearEpisodeWatchStateCache()

            _uiState.update {
                it.copy(
                    isLoading = true,
                    titleDetail = null,
                    titleExtras = null,
                    titleRatings = null,
                    extrasIsLoading = false,
                    ratingsIsLoading = false,
                    statusMessage = "",
                    extrasStatusMessage = "",
                    ratingsStatusMessage = "",
                    aiIsLoading = false,
                    aiInsights = null,
                    aiStoryVisible = false,
                    watchCta = WatchCta(),
                    continueVideoId = null,
                    seasons = emptyList(),
                    seasonItemIds = emptyMap(),
                    seasonWatchStates = emptyMap(),
                    highlightedEpisodeId = null,
                    seasonEpisodes = emptyList(),
                    episodeWatchStates = emptyMap(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                    streamSelector = StreamSelectorUiState(),
                )
            }

            val result =
                withContext(Dispatchers.IO) {
                    detailsUseCases.loadScreen(
                        itemId = itemId,
                        requestedMediaType = requestedMediaType,
                        runtimeEntry = runtimeEntry,
                        nowMs = nowMs,
                    )
                }
            if (!isCurrentGeneration(generation)) return@launch

            val enrichedDetails = result.details
            Log.d(
                "DetailsViewModel",
                "rendered details itemId=${enrichedDetails?.itemId} title=${enrichedDetails?.title} logoUrl=${enrichedDetails?.logoUrl}",
            )

            _uiState.update { state ->
                val pendingHighlightEpisodeId = pendingEpisodeNavigation?.highlightEpisodeId
                val selectedSeason =
                    when {
                        state.selectedSeason != null && state.selectedSeason in result.seasons -> state.selectedSeason
                        result.seasons.isNotEmpty() -> result.seasons.first()
                        else -> null
                    }

                state.copy(
                    isLoading = false,
                    details = result.details,
                    titleDetail = result.titleDetail,
                    statusMessage = result.statusMessage,
                    isWatched = result.providerState.isWatched,
                    isInWatchlist = result.providerState.isInWatchlist,
                    isRated = result.providerState.isRated,
                    userRating = result.providerState.userRating,
                    watchCta = result.watchCta,
                    continueVideoId = result.continueVideoId,
                    seasons = result.seasons,
                    selectedSeason = selectedSeason,
                    highlightedEpisodeId = pendingHighlightEpisodeId,
                    seasonEpisodes = emptyList(),
                    episodeWatchStates = emptyMap(),
                    episodesIsLoading = true,
                    episodesStatusMessage = "",
                )
            }

            serverSnapshot = UserStateSnapshot(
                isInWatchlist = result.providerState.isInWatchlist,
                isWatched = result.providerState.isWatched,
                isRated = result.providerState.isRated,
                userRating = result.providerState.userRating,
            )
            recomputeDerived()

            if (enrichedDetails != null) {
                loadExtras(generation)
                loadRatings(generation)
            }

            val detailsForAi = enrichedDetails
            val aiLocale = Locale.getDefault()
            val aiItemId = detailsForAi?.itemId?.trim()

            if (!aiItemId.isNullOrBlank()) {
                val cached =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.loadCachedAiInsights(aiItemId, aiLocale)
                    }
                if (cached != null && isCurrentGeneration(generation)) {
                    _uiState.update { it.copy(aiInsights = cached) }
                }
            }
        }
    }

    private fun loadExtras(generation: Long) {
        extrasJob?.cancel()
        _uiState.update { it.copy(extrasIsLoading = true, extrasStatusMessage = "") }

        extrasJob =
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.loadExtras(itemId = itemId)
                    }
                if (!isCurrentGeneration(generation)) return@launch

                val titleExtras = result.titleExtras
                if (titleExtras != null) {
                    titleExtras.episodes
                        .mapNotNull { it.toMediaVideo() }
                        .groupBy { it.season }
                        .forEach { (season, videos) ->
                            if (season != null && videos.isNotEmpty()) {
                                seasonEpisodesCache[season] = videos
                            }
                        }
                }

                _uiState.update { state ->
                    val extrasSeasons = titleExtras?.seasons
                        ?.map { it.seasonNumber }
                        ?.filter { it > 0 }
                        ?.distinct()
                        ?.sorted()
                        .orEmpty()
                    val seasonItemIds = titleExtras?.seasons
                        ?.filter { it.seasonNumber > 0 }
                        ?.associate { it.seasonNumber to it.itemId }
                        .orEmpty()
                    val runtimeEpisodeTarget = detailsUseCases.resolveRuntimeEpisodeTarget(
                        videos = titleExtras?.episodes.orEmpty().mapNotNull { it.toMediaVideo() },
                        runtimeEntry = runtimeEntry,
                    )
                    val pendingHighlightEpisodeId = pendingEpisodeNavigation?.highlightEpisodeId ?: runtimeEpisodeTarget?.episodeId
                    val pendingSeason =
                        runtimeEpisodeTarget?.seasonNumber ?: pendingHighlightEpisodeId?.let { highlightEpisodeId ->
                            seasonEpisodesCache.values.asSequence().flatten().firstOrNull { episode ->
                                episode.id.equals(highlightEpisodeId, ignoreCase = true)
                            }?.season
                        }
                    val selectedSeason =
                        when {
                            pendingSeason != null && pendingSeason in extrasSeasons -> pendingSeason
                            state.selectedSeason != null && state.selectedSeason in extrasSeasons -> state.selectedSeason
                            extrasSeasons.isNotEmpty() -> extrasSeasons.first()
                            else -> null
                        }
                    val selectedSeasonEpisodes = selectedSeason?.let { seasonEpisodesCache[it] }.orEmpty()
                    state.copy(
                        extrasIsLoading = false,
                        titleExtras = titleExtras,
                        seasons = extrasSeasons,
                        seasonItemIds = seasonItemIds,
                        selectedSeason = selectedSeason,
                        highlightedEpisodeId = pendingHighlightEpisodeId ?: state.highlightedEpisodeId,
                        seasonEpisodes = selectedSeasonEpisodes.takeIf { it.isNotEmpty() } ?: state.seasonEpisodes,
                        episodesIsLoading = false,
                        episodesStatusMessage = when {
                            selectedSeason == null -> ""
                            titleExtras == null -> "Episodes are unavailable right now."
                            selectedSeasonEpisodes.isEmpty() -> "No episodes found for this season."
                            else -> ""
                        },
                        extrasStatusMessage = if (titleExtras == null) "Some details are unavailable right now." else "",
                    )
                }

                val selectedSeason = _uiState.value.selectedSeasonOrFirst
                val selectedSeasonEpisodes = selectedSeason?.let { seasonEpisodesCache[it] }
                if (selectedSeason != null && !selectedSeasonEpisodes.isNullOrEmpty()) {
                    loadEpisodeWatchStatesForSeason(selectedSeason, selectedSeasonEpisodes)
                }
                loadAllSeasonWatchStates(generation)
            }
    }

    private fun loadRatings(generation: Long) {
        ratingsJob?.cancel()
        _uiState.update { it.copy(ratingsIsLoading = true, ratingsStatusMessage = "") }

        ratingsJob =
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.loadRatings(itemId = itemId)
                    }
                if (!isCurrentGeneration(generation)) return@launch

                _uiState.update {
                    it.copy(
                        ratingsIsLoading = false,
                        titleRatings = result.titleRatings,
                        ratingsStatusMessage = if (result.titleRatings == null) "Ratings are unavailable right now." else "",
                    )
                }
            }
    }

    private fun isCurrentGeneration(generation: Long): Boolean {
        return generation == reloadGeneration
    }

    fun onAiInsightsClick() {
        val state = uiState.value
        val details = state.details
        val aiItemId = details?.itemId?.trim()
        if (details == null || aiItemId.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "AI insights aren't available for this title yet.") }
            return
        }

        val cachedOrLoaded = state.aiInsights
        if (cachedOrLoaded != null) {
            _uiState.update { it.copy(aiStoryVisible = true) }
            return
        }

        if (state.aiIsLoading) return

        startAiGeneration(
            itemId = aiItemId,
            locale = Locale.getDefault(),
            showStory = true,
            announce = true,
        )
    }

    fun dismissAiInsightsStory() {
        _uiState.update { it.copy(aiStoryVisible = false) }
    }

    private fun startAiGeneration(
        itemId: String,
        locale: Locale,
        showStory: Boolean,
        announce: Boolean,
    ) {
        aiJob?.cancel()
        aiJob =
            viewModelScope.launch {
                if (announce) {
                    _uiState.update { it.copy(aiIsLoading = true) }
                } else {
                    _uiState.update { it.copy(aiIsLoading = true) }
                }

                runCatching {
                    withContext(Dispatchers.IO) {
                        detailsUseCases.generateAiInsights(
                            itemId = itemId,
                            locale = locale,
                        )
                    }
                }.onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            aiIsLoading = false,
                            aiInsights = result,
                            aiStoryVisible = showStory,
                            statusMessage = if (announce) "" else it.statusMessage,
                        )
                    }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            aiIsLoading = false,
                            statusMessage = e.message ?: "AI insights are unavailable right now.",
                        )
                    }
                }
            }
    }

    fun onSeasonSelected(season: Int) {
        val cached = seasonEpisodesCache[season]
        _uiState.update {
            it.copy(
                selectedSeason = season,
                seasonEpisodes = cached ?: emptyList(),
                episodeWatchStates = emptyMap(),
                episodesIsLoading = cached == null,
                episodesStatusMessage = "",
            )
        }
        if (cached == null && _uiState.value.extrasIsLoading) {
            return
        }
        if (cached == null) {
            loadEpisodesForSeason(season)
        } else {
            loadEpisodeWatchStatesForSeason(season, cached)
        }
    }

    fun requestEpisodeNavigation(
        highlightEpisodeId: String?,
        autoOpenEpisode: Boolean,
    ) {
        val normalizedHighlightEpisodeId = highlightEpisodeId?.trim()?.ifBlank { null } ?: return
        pendingEpisodeNavigation =
            PendingEpisodeNavigation(
                highlightEpisodeId = normalizedHighlightEpisodeId,
                autoOpenEpisode = autoOpenEpisode,
            )
        _uiState.update { it.copy(highlightedEpisodeId = normalizedHighlightEpisodeId) }

        val currentState = _uiState.value
        val targetSeason =
            currentState.seasonEpisodes.firstOrNull { episode ->
                episode.id.equals(normalizedHighlightEpisodeId, ignoreCase = true)
            }?.season
                ?: seasonEpisodesCache.values.asSequence().flatten().firstOrNull { episode ->
                    episode.id.equals(normalizedHighlightEpisodeId, ignoreCase = true)
                }?.season
                ?: currentState.details?.videos?.firstOrNull { episode ->
                    episode.id.equals(normalizedHighlightEpisodeId, ignoreCase = true)
                }?.season
                ?: currentState.selectedSeasonOrFirst
                ?: return
        if (targetSeason != currentState.selectedSeasonOrFirst && targetSeason in currentState.seasons) {
            onSeasonSelected(targetSeason)
            return
        }
        maybeConsumePendingEpisodeNavigation(currentState.seasonEpisodes)
    }

    fun onOpenStreamSelector() {
        val state = _uiState.value
        val details = state.details
        if (details == null) {
            _uiState.update { it.copy(statusMessage = "") }
            return
        }

        val target =
            state.continueVideoId
                ?.takeIf { it.isNotBlank() }
                ?.let { videoId ->
                    StreamLookupTarget(
                        mediaType = requestedMediaType,
                        lookupId = videoId,
                    )
                }
                ?: resolveStreamLookupTarget(
                    details = details,
                    selectedSeason = state.selectedSeasonOrFirst,
                    seasonEpisodes = state.seasonEpisodes,
                    fallbackMediaType = requestedMediaType,
                )

        val headerEpisode = findEpisodeForLookupId(target.lookupId, state.seasonEpisodes)

        openStreamSelectorWithTarget(
            target = target,
            headerEpisode = headerEpisode,
            blankIdMessage = "Unable to resolve stream lookup id for this title.",
        )
    }

    private fun loadEpisodesForSeason(
        season: Int,
        force: Boolean = false,
    ) {
        val state = _uiState.value
        val details = state.details ?: return
        if (details.itemType.toMetadataLabMediaTypeOrNull()?.let { it != MetadataLabMediaType.MOVIE } != true) return

        val cached = if (!force) seasonEpisodesCache[season] else null
        if (cached != null) {
            _uiState.update {
                it.copy(
                    seasonEpisodes = cached,
                    episodeWatchStates = emptyMap(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                )
            }
            loadEpisodeWatchStatesForSeason(season, cached)
            return
        }

        episodesJob?.cancel()
        _uiState.update {
            it.copy(
                episodesIsLoading = true,
                episodesStatusMessage = "",
                seasonEpisodes = emptyList(),
                episodeWatchStates = emptyMap(),
            )
        }

        episodesJob =
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.loadSeasonEpisodes(
                            season = season,
                            details = details,
                            titleExtras = _uiState.value.titleExtras,
                        )
                    }

                if (result.errorMessage != null) {
                    _uiState.update { current ->
                        if (current.selectedSeasonOrFirst != season) current
                        else current.copy(
                            episodesIsLoading = false,
                            episodesStatusMessage = result.errorMessage,
                        )
                    }
                    return@launch
                }

                seasonEpisodesCache[season] = result.videos
                result.effectiveSeasonNumber
                    ?.takeIf { it != season }
                    ?.let { effectiveSeason ->
                        seasonEpisodesCache[effectiveSeason] = result.videos
                    }

                _uiState.update { current ->
                    val resolvedSeason = result.effectiveSeasonNumber ?: season
                    if (current.selectedSeasonOrFirst != season && current.selectedSeasonOrFirst != resolvedSeason) current
                    else current.copy(
                        selectedSeason = resolvedSeason,
                        highlightedEpisodeId = pendingEpisodeNavigation?.highlightEpisodeId ?: current.highlightedEpisodeId,
                        seasonEpisodes = result.videos,
                        episodeWatchStates = result.episodeWatchStates,
                        episodesIsLoading = false,
                        episodesStatusMessage = if (result.videos.isEmpty()) "No episodes found." else "",
                    )
                }
                maybeConsumePendingEpisodeNavigation(result.videos)
            }
    }

    private fun loadEpisodeWatchStatesForSeason(
        season: Int,
        videos: List<MediaVideo>,
    ) {
        episodesJob?.cancel()
        episodesJob =
            viewModelScope.launch {
                val details = _uiState.value.details ?: return@launch
                val episodeWatchStates =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.resolveEpisodeWatchStates(details, videos)
                    }
                _uiState.update { current ->
                    if (current.selectedSeasonOrFirst != season) current
                    else {
                        val seasonWatched = videos.isNotEmpty() && episodeWatchStates.values.all { it.isWatched }
                        val mergedSeasonWatchStates = current.seasonWatchStates + (season to seasonWatched)
                        current.copy(
                            highlightedEpisodeId = pendingEpisodeNavigation?.highlightEpisodeId ?: current.highlightedEpisodeId,
                            episodeWatchStates = episodeWatchStates,
                            seasonWatchStates = mergedSeasonWatchStates,
                            isShowFullyWatched = computeShowFullyWatched(mergedSeasonWatchStates, current.seasons),
                        )
                    }
                }
                maybeConsumePendingEpisodeNavigation(videos)
            }
    }

    private fun loadAllSeasonWatchStates(generation: Long) {
        val details = _uiState.value.details ?: return
        val contentType = details.itemType.toMetadataLabMediaTypeOrNull()
        if (contentType != MetadataLabMediaType.SERIES && contentType != MetadataLabMediaType.ANIME) return

        val allEpisodes = seasonEpisodesCache.values.flatten()
        if (allEpisodes.isEmpty()) return

        allSeasonsWatchJob?.cancel()
        allSeasonsWatchJob =
            viewModelScope.launch {
                val watchStates =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.resolveEpisodeWatchStates(details, allEpisodes)
                    }
                if (!isCurrentGeneration(generation)) return@launch

                val seasonCompletion =
                    allEpisodes
                        .groupBy { it.season }
                        .mapNotNull { (season, videos) ->
                            if (season == null || videos.isEmpty()) {
                                null
                            } else {
                                season to videos.all { watchStates[it.id]?.isWatched == true }
                            }
                        }.toMap()

                _uiState.update { state ->
                    val merged = state.seasonWatchStates + seasonCompletion
                    state.copy(
                        seasonWatchStates = merged,
                        isShowFullyWatched = computeShowFullyWatched(merged, state.seasons),
                    )
                }
            }
    }

    private fun computeShowFullyWatched(
        seasonWatchStates: Map<Int, Boolean>,
        seasons: List<Int>,
    ): Boolean {
        if (seasons.isEmpty()) return false
        return seasons.all { seasonWatchStates[it] == true }
    }

    fun onOpenStreamSelectorForEpisode(videoId: String) {
        val state = _uiState.value
        val details = state.details
        if (details == null) {
            _uiState.update { it.copy(statusMessage = "") }
            return
        }
        val episode =
            state.seasonEpisodes.firstOrNull { it.id.equals(videoId.trim(), ignoreCase = true) }
                ?: seasonEpisodesCache.values.asSequence().flatten().firstOrNull { it.id.equals(videoId.trim(), ignoreCase = true) }
        val target =
            StreamLookupTarget(
                mediaType = requestedMediaType,
                lookupId = episode?.lookupId?.trim().orEmpty(),
            )
        openStreamSelectorWithTarget(
            target = target,
            headerEpisode = episode,
            blankIdMessage = "This episode does not have a stream lookup id.",
        )
    }

    private fun openStreamSelectorWithTarget(
        target: StreamLookupTarget,
        headerEpisode: MediaVideo? = null,
        blankIdMessage: String,
    ) {
        if (target.lookupId.isBlank()) {
            _uiState.update { it.copy(statusMessage = blankIdMessage) }
            return
        }

        val current = _uiState.value.streamSelector
        if (
            current.lookupId == target.lookupId &&
            current.mediaType == target.mediaType &&
            current.providers.isNotEmpty()
        ) {
            _uiState.update {
                it.copy(
                    streamSelector = current.copy(visible = true, headerEpisode = headerEpisode ?: current.headerEpisode),
                    statusMessage = "",
                )
            }
            return
        }

        streamLoadJob?.cancel()
        val session = ++streamSelectorSession
        _uiState.update {
            it.copy(
                streamSelector =
                    StreamSelectorUiState(
                        visible = true,
                        mediaType = target.mediaType,
                        lookupId = target.lookupId,
                        headerEpisode = headerEpisode,
                        isLoading = true,
                    ),
                statusMessage = "",
            )
        }

        streamLoadJob =
            viewModelScope.launch {
                runCatching {
                    detailsUseCases.loadStreams(
                        mediaType = target.mediaType,
                        lookupId = target.lookupId,
                        onProvidersResolved = {
                            _uiState.update { previous ->
                                if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                                previous.copy(
                                    streamSelector =
                                        previous.streamSelector.copy(
                                            visible = previous.streamSelector.visible,
                                            mediaType = target.mediaType,
                                            lookupId = target.lookupId,
                                            providers = emptyList(),
                                            isLoading = true,
                                        ),
                                    statusMessage = "",
                                )
                            }
                        },
                        onProviderResult = { result ->
                            _uiState.update { previous ->
                                if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                                val updatedProviders = previous.streamSelector.providers.applyProviderResult(result)

                                previous.copy(
                                    streamSelector =
                                        previous.streamSelector.copy(
                                            providers = updatedProviders,
                                ),
                            statusMessage = "",
                                )
                            }
                        },
                    )
                }.onSuccess { results ->
                    _uiState.update { previous ->
                        if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                        val finalizedProviders =
                            previous.streamSelector.providers
                                .finalizeFrom(results)

                        previous.copy(
                            streamSelector =
                                previous.streamSelector.copy(
                                    visible = previous.streamSelector.visible,
                                    mediaType = target.mediaType,
                                    lookupId = target.lookupId,
                                    providers = finalizedProviders,
                                    isLoading = false,
                                ),
                            statusMessage = "",
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    if (session != streamSelectorSession) return@onFailure
                    _uiState.update { previous ->
                        previous.copy(
                            streamSelector =
                                previous.streamSelector.copy(
                                    providers =
                                        previous.streamSelector.providers.map { provider ->
                                            provider.copy(isLoading = false)
                                        },
                                    isLoading = false,
                                ),
                            statusMessage = error.message ?: "Failed to fetch streams.",
                        )
                    }
                }
            }
    }

    fun onDismissStreamSelector() {
        ++streamSelectorSession
        streamLoadJob?.cancel()
        streamLoadJob = null
        _uiState.update { state ->
            state.copy(
                streamSelector = state.streamSelector.copy(visible = false),
                statusMessage = "",
            )
        }
    }

    fun onProviderSelected(providerId: String?) {
        _uiState.update { state ->
            state.copy(
                streamSelector =
                    state.streamSelector.copy(
                        selectedProviderId = providerId?.trim()?.takeIf { it.isNotBlank() },
                    )
            )
        }
    }

    fun onRetryProvider(providerId: String) {
        val normalizedProviderId = providerId.trim()
        if (normalizedProviderId.isBlank()) return

        val selectorState = _uiState.value.streamSelector
        val mediaType = selectorState.mediaType ?: return
        val lookupId = selectorState.lookupId ?: return
        val session = streamSelectorSession

        _uiState.update { state ->
            val providers =
                state.streamSelector.providers.map { provider ->
                    if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                        provider.copy(
                            isLoading = true,
                            errorMessage = null,
                        )
                    } else {
                        provider
                    }
                }
            state.copy(
                streamSelector = state.streamSelector.copy(providers = providers),
                statusMessage = "",
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    detailsUseCases.loadProviderStreams(
                        mediaType = mediaType,
                        lookupId = lookupId,
                        providerId = normalizedProviderId,
                    )
                }
            }.onSuccess { result ->
                if (session != streamSelectorSession) return@onSuccess
                _uiState.update { state ->
                    val providers =
                        state.streamSelector.providers.map { provider ->
                            if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                                val updated = result?.toUiState()
                                if (updated != null) {
                                    updated
                                } else {
                                    provider.copy(
                                        isLoading = false,
                                        errorMessage = "Provider no longer available.",
                                        streams = emptyList(),
                                        attemptedUrl = null,
                                    )
                                }
                            } else {
                                provider
                            }
                        }

                    val updatedProvider =
                        providers.firstOrNull { provider ->
                            provider.providerId.equals(normalizedProviderId, ignoreCase = true)
                        }
                    state.copy(
                        streamSelector = state.streamSelector.copy(providers = providers),
                        statusMessage =
                            when {
                                updatedProvider == null -> "Provider no longer available."
                                updatedProvider.errorMessage != null -> updatedProvider.errorMessage
                                else -> ""
                            },
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (session != streamSelectorSession) return@onFailure
                _uiState.update { state ->
                    val providers =
                        state.streamSelector.providers.map { provider ->
                            if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                                provider.copy(
                                    isLoading = false,
                                    errorMessage = error.message ?: "Failed to reload provider.",
                                )
                            } else {
                                provider
                            }
                        }
                    state.copy(
                        streamSelector = state.streamSelector.copy(providers = providers),
                        statusMessage = error.message ?: "Failed to reload provider.",
                    )
                }
            }
        }
    }

    fun onStreamSelected(stream: AddonStream) {
        if (!stream.hasPlayableSource) {
            _uiState.update { it.copy(statusMessage = "Selected stream has no playable source.") }
            return
        }

        val currentState = _uiState.value
        val selectedEpisodeTitle =
            currentState.streamSelector.headerEpisode
                ?.title
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        _uiState.update { state ->
            state.copy(
                streamSelector = state.streamSelector.copy(visible = false),
                statusMessage = "",
            )
        }

        val initialDetails = currentState.details

        viewModelScope.launch {
            val details = initialDetails
                ?: return@launch

            val enriched =
                withContext(Dispatchers.IO) {
                    detailsUseCases.ensureImdbId(details, requestedMediaType)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }

            val resolvedMediaType = enriched.itemType.toMetadataLabMediaTypeOrNull() ?: requestedMediaType
            val targetEpisode =
                currentState.streamSelector.headerEpisode
                    ?: findEpisodeForLookupId(
                        lookupId = currentState.streamSelector.lookupId.orEmpty(),
                        currentEpisodes = currentState.seasonEpisodes,
                    )
            val resolvedLookupId =
                currentState.streamSelector.lookupId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: targetEpisode?.lookupId?.trim()?.takeIf { it.isNotBlank() }
                    ?: resolveStreamLookupTarget(
                        details = enriched,
                        selectedSeason = currentState.selectedSeasonOrFirst,
                        seasonEpisodes = currentState.seasonEpisodes,
                        fallbackMediaType = requestedMediaType,
                    ).lookupId
            val parsedLookupId = parseLookupId(resolvedLookupId)

            val isEpisodic = resolvedMediaType != MetadataLabMediaType.MOVIE
            val parentMediaType =
                when (resolvedMediaType) {
                    MetadataLabMediaType.MOVIE -> null
                    MetadataLabMediaType.SERIES -> "show"
                    MetadataLabMediaType.ANIME -> "anime"
                }
            val season = if (isEpisodic) targetEpisode?.season ?: parsedLookupId.season else null
            val episode = if (isEpisodic) targetEpisode?.episode ?: parsedLookupId.episode else null

            val mediaTitle = enriched.title.trim().ifBlank { null } ?: details.title.trim().ifBlank { null }
            val title = selectedEpisodeTitle ?: targetEpisode?.title?.trim()?.takeIf { it.isNotBlank() } ?: mediaTitle ?: "Player"
            val yearInt = enriched.year?.trim()?.toIntOrNull()
            val playbackItemId = if (isEpisodic) {
                targetEpisode?.id?.trim()?.takeIf { it.isNotBlank() } ?: enriched.itemId
            } else {
                enriched.itemId
            }
            val identity =
                PlaybackIdentity(
                    itemId = playbackItemId,
                    seriesItemId = enriched.itemId,
                imdbId = enriched.imdbId,
                contentType = resolvedMediaType,
                season = season,
                episode = episode,
                title = title,
                year = yearInt,
                showTitle = if (isEpisodic) enriched.title else null,
                showYear = if (isEpisodic) yearInt else null,
                parentMediaType = enriched.parentMediaType ?: parentMediaType,
                absoluteEpisodeNumber = targetEpisode?.absoluteEpisodeNumber ?: enriched.absoluteEpisodeNumber,
            )
            val resumePositionMs = withContext(Dispatchers.IO) {
                detailsUseCases.userMediaRepository
                    .getLocalWatchProgress(identity)
                    ?.takeIf { it.progressPercent in 1.0..95.0 }
                    ?.let { (it.currentTimeSeconds * 1000.0).toLong() }
                    ?: 0L
            }

            _navigationEvents.tryEmit(
                DetailsNavigationEvent.OpenPlayer(
                    identity = identity,
                    resumePositionMs = resumePositionMs,
                    chosenStreamStableKey = stream.stableKey,
                    chosenProviderId = stream.providerId,
                    chosenStreamHandoffKey = PlayerStreamHandoff.stash(stream, resolvedLookupId),
                )
            )
        }
    }

    fun toggleWatchlist() {
        val details = uiState.value.details ?: return
        val targetId = details.itemId?.trim()?.ifBlank { null } ?: return
        val desired = !uiState.value.isInWatchlist
        val now = System.currentTimeMillis()
        outbox.enqueue(
            WatchlistMutation(
                id = UserMutationOutbox.newId(),
                titleItemId = targetId,
                entityId = targetId,
                createdAtMs = now,
                attempt = 0,
                status = MutationStatus.Pending,
                nextAttemptAtMs = now,
                desired = desired,
            ),
        )
    }

    fun toggleWatched() {
        val details = uiState.value.details ?: return
        val targetId = details.itemId?.trim()?.ifBlank { null } ?: return
        val contentType = (details.itemType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE).toContentType()
        val desired = !uiState.value.isWatched
        val now = System.currentTimeMillis()
        outbox.enqueue(
            TitleWatchedMutation(
                id = UserMutationOutbox.newId(),
                titleItemId = targetId,
                entityId = targetId,
                createdAtMs = now,
                attempt = 0,
                status = MutationStatus.Pending,
                nextAttemptAtMs = now,
                contentType = contentType,
                desired = desired,
            ),
        )
    }

    fun toggleEpisodeWatched(video: MediaVideo) {
        val details = uiState.value.details ?: return
        val targetId = details.itemId?.trim()?.ifBlank { null } ?: return
        val season = video.season
        val episode = video.episode
        if (season == null || episode == null) {
            _uiState.update { it.copy(statusMessage = "Episode metadata is incomplete.") }
            return
        }
        val desired = !(uiState.value.episodeWatchStates[video.id]?.isWatched ?: false)
        val now = System.currentTimeMillis()
        outbox.enqueue(
            EpisodeWatchedMutation(
                id = UserMutationOutbox.newId(),
                titleItemId = targetId,
                entityId = "$targetId#S$season:E$episode",
                createdAtMs = now,
                attempt = 0,
                status = MutationStatus.Pending,
                nextAttemptAtMs = now,
                itemId = targetId,
                season = season,
                episode = episode,
                videoId = video.id,
                desired = desired,
            ),
        )
    }

    fun toggleSeasonWatched(seasonItemId: String, seasonNumber: Int) {
        val desired = !(uiState.value.seasonWatchStates[seasonNumber] ?: false)
        val now = System.currentTimeMillis()
        outbox.enqueue(
            SeasonWatchedMutation(
                id = UserMutationOutbox.newId(),
                titleItemId = itemId,
                entityId = seasonItemId,
                createdAtMs = now,
                attempt = 0,
                status = MutationStatus.Pending,
                nextAttemptAtMs = now,
                seasonItemId = seasonItemId,
                seasonNumber = seasonNumber,
                desired = desired,
            ),
        )
    }

    fun setRating(rating: Int?) {
        val details = uiState.value.details ?: return
        val targetId = details.itemId?.trim()?.ifBlank { null } ?: return
        val now = System.currentTimeMillis()
        outbox.enqueue(
            RatingMutation(
                id = UserMutationOutbox.newId(),
                titleItemId = targetId,
                entityId = targetId,
                createdAtMs = now,
                attempt = 0,
                status = MutationStatus.Pending,
                nextAttemptAtMs = now,
                desired = rating,
            ),
        )
    }

    private fun maybeConsumePendingEpisodeNavigation(videos: List<MediaVideo>) {
        val pending = pendingEpisodeNavigation ?: return
        val target = videos.firstOrNull { video ->
            video.id.equals(pending.highlightEpisodeId, ignoreCase = true)
        } ?: return
        _uiState.update { it.copy(highlightedEpisodeId = target.id) }
        pendingEpisodeNavigation = null

        if (!pending.autoOpenEpisode) return

        onOpenStreamSelectorForEpisode(target.id)
    }

    companion object {
        internal fun factory(
            itemId: String,
            itemType: String,
            runtimeEntry: RuntimeDetailsEntry?,
            detailsUseCases: DetailsUseCases,
            outbox: UserMutationOutbox,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DetailsViewModel(
                        itemId = itemId,
                        itemType = itemType,
                        runtimeEntry = runtimeEntry,
                        detailsUseCases = detailsUseCases,
                        outbox = outbox,
                    ) as T
                }
            }
        }
    }
}

private data class PendingEpisodeNavigation(
    val highlightEpisodeId: String,
    val autoOpenEpisode: Boolean,
)
