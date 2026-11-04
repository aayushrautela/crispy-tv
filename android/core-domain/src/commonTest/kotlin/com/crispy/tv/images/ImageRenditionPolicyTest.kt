package com.crispy.tv.images

import com.crispy.tv.settings.ImageQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the one place a rendition URL is chosen: [urlForDisplaySize]. The widths in
 * [ImageRenditionWidths] are the floors the choice is written against, so every
 * boundary case here is a boundary of that table.
 */
class ImageRenditionPolicyTest {

    private val set = ResponsiveImageSet(low = "l", medium = "m", high = "h")

    @Test
    fun picksTheSmallestRenditionThatCoversTheTarget() {
        assertEquals("l", set.urlForDisplaySize(targetPx = 1, cap = ImageQuality.HIGH))
        assertEquals("l", set.urlForDisplaySize(targetPx = 342, cap = ImageQuality.HIGH))
        assertEquals("m", set.urlForDisplaySize(targetPx = 343, cap = ImageQuality.HIGH))
        assertEquals("m", set.urlForDisplaySize(targetPx = 500, cap = ImageQuality.HIGH))
        assertEquals("h", set.urlForDisplaySize(targetPx = 501, cap = ImageQuality.HIGH))
        assertEquals("h", set.urlForDisplaySize(targetPx = 780, cap = ImageQuality.HIGH))
    }

    @Test
    fun aTargetWiderThanEveryRenditionGetsTheWidestAllowed() {
        assertEquals("h", set.urlForDisplaySize(targetPx = 4000, cap = ImageQuality.HIGH))
    }

    @Test
    fun theCapNeverLetsAPickGoAboveItself() {
        assertEquals("l", set.urlForDisplaySize(targetPx = 4000, cap = ImageQuality.LOW))
        assertEquals("m", set.urlForDisplaySize(targetPx = 4000, cap = ImageQuality.MEDIUM))
    }

    @Test
    fun theCapDoesNotForceAWidthTheTargetDoesNotNeed() {
        assertEquals("l", set.urlForDisplaySize(targetPx = 100, cap = ImageQuality.MEDIUM))
        assertEquals("l", set.urlForDisplaySize(targetPx = 100, cap = ImageQuality.LOW))
    }

    @Test
    fun aBlankTierFallsBackDownTheChain() {
        val withoutLow = ResponsiveImageSet(low = null, medium = "m", high = "h")

        assertEquals("m", withoutLow.urlForDisplaySize(targetPx = 100, cap = ImageQuality.HIGH))
    }

    @Test
    fun anEmptySetAnswersNull() {
        val empty = ResponsiveImageSet(low = null, medium = null, high = null)

        assertNull(empty.urlForDisplaySize(targetPx = 100, cap = ImageQuality.HIGH))
    }

    @Test
    fun aNonPositiveTargetStillGetsTheSmallestRendition() {
        assertEquals("l", set.urlForDisplaySize(targetPx = 0, cap = ImageQuality.HIGH))
    }
}
