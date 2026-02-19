package com.crispy.rewrite.contracts

import com.crispy.rewrite.domain.metadata.MetadataMediaType
import com.crispy.rewrite.domain.metadata.MetadataRecord
import com.crispy.rewrite.domain.metadata.MetadataSeason
import com.crispy.rewrite.domain.metadata.MetadataVideo
import com.crispy.rewrite.domain.metadata.bridgeCandidateIds
import com.crispy.rewrite.domain.metadata.mergeAddonAndTmdbMeta
import com.crispy.rewrite.domain.metadata.needsTmdbMetaEnrichment
import com.crispy.rewrite.domain.metadata.withDerivedSeasons
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataTmdbEnhancerContractTest {
    @Test
    fun fixturesMatchTmdbEnhancerRules() {
        val fixturePaths = ContractTestSupport.fixtureFiles("metadata_tmdb_enhancer")
        assertTrue(fixturePaths.isNotEmpty(), "Expected metadata_tmdb_enhancer fixtures")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals(
                "metadata_tmdb_enhancer",
                fixture.requireString("suite", path),
                "$caseId: wrong suite"
            )

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val mediaType = MetadataMediaType.fromContractValue(input.requireString("media_type", path))
            val contentId = input.requireString("content_id", path)
            val season = input.optionalInt("season", path)
            val episode = input.optionalInt("episode", path)

            val addonMeta = parseMetadataRecord(input.requireJsonObject("addon_meta", path), path)
            val tmdbMeta = input.optionalJsonObject("tmdb_meta")?.let { parseMetadataRecord(it, path) }

            val actualNeedsEnrichment = needsTmdbMetaEnrichment(addonMeta, mediaType)
            assertEquals(
                expected.requireBoolean("needs_enrichment", path),
                actualNeedsEnrichment,
                "$caseId: needs_enrichment"
            )

            val actualMerged = withDerivedSeasons(
                mergeAddonAndTmdbMeta(addonMeta, tmdbMeta, mediaType),
                mediaType
            )
            val expectedMerged = parseMetadataRecord(expected.requireJsonObject("merged", path), path)
            assertEquals(expectedMerged, actualMerged, "$caseId: merged")

            assertEquals(
                expected.requireJsonArray("derived_season_numbers", path).toIntList(path),
                actualMerged.seasons.map { it.seasonNumber },
                "$caseId: derived_season_numbers"
            )
            assertEquals(
                expected.requireJsonArray("derived_episode_counts", path).toIntList(path),
                actualMerged.seasons.map { it.episodeCount },
                "$caseId: derived_episode_counts"
            )

            assertEquals(
                expected.requireJsonArray("bridge_candidate_ids", path).toStringList(path),
                bridgeCandidateIds(contentId, season, episode, tmdbMeta),
                "$caseId: bridge_candidate_ids"
            )
        }
    }

    private fun parseMetadataRecord(source: JsonObject, path: java.nio.file.Path): MetadataRecord {
        val seasons =
            source
                .requireJsonArray("seasons", path)
                .map { element ->
                    val season = element.jsonObject
                    MetadataSeason(
                        id = season.requireString("id", path),
                        name = season.requireString("name", path),
                        overview = season.requireString("overview", path),
                        seasonNumber = season.requireInt("season_number", path),
                        episodeCount = season.requireInt("episode_count", path),
                        airDate = season.optionalString("air_date", path)
                    )
                }
        val videos =
            source
                .requireJsonArray("videos", path)
                .map { element ->
                    val video = element.jsonObject
                    MetadataVideo(
                        season = video.optionalInt("season", path),
                        episode = video.optionalInt("episode", path),
                        released = video.optionalString("released", path)
                    )
                }

        return MetadataRecord(
            id = source.requireString("id", path),
            imdbId = source.optionalString("imdb_id", path),
            cast = source.requireJsonArray("cast", path).toStringList(path),
            director = source.requireJsonArray("director", path).toStringList(path),
            castWithDetails = source.requireJsonArray("cast_with_details", path).toStringList(path),
            similar = source.requireJsonArray("similar", path).toStringList(path),
            collectionItems = source.requireJsonArray("collection_items", path).toStringList(path),
            seasons = seasons,
            videos = videos
        )
    }
}
