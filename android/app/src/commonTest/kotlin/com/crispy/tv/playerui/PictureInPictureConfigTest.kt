package com.crispy.tv.playerui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What this file covers, and why it exists at all.
 *
 * [PictureInPictureAspectRatio.clampedAspectRatio] was a `private fun clampAspectRatio` on
 * `PlayerPipController`, itself a `private` class inside `androidMain` that cannot be constructed
 * off Android. The decision -- which aspect ratios the platform accepts, which get clamped, and
 * which are dropped as unknown -- was therefore untestable in every source set in this repo. It
 * is now a named top-level `internal` function over two `Int`s, which is the smallest change that
 * makes it callable.
 *
 * Every expected pair below was computed in Python from the documented arithmetic rather than
 * written from memory. Two of the cases are boundary pairs specifically because a `<`/`>` slip on
 * either end would satisfy every non-boundary case in this file.
 */
class PictureInPictureAspectRatioClampTest {

    @Test
    fun aRatioInsideBothBoundsIsPassedThroughUnchanged() {
        // PlayerRoute builds 16:9 as 17778/10000 from a rounded float and as 1920/1080 from raw
        // pixel bounds, so none of these are reduced. Each must survive field for field: the
        // platform type is rebuilt from these two fields, so reducing on the way through would
        // change the window the player opens.
        val cases = listOf(
            "16:9 as a rounded float becomes 17778/10000" to PictureInPictureAspectRatio(17778, 10000),
            "16:9 as raw pixel bounds" to PictureInPictureAspectRatio(1920, 1080),
            "16:9 already reduced" to PictureInPictureAspectRatio(16, 9),
            "square" to PictureInPictureAspectRatio(1, 1),
            "portrait" to PictureInPictureAspectRatio(1080, 1920),
        )
        for ((label, ratio) in cases) {
            assertEquals(ratio, ratio.clampedAspectRatio(), "clamped an in-bounds ratio: $label")
        }
    }

    @Test
    fun exactlyTheMaximumIsNotClampedButOneHundredthOverItIs() {
        // 239/100 is 2.39 to the last bit, and the guard is a strict `>`. Clamping the boundary
        // itself would be invisible here, because the clamped answer is 239/100 too -- so the
        // case that actually carries the assertion is the one just over it.
        assertEquals(
            PictureInPictureAspectRatio(239, 100),
            PictureInPictureAspectRatio(239, 100).clampedAspectRatio(),
            "the exact maximum is not clamped",
        )
        assertEquals(
            PictureInPictureAspectRatio(239, 100),
            PictureInPictureAspectRatio(240, 100).clampedAspectRatio(),
            "240/100 is 2.4, one hundredth over the maximum",
        )
    }

    @Test
    fun exactlyTheMinimumIsNotClampedButOneHundredthUnderItIs() {
        // Same shape at the other end. 100/240 is 0.41666 and the minimum is 0.41841.
        assertEquals(
            PictureInPictureAspectRatio(100, 239),
            PictureInPictureAspectRatio(100, 239).clampedAspectRatio(),
            "the exact minimum is not clamped",
        )
        assertEquals(
            PictureInPictureAspectRatio(100, 239),
            PictureInPictureAspectRatio(100, 240).clampedAspectRatio(),
            "100/240 is under the minimum",
        )
        // And just over the minimum is untouched, so the lower branch is not simply eating
        // every narrow ratio.
        assertEquals(
            PictureInPictureAspectRatio(101, 239),
            PictureInPictureAspectRatio(101, 239).clampedAspectRatio(),
            "101/239 is over the minimum",
        )
    }

    @Test
    fun theLowClampIsBuiltFromTheMaximumAndThatIsCorrect() {
        // The old code clamped a too-narrow ratio to Rational(100, round(MAX * 100)). Read on its
        // own that looks like a copy-paste slip -- the other branch reads MAX, so this one should
        // read MIN -- and it is only correct because the two constants are reciprocals by
        // construction: 100/239 is precisely 1/2.39.
        //
        // Asserted as the property the comment claims, not as the arithmetic that produces it, so
        // that changing MAX to a value whose hundreds do not round-trip fails here.
        val clamped = PictureInPictureAspectRatio(1, 100).clampedAspectRatio()
        assertEquals(PictureInPictureAspectRatio(100, 239), clamped)
        val answer = assertNotNull(clamped)
        assertEquals(
            MIN_PICTURE_IN_PICTURE_ASPECT_RATIO,
            answer.numerator.toDouble() / answer.denominator,
            "the low clamp is the reciprocal of the maximum, which is the minimum",
        )
    }

    @Test
    fun aNonPositiveComponentIsDroppedRatherThanClamped() {
        // The distinction the return type carries: null means "no shape known yet", and clamping
        // would advertise a 2.39:1 letterbox for a video whose geometry has not been reported.
        // Each row fails a different arm of the guard, so each is its own row.
        val cases = listOf(
            "zero numerator" to PictureInPictureAspectRatio(0, 10),
            "zero denominator" to PictureInPictureAspectRatio(10, 0),
            "negative numerator" to PictureInPictureAspectRatio(-16, 9),
            "negative denominator" to PictureInPictureAspectRatio(16, -9),
            "both zero" to PictureInPictureAspectRatio(0, 0),
            "both negative" to PictureInPictureAspectRatio(-16, -9),
        )
        for ((label, ratio) in cases) {
            assertNull(ratio.clampedAspectRatio(), "clamped an unknown shape instead of dropping it: $label")
        }
    }

    @Test
    fun anAbsentRatioIsAbsentRatherThanADefault() {
        // The nullable receiver: buildParams asks this of a field that is null whenever the route
        // has never reported video bounds, and the answer has to stay null rather than becoming
        // 1:1 -- which would open a square window for a video of unknown shape.
        val absent: PictureInPictureAspectRatio? = null
        assertNull(absent.clampedAspectRatio())
    }
}

/**
 * [PictureInPictureConfig] is the value `PlayerPipController` de-duplicates on
 * (`if (config == lastAppliedConfig) return`). This file is the evidence that replacing
 * `android.graphics.Rect` with four `Int`s did not change what that guard means: `Rect.equals`
 * compares the four ints, and a data class compares the same way.
 *
 * Without it, a port that merely *looks* equivalent is exactly the change that stays invisible --
 * every test in the repo would be green with a config that failed to de-duplicate, and the
 * symptom would be a `setPictureInPictureParams` window transaction on every recomposition
 * instead of none.
 */
class PictureInPictureConfigEqualityTest {

    @Test
    fun theSourceRectComparesByValueRatherThanByIdentity() {
        val bounds = PictureInPictureSourceRect(0, 0, 1920, 1080)
        assertEquals(bounds, PictureInPictureSourceRect(0, 0, 1920, 1080))
        // The shape the de-duplication guard depends on: two equal-by-value configs collapse.
        assertEquals(1, listOf(bounds, PictureInPictureSourceRect(0, 0, 1920, 1080)).distinct().size)
        assertEquals(2, listOf(bounds, PictureInPictureSourceRect(0, 0, 1280, 720)).distinct().size)
    }

    @Test
    fun theConfigDeDuplicatesOnEveryFieldItCarries() {
        val config = PictureInPictureConfig(
            enabled = true,
            sourceRect = PictureInPictureSourceRect(0, 0, 1920, 1080),
            aspectRatio = PictureInPictureAspectRatio(1920, 1080),
        )
        assertEquals(config, config.copy())
        assertNotEquals(config, config.copy(enabled = false))
        assertNotEquals(config, config.copy(sourceRect = null))
        assertNotEquals(config, config.copy(aspectRatio = null))
    }

    @Test
    fun theDefaultConfigIsDisabledAndEmpty() {
        // PlayerRoute pushes exactly this on dispose, to switch PiP back off when the route leaves.
        // It has to be distinguishable from any enabled config or the window never closes.
        val config = PictureInPictureConfig()
        assertEquals(false, config.enabled)
        assertNull(config.sourceRect)
        assertNull(config.aspectRatio)
    }

    @Test
    fun widthAndHeightAreDifferencesNotAbsolutes() {
        // Reproduced from android.graphics.Rect so the one call site that reads them -- the
        // "do we have real bounds yet" check in PlayerRoute -- is unchanged.
        assertEquals(1920, PictureInPictureSourceRect(0, 0, 1920, 1080).width())
        assertEquals(1080, PictureInPictureSourceRect(0, 0, 1920, 1080).height())
        // A negative difference is possible and is deliberately not normalised away: the caller
        // tests `width() > 0 && height() > 0`, so clamping at zero here would turn an unready
        // layout into a ready one and open a PiP window before the first frame.
        assertEquals(-1920, PictureInPictureSourceRect(1920, 0, 0, 1080).width())
    }
}