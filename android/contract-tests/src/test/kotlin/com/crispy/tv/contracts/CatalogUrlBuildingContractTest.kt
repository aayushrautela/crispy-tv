package com.crispy.tv.contracts

import com.crispy.tv.domain.catalog.CatalogFilter
import com.crispy.tv.domain.catalog.buildCatalogUrls
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class CatalogUrlBuildingContractTest {
    @Test
    fun fixturesMatchCatalogUrlBuildingRules() {
        val fixturePaths = ContractTestSupport.fixtureFiles("catalog_url_building")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one catalog_url_building fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("catalog_url_building", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val actual = buildCatalogUrls(
                baseUrl = input.requireString("base_url", path),
                encodedQuery = input.optionalString("encoded_query", path),
                mediaType = input.requireString("media_type", path),
                catalogId = input.requireString("catalog_id", path),
                skip = input.requireInt("skip", path),
                limit = input.requireInt("limit", path),
                filters = input.requireJsonArray("filters", path).map { parseFilter(it.jsonObject, path) },
            )

            assertEquals(expected.requireJsonArray("urls", path).toStringList(path), actual, "$caseId: urls")
        }
    }

    private fun parseFilter(json: JsonObject, path: Path): CatalogFilter {
        return CatalogFilter(
            key = json.requireString("key", path),
            value = json.requireString("value", path),
        )
    }
}
