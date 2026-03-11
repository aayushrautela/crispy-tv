package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

internal class VlcPlaybackRuntime(
    context: Context,
    private val onEvent: (NativePlaybackEvent) -> Unit
) {
    private val libVlc: LibVLC = LibVLC(
        context,
        arrayListOf(
            "--network-caching=1000",
            "--file-caching=1000",
            "--audio-time-stretch"
        )
    )

    private val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)
    private var surfaceView: SurfaceView? = null
    private var viewsAttached: Boolean = false
    private var pendingUrl: String? = null
    private val surfaceHolderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(
                    TAG,
                    "surfaceCreated isCurrent=${surfaceView?.holder === holder} ${describeSurface(surfaceView)}",
                )
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
        }

    init {
        Log.d(TAG, "init networkCachingMs=1000 fileCachingMs=1000")
        mediaPlayer.setEventListener { event ->
            when (event.type) {
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
                        "event=${eventName(event.type)}$bufferingSuffix isPlaying=${runCatching { mediaPlayer.isPlaying }.getOrDefault(false)} timeMs=${runCatching { mediaPlayer.time }.getOrDefault(-1L)} lengthMs=${runCatching { mediaPlayer.length }.getOrDefault(-1L)} ${describeSurface(surfaceView)}",
                    )
                }
            }
            when (event.type) {
                MediaPlayer.Event.Buffering -> onEvent(NativePlaybackEvent.Buffering)
                MediaPlayer.Event.Playing -> onEvent(NativePlaybackEvent.Ready)
                MediaPlayer.Event.EndReached -> onEvent(NativePlaybackEvent.Ended)
                MediaPlayer.Event.EncounteredError -> {
                    onEvent(
                        NativePlaybackEvent.Error(
                            message = "VLC encountered playback error",
                            codecLikely = false
                        )
                    )
                }
            }
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
            return
        }

        Log.d(
            TAG,
            "attach replacingViewHash=${surfaceView?.let(System::identityHashCode)} pendingUrl=${pendingUrl?.hashCode()} ${describeSurface(view)}",
        )
        detachViewsIfNeeded()
        surfaceView = view
        registerSurfaceDiagnostics(view)
        mediaPlayer.vlcVout.setVideoView(view)
        mediaPlayer.vlcVout.attachViews()
        viewsAttached = true
        Log.d(TAG, "attach complete ${describeSurface(view)}")

        pendingUrl?.let {
            pendingUrl = null
            startPlayback(it)
        }
    }

    fun play(url: String) {
        Log.d(TAG, "play viewsAttached=$viewsAttached url=${debugUrl(url)} ${describeSurface(surfaceView)}")
        if (!viewsAttached) {
            pendingUrl = url
            Log.d(TAG, "play deferred until surface attach url=${debugUrl(url)}")
            return
        }

        startPlayback(url)
    }

    fun setPlaying(isPlaying: Boolean) {
        Log.d(TAG, "setPlaying isPlaying=$isPlaying currentlyPlaying=${runCatching { mediaPlayer.isPlaying }.getOrDefault(false)}")
        if (isPlaying) {
            val currentlyPlaying = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
            if (!currentlyPlaying) {
                runCatching { mediaPlayer.play() }
            }
        } else {
            val currentlyPlaying = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
            if (currentlyPlaying) {
                runCatching { mediaPlayer.pause() }
            }
        }
    }

    fun isPlaying(): Boolean {
        return runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
    }

    fun stop() {
        pendingUrl = null
        Log.d(TAG, "stop viewsAttached=$viewsAttached ${describeSurface(surfaceView)}")
        runCatching { mediaPlayer.stop() }
    }

    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) {
            return
        }
        runCatching { mediaPlayer.time = positionMs }
    }

    fun currentPositionMs(): Long {
        return runCatching { mediaPlayer.time.coerceAtLeast(0L) }.getOrDefault(0L)
    }

    fun durationMs(): Long {
        return runCatching { mediaPlayer.length.coerceAtLeast(0L) }.getOrDefault(0L)
    }

    fun release() {
        Log.d(TAG, "release viewsAttached=$viewsAttached ${describeSurface(surfaceView)}")
        runCatching { mediaPlayer.stop() }
        detachViewsIfNeeded()
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }

    private fun startPlayback(url: String) {
        Log.d(TAG, "startPlayback url=${debugUrl(url)} viewsAttached=$viewsAttached ${describeSurface(surfaceView)}")
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
        }

        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    private fun detachViewsIfNeeded() {
        if (!viewsAttached) {
            return
        }

        val detachedSurface = surfaceView
        Log.d(TAG, "detachViewsIfNeeded ${describeSurface(detachedSurface)}")
        unregisterSurfaceDiagnostics(detachedSurface)
        runCatching { mediaPlayer.vlcVout.detachViews() }
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

        runCatching { view.holder.removeCallback(surfaceHolderCallback) }
        runCatching { view.removeOnLayoutChangeListener(surfaceLayoutListener) }
        Log.d(TAG, "surfaceDiagnostics removed ${describeSurface(view)}")
    }

    private fun describeSurface(view: SurfaceView?): String {
        if (view == null) {
            return "surface=null"
        }

        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val holderFrame = runCatching { view.holder.surfaceFrame }.getOrNull()
        val surfaceValid = runCatching { view.holder.surface?.isValid }.getOrDefault(false)

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

    private fun eventName(type: Int): String {
        return when (type) {
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
    }
}
