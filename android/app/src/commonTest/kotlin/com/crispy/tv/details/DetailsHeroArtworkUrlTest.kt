package com.crispy.tv.details

import com.crispy.tv.images.ResponsiveImageSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hero asks for the widest tier the set carries, so this file pins the two
 * properties that make the details response an upgrade rather than a no-op.
 *
 * The first is that the URL **changes** when the set arrives: the flat
 * `fallbackUrl` is `artwork.medium`, so a hero resolving `medium` both before
 * and after the load is one `ImageRequest` that never runs again -- which is
 * what the details backdrop actually did. The second is that the choice is the
 * top of the chain, not the tier `urlForDisplaySize` picks under the app's
 * card ceiling, because `original` has no rung on that ladder.
 */
class DetailsHeroArtworkUrlTest {

    private val set = ResponsiveImageSet(low = "l", medium = "m", high = "h", original = "o")

    @Test
    fun theChosenTierIsTheWidestOneTheSetCarries() {
        assertEquals(
            "o",
            detailsHeroArtworkUrl(set, fallbackUrl = "m"),
            "the hero is the only full-screen image, so it takes the source tier",
        )
    }

    @Test
    fun theChoiceDiffersFromTheFlatUrlRatherThanRepeatingIt() {
        val chosen = detailsHeroArtworkUrl(set, fallbackUrl = "m")

        assert(
            chosen != "m",
            { "the chosen URL must differ from the flat `artwork.medium`, or the request is frozen" },
        )
    }

    @Test
    fun aSetWithoutTheSourceTierDegradesToTheNextWidest() {
        assertEquals(
            "h",
            detailsHeroArtworkUrl(
                ResponsiveImageSet(low = "l", medium = "m", high = "h", original = null),
                fallbackUrl = "m",
            ),
            "`original` is the newest tier; a set written before it still upgrades to `high`",
        )
    }

    @Test
    fun aSetWithOneUrlFallsBackToItRatherThanToTheFlatUrl() {
        val single = ResponsiveImageSet(low = "only", medium = null, high = null, original = null)

        assertEquals(
            "only",
            detailsHeroArtworkUrl(single, fallbackUrl = "m"),
            "the chain is `original ?: high ?: medium ?: low`, so the one URL present is the answer",
        )
    }

    @Test
    fun noArtworkSetLeavesTheFlatUrlInPlace() {
        assertEquals(
            "m",
            detailsHeroArtworkUrl(artwork = null, fallbackUrl = "m"),
            "before details load there is no set, and the flat URL is all there is",
        )
    }

    @Test
    fun anEmptyArtworkSetIsNotAnArtworkSet() {
        assertEquals(
            "m",
            detailsHeroArtworkUrl(
                artwork = ResponsiveImageSet(low = null, medium = null, high = null, original = null),
                fallbackUrl = "m",
            ),
            "`isEmpty` is the guard, not a blank tier: all four slots blank is the same as no set",
        )
    }

    @Test
    fun nothingAtAllResolvesToNothingRatherThanToABlankUrl() {
        assertNull(
            detailsHeroArtworkUrl(artwork = null, fallbackUrl = null),
            "a blank fallback is null, not an empty string a request would be built from",
        )
    }
}
