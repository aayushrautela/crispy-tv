package com.crispy.tv.addons.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the round trip between [buildAddonEpisodeLookupId] and
 * [parseAddonEpisodeLookupId].
 *
 * **Why the round trip matters**: the TV app is handed a lookup id and nothing else
 * when the viewer picks a stream for an episode, and the watch-history key it writes
 * is `<title id>:<season>:<episode>`. If the two halves of the lookup id disagree
 * about what an episode looks like, the TV writes progress the phone will never read
 * back -- the failure is silent, and it shows up as "the TV forgets where I was"
 * weeks later.
 *
 * Each case states the invariant rather than the line, because a wrong line can be
 * right and still break the rule around it.
 */
class AddonEpisodeLookupIdTest {

    @Test
    fun episodeLookupIdRoundTrips() {
        val lookupId = buildAddonEpisodeLookupId("tt1234567", season = 2, episode = 5)

        assertEquals("tt1234567:2:5", lookupId)
        val parsed = parseAddonEpisodeLookupId(lookupId)
        assertEquals(AddonEpisodeLookup(imdbId = "tt1234567", season = 2, episode = 5), parsed)
    }

    @Test
    fun titleLookupIdIsNotAnEpisodeLookup() {
        val lookupId = buildAddonEpisodeLookupId("tt1234567", season = null, episode = null)

        assertEquals("tt1234567", lookupId)
        assertNull(parseAddonEpisodeLookupId(lookupId), "a title lookup names no episode")
    }

    @Test
    fun nonPositiveSeasonOrEpisodeIsNotAnEpisodeLookup() {
        assertNull(
            parseAddonEpisodeLookupId(buildAddonEpisodeLookupId("tt1234567", season = 0, episode = 5)),
            "season zero is not an episode of a season",
        )
        assertNull(
            parseAddonEpisodeLookupId(buildAddonEpisodeLookupId("tt1234567", season = 2, episode = 0)),
            "episode zero is not an episode",
        )
    }

    @Test
    fun lookupIdWithoutAnImdbPrefixIsNotAnEpisodeLookup() {
        assertNull(parseAddonEpisodeLookupId("12345:1:1"), "only imdb ids are episode lookups")
    }

    @Test
    fun malformedLookupIdIsNotAnEpisodeLookup() {
        assertNull(parseAddonEpisodeLookupId(null))
        assertNull(parseAddonEpisodeLookupId(""))
        assertNull(parseAddonEpisodeLookupId("tt1234567"))
        assertNull(parseAddonEpisodeLookupId("tt1234567:1"))
        assertNull(parseAddonEpisodeLookupId("tt1234567:1:2:3"))
        assertNull(parseAddonEpisodeLookupId("tt1234567:x:2"))
    }
}
