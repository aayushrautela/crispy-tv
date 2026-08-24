package com.crispy.tv.tv.ui.screens.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.tv.di.TvServices
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
    val airDate: String?,
    val runtimeMinutes: Int?,
    val overview: String?,
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
    val genres: List<String> = emptyList(),
    val certification: String? = null,
    val seasonCount: Int? = null,
    val seasons: List<CrispyBackendClient.MetadataSeasonView> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<DetailEpisodeUi> = emptyList(),
    val episodesLoading: Boolean = false,
    val cast: List<String> = emptyList(),
    val similar: List<com.crispy.tv.tv.ui.components.CrispyCardItem> = emptyList(),
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
        }
    }

    fun selectSeason(season: Int) {
        if (season == _state.value.selectedSeason) return
        _state.value = _state.value.copy(selectedSeason = season, episodesLoading = true)
        viewModelScope.launch {
            val context = TvServices.contextResolver(getApplication<Application>()).resolve()
            val episodes = context?.let { loadEpisodes(it, season) }.orEmpty()
            _state.value = _state.value.copy(episodes = episodes, episodesLoading = false)
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

            val firstSeason = extras?.seasons?.minOfOrNull { it.seasonNumber } ?: 1
            val episodes = if (isSeries) loadEpisodes(context, firstSeason) else emptyList()

            val metaParts = buildList {
                item.releaseYear?.let { add(it.toString()) }
                item.runtimeMinutes?.let { add("${it}m") }
                item.rating?.let { add("\u2605 " + it) }
                item.certification?.let { add(it) }
            }

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
                genres = item.genres,
                certification = item.certification,
                seasonCount = item.seasonCount,
                seasons = extras?.seasons.orEmpty().sortedBy { it.seasonNumber },
                selectedSeason = if (isSeries) firstSeason else null,
                episodes = episodes,
                cast = detail.cast.take(12).map { it.name },
                similar = extras?.similar.orEmpty()
                    .filter { it.itemId != null }
                    .map { card ->
                        com.crispy.tv.tv.ui.components.CrispyCardItem(
                            id = card.itemId!!,
                            title = card.title ?: "Untitled",
                            subtitle = card.releaseYear?.toString(),
                            imageUrl = card.images.backdrop.large
                                ?: card.images.backdrop.medium
                                ?: card.images.backdrop.small
                                ?: card.images.poster.medium,
                        )
                    },
            )
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
                        airDate = ep.airDate,
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
