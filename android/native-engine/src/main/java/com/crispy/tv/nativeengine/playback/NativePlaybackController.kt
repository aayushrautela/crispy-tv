package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class NativePlaybackController(
    context: Context,
    private val onEvent: (NativePlaybackEvent) -> Unit
) : PlaybackController {
    private val appContext = context.applicationContext

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
        .build()
        .apply {
            playWhenReady = true
        }
    private val vlcRuntime = VlcPlaybackRuntime(appContext, onEvent)
    private var currentEngine: NativePlaybackEngine = NativePlaybackEngine.EXO

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(
                TAG,
                "Exo onPlaybackStateChanged state=${playbackStateName(playbackState)} playWhenReady=${exoPlayer.playWhenReady} isPlaying=${exoPlayer.isPlaying} currentPositionMs=${exoPlayer.currentPosition} durationMs=${exoPlayer.duration}",
            )
            when (playbackState) {
                Player.STATE_BUFFERING -> onEvent(NativePlaybackEvent.Buffering)
                Player.STATE_READY -> onEvent(NativePlaybackEvent.Ready)
                Player.STATE_ENDED -> onEvent(NativePlaybackEvent.Ended)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "Exo onIsPlayingChanged isPlaying=$isPlaying")
        }

        override fun onPlayWhenReadyChanged(
            playWhenReady: Boolean,
            reason: Int,
        ) {
            Log.d(
                TAG,
                "Exo onPlayWhenReadyChanged playWhenReady=$playWhenReady reason=$reason",
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(
                TAG,
                "Exo onPlayerError code=${error.errorCodeName} message=${error.message} cause=${error.cause?.javaClass?.simpleName} fallbackToVlc=${shouldFallbackToVlc(error)}",
                error,
            )
            onEvent(
                NativePlaybackEvent.Error(
                    message = error.message ?: "ExoPlayer error",
                    codecLikely = shouldFallbackToVlc(error)
                )
            )
        }
    }

    init {
        Log.d(TAG, "init created ExoPlayer and VLC runtime")
        exoPlayer.addListener(exoListener)
    }

    override fun play(url: String, engine: NativePlaybackEngine) {
        Log.d(
            TAG,
            "play requested engine=$engine previousEngine=$currentEngine url=${debugUrl(url)}",
        )
        currentEngine = engine
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

    override fun setPlaying(isPlaying: Boolean) {
        Log.d(TAG, "setPlaying isPlaying=$isPlaying engine=$currentEngine")
        when (currentEngine) {
            NativePlaybackEngine.EXO -> {
                if (isPlaying) {
                    exoPlayer.play()
                } else {
                    exoPlayer.pause()
                }
            }

            NativePlaybackEngine.VLC -> {
                vlcRuntime.setPlaying(isPlaying)
            }
        }
    }

    override fun isPlaying(): Boolean {
        return when (currentEngine) {
            NativePlaybackEngine.EXO ->
                exoPlayer.playWhenReady && exoPlayer.playbackState != Player.STATE_ENDED
            NativePlaybackEngine.VLC -> vlcRuntime.isPlaying()
        }
    }

    override fun seekTo(positionMs: Long) {
        val clampedPositionMs = positionMs.coerceAtLeast(0L)
        when (currentEngine) {
            NativePlaybackEngine.EXO -> exoPlayer.seekTo(clampedPositionMs)
            NativePlaybackEngine.VLC -> vlcRuntime.seekTo(clampedPositionMs)
        }
    }

    override fun currentPositionMs(): Long {
        return when (currentEngine) {
            NativePlaybackEngine.EXO -> exoPlayer.currentPosition.coerceAtLeast(0L)
            NativePlaybackEngine.VLC -> vlcRuntime.currentPositionMs()
        }
    }

    override fun durationMs(): Long {
        return when (currentEngine) {
            NativePlaybackEngine.EXO -> {
                val duration = exoPlayer.duration
                when {
                    duration == C.TIME_UNSET -> 0L
                    duration < 0L -> 0L
                    else -> duration
                }
            }

            NativePlaybackEngine.VLC -> vlcRuntime.durationMs()
        }
    }

    override fun stop() {
        Log.d(TAG, "stop currentEngine=$currentEngine")
        runCatching { exoPlayer.stop() }
        runCatching { vlcRuntime.stop() }
    }

    override fun release() {
        Log.d(TAG, "release currentEngine=$currentEngine")
        runCatching { exoPlayer.removeListener(exoListener) }
        runCatching { exoPlayer.release() }
        runCatching { vlcRuntime.release() }
    }

    override fun bindExoPlayerView(playerView: PlayerView) {
        Log.d(TAG, "bindExoPlayerView viewHash=${System.identityHashCode(playerView)}")
        playerView.player = exoPlayer
    }

    override fun createVlcSurfaceView(context: Context): SurfaceView {
        Log.d(TAG, "createVlcSurfaceView")
        return vlcRuntime.createSurfaceView(context)
    }

    override fun attachVlcSurface(surfaceView: SurfaceView) {
        Log.d(TAG, "attachVlcSurface viewHash=${System.identityHashCode(surfaceView)}")
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

    private fun debugUrl(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host?.ifBlank { null }
        val scheme = uri?.scheme?.ifBlank { null }
        return buildString {
            append("hash=")
            append(url.hashCode())
            if (scheme != null || host != null) {
                append(" scheme=")
                append(scheme ?: "unknown")
                append(" host=")
                append(host ?: "unknown")
            }
        }
    }

    private fun playbackStateName(playbackState: Int): String {
        return when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> playbackState.toString()
        }
    }

    companion object {
        private const val TAG = "NativePlaybackCtrl"
    }
}
