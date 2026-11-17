package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class MpvPlaybackRuntime(
    context: Context,
    /**
     * Render through `gpu` instead of the tunneled `mediacodec_embed` path.
     * The tunneled vo gives native HDR and zero-copy but ignores panscan, so a
     * surface that must be cover-cropped (the hero trailer, which has no HDR
     * to preserve) needs `gpu`.
     */
    private val forceGpuVo: Boolean = false,
) {
    private val appContext = context.applicationContext
    private var mpv: MPVLib? = null
    private var created: Boolean = false
    private var surfaceView: SurfaceView? = null
    private var surfaceAttached: Boolean = false

    /**
     * The holder whose surface mpv is drawing into right now.
     *
     * Android does not order the new view's `surfaceCreated` before the old
     * view's `surfaceDestroyed`, and [attach] can be handed a second SurfaceView
     * (AndroidView's factory runs again once the window is recreated), so a
     * teardown that arrives for a holder that is no longer the live one has to
     * be dropped: it would write `vo=null` over the surface that just came up.
     */
    private var activeHolder: SurfaceHolder? = null

    /**
     * The view [surfaceHolderCallback] is registered with. A SurfaceHolder keeps
     * its callbacks across a surface destroy/create cycle, so registering the
     * same view twice fires every callback twice and the stale duplicate is
     * indistinguishable from the live one.
     */
    private var registeredView: SurfaceView? = null

    /**
     * Bumped on every surface attach and every surface detach. A readiness
     * job captures it when it starts and drops itself if it no longer
     * matches, so a surface destroyed mid-poll -- or replaced by a newer one
     * -- can never activate a video output for a surface that is already
     * gone. mpvRx's `surfaceAttachmentToken`.
     */
    private var surfaceGeneration: Int = 0

    /**
     * The video track (`vid`) mpv had selected when the surface went away.
     * Both `vo` and `wid` changes re-seek the whole file while a track is
     * selected, so the track is disabled before the output changes and
     * restored once the renderer is ready again. `null` when nothing was
     * selected, or when the media changed while the surface was gone (an id
     * belongs to the file that selected it).
     */
    private var suspendedVideoTrack: Int? = null

    /**
     * Whether the renderer chain is up: a surface is attached and mpv has
     * accepted the vo for it, confirmed by a `current-vo` readback. The
     * paused-frame redraw only runs in this state.
     */
    private var rendererOutputReady: Boolean = false

    /**
     * One worker for everything that must not run on the main thread but must
     * stay ordered with itself: the wid readiness poll after a surface
     * attach, and the delayed paused-frame redraw after a resize. Daemon,
     * shut down in [release].
     */
    private val surfaceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CrispyMpv-surface").apply { isDaemon = true }
    }

    private var pendingSource: PlaybackSource? = null
    private var pendingExternalAudioUrl: String? = null
    private var playRequested: Boolean = false
    private var state: NativePlaybackState = NativePlaybackState.IDLE
        set(value) {
            // The overlay's loading curtain is driven by this value, so a
            // session that "keeps loading" is a state that stopped changing;
            // the last transition line before it stopped is the clue.
            if (value != field) {
                Log.d(TAG, "state ${field} -> $value")
            }
            field = value
        }
    private var videoLayout: NativeVideoLayout? = null
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    /**
     * The stream's colour transfer function as mpv last reported it -- `pq`,
     * `hlg`, `bt.1886` -- and whether that one is HDR. Both are reset on a new
     * file, because the property outlives the file that set it otherwise.
     */
    private var sourceTransferFunction: String? = null
    private var sourceIsHdr: Boolean = false
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
    private var chapters: List<NativeChapter> = emptyList()
    private var subText: String? = null
    private var hasStartedPlayback: Boolean = false
    private var lastProgressAdvanceAtElapsedMs: Long = 0L
    private var lastObservedPositionMs: Long = 0L
    private var resizeMode: PlayerResizeMode = PlayerResizeMode.Fit

    /** Cover-crop requested before the mpv instance existed; applied at init. */
    private var coverCrop: Boolean = false

    /**
     * Whether the video is currently drawn into the PiP window. PiP renders
     * through `gpu`, which honours panscan, so mpv does the crop there; in
     * fullscreen the UI layer crops instead.
     */
    private var inPictureInPicture: Boolean = false

    /**
     * The user's HDR output choice. `true` (the default) plays through the
     * tunneled `mediacodec_embed` vo, where the decoder's frames reach the
     * display with their own colour aspects. `false` renders through `gpu`,
     * where mpv tone-maps every source down to the surface's SDR target, so
     * the display never switches into HDR mode.
     */
    private var hdrOutputEnabled: Boolean = true

    private val eventObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "cache-buffering-state" -> {
                    lastBufferingPercent = value.toFloat().coerceIn(0f, 100f)
                    if (state != NativePlaybackState.ERROR && state != NativePlaybackState.ENDED) {
                        state = when {
                            value in 1..99 -> NativePlaybackState.BUFFERING
                            // A full cache while `playRequested` is false means the file
                            // is ready and waiting, not playing: report PAUSED so the UI
                            // offers a resume rather than a spinner.
                            value == 100L ->
                                if (playRequested) NativePlaybackState.PLAYING else NativePlaybackState.PAUSED
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
                        restoreSubtitleMargins()
                    } else {
                        val current = mpv
                        if (current != null && subtitleTracks.none { it.index == value.toInt() }) {
                            subtitleTracks = readTrackList(current, "sub")
                        }
                        selectedSubtitleTrackId = resolveSelectedSubtitleTrackId(value.toString())
                        applySubtitleStyleForSelectedTrack()
                    }
                }
                "video-params/w" -> {
                    videoWidth = value.toInt()
                    refreshVideoLayout()
                }
                "video-params/h" -> {
                    videoHeight = value.toInt()
                    refreshVideoLayout()
                }
                "chapter-list/count" -> refreshChapters()
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
                    // A session the user has paused is never buffering. A vo change,
                    // a surface cycle or an audio track switch issues a seek while
                    // `pause=yes`, and mpv re-arms `seeking` for it; parking the UI in
                    // BUFFERING there is unrecoverable, because `pause` is already true
                    // so it fires no second change event and the loading curtain stays
                    // up for the whole pause.
                    if (value && playRequested && state != NativePlaybackState.ERROR && state != NativePlaybackState.ENDED) {
                        Log.d(TAG, "observer $property=$value -> BUFFERING")
                        state = NativePlaybackState.BUFFERING
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: String) {
            when (property) {
                // Raw sub-text carries ASS override blocks and markup; the UI
                // sanitises it before drawing (see SubtitleCueText.kt).
                "sub-text" -> subText = value.ifEmpty { null }
                "video-params/trc" -> {
                    sourceTransferFunction = value
                    sourceIsHdr = isHdrTransferFunction(value)
                }
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    state = NativePlaybackState.PREPARING
                    error = null
                    lastBufferingPercent = 0f
                    chapters = emptyList()
                    subText = null
                    videoWidth = 0
                    videoHeight = 0
                    sourceTransferFunction = null
                    sourceIsHdr = false
                    videoLayout = null
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
                    refreshChapters()
                    logEngineState("event:${if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) "file-loaded" else "playback-restart"}")
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    if (state != NativePlaybackState.ENDED && state != NativePlaybackState.ERROR) {
                        state = NativePlaybackState.IDLE
                    }
                    logEngineState("event:end-file")
                }
            }
        }
    }

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(
                TAG,
                "surfaceCreated holder=${System.identityHashCode(holder)} " +
                    "viewHash=${System.identityHashCode(surfaceView)} " +
                    "isActiveHolder=${activeHolder === holder} created=$created " +
                    "surfaceAttached=$surfaceAttached",
            )
            val generation = ++surfaceGeneration
            runCatching {
                // mpvRx's bind order: drop whatever renderer the previous
                // surface still held before handing over the new one, so the
                // attach starts from a clean detach instead of a replacement.
                mpv?.detachSurface()
                mpv?.attachSurface(holder.surface)
                activeHolder = holder
            }
            surfaceAttached = true
            rendererOutputReady = false
            // Both the load and the vo wait for the readiness job: the initial
            // attachSurface queues wid through mpv_set_option, and the
            // playback thread can run before mpv has consumed it, producing
            // "Missing surface pointer", a dropped video track and a permanent
            // black screen. Never load or select a vo before wid is observable
            // (mpvRx's comment on the same race).
            scheduleReadinessJob(holder, generation, startPending = pendingSource != null)
            logEngineState("surfaceCreated")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(TAG, "surfaceChanged size=${width}x$height holder=${System.identityHashCode(holder)}")
            runCatching { mpv?.setPropertyString("android-surface-size", "${width}x$height") }
            // The surface size is deliberately NOT recorded as the video layout:
            // the tunneled vo renders at the video's native size and the view
            // scales it, so the video's own aspect is what the UI must letterbox to.

            // EGL can finish a resize after the first paused redraw was already
            // submitted, leaving the old-sized buffer scaled or cropped; redraw
            // once the window has settled (mpvRx's resize redraw).
            if (!playRequested && hasStartedPlayback && rendererOutputReady && activeHolder === holder) {
                val generation = surfaceGeneration
                surfaceExecutor.execute {
                    try {
                        Thread.sleep(RESIZE_REDRAW_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                    if (surfaceGeneration != generation || activeHolder !== holder ||
                        !surfaceAttached || playRequested
                    ) {
                        return@execute
                    }
                    redrawPausedFrame()
                }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(
                TAG,
                "surfaceDestroyed holder=${System.identityHashCode(holder)} " +
                    "isActiveHolder=${activeHolder === holder} " +
                    "viewHash=${System.identityHashCode(surfaceView)}",
            )
            if (activeHolder !== null && activeHolder !== holder) {
                // The old view's surface went away after the new view's surface
                // came up. Tearing down here would write `vo=null` over the
                // surface that is now live, which is exactly the state that
                // leaves a session buffering forever.
                Log.d(TAG, "surfaceDestroyed stale holder ignored (a newer surface is up)")
                return
            }
            // Any readiness job or delayed redraw in flight belongs to the
            // surface that is going away now; the bump drops them.
            surfaceGeneration++
            rendererOutputReady = false
            // Both vo and wid changes re-seek the whole file while a video
            // track is selected, so the track is disabled before the output
            // changes and restored once the renderer is ready again (mpvRx's
            // suspendVideoTrack / restoreSuspendedVideoTrack pair).
            suspendVideoTrack()
            runCatching {
                mpv?.setPropertyString("vo", "null")
                mpv?.setPropertyString("force-window", "no")
                mpv?.detachSurface()
            }
            activeHolder = null
            surfaceAttached = false
            logEngineState("surfaceDestroyed")
        }
    }

    fun createSurfaceView(viewContext: Context): SurfaceView {
        if (!created) {
            initialize(viewContext)
        }
        val created = SurfaceView(viewContext)
        Log.d(TAG, "createSurfaceView viewHash=${System.identityHashCode(created)}")
        val previous = registeredView
        if (previous !== created && previous != null) {
            previous.holder.removeCallback(surfaceHolderCallback)
        }
        registeredView = created
        surfaceView = created
        created.holder.addCallback(surfaceHolderCallback)
        return created
    }

    fun attach(view: SurfaceView) {
        if (surfaceView === view && surfaceAttached) {
            return
        }
        if (registeredView === view) {
            // Still live. SurfaceHolder.Callback survives a surface
            // destroy/create cycle, so adding it again would run every callback
            // twice and the duplicate would be indistinguishable from the live
            // one in a log.
            Log.d(TAG, "attach noop alreadyRegistered viewHash=${System.identityHashCode(view)}")
            surfaceView = view
            return
        }
        Log.d(TAG, "attach replacingViewHash=${surfaceView?.let { System.identityHashCode(it) }} newViewHash=${System.identityHashCode(view)} pendingUrl=${pendingSource?.url?.hashCode()}")
        if (!created) {
            initialize(view.context)
        }
        val previous = registeredView
        if (previous !== view && previous != null) {
            previous.holder.removeCallback(surfaceHolderCallback)
        }
        registeredView = view
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
            // hwdec=mediacodec is the zero-copy path; "auto" resolves to
            // mediacodec-copy on Android, which copies every frame through the
            // CPU. If mediacodec init fails mpv falls back to software decode.
            instance.setOptionString("hwdec", "mediacodec")
            // vo order: mediacodec_embed first — the tunneled decoder writes
            // straight to the SurfaceView surface (zero copy, native HDR via
            // the HWC, but no mpv overlays, and it ignores panscan). gpu is the
            // fallback when tunneling is unsupported, and the only choice when
            // the surface must be cover-cropped or the user turned HDR output
            // off; gpu (not gpu-next) because gpu-next corrupts on 10-bit HDR
            // opaque surfaces (mpvEx #817). The trailer passes forceGpuVo =
            // true — it has no HDR to preserve.
            instance.setOptionString("vo", activeVo())
            instance.setOptionString("msg-level", "all=warn")
            instance.setOptionString("demuxer-max-bytes", "$DEMUXER_MAX_BYTES")
            instance.setOptionString("demuxer-max-back-bytes", "$DEMUXER_MAX_BYTES")
            instance.setOptionString("vd-lavc-film-grain", "cpu")
            instance.setOptionString("force-window", "no")
            instance.setOptionString("idle", "once")
            instance.init()
            // The player can start in a resize mode that was chosen before mpv
            // existed, so apply the remembered mode too.
            applyResizeMode(resizeMode)
            // The trailer asks for its cover-crop at construction, before the
            // instance exists, so apply the remembered value once it does. It
            // comes after the resize mode because it owns panscan: the trailer
            // crops itself, the player leaves the crop to the UI layer.
            if (coverCrop) {
                instance.setPropertyDouble("panscan", 1.0)
                instance.setPropertyString("video-aspect-override", "no")
            }
            // Subtitles are drawn by the app overlay from sub-text, not by mpv:
            // mediacodec_embed (tunneled) video cannot carry mpv's rendered
            // overlays, so mpv-rendered subs and native HDR are mutually exclusive.
            // In PiP the vo switches to gpu, where mpv renders subs itself.
            instance.setPropertyBoolean("sub-visibility", false)
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
        logEngineState("initialize")
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
            "chapter-list/count" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "video-params/w" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "video-params/h" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "video-params/trc" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "sub-text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
        )
        props.forEach { (name, format) -> mpv.observeProperty(name, format) }
    }

    /**
     * Loads [source].
     *
     * [externalAudioUrl] is a second stream played alongside the video -- the
     * trailer case, where the video and the audio come from two different
     * URLs. mpv takes it as the `audio-file` option of the same `loadfile`.
     */
    fun play(source: PlaybackSource, externalAudioUrl: String? = null) {
        resetPlaybackStateForNewMedia()
        playRequested = true
        state = NativePlaybackState.PREPARING
        val url = source.url
        Log.d(TAG, "play surfaceAttached=$surfaceAttached url=${debugUrl(url)} externalAudio=${externalAudioUrl != null}")
        if (!surfaceAttached) {
            pendingSource = source
            pendingExternalAudioUrl = externalAudioUrl
            Log.d(TAG, "play deferred until surface attach url=${debugUrl(url)}")
            return
        }
        startPlayback(source, externalAudioUrl)
    }

    fun setPlaying(isPlaying: Boolean) {
        Log.d(TAG, "setPlaying isPlaying=$isPlaying playRequested=$playRequested")
        playRequested = isPlaying
        runCatching { mpv?.setPropertyBoolean("pause", !isPlaying) }
        logEngineState("setPlaying:$isPlaying")
    }

    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) return
        runCatching {
            mpv?.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
        }
        lastProgressAdvanceAtElapsedMs = SystemClock.elapsedRealtime()
        lastObservedPositionMs = positionMs
    }

    fun selectChapter(index: Int) {
        val chapter = chapters.getOrNull(index) ?: return
        Log.d(TAG, "selectChapter index=$index startMs=${chapter.startMs}")
        seekTo(chapter.startMs)
    }

    fun setPictureInPictureActive(active: Boolean) {
        // A PiP window never qualifies for high-brightness HDR (the panel gates
        // it on HDR screen coverage), and tunneled mediacodec_embed output is
        // not guaranteed to compose in a small window, so render through gpu
        // while in PiP and restore the tunneled vo when leaving it.
        inPictureInPicture = active
        val vo = activeVo()
        Log.d(TAG, "setPictureInPictureActive active=$active vo=$vo")
        runCatching {
            mpv?.setPropertyString("vo", vo)
            // gpu vo in the small window can render subs; the fullscreen
            // tunneled surface cannot, so let mpv draw them there instead.
            mpv?.setPropertyBoolean("sub-visibility", active)
        }
        // The crop moves between the engine and the UI depending on where the
        // video is drawn, so re-apply the resize mode for the new context.
        applyResizeMode(resizeMode)
    }

    /**
     * Turns HDR output on or off while a file is playing.
     *
     * Off switches to the `gpu` vo, where mpv tone-maps the stream down to the
     * surface's SDR target; the display then never enters HDR mode. On
     * restores the tunneled `mediacodec_embed` vo from [VO_PREFERRED]. Like
     * every vo change here this is a live property write -- the same mechanism
     * [setPictureInPictureActive] uses -- so the swap happens without
     * reloading the file.
     */
    fun setHdrOutputEnabled(enabled: Boolean) {
        if (hdrOutputEnabled == enabled) {
            return
        }
        hdrOutputEnabled = enabled
        val vo = activeVo()
        Log.d(TAG, "setHdrOutputEnabled enabled=$enabled vo=$vo")
        runCatching {
            mpv?.setPropertyString("vo", vo)
        }
        // Where the video is drawn decides who crops it, so re-apply the
        // resize mode for the new vo.
        applyResizeMode(resizeMode)
    }

    /**
     * The vo the video should be drawn with right now: `gpu` whenever the
     * surface must be cover-cropped, we are in PiP, or the user turned HDR
     * output off; the tunneled chain the rest of the time.
     */
    private fun activeVo(): String =
        when {
            forceGpuVo -> "gpu"
            inPictureInPicture -> "gpu"
            !hdrOutputEnabled -> "gpu"
            else -> VO_PREFERRED
        }

    /**
     * The load and the vo wait for this job instead of happening inline in
     * [surfaceCreated]: the initial attachSurface queues `wid` through
     * mpv_set_option, and the playback thread can run before mpv has consumed
     * that option, producing "Missing surface pointer", a dropped video track
     * and a permanent black screen. Never load a file or select a video
     * output before `wid` is observable. mpvRx's readiness poll, adapted to
     * this runtime: the same budget (300 x 16ms) and the same stale-out, via
     * [surfaceGeneration] where they use a state generation.
     */
    private fun scheduleReadinessJob(holder: SurfaceHolder, generation: Int, startPending: Boolean) {
        surfaceExecutor.execute {
            var attempt = 0
            while (attempt < READINESS_MAX_ATTEMPTS) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(READINESS_POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                }
                attempt++
                val mpv = mpv
                if (surfaceGeneration != generation || activeHolder !== holder ||
                    !surfaceAttached || mpv == null
                ) {
                    // A newer surface owns the output now, or the engine was
                    // released; a stale job must never activate anything.
                    return@execute
                }
                // Read `wid` as a STRING and parse it: the JNI Int accessor
                // truncates int64 values, and wid is a native reference, so
                // its low 32 bits must never decide whether the surface is
                // attached. 0 is "no surface", -1 is "auto".
                val wid = runCatching { mpv.getPropertyString("wid") }.getOrNull()?.toLongOrNull() ?: 0L
                if (wid == 0L || wid == -1L) continue
                if (!activateVideoOutput(mpv)) continue
                if (startPending && startPendingPlayback()) {
                    return@execute
                }
                restoreSuspendedVideoTrack()
                redrawPausedFrame()
                logEngineState("surfaceReady")
                return@execute
            }
            // Never became ready within the budget. The vo write still goes
            // out -- that is what this runtime did before the poll existed,
            // and ending with a vo beats ending with the `vo=null` black
            // screen the poll exists to fix. A deferred load goes out too, for
            // the same reason; the line makes the race observable.
            Log.e(TAG, "renderer not ready after $attempt polls generation=$generation")
            runCatching {
                mpv?.setPropertyString("vo", activeVo())
                mpv?.setPropertyString("force-window", "yes")
            }
            if (startPending) {
                startPendingPlayback()
            }
            logEngineState("surfaceNotReady")
        }
    }

    /**
     * Requests the video output for the live surface and confirms it took:
     * the write plus a `current-vo` readback -- "read back the actual output,
     * not just the requested option" (mpvRx). False means the write has not
     * landed yet, which is a keep-polling condition, not a failure. The
     * requested value may be a fallback list ([VO_PREFERRED]), so any entry
     * of it counts as arrived.
     */
    private fun activateVideoOutput(mpv: MPVLib): Boolean {
        val vo = activeVo()
        val failure = runCatching {
            mpv.setPropertyString("vo", vo)
            mpv.setPropertyString("force-window", "yes")
        }.exceptionOrNull()
        if (failure != null) {
            Log.e(TAG, "activateVideoOutput vo=$vo failed", failure)
            return false
        }
        val currentVo = runCatching { mpv.getPropertyString("current-vo") }.getOrNull()
        val arrived = currentVo != null && currentVo in vo.split(",").map { it.trim() }
        if (!arrived) {
            Log.d(TAG, "activateVideoOutput waiting current-vo=$currentVo requested=$vo")
            return false
        }
        rendererOutputReady = true
        Log.d(TAG, "renderer ready vo=$vo wid=${runCatching { mpv.getPropertyString("wid") }.getOrNull()}")
        return true
    }

    /**
     * Consumes [pendingSource] if one is queued and starts it. Returns
     * whether something was started, so the readiness job knows whether the
     * paused-frame bookkeeping is still its business.
     */
    private fun startPendingPlayback(): Boolean {
        val pending = pendingSource ?: return false
        pendingSource = null
        val externalAudioUrl = pendingExternalAudioUrl
        pendingExternalAudioUrl = null
        startPlayback(pending, externalAudioUrl)
        return true
    }

    /**
     * Disables the video track before the Android output changes: both `vo`
     * and `wid` changes re-seek the whole file while a track is selected,
     * which is the re-seek behind the loading curtain on every surface cycle
     * (mpvRx's suspendVideoTrack). The selected id is kept in
     * [suspendedVideoTrack] for [restoreSuspendedVideoTrack].
     */
    private fun suspendVideoTrack() {
        val mpv = mpv ?: return
        val selected = runCatching { mpv.getPropertyString("vid") }.getOrNull()?.toIntOrNull()
        suspendedVideoTrack = selected?.takeIf { it > 0 }
        runCatching { mpv.setPropertyString("vid", "no") }
        val readback = runCatching { mpv.getPropertyString("vid") }.getOrNull()
        if (readback != "no") {
            Log.e(TAG, "suspendVideoTrack vid=$readback (wanted no); the output change may re-seek")
        }
    }

    /**
     * Restores the track [suspendVideoTrack] saved, once the renderer is up.
     * The readback, not the call, decides: the JNI setter logs native
     * failures rather than throwing, so a failed restore keeps the saved id
     * and the next renderer-ready moment retries it (mpvRx's
     * restoreSuspendedVideoTrack).
     */
    private fun restoreSuspendedVideoTrack() {
        val id = suspendedVideoTrack ?: return
        val mpv = mpv ?: return
        if (!rendererOutputReady) return
        val restored = runCatching {
            mpv.setPropertyString("vid", id.toString())
            runCatching { mpv.getPropertyString("vid") }.getOrNull()?.toIntOrNull() == id
        }.getOrDefault(false)
        if (restored) {
            suspendedVideoTrack = null
            Log.d(TAG, "video track $id restored after the surface came back")
        } else {
            Log.w(TAG, "video track $id restore failed; kept for retry")
        }
    }

    /**
     * A paused session repaints its retained frame without seeking: the
     * empty `osd-overlay` command wakes mpv's render pass, which re-renders
     * the last video frame onto the (new) surface without touching audio
     * buffers (mpvRx's redrawVideoFrame). Without it a session that returns
     * from home paused keeps whatever the old surface last held.
     */
    private fun redrawPausedFrame() {
        if (playRequested || !hasStartedPlayback || !rendererOutputReady) return
        runCatching { mpv?.command(arrayOf("osd-overlay", "2147483647", "ass-events", "")) }
        Log.d(TAG, "redrawPausedFrame requested")
    }

    /**
     * One line of every fact that decides whether the picture comes back: the
     * vo mpv accepted, the decoder it settled on, the pause and cache state, and
     * the UI state this engine is reporting. Each read is guarded because a name
     * mpv does not know throws, and the point of the dump is the other fields.
     */
    private fun logEngineState(phase: String) {
        val current = mpv
        fun read(name: String): String = runCatching { current?.getPropertyString(name) }.getOrNull() ?: "n/a"
        val path = runCatching { current?.getPropertyString("path") }.getOrNull()
        Log.d(
            TAG,
            "dump[$phase] ui=$state playWhenReady=$playRequested started=$hasStartedPlayback " +
                "surfaceAttached=$surfaceAttached " +
                "mpv.vo=${read("vo")} currentVo=${read("current-vo")} hwdec=${read("hwdec")} " +
                "pause=${read("pause")} cachePaused=${read("paused-for-cache")} seeking=${read("seeking")} " +
                "coreIdle=${read("core-idle")} eof=${read("eof-reached")} " +
                "cacheState=${read("cache-buffering-state")} cacheTime=${read("demuxer-cache-time")} " +
                "pos=${read("time-pos")} dur=${read("duration")} path=${if (path == null) "n/a" else path.hashCode()}",
        )
    }

    /**
     * The video's own pixel size, from `video-params/w`/`h` -- not the surface's.
     * The tunneled `mediacodec_embed` vo renders at the video's native size and the
     * SurfaceView scales that buffer to the view, so a full-screen view stretches
     * any video whose aspect differs from the screen; the UI needs the video's
     * aspect to size the view so the scaling stays uniform.
     */
    private fun refreshVideoLayout() {
        if (videoWidth > 0 && videoHeight > 0) {
            videoLayout = NativeVideoLayout(
                width = videoWidth,
                height = videoHeight,
                visibleWidth = videoWidth,
                visibleHeight = videoHeight,
                pixelWidthHeightRatio = 1f,
            )
        }
    }

    private fun refreshChapters() {
        val mpv = mpv ?: return
        val count = runCatching { mpv.getPropertyString("chapter-list/count") }.getOrNull()?.toIntOrNull() ?: 0
        if (count <= 0) {
            chapters = emptyList()
            return
        }
        val list = ArrayList<NativeChapter>(count)
        for (i in 0 until count) {
            val timeSec =
                runCatching { mpv.getPropertyString("chapter-list/$i/time") }.getOrNull()?.toDoubleOrNull() ?: continue
            val title = runCatching { mpv.getPropertyString("chapter-list/$i/title") }.getOrNull()?.takeIf { it.isNotBlank() }
            list.add(
                NativeChapter(
                    index = i,
                    startMs = (timeSec * 1000.0).toLong().coerceAtLeast(0L),
                    title = title,
                ),
            )
        }
        chapters = list
    }

    fun stop() {
        Log.d(TAG, "stop surfaceAttached=$surfaceAttached")
        pendingSource = null
        playRequested = false
        runCatching { mpv?.command(arrayOf("stop")) }
        state = NativePlaybackState.IDLE
        lastBufferingPercent = null
        logEngineState("stop")
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
        val language = trackId?.let { id -> audioTracks.firstOrNull { it.id == id }?.language }
        Log.d(TAG, "selectAudioTrack id=$trackId language=$language")
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
        val language = trackId?.let { id -> subtitleTracks.firstOrNull { it.id == id }?.language }
        Log.d(TAG, "selectSubtitleTrack id=$trackId language=$language")
        val mpv = mpv ?: return
        if (trackId == null) {
            runCatching { mpv.setPropertyString("sid", "no") }
            selectedSubtitleTrackId = null
            restoreSubtitleMargins()
            return
        }
        val track = subtitleTracks.firstOrNull { it.id == trackId }
        if (track == null) {
            Log.d(TAG, "selectSubtitleTrack id=$trackId rejected=track-missing")
            return
        }
        runCatching { mpv.setPropertyInt("sid", track.index) }
        selectedSubtitleTrackId = trackId
        applySubtitleStyleForSelectedTrack()
    }

    fun setExternalSubtitle(subtitle: PlaybackExternalSubtitle?) {
        val mpv = mpv
        if (mpv == null) return
        runCatching {
            removeExternalSubtitleTracks(mpv)
            if (subtitle != null) {
                mpv.command(arrayOf("sub-add", subtitle.url, "select", subtitle.name ?: "", subtitle.language ?: ""))
                // sub-add fires the sid observer, which recomputes this from
                // the track codec; apply directly too because the URL-derived
                // decision is available here without waiting for track-list
                // re-reads.
                applySubtitleStyle(
                    allowMargins = resizeMode == PlayerResizeMode.Zoom &&
                        isAdjustableSubtitle(codec = null, externalUrl = subtitle.url),
                )
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

    /**
     * Cover-crop the video to fill the surface (`panscan=1.0`), the hero
     * trailer's old `RESIZE_MODE_ZOOM` behaviour. Separate from
     * [applyResizeMode]'s `Zoom`, which is the player's user-facing resize
     * mode and is cropped by the UI layer, not by mpv.
     *
     * The trailer calls this before its mpv instance exists, so the value is
     * remembered and applied in [initialize] too.
     */
    fun setCoverCrop(enabled: Boolean) {
        coverCrop = enabled
        val mpv = mpv ?: return
        runCatching {
            mpv.setPropertyDouble("panscan", if (enabled) 1.0 else 0.0)
            mpv.setPropertyString("video-aspect-override", "no")
        }
    }

    fun applyResizeMode(mode: PlayerResizeMode) {
        resizeMode = mode
        val mpv = mpv ?: return
        runCatching {
            // Who crops depends on where the video is drawn. Fullscreen crops in
            // the UI layer: the surface is sized to cover the window and the
            // overflow is cropped by the window edge, so mpv must not crop as
            // well (and the tunneled vo ignores panscan anyway). PiP keeps the
            // surface fitted to the video, so there mpv does the crop.
            val panscan = if (mode == PlayerResizeMode.Zoom && inPictureInPicture) 0.5 else 0.0
            mpv.setPropertyDouble("panscan", panscan)
            mpv.setPropertyString("video-aspect-override", "no")
        }
        // Zoom crops plain-text subs off the video rect unless they are
        // allowed into the margins; re-run the selection-aware decision so a
        // ratio change restores the flag for the active track's format.
        applySubtitleStyleForSelectedTrack()
    }

    /**
     * Common subtitle styling. Plain-text tracks (subrip/webvtt) selected
     * while Zoom is active are laid into the letterbox margins
     * (`sub-use-margins=yes`) so panscan's crop cannot clip them; everything
     * else — styled ASS/SSA, TTML, bitmap (pgs/dvd/dvb) and anything
     * unrecognized — keeps the previous behavior (`sub-use-margins=no`,
     * `sub-pos=92`) untouched. Margins affect plain-text formats only, never
     * ASS placement (`sub-ass-override=no`), and `sub-ass-force-margins` is
     * never set.
     */
    private fun applySubtitleStyle(allowMargins: Boolean) {
        val mpv = mpv ?: return
        runCatching {
            mpv.setPropertyString("sub-ass-override", "no")
            mpv.setPropertyString("sub-use-margins", if (allowMargins) "yes" else "no")
            mpv.setPropertyInt("sub-pos", 92)
        }
    }

    /** mpv defaults (`sub-use-margins=yes` is only safe for text formats). */
    private fun restoreSubtitleMargins() {
        applySubtitleStyle(allowMargins = false)
    }

    /** Reapply the margin decision for the currently selected track. */
    private fun applySubtitleStyleForSelectedTrack() {
        val id = selectedSubtitleTrackId ?: return restoreSubtitleMargins()
        val track = subtitleTracks.firstOrNull { it.id == id } ?: return restoreSubtitleMargins()
        val allowMargins = resizeMode == PlayerResizeMode.Zoom && isAdjustableSubtitle(track.codec, null)
        applySubtitleStyle(allowMargins)
    }

    private fun refreshTrackLists() {
        val mpv = mpv ?: return
        audioTracks = readTrackList(mpv, "audio")
        subtitleTracks = readTrackList(mpv, "sub")
        val aid = runCatching { mpv.getPropertyString("aid") }.getOrNull()
        selectedAudioTrackId = aid?.takeIf { it.isNotBlank() && it != "0" }
        selectedSubtitleTrackId = resolveSelectedSubtitleTrackId(
            runCatching { mpv.getPropertyString("sid") }.getOrNull(),
        )
        applySubtitleStyleForSelectedTrack()
        Log.d(
            TAG,
            "refreshTrackLists audio=${audioTracks.joinToString { "${it.id}:${it.language}" }}" +
                " subtitle=${subtitleTracks.joinToString { "${it.id}:${it.language}" }}" +
                " selectedAudioId=$selectedAudioTrackId selectedSubtitleId=$selectedSubtitleTrackId",
        )
    }

    /** mpv reports the numeric track id; the snapshot exposes the stable NativeTrack id. */
    private fun resolveSelectedSubtitleTrackId(sid: String?): String? {
        if (sid.isNullOrBlank() || sid == "0") return null
        return sid.toIntOrNull()
            ?.let { sidInt -> subtitleTracks.firstOrNull { it.index == sidInt }?.id }
            ?: sid
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
            val externalFilename =
                runCatching { mpv.getPropertyString("track-list/$i/external-filename") }.getOrNull()?.takeIf { it.isNotBlank() }
            // mpv answers "codec" for every track type; demux-channel-count is
            // audio-only, so the channel read stays behind the audio gate.
            val isAudio = type == "audio"
            val codec =
                runCatching { mpv.getPropertyString("track-list/$i/codec") }.getOrNull()?.takeIf { it.isNotBlank() }
            val channelCount =
                if (isAudio) {
                    runCatching { mpv.getPropertyString("track-list/$i/demux-channel-count") }
                        .getOrNull()
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                } else {
                    null
                }
            tracks.add(
                NativeTrack(
                    // Externals keep the same stable ext:<hash(url)> identity Exo uses so
                    // the sheet matches catalog entries against engine tracks by id alone.
                    id = if (external && externalFilename != null) externalSubtitleTrackId(externalFilename) else id.toString(),
                    index = id,
                    language = lang,
                    title = title,
                    isExternal = external,
                    codec = codec,
                    channelCount = channelCount,
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
            playWhenReady = playRequested,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferingPercent = lastBufferingPercent,
            videoLayout = videoLayout,
            isHdrSource = sourceIsHdr,
            error = error,
            playbackSpeed = playbackSpeed,
            muted = muted,
            audioTracks = audioTracks,
            selectedAudioTrackId = selectedAudioTrackId,
            subtitleTracks = subtitleTracks,
            selectedSubtitleTrackId = selectedSubtitleTrackId,
            subtitleDelayMs = subtitleDelayMs,
            chapters = chapters,
            currentSubtitle = subText,
        )
    }

    fun release() {
        Log.d(TAG, "release surfaceAttached=$surfaceAttached created=$created")
        // Drop any in-flight readiness job or delayed redraw first, then stop
        // the worker: both poll for staleness and both check `mpv == null`,
        // but the bump makes the check immediate.
        surfaceGeneration++
        surfaceExecutor.shutdownNow()
        // One `runCatching` per step, deliberately not one around all four: the
        // steps are ordered, and a `detachSurface` that throws -- mpv can still
        // be reading the surface, the race mpv-android documents -- must not
        // also skip `destroy()`, which would leak the whole native instance.
        runCatching { registeredView?.holder?.removeCallback(surfaceHolderCallback) }
        runCatching { mpv?.removeObserver(eventObserver) }
        runCatching { mpv?.detachSurface() }
        runCatching { mpv?.destroy() }
        mpv = null
        created = false
        surfaceAttached = false
        surfaceView = null
        registeredView = null
        activeHolder = null
        pendingSource = null
        pendingExternalAudioUrl = null
        suspendedVideoTrack = null
        rendererOutputReady = false
    }

    private fun startPlayback(source: PlaybackSource, externalAudioUrl: String? = null) {
        applyRequestHeaders(source.headers)
        val url = source.url
        Log.d(TAG, "startPlayback url=${debugUrl(url)} surfaceAttached=$surfaceAttached externalAudio=${externalAudioUrl != null}")
        val loadArguments =
            if (externalAudioUrl.isNullOrBlank()) {
                arrayOf("loadfile", url, "replace")
            } else {
                arrayOf("loadfile", url, "replace", "audio-file=$externalAudioUrl")
            }
        runCatching {
            mpv?.setPropertyBoolean("pause", !playRequested)
            mpv?.command(loadArguments)
        }
        if (source.externalSubtitles.isNotEmpty()) {
            val mpv = mpv
            if (mpv != null) {
                // First external is the default selection, matching the Exo path.
                source.externalSubtitles.forEachIndexed { index, subtitle ->
                    runCatching {
                        mpv.command(
                            arrayOf("sub-add", subtitle.url, if (index == 0) "select" else "auto", subtitle.name ?: "", subtitle.language ?: ""),
                        )
                    }
                }
                applySubtitleStyle(
                    allowMargins = resizeMode == PlayerResizeMode.Zoom &&
                        isAdjustableSubtitle(codec = null, externalUrl = source.externalSubtitles.first().url),
                )
            }
        }
        logEngineState("startPlayback")
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

        if (state == NativePlaybackState.PLAYING && playRequested && hasStartedPlayback) {
            val stalled = nowElapsedMs - lastProgressAdvanceAtElapsedMs >= PLAYBACK_STALL_THRESHOLD_MS
            if (stalled) return NativePlaybackState.BUFFERING
        }

        return visiblePlaybackState(
            state = state,
            playRequested = playRequested,
            hasStartedPlayback = hasStartedPlayback,
            hasPendingSource = pendingSource != null,
        )
    }

    private fun resetPlaybackStateForNewMedia() {
        pendingSource = null
        pendingExternalAudioUrl = null
        // A vid id belongs to the media that selected it; a new file gets a
        // fresh selection from mpv's default instead.
        suspendedVideoTrack = null
        state = NativePlaybackState.IDLE
        lastBufferingPercent = null
        lastProgressAdvanceAtElapsedMs = 0L
        lastObservedPositionMs = 0L
        positionMs = 0L
        durationMs = 0L
        hasStartedPlayback = false
        videoLayout = null
        videoWidth = 0
        videoHeight = 0
        sourceTransferFunction = null
        sourceIsHdr = false
        error = null
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        selectedAudioTrackId = null
        selectedSubtitleTrackId = null
        chapters = emptyList()
        subText = null
        restoreSubtitleMargins()
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
        private const val VO_PREFERRED = "mediacodec_embed,gpu"
        private const val DEMUXER_MAX_BYTES = 64 * 1024 * 1024
        private const val POSITION_ADVANCE_TOLERANCE_MS = 150L

        /** mpvRx's readiness budget: 300 polls x 16ms (~4.8s) for a slow driver. */
        private const val READINESS_MAX_ATTEMPTS = 300

        private const val READINESS_POLL_INTERVAL_MS = 16L

        /** mpvRx's post-resize settle time before a paused redraw. */
        private const val RESIZE_REDRAW_DELAY_MS = 150L
        private const val PLAYBACK_STALL_THRESHOLD_MS = 1_000L
    }
}
