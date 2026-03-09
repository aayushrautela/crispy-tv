package com.crispy.tv.contracts

import com.crispy.tv.domain.metadata.MetadataMediaType
import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.domain.metadata.MetadataSeason
import com.crispy.tv.domain.metadata.MetadataVideo
import com.crispy.tv.domain.metadata.bridgeCandidateIds
import com.crispy.tv.domain.metadata.withDerivedSeasons
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class MetadataTmdbEnhancerContractTest {
    @Test
    fun fixturesMatchMetadataTmdbEnhancerRules() {
        val fixturePaths = ContractTestSupport.fixtureFiles("metadata_tmdb_enhancer")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one metadata_tmdb_enhancer fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals(
                "metadata_tmdb_enhancer",
                fixture.requireString("suite", path),
                "$caseId: wrong suite",
            )

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)

            val mediaType = MetadataMediaType.fromContractValue(input.requireString("media_type", path))
            val meta = parseMetadataRecord(input.requireJsonObject("meta", path), path)
            val tmdbMeta = input.optionalJsonObject("tmdb_meta", path)?.let { parseMetadataRecord(it, path) }

            val actualDerived = withDerivedSeasons(meta, mediaType).seasons
            val actualBridgeCandidates = bridgeCandidateIds(
                contentId = input.requireString("content_id", path),
                season = input.optionalInt("season", path),
                episode = input.optionalInt("episode", path),
                tmdbMeta = tmdbMeta,
            )

            val expectedDerived = expected.requireJsonArray("derived_seasons", path).map {
                parseMetadataSeason(it.jsonObject, path)
            }
            val expectedBridgeCandidates = expected.requireJsonArray("bridge_candidate_ids", path).toStringList()

            assertEquals(expectedDerived, actualDerived, "$caseId: derived_seasons")
            assertEquals(expectedBridgeCandidates, actualBridgeCandidates, "$caseId: bridge_candidate_ids")
        }
    }

    private fun parseMetadataRecord(json: JsonObject, path: Path): MetadataRecord {
        return MetadataRecord(
            id = json.requireString("id", path),
            imdbId = json.optionalString("imdb_id", path),
            cast = json.requireJsonArray("cast", path).toStringList(),
            director = json.requireJsonArray("director", path).toStringList(),
            castWithDetails = json.requireJsonArray("cast_with_details", path).toStringList(),
            similar = json.requireJsonArray("similar", path).toStringList(),
            collectionItems = json.requireJsonArray("collection_items", path).toStringList(),
            seasons = json.requireJsonArray("seasons", path).map { parseMetadataSeason(it.jsonObject, path) },
            videos = json.requireJsonArray("videos", path).map { parseMetadataVideo(it.jsonObject, path) },
        )
    }

    private fun parseMetadataSeason(json: JsonObject, path: Path): MetadataSeason {
        return MetadataSeason(
            id = json.requireString("id", path),
            name = json.requireString("name", path),
            overview = json.requireString("overview", path),
            seasonNumber = json.requireInt("season_number", path),
            episodeCount = json.requireInt("episode_count", path),
            airDate = json.optionalString("air_date", path),
        )
    }

    private fun parseMetadataVideo(json: JsonObject, path: Path): MetadataVideo {
        return MetadataVideo(
            season = json.optionalInt("season", path),
            episode = json.optionalInt("episode", path),
            released = json.optionalString("released", path),
        )
    }
}
