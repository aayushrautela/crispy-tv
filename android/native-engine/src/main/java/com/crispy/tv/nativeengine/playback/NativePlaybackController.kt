package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sidecar =
        ExoSidecarSubtitleController(
            scope = controllerScope,
            getPlayer = { exoPlayer },
            getSubtitleDelayMs = { exoSubtitleDelayMs },
        )
    private var currentEngine: NativePlaybackEngine = NativePlaybackEngine.EXO
    private var exoVideoLayout: NativeVideoLayout? = null
    private var exoError: NativePlaybackError? = null
    private var nextExoErrorToken: Long = 1L
    private var exoPlaybackSpeed: Float = 1f
    private var exoMuted: Boolean = false
    private var exoSubtitleDelayMs: Int = 0
    private var lastPlaybackSource: PlaybackSource? = null
    private var activeExternalSubtitle: PlaybackExternalSubtitle? = null
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
                tracks.groups.forEachIndexed { groupIndex, group ->
                    for (formatIndex in 0 until group.length) {
                        val format = group.getTrackFormat(formatIndex)
                        Log.d(
                            TAG,
                            "Exo track group=$groupIndex type=${trackTypeName(group.type)}" +
                                " index=$formatIndex id=${format.id} language=${format.language}" +
                                " label=${format.label} selected=${group.isTrackSelected(formatIndex)}",
                        )
                    }
                }
                if (pendingAudioTrackId != null && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    val id = pendingAudioTrackId
                    if (applyExoTrackOverride(C.TRACK_TYPE_AUDIO, id)) {
                        pendingAudioTrackId = null
                    }
                }
                if (pendingSubtitleTrackId != null && tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                    val id = pendingSubtitleTrackId
                    if (applyExoTrackOverride(C.TRACK_TYPE_TEXT, id)) {
                        pendingSubtitleTrackId = null
                    }
                }
                Log.d(
                    TAG,
                    "Exo selection after tracks change audioId=${selectedExoTrackId(C.TRACK_TYPE_AUDIO)}" +
                        " subtitleId=${selectedExoTrackId(C.TRACK_TYPE_TEXT)}",
                )
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
        // A replay of the same media (retry, decoder escalation) keeps the user-chosen
        // external subtitle; a different media starts fresh without it.
        val sameMedia = lastPlaybackSource?.url == source.url
        val carriedExternal = activeExternalSubtitle?.takeIf { sameMedia }
        if (!sameMedia) {
            activeExternalSubtitle = null
        }
        val effectiveSource =
            carriedExternal
                ?.let { carried ->
                    source.copy(
                        externalSubtitles =
                            (listOfNotNull(carried) + source.externalSubtitles).distinctBy { it.url },
                    )
                }
                ?: source
        currentEngine = engine
        when (engine) {
            NativePlaybackEngine.EXO -> {
                exoError = null
                exoVideoLayout = null
                probeAttempted = false
                decoderPriorityEscalated = false
                lastExoPositionMs = 0L
                mpvRuntime.stop()
                exoHttpDataSourceFactory.setDefaultRequestProperties(effectiveSource.headers)
                // Only SSA and TTML travel in the media item. SubRip and WebVTT
                // attach after prepare through the sidecar, with no rebuild, so
                // the URL-guessed MIME that used to mislabel them is no longer
                // on this path at all.
                val mediaSubtitles = mediaItemSubtitlesOf(effectiveSource.externalSubtitles)
                val mediaItem =
                    playbackMediaItemFromUrl(
                        url = effectiveSource.url,
                        streamType = effectiveSource.streamType,
                        externalSubtitles = mediaSubtitles,
                    )
                exoPlayer.setMediaItem(mediaItem)
                pendingSubtitleTrackId =
                    carriedExternal?.takeIf { it in mediaSubtitles }?.let { externalSubtitleTrackId(it.url) }
                        ?: mediaSubtitles.firstOrNull()?.let { externalSubtitleTrackId(it.url) }
                val sidecarCandidate =
                    effectiveSource.externalSubtitles.firstOrNull { it !in mediaSubtitles }
                if (sidecarCandidate != null) {
                    // The sidecar owns selection from here: a stale pending id
                    // must not linger into the engine override, and no embedded
                    // track may win over the attachment.
                    activeExternalSubtitle = sidecarCandidate
                    pendingSubtitleTrackId = null
                    setExoTextTracksDisabled(true)
                } else {
                    setExoTextTracksDisabled(false)
                }
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                applyPersistedExoSettings()
                sidecarCandidate?.let { sidecar.start(it) }
            }

            NativePlaybackEngine.MPV -> {
                sidecar.stop()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                mpvRuntime.play(effectiveSource)
            }
        }
        lastPlaybackSource = effectiveSource
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
        Log.d(TAG, "selectAudioTrack engine=$currentEngine id=$trackId")
        if (currentEngine != NativePlaybackEngine.EXO) {
            mpvRuntime.selectAudioTrack(trackId)
            return
        }
        pendingAudioTrackId = trackId
        applyExoTrackOverride(trackType = C.TRACK_TYPE_AUDIO, trackId = trackId)
    }

    override fun selectSubtitleTrack(trackId: String?) {
        Log.d(TAG, "selectSubtitleTrack engine=$currentEngine id=$trackId")
        if (currentEngine != NativePlaybackEngine.EXO) {
            mpvRuntime.selectSubtitleTrack(trackId)
            return
        }
        if (trackId != null && trackId == sidecar.activeTrackId) {
            // Re-pick of the sidecar attachment: already rendering. Only make
            // sure the engine text path stays out of its way.
            pendingSubtitleTrackId = null
            setExoTextTracksDisabled(true)
            return
        }
        if (sidecar.isActive()) {
            sidecar.stop()
        }
        pendingSubtitleTrackId = trackId
        applyExoTrackOverride(trackType = C.TRACK_TYPE_TEXT, trackId = trackId)
    }

    override fun setExternalSubtitle(subtitle: PlaybackExternalSubtitle?) {
        Log.d(TAG, "setExternalSubtitle engine=$currentEngine url=${subtitle?.url != null}")
        activeExternalSubtitle = subtitle
        if (currentEngine != NativePlaybackEngine.EXO) {
            mpvRuntime.setExternalSubtitle(subtitle)
            return
        }
        if (subtitle == null) {
            // Clearing the attachment is a selection change, not a media
            // change: stop the sidecar and disable the engine text path.
            sidecar.stop()
            pendingSubtitleTrackId = null
            applyExoTrackOverride(trackType = C.TRACK_TYPE_TEXT, trackId = null)
            return
        }
        if (sidecar.start(subtitle)) {
            // SubRip/WebVTT attach with no rebuild and no rebuffer. The pick
            // leads the source list so the MPV fallback and the next play()
            // carry it, and the snapshot reports it as selected immediately,
            // while the body is still downloading.
            val source = lastPlaybackSource
            if (source != null) {
                lastPlaybackSource =
                    source.copy(
                        externalSubtitles = (listOf(subtitle) + source.externalSubtitles).distinctBy { it.url },
                    )
            }
            pendingSubtitleTrackId = null
            setExoTextTracksDisabled(true)
            return
        }
        // SSA and TTML stay on the media item -- the one rebuild the Exo path
        // keeps. It replaces the media-item subtitle set instead of appending,
        // so dead picks cannot accumulate into ever-longer track groups.
        val source = lastPlaybackSource ?: return
        val resumeMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        exoHttpDataSourceFactory.setDefaultRequestProperties(source.headers)
        val audioIdToPreserve = pendingAudioTrackId ?: selectedExoTrackId(C.TRACK_TYPE_AUDIO)
        if (audioIdToPreserve != null) pendingAudioTrackId = audioIdToPreserve
        sidecar.stop()
        pendingSubtitleTrackId = externalSubtitleTrackId(subtitle.url)
        val mergedExternals = (listOf(subtitle) + source.externalSubtitles).distinctBy { it.url }
        lastPlaybackSource = source.copy(externalSubtitles = mergedExternals)
        val mediaItem =
            playbackMediaItemFromUrl(
                url = source.url,
                streamType = source.streamType,
                externalSubtitles = mediaItemSubtitlesOf(mergedExternals),
            )
        exoPlayer.setMediaItem(mediaItem, resumeMs)
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

    private fun applyExoTrackOverride(trackType: Int, trackId: String?): Boolean {
        val groups = exoPlayer.currentTracks.groups
        if (trackId == null) {
            Log.d(TAG, "applyExoTrackOverride type=${trackTypeName(trackType)} action=disable")
            exoPlayer.trackSelectionParameters =
                exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(trackType, true)
                    .build()
            return true
        }
        if (trackId.startsWith(EXTERNAL_SUBTITLE_TRACK_ID_PREFIX)) {
            val target = findExoTrackByStableId(trackType, trackId)
            if (target == null) {
                Log.d(TAG, "applyExoTrackOverride type=${trackTypeName(trackType)} id=$trackId rejected=external-group-missing")
                return false
            }
            applyExoOverride(target.first, target.second)
            return true
        }
        val (groupIndex, formatIndex) = parseTrackId(trackId) ?: run {
            Log.d(TAG, "applyExoTrackOverride type=${trackTypeName(trackType)} id=$trackId rejected=unparseable-id")
            return false
        }
        val group = groups.getOrNull(groupIndex) ?: run {
            Log.d(TAG, "applyExoTrackOverride type=${trackTypeName(trackType)} id=$trackId rejected=group-missing")
            return false
        }
        if (group.type != trackType) {
            Log.d(TAG, "applyExoTrackOverride id=$trackId rejected=type-mismatch group=${trackTypeName(group.type)}")
            return false
        }
        if (formatIndex < 0 || formatIndex >= group.length) {
            Log.d(TAG, "applyExoTrackOverride type=${trackTypeName(trackType)} id=$trackId rejected=index-out-of-bounds")
            return false
        }
        if (!group.isTrackSupported(formatIndex)) {
            Log.d(TAG, "applyExoTrackOverride type=${trackTypeName(trackType)} id=$trackId rejected=unsupported")
            return false
        }
        applyExoOverride(group, formatIndex)
        return true
    }

    private fun applyExoOverride(group: Tracks.Group, formatIndex: Int) {
        val override = TrackSelectionOverride(group.mediaTrackGroup, formatIndex)
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(group.type, false)
                .setOverrideForType(override)
                .build()
        Log.d(
            TAG,
            "applyExoTrackOverride type=${trackTypeName(group.type)} group=${group.mediaTrackGroup}" +
                " index=$formatIndex applied language=${group.getTrackFormat(formatIndex).language}",
        )
    }

    // Media3's MergingMediaSource namespaces side-loaded subtitle format ids as
    // "<childIndex>:<formatId>" (child index in the merged source), so the ext:<hash>
    // marker set in PlaybackMediaItems survives only as the id's suffix. Canonicalize
    // at this single boundary so every consumer compares/reports the plain ext:<hash>
    // id regardless of how many merge layers prefixed it.
    private fun externalFormatId(format: Format): String? {
        val rawId = format.id ?: return null
        if (!rawId.contains(EXTERNAL_SUBTITLE_TRACK_ID_PREFIX)) return null
        return EXTERNAL_SUBTITLE_TRACK_ID_PREFIX + rawId.substringAfter(EXTERNAL_SUBTITLE_TRACK_ID_PREFIX)
    }

    private fun findExoTrackByStableId(trackType: Int, stableId: String): Pair<Tracks.Group, Int>? {
        for (group in exoPlayer.currentTracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (externalFormatId(group.getTrackFormat(i)) == stableId) return group to i
            }
        }
        return null
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
        sidecar.stop()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        mpvRuntime.stop()
    }

    override fun release() {
        Log.d(TAG, "release currentEngine=$currentEngine")
        controllerScope.cancel()
        probeExecutor.shutdownNow()
        exoPlayer.removeListener(exoListener)
        exoPlayer.release()
        mpvRuntime.release()
    }

    override fun bindExoPlayerView(playerView: PlayerView) {
        Log.d(TAG, "bindExoPlayerView viewHash=${System.identityHashCode(playerView)}")
        playerView.player = exoPlayer
        currentlyBoundPlayerView = playerView
        sidecar.bindSubtitleView(playerView.subtitleView)
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
        // Retries rebuild the media item but never the sidecar: it renders
        // independently of the media pipeline and survives the rebuild. Only
        // the media-item formats travel here.
        val mediaSubtitles = mediaItemSubtitlesOf(source.externalSubtitles)
        val mediaItem =
            playbackMediaItemFromUrl(
                url = source.url,
                streamType = source.streamType,
                mimeTypeOverride = mimeType,
                externalSubtitles = mediaSubtitles,
            )
        exoPlayer.setMediaItem(mediaItem)
        pendingSubtitleTrackId =
            activeExternalSubtitle?.takeIf { it in mediaSubtitles }?.let { externalSubtitleTrackId(it.url) }
                ?: mediaSubtitles.firstOrNull()?.let { externalSubtitleTrackId(it.url) }
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
            val mediaSubtitles = mediaItemSubtitlesOf(source.externalSubtitles)
            val mediaItem =
                playbackMediaItemFromUrl(
                    url = source.url,
                    streamType = source.streamType,
                    mimeTypeOverride = null,
                    externalSubtitles = mediaSubtitles,
                )
            exoPlayer.setMediaItem(mediaItem)
            pendingSubtitleTrackId =
                activeExternalSubtitle?.takeIf { it in mediaSubtitles }?.let { externalSubtitleTrackId(it.url) }
                    ?: mediaSubtitles.firstOrNull()?.let { externalSubtitleTrackId(it.url) }
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
        val engineSubtitleTracks = collectExoTracks(C.TRACK_TYPE_TEXT)
        val sidecarTrack = sidecarTrack()
        val subtitleTracks =
            if (sidecarTrack != null) engineSubtitleTracks + sidecarTrack else engineSubtitleTracks
        val selectedAudioId = selectedExoTrackId(C.TRACK_TYPE_AUDIO)
        val selectedSubtitleId = sidecar.activeTrackId ?: selectedExoTrackId(C.TRACK_TYPE_TEXT)

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
                val stableId = externalFormatId(format)
                tracks.add(
                    NativeTrack(
                        id = stableId ?: "$groupIndex:$formatIndex",
                        index = groupIndex,
                        language = format.language?.takeIf { it.isNotBlank() },
                        title = format.label?.takeIf { it.isNotBlank() },
                        isExternal = stableId != null,
                        // Both are optional data and neither is set for every
                        // audio format: `channelCount` is `Format.NO_VALUE` until
                        // the extractor has read the track, and an audio `Format`
                        // usually carries a MIME type ("audio/eac3") where a
                        // video one carries `codecs`. MIME first because it is
                        // the half that is actually populated, and the display
                        // strips the "audio/" prefix.
                        codec = format.sampleMimeType?.takeIf { it.isNotBlank() } ?: format.codecs?.takeIf { it.isNotBlank() },
                        channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
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
                    return externalFormatId(group.getTrackFormat(formatIndex)) ?: "$groupIndex:$formatIndex"
                }
            }
        }
        return null
    }

    /**
     * The subtitle subset that travels inside the media item. Only the formats
     * the engine pipeline itself renders: SSA/ASS (libass when on, Exo's own
     * SSA decoder when off) and TTML. SubRip and WebVTT are owned by the
     * sidecar and must never appear here -- that was the mislabelled-MIME
     * failure and the per-pick rebuild.
     */
    private fun mediaItemSubtitlesOf(subtitles: List<PlaybackExternalSubtitle>): List<PlaybackExternalSubtitle> =
        subtitles.filter {
            when (subtitleFormatFromUrl(it.url)) {
                SubtitleContainerFormat.SubStationAlpha,
                SubtitleContainerFormat.Ttml,
                -> true
                SubtitleContainerFormat.SubRip,
                SubtitleContainerFormat.WebVtt,
                -> false
            }
        }

    private fun setExoTextTracksDisabled(disabled: Boolean) {
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
                .build()
    }

    /**
     * The sidecar attachment as an engine-style track, so the sheet lists the
     * picked subtitle with a checkmark while the body is still downloading.
     * Identity is the same `ext:<hash>` the media-item path used, which keeps
     * the sheet's catalog filter working unchanged.
     */
    private fun sidecarTrack(): NativeTrack? {
        val url = sidecar.activeSubtitleUrl ?: return null
        val subtitle = activeExternalSubtitle?.takeIf { it.url == url } ?: return null
        return NativeTrack(
            id = externalSubtitleTrackId(url),
            index = -1,
            language = subtitle.language?.takeIf { it.isNotBlank() },
            title = subtitle.name?.takeIf { it.isNotBlank() },
            isExternal = true,
        )
    }

    private fun trackTypeName(trackType: Int): String =
        when (trackType) {
            C.TRACK_TYPE_AUDIO -> "AUDIO"
            C.TRACK_TYPE_VIDEO -> "VIDEO"
            C.TRACK_TYPE_TEXT -> "TEXT"
            else -> "type=$trackType"
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
