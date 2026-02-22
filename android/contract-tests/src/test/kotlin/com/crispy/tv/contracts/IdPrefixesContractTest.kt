package com.crispy.tv.contracts

import com.crispy.tv.domain.metadata.formatIdForIdPrefixes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdPrefixesContractTest {
    @Test
    fun fixturesMatchNuvioIdPrefixFormatting() {
        val fixturePaths = ContractTestSupport.fixtureFiles("id_prefixes")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one id_prefixes fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("id_prefixes", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val actual =
                formatIdForIdPrefixes(
                    input = input.requireString("raw", path),
                    mediaType = input.requireString("media_type", path),
                    idPrefixes = input.requireJsonArray("id_prefixes", path).toStringList(path)
                )

            assertEquals(expected.optionalString("formatted_id", path), actual, "$caseId: formatted_id")
        }
    }
}
