package com.crispy.rewrite.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.domain.player.PlayerAction
import com.crispy.rewrite.domain.player.PlayerIntent
import com.crispy.rewrite.domain.player.PlayerState
import com.crispy.rewrite.domain.player.initialPlayerState
import com.crispy.rewrite.domain.player.reducePlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlaybackEngine {
    EXO,
    VLC
}

data class PlaybackLabUiState(
    val sampleVideoUrl: String = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4",
    val magnetInput: String = "",
    val statusMessage: String = "Ready. Play sample video or paste a magnet.",
    val playbackUrl: String? = null,
    val playbackRequestVersion: Long = 0,
    val activeEngine: PlaybackEngine = PlaybackEngine.EXO,
    val playerState: PlayerState = initialPlayerState(
        sessionId = "playback-lab",
        nowMs = System.currentTimeMillis()
    ),
    val isPreparingSamplePlayback: Boolean = false,
    val isPreparingTorrentPlayback: Boolean = false,
    val isBuffering: Boolean = false,
    val metadataInputId: String = "tmdb:1399:1:2",
    val metadataPreferredAddonId: String = "",
    val metadataMediaType: MetadataLabMediaType = MetadataLabMediaType.SERIES,
    val metadataStatusMessage: String = "Metadata idle. Resolve to apply Nuvio rules.",
    val isResolvingMetadata: Boolean = false,
    val metadataResolution: MetadataLabResolution? = null
)

class PlaybackLabViewModel(
    private val metadataResolver: MetadataLabResolver = DefaultMetadataLabResolver
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaybackLabUiState())
    val uiState: StateFlow<PlaybackLabUiState> = _uiState.asStateFlow()

    companion object {
        fun factory(metadataResolver: MetadataLabResolver): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlaybackLabViewModel(metadataResolver) as T
                }
            }
        }
    }

    fun onPlaySampleRequested() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.OpenHttp(engine = it.activeEngine.toReducerEngine()),
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Loading sample video.",
                playbackUrl = it.sampleVideoUrl,
                playbackRequestVersion = it.playbackRequestVersion + 1,
                activeEngine = nextPlayerState.engine.toPlaybackEngine(),
                playerState = nextPlayerState,
                isPreparingSamplePlayback = true,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onStartTorrentRequested(): String? {
        val magnet = _uiState.value.magnetInput.trim()
        if (!magnet.startsWith("magnet:?")) {
            _uiState.update {
                it.copy(
                    statusMessage = "Invalid magnet URI. It must start with magnet:?",
                    isPreparingTorrentPlayback = false
                )
            }
            return null
        }

        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.OpenTorrent(engine = it.activeEngine.toReducerEngine()),
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Starting torrent session.",
                activeEngine = nextPlayerState.engine.toPlaybackEngine(),
                playerState = nextPlayerState,
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = true,
                isBuffering = false
            )
        }
        return magnet
    }

    fun onTorrentStreamResolved(streamUrl: String) {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.TorrentStreamResolved,
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Torrent stream resolved, loading player.",
                playbackUrl = streamUrl,
                playbackRequestVersion = it.playbackRequestVersion + 1,
                playerState = nextPlayerState,
                isPreparingTorrentPlayback = false,
                isBuffering = true
            )
        }
    }

    fun onNativeReady() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeReady,
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Playback ready (${it.activeEngine.name}).",
                playerState = nextPlayerState,
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onNativeBuffering() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeBuffering,
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = if (it.playerState.intent == PlayerIntent.PAUSE) {
                    "Paused"
                } else {
                    "Buffering"
                },
                playerState = nextPlayerState,
                isBuffering = true
            )
        }
    }

    fun onNativeEnded() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeEnded,
                nowMs = nowMs()
            )
            it.copy(
                statusMessage = "Playback ended.",
                playerState = nextPlayerState,
                isBuffering = false
            )
        }
    }

    fun onNativeCodecError(reason: String) {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeCodecError,
                nowMs = nowMs()
            )
            val nextEngine = nextPlayerState.engine.toPlaybackEngine()
            val shouldRetry = it.playbackUrl != null && nextEngine != it.activeEngine

            it.copy(
                statusMessage = if (shouldRetry) {
                    "Codec issue detected. Falling back to ${nextEngine.name}."
                } else {
                    "Playback error: $reason"
                },
                playerState = nextPlayerState,
                activeEngine = nextEngine,
                playbackRequestVersion = if (shouldRetry) it.playbackRequestVersion + 1 else it.playbackRequestVersion,
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onPlaybackLaunchFailed(reason: String) {
        _uiState.update {
            it.copy(
                statusMessage = "Playback launch failed: $reason",
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onTorrentPlaybackFailed(reason: String) {
        _uiState.update {
            it.copy(
                statusMessage = "Torrent playback failed: $reason",
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onMagnetChanged(value: String) {
        _uiState.update { it.copy(magnetInput = value) }
    }

    fun onEngineSelected(engine: PlaybackEngine) {
        _uiState.update {
            val targetEngine = engine.toReducerEngine()
            val alreadySelected = it.activeEngine == engine && it.playerState.engine.equals(targetEngine, ignoreCase = true)
            if (alreadySelected) {
                return@update it
            }

            val shouldReplayCurrent = it.playbackUrl != null
            it.copy(
                activeEngine = engine,
                playerState = it.playerState.copy(
                    engine = targetEngine,
                    updatedAtMs = nowMs()
                ),
                playbackRequestVersion = if (shouldReplayCurrent) it.playbackRequestVersion + 1 else it.playbackRequestVersion,
                statusMessage = if (shouldReplayCurrent) {
                    "Switched engine to ${engine.name}. Restarting current stream."
                } else {
                    "Selected ${engine.name} engine."
                }
            )
        }
    }

    fun onMetadataInputChanged(value: String) {
        _uiState.update { it.copy(metadataInputId = value) }
    }

    fun onMetadataPreferredAddonChanged(value: String) {
        _uiState.update { it.copy(metadataPreferredAddonId = value) }
    }

    fun onMetadataMediaTypeSelected(mediaType: MetadataLabMediaType) {
        _uiState.update { it.copy(metadataMediaType = mediaType) }
    }

    fun onResolveMetadataRequested() {
        val snapshot = _uiState.value
        val preferredAddonId = snapshot.metadataPreferredAddonId.trim().ifBlank { null }
        val request = MetadataLabRequest(
            rawId = snapshot.metadataInputId,
            mediaType = snapshot.metadataMediaType,
            preferredAddonId = preferredAddonId
        )

        if (request.rawId.trim().isEmpty()) {
            _uiState.update {
                it.copy(
                    metadataStatusMessage = "Metadata ID is required.",
                    isResolvingMetadata = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isResolvingMetadata = true,
                metadataStatusMessage = "Resolving addon-first metadata..."
            )
        }

        viewModelScope.launch {
            runCatching {
                metadataResolver.resolve(request)
            }.onSuccess { resolution ->
                _uiState.update {
                    it.copy(
                        metadataResolution = resolution,
                        isResolvingMetadata = false,
                        metadataStatusMessage =
                            "Metadata resolved. Sources=${resolution.sources.joinToString()}"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isResolvingMetadata = false,
                        metadataStatusMessage = "Metadata resolution failed: ${error.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}

private fun String.toPlaybackEngine(): PlaybackEngine {
    return if (equals("vlc", ignoreCase = true)) PlaybackEngine.VLC else PlaybackEngine.EXO
}

private fun PlaybackEngine.toReducerEngine(): String {
    return when (this) {
        PlaybackEngine.EXO -> "exo"
        PlaybackEngine.VLC -> "vlc"
    }
}
