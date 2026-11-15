package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView

/**
 * The playback controller: mpv, and nothing else.
 *
 * ExoPlayer used to be the second engine behind this class, and every
 * method here carried a branch that chose between the two. Those branches
 * are gone, and what is left is the mpv runtime plus the two pieces of
 * state the runtime cannot own: which media is loaded (so a replay of the
 * same URL keeps the user's external subtitle) and which external subtitle
 * is attached right now.
 */
class NativePlaybackController(
    context: Context,
) : PlaybackController {
    private val appContext = context.applicationContext
    private val mpvRuntime = MpvPlaybackRuntime(appContext)
    private var lastPlaybackSource: PlaybackSource? = null
    private var activeExternalSubtitle: PlaybackExternalSubtitle? = null

    override fun play(
        source: PlaybackSource,
        engine: NativePlaybackEngine,
    ) {
        // `engine` belongs to the port, not to this controller: mpv is the only
        // engine left, so the argument is read for the log line and nothing
        // else. The caller still decides *what* to play.
        Log.d(TAG, "play requested engine=$engine url=${debugUrl(source.url)}")
        // A replay of the same media (a retry) keeps the user-chosen external
        // subtitle; a different media starts fresh without it.
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
        mpvRuntime.play(effectiveSource)
        lastPlaybackSource = effectiveSource
    }

    override fun setPlaying(isPlaying: Boolean) {
        Log.d(TAG, "setPlaying isPlaying=$isPlaying")
        mpvRuntime.setPlaying(isPlaying)
    }

    override fun snapshot(): NativePlaybackSnapshot = mpvRuntime.snapshot()

    override fun seekTo(positionMs: Long) {
        mpvRuntime.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun stop() {
        Log.d(TAG, "stop")
        mpvRuntime.stop()
    }

    override fun release() {
        Log.d(TAG, "release")
        mpvRuntime.release()
    }

    override fun setPlaybackSpeed(speed: Float) {
        mpvRuntime.setPlaybackSpeed(if (speed.isFinite() && speed > 0f) speed else 1f)
    }

    override fun setMuted(muted: Boolean) {
        mpvRuntime.setMuted(muted)
    }

    override fun selectAudioTrack(trackId: String?) {
        Log.d(TAG, "selectAudioTrack id=$trackId")
        mpvRuntime.selectAudioTrack(trackId)
    }

    override fun selectSubtitleTrack(trackId: String?) {
        Log.d(TAG, "selectSubtitleTrack id=$trackId")
        mpvRuntime.selectSubtitleTrack(trackId)
    }

    override fun setExternalSubtitle(subtitle: PlaybackExternalSubtitle?) {
        Log.d(TAG, "setExternalSubtitle attached=${subtitle != null}")
        activeExternalSubtitle = subtitle
        mpvRuntime.setExternalSubtitle(subtitle)
    }

    override fun setSubtitleDelayMs(delayMs: Int) {
        mpvRuntime.setSubtitleDelayMs(delayMs)
    }

    override fun applyResizeMode(mode: PlayerResizeMode) {
        mpvRuntime.applyResizeMode(mode)
    }

    override fun selectChapter(index: Int) {
        mpvRuntime.selectChapter(index)
    }

    override fun setPictureInPictureActive(active: Boolean) {
        mpvRuntime.setPictureInPictureActive(active)
    }

    override fun setHdrOutputEnabled(enabled: Boolean) {
        mpvRuntime.setHdrOutputEnabled(enabled)
    }

    override fun createMpvSurfaceView(context: Context): SurfaceView {
        Log.d(TAG, "createMpvSurfaceView")
        return mpvRuntime.createSurfaceView(context)
    }

    override fun attachMpvSurface(surfaceView: SurfaceView) {
        Log.d(TAG, "attachMpvSurface viewHash=${System.identityHashCode(surfaceView)}")
        mpvRuntime.attach(surfaceView)
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

    companion object {
        private const val TAG = "NativePlaybackCtrl"
    }
}
