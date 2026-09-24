package com.crispy.tv.contracts

import com.crispy.tv.domain.browse.BrowseCombo
import com.crispy.tv.domain.browse.BrowsePageResult
import com.crispy.tv.domain.browse.BrowseRequest
import com.crispy.tv.domain.browse.BrowseTypeItem
import com.crispy.tv.domain.browse.BrowseTypeResponse
import com.crispy.tv.domain.browse.mergeBrowsePage
import com.crispy.tv.domain.browse.planBrowseRequests
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class BrowseTitlesContractTest {
    @Test
    fun fixturesMatchBrowseTitlesPlanningRules() {
        val fixturePaths = ContractTestSupport.fixtureFiles("browse_titles")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one browse_titles fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("browse_titles", fixture.requireString("suite", path), "$caseId: wrong suite")
            assertEquals(1, fixture.requireInt("contract_version", path), "$caseId: wrong contract_version")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val combo = parseCombo(input.requireJsonObject("combo", path), path)
            val page = input.requireInt("page", path)
            val responses = parseResponses(input.requireJsonObject("responses", path), path)

            assertEquals(
                expected.requireJsonArray("requests", path).map { parseRequest(it.jsonObject, path) },
                planBrowseRequests(combo, page),
                "$caseId: requests",
            )

            assertEquals(
                BrowsePageResult(
                    itemIds = expected.requireJsonArray("items", path).toStringList(path),
                    hasMore = expected.requireBoolean("has_more", path),
                    nextPage = expected.optionalInt("next_page", path),
                ),
                mergeBrowsePage(combo = combo, page = page, responses = responses),
                "$caseId: merged page",
            )
        }
    }

    private fun parseCombo(json: JsonObject, path: Path): BrowseCombo {
        return BrowseCombo(
            type = json.requireString("type", path),
            genre = json.optionalString("genre", path),
            sort = json.requireString("sort", path),
        )
    }

    private fun parseResponses(json: JsonObject, path: Path): Map<String, BrowseTypeResponse> {
        return listOf("movie", "series")
            .filter { key -> json[key] != null }
            .associateWith { key ->
                val response = json.requireJsonObject(key, path)
                BrowseTypeResponse(
                    items = response.requireJsonArray("items", path).map { element ->
                        val item = element.jsonObject
                        BrowseTypeItem(
                            itemId = item.requireString("item_id", path),
                            type = item.requireString("type", path),
                        )
                    },
                    hasMore = response.requireBoolean("has_more", path),
                )
            }
    }

    private fun parseRequest(json: JsonObject, path: Path): BrowseRequest {
        return BrowseRequest(
            type = json.requireString("type", path),
            genre = json.optionalString("genre", path),
            sort = json.requireString("sort", path),
            page = json.requireInt("page", path),
        )
    }
}