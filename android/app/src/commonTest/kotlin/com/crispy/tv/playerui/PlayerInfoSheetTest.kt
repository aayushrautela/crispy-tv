package com.crispy.tv.playerui

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the seven decisions the player info sheet makes, and the three in
 * `PlayerEpisodeContext`.
 *
 * Five of the seven were `private fun`s at the bottom of `PlayerInfoSheet.kt` --
 * pure, sitting inside a file of `@Composable` bodies, and therefore unreachable.
 * **A decision is not "in the composable" because it renders; it is in the
 * composable only if it needs the composition.** None of these five do, so they
 * are now `internal` top-level functions and this suite calls them rather than
 * re-deriving them (the `searchItemKey` rule: a hand-written stand-in for a
 * decision converts an unreachable assertion into a vacuous one).
 *
 * The caps are the product numbers this file hides -- two genres, five cast
 * members -- and each is pinned both by its own case and by the case where blank
 * entries would have consumed the cap.
 */
class PlayerInfoSheetTest {
    private fun details(
        itemType: String = "series",
        logoUrl: String? = null,
        description: String? = null,
        genres: List<String> = emptyList(),
        year: String? = null,
        runtime: String? = null,
        certification: String? = null,
        rating: String? = null,
        cast: List<String> = emptyList(),
        directors: List<String> = emptyList(),
        creators: List<String> = emptyList(),
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) = MediaDetails(
        id = "d1",
        imdbId = "tt1",
        itemType = itemType,
        title = "Title",
        artworkUrl = null,
        description = description,
        logoUrl = logoUrl,
        genres = genres,
        year = year,
        runtime = runtime,
        certification = certification,
        rating = rating,
        cast = cast,
        directors = directors,
        creators = creators,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        addonId = "addon-1",
    )

    private fun video(
        id: String = "v1",
        title: String = "Episode title",
        season: Int? = 1,
        episode: Int? = 1,
        released: String? = null,
        overview: String? = null,
    ) = MediaVideo(
        id = id,
        title = title,
        season = season,
        episode = episode,
        released = released,
        overview = overview,
        thumbnailUrl = null,
    )

    // region logoUrlFor

    @Test
    fun aUsableLogoIsTrimmed() {
        assertEquals("https://logo", logoUrlFor(details(logoUrl = "  https://logo  ")))
    }

    @Test
    fun aBlankLogoMeansRenderTheTitleInstead() {
        // The real failure this guards: a blank string is a *valid URL* as far as
        // `AsyncImage` is concerned, so without this the sheet renders an empty
        // image box and the user sees no title either.
        assertNull(logoUrlFor(details(logoUrl = "   ")))
        assertNull(logoUrlFor(details(logoUrl = "")))
        assertNull(logoUrlFor(details(logoUrl = null)))
        assertNull(logoUrlFor(null))
    }

    // endregion

    // region metaRowFor

    @Test
    fun metaRowNormalisesEachFieldWithItsOwnPolicy() {
        val row = metaRowFor(
            details(
                rating = "8.4",
                certification = "  TV-14  ",
                year = "  2026  ",
                runtime = "  52 min ",
                genres = listOf("Drama", "  ", "Mystery"),
            ),
        )
        assertEquals("8.4", row.rating)
        assertEquals("TV-14", row.certification)
        assertEquals("2026", row.year)
        assertEquals("52m", row.runtime)
        // Blanks are dropped rather than mapped, and only two survive.
        assertEquals(listOf("Drama", "Mystery"), row.genres)
    }

    @Test
    fun metaRowDoesNotShowTheRatingVerbatim() {
        // `"8.4"` in the case above is the trap this case exists for: `formatOneDecimal(8.4)`
        // is also `"8.4"`, so that assertion passes whether or not `normalizeRatingText`
        // is called. A fixture whose normalised form equals its raw form is a vacuous
        // assertion, so the rating is pinned with an input the normalisation *changes*.
        // Measured: `"10"` -> `"10.0"` (the pattern always writes one decimal).
        assertEquals("10.0", metaRowFor(details(rating = "10")).rating)
    }

    @Test
    fun metaRowTrimsTheRatingRatherThanShowingThePadding() {
        assertEquals("7.1", metaRowFor(details(rating = " 7.1 ")).rating)
        // A rating that is not a number at all has no normalised form, so it is shown
        // trimmed but otherwise untouched -- and a blank one is dropped entirely.
        assertEquals("abc", metaRowFor(details(rating = " abc ")).rating)
        assertNull(metaRowFor(details(rating = "   ")).rating)
    }

    @Test
    fun metaRowCapsGenresAtTwoAndCountsOnlyTheUsableOnes() {
        // The cap is taken *after* the filter: a third blank must not consume a slot
        // and cost a real genre.
        val row = metaRowFor(details(genres = listOf("", "Drama", "  ", "Mystery", "Sci-Fi")))
        assertEquals(listOf("Drama", "Mystery"), row.genres)
    }

    @Test
    fun anAllBlankMetaRowIsEmpty() {
        val row = metaRowFor(
            details(certification = "  ", year = " ", runtime = " ", rating = null, genres = listOf("  ")),
        )
        assertTrue(row.isEmpty(), "expected empty, got $row")
        assertNull(row.certification)
        assertNull(row.year)
        assertNull(row.runtime)
        assertNull(row.rating)
        assertEquals(emptyList(), row.genres)
    }

    @Test
    fun oneUsableFieldIsEnoughForTheRowToRender() {
        // The old inline condition was five clauses long; deleting any one of them
        // failed nothing because nothing could reach it.
        assertTrue(!metaRowFor(details(year = "2026")).isEmpty())
        assertTrue(!metaRowFor(details(rating = "7.1")).isEmpty())
        assertTrue(!metaRowFor(details(genres = listOf("Drama"))).isEmpty())
        assertTrue(metaRowFor(null).isEmpty())
    }

    // endregion

    // region overviewTextFor

    @Test
    fun theEpisodeOverviewBeatsTheShowDescription() {
        val context = PlayerEpisodeContext(1, 1, null, "Episode overview")
        assertEquals("Episode overview", overviewTextFor(context, details(description = "Show description")))
    }

    @Test
    fun theShowDescriptionIsTheFallback() {
        assertEquals("Show description", overviewTextFor(null, details(description = "Show description")))
    }

    @Test
    fun theShowDescriptionIsTrimmedAndBlankLoses() {
        assertEquals("Padded", overviewTextFor(null, details(description = "  Padded  ")))
        assertNull(overviewTextFor(null, details(description = "   ")))
        assertNull(overviewTextFor(null, details(description = null)))
        assertNull(overviewTextFor(null, null))
    }

    @Test
    fun aBlankEpisodeOverviewFallsThroughToTheShow() {
        // A `PlayerEpisodeContext` can legitimately carry a null overview -- the
        // episode had none and the show had none either -- and it must not stop the
        // show's description from being found.
        val context = PlayerEpisodeContext(1, 1, null, null)
        assertEquals("Show description", overviewTextFor(context, details(description = "Show description")))
    }

    // endregion

    // region castNamesFor

    @Test
    fun castDropsBlanksAndCapsAtFive() {
        val cast = castNamesFor(
            details(cast = listOf("A", "", "B", "  ", "C", "D", "E", "F", "G")),
        )
        assertEquals(listOf("A", "B", "C", "D", "E"), cast)
    }

    @Test
    fun aCastOfOnlyBlanksIsNoCast() {
        assertEquals(emptyList(), castNamesFor(details(cast = listOf("", "   "))))
        assertEquals(emptyList(), castNamesFor(details()))
        assertEquals(emptyList(), castNamesFor(null))
    }

    // endregion

    // region parseCastEntry

    @Test
    fun aBareNameHasNoCharacter() {
        assertEquals("Ada Lovelace" to null, parseCastEntry("Ada Lovelace"))
    }

    @Test
    fun theNameAndCharacterAreBothTrimmed() {
        assertEquals("Ada Lovelace" to "Countess of Lovelace", parseCastEntry("  Ada Lovelace  as  Countess of Lovelace  "))
    }

    @Test
    fun aBlankCharacterIsNoCharacterRatherThanAnEmptyLine() {
        assertEquals("Ada Lovelace" to null, parseCastEntry("Ada Lovelace as    "))
    }

    @Test
    fun theFirstSeparatorWinsNotTheLast() {
        // A character *named* "As": the sheet has always split here, and switching
        // to `lastIndexOf` would change the rendered name.
        assertEquals("Ada" to "Lovelace as The One", parseCastEntry("Ada as Lovelace as The One"))
    }

    @Test
    fun theSeparatorIsCaseSensitive() {
        // "AS" is not the separator, so the whole entry is a bare name.
        assertEquals("Ada Lovelace AS Countess" to null, parseCastEntry("Ada Lovelace AS Countess"))
    }

    @Test
    fun anEntryBeginningWithTheSeparatorStillParses() {
        // "as The One" alone: the name is empty and the character is the rest. Pinned
        // because an empty name is reachable and the sheet will render it.
        assertEquals("" to "The One", parseCastEntry(" as The One"))
    }

    // endregion

    // region buildCreditLine

    @Test
    fun aMovieCreditsItsDirectors() {
        assertEquals(
            "Directed by Denis Villeneuve",
            buildCreditLine(details(itemType = "movie", directors = listOf("Denis Villeneuve"))),
        )
    }

    @Test
    fun aSeriesCreditsItsCreators() {
        assertEquals(
            "Created by Vince Gilligan",
            buildCreditLine(details(itemType = "series", creators = listOf("Vince Gilligan"))),
        )
    }

    @Test
    fun theItemTypeIsReadCaseInsensitively() {
        assertEquals(
            "Directed by A",
            buildCreditLine(details(itemType = "Movie", directors = listOf("A"))),
        )
    }

    @Test
    fun aMissingItemTypeIsASeries() {
        // `null?.equals("movie", ignoreCase = true)` is false, so a details object
        // with no itemType falls to "Created by" rather than to "Directed by".
        val noType = MediaDetails(
            id = "d1",
            imdbId = "tt1",
            itemType = "",
            title = "Title",
            artworkUrl = null,
            description = null,
            year = null,
            runtime = null,
            certification = null,
            rating = null,
            addonId = "addon-1",
        )
        assertEquals("Created by A", buildCreditLine(noType.copy(creators = listOf("A"))))
    }

    @Test
    fun creditsOfOnlyBlanksAreNoCreditLine() {
        assertNull(buildCreditLine(details(itemType = "movie", directors = listOf("", "  "))))
        assertNull(buildCreditLine(details(itemType = "series", creators = listOf(""))))
        assertNull(buildCreditLine(details(itemType = "movie")))
        assertNull(buildCreditLine(null))
    }

    @Test
    fun severalDirectorsAreJoinedInOrder() {
        assertEquals(
            "Directed by A, B, C",
            buildCreditLine(details(itemType = "movie", directors = listOf("A", "B", "C"))),
        )
    }

    @Test
    fun aMovieDoesNotFallBackToCreators() {
        // The verb and the list are chosen together. A film with only creators set
        // shows no credit line rather than "Created by" under a "Directed by" sheet.
        assertNull(buildCreditLine(details(itemType = "movie", creators = listOf("A"))))
    }

    // endregion

    // region PlayerEpisodeContext.seasonEpisodeLabel

    @Test
    fun theSeasonEpisodeLabelNeedsBothNumbers() {
        assertEquals("S2E5", PlayerEpisodeContext(2, 5, null, null).seasonEpisodeLabel)
        assertEquals("", PlayerEpisodeContext(2, null, null, null).seasonEpisodeLabel)
        assertEquals("", PlayerEpisodeContext(null, 5, null, null).seasonEpisodeLabel)
        assertEquals("", PlayerEpisodeContext(null, null, null, null).seasonEpisodeLabel)
    }

    @Test
    fun theSeasonEpisodeLabelRendersZeroRatherThanDroppingIt() {
        // The guard is `!= null`, not `> 0`, so season 0 -- specials -- renders.
        assertEquals("S0E1", PlayerEpisodeContext(0, 1, null, null).seasonEpisodeLabel)
    }

    @Test
    fun theSeasonEpisodeLabelOmitsTheSpaceTheRowMetaUses() {
        // The same both-or-neither rule as `episodeRowMeta`, a different output
        // format: `"S2E5"` here, `"S2 E5 • …"` there. Recorded so the two are not
        // "unified" later -- they render in different places.
        assertEquals("S2E5", PlayerEpisodeContext(2, 5, null, null).seasonEpisodeLabel)
        assertEquals("S2 E5 • Jan 5, 2026", episodeRowMeta(video(season = 2, episode = 5, released = "2026-01-05")))
    }

    // endregion

    // region MediaVideo.toPlayerEpisodeContext

    @Test
    fun aVideoBecomesAContextWithBothNumbersAndItsTextTrimmed() {
        assertEquals(
            PlayerEpisodeContext(3, 7, "Pilot", "Sets up"),
            video(season = 3, episode = 7, title = "  Pilot  ", overview = "  Sets up  ").toPlayerEpisodeContext(),
        )
    }

    @Test
    fun aVideoMissingEitherNumberIsNoContext() {
        assertNull(video(season = null, episode = 1).toPlayerEpisodeContext())
        assertNull(video(season = 1, episode = null).toPlayerEpisodeContext())
    }

    @Test
    fun aBlankVideoTitleOrOverviewBecomesNull() {
        val context = video(season = 1, episode = 1, title = "   ", overview = "  ").toPlayerEpisodeContext()
        assertNull(context?.title)
        assertNull(context?.overview)
    }

    // endregion

    // region MediaDetails.toPlayerEpisodeContext

    @Test
    fun aDetailsObjectBecomesAContextFromItsOwnSeasonAndEpisode() {
        val d = details(seasonNumber = 2, episodeNumber = 4)
        assertEquals(PlayerEpisodeContext(2, 4, null, null), d.toPlayerEpisodeContext())
    }

    @Test
    fun aDetailsObjectMissingEitherNumberIsNoContext() {
        assertNull(details(seasonNumber = 2).toPlayerEpisodeContext())
        assertNull(details(episodeNumber = 4).toPlayerEpisodeContext())
    }

    @Test
    fun theMatchingEpisodeSuppliesTheTitleAndOverview() {
        val d = details(
            seasonNumber = 2,
            episodeNumber = 4,
            description = "Show description",
        ).copy(
            videos = listOf(
                video(id = "other", season = 1, episode = 1, title = "Other", overview = "Other overview"),
                video(id = "match", season = 2, episode = 4, title = " Match ", overview = " Match overview "),
            ),
        )
        val context = d.toPlayerEpisodeContext()
        assertEquals("Match", context?.title)
        assertEquals("Match overview", context?.overview)
    }

    @Test
    fun theSeasonIsPartOfTheMatchAndNotOnlyTheEpisodeNumber() {
        // The decoy is *first* and shares the episode number, which is what makes the
        // season half of the condition load-bearing: matching on the episode number
        // alone would pick "S1E4 — Wrong" for a details object that says S2E4. The two
        // cases above cannot see this — one has no competing video with the same
        // episode number, and the other has none at all.
        val d = details(seasonNumber = 2, episodeNumber = 4).copy(
            videos = listOf(
                video(id = "decoy", season = 1, episode = 4, title = "Wrong"),
                video(id = "match", season = 2, episode = 4, title = "Right"),
            ),
        )
        assertEquals("Right", d.toPlayerEpisodeContext()?.title)
    }

    @Test
    fun anUnmatchedSeasonFallsBackToTheFirstVideoRatherThanNothing() {
        // A genuine fallback, not a defensive branch: the details object says
        // S2E4 and the video list has neither, and the sheet would otherwise show a
        // bare "S2E4" with no title at all.
        val d = details(seasonNumber = 2, episodeNumber = 4).copy(
            videos = listOf(
                video(id = "a", season = 1, episode = 1, title = "First"),
                video(id = "b", season = 3, episode = 9, title = "Second"),
            ),
        )
        assertEquals("First", d.toPlayerEpisodeContext()?.title)
    }

    @Test
    fun anEmptyVideoListStillYieldsTheSeasonEpisodeContext() {
        val context = details(seasonNumber = 2, episodeNumber = 4, description = "Show description")
            .toPlayerEpisodeContext()
        assertEquals(PlayerEpisodeContext(2, 4, null, "Show description"), context)
    }

    @Test
    fun theShowDescriptionIsTheOverviewFallbackAndBlankLoses() {
        val withBoth = details(seasonNumber = 1, episodeNumber = 1, description = "  Show  ")
            .copy(videos = listOf(video(overview = null)))
        assertEquals("Show", withBoth.toPlayerEpisodeContext()?.overview)

        val neither = details(seasonNumber = 1, episodeNumber = 1, description = "   ")
            .copy(videos = listOf(video(overview = "   ")))
        assertNull(neither.toPlayerEpisodeContext()?.overview)
    }

    @Test
    fun aBlankEpisodeTitleBecomesNullRatherThanEmpty() {
        val d = details(seasonNumber = 1, episodeNumber = 1).copy(videos = listOf(video(title = "   ")))
        assertNull(d.toPlayerEpisodeContext()?.title)
    }

    // endregion
}
