package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.view.SurfaceView

/**
 * The hero trailer's player: an mpv instance of its own, separate from the
 * one the full player uses.
 *
 * The trailer and the player are never on screen at the same time, but they
 * are not the same thing either: the trailer is a muted, cropped, looped
 * background clip that must survive the details screen being scrolled away
 * from, while the player owns the session, the tracks and the chapter list.
 * Sharing one instance would make the trailer's mute and crop fight the
 * player's own state.
 *
 * It is a wrapper rather than a bare [MpvPlaybackRuntime] because that class
 * is `internal` to this module, and because the trailer's needs are narrower:
 * a URL, a mute flag, a crop flag and a state to poll.
 */
class NativeTrailerPlayer(context: Context) {
    // `gpu`, not the tunneled `mediacodec_embed`: the trailer has no HDR to
    // preserve and needs panscan, which the tunneled vo ignores.
    private val runtime = MpvPlaybackRuntime(context.applicationContext, forceGpuVo = true)

    /** The view the trailer is drawn into. It must be created by this player. */
    fun createSurfaceView(viewContext: Context): SurfaceView = runtime.createSurfaceView(viewContext)

    /** Binds [view] to this player, replacing any previously bound surface. */
    fun attach(view: SurfaceView) {
        runtime.attach(view)
    }

    /**
     * Loads [videoUrl].
     *
     * [audioUrl] is a separate audio stream for the same clip -- the YouTube
     * trailer case, where the video and the audio come from two different
     * URLs. mpv takes it as the `audio-file` option of the same load.
     */
    fun play(videoUrl: String, audioUrl: String? = null) {
        runtime.play(PlaybackSource(url = videoUrl), externalAudioUrl = audioUrl)
    }

    fun setPlaying(isPlaying: Boolean) {
        runtime.setPlaying(isPlaying)
    }

    fun setMuted(muted: Boolean) {
        runtime.setMuted(muted)
    }

    /**
     * [cropped] cover-crops the trailer to fill its surface, the way the hero
     * did under Exo's `RESIZE_MODE_ZOOM`. The trailer runs on `vo=gpu`, so mpv
     * honours the crop and the UI neither sizes nor clips the surface.
     */
    fun setCropped(cropped: Boolean) {
        runtime.setCoverCrop(cropped)
    }

    fun snapshot(): NativePlaybackSnapshot = runtime.snapshot()

    fun stop() {
        runtime.stop()
    }

    fun release() {
        runtime.release()
    }
}
