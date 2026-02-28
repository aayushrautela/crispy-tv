package com.crispy.tv.contracts

import com.crispy.tv.domain.catalog.NormalizedSearchItem
import com.crispy.tv.domain.catalog.TmdbSearchResultInput
import com.crispy.tv.domain.catalog.normalizeTmdbSearchResults
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRankingAndDedupContractTest {
    @Test
    fun fixturesMatchSearchRankingAndDedup() {
        val fixturePaths = ContractTestSupport.fixtureFiles("search_ranking_and_dedup")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one search_ranking_and_dedup fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals(
                "search_ranking_and_dedup",
                fixture.requireString("suite", path),
                "$caseId: wrong suite"
            )

            val input = fixture.requireJsonObject("input", path)
            val results =
                input.requireJsonArray("results", path).mapIndexed { index, element ->
                    val result =
                        element as? JsonObject
                            ?: error("$caseId: input.results[$index] must be object")
                    TmdbSearchResultInput(
                        mediaType = result.requireString("media_type", path),
                        id = result.requireInt("id", path),
                        title = result.optionalString("title", path),
                        name = result.optionalString("name", path),
                        releaseDate = result.optionalString("release_date", path),
                        firstAirDate = result.optionalString("first_air_date", path),
                        posterPath = result.optionalString("poster_path", path),
                        profilePath = result.optionalString("profile_path", path),
                        voteAverage = result.optionalDouble("vote_average")
                    )
                }

            val expected = fixture.requireJsonObject("expected", path)
            val expectedItems =
                expected.requireJsonArray("items", path).mapIndexed { index, element ->
                    val item =
                        element as? JsonObject
                            ?: error("$caseId: expected.items[$index] must be object")
                    NormalizedSearchItem(
                        id = item.requireString("id", path),
                        type = item.requireString("type", path),
                        title = item.requireString("title", path),
                        year = item.optionalInt("year", path),
                        imageUrl = item.optionalString("image_url", path),
                        rating = item.optionalDouble("rating")
                    )
                }

            val actualItems = normalizeTmdbSearchResults(results)

            assertEquals(expectedItems, actualItems, "$caseId: items")
        }
    }
}

private fun JsonObject.optionalDouble(key: String): Double? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.jsonPrimitive.doubleOrNull
}
