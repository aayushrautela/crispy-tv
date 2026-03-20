package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import okhttp3.Headers

enum class NativePlaybackEngine {
    EXO,
    VLC
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
) {
    val isPlaying: Boolean
        get() = state == NativePlaybackState.PLAYING

    val isBuffering: Boolean
        get() = state == NativePlaybackState.PREPARING || state == NativePlaybackState.BUFFERING
}

data class PlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
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
}

interface PlaybackSurfaceController {
    fun bindExoPlayerView(playerView: PlayerView)
    fun createVlcSurfaceView(context: Context): SurfaceView
    fun attachVlcSurface(surfaceView: SurfaceView)
}

interface PlaybackController : PlaybackSessionController, PlaybackSurfaceController
