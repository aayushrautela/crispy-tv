package com.crispy.tv.tv.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.lookup.buildAddonEpisodeLookupId
import com.crispy.tv.addons.lookup.resolveStreamLookupTarget
import com.crispy.tv.addons.lookup.toMetadataLabMediaTypeOrNull
import com.crispy.tv.addons.mapping.toMediaDetails
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.tv.di.TvServices
import com.crispy.tv.tv.player.TvPlaybackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One stream a provider offered, as the picker shows it.
 *
 * The stream itself is carried rather than copied into flat fields, because the flat
 * fields are a reading of it: the URL, the cached flag, the torrent flag and the
 * subtitle count all come from the same value, so a row cannot claim one thing and
 * play another. **The request headers and the sidecar subtitles are the reason this
 * is not a set of strings**: they used to be dropped on the way to the player, which
 * is why a provider that requires a `Referer` played on the phone and not on the TV.
 */
data class SourceRowUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val description: String? = null,
    /** The provider's own order for this stream inside its own list. */
    val streamOrder: Int = 0,
    val stream: AddonStream,
) {
    val url: String? get() = stream.directPlaybackUrl
    val cached: Boolean get() = stream.cached
    val isTorrent: Boolean get() = stream.isTorrentStream
    val subtitleCount: Int get() = stream.subtitles.size
    val hasRequestHeaders: Boolean get() = stream.requestHeaders.isNotEmpty()

    /**
     * A row that needs the torrent engine to play.
     *
     * **Not the same thing as [isTorrent].** A stream can carry an info hash and
     * still be a plain HTTP URL: the phone plays those directly, without the torrent
     * engine, because the engine is only needed to turn a magnet link into
     * something the player can open. The TV app ships the engine too, so the
     * question it has to answer is narrower: is there a URL the player can open
     * directly, or is this one of the rows the engine has to resolve first?
     */
    val requiresTorrentEngine: Boolean get() = isTorrent && url.isNullOrBlank()

    /**
     * A row the TV app can actually play.
     *
     * Both answers count: a plain URL is ready at once, and a magnet link is ready
     * once the engine has resolved it. A row with neither is not playable at all.
     */
    val isPlayable: Boolean get() = !url.isNullOrBlank() || requiresTorrentEngine
}

data class SourcesUiState(
    val loading: Boolean = true,
    val rows: List<SourceRowUi> = emptyList(),
    val providersResolved: Int = 0,
    val providersWithResults: Int = 0,
    val notice: String? = null,
    /** What the sources were resolved from, kept so the player can offer a change. */
    val lookupMediaTypeName: String? = null,
    val lookupId: String? = null,
    /**
     * The episode the sources were resolved for, when they were resolved for one.
     *
     * **The season and the episode travel beside the lookup id** because a lookup id
     * cannot always carry them: the calendar rows name an episode but no imdb id,
     * so the episode is resolved from the series' episode list and the numbers are
     * what the player needs to file the progress under.
     */
    val season: Int? = null,
    val episode: Int? = null,
) {
    val hasAnyRows: Boolean get() = rows.isNotEmpty()
}

class TvSourcesViewModel(
    app: Application,
    private val itemId: String,
    private val mediaTypeName: String?,
    private val lookupId: String?,
    private val season: Int? = null,
    private val episode: Int? = null,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        resolve()
    }

    /**
     * The viewer's pick, or null with the reason on screen.
     *
     * The pick is a [TvPlaybackSelection] rather than a URL, because that is what the
     * player needs: the provider's stream with its headers and its sidecar
     * subtitles, and the lookup it came from, so "change source" can go back to the
     * picker for the same title. **A magnet link is picked here and resolved in the
     * player**, because resolving it starts a process and can fail, and that is a
     * message the player is much better placed to show.
     */
    fun onSourcePicked(row: SourceRowUi): TvPlaybackSelection? {
        if (!row.isPlayable) {
            _state.value = _state.value.copy(
                notice = "This source has no playable URL.",
            )
            return null
        }
        return TvPlaybackSelection(
            itemId = itemId,
            stream = row.stream,
            lookupMediaTypeName = _state.value.lookupMediaTypeName,
            lookupId = _state.value.lookupId,
            season = _state.value.season,
            episode = _state.value.episode,
        )
    }

    private fun resolve() {
        viewModelScope.launch {
            val target = try {
                resolveLookupTarget()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.value = _state.value.copy(
                    loading = false,
                    notice = t.message ?: "Failed to resolve sources",
                )
                return@launch
            }
            if (target == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    notice = "Could not work out what to look for.",
                )
                return@launch
            }
            _state.value = _state.value.copy(
                lookupMediaTypeName = target.mediaType.name,
                lookupId = target.lookupId,
                season = season ?: _state.value.season,
                episode = episode ?: _state.value.episode,
            )

            try {
                TvServices.streamResolver(getApplication<Application>()).resolve(
                    target = target,
                    onProvidersResolved = { descriptors ->
                        _state.value = _state.value.copy(providersResolved = descriptors.size)
                    },
                    onProviderResult = { result ->
                        appendResult(result.providerId, result.providerName, result.streams)
                    },
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.value = _state.value.copy(
                    loading = false,
                    notice = t.message ?: "Failed to resolve sources",
                )
                return@launch
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    /**
     * What to ask the providers for.
     *
     * A lookup that is already known -- the detail screen knows the episode it wants
     * -- is used as it is. **A lookup that is not known is worked out from the
     * title**, which is what makes "Play" on the home screen work at all: home knows
     * an item id and nothing else.
     *
     * For a series, the episode matters: the providers are asked for one episode, not
     * for the series, so the picker's rows are episodes the viewer can play. The next
     * episode the backend names is the one asked for; failing that, the series itself
     * is asked for, and the provider answers with what it has.
     */
    private suspend fun resolveLookupTarget(): StreamLookupTarget? {
        val knownLookupId = lookupId?.trim()?.takeIf { it.isNotEmpty() }
        if (knownLookupId != null) {
            val mediaType = mediaTypeName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { name ->
                    // The media type can be named either way round: the enum's own
                    // name, which is what the detail screen sends, or its label,
                    // which is what a title's own type says.
                    name.toMetadataLabMediaTypeOrNull()
                        ?: runCatching { MetadataLabMediaType.valueOf(name) }.getOrNull()
                }
                ?: MetadataLabMediaType.MOVIE
            return StreamLookupTarget(mediaType = mediaType, lookupId = knownLookupId)
        }

        val appContext = getApplication<Application>()
        val context = TvServices.contextResolver(appContext).resolve() ?: return null

        // An episode that is known but has no address yet. The calendar rows name a
        // season and an episode but carry no imdb id, and the providers are asked by
        // imdb id -- so the episode's own id is read from the series' episode list.
        // **Without this the picker would ask for the series' next episode** and the
        // viewer's episode would be missed.
        val requestedSeason = season
        val requestedEpisode = episode
        if (requestedSeason != null && requestedEpisode != null) {
            val episodeLookupId = resolveEpisodeLookupId(
                accessToken = context.accessToken,
                seasonNumber = requestedSeason,
                episodeNumber = requestedEpisode,
            )
            if (episodeLookupId != null) {
                return StreamLookupTarget(
                    mediaType = MetadataLabMediaType.SERIES,
                    lookupId = episodeLookupId,
                )
            }
        }

        val detail = TvServices.backendClient(appContext).getMetadataItemDetail(context.accessToken, itemId)
        val item = detail.item
        val isSeries = item.mediaType.equals("show", ignoreCase = true) ||
            item.mediaType.equals("tv", ignoreCase = true) ||
            item.mediaType.equals("series", ignoreCase = true)
        val fallbackType = if (isSeries) MetadataLabMediaType.SERIES else MetadataLabMediaType.MOVIE
        val base = resolveStreamLookupTarget(
            details = item.toMediaDetails(),
            selectedSeason = null,
            seasonEpisodes = emptyList(),
            fallbackMediaType = fallbackType,
        )
        if (!isSeries) return base

        val nextEpisode = detail.nextEpisode
        val episodeLookupId = buildAddonEpisodeLookupId(
            nextEpisode?.providerIds?.imdb,
            nextEpisode?.parent?.seasonNumber,
            nextEpisode?.parent?.episodeNumber,
        )
        return base.copy(lookupId = episodeLookupId ?: base.lookupId)
    }

    /**
     * The lookup id of one episode of a series.
     *
     * The episode list is the only place an episode's own imdb id is written down,
     * and the episode is matched on its season and its number -- **not** on its
     * position in the list, because a list that is short one episode would then
     * name the wrong one.
     *
     * A series that cannot be listed is not a failure: the picker falls back to the
     * series itself, and the viewer picks from what the providers offer.
     */
    private suspend fun resolveEpisodeLookupId(
        accessToken: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): String? {
        return try {
            val episodes = TvServices.backendClient(getApplication<Application>())
                .getSeriesEpisodes(accessToken, seriesItemId = itemId, season = seasonNumber)
                .items
            val match = episodes.firstOrNull { candidate ->
                candidate.parent?.seasonNumber == seasonNumber &&
                    candidate.parent?.episodeNumber == episodeNumber
            }
            buildAddonEpisodeLookupId(
                match?.providerIds?.imdb,
                match?.parent?.seasonNumber ?: seasonNumber,
                match?.parent?.episodeNumber ?: episodeNumber,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    private fun appendResult(providerId: String, providerName: String, streams: List<AddonStream>) {
        val mapped = streams.mapIndexed { index, stream ->
            SourceRowUi(
                id = "$providerId-$index-${stream.stableKey}",
                providerId = providerId,
                providerName = providerName,
                title = stream.title ?: stream.name ?: "${providerName} stream ${index + 1}",
                description = stream.description,
                streamOrder = index,
                stream = stream,
            )
        }
        val current = _state.value
        _state.value = current.copy(
            rows = TvSourceOrdering.order(current.rows + mapped),
            providersWithResults = current.providersWithResults + 1,
        )
    }
}
