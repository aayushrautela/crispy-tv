package com.crispy.tv.playerui

import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackEnginePreference
import com.crispy.tv.nativeengine.playback.NativePlaybackError
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
 * [INITIAL_SEEK_TOLERANCE_MS]. Nothing here moves a file into `commonMain`, and nothing
 * could: [NativePlaybackEngine], [NativePlaybackState], [NativePlaybackError] and
 * [NativePlaybackSnapshot] are declared in `:android:native-engine`, which is a plain
 * `com.android.library` and so publishes no JVM variant. `:app` cannot name those types
 * from a `commonMain` source set whatever the code looks like, which is the wall Phase 5
 * has to remove before any of this becomes portable.
 *
 * [NativePlaybackEnginePreference] is the interesting counter-example and is worth reading
 * before planning that work: it is *not* in `:native-engine`. It sits in `:core-domain`'s
 * `commonMain`, because a preference is a stored setting carried by `PlaybackSettings` in
 * its public interface, and it was moved out precisely because leaving it there pinned
 * every settings type that mentioned it to `androidMain` **by the module type rather than
 * by anything in the code**. [resolveInitialEngine] is the clearest illustration of what
 * remains: its input is portable and its output is not, so the function has to stay here
 * for exactly one reason.
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
 * The engine the session starts on, for a stored preference.
 *
 * **`Auto` resolving to ExoPlayer is a product decision, not an oversight.** "Auto" does
 * not mean "pick the best engine"; it means *start on ExoPlayer and switch to MPV if
 * ExoPlayer fails on a codec* -- which is [shouldFallBackToMpv]. A reader reasonably
 * assumes otherwise, which is why this is pinned rather than left implicit.
 */
internal fun resolveInitialEngine(preference: NativePlaybackEnginePreference): NativePlaybackEngine =
    if (preference == NativePlaybackEnginePreference.Libmpv) {
        NativePlaybackEngine.MPV
    } else {
        NativePlaybackEngine.EXO
    }

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

/**
 * Whether an ExoPlayer failure should be retried on the MPV fallback.
 *
 * All three conditions are required, and each is a separate reason to say no:
 *
 * - **not a codec problem** -- a network or DRM failure will fail identically on MPV, and
 *   switching engines would hide a real network error behind a second, identical failure.
 * - **already on ExoPlayer** -- the fallback is a change *away* from ExoPlayer, so a
 *   failure on the fallback engine has nothing to fall back to.
 * - **the user has not pinned ExoPlayer** -- this is the conjunct people expect to be
 *   "pinned to MPV", and it is not. A user who pinned **libmpv** and somehow reached
 *   ExoPlayer still falls back, because the alternative is a codec error with no retry at
 *   all. What "pinned to ExoPlayer" means is *this choice has already been made, so stop
 *   asking me*.
 *
 * That last point is the one worth testing, because reversing the conjunct to
 * `preference == Libmpv` compiles, reads sensibly, and makes `Auto` never fall back -- which
 * would quietly reduce `Auto` to `ExoPlayer`.
 *
 * The caller assigns its "already handled this error" token *before* calling this, and that
 * ordering is load-bearing: a non-codec error still consumes its token, so a snapshot that
 * repeats the same failure is not re-handled. It stays in the view model.
 */
internal fun shouldFallBackToMpv(
    error: NativePlaybackError,
    engine: NativePlaybackEngine,
    preference: NativePlaybackEnginePreference,
): Boolean {
    return error.codecLikely &&
        engine == NativePlaybackEngine.EXO &&
        preference != NativePlaybackEnginePreference.ExoPlayer
}
