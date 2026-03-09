package com.crispy.tv.contracts

import com.crispy.tv.domain.metadata.OmdbDetails
import com.crispy.tv.domain.metadata.OmdbRating
import com.crispy.tv.domain.metadata.OmdbRatingInput
import com.crispy.tv.domain.metadata.normalizeOmdbDetails
import com.crispy.tv.domain.metadata.normalizeOmdbImdbId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class OmdbContractTest {
    @Test
    fun fixturesMatchOmdbNormalizationRules() {
        val fixturePaths = ContractTestSupport.fixtureFiles("omdb")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one omdb fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("omdb", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val payload = input.requireJsonObject("payload", path)
            val expected = fixture.requireJsonObject("expected", path)

            val actualNormalizedImdbId = normalizeOmdbImdbId(input.optionalString("imdb_id", path))
            val actualDetails = normalizeOmdbDetails(
                ratings = payload.requireJsonArray("ratings", path).map { parseRatingInput(it.jsonObject, path) },
                metascore = payload.optionalString("metascore", path),
                imdbRating = payload.optionalString("imdb_rating", path),
                imdbVotes = payload.optionalString("imdb_votes", path),
                type = payload.optionalString("type", path),
            )

            assertEquals(
                expected.optionalString("normalized_imdb_id", path),
                actualNormalizedImdbId,
                "$caseId: normalized_imdb_id",
            )
            assertEquals(parseExpectedDetails(expected.requireJsonObject("details", path), path), actualDetails, "$caseId: details")
        }
    }

    private fun parseRatingInput(json: JsonObject, path: Path): OmdbRatingInput {
        return OmdbRatingInput(
            source = json.optionalString("source", path),
            value = json.optionalString("value", path),
        )
    }

    private fun parseExpectedDetails(json: JsonObject, path: Path): OmdbDetails {
        return OmdbDetails(
            ratings = json.requireJsonArray("ratings", path).map { parseRating(it.jsonObject, path) },
            metascore = json.optionalString("metascore", path),
            imdbRating = json.optionalString("imdb_rating", path),
            imdbVotes = json.optionalString("imdb_votes", path),
            type = json.optionalString("type", path),
        )
    }

    private fun parseRating(json: JsonObject, path: Path): OmdbRating {
        return OmdbRating(
            source = json.requireString("source", path),
            value = json.requireString("value", path),
        )
    }
}
