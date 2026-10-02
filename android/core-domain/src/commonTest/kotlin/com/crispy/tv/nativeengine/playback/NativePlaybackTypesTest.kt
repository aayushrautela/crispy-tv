package com.crispy.tv.nativeengine.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two decisions in [NativePlaybackTypes] that no test could reach before the types moved here.
 *
 * [NativePlaybackEngine], [NativePlaybackState] and [NativePlaybackError] are pure data and get
 * no cases of their own: a field list is not a decision, and a test that re-asserts a default
 * asserts the constructor. What needed covering was [NativeVideoLayout.aspectRatioValue], which
 * falls back three separate times, and [NativePlaybackSnapshot.isPlaying] / [isBuffering], which
 * are derived from the state rather than stored.
 *
 * **The ratios below are deliberately chosen to be exact in binary floating point** -- 2.0, 1.0,
 * 0.5 and 4.0 have exact `Float` representations, so every assertion here is an equality rather
 * than a tolerance. A 16:9 fixture would be the obvious choice and it is the wrong one: 16f/9f is
 * not representable, so the expectation would have to be a tolerance, and a tolerance wide enough
 * to absorb the rounding is also wide enough to absorb a fallback that fired. There is a separate
 * case for a non-exact ratio, which asserts the inequality that actually matters instead.
 */
class NativeVideoLayoutAspectRatioTest {

    /**
     * The discriminating pair for the first fallback. If this fixture gave the same answer whether
     * the function read the encoded dimensions or the visible ones, it would prove nothing -- so
     * the encoded size is 4:1 and the visible size is 2:1, and only 2.0f distinguishes them.
     */
    @Test
    fun theVisibleSizeIsPreferredOverTheEncodedOne() {
        val layout = NativeVideoLayout(
            width = 1920,
            height = 1080,
            visibleWidth = 800,
            visibleHeight = 400,
        )
        assertEquals(2.0f, layout.aspectRatioValue())
    }

    @Test
    fun anUnsetVisibleSizeFallsBackToTheEncodedOne() {
        val layout = NativeVideoLayout(
            width = 800,
            height = 400,
            visibleWidth = 0,
            visibleHeight = 0,
        )
        assertEquals(2.0f, layout.aspectRatioValue())
    }

    /**
     * The second fallback is per-axis, not per-layout: one visible axis being unset must not
     * discard the other. The encoded size is 1920x1080 and the visible height is 960, so a
     * per-axis answer is 1920/960 = 2.0 and a pair-wise `takeIf` -- which would discard the
     * visible height along with the unset width -- would answer 1920/1080 instead.
     */
    @Test
    fun onlyTheUnsetAxisFallsBackAndTheOtherKeepsItsVisibleValue() {
        val layout = NativeVideoLayout(
            width = 1920,
            height = 1080,
            visibleWidth = 0,
            visibleHeight = 960,
        )
        assertEquals(2.0f, layout.aspectRatioValue())
    }

    /** The third fallback, and the reason a zero ratio is not simply multiplied in. */
    @Test
    fun aNonPositivePixelWidthHeightRatioIsReplacedByOne() {
        val layout = NativeVideoLayout(
            width = 800,
            height = 400,
            visibleWidth = 0,
            visibleHeight = 0,
            pixelWidthHeightRatio = 0f,
        )
        assertEquals(2.0f, layout.aspectRatioValue())
    }

    @Test
    fun aNegativePixelWidthHeightRatioIsReplacedByOneRatherThanFlipped() {
        val layout = NativeVideoLayout(
            width = 800,
            height = 400,
            visibleWidth = 0,
            visibleHeight = 0,
            pixelWidthHeightRatio = -2f,
        )
        assertEquals(2.0f, layout.aspectRatioValue())
    }

    @Test
    fun aPositivePixelWidthHeightRatioIsMultipliedIntoTheWidth() {
        val layout = NativeVideoLayout(
            width = 800,
            height = 400,
            visibleWidth = 0,
            visibleHeight = 0,
            pixelWidthHeightRatio = 2f,
        )
        assertEquals(4.0f, layout.aspectRatioValue())
    }

    /**
     * The `null` return exists because the arithmetic would otherwise produce `NaN`, and `NaN` is
     * not a width a layout can use -- it silently collapses rather than failing. Both zero and
     * negative reach it, and the two are separate fixtures because a guard written as `== 0`
     * passes the first and fails the second.
     */
    @Test
    fun anAllZeroLayoutAnswersNullRatherThanNaN() {
        assertNull(NativeVideoLayout(0, 0, 0, 0).aspectRatioValue())
    }

    @Test
    fun aNegativeLayoutAnswersNullRatherThanANegativeRatio() {
        assertNull(NativeVideoLayout(-1920, -1080, 0, 0).aspectRatioValue())
    }

    @Test
    fun aZeroHeightAnswersNullEvenWhenTheWidthIsPositive() {
        assertNull(NativeVideoLayout(1920, 0, 0, 0).aspectRatioValue())
    }

    @Test
    fun aNegativeVisibleDimensionIsTreatedAsUnsetRatherThanAsTheLayout() {
        val layout = NativeVideoLayout(
            width = 800,
            height = 400,
            visibleWidth = -800,
            visibleHeight = -400,
        )
        assertEquals(2.0f, layout.aspectRatioValue())
    }

    /**
     * The one non-exact ratio, asserting the *ordering* rather than a computed value: a wide frame
     * must measure wider than a 4:3 one. A tolerance-based equality here would be the weaker
     * assertion, since it would pass a function that returned a constant near 1.78.
     */
    @Test
    fun aSixteenByNineFrameMeasuresWiderThanAStandardDefinitionOne() {
        val sixteenByNine = NativeVideoLayout(1920, 1080, 0, 0).aspectRatioValue()
        val standardDefinition = NativeVideoLayout(720, 576, 0, 0).aspectRatioValue()

        assertEquals(true, sixteenByNine != null && standardDefinition != null)
        assertEquals(true, sixteenByNine!! > standardDefinition!!)
    }
}

/**
 * [NativePlaybackSnapshot.isPlaying] and [isBuffering] are computed properties, not stored flags.
 * That is a decision with a cost -- a snapshot cannot report itself as buffering while its own
 * state says otherwise -- and it is the reason neither is a constructor parameter.
 *
 * **The case set is derived from [NativePlaybackState.entries] rather than listed**, so a state
 * added later fails this test instead of quietly answering `false` for both. A hand-written list
 * would pass on the new value, which is the failure mode where a test skips a case rather than
 * failing one.
 */
class NativePlaybackSnapshotDerivedFlagsTest {

    @Test
    fun onlyThePlayingStateCountsAsPlaying() {
        val playing = NativePlaybackState.entries
            .filter { NativePlaybackSnapshot(engine = NativePlaybackEngine.EXO, state = it).isPlaying }

        assertEquals(setOf(NativePlaybackState.PLAYING), playing.toSet())
    }

    /**
     * The rule that costs the most if it is got wrong: buffering spans **two** states, so a
     * function written as "not playing and not ended" would include [NativePlaybackState.ERROR]
     * and show a spinner on a failed stream.
     */
    @Test
    fun bufferingSpansPreparingAndBufferingAndNothingElse() {
        val buffering = NativePlaybackState.entries
            .filter { NativePlaybackSnapshot(engine = NativePlaybackEngine.EXO, state = it).isBuffering }

        assertEquals(setOf(NativePlaybackState.PREPARING, NativePlaybackState.BUFFERING), buffering.toSet())
    }

    @Test
    fun theErrorStateIsNeitherPlayingNorBuffering() {
        val snapshot = NativePlaybackSnapshot(
            engine = NativePlaybackEngine.EXO,
            state = NativePlaybackState.ERROR,
        )

        assertEquals(false, snapshot.isPlaying)
        assertEquals(false, snapshot.isBuffering)
    }

    @Test
    fun aFreshSnapshotIsIdleAndNotPlayingAndNotBuffering() {
        val snapshot = NativePlaybackSnapshot(engine = NativePlaybackEngine.MPV)

        assertEquals(NativePlaybackState.IDLE, snapshot.state)
        assertEquals(false, snapshot.isPlaying)
        assertEquals(false, snapshot.isBuffering)
    }

    /**
     * The flags follow the state, not the transport fields, so a snapshot taken mid-seek while
     * still reporting itself as playing answers `true` even with `playWhenReady = false`. This is
     * the pair that keeps a later edit from quietly making these fields a function of
     * `playWhenReady` instead.
     */
    @Test
    fun aPlayingSnapshotWithPlayWhenReadyClearedIsStillPlaying() {
        val snapshot = NativePlaybackSnapshot(
            engine = NativePlaybackEngine.EXO,
            state = NativePlaybackState.PLAYING,
            playWhenReady = false,
        )

        assertEquals(true, snapshot.isPlaying)
    }

    @Test
    fun aPausedSnapshotIsNeitherPlayingNorBuffering() {
        val snapshot = NativePlaybackSnapshot(
            engine = NativePlaybackEngine.EXO,
            state = NativePlaybackState.PAUSED,
            playWhenReady = false,
        )

        assertEquals(false, snapshot.isPlaying)
        assertEquals(false, snapshot.isBuffering)
    }
}
