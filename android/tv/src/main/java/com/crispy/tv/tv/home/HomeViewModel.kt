package com.crispy.tv.tv.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.tv.di.TvServices
import com.crispy.tv.tv.ui.components.CrispyCardItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeRailUi(
    val key: String,
    val title: String,
    val items: List<CrispyCardItem>,
)

data class HomeUiState(
    val loading: Boolean = true,
    val rails: List<HomeRailUi> = emptyList(),
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val context = TvServices.contextResolver(appContext).resolve()
            if (context == null) {
                _state.value = HomeUiState(
                    loading = false,
                    error = "Not signed in",
                )
                return@launch
            }
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = runCatching { loadRails(context) }
                .fold(
                    onSuccess = { rails -> HomeUiState(loading = false, rails = rails) },
                    onFailure = { t ->
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        HomeUiState(loading = false, error = t.message ?: "Failed to load home")
                    },
                )
        }
    }

    private suspend fun loadRails(context: BackendContext): List<HomeRailUi> {
        val appContext = getApplication<Application>()
        val client = TvServices.backendClient(appContext)
        return coroutineScope {
            val cw = async {
                runCatching { client.listContinueWatching(context.accessToken, context.profileId) }
                    .getOrNull()
            }
            val upNext = async {
                runCatching { client.getUpNext(context.accessToken, context.profileId) }
                    .getOrNull()
            }
            val thisWeek = async {
                runCatching { client.getCalendarThisWeek(context.accessToken, context.profileId) }
                    .getOrNull()
            }
            val profileHome = async {
                runCatching { client.getHome(context.accessToken, context.profileId) }
                    .getOrNull()
            }

            buildList {
                cw.await()?.let { result ->
                    if (result.items.isNotEmpty()) {
                        add(
                            HomeRailUi(
                                key = "continue_watching",
                                title = "Continue Watching",
                                items = result.items.map { it.toCardItem() },
                            ),
                        )
                    }
                }
                upNext.await()?.let { response ->
                    val mapped = response.items.mapNotNull { it.toCardItem() }
                    if (mapped.isNotEmpty()) {
                        add(HomeRailUi(key = "up_next", title = "Up Next", items = mapped))
                    }
                }
                thisWeek.await()?.let { response ->
                    val mapped = response.items.map { it.toCardItem() }
                    if (mapped.isNotEmpty()) {
                        add(HomeRailUi(key = "this_week", title = "This Week", items = mapped))
                    }
                }
                profileHome.await()?.sections?.forEach { section ->
                    if (section.items.isNotEmpty()) {
                        add(
                            HomeRailUi(
                                key = section.listKey,
                                title = section.title,
                                items = section.items.map { it.toCardItem() },
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun CrispyBackendClient.ClientMediaCard.bestImageUrl(): String? =
    images.backdrop.large
        ?: images.backdrop.medium
        ?: images.backdrop.small
        ?: images.still.medium
        ?: images.still.small
        ?: images.poster.medium

private fun CrispyBackendClient.ClientMediaCard.toCardItem(): CrispyCardItem {
    val parent = parent
    val isEpisode = mediaType.equals("episode", ignoreCase = true)
    val displayTitle = if (isEpisode && !parent?.seriesTitle.isNullOrBlank()) {
        parent!!.seriesTitle!!
    } else {
        title
    }
    val subtitle = when {
        isEpisode && parent?.seasonNumber != null && parent.episodeNumber != null -> {
            "S${parent.seasonNumber} E${parent.episodeNumber}" +
                (title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
        else -> year?.toString()
    }
    val fraction = progress?.let { p ->
        p.percent?.div(100.0)?.toFloat()
            ?: p.positionSeconds?.let { pos ->
                p.durationSeconds?.takeIf { it > 0 }?.let { dur -> pos.toFloat() / dur }
            }
    }
    return CrispyCardItem(
        id = itemId,
        title = displayTitle,
        subtitle = subtitle,
        imageUrl = bestImageUrl(),
        progressFraction = fraction,
    )
}

private fun CrispyBackendClient.UpNextItem.toCardItem(): CrispyCardItem? {
    val episodeId = nextEpisodeItemId ?: return null
    val seasonEpisode = listOfNotNull(nextEpisodeSeasonNumber, nextEpisodeEpisodeNumber)
        .joinToString(" E", prefix = "S")
        .takeIf { nextEpisodeSeasonNumber != null && nextEpisodeEpisodeNumber != null }
        .orEmpty()
    return CrispyCardItem(
        id = episodeId,
        title = showTitle ?: "Next episode",
        subtitle = sequenceOf(seasonEpisode, nextEpisodeTitle)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        imageUrl = showBackdropUrl ?: showPosterUrl,
    )
}

private fun CrispyBackendClient.MediaItem.toCardItem(): CrispyCardItem =
    CrispyCardItem(
        id = itemId,
        title = title,
        subtitle = releaseDate?.takeIf { it.isNotBlank() },
        imageUrl = backdrop.large
            ?: backdrop.medium
            ?: backdrop.small
            ?: poster.medium,
    )
