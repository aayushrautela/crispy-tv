package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class NativePlaybackController(
    context: Context,
) : PlaybackController {
    private val appContext = context.applicationContext

    private val httpDataSourceFactory =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)

    private val exoPlayer: ExoPlayer =
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()
            .apply {
                playWhenReady = true
            }
    private val mpvRuntime = MpvPlaybackRuntime(appContext)
    private var currentEngine: NativePlaybackEngine = NativePlaybackEngine.EXO
    private var exoVideoLayout: NativeVideoLayout? = null
    private var exoError: NativePlaybackError? = null
    private var nextExoErrorToken: Long = 1L

    private val exoListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(
                    TAG,
                    "Exo onPlaybackStateChanged state=${playbackStateName(playbackState)} playWhenReady=${exoPlayer.playWhenReady} isPlaying=${exoPlayer.isPlaying} currentPositionMs=${exoPlayer.currentPosition} durationMs=${exoPlayer.duration}",
                )
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

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                exoVideoLayout = videoSize.toNativeVideoLayout()
                Log.d(TAG, "Exo onVideoSizeChanged videoSize=$videoSize layout=$exoVideoLayout")
            }

            override fun onPlayerError(error: PlaybackException) {
                val codecLikely = shouldFallbackToMpv(error)
                exoError =
                    NativePlaybackError(
                        token = nextExoErrorToken++,
                        message = error.message ?: "ExoPlayer error",
                        codecLikely = codecLikely,
                    )
                Log.w(
                    TAG,
                    "Exo onPlayerError code=${error.errorCodeName} message=${error.message} cause=${error.cause?.javaClass?.simpleName} fallbackToMpv=$codecLikely",
                    error,
                )
            }
        }

    init {
        Log.d(TAG, "init created ExoPlayer and MPV runtime")
        exoPlayer.addListener(exoListener)
    }

    @UnstableApi
    override fun play(source: PlaybackSource, engine: NativePlaybackEngine) {
        val url = source.url
        Log.d(
            TAG,
            "play requested engine=$engine previousEngine=$currentEngine url=${debugUrl(url)}",
        )
        currentEngine = engine
        when (engine) {
            NativePlaybackEngine.EXO -> {
                exoError = null
                exoVideoLayout = null
                mpvRuntime.stop()
                httpDataSourceFactory.setDefaultRequestProperties(source.headers)
                exoPlayer.setMediaItem(MediaItem.fromUri(url))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }

            NativePlaybackEngine.MPV -> {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                mpvRuntime.play(source)
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

            NativePlaybackEngine.MPV -> {
                mpvRuntime.setPlaying(isPlaying)
            }
        }
    }

    override fun snapshot(): NativePlaybackSnapshot {
        return when (currentEngine) {
            NativePlaybackEngine.EXO -> currentExoSnapshot()
            NativePlaybackEngine.MPV -> mpvRuntime.snapshot()
        }
    }

    override fun seekTo(positionMs: Long) {
        val clampedPositionMs = positionMs.coerceAtLeast(0L)
        when (currentEngine) {
            NativePlaybackEngine.EXO -> exoPlayer.seekTo(clampedPositionMs)
            NativePlaybackEngine.MPV -> mpvRuntime.seekTo(clampedPositionMs)
        }
    }

    override fun stop() {
        Log.d(TAG, "stop currentEngine=$currentEngine")
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        mpvRuntime.stop()
    }

    override fun release() {
        Log.d(TAG, "release currentEngine=$currentEngine")
        exoPlayer.removeListener(exoListener)
        exoPlayer.release()
        mpvRuntime.release()
    }

    override fun bindExoPlayerView(playerView: PlayerView) {
        Log.d(TAG, "bindExoPlayerView viewHash=${System.identityHashCode(playerView)}")
        playerView.player = exoPlayer
    }

    override fun createMpvSurfaceView(context: Context): SurfaceView {
        Log.d(TAG, "createMpvSurfaceView")
        return mpvRuntime.createSurfaceView(context)
    }

    override fun attachMpvSurface(surfaceView: SurfaceView) {
        Log.d(TAG, "attachMpvSurface viewHash=${System.identityHashCode(surfaceView)}")
        mpvRuntime.attach(surfaceView)
    }

    private fun currentExoSnapshot(): NativePlaybackSnapshot {
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val durationMs = exoDurationMs()
        val state =
            when {
                exoError != null -> NativePlaybackState.ERROR
                exoPlayer.playbackState == Player.STATE_ENDED -> NativePlaybackState.ENDED
                exoPlayer.playbackState == Player.STATE_BUFFERING -> {
                    if (positionMs <= 0L && durationMs <= 0L) {
                        NativePlaybackState.PREPARING
                    } else {
                        NativePlaybackState.BUFFERING
                    }
                }
                exoPlayer.playbackState == Player.STATE_READY -> {
                    if (exoPlayer.isPlaying) {
                        NativePlaybackState.PLAYING
                    } else {
                        NativePlaybackState.PAUSED
                    }
                }
                exoPlayer.currentMediaItem != null || exoPlayer.playWhenReady -> NativePlaybackState.PREPARING
                else -> NativePlaybackState.IDLE
            }

        return NativePlaybackSnapshot(
            engine = NativePlaybackEngine.EXO,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            videoLayout = exoVideoLayout,
            error = exoError,
        )
    }

    private fun exoDurationMs(): Long {
        val duration = exoPlayer.duration
        return when {
            duration == C.TIME_UNSET -> 0L
            duration < 0L -> 0L
            else -> duration
        }
    }

    private fun shouldFallbackToMpv(error: PlaybackException): Boolean {
        val message = error.message.orEmpty().lowercase()
        val causeName = error.cause?.javaClass?.simpleName.orEmpty().lowercase()

        return message.contains("codec") ||
            message.contains("decoder") ||
            causeName.contains("codec") ||
            causeName.contains("decoder")
    }

    private fun debugUrl(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host?.ifBlank { null }
        val scheme = uri.scheme?.ifBlank { null }
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

    private fun VideoSize.toNativeVideoLayout(): NativeVideoLayout? {
        if (width <= 0 || height <= 0) {
            return null
        }

        return NativeVideoLayout(
            width = width,
            height = height,
            visibleWidth = width,
            visibleHeight = height,
            pixelWidthHeightRatio = pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f,
        )
    }

    companion object {
        private const val TAG = "NativePlaybackCtrl"
    }
}
