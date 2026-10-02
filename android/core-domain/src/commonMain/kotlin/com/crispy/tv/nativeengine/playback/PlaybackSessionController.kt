package com.crispy.tv.nativeengine.playback

/**
 * The playback port: everything an engine can be *asked* to do, in terms that
 * name no platform.
 *
 * This is the seam that makes a second engine possible. It was already an
 * interface in `:android:native-engine` with ExoPlayer and libmpv behind it,
 * but it lived in a plain `com.android.library` with no JVM variant, so a KMP
 * `commonMain` could not name it and every consumer of the types it returns was
 * pinned to `androidMain` for the same reason. It is now declared here,
 * unchanged -- **all thirteen members already used only the lifted value
 * types**, so this was a location change and not an interface change.
 *
 * What did *not* come with it is [PlaybackSurfaceController], the other half of
 * the old `PlaybackController`. All four of its members take a `PlayerView`, a
 * `SurfaceView` or a `Context`, because the video surface is the one part of
 * playback that genuinely has no portable form. An engine that can report a
 * snapshot does not need to know how a view is attached, and keeping the two
 * apart is what lets a non-Android engine implement this interface while
 * Android keeps its own `PlaybackController` composition of both.
 *
 * @see NativePlaybackTypes for the types these signatures use.
 */
interface PlaybackSessionController {
    fun play(source: PlaybackSource, engine: NativePlaybackEngine)
    fun setPlaying(isPlaying: Boolean)
    fun snapshot(): NativePlaybackSnapshot
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean)
    fun selectAudioTrack(trackId: String?)
    fun selectSubtitleTrack(trackId: String?)
    fun setExternalSubtitle(subtitle: PlaybackExternalSubtitle?)
    fun setSubtitleDelayMs(delayMs: Int)
    fun applyResizeMode(mode: PlayerResizeMode)
}
