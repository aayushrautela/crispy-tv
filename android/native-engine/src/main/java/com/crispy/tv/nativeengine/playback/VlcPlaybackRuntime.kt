package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

internal class VlcPlaybackRuntime(
    context: Context,
) {
    private val libVlc: LibVLC =
        LibVLC(
            context,
            arrayListOf(
                "--network-caching=1000",
                "--file-caching=1000",
                "--audio-time-stretch",
            ),
        )

    private val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)
    private var surfaceView: SurfaceView? = null
    private var viewsAttached: Boolean = false
    private var pendingUrl: String? = null
    private var state: NativePlaybackState = NativePlaybackState.IDLE
    private var lastBufferingPercent: Float? = null
    private var lastBufferingEventAtElapsedMs: Long = 0L
    private var lastProgressAdvanceAtElapsedMs: Long = 0L
    private var lastObservedPositionMs: Long = 0L
    private var hasStartedPlayback: Boolean = false
    private var playRequested: Boolean = false
    private var videoLayout: NativeVideoLayout? = null
    private var error: NativePlaybackError? = null
    private var nextErrorToken: Long = 1L

    private val surfaceHolderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(
                    TAG,
                    "surfaceCreated isCurrent=${surfaceView?.holder === holder} ${describeSurface(surfaceView)}",
                )
                updateWindowSize(surfaceView)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                Log.d(
                    TAG,
                    "surfaceChanged isCurrent=${surfaceView?.holder === holder} format=$format size=${width}x$height ${describeSurface(surfaceView)}",
                )
                updateWindowSize(surfaceView)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(
                    TAG,
                    "surfaceDestroyed isCurrent=${surfaceView?.holder === holder} ${describeSurface(surfaceView)}",
                )
            }
        }
    private val surfaceLayoutListener =
        View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val surface = view as? SurfaceView ?: return@OnLayoutChangeListener
            Log.d(
                TAG,
                "surfaceLayoutChanged viewHash=${System.identityHashCode(surface)} bounds=[$left,$top,$right,$bottom] oldBounds=[$oldLeft,$oldTop,$oldRight,$oldBottom] ${describeSurface(surface)}",
            )
            updateWindowSize(surface)
        }
    private val voutCallback =
        object : IVLCVout.Callback {
            override fun onSurfacesCreated(vlcVout: IVLCVout) {
                Log.d(TAG, "vout surfacesCreated ${describeSurface(surfaceView)}")
                updateWindowSize(surfaceView)
            }

            override fun onSurfacesDestroyed(vlcVout: IVLCVout) {
                Log.d(TAG, "vout surfacesDestroyed ${describeSurface(surfaceView)}")
            }

            override fun onNewLayout(
                vlcVout: IVLCVout,
                width: Int,
                height: Int,
                visibleWidth: Int,
                visibleHeight: Int,
                sarNum: Int,
                sarDen: Int,
            ) {
                videoLayout =
                    NativeVideoLayout(
                        width = width.coerceAtLeast(0),
                        height = height.coerceAtLeast(0),
                        visibleWidth = visibleWidth.coerceAtLeast(0),
                        visibleHeight = visibleHeight.coerceAtLeast(0),
                        pixelWidthHeightRatio =
                            if (sarNum > 0 && sarDen > 0) {
                                sarNum.toFloat() / sarDen.toFloat()
                            } else {
                                1f
                            },
                    )
                Log.d(
                    TAG,
                    "vout newLayout width=$width height=$height visibleWidth=$visibleWidth visibleHeight=$visibleHeight sar=$sarNum/$sarDen layout=$videoLayout",
                )
                updateWindowSize(surfaceView)
            }
        }

    init {
        Log.d(TAG, "init networkCachingMs=1000 fileCachingMs=1000")
        mediaPlayer.setEventListener { event ->
            handleMediaPlayerEvent(event)
        }
    }

    fun createSurfaceView(context: Context): SurfaceView {
        val created = SurfaceView(context)
        Log.d(TAG, "createSurfaceView ${describeSurface(created)}")
        attach(created)
        return created
    }

    fun attach(view: SurfaceView) {
        if (surfaceView === view && viewsAttached) {
            Log.d(TAG, "attach skipped viewHash=${System.identityHashCode(view)} alreadyAttached=true")
            updateWindowSize(view)
            return
        }

        Log.d(
            TAG,
            "attach replacingViewHash=${surfaceView?.let(System::identityHashCode)} pendingUrl=${pendingUrl?.hashCode()} ${describeSurface(view)}",
        )
        detachViewsIfNeeded()
        surfaceView = view
        registerSurfaceDiagnostics(view)
        applyBestFitScaling()
        mediaPlayer.vlcVout.setVideoView(view)
        mediaPlayer.vlcVout.addCallback(voutCallback)
        mediaPlayer.vlcVout.attachViews()
        viewsAttached = true
        updateWindowSize(view)
        Log.d(TAG, "attach complete ${describeSurface(view)}")

        pendingUrl?.let { pendingPlaybackUrl ->
            pendingUrl = null
            startPlayback(pendingPlaybackUrl)
        }
    }

    fun play(url: String) {
        resetPlaybackStateForNewMedia()
        playRequested = true
        state = NativePlaybackState.PREPARING
        Log.d(TAG, "play viewsAttached=$viewsAttached url=${debugUrl(url)} ${describeSurface(surfaceView)}")
        if (!viewsAttached) {
            pendingUrl = url
            Log.d(TAG, "play deferred until surface attach url=${debugUrl(url)}")
            return
        }

        startPlayback(url)
    }

    fun setPlaying(isPlaying: Boolean) {
        Log.d(TAG, "setPlaying isPlaying=$isPlaying currentlyPlaying=${mediaPlayer.isPlaying}")
        playRequested = isPlaying
        if (isPlaying) {
            if (!mediaPlayer.isPlaying) {
                mediaPlayer.play()
            }
        } else if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            state = NativePlaybackState.PAUSED
        }
    }

    fun snapshot(): NativePlaybackSnapshot {
        val positionMs = mediaPlayer.time.coerceAtLeast(0L)
        val durationMs = mediaPlayer.length.coerceAtLeast(0L)
        val isActuallyPlaying = mediaPlayer.isPlaying
        val normalizedState = normalizePlaybackState(positionMs = positionMs, isActuallyPlaying = isActuallyPlaying)

        return NativePlaybackSnapshot(
            engine = NativePlaybackEngine.VLC,
            state = normalizedState,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferingPercent = lastBufferingPercent,
            videoLayout = videoLayout,
            error = error,
        )
    }

    fun stop() {
        pendingUrl = null
        playRequested = false
        state = NativePlaybackState.IDLE
        lastBufferingPercent = null
        Log.d(TAG, "stop viewsAttached=$viewsAttached ${describeSurface(surfaceView)}")
        mediaPlayer.stop()
    }

    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) {
            return
        }
        mediaPlayer.time = positionMs
        lastProgressAdvanceAtElapsedMs = SystemClock.elapsedRealtime()
        lastObservedPositionMs = positionMs
    }

    fun release() {
        Log.d(TAG, "release viewsAttached=$viewsAttached ${describeSurface(surfaceView)}")
        mediaPlayer.stop()
        detachViewsIfNeeded()
        mediaPlayer.release()
        libVlc.release()
    }

    private fun handleMediaPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening,
            MediaPlayer.Event.Buffering,
            MediaPlayer.Event.Playing,
            MediaPlayer.Event.Paused,
            MediaPlayer.Event.Stopped,
            MediaPlayer.Event.EndReached,
            MediaPlayer.Event.EncounteredError -> {
                val bufferingSuffix =
                    if (event.type == MediaPlayer.Event.Buffering) {
                        " buffering=${event.buffering}"
                    } else {
                        ""
                    }
                Log.d(
                    TAG,
                    "event=${eventName(event.type)}$bufferingSuffix isPlaying=${mediaPlayer.isPlaying} timeMs=${mediaPlayer.time} lengthMs=${mediaPlayer.length} ${describeSurface(surfaceView)}",
                )
            }
        }

        when (event.type) {
            MediaPlayer.Event.Opening -> {
                state = NativePlaybackState.PREPARING
                error = null
                lastBufferingPercent = 0f
            }

            MediaPlayer.Event.Buffering -> {
                lastBufferingPercent = event.buffering.coerceIn(0f, 100f)
                lastBufferingEventAtElapsedMs = SystemClock.elapsedRealtime()
                if (!hasStartedPlayback || !mediaPlayer.isPlaying) {
                    state =
                        if (lastBufferingPercent == 100f && mediaPlayer.isPlaying) {
                            NativePlaybackState.PLAYING
                        } else {
                            NativePlaybackState.BUFFERING
                        }
                }
            }

            MediaPlayer.Event.Playing -> {
                hasStartedPlayback = true
                state = NativePlaybackState.PLAYING
                lastBufferingPercent = 100f
                lastProgressAdvanceAtElapsedMs = SystemClock.elapsedRealtime()
            }

            MediaPlayer.Event.Paused -> {
                state = NativePlaybackState.PAUSED
            }

            MediaPlayer.Event.Stopped -> {
                state = NativePlaybackState.IDLE
                lastBufferingPercent = null
            }

            MediaPlayer.Event.EndReached -> {
                playRequested = false
                state = NativePlaybackState.ENDED
                lastBufferingPercent = 100f
            }

            MediaPlayer.Event.EncounteredError -> {
                playRequested = false
                state = NativePlaybackState.ERROR
                error =
                    NativePlaybackError(
                        token = nextErrorToken++,
                        message = "VLC encountered playback error",
                        codecLikely = false,
                    )
            }
        }
    }

    private fun normalizePlaybackState(
        positionMs: Long,
        isActuallyPlaying: Boolean,
    ): NativePlaybackState {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (!isActuallyPlaying || positionMs > lastObservedPositionMs + POSITION_ADVANCE_TOLERANCE_MS) {
            lastProgressAdvanceAtElapsedMs = nowElapsedMs
        }
        lastObservedPositionMs = positionMs

        if (error != null) {
            return NativePlaybackState.ERROR
        }
        if (state == NativePlaybackState.ENDED) {
            return NativePlaybackState.ENDED
        }

        if (isActuallyPlaying) {
            val hasRecentBufferingSignal =
                lastBufferingEventAtElapsedMs > 0L &&
                    nowElapsedMs - lastBufferingEventAtElapsedMs <= BUFFERING_SIGNAL_GRACE_MS
            val hasStalledPlayback =
                hasStartedPlayback &&
                    hasRecentBufferingSignal &&
                    nowElapsedMs - lastProgressAdvanceAtElapsedMs >= PLAYBACK_STALL_THRESHOLD_MS

            return if (hasStalledPlayback) {
                NativePlaybackState.BUFFERING
            } else {
                NativePlaybackState.PLAYING
            }
        }

        return when (state) {
            NativePlaybackState.IDLE -> {
                if (playRequested || pendingUrl != null) {
                    NativePlaybackState.PREPARING
                } else {
                    NativePlaybackState.IDLE
                }
            }
            NativePlaybackState.PREPARING -> NativePlaybackState.PREPARING
            NativePlaybackState.BUFFERING -> {
                if (hasStartedPlayback) {
                    NativePlaybackState.BUFFERING
                } else {
                    NativePlaybackState.PREPARING
                }
            }
            NativePlaybackState.PLAYING -> {
                if (playRequested) {
                    NativePlaybackState.BUFFERING
                } else {
                    NativePlaybackState.PAUSED
                }
            }
            NativePlaybackState.PAUSED -> NativePlaybackState.PAUSED
            NativePlaybackState.ENDED -> NativePlaybackState.ENDED
            NativePlaybackState.ERROR -> NativePlaybackState.ERROR
        }
    }

    private fun startPlayback(url: String) {
        Log.d(TAG, "startPlayback url=${debugUrl(url)} viewsAttached=$viewsAttached ${describeSurface(surfaceView)}")
        applyBestFitScaling()
        val media =
            Media(libVlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)
            }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        updateWindowSize(surfaceView)
    }

    private fun detachViewsIfNeeded() {
        if (!viewsAttached) {
            return
        }

        val detachedSurface = surfaceView
        Log.d(TAG, "detachViewsIfNeeded ${describeSurface(detachedSurface)}")
        unregisterSurfaceDiagnostics(detachedSurface)
        mediaPlayer.vlcVout.removeCallback(voutCallback)
        mediaPlayer.vlcVout.detachViews()
        viewsAttached = false
        surfaceView = null
    }

    private fun registerSurfaceDiagnostics(view: SurfaceView) {
        view.holder.addCallback(surfaceHolderCallback)
        view.addOnLayoutChangeListener(surfaceLayoutListener)
        Log.d(TAG, "surfaceDiagnostics attached ${describeSurface(view)}")
    }

    private fun unregisterSurfaceDiagnostics(view: SurfaceView?) {
        if (view == null) {
            return
        }

        view.holder.removeCallback(surfaceHolderCallback)
        view.removeOnLayoutChangeListener(surfaceLayoutListener)
        Log.d(TAG, "surfaceDiagnostics removed ${describeSurface(view)}")
    }

    private fun updateWindowSize(view: SurfaceView?) {
        if (!viewsAttached || view == null) {
            return
        }

        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            return
        }

        mediaPlayer.vlcVout.setWindowSize(width, height)
        applyBestFitScaling()
        Log.d(TAG, "updateWindowSize width=$width height=$height layout=$videoLayout")
    }

    private fun applyBestFitScaling() {
        mediaPlayer.setAspectRatio(null)
        mediaPlayer.setScale(0f)
    }

    private fun resetPlaybackStateForNewMedia() {
        pendingUrl = null
        state = NativePlaybackState.IDLE
        lastBufferingPercent = null
        lastBufferingEventAtElapsedMs = 0L
        lastProgressAdvanceAtElapsedMs = 0L
        lastObservedPositionMs = 0L
        hasStartedPlayback = false
        videoLayout = null
        error = null
    }

    private fun describeSurface(view: SurfaceView?): String {
        if (view == null) {
            return "surface=null"
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val holderFrame = view.holder.surfaceFrame
        val surfaceValid = view.holder.surface.isValid

        return buildString {
            append("viewHash=")
            append(System.identityHashCode(view))
            append(" size=")
            append(view.width)
            append('x')
            append(view.height)
            append(" screenPos=")
            append(location[0])
            append(',')
            append(location[1])
            append(" attached=")
            append(view.isAttachedToWindow)
            append(" laidOut=")
            append(view.isLaidOut)
            append(" holderFrame=")
            append(holderFrame)
            append(" surfaceValid=")
            append(surfaceValid)
        }
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

    private fun eventName(type: Int): String {
        return when (type) {
            MediaPlayer.Event.Opening -> "Opening"
            MediaPlayer.Event.Buffering -> "Buffering"
            MediaPlayer.Event.Playing -> "Playing"
            MediaPlayer.Event.Paused -> "Paused"
            MediaPlayer.Event.Stopped -> "Stopped"
            MediaPlayer.Event.EndReached -> "EndReached"
            MediaPlayer.Event.EncounteredError -> "EncounteredError"
            else -> type.toString()
        }
    }

    companion object {
        private const val TAG = "VlcPlaybackRuntime"
        private const val POSITION_ADVANCE_TOLERANCE_MS = 150L
        private const val PLAYBACK_STALL_THRESHOLD_MS = 1_000L
        private const val BUFFERING_SIGNAL_GRACE_MS = 1_500L
    }
}
