package com.crispy.tv.addons.lookup

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.addons.streams.StreamProviderUiState
import com.crispy.tv.addons.streams.seedProviders
import com.crispy.tv.addons.streams.visibleProviders
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the pure decisions in `StreamLookupSupport.kt` that the player's stream
 * selector, its subtitle line and its episode lookup all depend on.
 *
 * ## Why this suite exists in this module and not in `:app`
 *
 * `:addons` had **no test source set at all**. That is not an oversight about
 * `StreamLookupSupport` -- it is the natural consequence of the module also
 * holding five `androidMain` files (`Context`, OkHttp, `org.json`), which is the
 * kind of module where nobody ever gets around to adding one. The effect was 171
 * lines of shared logic with **zero tests anywhere in the repository**: nothing
 * in `:app` reaches them either, because `:app` calls them through the view
 * model and never directly.
 *
 * So the value here is not coverage of a new refactor. It is that these are pure
 * functions over shared types -- no platform call, no harness, no clock, no
 * dispatcher -- which makes them the cheapest possible tests in this project.
 *
 * ## How to read a failure
 *
 * Every case here exists because the obvious reading of the code is the wrong
 * one. Each failure message says which invariant broke rather than which line,
 * because a wrong line can be right and still break the rule around it.
 */
class StreamLookupSupportTest {

    // ---------------------------------------------------------------- parseLookupId

    @Test
    fun aSeasonOrEpisodeOfZeroIsNotAParseAndTheWholeStringStaysTheBaseId() {
        val parsed = parseLookupId("tt1234:0:5")
        assertEquals("tt1234:0:5", parsed.baseId, "a zero season must not become a season, and must not be stripped off the base id either")
        assertNull(parsed.season, "season 0 fails the > 0 guard, so it is no season at all rather than season zero")
        assertNull(parsed.episode, "the guard is on both halves; a good episode beside a bad season still fails the parse")
    }

    @Test
    fun bothZeroEpisodeValuesAreRejectedTogether() {
        val parsed = parseLookupId("tt1234:5:0")
        assertEquals("tt1234:5:0", parsed.baseId)
        assertNull(parsed.season)
        assertNull(parsed.episode)
    }

    @Test
    fun anEpisodeWithinASingleDigitOfBothBoundsParsesOnBothSidesOfTheGuard() {
        val good = parseLookupId("tt1234:1:1")
        assertEquals("tt1234", good.baseId, "the base id is re-joined by dropping exactly the last two parts")
        assertEquals(1, good.season)
        assertEquals(1, good.episode)

        val justBelow = parseLookupId("tt1234:0:1")
        assertNull(justBelow.season, "the boundary is exclusive at zero: 0 fails, 1 passes")
    }

    @Test
    fun onlyTheLastTwoPartsAreTakenSoAColonBearingBaseIdSurvives() {
        val parsed = parseLookupId("tt1:2:3:7:2")
        assertEquals("tt1:2:3", parsed.baseId, "parts are dropped from the right, so a base id that itself contains colons is reconstructed intact")
        assertEquals(7, parsed.season)
        assertEquals(2, parsed.episode)
    }

    @Test
    fun anIdWithTwoOrFewerPartsNeverParses() {
        val single = parseLookupId("tt1234")
        assertEquals("tt1234", single.baseId)
        assertNull(single.season)
        assertNull(single.episode)

        val pair = parseLookupId("tt1234:5")
        assertEquals("tt1234:5", pair.baseId, "a bare pair has no episode half, so neither half is claimed")
        assertNull(pair.season)
        assertNull(pair.episode)

        // The pair above is caught by the *numeric* guard, not the arity one,
        // because "tt1234" is not an integer. A purely numeric pair is the case
        // that reaches the arity guard, and it is the only input that can tell
        // the two apart: with the arity loosened it claims season 5, episode 7
        // and an EMPTY base id, having dropped both parts it should have kept.
        val numericPair = parseLookupId("5:7")
        assertEquals("5:7", numericPair.baseId, "two parts cannot describe a season and an episode, so both stay in the base id")
        assertNull(numericPair.season, "claiming a season from a two-part id attaches the wrong episode")
        assertNull(numericPair.episode)
    }

    @Test
    fun anEmptyOrBlankIdYieldsAnEmptyBaseAndNoParts() {
        val empty = parseLookupId("")
        assertEquals("", empty.baseId)
        assertNull(empty.season)
        assertNull(empty.episode)

        // Whitespace is trimmed first, so "   " takes the empty path rather than
        // becoming a base id of three spaces.
        val blank = parseLookupId("   ")
        assertEquals("", blank.baseId, "the trim happens before the empty check")
        assertNull(blank.season)
    }

    @Test
    fun aNonNumericEpisodeFallsBackInsteadOfClaimingAPartNumber() {
        val parsed = parseLookupId("tt1234:7:latest")
        assertEquals("tt1234:7:latest", parsed.baseId, "a non-numeric half fails toIntOrNull, so the whole string is left alone")
        assertNull(parsed.season)
        assertNull(parsed.episode)
    }

    // ------------------------------------------------------- resolveStreamLookupTarget

    @Test
    fun anEpisodicTitleResolvesToTheFirstLoadedEpisodeNotToTheTitle() {
        val details = details(itemType = "series", id = "show-1", imdbId = "tt0903747")
        val episodes = listOf(
            episode(id = "first", lookupId = null),
            episode(id = "second", lookupId = null),
        )

        val target = resolveStreamLookupTarget(
            details = details,
            selectedSeason = 1,
            seasonEpisodes = episodes,
            fallbackMediaType = MetadataLabMediaType.MOVIE,
        )

        // `buildPlayerSubtitle`'s companion, `MediaVideo.id`, is what a series'
        // lookup actually keys on -- the title's own id is the wrong shape for a
        // stream lookup, so preferring it would break episode lookup entirely.
        val firstLoaded = details.toAddonLookupId()
        assertEquals(firstLoaded, target.lookupId, "episodic content falls back to the details' lookup id when no episode carries one")
        assertEquals(MetadataLabMediaType.SERIES, target.mediaType, "the media type comes from the details, not from the fallback")
    }

    @Test
    fun aMovieIgnoresItsEpisodesEvenIfSomeAreLoaded() {
        val details = details(itemType = "movie", id = "movie-1", imdbId = "tt0111161")
        val target = resolveStreamLookupTarget(
            details = details,
            selectedSeason = null,
            seasonEpisodes = listOf(episode(id = "stray", lookupId = null)),
            fallbackMediaType = MetadataLabMediaType.SERIES,
        )
        assertEquals("tt0111161", target.lookupId, "the fallback media type is only consulted when the details carry no type the code recognises")
        assertEquals(MetadataLabMediaType.MOVIE, target.mediaType)
    }

    @Test
    fun anUnrecognisedItemFallsBackToTheGivenMediaType() {
        val details = details(itemType = "podcast", id = "p-1", imdbId = null)
        val target = resolveStreamLookupTarget(
            details = details,
            selectedSeason = null,
            seasonEpisodes = emptyList(),
            fallbackMediaType = MetadataLabMediaType.ANIME,
        )
        assertEquals(MetadataLabMediaType.ANIME, target.mediaType, "an item type nobody maps is not a crash, it is the caller's answer")
    }

    @Test
    fun theSelectedSeasonIsNotConsultedAtAll() {
        // A claim about the code rather than a decision: `selectedSeason` is a
        // parameter that is never read. Pinning it means a future edit that starts
        // to use it has to change this test, which is when the question should
        // actually be asked.
        val details = details(itemType = "series", id = "s", imdbId = "tt0903747")
        val first = resolveStreamLookupTarget(details, 1, emptyList(), MetadataLabMediaType.SERIES)
        val second = resolveStreamLookupTarget(details, 9, emptyList(), MetadataLabMediaType.SERIES)
        assertEquals(first.lookupId, second.lookupId, "a parameter that changes nothing must be either deleted or explained, not left drifting")
    }

    // ----------------------------------------------- resolveStreamLookupTargetFromIdentity

    @Test
    fun anImdbIdThatDoesNotStartWithTtIsDroppedWithoutSignal() {
        val identity = PlaybackIdentity(
            itemId = "tmdb-550",
            imdbId = "550",
            contentType = MetadataLabMediaType.MOVIE,
            title = "Fight Club",
        )
        val target = resolveStreamLookupTargetFromIdentity(identity)
        assertEquals("tmdb-550", target.lookupId, "a numeric id parked in imdbId is discarded and itemId answers instead -- silently, which is why it is pinned")
    }

    @Test
    fun theTtPrefixTestIsCaseInsensitiveButTheIdIsKeptAsWritten() {
        val identity = PlaybackIdentity(
            itemId = "fallback",
            imdbId = "TT0903747",
            contentType = MetadataLabMediaType.MOVIE,
            title = "Show",
        )
        val target = resolveStreamLookupTargetFromIdentity(identity)
        assertEquals("TT0903747", target.lookupId, "the prefix is matched ignoring case; the id itself is not rewritten")
    }

    @Test
    fun anEpicIdentityBuildsAnEpisodeIdAndFallsBackWhenItCannot() {
        val withEpisode = resolveStreamLookupTargetFromIdentity(
            PlaybackIdentity(
                itemId = "season-3",
                imdbId = "tt0903747",
                contentType = MetadataLabMediaType.SERIES,
                title = "Show",
                season = 2,
                episode = 7,
            ),
        )
        assertEquals("tt0903747:2:7", withEpisode.lookupId)
        assertEquals(MetadataLabMediaType.SERIES, withEpisode.mediaType)

        val withoutEpisode = resolveStreamLookupTargetFromIdentity(
            PlaybackIdentity(
                itemId = "season-3",
                imdbId = "tt0903747",
                contentType = MetadataLabMediaType.SERIES,
                title = "Show",
                season = null,
                episode = null,
            ),
        )
        assertEquals("tt0903747", withoutEpisode.lookupId, "no episode halves means no episode id, so the base id stands alone")
    }

    // ---------------------------------------------------------- findEpisodeForLookupId

    @Test
    fun aBlankLookupIdMatchesNothingRatherThanSomethingBlank() {
        val episodes = listOf(episode(id = "   ", lookupId = null))
        assertNull(
            findEpisodeForLookupId("   ", episodes, listOf(episodes)),
            "the id is trimmed and the empty case short-circuits, so a blank request never matches a blank id by accident",
        )
    }

    @Test
    fun currentEpisodesWinOverCachedOnesAndEitherIdKindMatches() {
        val current = listOf(episode(id = "curr", lookupId = "TT-KEY"))
        val cached = listOf(episode(id = "cach", lookupId = "tt-key"))

        // The two halves are different shapes on purpose: `currentEpisodes` is the flat
        // list for the season on screen, while `cachedEpisodes` is the whole
        // per-season cache. That asymmetry is why the current list is searched
        // first -- it is the one the user is actually looking at.
        val byLookup = findEpisodeForLookupId("tt-key", current, listOf(cached))
        assertEquals("curr", byLookup?.id, "the current list is walked first; a cached copy of the same episode loses")

        val byId = findEpisodeForLookupId("CACH", current, listOf(cached))
        assertEquals("cach", byId?.id, "the match is on either identifier and is case-insensitive, so an id from one list works against the other")
    }

    @Test
    fun theCachedListIsOnlyConsultedWhenTheCurrentOneHasNoAnswer() {
        val current = listOf(episode(id = "only", lookupId = null))
        val cached = listOf(episode(id = "other", lookupId = "alias"))
        // The rule is "the first match in current-then-cached wins", which is not
        // the same as "current shadows cached". With no match in `current` at all,
        // the cached copy is the answer -- shadowing would mean *dropping* it, and
        // an earlier version of this case asserted exactly that and was wrong.
        assertEquals("other", findEpisodeForLookupId("alias", current, listOf(cached))?.id, "a lookup with no answer on screen is answered from the cache")
        assertEquals("other", findEpisodeForLookupId("alias", emptyList(), listOf(cached))?.id, "with nothing current the cache is the answer")
        assertNull(findEpisodeForLookupId("absent", current, listOf(cached)), "a lookup nothing answers is null rather than a stand-in episode")
    }

    // ----------------------------------------------------------- buildPlayerSubtitle

    @Test
    fun theSeriesTitleIsSuppressedWhenItIsAlsoWhatThePlayerIsAlreadyShowing() {
        val details = details(itemType = "series", id = "s", imdbId = "tt1", title = "Battlestar Galactica")
        val subtitle = buildPlayerSubtitle(
            mediaType = MetadataLabMediaType.SERIES,
            details = details,
            playerTitle = "Battlestar Galactica",
            season = 1,
            episode = 2,
        )
        assertEquals("S01E02", subtitle, "repeating the show name beside itself is noise; the episode number alone is the useful half")
    }

    @Test
    fun theSeriesTitleJoinsTheEpisodeLabelWhenTheTwoAreDifferent() {
        val details = details(itemType = "series", id = "s", imdbId = "tt1", title = "Battlestar Galactica")
        val subtitle = buildPlayerSubtitle(
            mediaType = MetadataLabMediaType.SERIES,
            details = details,
            playerTitle = "Something Else",
            season = 1,
            episode = 2,
        )
        assertEquals("Battlestar Galactica • S01E02", subtitle)
    }

    @Test
    fun theThreeEpisodeLabelArmsAreAllReachable() {
        val details = details(itemType = "series", id = "s", imdbId = "tt1", title = "Show")

        // `playerTitle` is set to the series' own title here so the suppression
        // rule the first two cases pin is not what these three are measuring.
        val both = buildPlayerSubtitle(MetadataLabMediaType.SERIES, details, "Show", season = 3, episode = 4)
        assertEquals("S03E04", both, "two-digit padding is part of the format, not decoration")

        val seasonOnly = buildPlayerSubtitle(MetadataLabMediaType.SERIES, details, "Show", season = 3, episode = null)
        assertEquals("Season 3", seasonOnly, "with no episode there is no SxxEyy, so the season alone carries the line")

        val neither = buildPlayerSubtitle(MetadataLabMediaType.SERIES, details, "Show", season = null, episode = null)
        assertNull(neither, "a series with neither number has nothing to add, so the player shows no subtitle at all")
    }

    @Test
    fun aBlankDetailsTitleLeavesTheLabelStandingAlone() {
        val details = details(itemType = "series", id = "s", imdbId = "tt1", title = "   ")
        val subtitle = buildPlayerSubtitle(MetadataLabMediaType.SERIES, details, "Other", season = 1, episode = 1)
        assertEquals("S01E01", subtitle, "a blank title is not a title, so it does not join the separator")
    }

    @Test
    fun aMovieShowsItsYearAndANewestYearStillShows() {
        val withYear = details(itemType = "movie", id = "m", imdbId = "tt1", year = "1999")
        assertEquals("1999", buildPlayerSubtitle(MetadataLabMediaType.MOVIE, withYear, "Matrix", season = null, episode = null))

        val blankYear = details(itemType = "movie", id = "m", imdbId = "tt1", year = "  ")
        assertNull(
            buildPlayerSubtitle(MetadataLabMediaType.MOVIE, blankYear, "Matrix", season = null, episode = null),
            "a blank year is trimmed then treated as missing, not shown as whitespace",
        )
    }

    // --------------------------------------------------------- provider result merging

    @Test
    fun anUnknownProviderIsAppendedRatherThanIgnored() {
        val list = listOf(provider("trakt", "Trakt"))
        val merged = list.applyProviderResult(result("Mdb", "Mdb"))
        assertEquals(listOf("trakt", "mdb"), merged.map { it.providerId.lowercase() }, "a provider arriving late is a new row, not a dropped one")
    }

    @Test
    fun providersMatchCaseInsensitivelyAndTheWinnerIsTheIncomingResult() {
        val seeded = listOf(provider("Trakt", "Trakt (loading)", isLoading = true))
        val merged = seeded.applyProviderResult(result("TRAKT", "Trakt"))
        assertEquals(1, merged.size, "one provider spelled two ways is one provider")
        assertEquals("Trakt", merged.single().providerName, "the incoming result replaces the placeholder wholesale, name included")
        assertEquals(false, merged.single().isLoading, "a settled result is no longer loading")
    }

    @Test
    fun aResultSetNeverDropsAProviderItWasMergedInto() {
        val seeded = listOf(provider("trakt", "Trakt"))
        val merged = seeded.finalizeFrom(listOf(result("tmdb", "TMDB")))
        assertEquals(listOf("trakt", "tmdb"), merged.map { it.providerId.lowercase() })

        val known = listOf(provider("tmdb", "TMDB"))
        assertEquals(
            listOf("tmdb"),
            known.finalizeFrom(listOf(result("TMDB", "TMDB"))).map { it.providerId.lowercase() }.distinct(),
            "a provider already known is not added a second time, and the spelling does not matter",
        )
    }

    @Test
    fun seedingAddsLoadingPlaceholdersWithoutTouchingWhatIsAlreadyThere() {
        val arrived = listOf(provider("trakt", "Trakt", isLoading = false))
        val seeded = arrived.seedProviders(descriptors())
        assertEquals(listOf("trakt", "tmdb"), seeded.map { it.providerId.lowercase() }, "only the descriptor nobody has answered yet becomes a placeholder")
        assertEquals(false, seeded[0].isLoading, "a result that already arrived keeps its state")
        assertEquals(true, seeded[1].isLoading, "the placeholder exists to render a spinner until a real result replaces it")

        val again = seeded.seedProviders(descriptors())
        assertEquals(2, again.size, "seeding is idempotent -- a second seed must not double every placeholder")
    }

    @Test
    fun visibleProvidersExcludesThePlaceholdersAndCountsWhatSurvives() {
        val list = listOf(
            provider("trakt", "Trakt", streamCount = 2),
            provider("tmdb", "TMDB", isLoading = true, streamCount = 0),
        )
        assertEquals(listOf("trakt"), list.visibleProviders().map { it.providerId }, "visibility is about delivered streams, not about whether a row exists")

        val empty = provider("empty", "Empty", streamCount = 0)
        assertEquals(0, empty.streams.size, "an empty row is still a row; it just renders nothing")
    }

    @Test
    fun toUiStateCarriesEveryFieldOverAndClearsTheLoadingFlag() {
        val result = ProviderStreamsResult(
            providerId = "trakt",
            providerName = "Trakt",
            streams = emptyList(),
            errorMessage = "offline",
            attemptedUrl = "https://example.test",
        )
        val ui = result.toUiState()
        assertEquals("trakt", ui.providerId)
        assertEquals("Trakt", ui.providerName)
        assertEquals(false, ui.isLoading, "a result describes an outcome, so it can never still be loading")
        assertEquals("offline", ui.errorMessage, "a failed provider keeps its message rather than rendering as empty")
        assertEquals("https://example.test", ui.attemptedUrl)
    }

    // ------------------------------------------------------------------ fixtures

    private fun details(
        itemType: String,
        id: String,
        imdbId: String?,
        itemId: String? = null,
        title: String = "A title",
        year: String? = null,
    ) = MediaDetails(
        id = id,
        itemId = itemId,
        imdbId = imdbId,
        itemType = itemType,
        title = title,
        artworkUrl = "https://img.test/poster.jpg",
        description = "A description",
        year = year,
        runtime = "60m",
        certification = "TV-MA",
        rating = "8.4",
        addonId = "backend",
    )

    private fun episode(id: String, lookupId: String?) = MediaVideo(
        id = id,
        title = "Episode",
        season = 1,
        episode = 1,
        released = null,
        overview = null,
        thumbnailUrl = null,
        lookupId = lookupId,
    )

    private fun provider(providerId: String, providerName: String, isLoading: Boolean = false, streamCount: Int = 0) = StreamProviderUiState(
        providerId = providerId,
        providerName = providerName,
        isLoading = isLoading,
        streams = List(streamCount) { index -> stream(providerId, index) },
    )

    // `visibleProviders` and `applyProviderResult` are the only two functions here
    // that care whether a row has streams, so the fixture has to be able to
    // produce them. `AddonStream` needs three fields and defaults the rest.
    private fun stream(providerId: String, index: Int) = AddonStream(
        providerId = providerId,
        providerName = providerId,
        stableKey = "$providerId#$index",
    )

    private fun result(providerId: String, providerName: String) = ProviderStreamsResult(
        providerId = providerId,
        providerName = providerName,
        streams = emptyList(),
    )

    private fun descriptors(): List<com.crispy.tv.addons.streams.StreamProviderDescriptor> = listOf(
        com.crispy.tv.addons.streams.StreamProviderDescriptor(providerId = "tmdb", providerName = "TMDB"),
    )
}
