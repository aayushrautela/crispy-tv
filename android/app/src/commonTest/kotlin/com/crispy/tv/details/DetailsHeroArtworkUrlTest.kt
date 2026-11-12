package com.crispy.tv.details

import com.crispy.tv.images.ResponsiveImageSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hero asks for the tier its box justifies, so this file pins the two
 * properties that make the details response an upgrade rather than a no-op.
 *
 * The first is that the URL **changes** when the set arrives: the flat
 * `fallbackUrl` is `artwork.medium`, so a hero resolving `medium` both before
 * and after the load is one `ImageRequest` that never runs again -- which is
 * what the details backdrop actually did. The second is that the choice is
 * *width-aware*, because the hero box is what decides the rung: `original` is
 * outside the ladder, so a full-bleed hero tops out at `high` and never reaches
 * for the uncapped source, and a box too small to need `high` is not held to it.
 */
class DetailsHeroArtworkUrlTest {

    private val set = ResponsiveImageSet(low = "l", medium = "m", high = "h", original = "o")

    /** A full-bleed hero on this device measured 1008x897 px. */
    private val heroBoxPx = 1008

    @Test
    fun theChosenTierCoversTheHeroBoxRatherThanTheSourceFile() {
        assertEquals(
            "h",
            detailsHeroArtworkUrl(artwork = set, targetPx = heroBoxPx, fallbackUrl = "m"),
            "the hero is the only full-screen image, and no listed rung covers a 1008px box",
        )
    }

    @Test
    fun theSourceTierIsNotReachedForOnASizedSurface() {
        val chosen = detailsHeroArtworkUrl(artwork = set, targetPx = heroBoxPx, fallbackUrl = "m")

        assert(
            chosen != "o",
            { "`original` is the uncapped source; a sized surface must not fetch it" },
        )
    }

    @Test
    fun theChoiceDiffersFromTheFlatUrlRatherThanRepeatingIt() {
        val chosen = detailsHeroArtworkUrl(artwork = set, targetPx = heroBoxPx, fallbackUrl = "m")

        assert(
            chosen != "m",
            { "the chosen URL must differ from the flat `artwork.medium`, or the request is frozen" },
        )
    }

    @Test
    fun aSmallHeroBoxDoesNotReachForTheTopRung() {
        assertEquals(
            "m",
            detailsHeroArtworkUrl(artwork = set, targetPx = 400, fallbackUrl = "m"),
            "`medium` covers a 400px box, so the tier is the target's, not the hero's",
        )
    }

    @Test
    fun aSetCarryingOnlyTheSourceTierStillUpgradesPastTheFlatUrl() {
        assertEquals(
            "o",
            detailsHeroArtworkUrl(
                artwork = ResponsiveImageSet(low = null, medium = null, high = null, original = "o"),
                targetPx = heroBoxPx,
                fallbackUrl = "m",
            ),
            "`original` is last in the chain, so it answers when no capped tier carries a URL",
        )
    }

    @Test
    fun aSetWithOneUrlFallsBackToItRatherThanToTheFlatUrl() {
        val single = ResponsiveImageSet(low = "only", medium = null, high = null, original = null)

        assertEquals(
            "only",
            detailsHeroArtworkUrl(artwork = single, targetPx = heroBoxPx, fallbackUrl = "m"),
            "the chain ends at `original`, so the one URL present is the answer",
        )
    }

    @Test
    fun noArtworkSetLeavesTheFlatUrlInPlace() {
        assertEquals(
            "m",
            detailsHeroArtworkUrl(artwork = null, targetPx = heroBoxPx, fallbackUrl = "m"),
            "before details load there is no set, and the flat URL is all there is",
        )
    }

    @Test
    fun anEmptyArtworkSetIsNotAnArtworkSet() {
        assertEquals(
            "m",
            detailsHeroArtworkUrl(
                artwork = ResponsiveImageSet(low = null, medium = null, high = null, original = null),
                targetPx = heroBoxPx,
                fallbackUrl = "m",
            ),
            "`isEmpty` is the guard, not a blank tier: all four slots blank is the same as no set",
        )
    }

    @Test
    fun nothingAtAllResolvesToNothingRatherThanToABlankUrl() {
        assertNull(
            detailsHeroArtworkUrl(artwork = null, targetPx = heroBoxPx, fallbackUrl = null),
            "a blank fallback is null, not an empty string a request would be built from",
        )
    }
}
