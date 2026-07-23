package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

internal class MpvPlaybackRuntime(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var mpv: MPVLib? = null
    private var created: Boolean = false
    private var surfaceView: SurfaceView? = null
    private var surfaceAttached: Boolean = false
    private var pendingSource: PlaybackSource? = null
    private var playRequested: Boolean = false
    private var state: NativePlaybackState = NativePlaybackState.IDLE
    private var videoLayout: NativeVideoLayout? = null
    private var error: NativePlaybackError? = null
    private var nextErrorToken: Long = 1L
    private var lastBufferingPercent: Float? = null
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L
    private var playbackSpeed: Float = 1f
    private var muted: Boolean = false
    private var subtitleDelayMs: Int = 0
    private var audioTracks: List<NativeTrack> = emptyList()
    private var selectedAudioTrackId: String? = null
    private var subtitleTracks: List<NativeTrack> = emptyList()
    private var selectedSubtitleTrackId: String? = null
    private var externalSubtitleUrl: String? = null
    private var hasStartedPlayback: Boolean = false
    private var lastProgressAdvanceAtElapsedMs: Long = 0L
    private var lastObservedPositionMs: Long = 0L

    private val eventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "cache-buffering-state" -> {
                    lastBufferingPercent = value.toFloat().coerceIn(0f, 100f)
                    if (state != NativePlaybackState.ERROR && state != NativePlaybackState.ENDED) {
                        state = when {
                            value in 1..99 -> NativePlaybackState.BUFFERING
                            value == 100L -> NativePlaybackState.PLAYING
                            else -> state
                        }
                    }
                }
                "aid" -> {
                    if (value <= 0L) {
                        selectedAudioTrackId = null
                    } else {
                        selectedAudioTrackId = value.toString()
                        val current = mpv
                        if (current != null && audioTracks.none { it.id == value.toString() }) {
                            audioTracks = readTrackList(current, "audio")
                        }
                    }
                }
                "sid" -> {
                    if (value <= 0L) {
                        selectedSubtitleTrackId = null
                    } else {
                        selectedSubtitleTrackId = value.toString()
                        val current = mpv
                        if (current != null && subtitleTracks.none { it.id == value.toString() }) {
                            subtitleTracks = readTrackList(current, "sub")
                        }
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> {
                    positionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                    lastProgressAdvanceAtElapsedMs = SystemClock.elapsedRealtime()
                    lastObservedPositionMs = positionMs
                }
                "duration" -> durationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                "speed" -> playbackSpeed = value.toFloat()
                "sub-delay" -> subtitleDelayMs = (value * 1000.0).toInt()
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> {
                    if (state != NativePlaybackState.ERROR && state != NativePlaybackState.ENDED) {
                        state = if (value) NativePlaybackState.PAUSED else NativePlaybackState.PLAYING
                    }
                }
                "mute" -> muted = value
                "eof-reached" -> {
                    if (value) {
                        playRequested = false
                        state = NativePlaybackState.ENDED
                        lastBufferingPercent = 100f
                    }
                }
                "paused-for-cache", "seeking" -> {
                    if (value && state != NativePlaybackState.ERROR && state != NativePlaybackState.ENDED) {
                        state = NativePlaybackState.BUFFERING
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: String) = Unit

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    state = NativePlaybackState.PREPARING
                    error = null
                    lastBufferingPercent = 0f
                }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    if (playRequested) {
                        hasStartedPlayback = true
                        state = NativePlaybackState.PLAYING
                        lastBufferingPercent = 100f
                        lastProgressAdvanceAtElapsedMs = SystemClock.elapsedRealtime()
                    }
                    refreshTrackLists()
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    if (state != NativePlaybackState.ENDED && state != NativePlaybackState.ERROR) {
                        state = NativePlaybackState.IDLE
                    }
                }
            }
        }
    }

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceCreated")
            runCatching {
                mpv?.attachSurface(holder.surface)
                mpv?.setOptionString("force-window", "yes")
            }
            surfaceAttached = true
            pendingSource?.let { pending ->
                pendingSource = null
                startPlayback(pending)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(TAG, "surfaceChanged size=${width}x$height")
            runCatching { mpv?.setPropertyString("android-surface-size", "${width}x$height") }
            if (width > 0 && height > 0) {
                videoLayout = NativeVideoLayout(
                    width = width,
                    height = height,
                    visibleWidth = width,
                    visibleHeight = height,
                    pixelWidthHeightRatio = 1f,
                )
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "surfaceDestroyed")
            runCatching {
                mpv?.setPropertyString("vo", "null")
                mpv?.setPropertyString("force-window", "no")
                mpv?.detachSurface()
            }
            surfaceAttached = false
        }
    }

    fun createSurfaceView(viewContext: Context): SurfaceView {
        if (!created) {
            initialize(viewContext)
        }
        val created = SurfaceView(viewContext)
        Log.d(TAG, "createSurfaceView viewHash=${System.identityHashCode(created)}")
        surfaceView = created
        created.holder.addCallback(surfaceHolderCallback)
        return created
    }

    fun attach(view: SurfaceView) {
        if (surfaceView === view && surfaceAttached) {
            return
        }
        Log.d(TAG, "attach replacingViewHash=${surfaceView?.let { System.identityHashCode(it) }} pendingUrl=${pendingSource?.url?.hashCode()}")
        if (!created) {
            initialize(view.context)
        }
        val previous = surfaceView
        if (previous !== view && previous != null) {
            previous.holder.removeCallback(surfaceHolderCallback)
        }
        surfaceView = view
        view.holder.addCallback(surfaceHolderCallback)
    }

    private fun initialize(context: Context) {
        val appCtx = context.applicationContext
        val instance = MPVLib.create(appCtx)
        if (instance == null) {
            error = NativePlaybackError(
                token = nextErrorToken++,
                message = "libmpv unavailable",
                codecLikely = false,
            )
            state = NativePlaybackState.ERROR
            Log.e(TAG, "MPVLib.create returned null")
            return
        }
        mpv = instance
        runCatching {
            instance.setOptionString("config", "no")
            instance.setOptionString("profile", "fast")
            instance.setOptionString("hwdec", "auto")
            instance.setOptionString("msg-level", "all=warn")
            instance.setOptionString("demuxer-max-bytes", "$DEMUXER_MAX_BYTES")
            instance.setOptionString("demuxer-max-back-bytes", "$DEMUXER_MAX_BYTES")
            instance.setOptionString("vd-lavc-film-grain", "cpu")
            instance.setOptionString("force-window", "no")
            instance.setOptionString("idle", "once")
            instance.init()
            instance.setPropertyBoolean("keep-open", true)
            instance.setPropertyBoolean("audio-fallback-to-null", true)
            instance.addObserver(eventObserver)
            observeProperties(instance)
        }.onFailure { e ->
            Log.e(TAG, "libmpv init failed", e)
            error = NativePlaybackError(
                token = nextErrorToken++,
                message = e.localizedMessage ?: "libmpv init failed",
                codecLikely = false,
            )
            state = NativePlaybackState.ERROR
        }
        created = true
        Log.d(TAG, "initialize configDir=${appCtx.filesDir.path}")
    }

    private fun observeProperties(mpv: MPVLib) {
        val props = mapOf(
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "mute" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "speed" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "sub-delay" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "aid" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "sid" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
        )
        props.forEach { (name, format) -> mpv.observeProperty(name, format) }
    }

    fun play(source: PlaybackSource) {
        resetPlaybackStateForNewMedia()
        playRequested = true
        state = NativePlaybackState.PREPARING
        val url = source.url
        Log.d(TAG, "play surfaceAttached=$surfaceAttached url=${debugUrl(url)}")
        if (!surfaceAttached) {
            pendingSource = source
            Log.d(TAG, "play deferred until surface attach url=${debugUrl(url)}")
            return
        }
        startPlayback(source)
    }

    fun setPlaying(isPlaying: Boolean) {
        Log.d(TAG, "setPlaying isPlaying=$isPlaying playRequested=$playRequested")
        playRequested = isPlaying
        runCatching { mpv?.setPropertyBoolean("pause", !isPlaying) }
    }

    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) return
        runCatching {
            mpv?.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
        }
        lastProgressAdvanceAtElapsedMs = SystemClock.elapsedRealtime()
        lastObservedPositionMs = positionMs
    }

    fun stop() {
        Log.d(TAG, "stop surfaceAttached=$surfaceAttached")
        pendingSource = null
        playRequested = false
        runCatching { mpv?.command(arrayOf("stop")) }
        state = NativePlaybackState.IDLE
        lastBufferingPercent = null
    }

    fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
        playbackSpeed = safeSpeed
        runCatching { mpv?.setPropertyDouble("speed", safeSpeed.toDouble()) }
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        runCatching { mpv?.setPropertyBoolean("mute", muted) }
    }

    fun selectAudioTrack(trackId: String?) {
        if (trackId == null) {
            runCatching { mpv?.setPropertyInt("aid", 0) }
            selectedAudioTrackId = null
            return
        }
        val id = trackId.toIntOrNull() ?: return
        runCatching { mpv?.setPropertyInt("aid", id) }
        selectedAudioTrackId = trackId
    }

    fun selectSubtitleTrack(trackId: String?) {
        if (trackId == null) {
            runCatching { mpv?.setPropertyString("sid", "no") }
            selectedSubtitleTrackId = null
            return
        }
        val id = trackId.toIntOrNull() ?: return
        runCatching { mpv?.setPropertyInt("sid", id) }
        selectedSubtitleTrackId = trackId
    }

    fun setExternalSubtitle(subtitle: PlaybackExternalSubtitle?) {
        val mpv = mpv
        if (mpv == null) return
        runCatching {
            removeExternalSubtitleTracks(mpv)
            externalSubtitleUrl = subtitle?.url
            if (subtitle != null) {
                mpv.command(arrayOf("sub-add", subtitle.url, "select"))
            }
        }
    }

    private fun removeExternalSubtitleTracks(mpv: MPVLib) {
        val count = runCatching { mpv.getPropertyString("track-list/count") }.getOrNull()?.toIntOrNull() ?: return
        if (count <= 0) return
        for (i in 0 until count) {
            val type = runCatching { mpv.getPropertyString("track-list/$i/type") }.getOrNull() ?: continue
            if (type != "sub") continue
            val isExternal = runCatching { mpv.getPropertyString("track-list/$i/external") }.getOrNull().equals("yes", true)
            if (!isExternal) continue
            val id = runCatching { mpv.getPropertyString("track-list/$i/id") }.getOrNull()?.toIntOrNull() ?: continue
            runCatching { mpv.command(arrayOf("sub-remove", id.toString())) }
        }
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        subtitleDelayMs = delayMs
        runCatching { mpv?.setPropertyDouble("sub-delay", delayMs / 1000.0) }
    }

    private fun refreshTrackLists() {
        val mpv = mpv ?: return
        audioTracks = readTrackList(mpv, "audio")
        subtitleTracks = readTrackList(mpv, "sub")
        val aid = runCatching { mpv.getPropertyString("aid") }.getOrNull()
        selectedAudioTrackId = aid?.takeIf { it.isNotBlank() && it != "0" }
        val sid = runCatching { mpv.getPropertyString("sid") }.getOrNull()
        selectedSubtitleTrackId = sid?.takeIf { it.isNotBlank() && it != "0" }
    }

    private fun readTrackList(mpv: MPVLib, trackType: String): List<NativeTrack> {
        val count = runCatching { mpv.getPropertyString("track-list/count") }.getOrNull()?.toIntOrNull() ?: 0
        if (count <= 0) return emptyList()
        val tracks = ArrayList<NativeTrack>(count)
        for (i in 0 until count) {
            val type = runCatching { mpv.getPropertyString("track-list/$i/type") }.getOrNull() ?: continue
            if (type != trackType) continue
            val id = runCatching { mpv.getPropertyString("track-list/$i/id") }.getOrNull()?.toIntOrNull() ?: continue
            val lang = runCatching { mpv.getPropertyString("track-list/$i/lang") }.getOrNull()?.takeIf { it.isNotBlank() }
            val title = runCatching { mpv.getPropertyString("track-list/$i/title") }.getOrNull()?.takeIf { it.isNotBlank() }
            val external = runCatching { mpv.getPropertyString("track-list/$i/external") }.getOrNull().equals("yes", true)
            tracks.add(
                NativeTrack(
                    id = id.toString(),
                    index = id,
                    language = lang,
                    title = title,
                    isExternal = external,
                )
            )
        }
        return tracks
    }

    fun snapshot(): NativePlaybackSnapshot {
        val normalizedState = normalizePlaybackState()
        return NativePlaybackSnapshot(
            engine = NativePlaybackEngine.MPV,
            state = normalizedState,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferingPercent = lastBufferingPercent,
            videoLayout = videoLayout,
            error = error,
            playbackSpeed = playbackSpeed,
            muted = muted,
            audioTracks = audioTracks,
            selectedAudioTrackId = selectedAudioTrackId,
            subtitleTracks = subtitleTracks,
            selectedSubtitleTrackId = selectedSubtitleTrackId,
            subtitleDelayMs = subtitleDelayMs,
        )
    }

    fun release() {
        Log.d(TAG, "release surfaceAttached=$surfaceAttached created=$created")
        runCatching {
            surfaceView?.holder?.removeCallback(surfaceHolderCallback)
            mpv?.removeObserver(eventObserver)
            mpv?.detachSurface()
            mpv?.destroy()
        }
        mpv = null
        created = false
        surfaceAttached = false
        surfaceView = null
        pendingSource = null
    }

    private fun startPlayback(source: PlaybackSource) {
        applyRequestHeaders(source.headers)
        val url = source.url
        Log.d(TAG, "startPlayback url=${debugUrl(url)} surfaceAttached=$surfaceAttached")
        runCatching {
            mpv?.setPropertyBoolean("pause", !playRequested)
            mpv?.command(arrayOf("loadfile", url, "replace"))
        }
        if (source.externalSubtitles.isNotEmpty()) {
            val mpv = mpv
            if (mpv != null) {
                source.externalSubtitles.forEach { subtitle ->
                    runCatching {
                        mpv.command(arrayOf("sub-add", subtitle.url, "select"))
                    }
                }
                externalSubtitleUrl = source.externalSubtitles.firstOrNull()?.url
            }
        }
    }

    private fun applyRequestHeaders(headers: Map<String, String>) {
        val mpv = mpv ?: return
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (!userAgent.isNullOrBlank()) {
            runCatching { mpv.setPropertyString("user-agent", userAgent) }
        }
        val serialized = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .map { (key, value) -> "${key}: ${value.replace(",", "\\,")}" }
            .joinToString(",")
        if (serialized.isNotBlank()) {
            runCatching { mpv.setPropertyString("http-header-fields", serialized) }
        }
    }

    private fun normalizePlaybackState(): NativePlaybackState {
        if (error != null) return NativePlaybackState.ERROR
        if (state == NativePlaybackState.ENDED) return NativePlaybackState.ENDED

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (positionMs > lastObservedPositionMs + POSITION_ADVANCE_TOLERANCE_MS) {
            lastProgressAdvanceAtElapsedMs = nowElapsedMs
        }
        lastObservedPositionMs = positionMs

        if (state == NativePlaybackState.PLAYING && hasStartedPlayback) {
            val stalled = nowElapsedMs - lastProgressAdvanceAtElapsedMs >= PLAYBACK_STALL_THRESHOLD_MS
            if (stalled) return NativePlaybackState.BUFFERING
        }

        return when (state) {
            NativePlaybackState.IDLE -> if (playRequested || pendingSource != null) NativePlaybackState.PREPARING else NativePlaybackState.IDLE
            NativePlaybackState.PREPARING -> NativePlaybackState.PREPARING
            NativePlaybackState.BUFFERING -> if (hasStartedPlayback) NativePlaybackState.BUFFERING else NativePlaybackState.PREPARING
            NativePlaybackState.PLAYING -> if (playRequested) NativePlaybackState.PLAYING else NativePlaybackState.PAUSED
            NativePlaybackState.PAUSED -> NativePlaybackState.PAUSED
            NativePlaybackState.ENDED -> NativePlaybackState.ENDED
            NativePlaybackState.ERROR -> NativePlaybackState.ERROR
        }
    }

    private fun resetPlaybackStateForNewMedia() {
        pendingSource = null
        state = NativePlaybackState.IDLE
        lastBufferingPercent = null
        lastProgressAdvanceAtElapsedMs = 0L
        lastObservedPositionMs = 0L
        positionMs = 0L
        durationMs = 0L
        hasStartedPlayback = false
        videoLayout = null
        error = null
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        selectedAudioTrackId = null
        selectedSubtitleTrackId = null
        externalSubtitleUrl = null
    }

    private fun debugUrl(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host?.ifBlank { null }
        val scheme = uri.scheme?.ifBlank { null }
        return buildString {
            append("hash=").append(url.hashCode())
            if (scheme != null || host != null) {
                append(" scheme=").append(scheme ?: "unknown")
                append(" host=").append(host ?: "unknown")
            }
        }
    }

    companion object {
        private const val TAG = "CrispyMpv"
        private const val DEMUXER_MAX_BYTES = 64 * 1024 * 1024
        private const val POSITION_ADVANCE_TOLERANCE_MS = 150L
        private const val PLAYBACK_STALL_THRESHOLD_MS = 1_000L
    }
}
