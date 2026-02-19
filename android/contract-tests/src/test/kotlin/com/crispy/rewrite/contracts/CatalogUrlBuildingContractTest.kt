package com.crispy.rewrite.contracts

import com.crispy.rewrite.domain.catalog.CatalogFilter
import com.crispy.rewrite.domain.catalog.CatalogRequestInput
import com.crispy.rewrite.domain.catalog.buildCatalogRequestUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogUrlBuildingContractTest {
    @Test
    fun fixturesMatchCatalogUrlBuilder() {
        val fixturePaths = ContractTestSupport.fixtureFiles("catalog_url_building")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one catalog_url_building fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals(
                "catalog_url_building",
                fixture.requireString("suite", path),
                "$caseId: wrong suite"
            )

            val inputObject = fixture.requireJsonObject("input", path)
            val filters =
                inputObject.requireJsonArray("filters", path).mapIndexed { index, element ->
                    val filterObject =
                        element as? kotlinx.serialization.json.JsonObject
                            ?: error("$caseId: input.filters[$index] must be object")
                    CatalogFilter(
                        key = filterObject.requireString("key", path),
                        value = filterObject.requireString("value", path)
                    )
                }

            val input =
                CatalogRequestInput(
                    baseUrl = inputObject.requireString("base_url", path),
                    mediaType = inputObject.requireString("media_type", path),
                    catalogId = inputObject.requireString("catalog_id", path),
                    page = inputObject.requireInt("page", path),
                    pageSize = inputObject.requireInt("page_size", path),
                    filters = filters,
                    encodedAddonQuery = inputObject.optionalString("encoded_addon_query", path)
                )

            val actualUrls = buildCatalogRequestUrls(input)
            val expectedUrls =
                fixture
                    .requireJsonObject("expected", path)
                    .requireJsonArray("urls", path)
                    .toStringList(path)

            assertEquals(expectedUrls, actualUrls, "$caseId: urls")
        }
    }
}
