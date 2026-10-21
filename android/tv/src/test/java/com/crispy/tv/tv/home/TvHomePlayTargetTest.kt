package com.crispy.tv.tv.home

import com.crispy.tv.home.CalendarEpisodeItem
import com.crispy.tv.home.HomeWideRailItemKind
import com.crispy.tv.home.HomeWideRailItemUi
import com.crispy.tv.player.CanonicalContinueWatchingItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The play target a home card produces.
 *
 * These pin the one decision the watch progress depends on: **which episode "play"
 * means**. A Continue Watching row must name the episode the viewer was watching --
 * with its season and its number, because that is what the watch-history key is
 * filed under -- and a calendar row must hand over the season and the number it
 * does know, even though it has no imdb id.
 */
class TvHomePlayTargetTest {

    @Test
    fun aWatchedShowNamesTheEpisodeItWasWatching() {
        val target = watchedItem(
            titleItemId = "series-1",
            imdbId = "tt1234567",
            season = 2,
            episode = 5,
        ).toPlayTarget()

        assertEquals("the play target names the series, not the episode entry", "series-1", target.itemId)
        assertEquals("a show is a series, whatever the source called it", "SERIES", target.mediaTypeName)
        assertEquals("the episode's own address at the providers", "tt1234567:2:5", target.lookupId)
        assertEquals(2, target.season)
        assertEquals(5, target.episode)
    }

    @Test
    fun aWatchedAnimeStaysAnime() {
        val target = watchedItem(
            titleItemId = "series-1",
            itemType = "anime",
            imdbId = "tt1234567",
            season = 1,
            episode = 12,
        ).toPlayTarget()

        assertEquals(
            "anime is filed under its own type, or the progress key disagrees " +
                "with the one the phone wrote",
            "ANIME",
            target.mediaTypeName,
        )
    }

    @Test
    fun aWatchedMovieNamesNoEpisode() {
        val target = watchedItem(
            titleItemId = "movie-1",
            itemType = "movie",
            imdbId = "tt7654321",
            season = null,
            episode = null,
        ).toPlayTarget()

        assertEquals("movie-1", target.itemId)
        assertEquals("MOVIE", target.mediaTypeName)
        // With no season and no episode, the lookup id is the title's own -- which
        // is exactly what the providers should be asked for.
        assertEquals("tt7654321", target.lookupId)
        assertEquals(null, target.season)
        assertEquals(null, target.episode)
    }

    @Test
    fun aWatchedShowWithNoImdbIdStillNamesItsEpisode() {
        val target = watchedItem(
            titleItemId = "series-1",
            imdbId = null,
            season = 3,
            episode = 7,
        ).toPlayTarget()

        assertEquals("no imdb id means no lookup id, and the picker resolves one", null, target.lookupId)
        assertEquals(
            "the season and the episode still travel, because the progress key " +
                "needs them",
            3,
            target.season,
        )
        assertEquals(7, target.episode)
    }

    @Test
    fun aCalendarEpisodeNamesItsEpisodeEvenWithoutAnImdbId() {
        val target = calendarItem(
            titleItemId = "series-1",
            season = 4,
            episode = 2,
        ).toPlayTarget()

        assertEquals("series-1", target.itemId)
        assertEquals("SERIES", target.mediaTypeName)
        assertEquals("the calendar model carries no imdb id, so there is no lookup id", null, target.lookupId)
        assertEquals(4, target.season)
        assertEquals(2, target.episode)
    }

    @Test
    fun aCardWithNeitherNamesItsTitleAlone() {
        val target = card(titleItemId = "series-1").toPlayTarget()

        assertEquals(
            "a title with no episode behind it plays the way the picker decides",
            TvHomePlayTarget(itemId = "series-1"),
            target,
        )
    }

    private fun watchedItem(
        titleItemId: String,
        itemType: String = "show",
        imdbId: String?,
        season: Int?,
        episode: Int?,
    ) = CanonicalContinueWatchingItem(
        id = "$titleItemId-entry",
        titleItemId = titleItemId,
        playbackItemId = "$titleItemId-playback",
        itemType = itemType,
        title = "Some Show",
        imdbId = imdbId,
        season = season,
        episode = episode,
        progressPercent = 40.0,
        lastUpdatedEpochMs = 0L,
    )

    private fun calendarItem(
        titleItemId: String,
        season: Int?,
        episode: Int?,
    ) = CalendarEpisodeItem(
        id = "$titleItemId-calendar",
        titleItemId = titleItemId,
        playbackItemId = "$titleItemId-playback",
        localKey = "$titleItemId-local",
        seriesName = "Some Show",
        episodeTitle = "An Episode",
        overview = null,
        season = season,
        episode = episode,
        episodeRange = "1-8",
        episodeCount = 8,
        releaseDate = null,
        releasedAtMs = null,
        isReleased = true,
        isGroup = false,
        artworkUrl = null,
        thumbnailUrl = null,
    )

    private fun card(titleItemId: String) = HomeWideRailItemUi(
        key = "$titleItemId-key",
        title = "Some Show",
        subtitle = "",
        imageUrl = null,
        kind = HomeWideRailItemKind.WATCH_ACTIVITY,
        detailsItemId = titleItemId,
    )
}
