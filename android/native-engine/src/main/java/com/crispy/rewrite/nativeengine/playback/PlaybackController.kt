package com.crispy.rewrite.nativeengine.playback

import android.content.Context
import android.view.SurfaceView
import androidx.media3.ui.PlayerView

enum class NativePlaybackEngine {
    EXO,
    VLC
}

sealed interface NativePlaybackEvent {
    data object Buffering : NativePlaybackEvent
    data object Ready : NativePlaybackEvent
    data object Ended : NativePlaybackEvent
    data class Error(
        val message: String,
        val codecLikely: Boolean
    ) : NativePlaybackEvent
}

interface PlaybackController {
    fun play(url: String, engine: NativePlaybackEngine)
    fun seekTo(positionMs: Long)
    fun currentPositionMs(): Long
    fun stop()
    fun release()
    fun bindExoPlayerView(playerView: PlayerView)
    fun createVlcSurfaceView(context: Context): SurfaceView
    fun attachVlcSurface(surfaceView: SurfaceView)
}
