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
import com.crispy.tv.nativeengine.playback.NativePlaybackError
import com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import com.crispy.tv.nativeengine.playback.NativeVideoLayout
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.player.PlaybackIdentity
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
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
    val videoLayout: NativeVideoLayout? = null,
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
    private val playbackController: PlaybackController = PlaybackDependencies.playbackControllerFactory(this.appContext)
    private val mediaSessionManager =
        PlayerMediaSessionManager(
            context = this.appContext,
            playbackController = playbackController,
            restorePlaybackIntent = restorePlaybackIntent,
        )

    private var hasReportedPlaybackStart = false
    private var hasReportedPlaybackStop = false
    private var lastProgressSyncAtElapsedMs = 0L
    private var lastHandledErrorToken: Long? = null

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
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
        syncPlaybackSnapshot(playbackController.snapshot())
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
                videoLayout = null,
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
                videoLayout = null,
            )
        }
        requestPlayback(engine = uiState.value.activeEngine)
    }

    private fun onPlaybackMetrics(
        snapshot: NativePlaybackSnapshot,
    ) {
        val sanitizedPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        val sanitizedDurationMs = snapshot.durationMs.coerceAtLeast(0L)

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
            val nextStatusMessage = statusMessage(snapshot)
            val nextErrorMessage = snapshot.error?.message
            val shouldUpdatePlaybackState =
                snapshot.engine != state.activeEngine ||
                    snapshot.isBuffering != state.isBuffering ||
                    snapshot.isPlaying != state.isPlaying ||
                    nextStatusMessage != state.statusMessage ||
                    nextErrorMessage != state.errorMessage ||
                    snapshot.videoLayout != state.videoLayout

            if (!(shouldUpdatePosition || shouldUpdateDuration || shouldUpdatePlaybackState)) {
                state
            } else {
                state.copy(
                    activeEngine = snapshot.engine,
                    isBuffering = snapshot.isBuffering,
                    isPlaying = snapshot.isPlaying,
                    positionMs = sanitizedPositionMs,
                    durationMs = sanitizedDurationMs,
                    stableDurationMs = nextStableDurationMs,
                    statusMessage = nextStatusMessage,
                    errorMessage = nextErrorMessage,
                    videoLayout = snapshot.videoLayout,
                )
            }
        }
    }

    private fun requestPlayback(engine: NativePlaybackEngine) {
        if (playbackUrl.isBlank()) {
            return
        }

        lastHandledErrorToken = null

        Log.d(
            TAG,
            "play request engine=$engine playbackUrlHash=${playbackUrl.hashCode()}",
        )
        playbackController.play(playbackUrl, engine)
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    private suspend fun pollPlaybackState() {
        while (currentCoroutineContext().isActive) {
            val snapshot = playbackController.snapshot()

            if (maybeHandlePlaybackError(snapshot.error, snapshot.engine)) {
                delay(250)
                continue
            }

            playbackMetrics.positionMs = snapshot.positionMs
            playbackMetrics.durationMs = snapshot.durationMs

            onPlaybackMetrics(snapshot)

            val uiStateSnapshot = uiState.value
            mediaSessionManager.updatePlayback(
                title = uiStateSnapshot.title,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                isPlaying = uiStateSnapshot.isPlaying,
                isBuffering = uiStateSnapshot.isBuffering,
                positionMs = uiStateSnapshot.positionMs,
                durationMs = uiStateSnapshot.durationMs,
            )

            syncWatchHistory(
                positionMs = uiStateSnapshot.positionMs,
                durationMs = uiStateSnapshot.durationMs,
                isPlaying = uiStateSnapshot.isPlaying,
            )

            delay(250)
        }
    }

    private fun maybeHandlePlaybackError(
        error: NativePlaybackError?,
        engine: NativePlaybackEngine,
    ): Boolean {
        if (error == null || lastHandledErrorToken == error.token) {
            return false
        }

        lastHandledErrorToken = error.token
        val shouldFallback = error.codecLikely && engine == NativePlaybackEngine.EXO
        if (!shouldFallback) {
            return false
        }

        Log.w(TAG, "Codec issue detected in EXO, retrying with VLC. message=${error.message}")
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
                videoLayout = null,
            )
        }
        requestPlayback(engine = NativePlaybackEngine.VLC)
        return true
    }

    private fun syncPlaybackSnapshot(snapshot: NativePlaybackSnapshot) {
        if (maybeHandlePlaybackError(snapshot.error, snapshot.engine)) {
            return
        }
        onPlaybackMetrics(snapshot)
    }

    private fun statusMessage(snapshot: NativePlaybackSnapshot): String {
        return when (snapshot.state) {
            NativePlaybackState.IDLE,
            NativePlaybackState.PREPARING -> "Preparing playback..."
            NativePlaybackState.BUFFERING -> "Buffering..."
            NativePlaybackState.PLAYING -> "Playing"
            NativePlaybackState.PAUSED -> "Paused"
            NativePlaybackState.ENDED -> "Playback ended."
            NativePlaybackState.ERROR -> snapshot.error?.message ?: "Playback error"
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
        backgroundScope.cancel()
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
