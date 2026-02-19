package com.crispy.rewrite.contracts

import com.crispy.rewrite.domain.metadata.AddonMetadataCandidate
import com.crispy.rewrite.domain.metadata.mergeAddonPrimaryMetadata
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataAddonPrimaryContractTest {
    @Test
    fun fixturesMatchDeterministicSourceSelection() {
        val fixturePaths = ContractTestSupport.fixtureFiles("metadata_addon_primary")
        assertTrue(fixturePaths.isNotEmpty(), "Expected metadata_addon_primary fixtures")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals(
                "metadata_addon_primary",
                fixture.requireString("suite", path),
                "$caseId: wrong suite"
            )

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val preferredAddonId = input.optionalString("preferred_addon_id", path)
            val addonResults =
                input
                    .requireJsonArray("addon_results", path)
                    .map { element ->
                        val entry = element.jsonObject
                        AddonMetadataCandidate(
                            addonId = entry.requireString("addon_id", path),
                            mediaId = entry.requireString("media_id", path),
                            title = entry.requireString("title", path)
                        )
                    }

            val actual = mergeAddonPrimaryMetadata(addonResults, preferredAddonId)
            assertEquals(expected.requireString("primary_id", path), actual.primaryId, "$caseId: primary_id")
            assertEquals(expected.requireString("title", path), actual.title, "$caseId: title")
            assertEquals(
                expected.requireJsonArray("sources", path).toStringList(path),
                actual.sources,
                "$caseId: sources"
            )
        }
    }
}
