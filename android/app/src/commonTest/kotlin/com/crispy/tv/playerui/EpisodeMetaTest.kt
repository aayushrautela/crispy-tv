package com.crispy.tv.playerui

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.streams.episodeHeaderMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the two functions that build an episode's metadata line.
 *
 * They were **one function written twice**, byte for byte, in two files that had
 * no dependency between them: `PlayerEpisodeRow.kt` held it as `episodeRowMeta`
 * and `StreamSelectorContent.kt` held it as `episodeHeaderMetadata`, with eight
 * identical lines in the middle. They now share one implementation, and this
 * suite calls both — the shared one *and* the wrapper — because the wrapper's
 * only interesting decision is the branch that decides whether the shared one
 * runs at all.
 *
 * The separator is `" • "` (U+2022 with spaces), which is a copy decision and not
 * an incidental character: it is asserted literally in the cases below rather
 * than joined in the test, for the reason the search-row key earned its own
 * production function.
 */
class EpisodeMetaTest {
    private fun video(
        season: Int? = null,
        episode: Int? = null,
        released: String? = null,
    ) = MediaVideo(
        id = "ep-1",
        title = "An Episode",
        season = season,
        episode = episode,
        released = released,
        overview = null,
        thumbnailUrl = null,
    )

    private fun details(year: String? = null) =
        MediaDetails(
            id = "show-1",
            imdbId = null,
            itemType = "show",
            title = "A Show",
            artworkUrl = null,
            description = null,
            year = year,
            runtime = null,
            certification = null,
            rating = null,
            addonId = null,
        )

    // region episodeRowMeta

    @Test
    fun bothNumbersAndADateMakeOneLine() {
        assertEquals(
            "S2 E5 • Jan 5, 2026",
            episodeRowMeta(video(season = 2, episode = 5, released = "2026-01-05")),
        )
    }

    @Test
    fun aSeasonWithoutAnEpisodeNumberIsNotRendered() {
        // The rule is both-or-neither, not "whichever is present": a row reading
        // "S2 • Jan 5, 2026" is not a thing this app has ever shown, and a
        // `season?.let` here would produce it.
        assertEquals("Jan 5, 2026", episodeRowMeta(video(season = 2, released = "2026-01-05")))
    }

    @Test
    fun anEpisodeNumberWithoutASeasonIsNotRendered() {
        assertEquals("Jan 5, 2026", episodeRowMeta(video(episode = 5, released = "2026-01-05")))
    }

    @Test
    fun theNumbersAloneAreStillARow() {
        assertEquals("S2 E5", episodeRowMeta(video(season = 2, episode = 5)))
    }

    @Test
    fun theDateAloneIsStillARow() {
        assertEquals("Jan 5, 2026", episodeRowMeta(video(released = "2026-01-05")))
    }

    @Test
    fun anEpisodeWithNeitherNumberNorDateHasNoLine() {
        // `parts` is empty, so the row renders no supporting text at all. Pinned
        // because the alternative -- an empty string -- would put a blank line in
        // the card, and `takeIf { it.isNotEmpty() }` is what prevents it.
        assertNull(episodeRowMeta(video()))
    }

    @Test
    fun aBlankReleaseDateIsNoDate() {
        assertEquals("S1 E1", episodeRowMeta(video(season = 1, episode = 1, released = "   ")))
    }

    @Test
    fun aNullReleaseDateIsNoDate() {
        assertEquals("S1 E1", episodeRowMeta(video(season = 1, episode = 1, released = null)))
    }

    @Test
    fun anUnparseableReleaseDateIsShownVerbatim() {
        // The fallback policy, and it is a *presentation* decision rather than a
        // property of a date -- see `formatLongDate`. A backend sending an
        // unexpected shape should still have its value on screen.
        assertEquals("S1 E1 • not a date", episodeRowMeta(video(season = 1, episode = 1, released = "not a date")))
    }

    @Test
    fun aReleaseDateCarryingATimeIsReadFromItsDatePart() {
        assertEquals(
            "S3 E1 • Feb 29, 2024",
            episodeRowMeta(video(season = 3, episode = 1, released = "2024-02-29T18:30:00Z")),
        )
    }

    @Test
    fun aPaddedReleaseDateIsTrimmedBeforeItIsRead() {
        assertEquals(
            "S3 E1 • Feb 29, 2024",
            episodeRowMeta(video(season = 3, episode = 1, released = "  2024-02-29  ")),
        )
    }

    @Test
    fun aPaddedUnparseableReleaseDateKeepsItsPadding() {
        // The one behavioural difference this landing introduced, and it is
        // deliberate: the old `formatEpisodeReleaseDate` returned the *trimmed*
        // `raw` from its `catch`, while `formatLongDate` returns the *untrimmed*
        // original. `DetailsScreen`'s rendering is the reason `formatLongDate`
        // was left alone, so the whitespace shows here instead.
        assertEquals("S1 E1 •   garbage  ", episodeRowMeta(video(season = 1, episode = 1, released = "  garbage  ")))
    }

    @Test
    fun aZeroSeasonAndEpisodeAreRenderedNotTreatedAsAbsent() {
        // The guard is `!= null`, not `> 0`: specials are legitimately S0E1, and a
        // truthiness test would drop them.
        assertEquals("S0 E1", episodeRowMeta(video(season = 0, episode = 1)))
    }

    // endregion

    // region episodeHeaderMetadata

    @Test
    fun theHeaderRendersTheSameLineAsTheRow() {
        val episode = video(season = 2, episode = 5, released = "2026-01-05")
        assertEquals(episodeRowMeta(episode), episodeHeaderMetadata(episode, details(year = "2024")))
    }

    @Test
    fun theHeaderPrefersTheEpisodesYearOverTheShows() {
        // With no episode the sheet has nothing but the show, so it shows the
        // year -- and with an episode it shows the episode, never both.
        assertEquals("S2 E5 • Jan 5, 2026", episodeHeaderMetadata(video(season = 2, episode = 5, released = "2026-01-05"), details(year = "2024")))
    }

    @Test
    fun withNoEpisodeTheHeaderFallsBackToTheShowsYear() {
        assertEquals("2024", episodeHeaderMetadata(null, details(year = "2024")))
    }

    @Test
    fun withNeitherAnEpisodeNorAYearThereIsNoHeaderLine() {
        assertNull(episodeHeaderMetadata(null, details(year = null)))
        assertNull(episodeHeaderMetadata(null, null))
    }

    @Test
    fun withNoEpisodeABlankYearIsStillNoHeaderLine() {
        // Trimming happens before the blank check, so "   " is the same as absent.
        // Both halves are needed: `.trim()` alone would yield an empty string,
        // and `isNotBlank()` alone would render three spaces.
        assertNull(episodeHeaderMetadata(null, details(year = "   ")))
    }

    @Test
    fun withNoEpisodeTheYearIsTrimmed() {
        assertEquals("2024", episodeHeaderMetadata(null, details(year = "  2024  ")))
    }

    @Test
    fun anEpisodeThatContributesNothingDoesNotFallBackToTheShowsYear() {
        // **The** case that makes the wrapper an `if` rather than an elvis. The
        // episode exists but has no numbers and no readable date, so
        // `episodeRowMeta` answers null -- and the original returned null there
        // too. An `episode?.let { … } ?: details?.year…` would answer "2024"
        // instead, which is a real change: the sheet would claim a release year
        // for an episode whose only reason for having no line is that it has no
        // metadata at all.
        assertNull(episodeHeaderMetadata(video(), details(year = "2024")))
    }

    // endregion
}
