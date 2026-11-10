package com.crispy.tv.playerui

import android.media.session.MediaSession
import com.crispy.tv.nativeengine.playback.PlaybackSessionController

/**
 * The platform media session's callback: it turns the system's transport
 * controls into calls on the playback port.
 *
 * The platform [MediaSession] has no notion of a player object -- it asks for
 * a playback state and calls back here when the user presses something. So
 * this class is the whole of the "player" the session talks to, and the
 * playback port is what it talks to.
 */
internal class PlaybackSessionCallback(
    private val playbackController: PlaybackSessionController,
) : MediaSession.Callback() {
    override fun onPlay() {
        playbackController.setPlaying(true)
    }

    override fun onPause() {
        playbackController.setPlaying(false)
    }

    override fun onStop() {
        playbackController.stop()
    }

    override fun onSeekTo(pos: Long) {
        playbackController.seekTo(pos)
    }

    override fun onFastForward() {
        playbackController.seekTo(currentPositionMs() + FAST_FORWARD_MS)
    }

    override fun onRewind() {
        playbackController.seekTo((currentPositionMs() - REWIND_MS).coerceAtLeast(0L))
    }

    /**
     * The position the fast-forward and rewind jumps are measured from.
     *
     * It comes from the controller's own snapshot rather than from a value
     * pushed in: a jump is a decision about *now*, and the snapshot is the
     * only place that knows now. A pushed-in position would be the one read
     * at the last poll, which is stale by up to a poll interval.
     */
    private fun currentPositionMs(): Long = playbackController.snapshot().positionMs

    companion object {
        private const val REWIND_MS = 10_000L
        private const val FAST_FORWARD_MS = 30_000L
    }
}
