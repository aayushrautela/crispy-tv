package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
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
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import java.util.concurrent.Executors

@UnstableApi
class NativePlaybackController(
    context: Context,
    private val useLibass: Boolean = false,
    private val libassRenderType: LibassRenderType = LibassRenderType.OVERLAY_OPEN_GL,
) : PlaybackController {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(appContext.mainLooper)

    private val exoHttpDataSourceFactory: HttpDataSource.Factory =
        CrispyPlaybackNetworking.createHttpDataSourceFactory()
    private var exoDataSourceFactory: DataSource.Factory =
        PlatformPlaybackDataSourceFactory.create(
            context = appContext,
            baseHttpDataSourceFactory = exoHttpDataSourceFactory,
            defaultResponseHeaders = emptyMap(),
            externalSubtitles = emptyList(),
        )

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
    private var exoPlayer: ExoPlayer = buildExoPlayer(extensionRendererMode, exoDataSourceFactory)
    private val mpvRuntime = MpvPlaybackRuntime(appContext)
    private var currentEngine: NativePlaybackEngine = NativePlaybackEngine.EXO
    private var exoVideoLayout: NativeVideoLayout? = null
    private var exoError: NativePlaybackError? = null
    private var nextExoErrorToken: Long = 1L
    private var exoPlaybackSpeed: Float = 1f
    private var exoMuted: Boolean = false
    private var exoSubtitleDelayMs: Int = 0
    private var lastPlaybackSource: PlaybackSource? = null
    private var lastExoPositionMs: Long = 0L
    private var pendingAudioTrackId: String? = null
    private var pendingSubtitleTrackId: String? = null
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

            override fun onTracksChanged(tracks: Tracks) {
                Log.d(TAG, "Exo onTracksChanged groups=${tracks.groups.size}")
                if (pendingAudioTrackId != null && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    val id = pendingAudioTrackId
                    pendingAudioTrackId = null
                    applyExoTrackOverride(C.TRACK_TYPE_AUDIO, id)
                }
                if (pendingSubtitleTrackId != null && tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                    val id = pendingSubtitleTrackId
                    pendingSubtitleTrackId = null
                    applyExoTrackOverride(C.TRACK_TYPE_TEXT, id)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handleExoPlayerError(error)
            }
        }

    init {
        Log.d(TAG, "init created ExoPlayer with hardened config and MPV runtime")
        exoPlayer.addListener(exoListener)
    }

    private fun buildExoPlayer(rendererMode: Int, dataSourceFactory: DataSource.Factory): ExoPlayer {
        val renderersFactory: RenderersFactory =
            DefaultRenderersFactory(appContext)
                .setExtensionRendererMode(rendererMode)
                .setEnableDecoderFallback(true)

        return if (useLibass) {
            ExoPlayer.Builder(appContext)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .buildWithAssSupportCompat(
                    context = appContext,
                    renderType = libassRenderType.toAssRenderType(),
                    dataSourceFactory = dataSourceFactory,
                    extractorsFactory = extractorsFactory,
                    renderersFactory = renderersFactory,
                ).apply {
                    playWhenReady = true
                }
        } else {
            val mediaSourceFactory =
                DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

            ExoPlayer.Builder(appContext)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    playWhenReady = true
                }
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
                exoHttpDataSourceFactory.setDefaultRequestProperties(source.headers)
                val mediaItem =
                    playbackMediaItemFromUrl(
                        url = url,
                        streamType = source.streamType,
                        externalSubtitles = source.externalSubtitles,
                    )
                exoPlayer.setMediaItem(mediaItem)
                source.externalSubtitles.firstOrNull()?.language?.let { language ->
                    exoPlayer.trackSelectionParameters =
                        exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setPreferredTextLanguage(language)
                            .build()
                }
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                applyPersistedExoSettings()
            }

            NativePlaybackEngine.MPV -> {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                mpvRuntime.play(source)
            }
        }
        lastPlaybackSource = source
    }

    override fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
        when (currentEngine) {
            NativePlaybackEngine.EXO -> {
                exoPlaybackSpeed = safeSpeed
                exoPlayer.setPlaybackSpeed(safeSpeed)
            }
            NativePlaybackEngine.MPV -> mpvRuntime.setPlaybackSpeed(safeSpeed)
        }
    }

    override fun setMuted(muted: Boolean) {
        when (currentEngine) {
            NativePlaybackEngine.EXO -> {
                exoMuted = muted
                exoPlayer.volume = if (muted) 0f else 1f
            }
            NativePlaybackEngine.MPV -> mpvRuntime.setMuted(muted)
        }
    }

    override fun selectAudioTrack(trackId: String?) {
        if (currentEngine != NativePlaybackEngine.EXO) {
            mpvRuntime.selectAudioTrack(trackId)
            return
        }
        pendingAudioTrackId = trackId
        applyExoTrackOverride(trackType = C.TRACK_TYPE_AUDIO, trackId = trackId)
    }

    override fun selectSubtitleTrack(trackId: String?) {
        if (currentEngine != NativePlaybackEngine.EXO) {
            mpvRuntime.selectSubtitleTrack(trackId)
            return
        }
        pendingSubtitleTrackId = trackId
        applyExoTrackOverride(trackType = C.TRACK_TYPE_TEXT, trackId = trackId)
    }

    override fun setExternalSubtitle(subtitle: PlaybackExternalSubtitle?) {
        if (currentEngine != NativePlaybackEngine.EXO) {
            mpvRuntime.setExternalSubtitle(subtitle)
            return
        }
        val source = lastPlaybackSource ?: return
        val resumeMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        exoHttpDataSourceFactory.setDefaultRequestProperties(source.headers)
        val audioIdToPreserve = pendingAudioTrackId ?: selectedExoTrackId(C.TRACK_TYPE_AUDIO)
        if (audioIdToPreserve != null) pendingAudioTrackId = audioIdToPreserve
        pendingSubtitleTrackId = null
        val mediaItem =
            playbackMediaItemFromUrl(
                url = source.url,
                streamType = source.streamType,
                externalSubtitles =
                    listOfNotNull(subtitle) + source.externalSubtitles.distinctBy { it.url },
            )
        exoPlayer.setMediaItem(mediaItem, resumeMs)
        subtitle?.language?.let { language ->
            exoPlayer.trackSelectionParameters =
                exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setPreferredTextLanguage(language)
                    .build()
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        exoSubtitleDelayMs = delayMs
        if (currentEngine == NativePlaybackEngine.MPV) {
            mpvRuntime.setSubtitleDelayMs(delayMs)
        }
    }

    override fun applyResizeMode(mode: PlayerResizeMode) {
        if (currentEngine == NativePlaybackEngine.MPV) {
            mpvRuntime.applyResizeMode(mode)
        }
    }

    private fun applyPersistedExoSettings() {
        if (exoPlaybackSpeed != 1f) {
            exoPlayer.setPlaybackSpeed(exoPlaybackSpeed)
        }
        if (exoMuted) {
            exoPlayer.volume = 0f
        }
    }

    private fun applyExoTrackOverride(trackType: Int, trackId: String?) {
        val groups = exoPlayer.currentTracks.groups
        if (trackId == null) {
            exoPlayer.trackSelectionParameters =
                exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(trackType, true)
                    .build()
            return
        }
        val (groupIndex, formatIndex) = parseTrackId(trackId) ?: return
        val group = groups.getOrNull(groupIndex) ?: return
        if (group.type != trackType) return
        if (formatIndex < 0 || formatIndex >= group.length) return
        if (!group.isTrackSupported(formatIndex)) return
        val override = TrackSelectionOverride(group.mediaTrackGroup, formatIndex)
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, false)
                .setOverrideForType(override)
                .build()
    }

    private fun parseTrackId(trackId: String): Pair<Int, Int>? {
        val separator = trackId.indexOf(':')
        if (separator <= 0 || separator == trackId.length - 1) return null
        val groupIndex = trackId.substring(0, separator).toIntOrNull() ?: return null
        val formatIndex = trackId.substring(separator + 1).toIntOrNull() ?: return null
        return groupIndex to formatIndex
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
        syncLibassOverlay(playerView)
    }

    override fun syncLibassOverlay(playerView: PlayerView) {
        val renderType = libassRenderType
        val containerId = if (renderType == LibassRenderType.OVERLAY_OPEN_GL) {
            currentlyBoundPlayerView?.context?.resources?.getIdentifier(
                "libass_overlay_container_gl",
                "id",
                currentlyBoundPlayerView?.context?.packageName,
            )
        } else {
            currentlyBoundPlayerView?.context?.resources?.getIdentifier(
                "libass_overlay_container",
                "id",
                currentlyBoundPlayerView?.context?.packageName,
            )
        } ?: return

        val overlayContainer = playerView.findViewById<android.widget.FrameLayout>(containerId) ?: return
        val boundPlayerTagId = playerView.context.resources.getIdentifier(
            "libass_overlay_bound_player",
            "id",
            playerView.context.packageName,
        )
        val needsOverlay = useLibass && renderType.usesOverlaySubtitleView
        val boundPlayer = if (boundPlayerTagId != 0) {
            playerView.getTag(boundPlayerTagId) as? ExoPlayer
        } else {
            null
        }
        val hasOverlayChild = hasAssOverlayChild(overlayContainer)

        if (!needsOverlay) {
            if (hasOverlayChild) removeAssOverlayChildren(overlayContainer)
            if (boundPlayer != null && boundPlayerTagId != 0) {
                playerView.setTag(boundPlayerTagId, null)
            }
            return
        }

        val assHandler = exoPlayer.getAssHandlerCompat() ?: return
        if (boundPlayer === exoPlayer && hasOverlayChild) return

        removeAssOverlayChildren(overlayContainer)
        val assSubtitleView = AssSubtitleView(overlayContainer.context, assHandler)
        overlayContainer.addView(
            assSubtitleView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (boundPlayerTagId != 0) {
            playerView.setTag(boundPlayerTagId, exoPlayer)
        }
    }

    private fun hasAssOverlayChild(container: android.widget.FrameLayout): Boolean {
        for (index in 0 until container.childCount) {
            if (container.getChildAt(index) is AssSubtitleView) return true
        }
        return false
    }

    private fun removeAssOverlayChildren(container: android.widget.FrameLayout) {
        for (index in container.childCount - 1 downTo 0) {
            if (container.getChildAt(index) is AssSubtitleView) {
                container.removeViewAt(index)
            }
        }
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
        exoHttpDataSourceFactory.setDefaultRequestProperties(source.headers)
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
        exoPlayer = buildExoPlayer(newMode, exoDataSourceFactory)
        exoPlayer.addListener(listener)
        boundView?.let { it.player = exoPlayer }
        if (source != null) {
            exoHttpDataSourceFactory.setDefaultRequestProperties(source.headers)
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

        val audioTracks = collectExoTracks(C.TRACK_TYPE_AUDIO)
        val subtitleTracks = collectExoTracks(C.TRACK_TYPE_TEXT)
        val selectedAudioId = selectedExoTrackId(C.TRACK_TYPE_AUDIO)
        val selectedSubtitleId = selectedExoTrackId(C.TRACK_TYPE_TEXT)

        return NativePlaybackSnapshot(
            engine = NativePlaybackEngine.EXO,
            state = state,
            playWhenReady = exoPlayer.playWhenReady,
            positionMs = positionMs,
            durationMs = durationMs,
            videoLayout = exoVideoLayout,
            error = exoError,
            playbackSpeed = exoPlayer.playbackParameters.speed.takeIf { it > 0f } ?: 1f,
            muted = exoMuted || exoPlayer.volume == 0f,
            audioTracks = audioTracks,
            selectedAudioTrackId = selectedAudioId,
            subtitleTracks = subtitleTracks,
            selectedSubtitleTrackId = selectedSubtitleId,
            subtitleDelayMs = exoSubtitleDelayMs,
        )
    }

    private fun collectExoTracks(trackType: Int): List<NativeTrack> {
        val groups = exoPlayer.currentTracks.groups
        val tracks = ArrayList<NativeTrack>()
        for ((groupIndex, group) in groups.withIndex()) {
            if (group.type != trackType) continue
            for (formatIndex in 0 until group.length) {
                if (!group.isTrackSupported(formatIndex)) continue
                val format = group.getTrackFormat(formatIndex)
                tracks.add(
                    NativeTrack(
                        id = "$groupIndex:$formatIndex",
                        index = groupIndex,
                        language = format.language?.takeIf { it.isNotBlank() },
                        title = format.label?.takeIf { it.isNotBlank() },
                        isExternal = false,
                    )
                )
            }
        }
        return tracks
    }

    private fun selectedExoTrackId(trackType: Int): String? {
        val groups = exoPlayer.currentTracks.groups
        for ((groupIndex, group) in groups.withIndex()) {
            if (group.type != trackType) continue
            for (formatIndex in 0 until group.length) {
                if (group.isTrackSelected(formatIndex)) {
                    return "$groupIndex:$formatIndex"
                }
            }
        }
        return null
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
