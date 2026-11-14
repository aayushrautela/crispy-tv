package com.crispy.tv.nativeengine.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule the runtime reads before it tells the player a stream is HDR.
 *
 * The names are not a wish list: they are what mpv's `video-params/trc`
 * property can hold, so the ones that are *not* HDR are as much a part of the
 * rule as the four that are.
 */
class PlaybackHdrTest {
    @Test
    fun `every HDR transfer function mpv names is recognised`() {
        // An empty set makes the loop below pass without covering anything, so
        // the size is the assertion that keeps the loop honest.
        assertEquals(4, HDR_TRANSFER_FUNCTIONS.size, "the HDR set this rule reads")
        HDR_TRANSFER_FUNCTIONS.forEach { name ->
            assertTrue(isHdrTransferFunction(name), "$name is in the HDR set but was not recognised")
        }
    }

    @Test
    fun `case and surrounding space do not change the answer`() {
        HDR_TRANSFER_FUNCTIONS.forEach { name ->
            assertTrue(isHdrTransferFunction(name.uppercase()), "$name.uppercase() is HDR")
            assertTrue(isHdrTransferFunction("  $name  "), "\"  $name  \" is HDR")
            // A name is mpv's spelling, not a prefix of it: `pq` and `pq.` are
            // two different strings, and only one of them is the option value.
            assertFalse(isHdrTransferFunction("$name."), "\"$name.\" is not an mpv name")
        }
    }

    @Test
    fun `the SDR transfer functions and the wide-gamut names are not HDR`() {
        listOf(
            "bt.1886",
            "srgb",
            "linear",
            "bt.2020",
            "gamma2.2",
            "gamma2.8",
            "v-log",
            "st428",
            "",
        ).forEach { name ->
            assertFalse(isHdrTransferFunction(name), "$name is SDR but was read as HDR")
        }
        assertFalse(isHdrTransferFunction(null), "an absent transfer function is not HDR")
        assertFalse(isHdrTransferFunction("   "), "a blank transfer function is not HDR")
    }
}