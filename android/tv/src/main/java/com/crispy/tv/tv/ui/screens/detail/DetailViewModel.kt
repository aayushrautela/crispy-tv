package com.crispy.tv.tv.ui.screens.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.addons.lookup.buildAddonEpisodeLookupId
import com.crispy.tv.addons.lookup.resolveStreamLookupTarget
import com.crispy.tv.addons.mapping.toMediaDetails
import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.tv.di.TvServices
import com.crispy.tv.tv.ui.components.CrispyCardItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailEpisodeUi(
    val itemId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val title: String,
    val lookupId: String? = null,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val overview: String?,
)

data class AiSlideUi(
    val id: String,
    val label: String,
    val body: String,
    val tag: String?,
)

data class ExtraVideoUi(
    val id: String,
    val name: String,
    val url: String?,
    val thumbnailUrl: String?,
)

data class DetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val itemId: String = "",
    val itemType: String = "",
    val title: String = "",
    val subtitleMeta: String? = null,
    val overview: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val genres: List<String> = emptyList(),
    val certification: String? = null,
    val status: String? = null,
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
    val seasons: List<CrispyBackendClient.MetadataSeasonView> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<DetailEpisodeUi> = emptyList(),
    val episodesLoading: Boolean = false,
    val cast: List<CastMemberUi> = emptyList(),
    val similar: List<CrispyCardItem> = emptyList(),
    val lookupMediaTypeName: String? = null,
    val lookupId: String? = null,
    val ctaLabel: String = "Watch now",
    val ctaKind: String = "WATCH",
    val ctaRemainingMinutes: Int? = null,
    val aiLoading: Boolean = false,
    val aiSlides: List<AiSlideUi> = emptyList(),
    val extraVideos: List<ExtraVideoUi> = emptyList(),
    val collectionName: String? = null,
    val collectionItems: List<CrispyCardItem> = emptyList(),
    val ratingBadges: List<String> = emptyList(),
    val reviews: List<ReviewUi> = emptyList(),
    val production: List<CompanyUi> = emptyList(),
    val detailRows: List<Pair<String, String>> = emptyList(),
    val isInWatchlist: Boolean = false,
    val isWatched: Boolean = false,
    val isRated: Boolean = false,
    val userRating: Int? = null,
)

data class CastMemberUi(
    val personId: String,
    val name: String,
    val role: String?,
    val profileUrl: String?,
)

data class ReviewUi(
    val id: String,
    val author: String,
    val content: String,
    val rating: String?,
)

data class CompanyUi(
    val id: String,
    val name: String,
    val logoUrl: String?,
)

class DetailViewModel(
    app: Application,
    private val itemId: String,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val context = TvServices.contextResolver(appContext).resolve()
            if (context == null) {
                _state.value = DetailUiState(loading = false, error = "Not signed in")
                return@launch
            }
            _state.value = DetailUiState(loading = true, itemId = itemId)
            _state.value = runCatching { load(context) }
                .fold(
                    onSuccess = { it },
                    onFailure = { t ->
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        DetailUiState(
                            loading = false,
                            itemId = itemId,
                            error = t.message ?: "Failed to load details",
                        )
                    },
                )
            launchEnrichment(context)
        }
    }

    /** Secondary loads that must never block or fail the page: AI insights, ratings. */
    private fun launchEnrichment(context: BackendContext) {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val client = TvServices.backendClient(appContext)
            _state.value = _state.value.copy(aiLoading = true)
            val insights = runCatching {
                client.getAiInsights(context.accessToken, context.profileId, itemId)
            }.getOrNull()
            _state.value = _state.value.copy(
                aiLoading = false,
                aiSlides = insights?.slides?.mapIndexed { index, slide -> slide.toUi(index) }.orEmpty(),
            )
        }
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val client = TvServices.backendClient(appContext)
            val ratings = runCatching {
                client.getMetadataItemRatings(context.accessToken, context.profileId, itemId)
            }.getOrNull()
            _state.value = _state.value.copy(ratingBadges = ratings?.ratings.toBadges())
        }
    }

    private fun CrispyBackendClient.MetadataTitleRatings?.toBadges(): List<String> {
        val r = this?.ratings ?: return emptyList()
        return buildList {
            r.imdb?.let { add("IMDb ${formatOneDecimal(it)}") }
            r.tmdb?.let { add("TMDB ${it.toInt()}%") }
            r.rottenTomatoes?.let { add("RT ${it.toInt()}%") }
            r.metacritic?.let { add("MC ${it.toInt()}") }
        }.take(3)
    }

    private fun formatOneDecimal(value: Double): String =
        kotlin.math.round(value * 10.0).div(10.0).toString()

    private fun AiInsightSlide.toUi(index: Int): AiSlideUi =
        AiSlideUi(
            id = "$index-${key.name}",
            label = label.ifBlank { key.name.replace('_', ' ') },
            body = listOfNotNull(body, focus, context).filter { it.isNotBlank() }.joinToString("\n\n"),
            tag = tag,
        )

    fun selectSeason(season: Int) {
        if (season == _state.value.selectedSeason) return
        _state.value = _state.value.copy(selectedSeason = season, episodesLoading = true)
        viewModelScope.launch {
            val context = TvServices.contextResolver(getApplication<Application>()).resolve()
            val episodes = context?.let { loadEpisodes(it, season) }.orEmpty()
            val refreshedLookup = episodes.firstOrNull()?.lookupId
            _state.value = _state.value.copy(
                episodes = episodes,
                isInWatchlist = watchState?.isInWatchlist ?: false,
                isWatched = watchState?.isWatched ?: false,
                isRated = watchState?.isRated ?: false,
                userRating = watchState?.userRating,
                episodeWatchStates = episodeStates,
                episodesLoading = false,
                lookupId = refreshedLookup ?: _state.value.lookupId,
            )
        }
    }

    private suspend fun load(context: BackendContext): DetailUiState {
        val appContext = getApplication<Application>()
        val client = TvServices.backendClient(appContext)
        return coroutineScope {
            val detailDeferred = async {
                client.getMetadataItemDetail(context.accessToken, itemId)
            }
            val extrasDeferred = async {
                runCatching { client.getMetadataItemExtras(context.accessToken, itemId) }
                    .getOrNull()
            }

            val detail = detailDeferred.await()
            val extras = extrasDeferred.await()
            val item = detail.item
            val isSeries = !item.seasonCount.isNullOrEmptyOrZero() ||
                item.itemType.equals("series", ignoreCase = true)

            val detailsModel = item.toMediaDetails()
            val fallbackType = if (isSeries) MetadataLabMediaType.SERIES else MetadataLabMediaType.MOVIE
            val lookupTarget =
                resolveStreamLookupTarget(
                    details = detailsModel,
                    selectedSeason = null,
                    seasonEpisodes = emptyList(),
                    fallbackMediaType = fallbackType,
                )

            val watchState = runCatching {
                TvServices.watchHistoryService(appContext).getTitleWatchState(
                    itemId = itemId,
                    contentType = fallbackType,
                )
            }.getOrNull()

            val firstSeason = extras?.seasons?.minOfOrNull { it.seasonNumber } ?: 1
            val episodes = if (isSeries) loadEpisodes(context, firstSeason) else emptyList()

            val metaParts = buildList {
                item.releaseYear?.let { add(it.toString()) }
                item.runtimeMinutes?.let { add("${it}m") }
                item.rating?.let { add("★ ${roundToOne(it)}") }
                item.certification?.let { add(it) }
                if (isSeries && item.seasonCount != null) {
                    add("${item.seasonCount} seasons")
                    item.episodeCount?.let { add("$it episodes") }
                }
                item.status?.takeIf { it.isNotBlank() && !it.equals("released", true) }?.let { add(it) }
            }

            val cta = resolveWatchCta(item, watchState, isSeries)
            val episodeIds = episodes.map { it.itemId }
            val episodeStates = loadEpisodeStates(context, episodeIds)

            DetailUiState(
                loading = false,
                itemId = itemId,
                itemType = item.itemType,
                title = item.title ?: "Untitled",
                subtitleMeta = metaParts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                overview = item.overview ?: item.summary,
                backdropUrl = item.images.backdrop.large
                    ?: item.images.backdrop.medium
                    ?: item.images.backdrop.small
                    ?: item.images.poster.large,
                logoUrl = item.images.logo.large
                    ?: item.images.logo.medium
                    ?: item.images.logo.small,
                genres = item.genres,
                certification = item.certification,
                status = item.status,
                seasonCount = item.seasonCount,
                episodeCount = item.episodeCount,
                seasons = extras?.seasons.orEmpty().sortedBy { it.seasonNumber },
                selectedSeason = if (isSeries) firstSeason else null,
                episodes = episodes,
                isInWatchlist = watchState?.isInWatchlist ?: false,
                isWatched = watchState?.isWatched ?: false,
                isRated = watchState?.isRated ?: false,
                userRating = watchState?.userRating,
                episodeWatchStates = episodeStates,
                cast = detail.cast.map {
                    CastMemberUi(
                        personId = it.personId,
                        name = it.name,
                        role = it.role,
                        profileUrl = it.profileUrl,
                    )
                },
                lookupMediaTypeName = lookupTarget.mediaType.name,
                lookupId = lookupTarget.lookupId,
                ctaLabel = cta.first,
                ctaKind = cta.second,
                ctaRemainingMinutes = cta.third,
                extraVideos = detail.videos.mapNotNull { it.toExtraVideo() },
                reviews = extras?.reviews.orEmpty().map { review ->
                    ReviewUi(
                        id = review.id,
                        author = review.author ?: review.username ?: "Anonymous",
                        content = review.content,
                        rating = review.rating?.let { "${it.toInt()}/10" },
                    )
                },
                production = (detail.production.companies + detail.production.networks)
                    .distinctBy { it.id }
                    .map { company -> CompanyUi(id = company.id, name = company.name, logoUrl = company.logoUrl) },
                detailRows = buildDetailRows(item, detailsModel, detail.production, isSeries),
                collectionName = extras?.collection?.name,
                collectionItems = extras?.collection?.parts.orEmpty()
                    .filter { it.itemId != null }
                    .map { it.toCardItem() },
                similar = extras?.similar.orEmpty()
                    .filter { it.itemId != null }
                    .map { it.toCardItem() },
            )
        }
    }

    private fun resolveWatchCta(
        item: CrispyBackendClient.MetadataView,
        watchState: com.crispy.tv.player.CanonicalWatchStateSnapshot?,
        isSeries: Boolean,
    ): Triple<String, String, Int?> {
        val played = watchState?.isWatched == true
        val progress = watchState?.progressPercent
        val resume = watchState?.resumePositionSeconds
        val hasResume = (resume != null && resume > 0.0) || (progress != null && progress > 0.0)

        val kind: String
        val label: String
        when {
            hasResume && !played -> {
                kind = "CONTINUE"
                label = if (isSeries) {
                    val next = item.nextEpisode
                    if (next?.seasonNumber != null && next.episodeNumber != null) {
                        "Continue S${next.seasonNumber}:E${next.episodeNumber}"
                    } else {
                        "Continue"
                    }
                } else {
                    "Resume"
                }
            }
            played -> {
                kind = "REWATCH"
                label = "Rewatch"
            }
            else -> {
                kind = "WATCH"
                label = "Watch now"
            }
        }

        val remaining = item.runtimeMinutes?.takeIf { it > 0 }?.let { runtime ->
            when {
                kind == "CONTINUE" && progress != null -> {
                    kotlin.math.round(runtime * (1.0 - progress / 100.0)).toInt().coerceAtLeast(0)
                }
                else -> runtime
            }
        }
        return Triple(label, kind, remaining)
    }

    private fun CrispyBackendClient.MetadataCardView.toCardItem(): CrispyCardItem =
        CrispyCardItem(
            id = itemId!!,
            title = title ?: "Untitled",
            imageUrl = images.backdrop.large
                ?: images.backdrop.medium
                ?: images.backdrop.small
                ?: images.poster.medium,
            logoUrl = images.logo.large ?: images.logo.medium ?: images.logo.small,
            rating = rating?.let { roundToOne(it) },
            year = releaseYear?.toString(),
            genre = genre,
        )

    private fun CrispyBackendClient.MetadataVideoView.toExtraVideo(): ExtraVideoUi? {
        val resolvedUrl = when {
            !url.isNullOrBlank() -> url
            site.equals("YouTube", ignoreCase = true) -> "https://youtu.be/$key"
            else -> return null
        }
        return ExtraVideoUi(
            id = id,
            name = name ?: type ?: "Extra video",
            url = resolvedUrl,
            thumbnailUrl = thumbnailUrl,
        )
    }

    fun toggleWatchlist() {
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            val target = !(_state.value.isInWatchlist)
            _state.value = _state.value.copy(isInWatchlist = target)
            runCatching {
                TvServices.watchHistoryService(appContext).setTitleInWatchlist(itemId, target)
            }
        }
    }

    fun toggleWatched() {
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            val target = !(_state.value.isWatched)
            _state.value = _state.value.copy(isWatched = target)
            val request = WatchHistoryRequest(
                itemId = itemId,
                contentType = currentMediaType(),
                title = _state.value.title,
            )
            runCatching {
                if (target) {
                    TvServices.watchHistoryService(appContext).markWatched(request)
                } else {
                    TvServices.watchHistoryService(appContext).unmarkWatched(request)
                }
            }
        }
    }

    fun setRating(rating: Int?) {
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            _state.value = _state.value.copy(isRated = rating != null, userRating = rating)
            runCatching {
                TvServices.watchHistoryService(appContext).setTitleRating(itemId, rating)
            }
        }
    }

    private fun currentMediaType(): MetadataLabMediaType =
        _state.value.lookupMediaTypeName
            ?.let { name -> runCatching { MetadataLabMediaType.valueOf(name) }.getOrNull() }
            ?: MetadataLabMediaType.MOVIE

    private suspend fun loadEpisodeStates(
        context: BackendContext,
        episodeItemIds: List<String>,
    ): Map<String, EpisodeWatchStateUi> {
        if (episodeItemIds.isEmpty()) return emptyMap()
        return runCatching {
            val client = TvServices.backendClient(getApplication<Application>())
            client.getWatchStateMap(context.accessToken, context.profileId, episodeItemIds)
        }.getOrElse { emptyMap() }.mapValues { (_, ws) ->
            EpisodeWatchStateUi(
                progressPercent = ws.progressPercent ?: 0.0,
                isWatched = ws.played,
            )
        }
    }

    fun toggleEpisodeWatched(episode: DetailEpisodeUi) {
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            val current = _state.value.episodeWatchStates[episode.itemId]?.isWatched == true
            val next = !current
            _state.value = _state.value.copy(
                episodeWatchStates = _state.value.episodeWatchStates +
                    (episode.itemId to EpisodeWatchStateUi(0.0, next)),
            )
            val request = WatchHistoryRequest(
                itemId = episode.itemId,
                contentType = MetadataLabMediaType.SERIES,
                title = _state.value.title,
                season = episode.seasonNumber,
                episode = episode.episodeNumber,
            )
            runCatching {
                val service = TvServices.watchHistoryService(appContext)
                if (next) service.markWatched(request) else service.unmarkWatched(request)
            }
        }
    }

    private fun roundToOne(value: Double): String =
        kotlin.math.round(value * 10.0).div(10.0).toString()

    private fun buildDetailRows(
        item: CrispyBackendClient.MetadataView,
        detailsModel: com.crispy.tv.addons.model.MediaDetails,
        production: CrispyBackendClient.MetadataProductionInfoView,
        isSeries: Boolean,
    ): List<Pair<String, String>> = buildList {
        item.status?.takeIf { it.isNotBlank() }?.let { add("STATUS" to it) }
        if (isSeries) {
            item.releaseDate?.let { add("FIRST AIR DATE" to it) }
            item.seasonCount?.takeIf { it > 0 }?.let { add("SEASONS" to "$it") }
            item.episodeCount?.takeIf { it > 0 }?.let { add("EPISODES" to "$it") }
            item.runtimeMinutes?.takeIf { it > 0 }?.let { add("EPISODE RUNTIME" to "$it min") }
        } else {
            item.releaseDate?.let { add("RELEASE DATE" to it) }
            detailsModel.runtime?.takeIf { it.isNotBlank() }?.let { add("RUNTIME" to it) }
        }
        if (production.originCountries.isNotEmpty()) {
            add("ORIGIN COUNTRY" to production.originCountries.joinToString(", "))
        }
        production.originalLanguage?.let { add("ORIGINAL LANGUAGE" to it.uppercase()) }
        if (genres.isNotEmpty()) {
            add("GENRES" to genres.joinToString(", "))
        }
    }

    private suspend fun loadEpisodes(
        context: BackendContext,
        season: Int,
    ): List<DetailEpisodeUi> {
        val client = TvServices.backendClient(getApplication<Application>())
        return runCatching {
            client.getSeriesEpisodes(
                accessToken = context.accessToken,
                seriesItemId = itemId,
                season = season,
            )
        }.fold(
            onSuccess = { response ->
                response.items.map { ep ->
                    DetailEpisodeUi(
                        itemId = ep.itemId,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                        title = ep.title ?: "Episode ${ep.episodeNumber ?: ""}".trim(),
                        lookupId = buildAddonEpisodeLookupId(ep.externalIds.imdb, ep.seasonNumber, ep.episodeNumber)
                            ?: ep.itemId,
                        airDate = ep.releaseDate,
                        runtimeMinutes = ep.runtimeMinutes,
                        overview = ep.summary ?: ep.overview,
                    )
                }
            },
            onFailure = { emptyList() },
        )
    }

    private fun Int?.isNullOrEmptyOrZero(): Boolean = this == null || this == 0
}
