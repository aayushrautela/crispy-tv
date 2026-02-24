package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
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
        mediaPlayer.setEventListener { event ->
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
        attach(created)
        return created
    }

    fun attach(view: SurfaceView) {
        if (surfaceView === view && viewsAttached) {
            return
        }

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
        if (!viewsAttached) {
            pendingUrl = url
            return
        }

        startPlayback(url)
    }

    fun setPlaying(isPlaying: Boolean) {
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
        runCatching { mediaPlayer.stop() }
        detachViewsIfNeeded()
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }

    private fun startPlayback(url: String) {
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

        runCatching { mediaPlayer.vlcVout.detachViews() }
        viewsAttached = false
        surfaceView = null
    }
}
