package com.crispy.rewrite.nativeengine.playback

import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class NativePlaybackController(
    context: Context,
    private val onEvent: (NativePlaybackEvent) -> Unit
) : PlaybackController {
    private val appContext = context.applicationContext
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        playWhenReady = true
    }
    private val vlcRuntime = VlcPlaybackRuntime(appContext, onEvent)

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> onEvent(NativePlaybackEvent.Buffering)
                Player.STATE_READY -> onEvent(NativePlaybackEvent.Ready)
                Player.STATE_ENDED -> onEvent(NativePlaybackEvent.Ended)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            onEvent(
                NativePlaybackEvent.Error(
                    message = error.message ?: "ExoPlayer error",
                    codecLikely = shouldFallbackToVlc(error)
                )
            )
        }
    }

    init {
        exoPlayer.addListener(exoListener)
    }

    override fun play(url: String, engine: NativePlaybackEngine) {
        when (engine) {
            NativePlaybackEngine.EXO -> {
                vlcRuntime.stop()
                exoPlayer.setMediaItem(MediaItem.fromUri(url))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }

            NativePlaybackEngine.VLC -> {
                exoPlayer.stop()
                vlcRuntime.play(url)
            }
        }
    }

    override fun stop() {
        runCatching { exoPlayer.stop() }
        runCatching { vlcRuntime.stop() }
    }

    override fun release() {
        runCatching { exoPlayer.removeListener(exoListener) }
        runCatching { exoPlayer.release() }
        runCatching { vlcRuntime.release() }
    }

    override fun bindExoPlayerView(playerView: PlayerView) {
        playerView.player = exoPlayer
    }

    override fun createVlcSurfaceView(context: Context): SurfaceView {
        return vlcRuntime.createSurfaceView(context)
    }

    override fun attachVlcSurface(surfaceView: SurfaceView) {
        vlcRuntime.attach(surfaceView)
    }

    private fun shouldFallbackToVlc(error: PlaybackException): Boolean {
        val message = error.message.orEmpty().lowercase()
        val causeName = error.cause?.javaClass?.simpleName.orEmpty().lowercase()

        return message.contains("codec") ||
            message.contains("decoder") ||
            causeName.contains("codec") ||
            causeName.contains("decoder")
    }
}
