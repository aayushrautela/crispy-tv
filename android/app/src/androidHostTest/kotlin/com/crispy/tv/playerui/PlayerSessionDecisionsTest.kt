package com.crispy.tv.playerui

import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackEnginePreference
import com.crispy.tv.nativeengine.playback.NativePlaybackError
import com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests for the decisions extracted from
 * [PlayerSessionViewModel] into `PlayerSessionDecisions.kt`.
 *
 * ## Why these tests are in `androidHostTest` and not `commonTest`
 *
 * Every function here is a pure decision, but none of its parameter types is
 * portable: `NativePlaybackEngine`, `NativePlaybackState`, `NativePlaybackError`
 * and `NativePlaybackSnapshot` are all declared in `:android:native-engine`,
 * which is a plain `com.android.library` and publishes no JVM variant. A
 * `commonTest` cannot name them, so this suite runs on Android only. That is
 * the same wall that keeps `PlayerSessionViewModel` itself in `androidMain`,
 * and it is why the decisions had to be extracted before they could be pinned
 * at all -- the class is 1,411 lines and cannot be constructed without a real
 * player.
 *
 * ## What these tests are for
 *
 * The use-case split that follows rewrites the *call sites* of these four
 * functions. These tests are the answer key for that rewrite: they say what
 * each decision must keep returning, so a split that changes an answer fails
 * here rather than on a device.
 *
 * No Robolectric annotation is present because nothing here needs a `Context`,
 * a resource or a framework shadow -- the values are constructed directly.
 */
class PlayerSessionDecisionsTest {

    // ---------------------------------------------------------------- engine

    @Test
    fun onlyTheLibmpvPreferenceSelectsTheMpvEngine() {
        val expected = mapOf(
            NativePlaybackEnginePreference.Auto to NativePlaybackEngine.EXO,
            NativePlaybackEnginePreference.ExoPlayer to NativePlaybackEngine.EXO,
            NativePlaybackEnginePreference.Libmpv to NativePlaybackEngine.MPV,
        )
        assertEquals(
            "a preference value has no pinned engine, so the decision is unpinned",
            expected.keys,
            NativePlaybackEnginePreference.entries.toSet(),
        )
        for ((preference, engine) in expected) {
            assertEquals("$preference should start on $engine", engine, resolveInitialEngine(preference))
        }
    }

    @Test
    fun autoAndExoPlayerAgreeBecauseAutoMeansTryExoThenFallBack() {
        assertEquals(
            "Auto resolving to EXO is the product decision that makes the codec-error " +
                "fallback meaningful, not a 'pick the best engine' heuristic",
            resolveInitialEngine(NativePlaybackEnginePreference.ExoPlayer),
            resolveInitialEngine(NativePlaybackEnginePreference.Auto),
        )
    }

    // -------------------------------------------------------- status message

    @Test
    fun everyStateButErrorHasItsOwnStatusMessage() {
        val expected = mapOf(
            NativePlaybackState.IDLE to "Preparing playback...",
            NativePlaybackState.PREPARING to "Preparing playback...",
            NativePlaybackState.BUFFERING to "Buffering...",
            NativePlaybackState.PLAYING to "Playing",
            NativePlaybackState.PAUSED to "Paused",
            NativePlaybackState.ENDED to "Playback ended.",
        )
        assertEquals(
            "a state has no pinned message, so statusMessage is unpinned for it",
            expected.keys,
            NativePlaybackState.entries.filter { it != NativePlaybackState.ERROR }.toSet(),
        )
        for ((state, message) in expected) {
            assertEquals("wrong message for $state", message, statusMessage(snapshot(state = state)))
        }
    }

    @Test
    fun theErrorStatePrefersTheEnginesOwnMessage() {
        val error = NativePlaybackError(token = 7L, message = "decoder gave up", codecLikely = true)
        assertEquals(
            "the engine's message is more useful than any generic one",
            "decoder gave up",
            statusMessage(snapshot(state = NativePlaybackState.ERROR, error = error)),
        )
    }

    @Test
    fun theErrorStateFallsBackWhenTheEngineGaveNoMessage() {
        assertEquals(
            "a state with no error still needs a message",
            "Playback error",
            statusMessage(snapshot(state = NativePlaybackState.ERROR, error = null)),
        )
    }

    // --------------------------------------------------------- initial seek

    @Test
    fun theSeekToleranceIsOneSecond() {
        assertEquals(
            "the tolerance is the value the inline literal carried; changing it moves " +
                "the boundary in the next test",
            1_000L,
            INITIAL_SEEK_TOLERANCE_MS,
        )
    }

    @Test
    fun thereIsNoPendingSeekWhileNoneWasRequested() {
        assertEquals(
            InitialSeekDecision.Wait,
            initialSeekDecision(null, NativePlaybackState.PLAYING, positionMs = 0L),
        )
    }

    @Test
    fun aNonPositivePendingSeekIsDroppedRatherThanHonoured() {
        // A negative position is not a "seek backwards to before the start" request; it is
        // a stale value, and seeking to it would be a worse bug than ignoring it.
        for (target in listOf(0L, -1L, -60_000L)) {
            assertEquals(
                "a target of $target should be cleared, not sought",
                InitialSeekDecision.Clear,
                initialSeekDecision(target, NativePlaybackState.PLAYING, positionMs = 0L),
            )
        }
    }

    @Test
    fun anUnpreparedEngineWaitsRatherThanSeeks() {
        for (state in listOf(NativePlaybackState.IDLE, NativePlaybackState.PREPARING)) {
            assertEquals(
                "seeking an unprepared engine throws the position away on $state",
                InitialSeekDecision.Wait,
                initialSeekDecision(60_000L, state, positionMs = 0L),
            )
        }
    }

    @Test
    fun aPreparedEngineSeeksToThePendingPosition() {
        assertEquals(
            InitialSeekDecision.Seek(60_000L),
            initialSeekDecision(60_000L, NativePlaybackState.PLAYING, positionMs = 0L),
        )
    }

    @Test
    fun theToleranceBoundaryIsInclusive() {
        val target = 60_000L
        // positionMs >= target - tolerance clears. One millisecond either side of that
        // line is the whole claim, so both sides are pinned rather than the line itself.
        assertEquals(
            "one millisecond short of the tolerance must still seek",
            InitialSeekDecision.Seek(target),
            initialSeekDecision(target, NativePlaybackState.PLAYING, positionMs = target - INITIAL_SEEK_TOLERANCE_MS - 1L),
        )
        assertEquals(
            "exactly at the tolerance is already close enough",
            InitialSeekDecision.Clear,
            initialSeekDecision(target, NativePlaybackState.PLAYING, positionMs = target - INITIAL_SEEK_TOLERANCE_MS),
        )
        assertEquals(
            "past the tolerance is already close enough",
            InitialSeekDecision.Clear,
            initialSeekDecision(target, NativePlaybackState.PLAYING, positionMs = target - INITIAL_SEEK_TOLERANCE_MS + 1L),
        )
    }

    @Test
    fun thePreparedCheckComesBeforeTheAlreadyThereCheck() {
        // This is the ordering the implementation transcribes, and it is the one case
        // where the two middle exits disagree. Checking "already there" first would clear
        // the pending seek and the user would land at the start of the episode.
        assertEquals(
            "a preparing engine that has already reached the target must keep the seek",
            InitialSeekDecision.Wait,
            initialSeekDecision(
                60_000L,
                NativePlaybackState.PREPARING,
                positionMs = 60_000L,
            ),
        )
    }

    // ------------------------------------------------------ codec fallback

    @Test
    fun aCodecErrorOnExoPlayerFallsBackUnderAutoAndLibmpvButNotExoPlayer() {
        val codecError = NativePlaybackError(token = 1L, message = "no decoder", codecLikely = true)
        val expected = setOf(
            NativePlaybackEnginePreference.Auto,
            NativePlaybackEnginePreference.Libmpv,
        )
        val actual = NativePlaybackEnginePreference.entries
            .filter { shouldFallBackToMpv(codecError, NativePlaybackEngine.EXO, it) }
            .toSet()
        assertEquals(
            "the third conjunct is `preference != ExoPlayer`, so Auto and Libmpv both fall " +
                "back and only an explicitly pinned ExoPlayer does not",
            expected,
            actual,
        )
    }

    @Test
    fun anErrorTheEngineDidNotBlameOnTheCodecDoesNotFallBack() {
        val networkError = NativePlaybackError(token = 2L, message = "stream ended", codecLikely = false)
        for (preference in NativePlaybackEnginePreference.entries) {
            assertFalse(
                "a non-codec error under $preference must not restart on another engine",
                shouldFallBackToMpv(networkError, NativePlaybackEngine.EXO, preference),
            )
        }
    }

    @Test
    fun aCodecErrorWhileAlreadyOnMpvDoesNotFallBack() {
        val codecError = NativePlaybackError(token = 3L, message = "no decoder", codecLikely = true)
        for (preference in NativePlaybackEnginePreference.entries) {
            assertFalse(
                "falling back from MPV to MPV would restart playback in a loop under $preference",
                shouldFallBackToMpv(codecError, NativePlaybackEngine.MPV, preference),
            )
        }
    }

    @Test
    fun theFallbackIsIndependentOfTheErrorToken() {
        // The token is consumed by the caller, before this decision runs, so that a
        // repeated failure is not handled twice. That is why it is not a parameter here:
        // the decision itself must be a pure function of the three things it is given.
        val first = NativePlaybackError(token = 4L, message = "no decoder", codecLikely = true)
        val sameTokenNewText = first.copy(message = "still no decoder")
        assertEquals(
            shouldFallBackToMpv(first, NativePlaybackEngine.EXO, NativePlaybackEnginePreference.Auto),
            shouldFallBackToMpv(sameTokenNewText, NativePlaybackEngine.EXO, NativePlaybackEnginePreference.Auto),
        )
        assertTrue(
            "and the answer is the affirmative one this suite exists to pin",
            shouldFallBackToMpv(first, NativePlaybackEngine.EXO, NativePlaybackEnginePreference.Auto),
        )
    }

    private fun snapshot(
        state: NativePlaybackState,
        error: NativePlaybackError? = null,
    ) = NativePlaybackSnapshot(
        engine = NativePlaybackEngine.EXO,
        state = state,
        error = error,
    )
}
