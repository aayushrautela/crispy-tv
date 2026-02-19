package com.crispy.rewrite.contracts

import com.crispy.rewrite.domain.catalog.AddonSearchResult
import com.crispy.rewrite.domain.catalog.SearchMetaInput
import com.crispy.rewrite.domain.catalog.mergeSearchResults
import kotlinx.serialization.json.JsonObject
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
            val addonResults =
                input.requireJsonArray("addon_results", path).mapIndexed { index, element ->
                    val addon =
                        element as? JsonObject
                            ?: error("$caseId: input.addon_results[$index] must be object")

                    val metas =
                        addon.requireJsonArray("metas", path).mapIndexed { metaIndex, metaElement ->
                            val meta =
                                metaElement as? JsonObject
                                    ?: error("$caseId: input.addon_results[$index].metas[$metaIndex] must be object")
                            SearchMetaInput(
                                id = meta.requireString("id", path),
                                title = meta.requireString("title", path)
                            )
                        }

                    AddonSearchResult(
                        addonId = addon.requireString("addon_id", path),
                        metas = metas
                    )
                }

            val expected = fixture.requireJsonObject("expected", path)
            val expectedMerged =
                expected.requireJsonArray("merged", path).mapIndexed { index, element ->
                    val meta =
                        element as? JsonObject
                            ?: error("$caseId: expected.merged[$index] must be object")
                    Triple(
                        meta.requireString("id", path),
                        meta.requireString("title", path),
                        meta.requireString("addon_id", path)
                    )
                }

            val actualMerged =
                mergeSearchResults(
                    addonResults = addonResults,
                    preferredAddonId = input.optionalString("preferred_addon_id", path)
                ).map { meta ->
                    Triple(meta.id, meta.title, meta.addonId)
                }

            assertEquals(expectedMerged, actualMerged, "$caseId: merged")
        }
    }
}
