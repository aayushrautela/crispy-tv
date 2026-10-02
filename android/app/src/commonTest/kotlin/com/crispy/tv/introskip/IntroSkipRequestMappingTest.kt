package com.crispy.tv.introskip

import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The seam between [PlaybackIdentity] and [IntroSkipRequest].
 *
 * The property under test is a *rejection* rule, so most of these are about the
 * `null` answer. A suite that only asserted the accepted case would pass just as
 * well against a function that returned a request for everything, which is the
 * failure this file exists to catch: a movie would otherwise spend a request to
 * learn it has no season and episode.
 */
class IntroSkipRequestMappingTest {

    @Test
    fun aSeriesEpisodeIsLookedUpByImdbIdSeasonAndEpisode() {
        val request = assertNotNull(
            identity(season = 2, episode = 7, imdbId = "tt0903747").toIntroSkipRequestOrNull()
        )

        assertEquals("tt0903747", request.imdbId)
        assertEquals(2, request.season)
        assertEquals(7, request.episode)
    }

    @Test
    fun malAndKitsuIdsAreLeftForTheServiceToResolve() {
        // AniSkip is keyed by MyAnimeList id, which `PlaybackIdentity` does not
        // carry, so the service resolves it via ARM and Kitsu. Asserting the two
        // are null pins that the resolution still happens in the service rather
        // than being duplicated here where it would be dropped.
        val request = assertNotNull(identity(season = 1, episode = 1).toIntroSkipRequestOrNull())

        assertNull(request.malId)
        assertNull(request.kitsuId)
    }

    @Test
    fun aSurroundingImdbIdIsTrimmed() {
        val request = assertNotNull(
            identity(season = 1, episode = 1, imdbId = "  tt0903747  ").toIntroSkipRequestOrNull()
        )

        assertEquals("tt0903747", request.imdbId)
    }

    @Test
    fun anIdentityWithNoEpisodeNumberingIsNotWorthLookingUp() {
        // The whole set, not a sample of it: a movie is the case this exists for,
        // and a hand-picked list would let a newly added content type pass
        // silently while producing a request built out of sentinels.
        val notAnEpisode = listOf(
            "movie with no season or episode" to identity(season = null, episode = null, imdbId = "tt0903747"),
            "movie with season 0" to identity(season = 0, episode = 0, imdbId = "tt0903747"),
            "movie with a negative season" to identity(season = -1, episode = 1, imdbId = "tt0903747"),
            "season but no episode" to identity(season = 1, episode = null, imdbId = "tt0903747"),
            "episode but no season" to identity(season = null, episode = 1, imdbId = "tt0903747"),
            "episode 0" to identity(season = 1, episode = 0, imdbId = "tt0903747"),
            "a negative episode" to identity(season = 1, episode = -3, imdbId = "tt0903747"),
        )

        for ((description, candidate) in notAnEpisode) {
            assertNull(
                candidate.toIntroSkipRequestOrNull(),
                "$description should not be looked up but produced a request",
            )
        }
    }

    @Test
    fun anIdentityWithNoImdbIdIsNotWorthLookingUp() {
        // Every id the service accepts has to come from somewhere, and
        // `PlaybackIdentity`'s only one is `imdbId`. Without it the service's own
        // `normalize` rejects the request, so asking first is the same delay
        // without the answer.
        val notIdentifiable = listOf(
            "no imdb id" to identity(season = 1, episode = 1, imdbId = null),
            "a blank imdb id" to identity(season = 1, episode = 1, imdbId = ""),
            "a whitespace imdb id" to identity(season = 1, episode = 1, imdbId = "   "),
        )

        for ((description, candidate) in notIdentifiable) {
            assertNull(
                candidate.toIntroSkipRequestOrNull(),
                "$description should not be looked up but produced a request",
            )
        }
    }

    @Test
    fun anImdbIdOfTheWrongShapeIsLeftForTheServiceToReject() {
        // `normalize` applies `tt\d+` to the id. This deliberately does not, so
        // that the rule stays in one place; what is asserted here is only that a
        // malformed id is passed through rather than dropped, which is the
        // difference between "the service decides" and "the caller decides twice".
        val request = assertNotNull(
            identity(season = 1, episode = 1, imdbId = "not-an-imdb-id").toIntroSkipRequestOrNull()
        )

        assertEquals("not-an-imdb-id", request.imdbId)
    }

    @Test
    fun anAnimeEpisodeIsLookedUpTheSameWayASeriesEpisodeIs() {
        // Anime is the content type AniSkip actually serves, and it is reached
        // through the same `imdbId` -> ARM -> MAL chain as everything else, so
        // the mapper must not treat it differently. Pinned because "add a branch
        // for ANIME" is the obvious wrong future edit.
        val request = assertNotNull(
            identity(season = 1, episode = 4, imdbId = "tt0903747", contentType = MetadataLabMediaType.ANIME)
                .toIntroSkipRequestOrNull()
        )

        assertEquals(4, request.episode)
    }

    private fun identity(
        season: Int?,
        episode: Int?,
        imdbId: String? = "tt0903747",
        contentType: MetadataLabMediaType = MetadataLabMediaType.SERIES,
    ) = PlaybackIdentity(
        itemId = "tt0903747",
        seriesItemId = "tt0903747",
        imdbId = imdbId,
        contentType = contentType,
        season = season,
        episode = episode,
        title = "Series",
    )
}
