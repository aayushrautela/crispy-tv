package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import okhttp3.Headers

enum class NativePlaybackEngine {
    EXO,
    MPV
}

enum class NativePlaybackState {
    IDLE,
    PREPARING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

data class NativePlaybackError(
    val token: Long,
    val message: String,
    val codecLikely: Boolean,
)

data class NativeVideoLayout(
    val width: Int,
    val height: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val pixelWidthHeightRatio: Float = 1f,
) {
    fun aspectRatioValue(): Float? {
        val effectiveWidth = visibleWidth.takeIf { it > 0 } ?: width
        val effectiveHeight = visibleHeight.takeIf { it > 0 } ?: height
        if (effectiveWidth <= 0 || effectiveHeight <= 0) {
            return null
        }

        val sanitizedPixelRatio = pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
        return (effectiveWidth.toFloat() * sanitizedPixelRatio) / effectiveHeight.toFloat()
    }
}

data class NativePlaybackSnapshot(
    val engine: NativePlaybackEngine,
    val state: NativePlaybackState = NativePlaybackState.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferingPercent: Float? = null,
    val videoLayout: NativeVideoLayout? = null,
    val error: NativePlaybackError? = null,
    val playbackSpeed: Float = 1f,
    val muted: Boolean = false,
    val audioTracks: List<NativeTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val subtitleTracks: List<NativeTrack> = emptyList(),
    val selectedSubtitleTrackId: String? = null,
    val subtitleDelayMs: Int = 0,
) {
    val isPlaying: Boolean
        get() = state == NativePlaybackState.PLAYING

    val isBuffering: Boolean
        get() = state == NativePlaybackState.PREPARING || state == NativePlaybackState.BUFFERING
}

data class PlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val externalSubtitles: List<PlaybackExternalSubtitle> = emptyList(),
)

data class PlaybackExternalSubtitle(
    val url: String,
    val language: String? = null,
    val name: String? = null,
)

data class NativeTrack(
    val id: String,
    val index: Int,
    val language: String?,
    val title: String?,
    val isExternal: Boolean = false,
)

fun PlaybackSource.toOkHttpHeaders(): Headers {
    val builder = Headers.Builder()
    headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
            builder.add(name, value)
        }
    }
    return builder.build()
}

interface PlaybackSessionController {
    fun play(source: PlaybackSource, engine: NativePlaybackEngine)
    fun setPlaying(isPlaying: Boolean)
    fun snapshot(): NativePlaybackSnapshot
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean)
    fun selectAudioTrack(trackId: String?)
    fun selectSubtitleTrack(trackId: String?)
    fun setExternalSubtitle(subtitle: PlaybackExternalSubtitle?)
    fun setSubtitleDelayMs(delayMs: Int)
}

interface PlaybackSurfaceController {
    fun bindExoPlayerView(playerView: PlayerView)
    fun createMpvSurfaceView(context: Context): SurfaceView
    fun attachMpvSurface(surfaceView: SurfaceView)
}

interface PlaybackController : PlaybackSessionController, PlaybackSurfaceController
