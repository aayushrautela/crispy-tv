package com.crispy.tv.tv.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.tv.di.TvServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SourceRowUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val title: String,
    val description: String? = null,
    val url: String?,
    val cached: Boolean = false,
    val isTorrent: Boolean = false,
)

data class SourcesUiState(
    val loading: Boolean = true,
    val rows: List<SourceRowUi> = emptyList(),
    val providersResolved: Int = 0,
    val providersWithResults: Int = 0,
    val notice: String? = null,
) {
    val hasAnyRows: Boolean get() = rows.isNotEmpty()
}

class TvSourcesViewModel(
    app: Application,
    private val mediaTypeName: String,
    private val lookupId: String,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        resolve()
    }

    fun onSourcePicked(row: SourceRowUi): Boolean {
        if (row.isTorrent) {
            _state.value = _state.value.copy(
                notice = "Torrent playback arrives with the engine integration.",
            )
            return false
        }
        if (row.url.isNullOrBlank()) {
            _state.value = _state.value.copy(notice = "This source has no playable URL.")
            return false
        }
        return true
    }

    private fun resolve() {
        viewModelScope.launch {
            val mediaType = runCatching {
                MetadataLabMediaType.valueOf(mediaTypeName)
            }.getOrDefault(MetadataLabMediaType.MOVIE)

            try {
                TvServices.streamResolver(getApplication<Application>()).resolve(
                    target = StreamLookupTarget(mediaType = mediaType, lookupId = lookupId),
                    onProvidersResolved = { descriptors ->
                        _state.value = _state.value.copy(providersResolved = descriptors.size)
                    },
                    onProviderResult = { result ->
                        appendResult(result.providerId, result.providerName, result.streams)
                    },
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(
                    loading = false,
                    notice = t.message ?: "Failed to resolve sources",
                )
                return@launch
            }
            _state.value = _state.value.copy(loading = false)
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
                url = stream.directPlaybackUrl,
                cached = stream.cached,
                isTorrent = stream.isTorrentStream,
            )
        }
        _state.value = _state.value.copy(
            rows = _state.value.rows + mapped,
            providersWithResults = _state.value.providersWithResults + 1,
        )
    }
}
