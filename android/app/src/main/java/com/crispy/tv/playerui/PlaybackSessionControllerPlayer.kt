package com.crispy.tv.playerui

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import com.crispy.tv.nativeengine.playback.PlaybackSessionController
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Bridges the app's engine-agnostic [PlaybackSessionController] (ExoPlayer or libmpv) to the
 * Media3 [Player] contract so it can back a Media3 [androidx.media3.session.MediaSession].
 *
 * Media3 requires a real [Player]; for a custom playback engine the documented approach is a
 * [SimpleBasePlayer] that mirrors the controller's snapshot into [getState] and forwards transport
 * commands to the controller. Media3 then owns all external session/notification state syncing.
 */
internal class PlaybackSessionControllerPlayer(
    context: Context,
    private val controller: PlaybackSessionController,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var title: String = "Player"
    private var subtitle: String? = null
    private var artworkData: ByteArray? = null

    fun updateMetadata(title: String, subtitle: String?, artworkData: ByteArray?) {
        this.title = title
        this.subtitle = subtitle
        this.artworkData = artworkData
        invalidateState()
    }

    fun invalidatePlaybackState() {
        invalidateState()
    }

    override fun getState(): State {
        val snapshot: NativePlaybackSnapshot = controller.snapshot()
        val playbackState = when (snapshot.state) {
            NativePlaybackState.IDLE -> Player.STATE_IDLE
            NativePlaybackState.PREPARING -> Player.STATE_BUFFERING
            NativePlaybackState.BUFFERING -> Player.STATE_BUFFERING
            NativePlaybackState.PLAYING -> Player.STATE_READY
            NativePlaybackState.PAUSED -> Player.STATE_READY
            NativePlaybackState.ENDED -> Player.STATE_ENDED
            NativePlaybackState.ERROR -> Player.STATE_IDLE
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(MEDIA_ITEM_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .apply {
                        artworkData?.let {
                            setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        }
                    }
                    .build(),
            )
            .build()

        val durationUs = if (snapshot.durationMs > 0L) snapshot.durationMs * 1000L else C.TIME_UNSET
        val timeline = SinglePeriodTimeline(durationUs, true, false, false, null, mediaItem)

        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    // Reads: getState() mirrors the full controller snapshot, so every
                    // getter is genuinely supported. SimpleBasePlayer does not add these
                    // implicitly, and Media3 publishes EMPTY metadata and duration=-1 to
                    // the platform session without them, which kills the system media
                    // card's seek bar and artwork.
                    .add(Player.COMMAND_GET_TIMELINE)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_TRACKS)
                    .add(Player.COMMAND_GET_TRACK_SELECTION_PARAMETERS)
                    // Controls forwarded to the playback controller in handle* below.
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .add(Player.COMMAND_STOP)
                    .build(),
            )
            .setPlayWhenReady(snapshot.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setContentPositionMs(snapshot.positionMs)
            .setSeekBackIncrementMs(REWIND_MS)
            .setSeekForwardIncrementMs(FAST_FORWARD_MS)
            .setPlaybackParameters(PlaybackParameters(snapshot.playbackSpeed))
            .setCurrentMediaItemIndex(0)
            .setPlaylist(timeline, Tracks.EMPTY, mediaItem.mediaMetadata)

        if (snapshot.state == NativePlaybackState.ERROR) {
            builder.setPlayerError(
                PlaybackException(
                    snapshot.error?.message ?: "Playback error",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                ),
            )
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        controller.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
    ): ListenableFuture<*> {
        controller.seekTo(positionMs.coerceAtLeast(0L))
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        // The controller is owned by the playback session; releasing the MediaSession must not
        // tear down the underlying engine.
        return Futures.immediateVoidFuture()
    }

    private companion object {
        private const val MEDIA_ITEM_ID = "crispy-playback"
        private const val REWIND_MS = 10_000L
        private const val FAST_FORWARD_MS = 30_000L
    }
}
