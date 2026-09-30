package com.crispy.tv.playerui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the two pure string decisions `PlayerGestures.kt` makes.
 *
 * Both are `internal` and both were already `internal` before the move — the file
 * is in `commonMain` now, which is what put them within reach of `commonTest` at
 * all. Between them they are a clamp, a rounding and a string interpolation, and
 * each of those three is a decision that can be wrong in a way a screen test would
 * not notice, because the text is rendered into an overlay that no golden covers.
 */
class PlayerGesturesTest {
    // region formatGestureBrightness

    @Test
    fun aBrightnessIsShownAsAPercentage() {
        assertEquals("50%", formatGestureBrightness(0.5f))
        assertEquals("0%", formatGestureBrightness(0f))
        assertEquals("100%", formatGestureBrightness(1f))
    }

    @Test
    fun brightnessIsRoundedRatherThanTruncated() {
        // `roundToInt`, so 50.4% reads "50%" and 50.6% reads "51%". A truncation
        // would make the readout lag behind the gesture by up to a whole percent.
        assertEquals("50%", formatGestureBrightness(0.504f))
        assertEquals("51%", formatGestureBrightness(0.506f))
        // 99.9% rounds to 100%, so the last representable value below full is
        // indistinguishable from full. Measured rather than assumed: `0.999f * 100f`
        // is 99.9, and `roundToInt` is half-up.
        assertEquals("100%", formatGestureBrightness(0.999f))
        assertEquals("99%", formatGestureBrightness(0.994f))
    }

    @Test
    fun brightnessBelowZeroIsClampedRatherThanShownNegative() {
        // The gesture maths adds a delta to the starting level, so a drag past the
        // top of the screen computes a negative value. `coerceIn(0f, 1f)` is what
        // keeps the overlay from reading "-7%".
        assertEquals("0%", formatGestureBrightness(-0.07f))
        assertEquals("0%", formatGestureBrightness(-100f))
    }

    @Test
    fun brightnessAboveOneIsClampedRatherThanShownOverAHundred() {
        assertEquals("100%", formatGestureBrightness(1.4f))
        assertEquals("100%", formatGestureBrightness(100f))
    }

    @Test
    fun theBrightnessReadoutAlwaysEndsInAPercentSign() {
        // Every branch ends the same way, so the sign is a property of the format
        // rather than of the value — worth one case so a future edit that returns a
        // bare number cannot pass the four above unnoticed.
        listOf(-1f, 0f, 0.5f, 1f, 2f).forEach {
            assertEquals(true, formatGestureBrightness(it).endsWith("%"), "no percent sign for $it")
        }
    }

    // endregion

    // region formatGestureVolume

    @Test
    fun aVolumeIsShownAsAPercentage() {
        assertEquals("50%", formatGestureVolume(AudioLevel(fraction = 0.5f, isMuted = false)))
        assertEquals("100%", formatGestureVolume(AudioLevel(fraction = 1f, isMuted = false)))
        assertEquals("0%", formatGestureVolume(AudioLevel(fraction = 0f, isMuted = false)))
    }

    @Test
    fun aMutedLevelReadsMutedAndNeverAPercentage() {
        // `isMuted` beats `fraction`, and the controller reports `isMuted` exactly
        // when the stream volume is zero -- so a muted level can only ever have
        // fraction 0f from the real implementation. The reverse is reachable from a
        // test, and it is the case worth pinning: a stubbed or future implementation
        // must not render "100%" for a muted track by reading the fraction first.
        assertEquals("Muted", formatGestureVolume(AudioLevel(fraction = 1f, isMuted = true)))
        assertEquals("Muted", formatGestureVolume(AudioLevel(fraction = 0f, isMuted = true)))
    }

    @Test
    fun theMutedLabelIsTheAppsOwnWordAndNotAStatusFromThePlatform() {
        // "Muted" is copy this app chose -- it is not what any framework calls the
        // state, and `getDisplayLanguage`/`toDisplayString` style answers would be
        // capitalised differently. It is a user-visible string on the volume
        // gesture, so it gets a case of its own rather than riding along inside
        // the mute assertion: a later edit that lowercases it or lengthens it to
        // "Muted (0%)" would otherwise pass the percentage cases untouched.
        assertEquals("Muted", formatGestureVolume(AudioLevel(fraction = 0f, isMuted = true)))
        assertEquals("Muted", formatGestureVolume(AudioLevel(fraction = 0.4f, isMuted = true)))
    }

    @Test
    fun volumeIsRoundedJustAsBrightnessIs() {
        assertEquals("51%", formatGestureVolume(AudioLevel(fraction = 0.506f, isMuted = false)))
        assertEquals("50%", formatGestureVolume(AudioLevel(fraction = 0.504f, isMuted = false)))
    }

    @Test
    fun volumeIsClampedAtBothEndsLikeBrightness() {
        // The same `coerceIn` and for the same reason: the drag arithmetic can leave
        // the 0f..1f range on either side, and `AudioLevel.fraction` is a fraction
        // of the stream maximum rather than a raw step precisely so that a fraction
        // outside the range is a mistake to clamp rather than a value to render.
        assertEquals("0%", formatGestureVolume(AudioLevel(fraction = -0.5f, isMuted = false)))
        assertEquals("100%", formatGestureVolume(AudioLevel(fraction = 3f, isMuted = false)))
    }

    @Test
    fun theTwoReadoutsUseTheSameRoundingSoTheyDoNotDisagree() {
        // They sit above the same gesture and are compared by eye, so a difference
        // between them is a bug the user sees rather than one a test suite reports.
        val level = 0.506f
        assertEquals(
            formatGestureBrightness(level),
            formatGestureVolume(AudioLevel(fraction = level, isMuted = false)),
        )
    }

    // endregion
}
