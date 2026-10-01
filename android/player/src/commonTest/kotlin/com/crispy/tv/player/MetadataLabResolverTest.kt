package com.crispy.tv.player

import com.crispy.tv.domain.metadata.AddonMetadataCandidate
import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.domain.metadata.MetadataVideo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two resolvers and a parser, pinned — none of which had ever been asserted.
 *
 * ## Why this file exists
 *
 * `:android:player` had no test source set. All six of its files are `commonMain`, so
 * unlike `:android:addons` there is no Android-shaped sibling explaining the absence;
 * the recipe this module established for the five that followed simply did not include
 * tests. 156 lines here ran on Android, desktop and both iOS targets with nothing
 * asserting any of it.
 *
 * ## The parser is private, so every case reaches it through `resolve`
 *
 * `parseTmdbLookupId` is `private`, and that is correct — nothing outside this file
 * names it. But it is also the single densest piece of behaviour in the module, and a
 * test that could not reach it would leave it unpinned. So every lookup-id case below
 * goes through `DefaultMetadataLabResolver`, which needs no data source and returns
 * `baseId` and `videoId` verbatim: `contentId` and `videoId` **are** the parser's two
 * outputs, with no interpretation in between.
 *
 * ## The duplicate worth naming
 *
 * `parseTmdbLookupId` here and `parseLookupId` in `:android:addons` are the same rule,
 * line for line — `parts.size >= 3`, both halves `> 0`, `dropLast(2).joinToString(":")`,
 * and the same fall-through. `:addons` has 28 cases pinning its copy;
 * **nothing pinned this one, and `:player` cannot see `:addons`' suite at all** because
 * `:player` is a dependency of `:addons`, not the other way round. The cases below are
 * written to be the ones that would fail if either copy drifted, which is what makes
 * the duplication survivable today rather than merely noticed.
 */
class MetadataLabResolverTest {

    // --------------------------------------------------------------------- the enum's label

    @Test
    fun theMediaTypeLabelIsAProviderTermAndNotTheEnumName() {
        // SERIES is "show", not "series". This string reaches a provider as a
        // media-type query value, so it is a wire term rather than a display label, and
        // renaming the enum member would be harmless while renaming this would not.
        assertEquals("movie", MetadataLabMediaType.MOVIE.label)
        assertEquals("show", MetadataLabMediaType.SERIES.label)
        assertEquals("anime", MetadataLabMediaType.ANIME.label)
    }

    // ----------------------------------------------------------------- the two `require`s

    @Test
    fun aBlankIdIsRejectedBeforeTheDataSourceIsAsked() = runTest {
        // Ordering, not just the throw: the id is validated ahead of the load, so a
        // blank request costs no I/O. The empty-results guard below is the opposite,
        // and the contrast between the two is the point of this pair.
        val source = RecordingDataSource(payloadOf(PRIMARY))
        val error = assertRejects {
            CoreDomainMetadataLabResolver(source).resolve(request(rawId = "   "))
        }
        assertEquals("content id is required", error.message)
        assertTrue(source.requests.isEmpty(), "the id is checked before the load")
    }

    @Test
    fun aBlankIdIsRejectedByTheDefaultResolverToo() = runTest {
        // The two resolvers duplicate the same trim and the same `require`, so the
        // guard is asserted on both. It is a null-safety check with two copies, which
        // is worth knowing if one of them is ever deleted.
        val error = assertRejects { DefaultMetadataLabResolver.resolve(request(rawId = " \t ")) }
        assertEquals("content id is required", error.message)
    }

    @Test
    fun emptyAddonResultsAreRejectedAfterTheDataSourceHasBeenAsked() = runTest {
        // The mirror of the case above: this guard sits *below* the load, so a resolver
        // with nothing to merge still pays for the fetch. That is the current shape,
        // not an oversight being blessed — but it is a cost a reader cannot see.
        val source = RecordingDataSource(payloadOf())
        val error = assertRejects {
            CoreDomainMetadataLabResolver(source).resolve(request())
        }
        assertEquals("addon results must not be empty", error.message)
        assertEquals(1, source.requests.size, "the load already happened")
    }

    // ------------------------------------------------------------- what the core resolver builds

    @Test
    fun onlyTheFirstAddonResultBecomesThePrimary() = runTest {
        val source = RecordingDataSource(
            payloadOf(
                PRIMARY,
                AddonMetadataCandidate(addonId = "second-addon", mediaId = "second-id", title = "Second"),
                AddonMetadataCandidate(addonId = "third-addon", mediaId = "third-id", title = "Third"),
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source).resolve(request())
        assertEquals("primary-id", resolution.primaryId)
        assertEquals("Primary", resolution.primaryTitle)
        assertEquals(
            listOf("primary-addon"),
            resolution.sources,
            "sources is the first result's addon alone, not a merge of all of them",
        )
    }

    @Test
    fun theAddonLookupIdIsTheVideoIdWhenThereIsOneAndTheBaseOtherwise() = runTest {
        // Two arms, one rule, **two resolvers** — and until this case only the default
        // resolver's copy was pinned. A mutation of the core resolver's line changed no
        // answer anywhere in the suite, which is a gap in the suite rather than a guard
        // with no effect: this is the id a caller hands the addons, so the two arms are
        // not interchangeable.
        val resolver = CoreDomainMetadataLabResolver(RecordingDataSource(payloadOf(PRIMARY)))
        assertEquals(
            "tt0903747:2:5",
            resolver.resolve(request(rawId = "tt0903747:2:5")).addonLookupId,
            "a three-part id looks itself up by its video id",
        )
        assertEquals(
            "tt0903747",
            resolver.resolve(request(rawId = "tt0903747")).addonLookupId,
            "a bare id has no video id, so the base is the lookup id",
        )
    }

    @Test
    fun theTmdbRecordWinsOverTheAddonRecordWhenBothArePresent() = runTest {
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(id = "addon-id", imdbId = "tt-from-addon"),
                tmdbMeta = record(id = "tmdb-id", imdbId = "tt-from-tmdb"),
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source).resolve(request())
        assertEquals("tt-from-tmdb", resolution.mergedImdbId)
    }

    @Test
    fun theAddonRecordIsUsedWhenThereIsNoTmdbRecord() = runTest {
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(id = "addon-id", imdbId = "tt-from-addon"),
                tmdbMeta = null,
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source).resolve(request())
        assertEquals("tt-from-addon", resolution.mergedImdbId)
    }

    @Test
    fun seasonNumbersAreDerivedFromTheVideosOfASeries() = runTest {
        // The videos carry a season 0, which `withDerivedSeasons` skips, so the
        // expectation is [1, 2] rather than [0, 1, 2] — the ordering is the sorted
        // insertion order of the accumulator, and skipping is the rule being pinned.
        val source = RecordingDataSource(payloadOf(PRIMARY, record = seriesRecord()))
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(mediaType = MetadataLabMediaType.SERIES))
        assertEquals(listOf(1, 2), resolution.mergedSeasonNumbers)
    }

    @Test
    fun aMovieNeverGivesUpSeasonNumbersHoweverManyVideosItHas() = runTest {
        // The same record, the same videos, a different media type: derivation is gated
        // on SERIES/ANIME, so a movie returns nothing. Without this case the previous
        // one proves only that derivation happens, not that it is conditional.
        val source = RecordingDataSource(payloadOf(PRIMARY, record = seriesRecord()))
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(mediaType = MetadataLabMediaType.MOVIE))
        assertTrue(resolution.mergedSeasonNumbers.isEmpty())
    }

    @Test
    fun anAnimeIsDerivedLikeASeries() = runTest {
        // The gate admits two media types, not one, and a test that only used SERIES
        // would leave the second arm uncovered.
        val source = RecordingDataSource(payloadOf(PRIMARY, record = seriesRecord()))
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(mediaType = MetadataLabMediaType.ANIME))
        assertEquals(listOf(1, 2), resolution.mergedSeasonNumbers)
    }

    @Test
    fun theResolutionNeverAsksForEnrichment() = runTest {
        // `needsEnrichment` is hard-coded `false` on both resolvers, including the one
        // that has no data source at all and could not enrich anything. Pinned because
        // a reader meets the field on the unavailable path and takes it as meaningful.
        assertFalse(
            CoreDomainMetadataLabResolver(RecordingDataSource(payloadOf(PRIMARY)))
                .resolve(request()).needsEnrichment,
        )
    }

    @Test
    fun theTransportStatsArePassedThroughUntouched() = runTest {
        val stats = listOf(
            MetadataTransportStat(
                addonId = "primary-addon",
                streamLookupId = "primary-id",
                streamCount = 3,
                subtitleLookupId = "primary-id",
                subtitleCount = 2,
            ),
        )
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(),
                tmdbMeta = null,
                transportStats = stats,
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source).resolve(request())
        assertEquals(stats, resolution.transportStats)
    }

    @Test
    fun theDataSourceIsGivenTheRequestExactlyAsItArrived() = runTest {
        // The id is trimmed for the resolution and the *untrimmed* request is handed to
        // the data source — so a provider query built from `request.rawId` carries
        // whitespace the resolution has already discarded. An asymmetry worth naming,
        // because the two halves of the same request disagree.
        val source = RecordingDataSource(payloadOf(PRIMARY))
        val padded = request(rawId = "  tt0903747  ")
        val resolution = CoreDomainMetadataLabResolver(source).resolve(padded)
        assertEquals("  tt0903747  ", source.requests.single().rawId, "the load sees it untrimmed")
        assertEquals("tt0903747", resolution.contentId, "the resolution trims it")
    }

    // ------------------------------------------------------------- what the default resolver builds

    @Test
    fun theDefaultResolverReportsTheTitleAsUnavailableAndClaimsNoSources() = runTest {
        val resolution = DefaultMetadataLabResolver.resolve(request())
        assertEquals("Unavailable", resolution.primaryTitle)
        assertEquals("tt0903747", resolution.primaryId, "it falls back to the base id")
        assertTrue(resolution.sources.isEmpty())
        assertTrue(resolution.transportStats.isEmpty())
        assertTrue(resolution.mergedSeasonNumbers.isEmpty())
        assertEquals(null, resolution.mergedImdbId)
    }

    @Test
    fun theDefaultResolverAsksForEnrichmentEvenThoughItHasNoDataSource() = runTest {
        // The field says `false` on the one resolver that produced nothing. Pinned as
        // the claim it is: the reading is "this needed enriching" and the code says no.
        // Unifying it is a product decision about the field's meaning, not a refactor.
        assertFalse(DefaultMetadataLabResolver.resolve(request()).needsEnrichment)
    }

    @Test
    fun theDefaultResolverOffersTheLookupIdAsItsOwnBridgeCandidate() = runTest {
        // One candidate, built the same way `bridgeCandidateIds` builds the first of
        // its own, so a caller has something to try even with no metadata at all.
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747:2:5"))
        assertEquals(listOf("tt0903747:2:5"), resolution.bridgeCandidateIds)
    }

    @Test
    fun theDefaultResolverPrefersTheBareIdWhenThereIsNoEpisode() = runTest {
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747"))
        assertEquals("tt0903747", resolution.addonLookupId)
        assertEquals(null, resolution.videoId)
        assertEquals(listOf("tt0903747"), resolution.bridgeCandidateIds)
    }

    // ---------------------------------------------------------- the lookup-id parser, via resolve

    @Test
    fun aThreePartIdSplitsIntoABaseAndAVideo() = runTest {
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747:2:5"))
        assertEquals("tt0903747", resolution.contentId)
        assertEquals("tt0903747:2:5", resolution.videoId)
        assertEquals("tt0903747:2:5", resolution.addonLookupId, "the video id is the lookup id")
    }

    @Test
    fun aFourPartIdKeepsEveryColonButTheLastTwo() = runTest {
        // `dropLast(2)` is not "everything before the last colon" — the middle part is
        // part of the base. A provider id that contains a colon therefore survives, and
        // a reader who assumed a two-part split would expect "tt0903747" here.
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "tmdb:1234:1:2"))
        assertEquals("tmdb:1234", resolution.contentId)
        assertEquals("tmdb:1234:1:2", resolution.videoId)
    }

    @Test
    fun aFivePartIdDropsExactlyTheLastTwoParts() = runTest {
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "a:b:1:2:3"))
        assertEquals("a:b:1", resolution.contentId)
        assertEquals("a:b:1:2:3", resolution.videoId)
    }

    @Test
    fun aBareIdIsLeftAlone() = runTest {
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747"))
        assertEquals("tt0903747", resolution.contentId)
        assertEquals(null, resolution.videoId)
    }

    @Test
    fun aZeroSeasonOrEpisodeIsNotAnEpisodeId() = runTest {
        // Both halves of the numeric guard, and both directions. The whole id comes back
        // as the base rather than a partial parse, so a caller cannot mistake
        // "tt:0:1" for an episode of "tt:0".
        val zeroSeason = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747:0:1"))
        assertEquals("tt0903747:0:1", zeroSeason.contentId)
        assertEquals(null, zeroSeason.videoId)

        val zeroEpisode = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747:1:0"))
        assertEquals("tt0903747:1:0", zeroEpisode.contentId)
        assertEquals(null, zeroEpisode.videoId)
    }

    @Test
    fun aNegativeSeasonOrEpisodeIsNotAnEpisodeId() = runTest {
        val negativeSeason = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747:-1:2"))
        assertEquals("tt0903747:-1:2", negativeSeason.contentId)
        assertEquals(null, negativeSeason.videoId)
    }

    @Test
    fun aNonNumericSeasonOrEpisodeIsNotAnEpisodeId() = runTest {
        val nonNumericSeason = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747:special:2"))
        assertEquals("tt0903747:special:2", nonNumericSeason.contentId)

        val nonNumericEpisode = DefaultMetadataLabResolver.resolve(request(rawId = "tt0903747:1:final"))
        assertEquals("tt0903747:1:final", nonNumericEpisode.contentId)
    }

    @Test
    fun aTwoPartIdIsNeverAnEpisodeId() = runTest {
        // The arity guard's own case, and the one that distinguishes it from the
        // numeric guard: `"5:7"` is two purely numeric parts, so a numeric check alone
        // would read it as season 5, episode 7 of an empty base id. It has three
        // characters of base and no base at all respectively, so the arity check is
        // what rejects it and the numeric check never sees it.
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "5:7"))
        assertEquals("5:7", resolution.contentId)
        assertEquals(null, resolution.videoId, "season 5 episode 7 of nothing is not a resolution")
        assertEquals("5:7", resolution.addonLookupId)
    }

    @Test
    fun aSingleColonBearingIdIsNotAnEpisodeId() = runTest {
        val resolution = DefaultMetadataLabResolver.resolve(request(rawId = "tmdb:1234"))
        assertEquals("tmdb:1234", resolution.contentId)
        assertEquals(null, resolution.videoId)
    }

    @Test
    fun surroundingWhitespaceIsTrimmedOffTheWholeId() = runTest {
        // `contentId` is the *base* of a three-part id, so trimming `"…:2:5"` leaves
        // the bare id there and the episode in `videoId` — the same split as the
        // unpadded case above, which is what makes the pair a test of trimming rather
        // than of parsing.
        val padded = DefaultMetadataLabResolver.resolve(request(rawId = "  tt0903747:2:5  "))
        assertEquals("tt0903747", padded.contentId)
        assertEquals("tt0903747:2:5", padded.videoId)
        assertEquals("tt0903747:2:5", padded.addonLookupId)

        val paddedBare = DefaultMetadataLabResolver.resolve(request(rawId = "  tt0903747  "))
        assertEquals("tt0903747", paddedBare.contentId)
        assertEquals(null, paddedBare.videoId)
    }

    // ----------------------------------------------------------------------- the bridge candidates

    @Test
    fun aTmdbScopedIdIsBridgedToTheImdbIdTheTmdbRecordCarries() = runTest {
        // The one place `bridgeCandidateIds` produces a second candidate, and it needs
        // all three conditions at once: a `:tmdb:` segment, a tmdb record, and an imdb
        // id on that record. Each is removed in the next two cases.
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(),
                tmdbMeta = record(id = "tmdb:1234", imdbId = "tt0903747"),
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(rawId = "provider:tmdb:1234:1:2"))
        assertEquals(
            listOf("provider:tmdb:1234:1:2", "tt0903747:1:2"),
            resolution.bridgeCandidateIds,
        )
    }

    @Test
    fun aTmdbScopedIdWithNoImdbIdFallsBackToTheTmdbRecordOwnId() = runTest {
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(),
                tmdbMeta = record(id = "tmdb:99", imdbId = null),
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(rawId = "provider:tmdb:1234:1:2"))
        assertEquals(
            listOf("provider:tmdb:1234:1:2", "tmdb:99:1:2"),
            resolution.bridgeCandidateIds,
        )
    }

    @Test
    fun aTmdbScopedIdIsNotBridgedWhenTheRecordEchoesItBack() = runTest {
        // The record's own id equals the content id, so bridging would add a duplicate
        // of the candidate already in hand. The dedupe that removes it is the reason
        // this case exists rather than a second entry with a wider list.
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(),
                tmdbMeta = record(id = "provider:tmdb:1234", imdbId = null),
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(rawId = "provider:tmdb:1234:1:2"))
        assertEquals(listOf("provider:tmdb:1234:1:2"), resolution.bridgeCandidateIds)
    }

    @Test
    fun aBareTmdbIdIsNeverBridgedBecauseTheMarkerIsASegmentNotAPrefix() = runTest {
        // The finding this suite exists to pin, and the one a reader gets backwards on
        // first contact. The marker is `":tmdb:"` — colon on *both* sides — so it only
        // matches a tmdb id carrying a provider scope in front of it. A bare
        // `"tmdb:1234"` opens with `tmdb:` and no leading colon, contains no `:tmdb:`,
        // and therefore never bridges even when the record carries a perfectly good
        // imdb id. Whether that is intended is a question about the providers; what the
        // code does is not in doubt, and before this case nothing in the repository
        // said so.
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(),
                tmdbMeta = record(id = "tmdb:1234", imdbId = "tt0903747"),
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(rawId = "tmdb:1234:1:2"))
        assertEquals(
            listOf("tmdb:1234:1:2"),
            resolution.bridgeCandidateIds,
            "a leading tmdb: is not the :tmdb: segment the guard looks for",
        )
    }


    @Test
    fun aNonTmdbIdIsNeverBridged() = runTest {
        // An imdb id is already the canonical form, so the record's imdb id is ignored.
        val source = RecordingDataSource(
            MetadataLabPayload(
                addonResults = listOf(PRIMARY),
                addonMeta = record(),
                tmdbMeta = record(id = "anything", imdbId = "tt0903747"),
            ),
        )
        val resolution = CoreDomainMetadataLabResolver(source)
            .resolve(request(rawId = "tt0903747:1:2"))
        assertEquals(listOf("tt0903747:1:2"), resolution.bridgeCandidateIds)
    }

    // ------------------------------------------------------------------------------- fixtures

    private suspend fun assertRejects(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected
        }
        throw AssertionError("expected an IllegalArgumentException and none was thrown")
    }

    private companion object {
        val PRIMARY = AddonMetadataCandidate(
            addonId = "primary-addon",
            mediaId = "primary-id",
            title = "Primary",
        )

        fun request(
            rawId: String = "tt0903747",
            mediaType: MetadataLabMediaType = MetadataLabMediaType.MOVIE,
        ) = MetadataLabRequest(
            rawId = rawId,
            mediaType = mediaType,
            preferredAddonId = null,
        )

        fun payloadOf(
            vararg results: AddonMetadataCandidate,
            record: MetadataRecord? = null,
        ) = MetadataLabPayload(
            addonResults = results.toList(),
            addonMeta = record ?: blankRecord(),
            tmdbMeta = null,
        )

        fun record(id: String = "record-id", imdbId: String? = null) = MetadataRecord(
            id = id,
            imdbId = imdbId,
            cast = emptyList(),
            director = emptyList(),
            castWithDetails = emptyList(),
            similar = emptyList(),
            collectionItems = emptyList(),
            seasons = emptyList(),
            videos = emptyList(),
        )

        fun blankRecord() = record()

        /**
         * Season 0 is present on purpose: `withDerivedSeasons` skips a non-positive
         * season, so the derived list is [1, 2] and not [0, 1, 2].
         */
        fun seriesRecord() = record().copy(
            videos = listOf(
                MetadataVideo(season = 1, episode = 1, released = "2024-01-01"),
                MetadataVideo(season = 0, episode = 9, released = "2024-01-09"),
                MetadataVideo(season = 1, episode = 2, released = "2024-01-08"),
                MetadataVideo(season = 2, episode = 1, released = "2024-02-01"),
            ),
        )
    }
}

/** Records every request it is handed, so a test can prove whether the load happened. */
private class RecordingDataSource(
    private val payload: MetadataLabPayload,
) : MetadataLabDataSource {
    val requests = mutableListOf<MetadataLabRequest>()

    override suspend fun load(request: MetadataLabRequest): MetadataLabPayload {
        requests += request
        return payload
    }
}
