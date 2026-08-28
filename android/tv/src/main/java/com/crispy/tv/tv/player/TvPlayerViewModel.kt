package com.crispy.tv.tv.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.crispy.tv.backend.PlaybackEventInput
import com.crispy.tv.tv.di.TvServices
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TvPlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val hasSource: Boolean = false,
    val error: String? = null,
) {
    val episodeLabel: String?
        get() = if (seasonNumber != null && episodeNumber != null) {
            "S$seasonNumber E$episodeNumber"
        } else {
            null
        }
}

class TvPlayerViewModel(
    app: Application,
    private val itemId: String,
    private val streamUrl: String?,
) : AndroidViewModel(app) {

    val player: ExoPlayer = ExoPlayer.Builder(app.applicationContext).build()

    private val _state = MutableStateFlow(TvPlayerUiState())
    val state: StateFlow<TvPlayerUiState> = _state.asStateFlow()

    private var lastReportedPositionSec: Long = -1

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) reportProgress(snapshot = true)
                }
            },
        )
        viewModelScope.launch { prepare() }
    }

    fun playPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekBy(seconds: Long) {
        val target = (player.currentPosition + seconds * 1000L).coerceIn(0L, player.duration)
        if (player.duration > 0) {
            player.seekTo(target)
        }
    }

    fun onHostResumed() {
        reportProgress(snapshot = false)
    }

    fun onHostPaused() {
        player.pause()
        reportProgress(snapshot = true)
    }

    override fun onCleared() {
        runCatching { reportProgress(snapshot = true, completed = true) }
        player.release()
        super.onCleared()
    }

    private suspend fun prepare() {
        val appContext = getApplication<Application>()
        val context = TvServices.contextResolver(appContext).resolve()
        if (context == null) {
            _state.value = TvPlayerUiState(loading = false, error = "Not signed in")
            return
        }
        try {
            val resolved = TvServices.backendClient(appContext).resolvePlayback(
                accessToken = context.accessToken,
                input = com.crispy.tv.backend.ItemLookupInput(itemId = itemId),
            )
            val item = resolved.item
            _state.value = TvPlayerUiState(
                loading = false,
                title = item.title ?: "Now playing",
                seasonNumber = item.parent?.seasonNumber,
                episodeNumber = item.parent?.episodeNumber,
                hasSource = !streamUrl.isNullOrBlank(),
                error = if (streamUrl.isNullOrBlank()) {
                    "No stream source yet — source selection arrives with the addon integration."
                } else {
                    null
                },
            )
            if (!streamUrl.isNullOrBlank()) {
                player.setMediaItem(MediaItem.fromUri(streamUrl))
                player.prepare()
                player.playWhenReady = true
                reportProgress(snapshot = false)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.value = TvPlayerUiState(loading = false, error = t.message ?: "Playback failed")
        }
    }

    private fun reportProgress(snapshot: Boolean, completed: Boolean = false) {
        if (streamUrl.isNullOrBlank()) return
        if (_state.value.error != null || !_state.value.hasSource) return

        val positionSec = player.currentPosition / 1000L
        val durationSec = if (player.duration > 0) player.duration / 1000L else 0L
        if (!completed && positionSec == lastReportedPositionSec) return
        lastReportedPositionSec = positionSec

        val eventType = when {
            completed && durationSec > 0 && positionSec >= durationSec - 30 -> "playback_completed"
            snapshot -> "playback_progress_snapshot"
            else -> "playback_progress"
        }

        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val context = TvServices.contextResolver(appContext).resolve() ?: return@launch
            runCatching {
                TvServices.backendClient(appContext).sendWatchEvent(
                    accessToken = context.accessToken,
                    profileId = context.profileId,
                    input = PlaybackEventInput(
                        clientEventId = "$eventType:${UUID.randomUUID()}",
                        eventType = eventType,
                        itemId = itemId,
                        positionSeconds = positionSec.toDouble(),
                        durationSeconds = durationSec.takeIf { it > 0 }?.toDouble(),
                        seasonNumber = _state.value.seasonNumber,
                        episodeNumber = _state.value.episodeNumber,
                    ),
                )
            }
        }
    }
}
