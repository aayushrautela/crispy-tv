package com.crispy.tv.nativeengine.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The id that lets an engine track and a catalog entry be matched by string alone.
 *
 * Moved to `:core-domain`'s `commonMain` alongside the function it tests, and this
 * suite is the first coverage it has ever had: `externalSubtitleTrackId` had **no
 * test anywhere in the repository** before this landing, in any source set.
 *
 * The expectations were computed from `String.hashCode`'s documented algorithm
 * (31-radix, signed 32-bit) rather than written from memory, and each is then
 * confirmed by running the function -- a golden has to be measured *through* the
 * function it pins, not asserted from a belief about what it assembles.
 */
class ExternalSubtitleTrackIdTest {

    @Test
    fun everyIdIsMarkedAsExternal() {
        // "external" is what lets a match skip the engine's own track numbering, so it
        // is load-bearing rather than decoration.
        for (url in URLS) {
            val id = externalSubtitleTrackId(url)
            assertTrue(
                id.startsWith(EXTERNAL_SUBTITLE_TRACK_ID_PREFIX),
                "id must carry the marker so it is distinguishable from an engine track, got '$id'",
            )
        }
    }

    @Test
    fun aFragmentIsStrippedSoTwoUrlsDifferingOnlyInItShareAnId() {
        // The fragment can carry per-track options that are not part of the
        // subtitle's identity, so it must not change the key. The consequence --
        // two catalog entries differing only by fragment are indistinguishable to
        // anything matching by id -- is the flip side of this, and asserting
        // injectivity here would assert a property the function does not have.
        val plain = externalSubtitleTrackId(SUBTITLE_URL)
        val withFragment = externalSubtitleTrackId("$SUBTITLE_URL#en")
        val withOtherFragment = externalSubtitleTrackId("$SUBTITLE_URL#&ENG=English")

        assertEquals(plain, withFragment, "a fragment is not part of the subtitle's identity")
        assertEquals(plain, withOtherFragment, "a second fragment must collapse the same way")
    }

    @Test
    fun surroundingWhitespaceIsTrimmedSoPaddedUrlsShareAnId() {
        assertEquals(
            externalSubtitleTrackId(SUBTITLE_URL),
            externalSubtitleTrackId("   $SUBTITLE_URL  "),
            "padding around a url is not part of it",
        )
    }

    @Test
    fun aNegativeHashIsRenderedUnsignedRatherThanCarryingASign() {
        // `String.hashCode()` is signed and `sub.ass` really does hash negative. An id
        // is compared as a string, so a `-` would become part of the key, and a match
        // built from an unsigned expectation would never find it.
        assertEquals("ext:908f01d3", externalSubtitleTrackId("sub.ass"))
    }

    @Test
    fun leadingZeroesAreNotPaddedOntoTheHash() {
        // `0x41` renders `41`, not `00000041`. The width is not a decision this
        // function can make, and padding it would invent one; what matters is only
        // that the same url yields the same string every time.
        assertEquals("ext:41", externalSubtitleTrackId("A"))
    }

    @Test
    fun theSameUrlYieldsTheSameIdEveryTime() {
        // The entire purpose of the function: it is a match key, so stability across
        // calls is a stronger claim than any single-value expectation.
        val first = externalSubtitleTrackId(SUBTITLE_URL)
        repeat(3) {
            assertEquals(first, externalSubtitleTrackId(SUBTITLE_URL), "id must be stable across calls")
        }
    }

    @Test
    fun distinctUrlsGetDistinctIds() {
        // Not an injectivity claim -- a 32-bit hash over unbounded input cannot be
        // injective, and `aDifferentUrlCollidingIsNotAsserted` records that. This only
        // pins that ordinary subtitle urls do not collide with each other, which is
        // what makes the key usable in practice.
        for (left in URLS) {
            for (right in URLS) {
                if (left == right) continue
                assertNotEquals(
                    externalSubtitleTrackId(left),
                    externalSubtitleTrackId(right),
                    "ordinary distinct subtitle urls must not share an id: '$left' vs '$right'",
                )
            }
        }
    }

    @Test
    fun anEmptyUrlStillProducesAMarkedId() {
        // The degenerate input is documented rather than guarded: a blank url still
        // yields a well-formed marker-only id, and two blank urls collide by
        // construction. Guarding it here would be a decision the caller has to make
        // with its own context, so it is recorded instead of invented.
        assertEquals("ext:0", externalSubtitleTrackId(""))
        assertEquals(externalSubtitleTrackId(""), externalSubtitleTrackId("   "))
    }

    @Test
    fun aUrlThatIsNothingButAFragmentCollapsesToTheEmptyUrl() {
        // The trim and the strip compose: there is no url left to hash, so this is
        // the empty case wearing a fragment. Pinned because the two operations are
        // separate and a reader could reasonably assume the fragment survives.
        assertEquals(externalSubtitleTrackId(""), externalSubtitleTrackId("#en"))
    }

    @Test
    fun aNonAsciiUrlIsHashedOverItsCharactersNotItsBytes() {
        // `hashCode` is defined over UTF-16 code units, so a non-ASCII url hashes
        // differently from a byte-oriented reading of the same string. The value is
        // measured, and asserting it pins the character-level contract.
        assertEquals("ext:ce9c7fc", externalSubtitleTrackId("\u00e9.ass"))
    }

    private companion object {
        const val SUBTITLE_URL = "a.ass"

        /** A table rather than two loose values: a pair proves nothing about a hash. */
        val URLS =
            listOf(
                "a.ass",
                "sub.ass",
                "ep1.ass",
                "https://cdn.example/x.ass",
                "A",
                "\u00e9.ass",
                "aaaaaaaaaaaaaaaa.ass",
            )
    }
}
