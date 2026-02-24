package com.crispy.tv.playerui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlayerUiState(
    val playbackUrl: String,
    val title: String,
    val activeEngine: NativePlaybackEngine = NativePlaybackEngine.EXO,
    val playbackRequestVersion: Long = 1L,
    val isBuffering: Boolean = true,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val stableDurationMs: Long = 0L,
    val statusMessage: String = "Preparing playback...",
    val errorMessage: String? = null,
)

class PlayerViewModel(
    playbackUrl: String,
    title: String,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            PlayerUiState(
                playbackUrl = playbackUrl,
                title = title.ifBlank { "Player" },
            )
        )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    fun onNativeReady() {
        _uiState.update { state ->
            state.copy(
                isBuffering = false,
                statusMessage = "Playing",
                errorMessage = null,
            )
        }
    }

    fun onNativeBuffering() {
        _uiState.update { state ->
            state.copy(
                isBuffering = true,
                statusMessage = "Buffering...",
                errorMessage = null,
            )
        }
    }

    fun onNativeEnded() {
        _uiState.update { state ->
            state.copy(
                isBuffering = false,
                isPlaying = false,
                statusMessage = "Playback ended.",
                errorMessage = null,
            )
        }
    }

    fun onNativeError(message: String, codecLikely: Boolean) {
        _uiState.update { state ->
            val shouldFallback = codecLikely && state.activeEngine == NativePlaybackEngine.EXO
            if (shouldFallback) {
                state.copy(
                    activeEngine = NativePlaybackEngine.VLC,
                    playbackRequestVersion = state.playbackRequestVersion + 1,
                    isBuffering = true,
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    stableDurationMs = 0L,
                    statusMessage = "Codec issue detected, retrying with VLC...",
                    errorMessage = null,
                )
            } else {
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
        _uiState.update { state ->
            if (state.activeEngine == engine) {
                state
            } else {
                state.copy(
                    activeEngine = engine,
                    playbackRequestVersion = state.playbackRequestVersion + 1,
                    isBuffering = true,
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    stableDurationMs = 0L,
                    statusMessage = "Switching playback engine...",
                    errorMessage = null,
                )
            }
        }
    }

    fun retryPlayback() {
        _uiState.update { state ->
            state.copy(
                playbackRequestVersion = state.playbackRequestVersion + 1,
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "Retrying playback...",
                errorMessage = null,
            )
        }
    }

    fun onPlaybackMetrics(
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

    fun onUserSetPlaying(isPlaying: Boolean) {
        _uiState.update { state ->
            if (state.isPlaying == isPlaying) {
                state
            } else {
                state.copy(isPlaying = isPlaying)
            }
        }
    }

    companion object {
        fun factory(playbackUrl: String, title: String): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerViewModel(playbackUrl = playbackUrl, title = title) as T
                }
            }
        }
    }
}
