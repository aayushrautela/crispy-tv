package com.crispy.tv.contracts

import com.crispy.tv.domain.account.normalizeAddonUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class AddonUrlNormalizationContractTest {
    @Test
    fun addonUrlNormalizationFixtures() {
        ContractTestSupport.fixtureFiles("addon_url_normalization").forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("addon_url_normalization", root.requireString("suite", path))
            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val raw = input.requireString("raw_url", path)
            val result = normalizeAddonUrl(raw)

            val expectedValid = expected.requireBoolean("is_valid", path)
            val expectedNormalized = expected.optionalString("normalized_url", path)

            when (result) {
                is com.crispy.tv.domain.account.AddonUrlResult.Valid -> {
                    assertEquals(true, expectedValid, "Expected invalid in ${path.toDisplayPath()}")
                    assertEquals(expectedNormalized, result.normalized, "Normalized mismatch in ${path.toDisplayPath()}")
                }
                is com.crispy.tv.domain.account.AddonUrlResult.Invalid -> {
                    assertEquals(false, expectedValid, "Expected valid in ${path.toDisplayPath()}")
                    assertEquals(null, expectedNormalized, "Expected null normalized in ${path.toDisplayPath()}")
                }
            }
        }
    }
}

