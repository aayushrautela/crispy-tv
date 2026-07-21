package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

@UnstableApi
class NativePlaybackController(
    context: Context,
) : PlaybackController {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(appContext.mainLooper)

    private val httpDataSourceFactory =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)

    private val extractorsFactory =
        DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

    private val trackSelector =
        DefaultTrackSelector(appContext).apply {
            setParameters(
                buildUponParameters()
                    .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true),
            )
        }

    private val loadControl: LoadControl =
        DefaultLoadControl.Builder()
            .setTargetBufferBytes(100 * 1024 * 1024)
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 70_000,
                /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .build()

    private var currentlyBoundPlayerView: PlayerView? = null

    private var extensionRendererMode: Int = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    private var exoPlayer: ExoPlayer = buildExoPlayer(extensionRendererMode)
    private val mpvRuntime = MpvPlaybackRuntime(appContext)
    private var currentEngine: NativePlaybackEngine = NativePlaybackEngine.EXO
    private var exoVideoLayout: NativeVideoLayout? = null
    private var exoError: NativePlaybackError? = null
    private var nextExoErrorToken: Long = 1L

    private var lastPlaybackSource: PlaybackSource? = null
    private var lastExoPositionMs: Long = 0L
    private var probeAttempted: Boolean = false
    private var decoderPriorityEscalated: Boolean = false
    private val probeExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "crispy-exo-probe").apply { isDaemon = true }
        }

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
                handleExoPlayerError(error)
            }
        }

    init {
        Log.d(TAG, "init created ExoPlayer with hardened config and MPV runtime")
        exoPlayer.addListener(exoListener)
    }

    private fun buildExoPlayer(rendererMode: Int): ExoPlayer {
        val renderersFactory: RenderersFactory =
            DefaultRenderersFactory(appContext)
                .setExtensionRendererMode(rendererMode)
                .setEnableDecoderFallback(true)

        val mediaSourceFactory =
            DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)

        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
            }
    }

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
                probeAttempted = false
                decoderPriorityEscalated = false
                lastExoPositionMs = 0L
                mpvRuntime.stop()
                httpDataSourceFactory.setDefaultRequestProperties(source.headers)
                val mediaItem = playbackMediaItemFromUrl(url = url, streamType = source.streamType)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }

            NativePlaybackEngine.MPV -> {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                mpvRuntime.play(source)
            }
        }
        lastPlaybackSource = source
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
        probeExecutor.shutdownNow()
        exoPlayer.removeListener(exoListener)
        exoPlayer.release()
        mpvRuntime.release()
    }

    override fun bindExoPlayerView(playerView: PlayerView) {
        Log.d(TAG, "bindExoPlayerView viewHash=${System.identityHashCode(playerView)}")
        playerView.player = exoPlayer
        currentlyBoundPlayerView = playerView
    }

    override fun createMpvSurfaceView(context: Context): SurfaceView {
        Log.d(TAG, "createMpvSurfaceView")
        return mpvRuntime.createSurfaceView(context)
    }

    override fun attachMpvSurface(surfaceView: SurfaceView) {
        Log.d(TAG, "attachMpvSurface viewHash=${System.identityHashCode(surfaceView)}")
        mpvRuntime.attach(surfaceView)
    }

    private fun handleExoPlayerError(error: PlaybackException) {
        Log.w(
            TAG,
            "Exo onPlayerError code=${error.errorCodeName} isDecoder=${error.isDecoderFailure()} isSource=${error.isSourceError()} isDrm=${error.isDrmError()} cause=${error.cause?.javaClass?.simpleName}",
            error,
        )
        lastExoPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)

        if (error.isSourceError() && !probeAttempted) {
            probeAttempted = true
            val source = lastPlaybackSource
            if (source == null) {
                surfaceError(error)
                return
            }
            Log.d(TAG, "Source error; launching MIME probe before retry")
            probeExecutor.execute {
                val probed = probeMimeType(source.url, source.headers)
                mainHandler.post {
                    if (probed != null) {
                        Log.d(TAG, "Probe succeeded mimeType=$probed; retrying with inferred MIME")
                        retryExoWithMimeType(source, probed)
                    } else {
                        Log.d(TAG, "Probe returned no MIME; surfacing error / fallback")
                        surfaceError(error)
                    }
                }
            }
            return
        }

        if (error.isDecoderFailure() && !decoderPriorityEscalated) {
            decoderPriorityEscalated = true
            if (extensionRendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) {
                Log.d(TAG, "Decoder failure but priority already PREFER; surfacing for fallback")
                surfaceError(error)
                return
            }
            Log.w(TAG, "Decoder failure; escalating to EXTENSION_RENDERER_MODE_PREFER and retrying")
            retryExoWithEscalatedDecoderPriority()
            return
        }

        surfaceError(error)
    }

    private fun retryExoWithMimeType(
        source: PlaybackSource,
        mimeType: String,
    ) {
        exoError = null
        httpDataSourceFactory.setDefaultRequestProperties(source.headers)
        val mediaItem =
            playbackMediaItemFromUrl(
                url = source.url,
                streamType = source.streamType,
                mimeTypeOverride = mimeType,
            )
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        if (lastExoPositionMs > 0L) {
            exoPlayer.seekTo(lastExoPositionMs)
        }
        exoPlayer.playWhenReady = true
    }

    private fun retryExoWithEscalatedDecoderPriority() {
        val newMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        extensionRendererMode = newMode
        val listener = exoListener
        val source = lastPlaybackSource
        val resumeMs = lastExoPositionMs
        val boundView = currentlyBoundPlayerView
        exoError = null
        exoVideoLayout = null
        lastExoPositionMs = 0L
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        exoPlayer = buildExoPlayer(newMode)
        exoPlayer.addListener(listener)
        boundView?.let { it.player = exoPlayer }
        if (source != null) {
            httpDataSourceFactory.setDefaultRequestProperties(source.headers)
            val mediaItem =
                playbackMediaItemFromUrl(
                    url = source.url,
                    streamType = source.streamType,
                    mimeTypeOverride = null,
                )
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            if (resumeMs > 0L) {
                exoPlayer.seekTo(resumeMs)
            }
            exoPlayer.playWhenReady = true
        }
        Log.d(TAG, "ExoPlayer rebuilt with PREFER extension renderers and same source")
    }

    private fun surfaceError(error: PlaybackException) {
        exoError =
            NativePlaybackError(
                token = nextExoErrorToken++,
                message = error.message ?: "ExoPlayer error",
                codecLikely = shouldFallbackToMpv(error),
            )
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
        if (error.isDrmError()) {
            return false
        }
        return true
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
