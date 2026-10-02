package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import okhttp3.Headers

fun PlaybackSource.toOkHttpHeaders(): Headers {
    val builder = Headers.Builder()
    headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
            builder.add(name, value)
        }
    }
    return builder.build()
}

interface PlaybackSurfaceController {
    fun bindExoPlayerView(playerView: PlayerView)
    fun createMpvSurfaceView(context: Context): SurfaceView
    fun attachMpvSurface(surfaceView: SurfaceView)
    fun syncLibassOverlay(playerView: PlayerView)
}

interface PlaybackController : PlaybackSessionController, PlaybackSurfaceController
