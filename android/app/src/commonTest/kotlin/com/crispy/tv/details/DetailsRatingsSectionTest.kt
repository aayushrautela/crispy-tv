package com.crispy.tv.details

import com.crispy.tv.backend.MetadataTitleRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rating-badge logic reached `commonMain` with the third R-split, which replaced a raw
 * `Int` resource id with the [RatingBadgeLogo] enum and put the drawable behind a composable
 * slot. The consequence worth testing is that the *decision* -- which badge, and whether the
 * pill falls back to text -- is now ordinary pure code that needs no resource to reach, and
 * that is what these cases pin.
 *
 * The four declarations this suite calls (`buildRatings`, `buildRatingPill`,
 * `DetailsRatingPill`, `RatingBadgeSpec`) are `internal` rather than `private` precisely
 * because of this file: a decision nobody can call is a decision nobody can test.
 */
class DetailsRatingsSectionTest {
    /**
     * Every field non-null, so each of the nine providers produces a pill. The values are
     * chosen per provider so the expected formatted score differs per formatter, which is
     * what makes the assertions on `score` worth writing.
     */
    private fun allRatings(
        imdb: Double? = 8.3,
        tmdb: Double? = 8.1,
        trakt: Double? = 8.4,
        metacritic: Double? = 86.0,
        rottenTomatoes: Double? = 93.0,
        audience: Double? = 91.0,
        letterboxd: Double? = 7.5,
        rogerEbert: Double? = 7.0,
        myAnimeList: Double? = 8.2,
    ) = MetadataTitleRatings(
        imdb = imdb,
        tmdb = tmdb,
        trakt = trakt,
        metacritic = metacritic,
        rottenTomatoes = rottenTomatoes,
        audience = audience,
        letterboxd = letterboxd,
        rogerEbert = rogerEbert,
        myAnimeList = myAnimeList,
    )

    private fun pills(tmdbRating: String? = null, ratings: MetadataTitleRatings? = allRatings()) =
        buildRatings(tmdbRating = tmdbRating, titleRatings = ratings)

    @Test
    fun `every provider produces a pill in a fixed order`() {
        val keys = pills().map { it.key }

        assertEquals(
            listOf(
                "tmdb",
                "imdb",
                "trakt",
                "rotten_tomatoes",
                "audience",
                "metacritic",
                "letterboxd",
                "roger_ebert",
                "my_anime_list",
            ),
            keys,
        )
    }

    @Test
    fun `a pill carries text only when it has no logo`() {
        // This is the rule the R-split made reachable: the badge *is* the logo, and the text
        // is the fallback for the two providers that have no mark of their own.
        for (pill in pills()) {
            if (pill.badgeLogo != null) {
                assertNull("${pill.key} has a logo and must not also carry text", pill.badgeText)
            } else {
                assertNotNull("${pill.key} has no logo and must fall back to text", pill.badgeText)
            }
        }
    }

    @Test
    fun `the two providers without a logo keep their own text`() {
        val byKey = pills().associateBy { it.key }

        assertNull("audience has no mark of its own", byKey.getValue("audience").badgeLogo)
        assertEquals("AUD", byKey.getValue("audience").badgeText)
        assertNull("roger_ebert has no mark of its own", byKey.getValue("roger_ebert").badgeLogo)
        assertEquals("RE", byKey.getValue("roger_ebert").badgeText)
    }

    @Test
    fun `each provider is mapped to its own logo`() {
        val byKey = pills().associateBy { it.key }

        assertEquals(RatingBadgeLogo.TMDB, byKey.getValue("tmdb").badgeLogo)
        assertEquals(RatingBadgeLogo.IMDB, byKey.getValue("imdb").badgeLogo)
        assertEquals(RatingBadgeLogo.TRAKT, byKey.getValue("trakt").badgeLogo)
        assertEquals(RatingBadgeLogo.ROTTEN_TOMATOES, byKey.getValue("rotten_tomatoes").badgeLogo)
        assertEquals(RatingBadgeLogo.METACRITIC, byKey.getValue("metacritic").badgeLogo)
        assertEquals(RatingBadgeLogo.LETTERBOXD, byKey.getValue("letterboxd").badgeLogo)
        assertEquals(RatingBadgeLogo.MYANIMELIST, byKey.getValue("my_anime_list").badgeLogo)
    }

    @Test
    fun `each provider is formatted on its own scale`() {
        val byKey = pills().associateBy { it.key }

        assertEquals("8.1/10", byKey.getValue("tmdb").score)
        assertEquals("8.3/10", byKey.getValue("imdb").score)
        assertEquals("8.4/10", byKey.getValue("trakt").score)
        assertEquals("93.0%", byKey.getValue("rotten_tomatoes").score)
        assertEquals("91.0%", byKey.getValue("audience").score)
        assertEquals("86.0/100", byKey.getValue("metacritic").score)
        assertEquals("7.5/5", byKey.getValue("letterboxd").score)
        assertEquals("7.0/4", byKey.getValue("roger_ebert").score)
        assertEquals("8.2/10", byKey.getValue("my_anime_list").score)
    }

    @Test
    fun `a provider with no rating contributes no pill`() {
        val keys = pills(ratings = allRatings(tmdb = null)).map { it.key }

        assertTrue("tmdb has nothing to show, so it is not a pill at all", "tmdb" !in keys)
        assertEquals(8, keys.size)
        // The rest must be untouched, or the missing one would have shifted the order.
        assertEquals("imdb", keys.first())
    }

    @Test
    fun `a zero rating contributes no pill because zero is not a rating`() {
        // The shared formatter drops anything that is not greater than zero, so a source
        // that reports 0 rather than null must not produce a "0/10" pill.
        val keys = pills(ratings = allRatings(imdb = 0.0)).map { it.key }

        assertTrue("imdb" !in keys)
        assertEquals(8, keys.size)
    }

    @Test
    fun `the tmdb fallback string is used when no structured rating is available`() {
        // DetailsScreen passes the older top-level TMDB score separately, and it is the only
        // provider with a fallback: with no title ratings at all, that string is all there is.
        val keys = pills(tmdbRating = "7.4", ratings = null).map { it.key }

        assertEquals(listOf("tmdb"), keys)
        assertEquals("7.4/10", buildRatings("7.4", null).single().score)
    }

    @Test
    fun `a blank fallback string is not a rating`() {
        val keys = pills(tmdbRating = "   ", ratings = null).map { it.key }

        assertTrue("a blank score is not a score", keys.isEmpty())
    }

    @Test
    fun `a structured tmdb rating wins over the fallback string`() {
        val pill = pills(tmdbRating = "7.4", ratings = allRatings(tmdb = 8.1)).single { it.key == "tmdb" }

        assertEquals("8.1/10", pill.score)
    }

    @Test
    fun `a pill with no score and no logo is dropped rather than rendered empty`() {
        val badge =
            RatingBadgeSpec(
                text = "TBD",
                backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = androidx.compose.ui.graphics.Color.Unspecified,
            )
        val byKey = buildRatingPill("k", "S", score = "  ", badge = badge)

        assertNull("a score that is blank after trimming cannot be shown", byKey)
    }
}
