package com.crispy.tv.playerui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.PlayerView
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackEvent
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.player.PlaybackIdentity
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class PlayerUiState(
    val title: String,
    val activeEngine: NativePlaybackEngine = NativePlaybackEngine.EXO,
    val isBuffering: Boolean = true,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val stableDurationMs: Long = 0L,
    val statusMessage: String = "Preparing playback...",
    val errorMessage: String? = null,
)

class PlayerSessionViewModel(
    appContext: Context,
    private val playbackUrl: String,
    title: String,
    private val subtitle: String?,
    private val artworkUrl: String?,
    private val identity: PlaybackIdentity?,
    restorePlaybackIntent: Intent,
) : ViewModel() {
    private val appContext = appContext.applicationContext
    private val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(this.appContext)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackMetrics = PlaybackMetricsHolder()
    private val _uiState =
        MutableStateFlow(
            PlayerUiState(
                title = title.ifBlank { "Player" },
            )
        )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val playbackController: PlaybackController =
        PlaybackDependencies.playbackControllerFactory(this.appContext, ::onNativePlaybackEvent)
    private val mediaSessionManager =
        PlayerMediaSessionManager(
            context = this.appContext,
            playbackController = playbackController,
            restorePlaybackIntent = restorePlaybackIntent,
        )

    private var hasReportedPlaybackStart = false
    private var hasReportedPlaybackStop = false
    private var lastProgressSyncAtElapsedMs = 0L

    init {
        mediaSessionManager.updateMetadata(
            title = uiState.value.title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
        )
        requestPlayback(engine = uiState.value.activeEngine)
        viewModelScope.launch {
            pollPlaybackState()
        }
    }

    fun bindExoPlayerView(playerView: PlayerView) {
        playbackController.bindExoPlayerView(playerView)
    }

    fun createVlcSurfaceView(context: Context): SurfaceView = playbackController.createVlcSurfaceView(context)

    fun attachVlcSurface(surfaceView: SurfaceView) {
        playbackController.attachVlcSurface(surfaceView)
    }

    fun setPlaying(isPlaying: Boolean) {
        playbackController.setPlaying(isPlaying)
        _uiState.update { state ->
            if (state.isPlaying == isPlaying) {
                state
            } else {
                state.copy(isPlaying = isPlaying)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    private fun onNativeReady() {
        _uiState.update { state ->
            state.copy(
                isBuffering = false,
                statusMessage = "Playing",
                errorMessage = null,
            )
        }
    }

    private fun onNativeBuffering() {
        _uiState.update { state ->
            state.copy(
                isBuffering = true,
                statusMessage = "Buffering...",
                errorMessage = null,
            )
        }
    }

    private fun onNativeEnded() {
        _uiState.update { state ->
            state.copy(
                isBuffering = false,
                isPlaying = false,
                statusMessage = "Playback ended.",
                errorMessage = null,
            )
        }
    }

    private fun onNativeError(message: String, codecLikely: Boolean) {
        val shouldFallback = codecLikely && uiState.value.activeEngine == NativePlaybackEngine.EXO
        if (shouldFallback) {
            _uiState.update { state ->
                state.copy(
                    activeEngine = NativePlaybackEngine.VLC,
                    isBuffering = true,
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    stableDurationMs = 0L,
                    statusMessage = "Codec issue detected, retrying with VLC...",
                    errorMessage = null,
                )
            }
            requestPlayback(engine = NativePlaybackEngine.VLC)
        } else {
            _uiState.update { state ->
                state.copy(
                    isBuffering = false,
                    isPlaying = false,
                    statusMessage = message,
                    errorMessage = message,
                )
            }
        }
    }

    fun onEngineSelected(engine: NativePlaybackEngine) {
        if (uiState.value.activeEngine == engine) {
            return
        }

        _uiState.update { state ->
            state.copy(
                activeEngine = engine,
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "Switching playback engine...",
                errorMessage = null,
            )
        }
        requestPlayback(engine = engine)
    }

    fun retryPlayback() {
        _uiState.update { state ->
            state.copy(
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "Retrying playback...",
                errorMessage = null,
            )
        }
        requestPlayback(engine = uiState.value.activeEngine)
    }

    private fun onPlaybackMetrics(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val sanitizedPositionMs = positionMs.coerceAtLeast(0L)
        val sanitizedDurationMs = durationMs.coerceAtLeast(0L)

        _uiState.update { state ->
            val nextStableDurationMs =
                if (sanitizedDurationMs > 0L) {
                    sanitizedDurationMs
                } else {
                    state.stableDurationMs
                }

            val shouldUpdatePosition = abs(sanitizedPositionMs - state.positionMs) >= 500L
            val shouldUpdateDuration =
                sanitizedDurationMs != state.durationMs || nextStableDurationMs != state.stableDurationMs
            val shouldUpdatePlaying = isPlaying != state.isPlaying

            if (!(shouldUpdatePosition || shouldUpdateDuration || shouldUpdatePlaying)) {
                state
            } else {
                state.copy(
                    isPlaying = isPlaying,
                    positionMs = sanitizedPositionMs,
                    durationMs = sanitizedDurationMs,
                    stableDurationMs = nextStableDurationMs,
                )
            }
        }
    }

    private fun onNativePlaybackEvent(event: NativePlaybackEvent) {
        when (event) {
            NativePlaybackEvent.Buffering -> onNativeBuffering()
            NativePlaybackEvent.Ready -> onNativeReady()
            NativePlaybackEvent.Ended -> onNativeEnded()
            is NativePlaybackEvent.Error -> onNativeError(event.message, codecLikely = event.codecLikely)
        }
    }

    private fun requestPlayback(engine: NativePlaybackEngine) {
        if (playbackUrl.isBlank()) {
            return
        }

        Log.d(
            TAG,
            "play request engine=$engine playbackUrlHash=${playbackUrl.hashCode()}",
        )
        playbackController.play(playbackUrl, engine)
    }

    private suspend fun pollPlaybackState() {
        while (isActive) {
            val positionMs = playbackController.currentPositionMs()
            val durationMs = playbackController.durationMs()
            val isPlaying = playbackController.isPlaying()

            playbackMetrics.positionMs = positionMs
            playbackMetrics.durationMs = durationMs

            onPlaybackMetrics(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            )

            val snapshot = uiState.value
            mediaSessionManager.updatePlayback(
                title = snapshot.title,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                isPlaying = isPlaying,
                isBuffering = snapshot.isBuffering,
                positionMs = positionMs,
                durationMs = durationMs,
            )

            syncWatchHistory(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            )

            delay(250)
        }
    }

    private fun syncWatchHistory(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val playbackIdentity = identity ?: return
        if (durationMs <= 0L) {
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (!hasReportedPlaybackStart && isPlaying) {
            hasReportedPlaybackStart = true
            lastProgressSyncAtElapsedMs = nowElapsedMs
            backgroundScope.launch {
                watchHistoryService.onPlaybackStarted(
                    identity = playbackIdentity,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            }
            return
        }

        if (!hasReportedPlaybackStart || nowElapsedMs - lastProgressSyncAtElapsedMs < PROGRESS_SYNC_INTERVAL_MS) {
            return
        }

        lastProgressSyncAtElapsedMs = nowElapsedMs
        backgroundScope.launch {
            watchHistoryService.onPlaybackProgress(
                identity = playbackIdentity,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            )
        }
    }

    private fun reportPlaybackStopped() {
        if (hasReportedPlaybackStop) {
            return
        }
        hasReportedPlaybackStop = true

        val playbackIdentity = identity ?: return
        val lastDurationMs = playbackMetrics.durationMs
        if (lastDurationMs <= 0L) {
            return
        }

        backgroundScope.launch {
            watchHistoryService.onPlaybackStopped(
                identity = playbackIdentity,
                positionMs = playbackMetrics.positionMs,
                durationMs = lastDurationMs,
            )
        }
    }

    override fun onCleared() {
        reportPlaybackStopped()
        mediaSessionManager.release()
        playbackController.release()
        super.onCleared()
    }

    companion object {
        fun factory(
            appContext: Context,
            playbackUrl: String,
            title: String,
            subtitle: String?,
            artworkUrl: String?,
            identity: PlaybackIdentity?,
            restorePlaybackIntent: Intent,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerSessionViewModel(
                        appContext = appContext,
                        playbackUrl = playbackUrl,
                        title = title,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
                        identity = identity,
                        restorePlaybackIntent = restorePlaybackIntent,
                    ) as T
                }
            }
        }
    }
}

private class PlaybackMetricsHolder {
    var positionMs: Long = 0L
    var durationMs: Long = 0L
}

private const val TAG = "PlayerSessionViewModel"
private const val PROGRESS_SYNC_INTERVAL_MS = 5_000L
