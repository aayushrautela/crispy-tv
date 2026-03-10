package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
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
                        "event=${eventName(event.type)}$bufferingSuffix isPlaying=${runCatching { mediaPlayer.isPlaying }.getOrDefault(false)} timeMs=${runCatching { mediaPlayer.time }.getOrDefault(-1L)} lengthMs=${runCatching { mediaPlayer.length }.getOrDefault(-1L)}",
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
        Log.d(TAG, "createSurfaceView viewHash=${System.identityHashCode(created)}")
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
            "attach viewHash=${System.identityHashCode(view)} replacingViewHash=${surfaceView?.let(System::identityHashCode)} pendingUrl=${pendingUrl?.hashCode()}",
        )
        detachViewsIfNeeded()
        surfaceView = view
        mediaPlayer.vlcVout.setVideoView(view)
        mediaPlayer.vlcVout.attachViews()
        viewsAttached = true

        pendingUrl?.let {
            pendingUrl = null
            startPlayback(it)
        }
    }

    fun play(url: String) {
        Log.d(TAG, "play viewsAttached=$viewsAttached url=${debugUrl(url)}")
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
        Log.d(TAG, "stop viewsAttached=$viewsAttached")
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
        Log.d(TAG, "release viewsAttached=$viewsAttached")
        runCatching { mediaPlayer.stop() }
        detachViewsIfNeeded()
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }

    private fun startPlayback(url: String) {
        Log.d(TAG, "startPlayback url=${debugUrl(url)} viewsAttached=$viewsAttached")
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

        Log.d(TAG, "detachViewsIfNeeded viewHash=${surfaceView?.let(System::identityHashCode)}")
        runCatching { mediaPlayer.vlcVout.detachViews() }
        viewsAttached = false
        surfaceView = null
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
