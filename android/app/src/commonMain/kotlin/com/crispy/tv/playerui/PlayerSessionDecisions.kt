package com.crispy.tv.playerui

import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot
import com.crispy.tv.nativeengine.playback.NativePlaybackState

/**
 * The decisions `PlayerSessionViewModel` makes, with the side effects left behind.
 *
 * ## Why these are here and not in the view model
 *
 * `PlayerSessionViewModel` is 1,411 lines and 54 functions, and it cannot be tested:
 * constructing one needs a real player. The work that splits it by use case therefore
 * has to be able to answer "what does this code *decide*" for each piece it moves, and
 * today the answer is only available by reading it. These four decisions are extracted
 * first, on their own, so that the split has pinned answers to preserve rather than
 * behaviour to rediscover.
 *
 * This is a pure refactor. Every body below is the body that was in the view model, and
 * the only text change is that the inline literal `1_000L` is now
 * [INITIAL_SEEK_TOLERANCE_MS].
 *
 * ## Why this file is in `commonMain`, and why that used to read as impossible
 *
 * **The first version of this note said the opposite: that nothing here could move to
 * `commonMain` because [NativePlaybackEngine], [NativePlaybackState],
 * [NativePlaybackError] and [NativePlaybackSnapshot] were declared in
 * `:android:native-engine`.** They were, and that was true at the time, but it described
 * a *location* rather than a wall, and it was the reason a 162-line file with no
 * `android.*` and no `java.*` import was filed as blocked for the rest of Phase 4.
 *
 * `:android:native-engine` is a plain `com.android.library` and so publishes no JVM
 * variant, which means a `commonMain` cannot name anything declared in it **whatever the
 * code looks like**. That part was right. What it missed is that the pin rides in on the
 * *type*, and the types are portable: seven of them and the `PlaybackSessionController`
 * port itself were lifted into `:core-domain`'s `commonMain` under this same package,
 * which is why moving this file needed no import edit anywhere.
 *
 * The lesson worth keeping is the one the engine-preference enum already demonstrated:
 * **a file can be portable in every line it writes and still be unpinnable, and what
 * decides is the *kind* of the names it mentions, not how much platform API it calls.** This
 * file calls none. It named data types that had no business living in an Android
 * library.
 *
 * Its only production consumer is `PlayerSessionViewModel`, which is still in `androidMain`
 * because it holds the playback controller and the `Context` wiring. It reaches this
 * file with no import, from the same package.
 */

/**
 * How far the player may already have advanced and still count as "at" the pending
 * initial seek position.
 *
 * This was the inline literal `1_000L`. Naming it is a same-value change, and the boundary
 * it defines is pinned on both sides in `PlayerSessionDecisionsTest` -- a tolerance of the
 * wrong sign would either seek on every snapshot or never seek at all, and neither is
 * visible without a real player.
 */
internal const val INITIAL_SEEK_TOLERANCE_MS = 1_000L

/**
 * The engine the session starts on.
 *
 * mpv is the only engine left, so this always answers [NativePlaybackEngine.MPV]:
 * the stored preference it used to read is gone with the engine picker, and a
 * function that always returns one value exists only to keep the call site's
 * shape -- which is fine, because the call site reads better naming the
 * decision than spelling the enum at every use.
 */
internal fun resolveInitialEngine(): NativePlaybackEngine = NativePlaybackEngine.MPV

/**
 * The user-facing line for a playback state.
 *
 * `ERROR` prefers the engine's own message and falls back only when there is none. The
 * distinction matters: an engine-supplied message is the diagnostic, and replacing it with
 * the generic string would lose the only thing the user can act on.
 */
internal fun statusMessage(snapshot: NativePlaybackSnapshot): String {
    return when (snapshot.state) {
        NativePlaybackState.IDLE,
        NativePlaybackState.PREPARING -> "Preparing playback..."
        NativePlaybackState.BUFFERING -> "Buffering..."
        NativePlaybackState.PLAYING -> "Playing"
        NativePlaybackState.PAUSED -> "Paused"
        NativePlaybackState.ENDED -> "Playback ended."
        NativePlaybackState.ERROR -> snapshot.error?.message ?: "Playback error"
    }
}

/** What to do with a pending initial seek when a new playback snapshot arrives. */
internal sealed interface InitialSeekDecision {
    /** Leave the pending seek armed; the player is not ready for it yet. */
    data object Wait : InitialSeekDecision

    /** Drop the pending seek without seeking: it is stale or already satisfied. */
    data object Clear : InitialSeekDecision

    /** Seek to [positionMs] and drop the pending seek. */
    data class Seek(val positionMs: Long) : InitialSeekDecision
}

/**
 * Whether a pending initial seek should wait, be dropped, or be performed now.
 *
 * ## The order is the behaviour
 *
 * The four exits are checked in this order, and two of them can both be true of the same
 * snapshot. A `PREPARING` snapshot whose position has *already* reached the target is
 * [Wait], not [Clear], because the readiness check comes first -- so the pending seek
 * survives to be applied once the player is up. Reordering these two checks would make
 * that snapshot drop the seek and the user would resume at the wrong place. Both readings
 * are pinned.
 *
 * The tolerance comparison is `positionMs >= target - INITIAL_SEEK_TOLERANCE_MS`, so
 * "within a second" counts as already there. It is deliberately not `>`: resuming exactly
 * at the target should not seek again.
 */
internal fun initialSeekDecision(
    pendingInitialSeekMs: Long?,
    state: NativePlaybackState,
    positionMs: Long,
): InitialSeekDecision {
    val targetPositionMs = pendingInitialSeekMs ?: return InitialSeekDecision.Wait
    if (targetPositionMs <= 0L) {
        return InitialSeekDecision.Clear
    }
    if (state == NativePlaybackState.IDLE || state == NativePlaybackState.PREPARING) {
        return InitialSeekDecision.Wait
    }
    if (positionMs >= targetPositionMs - INITIAL_SEEK_TOLERANCE_MS) {
        return InitialSeekDecision.Clear
    }
    return InitialSeekDecision.Seek(targetPositionMs)
}

